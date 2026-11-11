package com.theveloper.pixelplay.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.media.ReplayGainManager
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Owns all ReplayGain volume-normalization state and logic, extracted from
 * [MusicService] during the Pass 5 service decomposition.
 *
 * The processor encapsulates the RG enable/album-gain flags, the in-flight IO
 * read job, the "expected vs. user-selected" volume bookkeeping that keeps a
 * programmatic RG volume change from being mistaken for a user gesture, and the
 * crossfade-aware application of the computed gain. It reaches the players only
 * through the injected [DualPlayerEngine]; the service supplies the current
 * session media item via [currentSessionMediaItem] so stale IO results can be
 * discarded.
 *
 * All public methods are main-thread affine (the IO tag read is dispatched
 * internally), matching the original in-service behaviour.
 */
class ReplayGainProcessor(
    private val engine: DualPlayerEngine,
    private val replayGainManager: ReplayGainManager,
    private val scope: CoroutineScope,
    private val currentSessionMediaItem: () -> MediaItem?,
    /**
     * Spatial audio's level lift (cloud-audio-spec §6.3 "Level"): `10^(headroomDb / 20)` while a spatial rendition is
     * heard, 1 otherwise. It multiplies the normalisation gain only, capped at unity, never the person's own volume.
     */
    private val spatialLift: () -> Float = { 1f },
) {
    private companion object {
        private const val TAG = "MusicService_PixelPlay"
        private const val LIFT_RAMP_STEPS = 5
        private const val LIFT_RAMP_STEP_MS = 16L
    }

    private var enabled = false
    private var useAlbumGain = false
    private var job: Job? = null
    private var requestToken = 0L

    // Volume the user last chose by hand; restored whenever RG is disabled.
    private var userSelectedVolume = 1f
    // Volume we just wrote programmatically — used to ignore the echoed onVolumeChanged.
    private var expectedVolume: Float? = null
    // RG volume computed mid-crossfade, applied once the transition finishes.
    private var pendingVolume: Float? = null
    // Last successfully applied RG volume — avoids a full-volume spike during the
    // IO read for the next track (Repeat/Shuffle/Queue changes).
    private var lastAppliedVolume: Float? = null
    // MediaId for which lastAppliedVolume was computed.
    private var lastMediaId: String? = null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setUseAlbumGain(value: Boolean) {
        useAlbumGain = value
    }

    /** Seeds the user-selected volume from the player's current volume on startup. */
    fun captureUserVolume(volume: Float) {
        userSelectedVolume = volume.coerceIn(0f, 1f)
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Mirrors [Player.Listener.onVolumeChanged]: distinguishes a programmatic RG
     * volume change (which we ignore) from a genuine user gesture (which updates
     * [userSelectedVolume]).
     */
    fun onPlayerVolumeChanged(volume: Float) {
        if (engine.isTransitionRunning()) return
        val expected = expectedVolume
        if (expected != null && abs(expected - volume) < 0.001f) {
            expectedVolume = null
            return
        }
        expectedVolume = null
        userSelectedVolume = volume.coerceIn(0f, 1f)
    }

    /**
     * The one place the processor writes the player's volume. A normalisation gain ([normalization]) gets Spatial
     * audio's lift, `min(1, gain * lift)`; the person's own volume never does. [lastAppliedVolume] and
     * [userSelectedVolume] stay un-lifted, so applying them again is idempotent.
     */
    private fun setPlayerVolume(player: Player, volume: Float, normalization: Boolean = false) {
        lastWriteWasNormalization = normalization
        if (normalization) lastNormalizationGain = volume
        val clampedVolume = if (normalization) {
            com.theveloper.pixelplay.data.spatial.playback.SpatialLevelRule.lifted(volume, spatialLift())
        } else {
            volume.coerceIn(0f, 1f)
        }
        liftRampJob?.cancel()
        expectedVolume = clampedVolume
        player.volume = clampedVolume
    }

    /** The volume an incoming crossfade player should end on: a normalisation gain, lifted like [setPlayerVolume]. */
    private fun liftedIncoming(volume: Float): Float =
        com.theveloper.pixelplay.data.spatial.playback.SpatialLevelRule.lifted(volume, spatialLift())

    private var lastWriteWasNormalization = false
    private var lastNormalizationGain = 1f
    private var liftRampJob: Job? = null

    /**
     * Spatial audio started or stopped being heard (§6.3 "Level"): when a normalisation gain is applied, move to the
     * new lifted level over about 80 ms (five 16 ms steps), like the stage's own ramp. Nothing to do otherwise (no
     * normalisation gain, a crossfade running, or another owner of the volume, which the caller checks).
     */
    fun onSpatialLiftChanged() {
        if (!lastWriteWasNormalization || engine.isTransitionRunning()) return
        val player = engine.masterPlayer
        val target = com.theveloper.pixelplay.data.spatial.playback.SpatialLevelRule.lifted(lastNormalizationGain, spatialLift())
        val steps = com.theveloper.pixelplay.data.spatial.playback.SpatialLevelRule.ramp(player.volume, target, LIFT_RAMP_STEPS)
        liftRampJob?.cancel()
        liftRampJob = scope.launch {
            for (volume in steps) {
                expectedVolume = volume
                player.volume = volume
                delay(LIFT_RAMP_STEP_MS)
            }
        }
    }

    /**
     * Re-applies the last computed RG volume immediately (no IO) unless a
     * crossfade is running, preventing a brief full-volume spike on resume,
     * same-track seeks, and queue edits.
     */
    fun reapplyLastAppliedVolume(player: Player) {
        if (engine.isTransitionRunning()) return
        lastAppliedVolume?.let { setPlayerVolume(player, it, normalization = true) }
    }

    /**
     * Pre-computes ReplayGain for the incoming crossfade track. Seeds
     * [DualPlayerEngine.incomingTrackReplayGainVolume] from cache when available so
     * the fade loop ends at the correct volume, then kicks off [apply] which (since
     * the transition is running) stores the result as a pending volume.
     */
    fun prepareForTransition(player: Player) {
        val incomingItem = player.currentMediaItem
        cachedVolumeFor(incomingItem)?.let { engine.incomingTrackReplayGainVolume = liftedIncoming(it) }
        apply(incomingItem)
    }

    /**
     * Applies ReplayGain volume normalization to [mediaItem]. Reads RG tags from
     * the file on an IO thread and adjusts the master player's volume accordingly.
     */
    fun apply(mediaItem: MediaItem?) {
        job?.cancel()
        requestToken += 1
        val currentRequestToken = requestToken

        if (mediaItem == null) {
            return
        }

        if (!enabled) {
            pendingVolume = null
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, userSelectedVolume)
            }
            return
        }

        val mediaId = mediaItem.mediaId
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)

        if (filePath.isNullOrBlank()) {
            Timber.tag(TAG).d("ReplayGain: No file path for track, keeping user-selected volume")
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, userSelectedVolume)
            }
            return
        }

        val resolvedUseAlbumGain = useAlbumGain

        // Apply the last known RG volume immediately so there is no full-volume spike
        // while the IO coroutine reads the tags for the new track.
        if (!engine.isTransitionRunning()) {
            lastAppliedVolume?.let { setPlayerVolume(engine.masterPlayer, it, normalization = true) }
        }

        // Read ReplayGain tags on IO thread to avoid blocking main
        job = scope.launch {
            val rgValues = withContext(Dispatchers.IO) {
                replayGainManager.readReplayGain(filePath)
            }

            if (currentRequestToken != requestToken) {
                return@launch
            }

            val currentMediaId = currentSessionMediaItem()?.mediaId
            if (currentMediaId != mediaId) {
                Timber.tag(TAG).d("ReplayGain: Ignoring stale result for mediaId=%s", mediaId)
                return@launch
            }

            val volume = replayGainManager.getVolumeMultiplier(
                rgValues,
                useAlbumGain = resolvedUseAlbumGain
            )

            if (engine.isTransitionRunning()) {
                // Store for application after transition completes.
                // Also pass to engine so the crossfade loop ends at the correct RG
                // volume instead of hard-coding 1f, preventing the audible jump.
                pendingVolume = volume
                engine.incomingTrackReplayGainVolume = liftedIncoming(volume)
                Timber.tag(TAG).d("ReplayGain: Stored pending volume=%.2f for %s (transition running)",
                    volume, mediaItem.mediaMetadata.title
                )
            } else {
                pendingVolume = null
                engine.incomingTrackReplayGainVolume = null
                lastAppliedVolume = volume
                lastMediaId = mediaId
                setPlayerVolume(engine.masterPlayer, volume, normalization = true)
                Timber.tag(TAG).d("ReplayGain: Applied volume=%.2f for %s",
                    volume, mediaItem.mediaMetadata.title
                )
            }
        }
    }

    /**
     * Returns the cached ReplayGain volume for a media item if already computed, or null.
     * Does NOT trigger an IO read — only reads from the in-memory cache.
     */
    private fun cachedVolumeFor(mediaItem: MediaItem?): Float? {
        if (!enabled || mediaItem == null) return null
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return null
        if (filePath.isBlank()) return null
        val cached = replayGainManager.getCachedReplayGain(filePath) ?: return null
        return replayGainManager.getVolumeMultiplier(cached, useAlbumGain = useAlbumGain)
    }

    /**
     * Pre-fetches ReplayGain tags for a media item into the cache without applying the volume.
     * Called on queue changes and track transitions so the cache is warm by the time
     * [apply] runs, avoiding the 1-2s JNI read delay on playback start.
     */
    fun prefetch(mediaItem: MediaItem?) {
        if (!enabled || mediaItem == null) return
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return
        if (filePath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            replayGainManager.readReplayGain(filePath)
        }
    }

    /**
     * Applies the volume that was held back while a crossfade was running, or
     * triggers a fresh computation if none was pending.
     */
    fun onTransitionFinished() {
        val player = engine.masterPlayer
        val pending = pendingVolume
        pendingVolume = null

        if (!enabled) {
            setPlayerVolume(player, userSelectedVolume)
            Timber.tag(TAG).d("ReplayGain: Transition finished, RG disabled — restored userSelectedVolume=%.2f", userSelectedVolume)
            return
        }

        if (pending != null) {
            // The crossfade loop ramps to this value; apply it now as the stable post-fade volume.
            // Also update lastAppliedVolume so any subsequent onPositionDiscontinuity
            // (REASON_AUTO_TRANSITION fires right after crossfade ends) uses this value
            // immediately instead of launching a new IO coroutine and causing a spike.
            lastAppliedVolume = pending
            setPlayerVolume(player, pending, normalization = true)
            Timber.tag(TAG).d("ReplayGain: Transition finished, applied pending volume=%.2f", pending)
        } else {
            // No pending volume was computed during transition, trigger full computation
            apply(currentSessionMediaItem())
            Timber.tag(TAG).d("ReplayGain: Transition finished, no pending volume — triggering full recomputation")
        }
    }

    /**
     * Recomputes RG only when the track actually changed; otherwise re-applies the
     * last known volume. [onMediaMetadataChanged] also fires on queue edits without a
     * track change, which would otherwise launch a redundant IO read and cause a spike.
     */
    fun onMediaMetadataChanged(currentItem: MediaItem?) {
        val currentMediaId = currentItem?.mediaId ?: return
        if (currentMediaId != lastMediaId) {
            apply(currentItem)
        } else {
            reapplyLastAppliedVolume(engine.masterPlayer)
        }
    }
}
