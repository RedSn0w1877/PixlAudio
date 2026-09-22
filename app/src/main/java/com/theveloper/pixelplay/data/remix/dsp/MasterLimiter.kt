package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Catches the sum before it clips.
 *
 * Four stems, a resonant filter and a reverb tail *will* exceed full scale when someone pushes
 * resonance up with everything close to the listener — that is the point of the instrument. A
 * feed-forward envelope follower pulls the gain down when it does, fast enough to catch a
 * transient (1 ms) and slow enough not to pump (100 ms), with `tanh` as a hard backstop for
 * whatever slips past during the attack.
 */
class MasterLimiter(private val sampleRate: Int) {

    private var envelope = 0f
    private var gain = 1f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f

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
        // Gain only moves at release speed upward; downward it follows immediately so a transient
        // cannot punch through while the gain is still on its way down.
        gain = if (target < gain) target else gain + releaseCoeff * (target - gain)
        limiting = gain < 0.999f

        outLeft = tanh(left * gain)
        outRight = tanh(right * gain)
    }

    private fun coefficient(ms: Float): Float =
        (1f - exp(-1f / (ms / 1000f * sampleRate))).coerceIn(0f, 1f)

    private companion object {
        const val THRESHOLD = 0.89f
        const val ATTACK_MS = 1f
        const val RELEASE_MS = 100f
    }
}
