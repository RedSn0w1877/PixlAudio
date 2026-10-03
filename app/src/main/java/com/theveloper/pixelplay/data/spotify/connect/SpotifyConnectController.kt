package com.theveloper.pixelplay.data.spotify.connect

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Spotify Connect output (shared spec with the iOS port's `SpotifyConnectController`): plays
 * PixlAudio's queue on a Spotify Connect device through the Web API's Player endpoints. The device
 * streams from Spotify; PixlAudio sends the track URIs and stays the remote.
 *
 * While a session runs, MusicService swaps a [com.theveloper.pixelplay.data.service.player.SpotifyConnectSessionPlayer]
 * into its MediaSession (see [isAttached]): every controller — the app UI, the notification, the
 * lock screen, Wear, widgets, volume keys — then drives the device through the hooks below, and the
 * local ExoPlayer stays paused with its queue intact, so ending the session resumes on this phone
 * exactly where the device was.
 *
 * Threading: all state here is main-thread confined ([scope] runs on Main); networking, JSON, tag
 * reads and search run on IO inside [SpotifyConnectClient] and [SpotifyConnectResolver]. Polling
 * only exists during a session (≈1 s in the foreground, 5 s in the background, Retry-After
 * honoured), and observable state is written only when the reducer reports a change.
 */
@Singleton
class SpotifyConnectController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: SpotifyConnectClient,
    private val authManager: SpotifyAuthManager,
    private val spotifyDao: SpotifyDao,
    private val engineProvider: Lazy<DualPlayerEngine>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val resolver = SpotifyConnectResolver(
        storage = SpotifyConnectFileStorage(File(context.filesDir, CACHE_FILE_NAME)),
        search = { query -> client.searchTracks(query) },
        isrc = { track -> readIsrc(track) }
    )

    // ─── Published state ──────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(SpotifyConnectUiState())
    val uiState: StateFlow<SpotifyConnectUiState> = _uiState.asStateFlow()

    /** The remote session as last published (only on a reducer change); null while this phone plays. */
    private val _session = MutableStateFlow<SpotifyConnectSessionState?>(null)
    val session: StateFlow<SpotifyConnectSessionState?> = _session.asStateFlow()

    /** True while the session player must sit in the MediaSession. */
    private val _isAttached = MutableStateFlow(false)
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Connect's toasts (skipped songs once per resolution pass, takeovers, errors). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ─── Session internals (main thread) ──────────────────────────────────────────────────

    private var state: SpotifyConnectSessionState? = null
    /** The queue's song ids when [slots] was built, and what each resolved to (`slots[i]` ↔ queue[i]). */
    private var slotSongIds: List<String> = emptyList()
    private var slotItems: List<MediaItem> = emptyList()
    private var slots: ArrayList<SpotifyConnectSlot> = ArrayList()
    private val resolvedBySongId = HashMap<String, SpotifyConnectSlot>()
    /** The song ids of the queue the last window was sent from (`state.window.queueIndices` index into it). */
    private var sentSongIds: List<String> = emptyList()
    private var sessionGeneration = 0
    private var networkFailures = 0
    /** Background resolution found more after a short first window: send the longer one at the next track change. */
    private var extendAtNextTrack = false
    private var lastRepeatSent: String? = null
    /** The granted scopes a 403 "Insufficient client scope" was seen with (cleared once the login changes). */
    private var scopeDeniedFor: Set<String>? = null
    private var scopeDenied = false
    private var isForeground = false

    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var resolveJob: Job? = null
    private var resyncJob: Job? = null
    private var volumeJob: Job? = null
    private var commandJob = SupervisorJob()
    private val commandMutex = Mutex()

    private var suppressLocalEvents = 0

    init {
        scope.launch {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    isForeground = true
                    // Catch up at once: the device kept playing while the app was away.
                    if (state != null) startPolling(pollImmediately = true)
                }

                override fun onStop(owner: LifecycleOwner) {
                    isForeground = false
                }
            })
        }
        // Availability follows the login and its granted scopes. Opening the encrypted prefs is
        // slow, so the flows are first touched on IO.
        scope.launch(Dispatchers.IO) {
            combine(authManager.isLoggedIn, authManager.grantedScopes) { loggedIn, granted -> loggedIn to granted }
                .collect { (loggedIn, granted) ->
                    val hasClient = authManager.hasClientId()
                    withContext(Dispatchers.Main) { applyAvailability(loggedIn && hasClient, granted) }
                }
        }
    }

    private fun applyAvailability(linked: Boolean, granted: Set<String>?) {
        if (scopeDenied && scopeDeniedFor != granted) {
            scopeDenied = false
            scopeDeniedFor = null
        }
        val availability = when {
            !linked -> SpotifyConnectAvailability.HIDDEN
            scopeDenied || SpotifyConnect.missingScopes(granted).isNotEmpty() -> SpotifyConnectAvailability.NEEDS_RECONNECT
            else -> SpotifyConnectAvailability.READY
        }
        _uiState.update {
            if (it.availability == availability) it
            else it.copy(
                availability = availability,
                devices = if (availability == SpotifyConnectAvailability.READY) it.devices else kotlinx.collections.immutable.persistentListOf()
            )
        }
        if (!linked && state != null) endSession("Spotify was disconnected")
    }

    private fun markScopeDenied() {
        scopeDenied = true
        scopeDeniedFor = authManager.grantedScopes.value
        _uiState.update { it.copy(availability = SpotifyConnectAvailability.NEEDS_RECONNECT) }
    }

    private fun toast(message: String) {
        if (_messages.subscriptionCount.value > 0) {
            _messages.tryEmit(message)
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    // ─── Devices ──────────────────────────────────────────────────────────────────────────

    /** The sheet opened or the refresh action ran: list the devices again. */
    fun refreshDevices() {
        scope.launch { refreshDevicesNow() }
    }

    private suspend fun refreshDevicesNow() {
        if (_uiState.value.availability != SpotifyConnectAvailability.READY) return
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        try {
            when (val result = client.devices()) {
                is ConnectResult.Ok -> {
                    val list = SpotifyConnectDevice.sortedForDisplay(result.value)
                    _uiState.update { ui ->
                        val active = ui.active?.let { active ->
                            list.firstOrNull { it.deviceId == active.deviceId }?.let { device ->
                                active.copy(
                                    name = device.name,
                                    type = device.type,
                                    supportsVolume = device.supportsVolume,
                                    volumePercent = device.volumePercent ?: active.volumePercent
                                )
                            } ?: active
                        }
                        val devices = if (ui.devices == list) ui.devices else list.toImmutableList()
                        ui.copy(devices = devices, deviceListError = null, active = active, hasLoadedDevices = true)
                    }
                }
                is ConnectResult.Err -> {
                    if (result.error == SpotifyConnectError.MissingScope) markScopeDenied()
                    _uiState.update { it.copy(deviceListError = result.error.userMessage, hasLoadedDevices = true) }
                }
            }
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun markDeviceActive(deviceId: String) {
        _uiState.update { ui ->
            val updated = ui.devices.map { it.copy(isActive = it.deviceId == deviceId) }
            ui.copy(devices = SpotifyConnectDevice.sortedForDisplay(updated).toImmutableList())
        }
    }

    // ─── Local queue (the engine's player; main thread) ───────────────────────────────────

    private val engine: DualPlayerEngine get() = engineProvider.get()

    private fun localPlayer(): Player? = engine.masterPlayerIfAlive

    /** PixlAudio's queue, absolute (the engine may hold only a window of it after a crossfade). */
    private fun queueItems(player: Player): List<MediaItem> {
        if (engine.isUsingWindowedQueue()) return engine.getFullQueue()
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val window = Timeline.Window()
        return List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem }
    }

    private fun localQueueIndex(player: Player): Int =
        if (engine.isUsingWindowedQueue()) engine.getCurrentAbsoluteIndex() else player.currentMediaItemIndex

    /** The local timeline index of absolute queue entry [queueIndex], or null when it isn't loaded. */
    private fun localIndexFor(player: Player, queueIndex: Int): Int? {
        if (!engine.isUsingWindowedQueue()) return queueIndex.takeIf { it in 0 until player.mediaItemCount }
        val offset = engine.getCurrentAbsoluteIndex() - player.currentMediaItemIndex
        val candidate = queueIndex - offset
        return candidate.takeIf { it in 0 until player.mediaItemCount }
    }

    /** Absolute queue index of local timeline index [localIndex]. */
    fun queueIndexForLocal(localIndex: Int): Int {
        val player = localPlayer() ?: return localIndex
        if (!engine.isUsingWindowedQueue()) return localIndex
        return engine.getCurrentAbsoluteIndex() - player.currentMediaItemIndex + localIndex
    }

    private inline fun <T> suppressed(block: () -> T): T {
        suppressLocalEvents++
        try {
            return block()
        } finally {
            suppressLocalEvents--
        }
    }

    /** True while this controller itself moves the local player (its events are not user actions). */
    val isMovingLocalPlayer: Boolean get() = suppressLocalEvents > 0

    /** True while the local player's attempts to play must be redirected to the device. */
    val isRedirectingLocal: Boolean get() = state != null

    /** Moves the local model to [queueIndex] (loaded, paused) when the device advances. */
    private fun remoteMoved(queueIndex: Int, positionMs: Long = 0L) {
        val player = localPlayer() ?: return
        val target = localIndexFor(player, queueIndex) ?: run {
            Timber.tag(TAG).w("Queue entry %d isn't loaded in the local player; not moving it", queueIndex)
            return
        }
        suppressed {
            if (target != player.currentMediaItemIndex) {
                player.seekTo(target, positionMs.coerceAtLeast(0L))
            }
            if (player.playWhenReady) player.pause()
        }
    }

    /** Rebuilds [slots] for the current queue from what is known; entries not known yet are Pending. */
    private fun rebuildSlots() {
        val player = localPlayer() ?: return
        val items = queueItems(player)
        val ids = items.map { it.mediaId }
        if (ids == slotSongIds) {
            slotItems = items
            return
        }
        slotSongIds = ids
        slotItems = items
        slots = items.mapTo(ArrayList(items.size)) { item ->
            resolvedBySongId[item.mediaId]
                ?: ConnectTrack.from(item).directUri?.let { SpotifyConnectSlot.Uri(it) }
                ?: SpotifyConnectSlot.Pending
        }
    }

    private fun setSlot(slot: SpotifyConnectSlot, index: Int) {
        if (index !in slots.indices) return
        slots[index] = slot
        if (slot != SpotifyConnectSlot.Pending) resolvedBySongId[slotSongIds[index]] = slot
    }

    /** Resolves one queue entry (cache first, then search); Pending when the lookup failed. */
    private suspend fun resolve(index: Int): SpotifyConnectSlot {
        if (index !in slots.indices) return SpotifyConnectSlot.Skipped
        if (slots[index] != SpotifyConnectSlot.Pending) return slots[index]
        val item = slotItems.getOrNull(index) ?: return SpotifyConnectSlot.Pending
        val track = ConnectTrack.from(item)
        val slot = withContext(Dispatchers.IO) { resolver.resolve(track) }
        // The queue may have changed while the search ran.
        if (index in slotSongIds.indices && slotSongIds[index] == track.songId) setSlot(slot, index)
        return slot
    }

    /**
     * Resolves from [start] until it holds the first playable entry plus [EAGER_LOOKAHEAD] more (or
     * the lookup budget is spent): enough to start without waiting for the whole queue.
     */
    private suspend fun resolveAhead(start: Int) {
        var found = 0
        var lookups = 0
        var index = start
        while (index < slots.size && found <= EAGER_LOOKAHEAD && lookups < EAGER_LOOKUP_BUDGET) {
            if (slots[index] == SpotifyConnectSlot.Pending) {
                lookups++
                resolve(index)
            }
            when (slots.getOrNull(index)) {
                is SpotifyConnectSlot.Uri -> found++
                SpotifyConnectSlot.Skipped -> Unit
                SpotifyConnectSlot.Pending, null -> return // a failed lookup: order must hold, stop here
            }
            index++
        }
    }

    /** Resolves the rest of the window in the background, then shows the "N songs … skipped" toast once. */
    private fun startBackgroundResolve(first: Int, toastFrom: Int) {
        resolveJob?.cancel()
        val generation = sessionGeneration
        resolveJob = scope.launch {
            var index = first
            var uris = 0
            val end = minOf(slots.size, first + BACKGROUND_SCAN_LIMIT)
            while (index < end && uris < SpotifyConnect.MAX_URIS_PER_PLAY && isActive && generation == sessionGeneration) {
                val slot = try {
                    resolve(index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
                if (slot == SpotifyConnectSlot.Pending) break
                if (slot is SpotifyConnectSlot.Uri) uris++
                index++
            }
            withContext(Dispatchers.IO) { resolver.flush() }
            if (generation != sessionGeneration) return@launch
            val current = state ?: return@launch
            SpotifyConnectWindow.skippedMessage(SpotifyConnectWindow.skippedCount(slots, toastFrom, index))?.let(::toast)
            // A longer window is now possible: send it when the next track starts (no audible jump mid-song).
            current.queueIndex?.let { at ->
                val longer = SpotifyConnectWindow.make(slots, at)
                if (longer.count > current.window.count - current.windowPosition) extendAtNextTrack = true
            }
            state = current.copy(hasMoreAfterWindow = SpotifyConnectWindow.hasMore(current.window, slots))
        }
    }

    // ─── Starting a session ───────────────────────────────────────────────────────────────

    /** Plays PixlAudio's queue on [device], from the current entry and position. */
    fun connect(device: SpotifyConnectDevice) {
        val ui = _uiState.value
        if (ui.availability != SpotifyConnectAvailability.READY || ui.connectingDeviceId != null) return
        val deviceId = device.deviceId ?: return
        if (!device.isControllable) {
            toast(SpotifyConnectError.DeviceRestricted.userMessage)
            return
        }
        if (ui.active?.deviceId == deviceId) return
        val player = localPlayer()
        if (player == null || player.mediaItemCount == 0) {
            toast("Play something first, then choose a device")
            return
        }
        _uiState.update { it.copy(connectingDeviceId = deviceId) }
        connectJob = scope.launch {
            try {
                performConnect(device, deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Connect failed")
                toast(SpotifyConnectError.Network(e.message).userMessage)
            } finally {
                _uiState.update { it.copy(connectingDeviceId = null) }
            }
        }
    }

    private suspend fun performConnect(device: SpotifyConnectDevice, deviceId: String) {
        val player = localPlayer() ?: return
        val previous = state
        // Switching devices mid-session starts from where the old device is, not the paused phone.
        val start = previous?.queueIndex ?: localQueueIndex(player)
        val position = previous?.positionAt(nowMs()) ?: player.currentPosition.coerceAtLeast(0L)
        rebuildSlots()
        resolveAhead(start)
        val window = SpotifyConnectWindow.make(slots, start)
        val first = window.queueIndices.firstOrNull()
        if (first == null) {
            val allSkipped = start < slots.size && (start until slots.size).all { slots[it] == SpotifyConnectSlot.Skipped }
            toast(if (allSkipped) "None of these songs are on Spotify" else "Couldn't find these songs on Spotify")
            return
        }
        val startPosition = if (first == start) position else 0L
        val started = client.start(deviceId, device.isActive, window.uris, startPosition)
        if (started is ConnectResult.Err) {
            report(started.error)
            return
        }
        val now = nowMs()
        val duration = slotItems.getOrNull(first)?.let { ConnectTrack.from(it).durationMs } ?: 0L
        val initial = SpotifyConnectSessionState(
            deviceId = deviceId,
            deviceName = device.name,
            deviceType = device.type,
            window = window,
            anchorMs = now,
            volumePercent = device.volumePercent,
            supportsVolume = device.supportsVolume
        )
        val sessionState = SpotifyConnectReducer.sent(
            window, startPosition, duration, SpotifyConnectWindow.hasMore(window, slots), initial, now,
            grace = SpotifyConnectReducer.START_GRACE_MS
        )
        stopTasks(keepConnect = true)
        sessionGeneration++
        state = sessionState
        sentSongIds = slotSongIds
        networkFailures = 0
        extendAtNextTrack = false
        // The device plays now: the phone pauses, keeping its queue.
        suppressed { if (player.playWhenReady) player.pause() }
        if (first != localQueueIndex(player)) remoteMoved(first, 0L)
        _uiState.update {
            it.copy(
                active = SpotifyConnectActiveDevice(deviceId, device.name, device.type, device.supportsVolume, device.volumePercent),
                isRemotePlaying = true
            )
        }
        _session.value = sessionState
        _isAttached.value = true
        // PixlAudio's queue is the order: Spotify's own shuffle stays off; repeat-one maps to `track`.
        lastRepeatSent = null
        enqueue { client.setShuffle(deviceId, enabled = false) }
        remoteRepeatModeChanged(player.repeatMode)
        startPolling()
        startBackgroundResolve(first, toastFrom = start)
        markDeviceActive(deviceId)
    }

    // ─── Polling ──────────────────────────────────────────────────────────────────────────

    private fun startPolling(pollImmediately: Boolean = false) {
        pollJob?.cancel()
        val generation = sessionGeneration
        pollJob = scope.launch {
            if (pollImmediately) pollOnce()
            while (isActive && generation == sessionGeneration && state != null) {
                val interval = maxOf(if (isForeground) FOREGROUND_POLL_MS else BACKGROUND_POLL_MS, client.retryAfterRemainingMs)
                delay(interval)
                if (generation != sessionGeneration) return@launch
                pollOnce()
            }
        }
    }

    private suspend fun pollOnce() {
        if (state == null) return
        val polled = when (val result = client.playbackState()) {
            is ConnectResult.Ok -> {
                networkFailures = 0
                result.value
            }
            is ConnectResult.Err -> {
                when (val error = result.error) {
                    is SpotifyConnectError.RateLimited -> Unit
                    is SpotifyConnectError.Network, is SpotifyConnectError.Unavailable, is SpotifyConnectError.Failed -> {
                        networkFailures++
                        if (networkFailures >= MAX_NETWORK_FAILURES) endSession("Lost the connection to Spotify")
                    }
                    else -> {
                        if (error == SpotifyConnectError.MissingScope) markScopeDenied()
                        endSession(error.userMessage)
                    }
                }
                return
            }
        }
        val current = state ?: return
        val reduction = SpotifyConnectReducer.apply(polled, current, nowMs())
        state = reduction.state
        when (val change = reduction.outcome.change) {
            SpotifyConnectPollOutcome.Change.None -> Unit
            SpotifyConnectPollOutcome.Change.Updated -> publish(reduction.state)
            is SpotifyConnectPollOutcome.Change.TrackChanged -> {
                remoteMoved(change.queueIndex)
                publish(reduction.state)
                if (extendAtNextTrack) {
                    extendAtNextTrack = false
                    resendFromCurrent()
                    return
                }
            }
            is SpotifyConnectPollOutcome.Change.TakenOver -> {
                endSession(change.takeover.message(reduction.state.deviceName))
                return
            }
            SpotifyConnectPollOutcome.Change.ReachedEnd -> {
                reachedEnd()
                return
            }
        }
        if (reduction.outcome.needsNextWindow) resendFromCurrent()
    }

    /** Pushes the remote state to the session player and the UI (only on a reducer change). */
    private fun publish(newState: SpotifyConnectSessionState) {
        _session.value = newState
        _uiState.update { ui ->
            val active = ui.active?.let {
                if (it.volumePercent != newState.volumePercent || it.supportsVolume != newState.supportsVolume) {
                    it.copy(volumePercent = newState.volumePercent, supportsVolume = newState.supportsVolume)
                } else it
            }
            if (active == ui.active && ui.isRemotePlaying == newState.isPlaying) ui
            else ui.copy(active = active, isRemotePlaying = newState.isPlaying)
        }
    }

    /**
     * The device finished PixlAudio's last entry: repeat-all starts over, else the device is paused
     * (its own autoplay would play something else) and the player shows the last song, paused.
     */
    private fun reachedEnd() {
        val current = state ?: return
        val repeatAll = localPlayer()?.repeatMode == Player.REPEAT_MODE_ALL
        if (repeatAll) {
            SpotifyConnectReducer.firstPlayable(slots, after = -1)?.let {
                send(it)
                return
            }
        }
        val paused = SpotifyConnectReducer.setPlaying(false, current, nowMs()).copy(progressMs = 0L)
        state = paused
        publish(paused)
        val deviceId = paused.deviceId
        enqueue { client.pause(deviceId) }
    }

    // ─── Sending windows ──────────────────────────────────────────────────────────────────

    /** Sends the window starting at the entry the device plays, keeping its position (queue edits, window extension). */
    private fun resendFromCurrent() {
        val current = state ?: return
        val player = localPlayer() ?: return
        rebuildSlots()
        val playingId = current.queueIndex?.let { sentSongIds.getOrNull(it) }
        val index = current.queueIndex
        val from = if (index != null && playingId != null && slotSongIds.getOrNull(index) == playingId) {
            index // the queue didn't move under it
        } else {
            localQueueIndex(player) // an edit: the local queue already holds the new order
        }
        send(from, keepPosition = slotSongIds.getOrNull(from) == playingId)
    }

    /**
     * `PUT play` with the window from [index] (resolving what it needs first), at the device's current
     * position when [keepPosition] (read when the request goes out), else from the start. A device
     * that was paused stays paused.
     */
    private fun send(index: Int, keepPosition: Boolean = false) {
        if (state == null) return
        val generation = sessionGeneration
        enqueue {
            if (generation != sessionGeneration) return@enqueue ConnectResult.Ok(Unit)
            val deviceId = state?.deviceId ?: return@enqueue ConnectResult.Ok(Unit)
            val wasPlaying = state?.isPlaying ?: true
            rebuildSlots()
            resolveAhead(index)
            val window = SpotifyConnectWindow.make(slots, index)
            val first = window.queueIndices.firstOrNull()
            if (first == null) {
                toast("This song isn't on Spotify")
                return@enqueue ConnectResult.Ok(Unit)
            }
            val position = if (keepPosition && first == index) (state?.positionAt(nowMs()) ?: 0L) else 0L
            val played = client.play(deviceId, window.uris, position)
            if (played is ConnectResult.Err) return@enqueue played
            val current = state
            if (generation != sessionGeneration || current == null) return@enqueue played
            val duration = slotItems.getOrNull(first)?.let { ConnectTrack.from(it).durationMs } ?: 0L
            var sent = SpotifyConnectReducer.sent(
                window, position, duration, SpotifyConnectWindow.hasMore(window, slots), current, nowMs()
            )
            sentSongIds = slotSongIds
            remoteMoved(first)
            if (!wasPlaying && keepPosition) {
                // A queue edit while the device was paused: re-sending must not start it.
                sent = SpotifyConnectReducer.setPlaying(false, sent, nowMs())
                state = sent
                publish(sent)
                client.pause(deviceId)
            } else {
                state = sent
                publish(sent)
            }
            if (window.count < SpotifyConnect.MAX_URIS_PER_PLAY && SpotifyConnectWindow.hasMore(window, slots)) {
                startBackgroundResolve(first, toastFrom = first)
            }
            played
        }
    }

    /** Runs commands one after another (the Web API doesn't order concurrent player calls); failures become toasts. */
    private fun enqueue(work: suspend () -> ConnectResult<*>) {
        scope.launch(commandJob) {
            commandMutex.withLock {
                val result = try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Connect command failed")
                    ConnectResult.Err(SpotifyConnectError.Network(e.message))
                }
                if (result is ConnectResult.Err) report(result.error)
            }
        }
    }

    /** A failed call: a toast; the errors that make the session impossible end it. */
    private fun report(error: SpotifyConnectError) {
        if (error == SpotifyConnectError.MissingScope) markScopeDenied()
        if (error.endsSession && state != null) endSession(error.userMessage) else toast(error.userMessage)
    }

    // ─── Remote transport (called by the session player, main thread) ─────────────────────

    /** The local player's current entry is another song than the device's (a new queue or a pick). */
    private fun localDiffersFromRemote(): Boolean {
        val current = state ?: return false
        val player = localPlayer() ?: return false
        val playingId = current.queueIndex?.let { sentSongIds.getOrNull(it) }
        return player.currentMediaItem?.mediaId != playingId
    }

    fun remotePlay() {
        val current = state ?: return
        if (localDiffersFromRemote()) {
            resyncJob?.cancel()
            localPlayer()?.let { send(localQueueIndex(it)) }
            return
        }
        val playing = SpotifyConnectReducer.setPlaying(true, current, nowMs())
        state = playing
        publish(playing)
        val deviceId = playing.deviceId
        enqueue { client.resume(deviceId) }
    }

    fun remotePause() {
        val current = state ?: return
        val paused = SpotifyConnectReducer.setPlaying(false, current, nowMs())
        state = paused
        publish(paused)
        val deviceId = paused.deviceId
        enqueue { client.pause(deviceId) }
    }

    fun remoteSeek(positionMs: Long) {
        val current = state ?: return
        val sought = SpotifyConnectReducer.seek(positionMs, current, nowMs())
        state = sought
        publish(sought)
        val deviceId = sought.deviceId
        enqueue { client.seek(deviceId, positionMs) }
    }

    fun remoteSkipToNext() {
        val current = state ?: return
        val player = localPlayer() ?: return
        rebuildSlots()
        when (val skip = SpotifyConnectReducer.next(current, slots, repeatAll = player.repeatMode == Player.REPEAT_MODE_ALL)) {
            SpotifyConnectReducer.Skip.Next -> {
                val nextIndex = current.window.queueIndices[current.windowPosition + 1]
                val duration = slotItems.getOrNull(nextIndex)?.let { ConnectTrack.from(it).durationMs } ?: 0L
                val advanced = SpotifyConnectReducer.advance(current, duration, nowMs())
                state = advanced
                remoteMoved(nextIndex)
                publish(advanced)
                val deviceId = advanced.deviceId
                enqueue { client.next(deviceId) }
            }
            is SpotifyConnectReducer.Skip.Play -> send(skip.fromQueueIndex)
            SpotifyConnectReducer.Skip.Restart -> remoteSeek(0L)
            SpotifyConnectReducer.Skip.None -> Unit
        }
    }

    fun remoteSkipToPrevious() {
        val current = state ?: return
        rebuildSlots()
        when (val skip = SpotifyConnectReducer.previous(current, slots, nowMs())) {
            is SpotifyConnectReducer.Skip.Play -> sendPrevious(skip.fromQueueIndex)
            SpotifyConnectReducer.Skip.Restart -> remoteSeek(0L)
            SpotifyConnectReducer.Skip.Next, SpotifyConnectReducer.Skip.None -> Unit
        }
    }

    /** The previous entry that is on Spotify, resolving backwards as needed. */
    private fun sendPrevious(from: Int) {
        val generation = sessionGeneration
        enqueue {
            if (generation != sessionGeneration) return@enqueue ConnectResult.Ok(Unit)
            var candidate = from
            while (candidate >= 0) {
                if (resolve(candidate) is SpotifyConnectSlot.Uri) break
                candidate--
            }
            if (candidate < 0) remoteSeek(0L) else send(candidate)
            ConnectResult.Ok(Unit)
        }
    }

    /** A specific queue entry was picked. */
    fun remoteSkip(toQueueIndex: Int) {
        if (state == null) return
        resyncJob?.cancel()
        send(toQueueIndex)
    }

    fun remoteRepeatModeChanged(mode: Int) {
        val current = state ?: return
        val remote = SpotifyConnectReducer.remoteRepeat(repeatOne = mode == Player.REPEAT_MODE_ONE)
        if (remote == lastRepeatSent) return
        lastRepeatSent = remote
        val deviceId = current.deviceId
        enqueue { client.setRepeat(deviceId, remote) }
    }

    /** Queue edits, shuffle, a new queue: coalesced, then re-sent when what the device will play changed. */
    fun remoteQueueChanged() {
        if (state == null) return
        resyncJob?.cancel()
        resyncJob = scope.launch {
            delay(RESYNC_DEBOUNCE_MS)
            resync()
        }
    }

    private fun resync() {
        val current = state ?: return
        val player = localPlayer() ?: return
        val localIndex = localQueueIndex(player)
        val playingSongId = current.queueIndex?.let { sentSongIds.getOrNull(it) }
        rebuildSlots()
        val candidate = SpotifyConnectWindow.make(slots, localIndex)
        val remaining = current.window.uris.drop(current.windowPosition)
        // Same song still current and the device's remaining list unchanged (or only extended at
        // the end): just re-index, nothing is sent — no audible jump for "Add to queue". Entries the
        // device already played keep their song's new index (-1 when it left the queue).
        if (slotSongIds.getOrNull(localIndex) == playingSongId && remaining.isNotEmpty() &&
            candidate.uris.size >= remaining.size && candidate.uris.subList(0, remaining.size) == remaining
        ) {
            val played = current.window.queueIndices.take(current.windowPosition).map { old ->
                val id = sentSongIds.getOrNull(old) ?: return@map -1
                slotSongIds.indexOf(id)
            }
            val window = SpotifyConnectWindow(current.window.uris, played + candidate.queueIndices.take(remaining.size))
            state = current.copy(window = window, hasMoreAfterWindow = SpotifyConnectWindow.hasMore(window, slots))
            sentSongIds = slotSongIds
            return
        }
        resendFromCurrent()
    }

    /**
     * The local player tried to play on its own (the app plays songs straight on the engine, not
     * through the MediaSession): keep it paused and play that entry on the device instead.
     */
    fun onLocalPlayAttempt() {
        val player = localPlayer() ?: return
        if (state == null) return
        suppressed { player.pause() }
        if (localDiffersFromRemote()) {
            resyncJob?.cancel()
            send(localQueueIndex(player))
        } else if (state?.isPlaying != true) {
            remotePlay()
        }
    }

    // ─── Volume ───────────────────────────────────────────────────────────────────────────

    /** The device volume (debounced; only when the device supports it). */
    fun setVolume(percent: Int) {
        val active = _uiState.value.active ?: return
        if (!active.supportsVolume) {
            toast(SpotifyConnectError.VolumeNotSupported.userMessage)
            return
        }
        val clamped = percent.coerceIn(0, 100)
        if (active.volumePercent != clamped) {
            _uiState.update { it.copy(active = it.active?.copy(volumePercent = clamped)) }
        }
        state?.let { current ->
            val updated = current.copy(volumePercent = clamped)
            state = updated
            _session.value = updated
        }
        volumeJob?.cancel()
        val deviceId = active.deviceId
        volumeJob = scope.launch {
            delay(VOLUME_DEBOUNCE_MS)
            enqueue { client.setVolume(deviceId, clamped) }
        }
    }

    fun adjustVolume(delta: Int) {
        val active = _uiState.value.active ?: return
        setVolume((active.volumePercent ?: 50) + delta)
    }

    // ─── Ending a session ─────────────────────────────────────────────────────────────────

    /** "Stop playing on <device>": read where the device is, pause it, and carry on on this phone from there. */
    fun disconnect() {
        val current = state ?: return
        if (_uiState.value.isStopping) return
        val generation = sessionGeneration
        _uiState.update { it.copy(isStopping = true) }
        stopTasks()
        scope.launch {
            var index = current.queueIndex
            var position = current.positionAt(nowMs())
            var playing = current.isPlaying
            val remote = client.playbackState().valueOrNull()
            if (remote != null && remote.device?.deviceId == current.deviceId) {
                remote.itemUri?.let { uri -> current.window.positionOf(uri, current.windowPosition) }?.let { at ->
                    index = current.window.queueIndices[at]
                    position = remote.progressMs ?: position
                    playing = remote.isPlaying
                }
            }
            client.pause(current.deviceId)
            _uiState.update { it.copy(isStopping = false) }
            if (generation != sessionGeneration) return@launch
            finish(index, position, playLocally = playing, message = null)
        }
    }

    /**
     * Ends the session without touching the device (taken over, signed out, lost): this phone stays
     * paused on the last known song and position.
     */
    private fun endSession(message: String?) {
        val current = state ?: return
        stopTasks()
        finish(current.queueIndex, current.positionAt(nowMs()), playLocally = false, message = message)
    }

    /** MusicService is going away: pause the device and drop the session (nothing to resume on). */
    fun onServiceStopping() {
        val current = state ?: return
        stopTasks()
        sessionGeneration++
        state = null
        _session.value = null
        _isAttached.value = false
        _uiState.update { it.copy(active = null, isRemotePlaying = false, isStopping = false) }
        val deviceId = current.deviceId
        scope.launch { client.pause(deviceId) }
    }

    private fun finish(index: Int?, positionMs: Long, playLocally: Boolean, message: String?) {
        state = null
        sessionGeneration++
        _session.value = null
        _uiState.update { it.copy(active = null, isRemotePlaying = false) }
        val player = localPlayer()
        if (player != null) {
            val target = index?.takeIf { it >= 0 }?.let { localIndexFor(player, it) }
            suppressed {
                if (target != null) player.seekTo(target, positionMs.coerceAtLeast(0L))
                else player.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
        // The service swaps the local player back in; the redirect is already off (state == null).
        _isAttached.value = false
        if (playLocally && player != null) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        message?.let(::toast)
        refreshDevices()
    }

    private fun stopTasks(keepConnect: Boolean = false) {
        pollJob?.cancel()
        resolveJob?.cancel()
        resyncJob?.cancel()
        volumeJob?.cancel()
        commandJob.cancel()
        commandJob = SupervisorJob()
        if (!keepConnect) connectJob?.cancel()
        pollJob = null
        resolveJob = null
        resyncJob = null
        volumeJob = null
    }

    // ─── ISRC lookup (only on a cache miss) ───────────────────────────────────────────────

    private suspend fun readIsrc(track: ConnectTrack): String? = withContext(Dispatchers.IO) {
        track.spotifyId?.let { id ->
            runCatching { spotifyDao.getSongBySpotifyId(id)?.isrc }.getOrNull()?.takeIf { it.isNotBlank() }
        }?.let { return@withContext it }
        val path = track.filePath ?: return@withContext null
        try {
            val descriptor: ParcelFileDescriptor? = when {
                path.startsWith("content://") -> context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                File(path).isFile -> ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                else -> null
            }
            descriptor?.use { fd ->
                TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)
                    ?.propertyMap?.get("ISRC")?.firstOrNull()?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Couldn't read the ISRC tag")
            null
        }
    }

    companion object {
        private const val TAG = "SpotifyConnect"
        private const val CACHE_FILE_NAME = "spotify_connect_lookups.json"
        const val FOREGROUND_POLL_MS = 1_000L
        const val BACKGROUND_POLL_MS = 5_000L
        /** Entries resolved before the first `play` (the current one plus a few after it). */
        const val EAGER_LOOKAHEAD = 4
        const val EAGER_LOOKUP_BUDGET = 12
        /** How far background resolution looks past the current entry. */
        const val BACKGROUND_SCAN_LIMIT = 200
        /** Consecutive failed polls (no network, 5xx) before the session is given up. */
        const val MAX_NETWORK_FAILURES = 15
        const val RESYNC_DEBOUNCE_MS = 350L
        const val VOLUME_DEBOUNCE_MS = 250L
        /** Volume key step, in percent. */
        const val VOLUME_STEP = 5
    }
}
