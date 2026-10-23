package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.cloudstudio.CloudSingStart
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.worker.CloudPhase
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJob
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJobs
import com.theveloper.pixelplay.data.worker.RenderJobState
import com.theveloper.pixelplay.data.worker.RenderSource
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.sing.SingOnlineProbe
import com.theveloper.pixelplay.presentation.lyrics.sing.SingRoute
import com.theveloper.pixelplay.presentation.lyrics.sing.VocalGapFinder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Which words the Sing button shows while it works. */
enum class SingPhase {
    IDLE,

    /** The instrumental is being rendered on this phone ("Removing vocals 40%"). */
    ON_DEVICE,

    /** The cloud is preparing it ("Preparing…"). */
    CLOUD,

    /** The cloud job waits for Wi-Fi ("Waiting for Wi-Fi"). */
    CLOUD_WIFI,

    /** The instrumental is ready and the player is switching to it; the button still says "Sing". */
    SWITCHING,
}

/** The lyrics toolbar's Sing button. */
@Immutable
data class SingUi(
    /** Vocals are off: the song plays through its studio instrumental. */
    val active: Boolean = false,
    /** False while Cast or a Spotify Connect speaker plays: the instrumental swap is local only. */
    val enabled: Boolean = true,
    /** The instrumental is being prepared (the person's job, or quiet automatic work while it runs). */
    val rendering: Boolean = false,
    /** 0..1 while the render runs; null while it is queued (or not rendering). */
    val progress: Float? = null,
    /** Which words go with [rendering]. */
    val phase: SingPhase = SingPhase.IDLE,
)

/** What the person is waiting for after a Sing tap. */
enum class SingWait {
    NONE,

    /** The tap started (or joined) a cloud job; the song switches when its instrumental lands. */
    CLOUD,

    /** The instrumental was already on this phone (or just rendered here): the player is switching to it. */
    SWITCHING,

    /** The cloud's instrumental landed; the player waits for a gap between sung words, then fades. */
    SWITCHING_AFTER_CLOUD,
}

/** Pure: what the Sing button shows for this state. */
fun singUi(
    available: Boolean,
    active: Boolean,
    job: InstrumentalRenderJob?,
    remoteActive: Boolean,
    wait: SingWait = SingWait.NONE,
): SingUi {
    val enabled = !remoteActive
    if (active) return SingUi(active = true, enabled = enabled)
    when (wait) {
        SingWait.SWITCHING_AFTER_CLOUD ->
            return SingUi(enabled = enabled, rendering = true, progress = 1f, phase = SingPhase.CLOUD)
        SingWait.SWITCHING -> return SingUi(enabled = enabled, phase = SingPhase.SWITCHING)
        SingWait.CLOUD -> if (!available) {
            // The person asked for the cloud's version: show its steps, with the job's fill once it exists.
            val cloudJob = job?.takeIf { it.source == RenderSource.CLOUD && !it.state.isFinished }
            return SingUi(
                enabled = enabled,
                rendering = true,
                progress = cloudJob?.percent?.coerceIn(0, 100)?.div(100f),
                phase = if (cloudJob?.phase == CloudPhase.WAITING_FOR_WIFI) SingPhase.CLOUD_WIFI else SingPhase.CLOUD,
            )
        }
        SingWait.NONE -> Unit
    }
    // Quiet automatic work only counts once it actually runs: Automatic Studio queues songs that
    // wait for idle playback and a charged battery, and "Removing vocals…" on every queued song
    // would claim work that isn't happening (a tap starts the person's own render instead).
    // A cloud job only shows while the person waits for it (above): it is not a render on this phone.
    val rendering = job != null && job.source == RenderSource.ON_DEVICE && !job.state.isFinished &&
        !available && (!job.automatic || job.state == RenderJobState.RUNNING)
    return SingUi(
        active = false,
        enabled = enabled,
        rendering = rendering,
        progress = if (rendering && job?.state == RenderJobState.RUNNING) job.percent.coerceIn(0, 100) / 100f else null,
        phase = if (rendering) SingPhase.ON_DEVICE else SingPhase.IDLE,
    )
}

/** How the player places the fade to a cloud instrumental: start at this position, fade this long. */
data class CrossfadePlan(val startAtPositionMs: Long?, val fadeMs: Int)

/** What the player should do for a Sing tap; [PlayerViewModel] sends it to MusicService. */
sealed interface SingRequest {
    /** Switch to the song's instrumental; [plan] places the fade at a word gap (null: at once, at the current position). */
    data class Instrumental(val path: String, val plan: CrossfadePlan? = null) : SingRequest
    data object Original : SingRequest
}

/**
 * Sing on the lyrics page: vocals off / on through the song's studio instrumental. It replaces the old
 * floating instrumental pill and owns the instrumental state that used to sit in [PlayerViewModel]
 * (`studioInstrumentalActive` / `Available`).
 *
 * A tap on Sing, in order:
 * 1. An instrumental is already on this phone: the song switches to it now, at the same position.
 * 2. Else, if the cloud can take the song ([SingRoute]): a Sing job starts there (or the song's job
 *    already on its way is joined). The original keeps playing with its karaoke lyrics, the button
 *    shows the cloud's progress, and when the instrumental lands the song fades to it at the next gap
 *    between sung words ([VocalGapFinder]), if it is still the song playing on this phone.
 * 3. Else the instrumental is rendered on this phone, and a toast says why the cloud was not used.
 *
 * The pending "switch when ready" lives here, not in a screen, so it survives closing the lyrics page.
 * It is per song: a new song starts with vocals and no pending switch. A second tap while it waits
 * cancels only the switch (the job keeps running and the file is kept for next time).
 * A cloud job that fails sends the next tap for that song to this phone. Cast and Spotify Connect play
 * elsewhere, where the local audio swap can't reach: Sing is off.
 */
@Singleton
class LyricsSingStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val castStateHolder: CastStateHolder,
    private val spotifyConnect: SpotifyConnectStateHolder,
    private val renderJobs: InstrumentalRenderJobs,
    private val lyricsState: LyricsStateHolder,
    private val onlineProbe: SingOnlineProbe,
) {
    private val _active = MutableStateFlow(false)
    /** True while the current song plays through its instrumental (vocals off). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** True when a rendered instrumental exists on disk for the current song. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val currentJob = MutableStateFlow<InstrumentalRenderJob?>(null)
    private val remoteActive = MutableStateFlow(false)
    private val wait = MutableStateFlow(SingWait.NONE)

    private val _ui = MutableStateFlow(SingUi())
    val ui: StateFlow<SingUi> = _ui.asStateFlow()

    private val _requests = MutableSharedFlow<SingRequest>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val requests: SharedFlow<SingRequest> = _requests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scope: CoroutineScope? = null
    private val jobs = ArrayList<Job>(3)

    /** The song whose Sing tap waits for its render. */
    private var pendingSongId: String? = null
    /** Render jobs already answered (switched to, or reported failed), so each answers once. */
    private val answeredJobIds = HashSet<String>()
    /** Jobs the person stopped waiting for (a second tap): they keep running, but the button no longer shows them. */
    private val dismissedJobIds = HashSet<String>()
    /** The current song's jobs as WorkManager and Cloud Studio last reported them. */
    private var lastJobs: List<InstrumentalRenderJob> = emptyList()
    /** Songs whose cloud job failed: their next tap renders on this phone. */
    private val cloudFailedSongIds = HashSet<String>()
    /** A tap is choosing its route: further taps wait for it. */
    private var routing = false
    /** Counts switches and song changes, so a switch being planned can tell it was overtaken. */
    private var switchToken = 0

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        jobs.forEach { it.cancel() }
        jobs.clear()
        jobs += coroutineScope.launch {
            remoteFlow().collect { remoteActive.value = it }
        }
        jobs += coroutineScope.launch {
            combine(_available, _active, currentJob, remoteActive, wait) { available, active, job, remote, waiting ->
                singUi(available, active, job, remote, waiting)
            }
                .distinctUntilChanged()
                .collect { _ui.value = it }
        }
        jobs += coroutineScope.launch {
            playbackStateHolder.stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collectLatest { songId -> followSong(songId) }
        }
    }

    private fun remoteFlow(): Flow<Boolean> = combine(
        castStateHolder.isRemotePlaybackActive,
        castStateHolder.isCastConnecting,
        spotifyConnect.playingOnName,
    ) { casting, connecting, connectDevice -> casting || connecting || connectDevice != null }

    private suspend fun followSong(songId: String?) {
        // Vocals come back with every new song, and a pending tap stays with its own song.
        _active.value = false
        _available.value = false
        currentJob.value = null
        answeredJobIds.clear()
        dismissedJobIds.clear()
        lastJobs = emptyList()
        switchToken++
        wait.value = SingWait.NONE
        if (pendingSongId != songId) pendingSongId = null
        if (songId == null) return
        _available.value = renderJobs.bestAvailablePath(songId) != null
        renderJobs.jobsFor(songId).collect { list -> onJobs(songId, list) }
    }

    internal fun onJobs(songId: String, list: List<InstrumentalRenderJob>) {
        lastJobs = list
        val job = pickJob(list)
        currentJob.value = job?.takeUnless { it.id in dismissedJobIds }
        if (job == null) return
        val path = job.instrumentalPath
        when {
            job.state == RenderJobState.SUCCEEDED && path != null -> {
                // Already switched to, or finished before the latest tap (which found its file
                // gone from disk): nothing new.
                if (job.id in answeredJobIds) return
                _available.value = true
                if (pendingSongId == songId) {
                    answeredJobIds += job.id
                    pendingSongId = null
                    // A render landing while a Cast or Connect speaker plays doesn't touch this phone's player.
                    if (remoteActive.value) {
                        wait.value = SingWait.NONE
                    } else {
                        switchTo(songId, path, afterCloud = job.source == RenderSource.CLOUD)
                    }
                }
            }
            // A person's render that failed or was stopped. Quiet automatic work that our own
            // enqueue cancelled is ignored, or the tap that cancelled it would answer itself.
            job.state.isFinished && !job.automatic -> {
                if (pendingSongId == songId && answeredJobIds.add(job.id)) {
                    pendingSongId = null
                    if (wait.value == SingWait.CLOUD) wait.value = SingWait.NONE
                    if (job.state == RenderJobState.CANCELLED) return
                    if (job.source == RenderSource.CLOUD) {
                        // The next tap for this song renders on this phone, instead of waiting on the cloud again.
                        cloudFailedSongIds += songId
                        _messages.tryEmit(context.getString(R.string.lyrics_sing_cloud_failed))
                    } else {
                        val reason = job.failureReason
                        if (reason != null) _messages.tryEmit(context.getString(R.string.lyrics_sing_failed, reason))
                    }
                }
            }
        }
    }

    /** The tap on Sing. */
    fun onSingTapped() {
        val scope = scope ?: return
        val song = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        if (remoteActive.value) {
            _messages.tryEmit(context.getString(R.string.lyrics_sing_remote_only))
            return
        }
        if (_active.value) {
            // Back to the original is final: nothing is pending any more.
            pendingSongId = null
            wait.value = SingWait.NONE
            switchToken++
            _requests.tryEmit(SingRequest.Original)
            return
        }
        // The player is already moving to the instrumental, or this tap's own route is still being chosen.
        if (wait.value == SingWait.SWITCHING || wait.value == SingWait.SWITCHING_AFTER_CLOUD || routing) return
        if (pendingSongId == song.id) {
            cancelPendingSwitch()
            return
        }
        routing = true
        scope.launch {
            try {
                route(song)
            } finally {
                routing = false
            }
        }
    }

    private suspend fun route(song: Song) {
        val path = renderJobs.bestAvailablePath(song.id)
        if (playbackStateHolder.stablePlayerState.value.currentSong?.id != song.id) return
        if (path != null) {
            // An instrumental is on this phone: switch now, at the same position.
            _available.value = true
            switchTo(song.id, path, afterCloud = false)
            return
        }
        // Nothing on disk, whatever an old finished job says (its file can be gone), so the
        // new render shows its progress.
        _available.value = false
        // Jobs that finished before this tap (yesterday's failure, a render whose file was
        // since deleted) belong to earlier taps: WorkManager can report them again before the
        // new job shows up, and they must not answer this one with a stale failure or a
        // switch to a missing file.
        lastJobs.forEach { if (it.state.isFinished) answeredJobIds += it.id }
        val running = pickJob(lastJobs)?.takeIf { !it.state.isFinished }
        when {
            // The person's render is already on its way (started here or from Song info): the
            // song switches when it lands.
            running != null && running.source == RenderSource.ON_DEVICE && !running.automatic -> {
                dismissedJobIds -= running.id
                currentJob.value = running
                pendingSongId = song.id
            }
            // A cloud job for this song is on its way (the person's, or the quiet automatic one, which a
            // Sing tap takes over): join it, whatever the cloud would say about a new job.
            running != null && running.source == RenderSource.CLOUD -> startCloud(song)
            else -> {
                val failedBefore = cloudFailedSongIds.remove(song.id)
                when (
                    val decision = SingRoute.choose(
                        existingPath = null,
                        cloud = renderJobs.cloudAvailability(song),
                        online = onlineProbe.isOnline(),
                        durationMs = song.duration,
                        cloudFailedForThisSong = failedBefore,
                    )
                ) {
                    SingRoute.Route.Cloud -> startCloud(song)
                    is SingRoute.Route.OnDevice -> renderOnDevice(song, decision.reason)
                    // Nothing on disk was already handled above.
                    SingRoute.Route.Instant -> Unit
                }
            }
        }
    }

    private suspend fun startCloud(song: Song) {
        pendingSongId = song.id
        wait.value = SingWait.CLOUD
        val start = try {
            renderJobs.startCloud(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Sing could not start a cloud job")
            null
        }
        // The person tapped again (or the song changed) while the job was being set up: only the switch is off.
        if (pendingSongId != song.id) return
        when (start) {
            is CloudSingStart.Unavailable -> renderOnDevice(song, SingRoute.reasonFor(start.reason))
            null -> renderOnDevice(song, SingRoute.OnDeviceReason.OTHER)
            // Started, or joined the job already on its way: the original keeps playing meanwhile.
            else -> _messages.tryEmit(context.getString(R.string.lyrics_sing_cloud_queued))
        }
    }

    private fun renderOnDevice(song: Song, reason: SingRoute.OnDeviceReason?) {
        wait.value = SingWait.NONE
        pendingSongId = song.id
        renderJobs.enqueue(song)
        _messages.tryEmit(context.getString(fallbackMessage(reason)))
    }

    /** A second tap while the switch waits: stay on the original. The job keeps running and its file is kept for next time. */
    private fun cancelPendingSwitch() {
        pendingSongId = null
        wait.value = SingWait.NONE
        pickJob(lastJobs)?.takeIf { !it.state.isFinished }?.let { running ->
            dismissedJobIds += running.id
            currentJob.value = null
        }
        _messages.tryEmit(context.getString(R.string.lyrics_sing_switch_cancelled))
    }

    /**
     * Asks the player to switch to the instrumental. From the cloud the fade is placed at a gap between sung
     * words; anything already on this phone switches at once.
     */
    private fun switchTo(songId: String, path: String, afterCloud: Boolean) {
        val scope = scope ?: return
        val token = ++switchToken
        wait.value = if (afterCloud) SingWait.SWITCHING_AFTER_CLOUD else SingWait.SWITCHING
        scope.launch {
            val plan = if (afterCloud) planCrossfade() else null
            val overtaken = token != switchToken ||
                playbackStateHolder.stablePlayerState.value.currentSong?.id != songId || remoteActive.value
            if (overtaken) {
                if (token == switchToken) wait.value = SingWait.NONE
                return@launch
            }
            _requests.tryEmit(SingRequest.Instrumental(path, plan))
            // The player answers through onInstrumentalPlaying / onSwitchFailed; if it never does (the
            // service is gone), the button must not wait for ever.
            delay(SWITCH_ANSWER_TIMEOUT_MS)
            if (token == switchToken && (wait.value == SingWait.SWITCHING || wait.value == SingWait.SWITCHING_AFTER_CLOUD)) {
                wait.value = SingWait.NONE
            }
        }
    }

    /** Where to start the fade: the next gap between sung words, in player time, from the lyrics at this moment. */
    private suspend fun planCrossfade(): CrossfadePlan {
        // The cloud's lyrics import runs before the instrumental's: its word timing may have just arrived.
        val prepared = settledLyrics()
        val offsetMs = lyricsState.currentSongSyncOffset.value.toLong()
        val nowPlayerMs = playbackStateHolder.exactPositionMs()
        // The instrumental player needs a moment to get in step before it can fade: plan from then.
        val plan = VocalGapFinder.plan(prepared, nowPlayerMs + offsetMs + CROSSFADE_PREPARE_LEAD_MS)
        // The lyrics clock is the player position plus the sync offset, so take the offset back off.
        return CrossfadePlan(startAtPositionMs = plan.startAtLyricsMs - offsetMs, fadeMs = plan.fadeMs)
    }

    private suspend fun settledLyrics(): PreparedLyrics? {
        lyricsState.preparedLyrics.value?.let { return it }
        if (!hasTimedLyrics(playbackStateHolder.stablePlayerState.value.lyrics)) return null
        // The synced lyrics are there but their render model is still being built.
        return withTimeoutOrNull(LYRICS_SETTLE_MS) { lyricsState.preparedLyrics.filterNotNull().first() }
    }

    private fun hasTimedLyrics(lyrics: Lyrics?): Boolean =
        lyrics != null && (!lyrics.synced.isNullOrEmpty() || lyrics.document?.lines?.any { it.text.isNotBlank() } == true)

    /** MusicService now plays the instrumental (set by [PlayerViewModel.switchToStudioInstrumental]). */
    fun onInstrumentalPlaying() {
        _active.value = true
        _available.value = true
        wait.value = SingWait.NONE
    }

    /** MusicService could not switch (no instrumental to play, or the song changed meanwhile). */
    fun onSwitchFailed() {
        wait.value = SingWait.NONE
        _messages.tryEmit(context.getString(R.string.lyrics_sing_switch_failed))
    }

    /** MusicService is back on the original audio. */
    fun onOriginalPlaying() {
        _active.value = false
    }

    private fun pickJob(list: List<InstrumentalRenderJob>): InstrumentalRenderJob? =
        list.firstOrNull { !it.state.isFinished } ?: list.maxByOrNull { it.createdAtMs }

    private fun fallbackMessage(reason: SingRoute.OnDeviceReason?): Int = when (reason) {
        null -> R.string.lyrics_sing_queued
        SingRoute.OnDeviceReason.CLOUD_OFF -> R.string.lyrics_sing_fallback_off
        SingRoute.OnDeviceReason.CLOUD_NOT_SET_UP -> R.string.lyrics_sing_fallback_not_set_up
        SingRoute.OnDeviceReason.OFFLINE -> R.string.lyrics_sing_fallback_offline
        SingRoute.OnDeviceReason.NOT_ON_WIFI -> R.string.lyrics_sing_fallback_wifi
        SingRoute.OnDeviceReason.BUDGET_USED -> R.string.lyrics_sing_fallback_budget
        SingRoute.OnDeviceReason.TOO_LONG -> R.string.lyrics_sing_fallback_too_long
        SingRoute.OnDeviceReason.OTHER -> R.string.lyrics_sing_fallback_other
    }

    private companion object {
        /** The instrumental player needs about this long to start and line up before its fade can begin. */
        const val CROSSFADE_PREPARE_LEAD_MS = 300L

        /** The longest to wait for the lyrics' render model to finish building before planning without it. */
        const val LYRICS_SETTLE_MS = 300L

        /** How long the button waits for the player to say whether the switch happened. */
        const val SWITCH_ANSWER_TIMEOUT_MS = 8_000L
    }
}
