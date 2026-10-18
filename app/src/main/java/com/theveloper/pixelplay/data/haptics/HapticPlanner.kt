package com.theveloper.pixelplay.data.haptics

import com.theveloper.pixelplay.data.preferences.MusicHapticsFollows

/** Lets at most one event through per [minIntervalMs] (25 per second at 40 ms). */
class EventRateLimiter(private val minIntervalMs: Long) {
    private var lastMs = Long.MIN_VALUE / 2

    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }

    fun reset() {
        lastMs = Long.MIN_VALUE / 2
    }
}

/**
 * Turns detector callbacks into haptic events according to the owner's choices:
 *  - EVERYTHING: taps on kick and snare, plus a soft rumble under the bass
 *  - BEAT: taps only (kick and snare)
 *  - BASS: kick taps plus the rumble
 * Strength scales every event (20-200 %, clamped to the actuator's 0..1 range) and the limiter keeps
 * the total at about 25 events per second so the motor and the battery are not hammered.
 */
class HapticPlanner(
    private val output: Output,
    private val clockMs: () -> Long,
) : BeatDetector.Listener {

    interface Output {
        /** A short tap. [sharp] picks a crisp click (kick) over a lighter tick (snare). */
        fun tap(scale: Float, sharp: Boolean)

        /** A soft low rumble pulse. */
        fun rumble(scale: Float)
    }

    @Volatile
    var follows: MusicHapticsFollows = MusicHapticsFollows.EVERYTHING

    @Volatile
    var strengthPercent: Int = 100

    private val limiter = EventRateLimiter(MIN_EVENT_INTERVAL_MS)
    private val rumbleLimiter = EventRateLimiter(MIN_RUMBLE_INTERVAL_MS)

    private fun scaled(base: Float): Float = (base * strengthPercent / 100f).coerceIn(0.05f, 1f)

    override fun onKick(strength: Float) {
        if (!limiter.tryAcquire(clockMs())) return
        output.tap(scaled(strength), sharp = true)
    }

    override fun onSnare(strength: Float) {
        if (follows == MusicHapticsFollows.BASS) return
        if (!limiter.tryAcquire(clockMs())) return
        output.tap(scaled(strength * 0.7f), sharp = false)
    }

    override fun onBass(level: Float) {
        if (follows == MusicHapticsFollows.BEAT) return
        if (level < RUMBLE_FLOOR) return
        if (!rumbleLimiter.tryAcquire(clockMs())) return
        output.rumble(scaled(level * 0.45f))
    }

    fun reset() {
        limiter.reset()
        rumbleLimiter.reset()
    }

    companion object {
        const val MIN_EVENT_INTERVAL_MS = 40L
        const val MIN_RUMBLE_INTERVAL_MS = 90L
        const val RUMBLE_FLOOR = 0.35f
    }
}
