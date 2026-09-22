package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.exp

/**
 * Turns a parameter that jumps (a finger dragging a puck, a slider, a value from the cloud) into
 * one that moves continuously, in two stages:
 *
 *  - **per block**: a one-pole step toward the target, so a big jump takes a known time to arrive;
 *  - **per sample**: a linear ramp across the block, so the value never actually steps.
 *
 * Both stages matter. Without the ramp, a gain that changes once per 128-frame block is a 3 ms
 * staircase — audible as zipper noise on anything sustained. Without the one-pole, a puck flung
 * across the stage would slew the inter-aural delay instantly and click.
 *
 * Usage on the audio thread, once per block:
 * ```
 * var g = gain.beginBlock(target)          // value at the first sample of this block
 * val dg = gain.increment                  // add this per sample
 * for (i in 0 until n) { out[i] = in[i] * g; g += dg }
 * ```
 * [beginBlock] must be called exactly once per block with a target snapshotted once per block —
 * see `RemixParams`. Reading the volatile twice inside a block can hand you two different targets
 * and reintroduce the discontinuity this class exists to remove.
 */
class ParamSmoother(
    initial: Float = 0f,
    /** Time to cover ~63% of a step. 10 ms for gains, ~60 ms for positions, 100 ms for reverb mix. */
    private val timeConstantMs: Float = 10f,
) {
    /** Smoothed value at the start of the current block. */
    var value: Float = initial
        private set

    /** Per-sample addend for the current block. */
    var increment: Float = 0f
        private set

    private var alpha = 1f
    private var blockSize = 1
    private var endOfBlock: Float = initial

    fun prepare(sampleRateHz: Int, blockSize: Int) {
        this.blockSize = blockSize.coerceAtLeast(1)
        val blockSeconds = this.blockSize.toFloat() / sampleRateHz.coerceAtLeast(1)
        val tau = (timeConstantMs / 1000f).coerceAtLeast(1e-5f)
        alpha = 1f - exp(-blockSeconds / tau)
    }

    /** Jump straight to [target] with no ramp — for a fresh load or a seek, never mid-playback. */
    fun snapTo(target: Float) {
        value = target
        endOfBlock = target
        increment = 0f
    }

    /**
     * Advances the smoother by one block toward [target] and returns the value at the block's
     * first sample. [increment] carries the per-sample step.
     */
    fun beginBlock(target: Float): Float {
        endOfBlock = value + alpha * (target - value)
        increment = (endOfBlock - value) / blockSize
        return value
    }

    /** Commits the block. Call after processing so the next block starts where this one ended. */
    fun endBlock() {
        value = endOfBlock
        increment = 0f
    }
}
