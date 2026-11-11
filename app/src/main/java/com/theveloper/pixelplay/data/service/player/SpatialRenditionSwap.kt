package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.playback.SpatialSwapMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * The Sing shadow-player swap ([InstrumentalCrossfadeController]) generalised to Spatial audio renders (cloud-audio-spec
 * §6.3): a render that arrives while its song plays from the stream is started on a second, silent player, lined up with
 * the song by media time (the render may have another sample rate than the stream), and crossfaded in over about 250 ms,
 * linear. From then on the main player stays silent and this one is heard; going back is the same fade the other way.
 *
 * The second player is a plain ExoPlayer: default renderers and sink, so no on-device stage ever touches the render
 * (never spatialized twice), and it shares the main player's audio session (equalizer and effects apply).
 *
 * Sing's numbers and paths are untouched: this class has its own ([RenditionSwapTiming]). The two never own the main
 * player's volume at once: the service fades a render out before a Sing fade starts, and Spatial audio is not active
 * while Sing's instrumental is (§6.3), so this class starts nothing then.
 *
 * The second player moves only while the main player does ([followMain]): it pauses while the main player buffers (a
 * seek outside the buffer, a stall), so the local file never runs ahead of a stream that reloads.
 *
 * Main thread only.
 */
class SpatialRenditionSwap(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The main player's audio session id. */
    private val audioSessionIdProvider: () -> Int = { C.AUDIO_SESSION_ID_UNSET },
    /** A next-song overlap transition is running. */
    private val isTransitionRunning: () -> Boolean = { false },
    /** The volume the incoming player of an overlap fades to. */
    private val incomingVolumeTarget: () -> Float = { 1f },
    /** [audibleRender] changed. */
    private val onAudibleChanged: () -> Unit = {},
    /**
     * A level written to the main player while a render is heard (the person's volume slider, ReplayGain) became the
     * render's level. The service hands it on to ReplayGain, which recognises its own writes and keeps the person's
     * level for the next song.
     */
    private val onLevelAbsorbed: (Float) -> Unit = {},
    /** Builds a bare second player (tests hand in fakes). */
    private val newPlayer: () -> ExoPlayer = { ExoPlayer.Builder(context).build() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var shadowPlayer: ExoPlayer? = null
    private var incomingPlayer: ExoPlayer? = null
    private var currentSongId: String? = null
    private var resyncJob: Job? = null
    private var baseVolume = 1f
    private var echoVolume: Float? = null
    private var generation = 0
    private val switchLock = Mutex()

    /** Follows the main player's own play state ([followMain]). */
    private val mainListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = followMain()
    }

    /** The second players' errors: a render that dies while heard must not leave silence (the main player is muted). */
    private val renderPlayerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = onRenderPlayerError(error)
    }

    /** The main player the second player follows; [mainListener] is on it while it is set. */
    private var mainPlayerRef: Player? = null
        set(value) {
            if (field === value) return
            field?.let { old ->
                try {
                    old.removeListener(mainListener)
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "The old main player is gone")
                }
            }
            field = value
            value?.addListener(mainListener)
        }

    /** The render heard instead of the main player (null: the main player is heard). */
    var audibleRender: SpatialRender? = null
        private set(value) {
            if (field == value) return
            field = value
            onAudibleChanged()
        }

    /** The render the second player holds (heard, or being faded). */
    private var shadowRender: SpatialRender? = null

    /** A fade is running. */
    var isRamping: Boolean = false
        private set

    /** The main player's volume is ours (a fade, or a render is heard): nobody else may write it. */
    val ownsMasterVolume: Boolean get() = isRamping || audibleRender != null

    /** Call from `onMediaItemTransition`: a new song tears the second player down and gives the main its volume back. */
    fun onSongChanged(songId: String?, mainPlayer: Player) {
        if (songId == currentSongId) return
        val silenced = ownsMasterVolume && mainPlayerRef === mainPlayer
        teardown()
        if (silenced) restoreMasterVolume(mainPlayer)
        currentSongId = songId
    }

    private fun syncSongWithMain(mainPlayer: Player) {
        val mediaId = mainPlayer.currentMediaItem?.mediaId ?: return
        if (mediaId != currentSongId) onSongChanged(mediaId, mainPlayer)
    }

    /**
     * Stream to [render]: build the second player muted (pre-roll), wait until it plays, line it up with the song, then
     * the 250 ms linear crossfade. True once the render is heard; false (the song untouched) when it could not start,
     * the song changed, or [stillWanted] stopped holding.
     */
    suspend fun fadeIn(mainPlayer: Player, render: SpatialRender, stillWanted: () -> Boolean): Boolean =
        switchLock.withLock {
            syncSongWithMain(mainPlayer)
            if (shadowPlayer != null || audibleRender != null) return@withLock false
            val songId = currentSongId ?: return@withLock false
            val player = createPlayer(render, mainPlayer) ?: return@withLock false
            shadowPlayer = player
            shadowRender = render
            mainPlayerRef = mainPlayer
            startResyncLoop()
            val gen = generation
            // (A player that failed is never faded in: its error ends the swap, the main player back at its level.)
            val valid = {
                generation == gen && shadowPlayer === player && player.playerError == null && currentSongId == songId &&
                    !isTransitionRunning() && stillWanted()
            }
            if (!awaitReady(player, valid) || !align(mainPlayer, player, render, valid)) {
                if (generation == gen) teardown()
                return@withLock false
            }
            val main = { mainPlayerRef ?: mainPlayer }
            val base = main().volume.coerceIn(0f, 1f)
            baseVolume = base
            var completed = false
            isRamping = true
            try {
                completed = CrossfadeRamp.ramp(
                    durationMs = RenditionSwapTiming.FADE_MS,
                    outFrom = base, outTo = 0f,
                    inFrom = 0f, inTo = base,
                    setOut = { main().volume = it },
                    setIn = { player.volume = it },
                    stillValid = valid,
                    clock = clock,
                )
            } catch (e: CancellationException) {
                if (generation == gen) abort(main(), base)
                throw e
            } finally {
                isRamping = false
            }
            if (!completed) {
                if (generation == gen) abort(main(), base)
                return@withLock false
            }
            audibleRender = render
            true
        }

    /** Render to another render (a profile change while one plays): a second player takes over in the same fade. */
    suspend fun crossTo(mainPlayer: Player, render: SpatialRender, stillWanted: () -> Boolean): Boolean =
        switchLock.withLock {
            val old = shadowPlayer ?: return@withLock false
            if (audibleRender == null) return@withLock false
            val songId = currentSongId ?: return@withLock false
            val main = mainPlayerRef ?: mainPlayer
            val next = createPlayer(render, main) ?: return@withLock false
            incomingPlayer = next
            val gen = generation
            // (An incoming player that failed is let go of: the render heard stays.)
            val valid = {
                generation == gen && shadowPlayer === old && incomingPlayer === next && next.playerError == null &&
                    currentSongId == songId && !isTransitionRunning() && stillWanted()
            }
            var completed = false
            try {
                if (awaitReady(next, valid) && align(main, next, render, valid)) {
                    isRamping = true
                    try {
                        completed = CrossfadeRamp.ramp(
                            durationMs = RenditionSwapTiming.FADE_MS,
                            outFrom = old.volume, outTo = 0f,
                            inFrom = 0f, inTo = baseVolume,
                            setOut = { old.volume = it },
                            setIn = { next.volume = it },
                            stillValid = valid,
                            clock = clock,
                        )
                    } finally {
                        isRamping = false
                    }
                }
            } catch (e: CancellationException) {
                abandonCross(next, old, gen)
                throw e
            }
            if (!completed) {
                abandonCross(next, old, gen)
                return@withLock false
            }
            incomingPlayer = null
            shadowPlayer = next
            shadowRender = render
            releaseQuietly(old)
            audibleRender = render
            true
        }

    /** Render back to the main player (Spatial audio off, the route, another profile with no render). */
    suspend fun fadeToMain(mainPlayer: Player): Boolean = switchLock.withLock {
        val player = shadowPlayer ?: return@withLock false
        if (audibleRender == null) {
            teardown()
            return@withLock false
        }
        val gen = generation
        val valid = { generation == gen && shadowPlayer === player }
        val main = { mainPlayerRef ?: mainPlayer }
        val base = baseVolume
        var completed = false
        isRamping = true
        try {
            completed = CrossfadeRamp.ramp(
                durationMs = RenditionSwapTiming.FADE_MS,
                outFrom = player.volume, outTo = 0f,
                inFrom = main().volume, inTo = base,
                setOut = { player.volume = it },
                setIn = { main().volume = it },
                stillValid = valid,
                clock = clock,
            )
        } catch (e: CancellationException) {
            // Cut off half way: the main player is the safe side to stop on.
            if (generation == gen) {
                restoreMasterVolume(main(), base)
                teardown()
            }
            throw e
        } finally {
            isRamping = false
        }
        if (completed) teardown()
        completed
    }

    private fun abandonCross(next: ExoPlayer, old: ExoPlayer, gen: Int) {
        if (incomingPlayer === next) {
            incomingPlayer = null
            releaseQuietly(next)
        }
        if (generation == gen && shadowPlayer === old) old.volume = baseVolume
    }

    /** A fade-in that did not finish: the main player back at its level, the second player gone. */
    private fun abort(main: Player, base: Float) {
        shadowPlayer?.volume = 0f
        restoreMasterVolume(main, base)
        teardown()
    }

    private fun createPlayer(render: SpatialRender, mainPlayer: Player): ExoPlayer? {
        var built: ExoPlayer? = null
        return try {
            newPlayer().apply {
                built = this
                addListener(renderPlayerListener)
                val attrs = Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                audioSessionIdProvider().takeIf { it > 0 }?.let { setAudioSessionId(it) }
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(render.file)))
                prepare()
                playbackParameters = mainPlayer.playbackParameters
                seekTo(SpatialSwapMath.alignTargetMs(mainPlayer.currentPosition, 0L, SpatialSwapMath.renderDurationMs(render)))
                // Only while the main player moves, not while it buffers ([followMain]).
                playWhenReady = mainPlayer.isPlaying
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not prepare the render %s", render.file.name)
            built?.let(::releaseQuietly)
            null
        }
    }

    private fun startResyncLoop() {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            while (true) {
                delay(RESYNC_INTERVAL_MS)
                val shadow = shadowPlayer ?: break
                val main = mainPlayerRef ?: break
                try {
                    if (!main.isPlaying || isRamping) continue
                    val target = main.currentPosition
                    if (abs(shadow.currentPosition - target) > DRIFT_TOLERANCE_MS) shadow.seekTo(target)
                } catch (e: IllegalStateException) {
                    Timber.tag(TAG).d(e, "Resync skipped: a player was released")
                }
            }
        }
    }

    /** The main player sought: the render follows to the same media time. */
    fun onMainSeek(positionMs: Long) {
        shadowPlayer?.seekTo(positionMs)
        incomingPlayer?.seekTo(positionMs)
    }

    fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
        listOfNotNull(shadowPlayer, incomingPlayer).forEach { player ->
            if (player.playbackParameters != parameters) player.playbackParameters = parameters
        }
    }

    /** The session player's play/pause ([playWhenReady] false also while another device plays). */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (playWhenReady) {
            followMain()
        } else {
            shadowPlayer?.playWhenReady = false
            incomingPlayer?.playWhenReady = false
        }
    }

    /**
     * The second players move only while the main player does. A seek outside the main player's buffer (every seek
     * back) or a stall leaves it buffering with play still on while the local render would be ready in tens of ms: the
     * render waits, then starts again from the main player's media time when it moves on. Otherwise it ran ahead of the
     * reloading stream until the next resync sought the heard render back over the same stretch.
     */
    private fun followMain() {
        val main = mainPlayerRef ?: return
        val moving = main.isPlaying
        val shadow = shadowPlayer
        // Not in the middle of a fade: a seek there would be heard (the resync waits for the same reason).
        if (moving && shadow != null && !isRamping) {
            val target = main.currentPosition
            if (abs(shadow.currentPosition - target) > DRIFT_TOLERANCE_MS) shadow.seekTo(target)
        }
        if (shadow != null && shadow.playWhenReady != moving) shadow.playWhenReady = moving
        incomingPlayer?.let { if (it.playWhenReady != moving) it.playWhenReady = moving }
    }

    private suspend fun awaitReady(player: ExoPlayer, valid: () -> Boolean): Boolean {
        if (player.playbackState == Player.STATE_READY) return true
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (valid() && player.playbackState != Player.STATE_READY) {
                if (player.playerError != null) return@withTimeoutOrNull false
                delay(READY_POLL_MS)
            }
            valid()
        }
        return ready == true
    }

    /**
     * Puts the render on the main player's media time: seek, measure how far behind it landed, aim ahead by that, until
     * within [RenditionSwapTiming.ALIGN_TOLERANCE_MS] or out of [RenditionSwapTiming.ALIGN_BUDGET_MS]. Positions are
     * milliseconds of media time on both players, whatever their sample rates.
     */
    private suspend fun align(main: Player, player: ExoPlayer, render: SpatialRender, valid: () -> Boolean): Boolean {
        val renderMs = SpatialSwapMath.renderDurationMs(render)
        player.playWhenReady = main.isPlaying
        if (!main.isPlaying) {
            val at = SpatialSwapMath.alignTargetMs(main.currentPosition, 0L, renderMs)
            if (player.currentPosition != at) {
                player.seekTo(at)
                awaitSeekLanded(player, valid)
            }
            return valid()
        }
        val giveUpAt = clock() + RenditionSwapTiming.ALIGN_BUDGET_MS
        var lead = 0L
        var drift = player.currentPosition - main.currentPosition
        while (abs(drift) > RenditionSwapTiming.ALIGN_TOLERANCE_MS && clock() < giveUpAt) {
            lead = CrossfadeRamp.nextSeekLead(lead, drift)
            player.seekTo(SpatialSwapMath.alignTargetMs(main.currentPosition, lead, renderMs))
            awaitSeekLanded(player, valid)
            if (!valid()) return false
            drift = player.currentPosition - main.currentPosition
        }
        if (abs(drift) > RenditionSwapTiming.ALIGN_TOLERANCE_MS) Timber.tag(TAG).d("Render starts %d ms off the song", drift)
        return valid()
    }

    private suspend fun awaitSeekLanded(player: ExoPlayer, valid: () -> Boolean) {
        withTimeoutOrNull(RenditionSwapTiming.ALIGN_BUDGET_MS) {
            while (valid() && player.playbackState != Player.STATE_READY) delay(READY_POLL_MS)
        }
    }

    private fun restoreMasterVolume(main: Player, volume: Float = baseVolume) {
        if (abs(main.volume - volume) > VOLUME_EPSILON) echoVolume = volume
        main.volume = volume
    }

    /**
     * The service's look at every volume change of the session player, after Sing's. True when the change is ours or
     * must be absorbed: while a render is heard, a write that raises the main player (ReplayGain, a resume) becomes
     * the render's level and the main player is silenced again; an overlap's incoming player fading in fades the
     * render out by the same amount.
     */
    fun onMainVolumeChanged(player: Player, volume: Float): Boolean {
        echoVolume?.let { echo ->
            if (abs(echo - volume) < VOLUME_EPSILON) {
                echoVolume = null
                return true
            }
        }
        if (isRamping) return true
        if (audibleRender == null) return false
        val shadow = shadowPlayer ?: return false
        if (player !== mainPlayerRef) {
            if (isTransitionRunning()) {
                val target = incomingVolumeTarget().coerceAtLeast(VOLUME_EPSILON)
                val faded = (volume / target).coerceIn(0f, 1f)
                shadow.volume = (baseVolume * (1f - faded)).coerceIn(0f, 1f)
            }
            return true
        }
        if (volume > VOLUME_EPSILON) {
            baseVolume = volume
            shadow.volume = volume
            // The person's level must outlive this render (ReplayGain restores it at the next song).
            onLevelAbsorbed(volume)
            echoVolume = 0f
            player.volume = 0f
        }
        return true
    }

    /** The engine published another main player (a rebuild): follow it, silent while a render is heard. */
    fun onMainPlayerSwapped(newMain: Player) {
        if (shadowPlayer == null || isTransitionRunning()) return
        mainPlayerRef = newMain
        if (audibleRender != null && newMain.volume > VOLUME_EPSILON) {
            baseVolume = newMain.volume
            shadowPlayer?.volume = baseVolume
            echoVolume = 0f
            newMain.volume = 0f
        }
        followMain()
    }

    /**
     * A second player failed (an AudioSink or decoder error, e.g. after a route change). The one heard gives the main
     * player back at once: it is silent already, so there is nothing to fade, and the main player is at 0 with no other
     * error recovery for this player. The caller keeps the render tried for this song, so it is not started again.
     */
    private fun onRenderPlayerError(error: PlaybackException) {
        val shadow = shadowPlayer ?: return
        // The incoming player of a profile change: crossTo lets go of it (its check sees the error).
        if (shadow.playerError == null) return
        // Still fading in: fadeIn's check sees the error and puts the main player back.
        if (audibleRender == null) return
        Timber.tag(TAG).w(error, "The render heard failed: back to the main player")
        mainPlayerRef?.let { restoreMasterVolume(it) }
        teardown()
    }

    /** An overlap transition ended: the next song took over (the render played out), or it was cancelled. */
    fun onTransitionFinished(mainPlayer: Player) {
        val shadow = shadowPlayer ?: return
        val songId = mainPlayer.currentMediaItem?.mediaId
        if (songId != currentSongId || mainPlayer !== mainPlayerRef) {
            teardown()
            currentSongId = songId
        } else if (audibleRender != null && !isRamping) {
            shadow.volume = baseVolume
        }
    }

    fun teardown() {
        generation++
        resyncJob?.cancel()
        resyncJob = null
        shadowPlayer?.let(::releaseQuietly)
        incomingPlayer?.let(::releaseQuietly)
        shadowPlayer = null
        incomingPlayer = null
        shadowRender = null
        mainPlayerRef = null
        isRamping = false
        audibleRender = null
    }

    private fun releaseQuietly(player: ExoPlayer) {
        try {
            player.removeListener(renderPlayerListener)
            player.release()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Releasing the render player failed")
        }
    }

    private companion object {
        const val TAG = "SpatialRenditionSwap"
        const val RESYNC_INTERVAL_MS = 4_000L
        const val DRIFT_TOLERANCE_MS = 200L
        const val READY_TIMEOUT_MS = 1_500L
        const val READY_POLL_MS = 8L
        const val VOLUME_EPSILON = 0.001f
    }
}
