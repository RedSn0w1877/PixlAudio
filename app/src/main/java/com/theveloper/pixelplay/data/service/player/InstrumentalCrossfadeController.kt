package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.theveloper.pixelplay.data.tais.SingClipStore
import com.theveloper.pixelplay.data.tais.SingClipWindow
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Keeps a second, silent ExoPlayer running in lockstep with the main session player whenever the
 * current song has a rendered instrumental available, so switching to it (the Sing button) is a volume
 * crossfade instead of a hard media-item swap — both tracks are already playing in sync, only which
 * one is audible changes.
 *
 * This player never touches audio focus — `setAudioAttributes(attrs, handleAudioFocus = false)`,
 * same convention as [DualPlayerEngine]'s own players — because focus is owned entirely by the
 * main player; this is purely a second, normally-silent output riding on the session's existing
 * grant. It also never reaches [androidx.media3.session.MediaSession]: position/duration/seek
 * commands from the lock screen, notification, Android Auto etc. all keep talking to the main
 * player exactly as before, and this class just mirrors whatever that player does. It does share the
 * main player's audio session, so the equalizer and the other session effects colour the instrumental
 * as they do the song.
 *
 * Volumes: while the instrumental is audible the main player is silenced, and [baseVolume] remembers
 * what it was (ReplayGain and the user's level included). The instrumental plays at that level, and
 * going back restores exactly it. Everything that writes the main player's volume meanwhile (ReplayGain,
 * a resume, a seek) is intercepted by [onMainVolumeChanged], which the service consults first; such a
 * write only updates [baseVolume].
 *
 * Next-song overlap transitions: the instrumental is the audible tail of the song, so it fades out with
 * the incoming player ([onMainVolumeChanged] mirrors that player's fade-in) and is released when the
 * hand-over finishes ([onTransitionFinished]).
 *
 * Preview clips (a Sing whose full instrumental is still in the cloud): the second player can play a clip that
 * holds only part of the song ([SingClipSource]), at song position minus the clip's start; every seek and resync
 * goes through that offset ([SingClipTimeline]). Before the clip runs out the vocals come back at the gap the
 * app planned; a seek outside it brings them back at once; either way [onClipLeft] tells the app, whose Sing keeps
 * waiting. When the full instrumental lands while the clip plays, a second player takes over at the same
 * position with a short fade ([swapClipToFull]) and the clip's file goes.
 */
class InstrumentalCrossfadeController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The main player's audio session id, so effects attached to it reach the instrumental too. */
    private val audioSessionIdProvider: () -> Int = { C.AUDIO_SESSION_ID_UNSET },
    /** A next-song overlap transition is running (the incoming player is the one fading in). */
    private val isTransitionRunning: () -> Boolean = { false },
    /** The volume the incoming player of an overlap transition fades to (its ReplayGain level). */
    private val incomingVolumeTarget: () -> Float = { 1f },
    /** The player left a preview clip on its own (it was running out, or a seek left it): the song id. */
    private val onClipLeft: (String) -> Unit = {},
) {
    private var shadowPlayer: ExoPlayer? = null
    private var mainPlayerRef: Player? = null
    private var currentSongId: String? = null
    private var resyncJob: Job? = null

    /** The preview clip the second player plays (null: the song's full instrumental). */
    private var clip: SingClipSource? = null

    /** Where the fade back from the audible clip starts, in song time. */
    private var clipReturnAtMs = 0L

    /** Watches the song while a clip is audible, to bring the vocals back before it runs out. */
    private var clipWatchJob: Job? = null

    /** The full instrumental's player while it takes over from the clip. */
    private var incomingPlayer: ExoPlayer? = null

    /** A suspend owner forced the vocals back while the instrumental was audible; re-apply on resume. */
    private var restoreInstrumentalOnResume = false

    /** The main player's volume before it was silenced: what the instrumental plays at, and what comes back. */
    private var baseVolume = 1f

    /** A volume we just wrote to the main player, whose echo in [onMainVolumeChanged] is not a foreign write. */
    private var echoVolume: Float? = null

    /** Bumped by every [teardown], so a switch in flight can tell its player is gone. */
    private var generation = 0

    /** One switch at a time: a tap while the previous one is still fading waits for it. */
    private val switchLock = Mutex()

    private val clock: () -> Long = SystemClock::elapsedRealtime

    init {
        active = java.lang.ref.WeakReference(this)
    }

    var isCrossfadedToInstrumental: Boolean = false
        private set

    /** A fade between the vocals and the instrumental is running. */
    var isRamping: Boolean = false
        private set

    /** The main player's volume is ours right now (a fade, or the instrumental is audible): nobody else may write it. */
    val ownsMasterVolume: Boolean
        get() = isRamping || isCrossfadedToInstrumental

    val hasInstrumentalReady: Boolean
        get() = shadowPlayer != null

    /**
     * Call from the main player's `onMediaItemTransition`. Tears down the old shadow player. The
     * new one is built lazily by [crossfadeToInstrumental]: keeping a silent second ExoPlayer
     * (extra PCM AudioTrack + wake lock) running for the whole song only to make a rarely used
     * toggle instant cost battery on every song that has a render, and its file checks ran on the
     * main thread at each skip.
     */
    fun onSongChanged(songId: String?, mainPlayer: Player) {
        if (songId == currentSongId) return
        // The previous song left the main player silenced (or half faded) — the new song's vocals must
        // not inherit that. ReplayGain re-applies its own level right after.
        val silenced = (isCrossfadedToInstrumental || isRamping) && mainPlayerRef === mainPlayer
        teardown()
        if (silenced) restoreMasterVolume(mainPlayer)
        currentSongId = songId
    }

    /**
     * A session restored with a song already open never reports a transition, and an overlap hand-over
     * changes the song without one: take the song the main player is on now.
     */
    private fun syncSongWithMain(mainPlayer: Player) {
        val mediaId = mainPlayer.currentMediaItem?.mediaId ?: return
        if (mediaId != currentSongId) onSongChanged(mediaId, mainPlayer)
    }

    /** What is audible now: the vocals, a preview clip, or the song's full instrumental. */
    private val audible: InstrumentalAudible
        get() = when {
            !isCrossfadedToInstrumental -> InstrumentalAudible.NONE
            clip != null -> InstrumentalAudible.CLIP
            else -> InstrumentalAudible.FULL
        }

    /** Where the second player belongs when the song is at [songMs]: the same point, or that point of the clip. */
    private fun shadowTargetMs(songMs: Long): Long = clip?.window?.clipPositionMs(songMs) ?: songMs

    /**
     * Builds the instrumental player on demand — a render that finishes *while its song is already open*
     * has no transition to build it on. Null when the song has none (or the song changed meanwhile). With
     * [source] it plays that preview clip instead of the song's full instrumental.
     */
    private suspend fun prepareShadow(mainPlayer: Player, source: SingClipSource?): ExoPlayer? {
        shadowPlayer?.let { existing ->
            // Kept from a suspend: reuse it only for the same audio.
            if (clip?.file == source?.file) return existing
            teardown()
        }
        val songId = currentSongId ?: return null
        val file = withContext(Dispatchers.IO) {
            if (source != null) source.file.takeIf { it.isFile } else TaisInstrumentalIndex.bestAvailableFile(context, songId)
        } ?: return null
        if (currentSongId != songId || shadowPlayer != null) return shadowPlayer
        clip = source
        return buildShadow(file, mainPlayer) ?: run {
            clip = null
            null
        }
    }

    private fun buildShadow(file: java.io.File, mainPlayer: Player): ExoPlayer? {
        val player = createPlayer(file, mainPlayer, shadowTargetMs(mainPlayer.currentPosition))
        if (player == null) {
            shadowPlayer = null
            return null
        }
        // A clip being read can't be deleted under the player (a seek reopens the file).
        clip?.let { SingClipStore.markInUse(it.file) }
        shadowPlayer = player
        mainPlayerRef = mainPlayer
        isCrossfadedToInstrumental = false
        startResyncLoop()
        return player
    }

    /** A silent second player on [file], at [positionMs] of it, following [mainPlayer]'s speed and play state. */
    private fun createPlayer(file: java.io.File, mainPlayer: Player, positionMs: Long): ExoPlayer? {
        return try {
            ExoPlayer.Builder(context).build().apply {
                val attrs = Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                // Same session as the main player, so the equalizer and effects attached to it apply. Only
                // possible before prepare().
                audioSessionIdProvider().takeIf { it > 0 }?.let { setAudioSessionId(it) }
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playbackParameters = mainPlayer.playbackParameters
                seekTo(positionMs)
                playWhenReady = mainPlayer.playWhenReady
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to prepare shadow instrumental player for %s", file.name)
            null
        }
    }

    private fun startResyncLoop() {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            while (true) {
                delay(RESYNC_INTERVAL_MS)
                val shadow = shadowPlayer ?: break
                // Read every round: a player swap or rebuild hands the loop a new main player.
                val main = mainPlayerRef ?: break
                try {
                    // Keeps running while suspended too: it only ever seeks the shadow, never the main
                    // player, and the shadow must stay aligned for when it is audible again. A seek in
                    // the middle of a fade would be heard, so a fade is left alone.
                    if (!main.isPlaying || isRamping) continue
                    val songMs = main.currentPosition
                    // Outside a clip there is nothing to line up: the clip watch brings the vocals back.
                    if (clip?.window?.covers(songMs) == false) continue
                    val target = shadowTargetMs(songMs)
                    val drift = abs(shadow.currentPosition - target)
                    if (drift > DRIFT_TOLERANCE_MS) {
                        shadow.seekTo(target)
                    }
                } catch (e: IllegalStateException) {
                    // One of the two players was released under us (a rebuild); the owner rebinds or tears down.
                    Timber.tag(TAG).d(e, "Resync skipped: a player was released")
                }
            }
        }
    }

    /** Call on the main player's seek (position discontinuity, reason SEEK) — "match the exact timestamp" instead of waiting up to [RESYNC_INTERVAL_MS] for the next periodic correction. */
    fun onMainSeek(positionMs: Long) {
        val shadow = shadowPlayer ?: return
        // The full instrumental taking over from a clip is on the song's own timeline.
        incomingPlayer?.seekTo(positionMs)
        val active = clip
        // (Not while the full instrumental is taking over: it holds every position, so the swap just finishes.)
        if (active != null && !active.window.covers(positionMs) && isCrossfadedToInstrumental && !isRamping &&
            incomingPlayer == null
        ) {
            // A seek outside the clip: the vocals come back at once (the seek is the cut), and the Sing keeps waiting.
            leaveClip()
            return
        }
        shadow.seekTo(shadowTargetMs(positionMs))
    }

    /** Keeps the shadow at the main player's speed (e.g. the lyrics sync editor's slower speeds). */
    fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
        listOfNotNull(shadowPlayer, incomingPlayer).forEach { player ->
            if (player.playbackParameters != parameters) player.playbackParameters = parameters
        }
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        shadowPlayer?.playWhenReady = playWhenReady
        incomingPlayer?.playWhenReady = playWhenReady
    }

    /**
     * Fades from the song's vocals to its instrumental, as [request] asks: wait until the instrumental is in
     * step with the song, then until the planned start (a gap between sung words), then fade. True once the
     * instrumental is the one audible; false (the song untouched) when the song has no instrumental, it could
     * not be started, the song changed meanwhile or a suspend owner forbids crossfades.
     *
     * With [clipSource] the instrumental is that preview clip, valid only while the song is inside it. A request
     * for the full instrumental while a clip plays swaps the clip for it ([swapClipToFull]); a clip is never put
     * over an instrumental that already plays.
     */
    suspend fun crossfadeToInstrumental(
        mainPlayer: Player,
        request: CrossfadeRequest = CrossfadeRequest(),
        clipSource: SingClipSource? = null,
    ): Boolean =
        switchLock.withLock {
            if (isSuspended) return@withLock false
            syncSongWithMain(mainPlayer)
            when (SingClipTimeline.switchFor(audible, wantsClip = clipSource != null)) {
                // Already audible: nothing to do (a second request, e.g. a resume, must not fade a silent player to itself).
                InstrumentalSwitch.KEEP -> return@withLock true
                InstrumentalSwitch.SWAP_CLIP_TO_FULL -> return@withLock swapClipToFull(mainPlayer)
                InstrumentalSwitch.CROSSFADE_FROM_ORIGINAL -> Unit
            }
            val songId = currentSongId ?: return@withLock false
            val shadow = prepareShadow(mainPlayer, clipSource) ?: return@withLock false
            val gen = generation
            val window = clipSource?.window
            // A clip holds only part of the song: the switch is off as soon as the song is outside it.
            val valid = {
                generation == gen && shadowPlayer === shadow && currentSongId == songId && !isSuspended &&
                    (window == null || window.covers((mainPlayerRef ?: mainPlayer).currentPosition))
            }
            if (!awaitReady(shadow, valid) || !alignShadow(mainPlayer, shadow, valid, window)) {
                // Not startable now: free the second player instead of leaving it open until the song ends.
                if (generation == gen) teardown()
                return@withLock false
            }
            val startAt = request.startAtPositionMs
            if (startAt != null && mainPlayer.isPlaying) {
                val reached = CrossfadeRamp.awaitPosition(
                    position = { mainPlayer.currentPosition },
                    targetMs = startAt,
                    maxWaitMs = request.maxWaitMs,
                    stillValid = valid,
                    clock = clock,
                )
                if (!reached) {
                    // A clip the song left meanwhile is of no use: let go of it (and of its file) now.
                    if (window != null && generation == gen) teardown()
                    return@withLock false
                }
            }
            val main = { mainPlayerRef ?: mainPlayer }
            val base = main().volume.coerceIn(0f, 1f)
            baseVolume = base
            var completed = false
            isRamping = true
            try {
                completed = CrossfadeRamp.ramp(
                    durationMs = CrossfadeRamp.clampFadeMs(request.fadeMs),
                    outFrom = base, outTo = 0f,
                    inFrom = 0f, inTo = base,
                    setOut = { main().volume = it },
                    setIn = { shadow.volume = it },
                    stillValid = valid,
                    clock = clock,
                )
                if (completed) isCrossfadedToInstrumental = true
            } catch (e: CancellationException) {
                if (generation == gen) abortFade(main(), shadow, base)
                throw e
            } finally {
                isRamping = false
            }
            if (!completed && generation == gen) {
                abortFade(main(), shadow, base)
                // A clip that couldn't be faded in (the song left it) is let go of at once, with its file.
                if (window != null) teardown()
            }
            if (completed && clip != null) startClipWatch()
            completed
        }

    /** Fades back from the instrumental to the vocals and frees the second player. False if there was nothing to fade back from. */
    suspend fun crossfadeToOriginal(mainPlayer: Player, durationMs: Long = CrossfadeRamp.DEFAULT_FADE_MS): Boolean =
        switchLock.withLock { fadeToOriginalLocked(mainPlayer, durationMs) }

    /** [crossfadeToOriginal]'s fade, with [switchLock] held. */
    private suspend fun fadeToOriginalLocked(mainPlayer: Player, durationMs: Long): Boolean {
        val shadow = shadowPlayer ?: return false
        if (!isCrossfadedToInstrumental) {
            // The switch never completed: nothing is audible but the song itself.
            teardown()
            return false
        }
        val gen = generation
        val valid = { generation == gen && shadowPlayer === shadow }
        val main = { mainPlayerRef ?: mainPlayer }
        val base = baseVolume
        var completed = false
        isRamping = true
        try {
            completed = CrossfadeRamp.ramp(
                durationMs = CrossfadeRamp.clampFadeMs(durationMs),
                outFrom = shadow.volume, outTo = 0f,
                inFrom = main().volume, inTo = base,
                setOut = { shadow.volume = it },
                setIn = { main().volume = it },
                stillValid = valid,
                clock = clock,
            )
        } catch (e: CancellationException) {
            // The fade was cut off half way: the vocals are the safe side to stop on.
            if (generation == gen) {
                restoreMasterVolume(main(), base)
                finishReturnToOriginal()
            }
            throw e
        } finally {
            isRamping = false
        }
        if (completed) finishReturnToOriginal()
        return completed
    }

    // ─── Preview clips ──────────────────────────────────────────────────────────────────────

    /**
     * Watches the song while a clip is audible: before the clip runs out the vocals come back at the planned gap
     * ([SingClipTimeline.returnAtMs]); a song outside the clip brings them back at once. Sleeps until shortly before
     * the return point, so it costs next to nothing meanwhile.
     */
    private fun startClipWatch() {
        clipWatchJob?.cancel()
        val active = clip ?: return
        clipReturnAtMs = SingClipTimeline.returnAtMs(active.window, active.returnAtMs, active.returnFadeMs)
        val gen = generation
        clipWatchJob = scope.launch {
            while (generation == gen && clip === active && isCrossfadedToInstrumental) {
                val main = mainPlayerRef ?: break
                if (isRamping) {
                    delay(SingClipTimeline.MIN_CHECK_MS)
                    continue
                }
                val songMs = try {
                    main.currentPosition
                } catch (e: IllegalStateException) {
                    break
                }
                when (SingClipTimeline.action(active.window, songMs, clipReturnAtMs)) {
                    ClipAction.STAY -> delay(
                        SingClipTimeline.nextCheckDelayMs(songMs, clipReturnAtMs, main.playbackParameters.speed)
                    )
                    ClipAction.RETURN -> {
                        // Detached first: the return tears this player down, and must not cancel itself half way.
                        clipWatchJob = null
                        returnFromClip(main, active)
                        break
                    }
                    ClipAction.LEAVE_NOW -> {
                        clipWatchJob = null
                        leaveClip()
                        break
                    }
                }
            }
        }
    }

    /** The clip is about to run out and the full instrumental isn't here: fade back to the vocals, and say so. */
    private suspend fun returnFromClip(main: Player, active: SingClipSource) {
        val songId = currentSongId
        val returned = switchLock.withLock {
            // The full instrumental took over meanwhile, or the clip is no longer audible: nothing to return from.
            if (clip !== active || !isCrossfadedToInstrumental) false
            else fadeToOriginalLocked(main, active.returnFadeMs)
        }
        if (returned) songId?.let(onClipLeft)
    }

    /** Back to the vocals at once (a seek left the clip): no fade over a cut that already happened. */
    private fun leaveClip() {
        val songId = currentSongId
        val main = mainPlayerRef
        shadowPlayer?.volume = 0f
        if (main != null) restoreMasterVolume(main)
        teardown()
        songId?.let(onClipLeft)
    }

    /**
     * The full instrumental arrived while the clip plays: a second player starts on it at the song's position, and
     * the two instrumentals cross in [SingClipTimeline.SWAP_FADE_MS] (no gap needed: the vocals stay off). Then the
     * clip's player and file go. With [switchLock] held. False (the clip keeps playing) when it couldn't start.
     */
    private suspend fun swapClipToFull(mainPlayer: Player): Boolean {
        val old = shadowPlayer ?: return false
        val oldClip = clip ?: return false
        val songId = currentSongId ?: return false
        val file = withContext(Dispatchers.IO) { TaisInstrumentalIndex.bestAvailableFile(context, songId) } ?: return false
        if (currentSongId != songId || shadowPlayer !== old || clip !== oldClip) return false
        val main = mainPlayerRef ?: mainPlayer
        val next = createPlayer(file, main, main.currentPosition) ?: return false
        incomingPlayer = next
        val gen = generation
        val valid = { generation == gen && shadowPlayer === old && incomingPlayer === next && currentSongId == songId && !isSuspended }
        // The full instrumental takes over before the clip would run out: the watch stands down.
        clipWatchJob?.cancel()
        clipWatchJob = null
        var completed = false
        try {
            if (awaitReady(next, valid) && alignShadow(main, next, valid, window = null)) {
                isRamping = true
                try {
                    completed = CrossfadeRamp.ramp(
                        durationMs = SingClipTimeline.SWAP_FADE_MS,
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
            abandonSwap(next, old, gen)
            throw e
        }
        if (!completed) {
            abandonSwap(next, old, gen)
            return false
        }
        incomingPlayer = null
        shadowPlayer = next
        clip = null
        releaseQuietly(old)
        SingClipStore.release(oldClip.file)
        SingClipStore.delete(oldClip.file)
        return true
    }

    /** A swap that didn't finish: the full instrumental's player goes, the clip plays on at its level. */
    private fun abandonSwap(next: ExoPlayer, old: ExoPlayer, gen: Int) {
        if (incomingPlayer === next) {
            incomingPlayer = null
            releaseQuietly(next)
        }
        if (generation == gen && shadowPlayer === old) {
            old.volume = baseVolume
            if (isCrossfadedToInstrumental && clip != null) startClipWatch()
        }
    }

    private fun releaseQuietly(player: ExoPlayer) {
        try {
            player.release()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Releasing the instrumental player failed")
        }
    }

    /** The original is audible again (its volume already restored): free the silent second player until the next switch. */
    private fun finishReturnToOriginal() {
        teardown()
    }

    /** A fade that did not finish: back to the vocals at the volume they had, the instrumental silent. */
    private fun abortFade(main: Player, shadow: ExoPlayer, base: Float) {
        shadow.volume = 0f
        restoreMasterVolume(main, base)
        isCrossfadedToInstrumental = false
    }

    /** Waits for the instrumental's first frames, so the fade is not a dip to silence. */
    private suspend fun awaitReady(shadow: ExoPlayer, valid: () -> Boolean): Boolean {
        if (shadow.playbackState == Player.STATE_READY) return true
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (valid() && shadow.playbackState != Player.STATE_READY) {
                // An unreadable file never gets ready; do not wait the whole timeout for it.
                if (shadow.playerError != null) return@withTimeoutOrNull false
                delay(READY_POLL_MS)
            }
            valid()
        }
        return ready == true
    }

    /**
     * Puts the instrumental on the main player's timestamp. If the two are not within [ALIGN_TOLERANCE_MS]
     * (a fresh player starts a little behind), seek again, aiming ahead by the lag the last seek showed, and
     * look once more. Gives up improving after [ALIGN_BUDGET_MS] (the periodic resync keeps correcting
     * afterwards). False only when the switch stopped being valid. [window] is the preview clip [shadow] plays
     * (null: the full instrumental, on the song's own timeline).
     */
    private suspend fun alignShadow(main: Player, shadow: ExoPlayer, valid: () -> Boolean, window: SingClipWindow?): Boolean {
        val target = { songMs: Long -> window?.clipPositionMs(songMs) ?: songMs }
        shadow.playWhenReady = main.playWhenReady
        if (!main.isPlaying) {
            // Nothing moves: one seek puts them on the same timestamp exactly.
            val at = target(main.currentPosition)
            if (shadow.currentPosition != at) {
                shadow.seekTo(at)
                awaitSeekLanded(shadow, valid)
            }
            return valid()
        }
        val giveUpAt = clock() + ALIGN_BUDGET_MS
        var lead = 0L
        var drift = shadow.currentPosition - target(main.currentPosition)
        while (abs(drift) > ALIGN_TOLERANCE_MS && clock() < giveUpAt) {
            lead = CrossfadeRamp.nextSeekLead(lead, drift)
            shadow.seekTo(target(main.currentPosition + lead))
            awaitSeekLanded(shadow, valid)
            if (!valid()) return false
            drift = shadow.currentPosition - target(main.currentPosition)
        }
        if (abs(drift) > ALIGN_TOLERANCE_MS) Timber.tag(TAG).d("Instrumental starts %d ms off the song", drift)
        return valid()
    }

    /** A seek puts the player back to buffering until it has landed. */
    private suspend fun awaitSeekLanded(shadow: ExoPlayer, valid: () -> Boolean) {
        withTimeoutOrNull(ALIGN_BUDGET_MS) {
            while (valid() && shadow.playbackState != Player.STATE_READY) delay(READY_POLL_MS)
        }
    }

    /** Writes the main player's volume back, remembering that the echo of this write is ours. */
    private fun restoreMasterVolume(main: Player, volume: Float = baseVolume) {
        // A write that changes nothing raises no event, so no echo is owed (a stale one would swallow a real change).
        if (abs(main.volume - volume) > VOLUME_EPSILON) echoVolume = volume
        main.volume = volume
    }

    /**
     * The service's first look at every volume change of the session player (call before ReplayGain and
     * pause-on-zero see it). True when the change is ours or must be absorbed, so the service skips the rest.
     *
     * - A fade, or the echo of a volume we wrote: ours.
     * - While the instrumental is audible, anything that raises the main player's volume (ReplayGain
     *   re-applying, a resume) is taken as the new [baseVolume] and the main player is silenced again.
     * - The incoming player of a next-song overlap fading in: the instrumental fades out by the same
     *   amount.
     */
    fun onMainVolumeChanged(player: Player, volume: Float): Boolean {
        echoVolume?.let { echo ->
            if (abs(echo - volume) < VOLUME_EPSILON) {
                echoVolume = null
                return true
            }
        }
        if (isRamping) return true
        if (!isCrossfadedToInstrumental) return false
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
            echoVolume = 0f
            player.volume = 0f
        }
        return true
    }

    /**
     * The engine published another main player (a rebuild after a Hi-Fi toggle or an offload fallback, or the
     * end of a cancelled overlap): follow it, and keep it silent while the instrumental plays. A hand-over at
     * the end of an overlap is settled by [onTransitionFinished].
     */
    fun onMainPlayerSwapped(newMain: Player) {
        if (shadowPlayer == null || isTransitionRunning()) return
        mainPlayerRef = newMain
        if (isCrossfadedToInstrumental && newMain.volume > VOLUME_EPSILON) {
            baseVolume = newMain.volume
            shadowPlayer?.volume = baseVolume
            echoVolume = 0f
            newMain.volume = 0f
        }
    }

    /**
     * A next-song overlap transition ended (or was cancelled). If the next song is now the main player, the
     * instrumental of the song before it has played out: release it. A cancelled overlap leaves the song
     * as it was, so the instrumental comes back to full level.
     */
    fun onTransitionFinished(mainPlayer: Player) {
        val shadow = shadowPlayer ?: return
        val songId = mainPlayer.currentMediaItem?.mediaId
        if (songId != currentSongId || mainPlayer !== mainPlayerRef) {
            // The new master was never silenced by us: no volume to restore.
            teardown()
            currentSongId = songId
        } else if (isCrossfadedToInstrumental && !isRamping) {
            shadow.volume = baseVolume
        }
    }

    fun teardown() {
        generation++
        resyncJob?.cancel()
        resyncJob = null
        clipWatchJob?.cancel()
        clipWatchJob = null
        shadowPlayer?.let(::releaseQuietly)
        incomingPlayer?.let(::releaseQuietly)
        shadowPlayer = null
        incomingPlayer = null
        // The clip's file may go now (a delete asked for while it played happens here).
        clip?.let { SingClipStore.release(it.file) }
        clip = null
        mainPlayerRef = null
        isCrossfadedToInstrumental = false
        isRamping = false
        restoreInstrumentalOnResume = false
    }

    /**
     * A suspend owner (the tap-sync editor) needs to hear the vocals it is timing: snap back to
     * the original if the instrumental is audible. The owner pauses first, so the snap is silent.
     */
    private fun onSuspended() {
        if (!isCrossfadedToInstrumental) return
        val main = mainPlayerRef ?: return
        val shadow = shadowPlayer ?: return
        shadow.volume = 0f
        restoreMasterVolume(main)
        shadow.seekTo(shadowTargetMs(main.currentPosition))
        isCrossfadedToInstrumental = false
        restoreInstrumentalOnResume = true
    }

    private fun onResumed() {
        if (!restoreInstrumentalOnResume) return
        restoreInstrumentalOnResume = false
        val main = mainPlayerRef ?: return
        val resumeClip = clip
        val songId = currentSongId
        scope.launch {
            val switched = crossfadeToInstrumental(main, CrossfadeRequest(), resumeClip)
            // The song moved outside the clip meanwhile: the vocals stay, and the Sing keeps waiting.
            if (!switched && resumeClip != null) songId?.let(onClipLeft)
        }
    }

    companion object {
        /**
         * Owners that forbid crossfades for now (the lyrics tap-sync editor, which needs to hear
         * the vocals it times). Suspending snaps back to the original. Main thread only.
         */
        private val suspendedBy = mutableSetOf<String>()
        private val isSuspended: Boolean get() = suspendedBy.isNotEmpty()

        /** The service's live controller, so suspend owners can bring the vocals back. */
        private var active: java.lang.ref.WeakReference<InstrumentalCrossfadeController>? = null

        fun suspend(owner: String) {
            suspendedBy += owner
            active?.get()?.onSuspended()
        }

        fun resume(owner: String) {
            suspendedBy -= owner
            if (!isSuspended) active?.get()?.onResumed()
        }

        private const val TAG = "InstrumentalCrossfade"
        private const val RESYNC_INTERVAL_MS = 4000L
        private const val DRIFT_TOLERANCE_MS = 200L
        private const val READY_TIMEOUT_MS = 1_500L
        private const val READY_POLL_MS = 8L

        /** How well the instrumental must line up with the song before the fade (the two overlap, so a few tens of ms is audible as a phase smear). */
        private const val ALIGN_TOLERANCE_MS = 40L
        private const val ALIGN_BUDGET_MS = 250L
        private const val VOLUME_EPSILON = 0.001f
    }
}
