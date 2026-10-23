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
) {
    private var shadowPlayer: ExoPlayer? = null
    private var mainPlayerRef: Player? = null
    private var currentSongId: String? = null
    private var resyncJob: Job? = null

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

    /**
     * Builds the instrumental player on demand — a render that finishes *while its song is already open*
     * has no transition to build it on. Null when the song has none (or the song changed meanwhile).
     */
    private suspend fun prepareShadow(mainPlayer: Player): ExoPlayer? {
        shadowPlayer?.let { return it }
        val songId = currentSongId ?: return null
        val file = withContext(Dispatchers.IO) { TaisInstrumentalIndex.bestAvailableFile(context, songId) } ?: return null
        if (currentSongId != songId || shadowPlayer != null) return shadowPlayer
        return buildShadow(file, mainPlayer)
    }

    private fun buildShadow(file: java.io.File, mainPlayer: Player): ExoPlayer? {
        return try {
            val player = ExoPlayer.Builder(context).build().apply {
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
                seekTo(mainPlayer.currentPosition)
                playWhenReady = mainPlayer.playWhenReady
            }
            shadowPlayer = player
            mainPlayerRef = mainPlayer
            isCrossfadedToInstrumental = false
            startResyncLoop()
            player
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to prepare shadow instrumental player for %s", file.name)
            shadowPlayer = null
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
                    val drift = abs(shadow.currentPosition - main.currentPosition)
                    if (drift > DRIFT_TOLERANCE_MS) {
                        shadow.seekTo(main.currentPosition)
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
        shadowPlayer?.seekTo(positionMs)
    }

    /** Keeps the shadow at the main player's speed (e.g. the lyrics sync editor's slower speeds). */
    fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
        val shadow = shadowPlayer ?: return
        if (shadow.playbackParameters != parameters) shadow.playbackParameters = parameters
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        shadowPlayer?.playWhenReady = playWhenReady
    }

    /**
     * Fades from the song's vocals to its instrumental, as [request] asks: wait until the instrumental is in
     * step with the song, then until the planned start (a gap between sung words), then fade. True once the
     * instrumental is the one audible; false (the song untouched) when the song has no instrumental, it could
     * not be started, the song changed meanwhile or a suspend owner forbids crossfades.
     */
    suspend fun crossfadeToInstrumental(mainPlayer: Player, request: CrossfadeRequest = CrossfadeRequest()): Boolean =
        switchLock.withLock {
            if (isSuspended) return@withLock false
            syncSongWithMain(mainPlayer)
            // Already audible: nothing to do (a second request, e.g. a resume, must not fade a silent player to itself).
            if (isCrossfadedToInstrumental) return@withLock true
            val songId = currentSongId ?: return@withLock false
            val shadow = prepareShadow(mainPlayer) ?: return@withLock false
            val gen = generation
            val valid = { generation == gen && shadowPlayer === shadow && currentSongId == songId && !isSuspended }
            if (!awaitReady(shadow, valid) || !alignShadow(mainPlayer, shadow, valid)) {
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
                if (!reached) return@withLock false
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
            if (!completed && generation == gen) abortFade(main(), shadow, base)
            completed
        }

    /** Fades back from the instrumental to the vocals and frees the second player. False if there was nothing to fade back from. */
    suspend fun crossfadeToOriginal(mainPlayer: Player, durationMs: Long = CrossfadeRamp.DEFAULT_FADE_MS): Boolean =
        switchLock.withLock {
            val shadow = shadowPlayer ?: return@withLock false
            if (!isCrossfadedToInstrumental) {
                // The switch never completed: nothing is audible but the song itself.
                teardown()
                return@withLock false
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
            completed
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
     * afterwards). False only when the switch stopped being valid.
     */
    private suspend fun alignShadow(main: Player, shadow: ExoPlayer, valid: () -> Boolean): Boolean {
        shadow.playWhenReady = main.playWhenReady
        if (!main.isPlaying) {
            // Nothing moves: one seek puts them on the same timestamp exactly.
            if (shadow.currentPosition != main.currentPosition) {
                shadow.seekTo(main.currentPosition)
                awaitSeekLanded(shadow, valid)
            }
            return valid()
        }
        val giveUpAt = clock() + ALIGN_BUDGET_MS
        var lead = 0L
        var drift = shadow.currentPosition - main.currentPosition
        while (abs(drift) > ALIGN_TOLERANCE_MS && clock() < giveUpAt) {
            lead = CrossfadeRamp.nextSeekLead(lead, drift)
            shadow.seekTo(main.currentPosition + lead)
            awaitSeekLanded(shadow, valid)
            if (!valid()) return false
            drift = shadow.currentPosition - main.currentPosition
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
        shadowPlayer?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Releasing the instrumental player failed")
            }
        }
        shadowPlayer = null
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
        shadow.seekTo(main.currentPosition)
        isCrossfadedToInstrumental = false
        restoreInstrumentalOnResume = true
    }

    private fun onResumed() {
        if (!restoreInstrumentalOnResume) return
        restoreInstrumentalOnResume = false
        val main = mainPlayerRef ?: return
        scope.launch { crossfadeToInstrumental(main) }
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
