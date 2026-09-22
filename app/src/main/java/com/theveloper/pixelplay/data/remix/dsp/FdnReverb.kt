package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Feedback-delay-network reverb: two input allpasses for diffusion, then four delay lines mixed
 * through a Householder matrix, each damped in its feedback path.
 *
 * FDN rather than the cheaper Schroeder comb bank because Schroeder rings metallically, and on
 * sparse stem material (an isolated vocal, a bass line) that is exactly what you hear. The
 * Householder mixing `out_i = in_i − ½·Σin` costs three adds and one multiply for the whole
 * matrix — no per-tap multiplies at all — so the quality is close to free.
 *
 * Delay lengths are mutually prime so their echo patterns don't line up and turn into a pitch.
 * Total memory is ~44 KB.
 *
 * The room is shared by every stem rather than one reverb per stem: four rooms cost four times as
 * much and sound *worse*, because the stems stop sharing a space.
 */
class FdnReverb(private val sampleRate: Int) {

    private val lines = Array(LINE_LENGTHS.size) { FloatArray(LINE_LENGTHS[it]) }
    private val writeIndex = IntArray(LINE_LENGTHS.size)
    private val damping = FloatArray(LINE_LENGTHS.size)
    private val feedback = FloatArray(LINE_LENGTHS.size)

    private val diffusionA = FloatArray(DIFFUSION_A)
    private val diffusionB = FloatArray(DIFFUSION_B)
    private var diffusionIndexA = 0
    private var diffusionIndexB = 0

    private val preDelay = FloatArray((MAX_PREDELAY_MS / 1000f * sampleRate).toInt() + 1)
    private var preDelayIndex = 0
    private var preDelayFrames = 0

    private var dampCoefficient = 0.4f

    /** Preallocated: the audio thread must not allocate, and this is read every single frame. */
    private val taps = FloatArray(LINE_LENGTHS.size)

    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    fun reset() {
        lines.forEach { it.fill(0f) }
        writeIndex.fill(0)
        damping.fill(0f)
        diffusionA.fill(0f)
        diffusionB.fill(0f)
        preDelay.fill(0f)
        diffusionIndexA = 0
        diffusionIndexB = 0
        preDelayIndex = 0
        outLeft = 0f
        outRight = 0f
    }

    /** Call per block. [rt60] in seconds, [damp] 0..0.9, [preDelayMs] 0..120. */
    fun setParams(rt60: Float, damp: Float, preDelayMs: Float) {
        dampCoefficient = damp.coerceIn(0f, 0.9f)
        val decay = rt60.coerceIn(0.1f, 12f)
        for (i in LINE_LENGTHS.indices) {
            // g such that the line decays 60 dB over rt60: g = 10^(-3·L/(rt60·fs))
            feedback[i] = 10f.pow(-3f * LINE_LENGTHS[i] / (decay * sampleRate))
        }
        preDelayFrames = ((preDelayMs.coerceIn(0f, MAX_PREDELAY_MS) / 1000f) * sampleRate)
            .toInt().coerceIn(0, preDelay.size - 1)
    }

    /** Processes one mono send sample and updates [outLeft]/[outRight]. */
    fun processSample(input: Float) {
        // Pre-delay: the gap before the room answers. Even 20 ms makes a source feel placed
        // rather than smeared into the reverb.
        preDelay[preDelayIndex] = input
        val readIndex = (preDelayIndex - preDelayFrames + preDelay.size) % preDelay.size
        val delayed = preDelay[readIndex]
        preDelayIndex = (preDelayIndex + 1) % preDelay.size

        val diffused = allpassB(allpassA(delayed))

        var sum = 0f
        for (i in lines.indices) {
            val line = lines[i]
            val idx = writeIndex[i]
            val v = line[idx]
            // One-pole damping inside the feedback path: high frequencies die first, as in a real
            // room. Without it the tail sounds like a metal tank.
            damping[i] += (1f - dampCoefficient) * (v - damping[i])
            taps[i] = damping[i]
            sum += taps[i]
        }

        val householder = 0.5f * sum
        for (i in lines.indices) {
            val mixed = taps[i] - householder
            val line = lines[i]
            line[writeIndex[i]] = diffused + mixed * feedback[i]
            writeIndex[i] = (writeIndex[i] + 1) % line.size
        }

        // Opposite pairs to each ear, so the tail is decorrelated rather than centred.
        outLeft = taps[0] + taps[2]
        outRight = taps[1] + taps[3]
    }

    private fun allpassA(x: Float): Float {
        val buffered = diffusionA[diffusionIndexA]
        val y = -ALLPASS_G * x + buffered
        diffusionA[diffusionIndexA] = x + ALLPASS_G * y
        diffusionIndexA = (diffusionIndexA + 1) % diffusionA.size
        return y
    }

    private fun allpassB(x: Float): Float {
        val buffered = diffusionB[diffusionIndexB]
        val y = -ALLPASS_G * x + buffered
        diffusionB[diffusionIndexB] = x + ALLPASS_G * y
        diffusionIndexB = (diffusionIndexB + 1) % diffusionB.size
        return y
    }

    companion object {
        /** Mutually prime, 33–62 ms at 44.1 kHz. */
        private val LINE_LENGTHS = intArrayOf(1447, 1867, 2287, 2731)
        private const val DIFFUSION_A = 142
        private const val DIFFUSION_B = 379
        private const val ALLPASS_G = 0.5f
        private const val MAX_PREDELAY_MS = 120f

        /** Equal-power dry/wet so the mix control doesn't dip in loudness at the midpoint. */
        fun dryGain(mix: Float): Float = cos(mix.coerceIn(0f, 1f) * (PI / 2).toFloat())

        fun wetGain(mix: Float): Float = sin(mix.coerceIn(0f, 1f) * (PI / 2).toFloat())
    }
}
