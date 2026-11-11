package com.theveloper.pixelplay.data.spatial.playback

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.service.player.RenditionSwapTiming
import com.theveloper.pixelplay.data.service.player.SpatialRenditionSwap
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialLibraryEvent
import com.theveloper.pixelplay.data.spatial.SpatialPlayback
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player side of Spatial audio ([SpatialPlayback], cloud-audio-spec §6.3, §7).
 *
 * It keeps the facts of §6.3 ("Spatial active" = a profile, the route rule, not Sing's instrumental; another device
 * blocks it) and turns them into the stage's parameters ([SpatialStage]), the [audible] state the UI reads, the level
 * lift of §6.3 "Level", the library's [SpatialLibrary.request] / [SpatialLibrary.markPlayed], and the mid-song swaps to
 * and from a render ([SpatialRenditionSwap], which the playback service owns and lends through [Host]).
 *
 * Which file a song starts on is decided by the player's resolver ([SpatialStartResolver]); what each stream turned
 * out to be is reported by the stage ([SpatialStreamReport]). Everything here runs on the main thread.
 */
@Singleton
class RealSpatialPlayback @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stage: SpatialStage,
    private val library: SpatialLibrary,
    private val spotifyDao: SpotifyDao,
) : SpatialPlayback {

    /** What the playback service lends while it runs. Main thread. */
    interface Host {
        val scope: CoroutineScope
        val swap: SpatialRenditionSwap
        fun mainPlayer(): Player
        /** Cast or Spotify Connect plays instead of this phone. */
        fun isRemote(): Boolean
        /** Sing's instrumental is heard (§6.3: Sing pauses Spatial audio). */
        fun isSingAudible(): Boolean
        /** Sing owns the main player's volume (heard or fading). */
        fun singOwnsVolume(): Boolean
        fun isTransitionRunning(): Boolean
        /** [levelLift] changed: re-apply the normalisation gain with it. */
        fun onLevelLiftChanged()

        /**
         * Spatial audio was turned off for the song playing, and the main player plays a render of it: open the song
         * again where it is, so its original plays (at full quality) from here. A short re-buffer, once, on the
         * person's own tap; the item's file never changes any other way mid-song (integration map 2.6 trap 1).
         */
        fun playOriginalFromHere() = Unit
    }

    private val _audible = MutableStateFlow(SpatialAudible.NOTHING)
    override val audible: StateFlow<SpatialAudible> = _audible.asStateFlow()

    private var settings: SpatialSettings? = null
    private var host: Host? = null
    private var eventsJob: Job? = null
    private val route = SpatialRouteMonitor(context) { refresh() }

    private var songId: String? = null
    private var spotifyId: String? = null
    private var itemUri: String? = null
    private var videoId: String? = null
    private var itemGeneration = 0
    private val reports = LinkedHashMap<String, SpatialStreamReport>()
    private var requestedKey: String? = null
    private var markedKey: String? = null
    private var lastSeekAtMs = Long.MIN_VALUE / 4
    private var singReturning = false
    private var originalReloadedFor: String? = null

    private var reconcileJob: Job? = null
    private var reconcileAgain = false
    private var lookedUpKey: String? = null
    private var lookedUpRender: SpatialRender? = null
    private var swapTriedKey: String? = null
    private var retryJob: Job? = null
    private var libraryEpoch = 0

    /**
     * Sing switches to its instrumental under way ([yieldToSing] to [endSingSwitch]). Sing prepares, lines up and
     * waits for its start with nothing of it heard yet, so neither [Host.isSingAudible] nor [Host.singOwnsVolume] holds;
     * no render may start meanwhile.
     */
    private var singSwitches = 0

    /** Where the render lookup runs. */
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** §6.3 "Level": what the normalisation gain is multiplied by now (1 while stereo). Read by ReplayGain. */
    @Volatile
    var levelLift: Float = 1f
        private set

    /** Spatial audio is active right now (§6.3), as the stage was last told. */
    private val active: Boolean get() = stage.state.wet

    override fun apply(settings: SpatialSettings) {
        if (this.settings == settings) return
        val profileChanged = this.settings?.profile != settings.profile ||
            this.settings?.isOffFor(spotifyId) != settings.isOffFor(spotifyId)
        this.settings = settings
        stage.songsOff = settings.songsOff
        if (profileChanged) lookedUpKey = null
        updateRouteMonitor()
        refresh()
    }

    /** The playback service started: from now on items, reports and library events reach this. */
    fun attach(host: Host) {
        this.host = host
        stage.reportListener = ::onStreamReport
        stage.tablesListener = { refresh() }
        eventsJob?.cancel()
        eventsJob = host.scope.launch { library.events.collect { onLibraryEvent(it) } }
        updateRouteMonitor()
        refresh()
    }

    /** The playback service is going away. */
    fun detach() {
        eventsJob?.cancel()
        eventsJob = null
        reconcileJob?.cancel()
        reconcileJob = null
        retryJob?.cancel()
        retryJob = null
        stage.reportListener = null
        stage.tablesListener = null
        host = null
        route.stop()
        songId = null
        spotifyId = null
        itemUri = null
        videoId = null
        reports.clear()
        stage.update(settings?.profile, wet = false)
        levelLift = 1f
        _audible.value = SpatialAudible.NOTHING
    }

    /** A new item is current (`onMediaItemTransition`, or a player swap): [uri] is its configured URI. */
    fun onItemChanged(songId: String?, spotifyId: String?, uri: String?) {
        if (songId == this.songId && spotifyId == this.spotifyId && uri == this.itemUri) return
        val sameSong = songId == this.songId
        this.songId = songId
        this.spotifyId = spotifyId
        this.itemUri = uri
        if (!sameSong) {
            videoId = null
            requestedKey = null
            markedKey = null
            lookedUpKey = null
            originalReloadedFor = null
            itemGeneration++
            // Keep a report of the new item that arrived first (the stage runs ahead of what is heard).
            reports.keys.retainAll(setOfNotNull(songId))
            lookupVideoId()
        }
        refresh()
    }

    /** The main player sought (a seek is "in flight" for the swap rule for a moment). */
    fun onSeek() {
        lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    /** Sing's instrumental came or went. */
    fun onSingChanged() {
        if (host?.isSingAudible() != true) {
            singReturning = false
            // A render that gave way to Sing may take over again now (within the 20 s rule).
            swapTriedKey = null
        }
        refresh()
    }

    /** Sing is about to fade back to the vocals: the stage comes back first so the vocals return spatial. */
    fun prepareForSingReturn() {
        if (host?.isSingAudible() != true) return
        singReturning = true
        refresh()
    }

    /** Sing's fade back to the vocals is over (done or not): Sing blocks Spatial audio again only if still heard. */
    fun endSingReturn() {
        singReturning = false
        refresh()
    }

    /** The render heard on the second player changed (a fade finished, a song change let it go). */
    fun onSwapChanged() = refresh()

    /**
     * Sing is about to fade to the instrumental: a render on the second player gives the main player back first, and no
     * render starts (nor finishes fading in) until [endSingSwitch]. The service calls [endSingSwitch] in a `finally`
     * after Sing's switch, this call included.
     *
     * The render is not re-armed here: it may take over again when Sing ends ([onSingChanged]) or did not take over
     * ([endSingSwitch]). Re-arming it now let it fade straight back in while Sing was still preparing its own fade, both
     * then writing the main player's volume.
     */
    suspend fun yieldToSing() {
        singSwitches++
        val h = host ?: return
        if (h.swap.audibleRender != null || h.swap.isRamping) h.swap.fadeToMain(h.mainPlayer())
    }

    /** Sing's switch to the instrumental is over, done or not. When Sing did not take over, a render may come back. */
    fun endSingSwitch() {
        if (singSwitches == 0) return
        singSwitches--
        if (singSwitches == 0 && host?.isSingAudible() != true) swapTriedKey = null
        refresh()
    }

    /** Cast or Spotify Connect started or stopped. */
    fun onRemoteChanged() = refresh()

    private fun updateRouteMonitor() {
        if (host != null && settings?.profile != null) route.start() else route.stop()
    }

    private fun onStreamReport(report: SpatialStreamReport) {
        val id = report.mediaId ?: return
        reports.remove(id)
        reports[id] = report
        while (reports.size > MAX_REPORTS) reports.remove(reports.keys.first())
        if (id == songId) {
            if (videoId == null) lookupVideoId()
            refresh()
        }
    }

    private fun onLibraryEvent(event: SpatialLibraryEvent) {
        when (event) {
            is SpatialLibraryEvent.RenderReady -> {
                libraryEpoch++
                if (event.render.videoId == currentVideoId()) refresh()
            }
            is SpatialLibraryEvent.Removed -> {
                libraryEpoch++
                stage.forget(event.videoId)
                val h = host ?: return
                val heard = h.swap.audibleRender
                if (heard != null && (event.videoId == null || event.videoId == heard.videoId)) {
                    h.scope.launch { h.swap.fadeToMain(h.mainPlayer()) }
                }
            }
        }
    }

    private fun lookupVideoId() {
        val id = spotifyId ?: return
        val h = host ?: return
        val generation = itemGeneration
        h.scope.launch {
            val found = try {
                spotifyDao.getMatchedVideoId(id)?.takeIf { CloudStreamSecurity.validateYouTubeVideoId(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (generation == itemGeneration && found != null && found != videoId) {
                videoId = found
                refresh()
            }
        }
    }

    /** What the current item's stream is: the stage's report, or (before it) the resolver's decision. */
    private fun currentStream(): SpatialStreamReport? {
        val id = songId ?: return null
        reports[id]?.let { return it }
        val uri = itemUri ?: return null
        val render = stage.renditionOf(uri) ?: return null
        return SpatialStreamReport(id, render, formatSupported = true)
    }

    private fun currentVideoId(): String? {
        val shadow = host?.swap?.audibleRender
        return shadow?.videoId ?: currentStream()?.render?.videoId ?: videoId
    }

    /** The profile the current song plays with: none while Spatial audio is turned off for it (`spatial.songsOff`). */
    private fun songProfile(): SpatialProfile? = settings?.profileFor(spotifyId)

    private fun refresh() {
        val s = settings
        val profile = songProfile()
        val h = host
        val remote = h?.isRemote() == true
        val routeOk = s != null && SpatialRouteRule.passes(s.routeMode, route.deviceType, remote)
        val sing = h?.isSingAudible() == true && !singReturning
        val wet = profile != null && h != null && routeOk && !sing && !remote
        stage.update(profile, wet)

        val stream = currentStream()
        val shadow = h?.swap?.audibleRender
        val audible = SpatialAudibleRule.of(
            songId = songId,
            videoId = videoId,
            profile = profile,
            routeOk = routeOk,
            remote = remote,
            sing = sing,
            stageReady = stage.tables != null,
            stream = stream,
            shadow = shadow,
            spotifyId = spotifyId,
        )
        if (_audible.value != audible) _audible.value = audible

        val lift = SpatialLevelRule.liftFor(audible, (shadow ?: stream?.render)?.headroomDb, stage.tables?.headroomDb)
        if (lift != levelLift) {
            levelLift = lift
            h?.onLevelLiftChanged()
        }
        if (h == null) return
        maybeMarkPlayed(stream)
        maybeRequest(profile, routeOk && !sing && !remote, stream, shadow)
        scheduleReconcile(h)
        maybePlayOriginal(h, s, stream, shadow)
    }

    /**
     * Turned off for this song while it plays a render on the main player (it started on one): its original comes back
     * from here, once per song. Waits for a render on the second player to fade out, and for Sing and any track change.
     */
    private fun maybePlayOriginal(h: Host, s: SpatialSettings?, stream: SpatialStreamReport?, shadow: SpatialRender?) {
        val id = songId ?: return
        if (s == null || !s.isOffFor(spotifyId) || stream?.render == null) return
        if (originalReloadedFor == id || shadow != null || h.swap.isRamping) return
        if (h.isSingAudible() || h.singOwnsVolume() || singSwitches > 0 || h.isTransitionRunning()) return
        if (h.mainPlayer().currentMediaItem?.mediaId != id) return
        originalReloadedFor = id
        reports.remove(id)
        h.playOriginalFromHere()
    }

    /** §6.3: a song playing with Spatial active and no render of the profile asks the library for one (FORMAT too). */
    private fun maybeRequest(profile: SpatialProfile?, spatialActive: Boolean, stream: SpatialStreamReport?, shadow: SpatialRender?) {
        val p = profile ?: return
        val id = songId ?: return
        val vid = videoId ?: return
        if (!spatialActive || stream == null) return
        if (stream.render?.profile == p) return
        if (shadow?.profile == p && shadow.videoId == vid) return
        val key = "$id|$vid|${p.id}"
        if (key == requestedKey) return
        requestedKey = key
        library.request(id, vid, p)
    }

    /** The main player started a render from the local store: the storage budget evicts least-recently-played first. */
    private fun maybeMarkPlayed(stream: SpatialStreamReport?) {
        val render = stream?.render ?: return
        val key = "$songId|${render.videoId}|${render.profile.id}"
        if (key == markedKey) return
        markedKey = key
        library.markPlayed(render.videoId, render.profile)
    }

    private fun scheduleReconcile(h: Host) {
        if (reconcileJob?.isActive == true) {
            reconcileAgain = true
            return
        }
        reconcileJob = h.scope.launch {
            do {
                reconcileAgain = false
                try {
                    reconcile(h)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag(TAG).w(e, "Rendition swap failed")
                }
            } while (reconcileAgain && host === h)
        }
    }

    /**
     * §6.3 mid-song: the render of the wanted profile takes over when it is on the phone (a render that just arrived,
     * a profile picked mid-song, Spatial audio active again), within the 20 s rule; a render that should no longer be
     * heard gives way to the main player.
     */
    private suspend fun reconcile(h: Host) {
        if (host !== h) return
        val swap = h.swap
        val main = h.mainPlayer()
        val profile = songProfile()
        val wanted = if (active && !h.singOwnsVolume() && singSwitches == 0) profile else null
        val stream = currentStream()
        // (The swap lets go of its render at every song change, so what it holds is always this song's.)
        when (SpatialRenditionRule.plan(wanted, stream?.render?.profile, swap.audibleRender?.profile)) {
            SpatialSwapPlan.KEEP -> Unit
            SpatialSwapPlan.BACK_TO_MAIN -> {
                // A later return of the conditions (headphones back, the profile again) may swap in once more.
                if (swap.fadeToMain(main)) swapTriedKey = null
            }
            SpatialSwapPlan.FIND_RENDER -> {
                val want = wanted ?: return
                val vid = videoId ?: return
                if (stream == null) return
                val song = songId
                // The main player must be on this song. An overlap hand-over (crossfade) changes it without a transition
                // of its own, so until the service reports the new song this one may be the last song's: its render
                // would be faded in at the new song's position, over the new song.
                if (main.currentMediaItem?.mediaId != song) return
                val stillWanted = {
                    host === h && songId == song && songProfile() == want && active && singSwitches == 0 &&
                        !h.singOwnsVolume() && h.mainPlayer().currentMediaItem?.mediaId == song
                }
                val key = "$song|$vid|${want.id}|$libraryEpoch"
                // One lookup per song, profile and library change (a memory or file check, off the main thread).
                val local = if (key == lookedUpKey) {
                    lookedUpRender
                } else {
                    val found = withContext(ioDispatcher) {
                        library.localRender(vid, want)?.takeIf { it.videoId == vid && it.profile == want && it.file.isFile }
                    }
                    if (!stillWanted()) return
                    lookedUpKey = key
                    lookedUpRender = found
                    found
                }
                // (Read again: the engine may have rebuilt its player during the lookup.)
                val player = h.mainPlayer()
                if (local == null ||
                    !SpatialRenditionRule.hasTimeLeft(player.currentPosition, SpatialSwapMath.durationMs(player.duration, local)) ||
                    !SpatialRenditionRule.linesUp(player.duration, SpatialSwapMath.renderDurationMs(local))
                ) {
                    // Another profile's render is heard and this one's is not here (or too late in the song, or made
                    // from other audio of the song than what plays): back to the stream with the stage. Otherwise the
                    // next play starts on it.
                    if (swap.audibleRender != null) swap.fadeToMain(player)
                    return
                }
                if (key == swapTriedKey) return
                val settled = SpatialRenditionRule.nothingInFlight(
                    playerReady = player.playbackState == Player.STATE_READY,
                    msSinceSeek = SystemClock.elapsedRealtime() - lastSeekAtMs,
                    transitionRunning = h.isTransitionRunning(),
                )
                if (!settled) {
                    // A seek or a track change is in flight: look again once it has settled.
                    retryLater(h)
                    return
                }
                swapTriedKey = key
                val swapped = if (swap.audibleRender == null) {
                    swap.fadeIn(player, local, stillWanted)
                } else {
                    swap.crossTo(player, local, stillWanted)
                }
                if (swapped) library.markPlayed(local.videoId, local.profile)
            }
        }
    }

    private fun retryLater(h: Host) {
        if (retryJob?.isActive == true) return
        retryJob = h.scope.launch {
            delay(RenditionSwapTiming.SEEK_SETTLE_MS)
            if (host === h) refresh()
        }
    }

    private companion object {
        const val TAG = "SpatialPlayback"
        const val MAX_REPORTS = 4
    }
}
