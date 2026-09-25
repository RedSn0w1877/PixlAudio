package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import kotlin.math.abs

/**
 * The lyrics time base (spec §3.4). Once per frame, [tick] reads the player position through
 * [positionProvider] (expected to be the frame-accurate, speed-aware `framePositionMs()` on the
 * main thread), adds the lyric sync offset, and publishes the result in [nowMs].
 *
 * - **Monotonic guard:** while playing, backward steps smaller than [BACKWARD_JITTER_MS] are
 *   rejected; `MediaController` extrapolation can jitter backward when a session update lands.
 * - **Seek detection:** a jump of more than [SEEK_THRESHOLD_MS] away from the prediction
 *   (last time + elapsed frame time × speed while playing) counts as a seek; the engine picks it
 *   up once through [consumeSeek].
 *
 * [nowMs] is snapshot state: read it only in draw (and only for hot rows), never in composition.
 */
@Stable
class LyricsClock(
    private val positionProvider: () -> Long,
    private val offsetMsProvider: () -> Long = { 0L },
) {
    private val nowState = mutableLongStateOf(0L)

    /** Lyrics time in ms: `playerPosition + lyricsSyncOffset`, guarded. Snapshot state. */
    val nowMs: Long get() = nowState.longValue

    /** Same value as [nowMs] without a snapshot read, for the frame loop. */
    var currentMs: Long = 0L
        private set

    /** Set by the owner from the player state; the guard and the prediction only apply while playing. */
    var isPlaying: Boolean = false

    /** Playback speed used to predict the next position (seek detection only). */
    var playbackSpeed: Float = 1f

    /** Number of seeks detected since construction or [reset]. */
    var seekCount: Int = 0
        private set

    private var initialized = false
    private var lastFrameNanos = 0L
    private var seekPending = false

    /**
     * Samples the player once. Call exactly once per frame, before `LyricsEngine.step`.
     * @return `true` if this sample was a seek.
     */
    fun tick(frameNanos: Long): Boolean {
        val raw = positionProvider() + offsetMsProvider()
        if (!initialized) {
            initialized = true
            lastFrameNanos = frameNanos
            publish(raw)
            return false
        }
        val elapsedMs = ((frameNanos - lastFrameNanos) / 1_000_000L).coerceAtLeast(0L)
        lastFrameNanos = frameNanos

        val predicted = if (isPlaying) currentMs + (elapsedMs * playbackSpeed).toLong() else currentMs
        val isSeek = abs(raw - predicted) > SEEK_THRESHOLD_MS
        if (isSeek) {
            seekPending = true
            seekCount++
            publish(raw)
            return true
        }
        val backward = currentMs - raw
        if (isPlaying && backward in 1 until BACKWARD_JITTER_MS) {
            // Jitter: hold the current time rather than stepping back.
            return false
        }
        publish(raw)
        return false
    }

    /** Returns whether a seek happened since the last call, and clears the flag. */
    fun consumeSeek(): Boolean {
        val s = seekPending
        seekPending = false
        return s
    }

    /** Flags a seek the owner knows about (e.g. tap-to-seek) without waiting for detection. */
    fun markSeek() {
        seekPending = true
        seekCount++
    }

    /** Forget the history (song change): the next [tick] adopts the position as-is. */
    fun reset() {
        initialized = false
        seekPending = false
    }

    private fun publish(value: Long) {
        currentMs = value
        if (nowState.longValue != value) nowState.longValue = value
    }

    companion object {
        const val BACKWARD_JITTER_MS = 80L
        const val SEEK_THRESHOLD_MS = 1_000L
    }
}
