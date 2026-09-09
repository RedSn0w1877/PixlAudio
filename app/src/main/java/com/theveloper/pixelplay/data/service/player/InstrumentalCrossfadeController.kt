package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.math.abs

/**
 * Keeps a second, silent ExoPlayer running in lockstep with the main session player whenever the
 * current song has a rendered instrumental available, so switching to it (the "Magic
 * Instrumentalize" toggle) is a volume crossfade instead of a hard media-item swap — both tracks
 * are already playing in sync, only which one is audible changes.
 *
 * This player never touches audio focus — `setAudioAttributes(attrs, handleAudioFocus = false)`,
 * same convention as [DualPlayerEngine]'s own players — because focus is owned entirely by the
 * main player; this is purely a second, normally-silent output riding on the session's existing
 * grant. It also never reaches [androidx.media3.session.MediaSession]: position/duration/seek
 * commands from the lock screen, notification, Android Auto etc. all keep talking to the main
 * player exactly as before, and this class just mirrors whatever that player does.
 */
class InstrumentalCrossfadeController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var shadowPlayer: ExoPlayer? = null
    private var currentSongId: String? = null
    private var resyncJob: Job? = null

    var isCrossfadedToInstrumental: Boolean = false
        private set

    val hasInstrumentalReady: Boolean
        get() = shadowPlayer != null

    /** Call from the main player's `onMediaItemTransition`. Tears down the old shadow player and prepares a new one if the incoming song already has a render on disk. */
    fun onSongChanged(songId: String?, mainPlayer: Player) {
        if (songId == currentSongId) return
        val wasCrossfaded = isCrossfadedToInstrumental
        teardown()
        if (wasCrossfaded) {
            // The previous song left the main player silenced mid-crossfade — the new song's
            // vocals must not inherit that.
            mainPlayer.volume = 1f
        }
        currentSongId = songId
        if (songId == null) return

        resolveInstrumentalFile(songId)?.let { file -> preparePlayer(file, mainPlayer) }
    }

    /**
     * Fallback for a render that finishes *while its song is already open* — `onSongChanged`
     * only fires on an actual track change, so this covers the gap by preparing on demand right
     * before the crossfade the user just requested.
     */
    private fun ensurePrepared(mainPlayer: Player): Boolean {
        if (shadowPlayer != null) return true
        val songId = currentSongId ?: return false
        val file = resolveInstrumentalFile(songId) ?: return false
        preparePlayer(file, mainPlayer)
        return shadowPlayer != null
    }

    private fun resolveInstrumentalFile(songId: String): File? {
        return TaisInstrumentalIndex.bestAvailableFile(context, songId)
    }

    private fun preparePlayer(file: File, mainPlayer: Player) {
        try {
            val player = ExoPlayer.Builder(context).build().apply {
                val attrs = Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                seekTo(mainPlayer.currentPosition)
                playWhenReady = mainPlayer.playWhenReady
            }
            shadowPlayer = player
            isCrossfadedToInstrumental = false
            startResyncLoop(mainPlayer)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to prepare shadow instrumental player for %s", file.name)
            shadowPlayer = null
        }
    }

    private fun startResyncLoop(mainPlayer: Player) {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            while (true) {
                delay(RESYNC_INTERVAL_MS)
                val shadow = shadowPlayer ?: break
                if (!mainPlayer.isPlaying) continue
                val drift = abs(shadow.currentPosition - mainPlayer.currentPosition)
                if (drift > DRIFT_TOLERANCE_MS) {
                    shadow.seekTo(mainPlayer.currentPosition)
                }
            }
        }
    }

    /** Call on the main player's seek (position discontinuity, reason SEEK) — "match the exact timestamp" instead of waiting up to [RESYNC_INTERVAL_MS] for the next periodic correction. */
    fun onMainSeek(positionMs: Long) {
        shadowPlayer?.seekTo(positionMs)
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        shadowPlayer?.playWhenReady = playWhenReady
    }

    /** Returns false (no-op) if no render exists for the current song. */
    suspend fun crossfadeToInstrumental(mainPlayer: Player, durationMs: Long = DEFAULT_CROSSFADE_MS): Boolean {
        if (!ensurePrepared(mainPlayer)) return false
        val shadow = shadowPlayer ?: return false
        shadow.seekTo(mainPlayer.currentPosition)
        shadow.playWhenReady = mainPlayer.playWhenReady
        runCrossfade(from = mainPlayer, to = shadow, durationMs = durationMs)
        isCrossfadedToInstrumental = true
        return true
    }

    suspend fun crossfadeToOriginal(mainPlayer: Player, durationMs: Long = DEFAULT_CROSSFADE_MS): Boolean {
        val shadow = shadowPlayer ?: return false
        runCrossfade(from = shadow, to = mainPlayer, durationMs = durationMs)
        isCrossfadedToInstrumental = false
        return true
    }

    private suspend fun runCrossfade(from: Player, to: Player, durationMs: Long) {
        val steps = (durationMs / CROSSFADE_STEP_MS).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            from.volume = (1f - t).coerceIn(0f, 1f)
            to.volume = t.coerceIn(0f, 1f)
            delay(CROSSFADE_STEP_MS)
        }
        from.volume = 0f
        to.volume = 1f
    }

    fun teardown() {
        resyncJob?.cancel()
        resyncJob = null
        shadowPlayer?.release()
        shadowPlayer = null
        isCrossfadedToInstrumental = false
    }

    companion object {
        private const val TAG = "InstrumentalCrossfade"
        private const val RESYNC_INTERVAL_MS = 4000L
        private const val DRIFT_TOLERANCE_MS = 200L
        private const val CROSSFADE_STEP_MS = 16L
        private const val DEFAULT_CROSSFADE_MS = 700L
    }
}
