package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * One stem's loop region, held in memory as mono float samples.
 *
 * **Mono on purpose.** Each stem is spatialized from a single source — the binaural panner
 * synthesizes the stereo image by reading the same signal at two slightly different times. Keeping
 * the original stereo would double memory for material that is about to be collapsed anyway.
 *
 * **Guarded on purpose.** [samples] covers `[regionStart - guardFrames, regionEnd + guardFrames)`,
 * not just the region. The loop crossfade reads *behind* the region start (see [LoopReader]), and
 * the interpolator reads one sample either side of its position. Without the guard both would run
 * off the end of the array, which is exactly where clicks come from.
 */
class RemixStemBuffer(
    val samples: FloatArray,
    val guardFrames: Int,
    val regionFrames: Int,
    val sampleRate: Int,
    /**
     * Frames of *real* audio in front of the region. Usually the whole guard — but a loop that
     * starts at 0:00 has nothing before it, and the guard is silence. The seam crossfade reads that
     * lead-in, so trusting a silent guard made every loop from the top of a song fade to nothing
     * and snap back: a 43 ms dip and a click, on the default configuration.
     *
     * Deliberately **required**: a default of `guardFrames` would silently reintroduce the bug at
     * every construction site that forgot to think about it.
     */
    val leadInFrames: Int,
) {
    init {
        require(regionFrames > 0) { "empty region" }
        require(samples.size >= regionFrames + 2 * guardFrames) { "buffer smaller than region + guards" }
    }
}

/**
 * Reads a looping region with a click-free seam.
 *
 * The trick is where the tail comes from. Naively crossfading the end of the region into its own
 * start blends two unrelated moments and smears. Instead the fade mixes the end of the region with
 * **the audio immediately preceding the region start** — its natural lead-in — so the blend is
 * equal-power *and* plausible:
 *
 * ```
 * q in [0, N)                      position within the region
 * y = x[q]                                              when q < N - X
 * y = cos(t·π/2)·x[q] + sin(t·π/2)·x[q - N]              when q >= N - X,  t = (q - (N - X)) / X
 * ```
 *
 * `x[q - N]` lands in the guard region before the loop start. `cos²+sin² = 1`, so the blend holds
 * power constant rather than dipping in the middle the way a linear fade would.
 *
 * Interpolation is 4-point Hermite, not linear: at 0.6× linear loses audible high end, and at 1.4×
 * it aliases. Hermite costs ~8 more flops per tap and removes the whole class of complaint.
 *
 * Note the crossfade is measured in **input** frames, so at rate ≠ 1 it lasts `X / rate` output
 * frames. Equal-power still holds; only its wall-clock duration scales with the tape speed, which
 * is the musically expected behaviour.
 */
class LoopReader(
    private var buffer: RemixStemBuffer,
    xfadeFrames: Int,
) {
    private var xfade = 0
    private var position = 0f

    /**
     * True when there is not enough real audio before the region to crossfade into — a loop
     * starting at 0:00. The seam then fades briefly out and back in through silence, which is
     * continuous at the wrap, instead of crossfading into a silent guard and snapping back.
     */
    private var fadeThroughSilence = false
    private var edgeFade = 1

    /** Position within the region, in frames. Exposed for the decay-tail envelope and the UI. */
    val positionFrames: Float get() = position

    val regionFrames: Int get() = buffer.regionFrames

    init {
        setCrossfade(xfadeFrames)
    }

    fun setCrossfade(frames: Int) {
        // Never longer than the guard (the fade reads that far back) or half the region.
        val ceiling = minOf(buffer.guardFrames - HERMITE_MARGIN, buffer.regionFrames / 2)
        xfade = frames.coerceIn(1, ceiling.coerceAtLeast(1))
        // …nor longer than the audio that is really there. If the lead-in cannot cover the fade,
        // crossfading would blend the loop's end into silence and then jump back to full level.
        fadeThroughSilence = buffer.leadInFrames < xfade + HERMITE_MARGIN
        edgeFade = (buffer.sampleRate * EDGE_FADE_SECONDS).toInt()
            .coerceIn(1, (buffer.regionFrames / 4).coerceAtLeast(1))
    }

    /** Swaps in a newly loaded region, e.g. when the user moves the loop handles. */
    fun setBuffer(newBuffer: RemixStemBuffer, xfadeFrames: Int) {
        buffer = newBuffer
        setCrossfade(xfadeFrames)
        if (position >= newBuffer.regionFrames) position = 0f
    }

    fun reset() {
        position = 0f
    }

    /** Advances the playhead by [rate] frames. Call once per output frame. */
    fun advance(rate: Float) {
        val n = buffer.regionFrames
        position += rate
        if (position >= n) position -= n * floor(position / n)
        if (position < 0f) position += n * (floor(-position / n) + 1f)
    }

    /**
     * Reads the stem [delayFrames] behind the playhead, with the loop seam applied.
     *
     * The delay is how the inter-aural time difference is produced: the two ears tap the same
     * reader at offsets a fraction of a millisecond apart. Because the region is resident, this
     * costs an array read rather than a delay line.
     */
    fun tap(delayFrames: Float): Float {
        val n = buffer.regionFrames
        var q = position - delayFrames
        if (q < 0f) q += n * (floor(-q / n) + 1f)
        if (q >= n) q -= n * floor(q / n)

        val primary = hermite(buffer.guardFrames + q)

        if (fadeThroughSilence) {
            // Symmetric short fades on both sides of the seam: both reach exactly zero at the
            // wrap, so the waveform is continuous across it.
            val toEnd = n - q
            val gain = when {
                q < edgeFade -> sin(q / edgeFade * HALF_PI)
                toEnd < edgeFade -> sin(toEnd / edgeFade * HALF_PI)
                else -> 1f
            }
            return primary * gain
        }

        val seamStart = n - xfade
        if (q < seamStart) return primary

        val t = ((q - seamStart) / xfade).coerceIn(0f, 1f)
        val fadeOut = cos(t * HALF_PI)
        val fadeIn = sin(t * HALF_PI)
        val leadIn = hermite(buffer.guardFrames + q - n)
        return fadeOut * primary + fadeIn * leadIn
    }

    /** 4-point, 3rd-order Hermite (Catmull-Rom) interpolation at a fractional index. */
    private fun hermite(index: Float): Float {
        val samples = buffer.samples
        val i = floor(index).toInt()
        val f = index - i
        // The guard makes i-1 and i+2 safe for every legal position; clamp anyway so a bad region
        // can never crash the audio thread — silence beats an IndexOutOfBounds in a DSP callback.
        val i0 = (i - 1).coerceIn(0, samples.size - 1)
        val i1 = i.coerceIn(0, samples.size - 1)
        val i2 = (i + 1).coerceIn(0, samples.size - 1)
        val i3 = (i + 2).coerceIn(0, samples.size - 1)
        val y0 = samples[i0]
        val y1 = samples[i1]
        val y2 = samples[i2]
        val y3 = samples[i3]

        val c0 = y1
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * f + c2) * f + c1) * f + c0
    }

    companion object {
        /** Hermite reads one sample either side, so the guard needs a couple of frames spare. */
        const val HERMITE_MARGIN = 4
        private const val HALF_PI = (PI / 2).toFloat()

        /** Length of each side of a fade-through-silence seam. Short enough to read as a join. */
        private const val EDGE_FADE_SECONDS = 0.004f
    }
}
