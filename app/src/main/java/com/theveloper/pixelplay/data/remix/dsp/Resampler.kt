package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts a resident stem to the device's sample rate, once, at load time.
 *
 * This used to happen on the audio thread: a 44.1 kHz stem played through a 48 kHz track by
 * scaling the read rate and letting the 4-point Hermite interpolator fill the gaps. Hermite is a
 * fine *tape-speed* interpolator — small, slowly varying offsets — and a poor sample-rate
 * converter: its images sit only ~33 dB down at 8 kHz, which on headphones is harshness laid over
 * everything. Doing it here costs a fraction of a second off the audio thread, where allocation
 * is free and a proper windowed-sinc is affordable, and leaves the real-time path running at a
 * ratio of exactly 1.0 in the common case.
 *
 * The kernel is a Kaiser-windowed sinc (β = 8.6, roughly −90 dB stopband), tabulated at
 * [PHASES] sub-sample phases and linearly interpolated between them, so no transcendental runs
 * per output sample.
 */
object Resampler {

    private const val HALF_TAPS = 16
    private const val PHASES = 512
    private const val BETA = 8.6

    /**
     * Returns [buffer] at [targetRate], or [buffer] itself if it is already there.
     *
     * Geometry is preserved in *time*: the region start maps exactly onto the region start, so a
     * region that began on a downbeat still begins on it. The guard grows or shrinks with the
     * ratio; since the caller sized it in device frames and it was read as source frames, it only
     * ever grows here, which is the safe direction.
     */
    fun toRate(buffer: RemixStemBuffer, targetRate: Int): RemixStemBuffer {
        val fromRate = buffer.sampleRate
        if (fromRate == targetRate || fromRate <= 0 || targetRate <= 0) return buffer

        val ratio = targetRate.toDouble() / fromRate           // output frames per input frame
        val step = fromRate.toDouble() / targetRate            // input frames per output frame

        val newGuard = floor(buffer.guardFrames * ratio).toInt()
        val newRegion = (buffer.regionFrames * ratio).roundToInt().coerceAtLeast(1)
        val newLeadIn = floor(buffer.leadInFrames * ratio).toInt().coerceIn(0, newGuard)
        val out = FloatArray(newRegion + 2 * newGuard)

        // Cut off just below the lower of the two Nyquists, with a little transition band.
        // Upsampling (48k from 44.1k) cuts at the source Nyquist; downsampling at the target's.
        val cutoff = 0.5 * min(1.0, ratio) * 0.94
        // A lower cutoff means a wider sinc, so the kernel spans more input samples.
        val half = (HALF_TAPS / min(1.0, ratio)).toInt().coerceAtLeast(HALF_TAPS)
        val table = kernelTable(cutoff, half)

        val src = buffer.samples
        val last = src.size - 1
        for (m in out.indices) {
            // Anchor the region start: output frame newGuard lands on input frame guardFrames.
            val pos = buffer.guardFrames + (m - newGuard) * step
            val base = floor(pos).toInt()
            val frac = pos - base

            var acc = 0.0
            var k = -half + 1
            while (k <= half) {
                val idx = base + k
                if (idx in 0..last) {
                    acc += src[idx] * tap(table, half, k - frac)
                }
                k++
            }
            out[m] = acc.toFloat()
        }

        return RemixStemBuffer(
            samples = out,
            guardFrames = newGuard,
            regionFrames = newRegion,
            sampleRate = targetRate,
            leadInFrames = newLeadIn,
        )
    }

    /** Kernel value at a fractional input offset [t], interpolated from the phase table. */
    private fun tap(table: FloatArray, half: Int, t: Double): Double {
        val a = abs(t)
        if (a >= half) return 0.0
        val x = a * PHASES
        val i = x.toInt()
        val f = x - i
        val v0 = table[i]
        val v1 = table[min(i + 1, table.size - 1)]
        return v0 + (v1 - v0) * f
    }

    /**
     * One side of the symmetric kernel, sampled [PHASES] times per input sample out to [half].
     * Normalised so a DC input comes out at exactly unity gain.
     */
    private fun kernelTable(cutoff: Double, half: Int): FloatArray {
        val n = half * PHASES + 1
        val table = FloatArray(n)
        val i0Beta = besselI0(BETA)
        for (i in 0 until n) {
            val t = i.toDouble() / PHASES
            val x = 2.0 * cutoff * t
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = t / half
            val window = if (r >= 1.0) 0.0 else besselI0(BETA * sqrt(1.0 - r * r)) / i0Beta
            table[i] = (2.0 * cutoff * sinc * window).toFloat()
        }
        // DC gain: sum the kernel at integer offsets and rescale so a constant stays constant.
        var dc = table[0].toDouble()
        for (k in 1 until half) dc += 2.0 * table[k * PHASES]
        if (dc > 0.0) {
            val scale = (1.0 / dc).toFloat()
            for (i in table.indices) table[i] *= scale
        }
        return table
    }

    /** Zeroth-order modified Bessel function of the first kind, by its power series. */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        var k = 1
        while (k < 64) {
            term *= q / (k.toDouble() * k)
            sum += term
            if (term < sum * 1e-12) break
            k++
        }
        return sum
    }
}
