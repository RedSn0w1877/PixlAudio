package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Catches the sum before it clips — and is a straight wire the rest of the time.
 *
 * Four stems, a resonant filter and a reverb tail *will* exceed full scale when someone pushes
 * resonance up with everything close to the listener; that is the point of the instrument. A
 * feed-forward envelope follower pulls the gain down when it does.
 *
 * **The output stage is identity below [KNEE].** This used to end with `tanh(left * gain)` on
 * every sample, which is not a limiter backstop but an always-on saturator on the master bus:
 * 2 % THD at −6 dBFS and 5.5 % at the limiter's own 0.89 operating point, as broadband
 * program-correlated intermodulation. That is exactly what "gritty and staticy" describes, and
 * it only showed up on headphones because phone speakers mask it. [KNEE] sits above
 * [THRESHOLD], so once the gain stage has done its job the shaper never engages; it only bends
 * the one transient that gets through during the attack.
 */
class MasterLimiter(private val sampleRate: Int) {

    private var envelope = 0f
    private var gain = 1f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var downCoeff = 0f

    /** True while the limiter is actually pulling gain down — the UI shows this as a clip light. */
    var limiting: Boolean = false
        private set

    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    init {
        attackCoeff = coefficient(ATTACK_MS)
        releaseCoeff = coefficient(RELEASE_MS)
        downCoeff = coefficient(DOWN_MS)
    }

    fun reset() {
        envelope = 0f
        gain = 1f
        limiting = false
    }

    fun processFrame(left: Float, right: Float) {
        val peak = maxOf(abs(left), abs(right))
        val coeff = if (peak > envelope) attackCoeff else releaseCoeff
        envelope += coeff * (peak - envelope)

        val target = if (envelope > THRESHOLD) THRESHOLD / envelope else 1f
        // Down fast, up slow — but *smoothly* down. Stepping straight to the target made the gain
        // track a 60 Hz bass note's envelope within each half-cycle, which is amplitude
        // modulation, which is another sideband skirt on top of the one tanh was adding.
        gain += (if (target < gain) downCoeff else releaseCoeff) * (target - gain)
        limiting = gain < 0.999f

        outLeft = ceiling(left * gain)
        outRight = ceiling(right * gain)
    }

    /** Identity below [KNEE]; tanh-shaped only in the last few percent before full scale. */
    private fun ceiling(x: Float): Float {
        val a = if (x < 0f) -x else x
        if (a <= KNEE) return x
        val shaped = KNEE + HEAD * tanh((a - KNEE) / HEAD)
        return if (x < 0f) -shaped else shaped
    }

    private fun coefficient(ms: Float): Float =
        (1f - exp(-1f / (ms / 1000f * sampleRate))).coerceIn(0f, 1f)

    private companion object {
        const val THRESHOLD = 0.89f

        /** Above [THRESHOLD], so in steady state the shaper is idle and adds exactly nothing. */
        const val KNEE = 0.92f
        const val HEAD = 1f - KNEE

        const val ATTACK_MS = 1f
        const val DOWN_MS = 2f
        const val RELEASE_MS = 100f
    }
}
