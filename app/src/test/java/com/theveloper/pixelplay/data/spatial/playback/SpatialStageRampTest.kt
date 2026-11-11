package com.theveloper.pixelplay.data.spatial.playback

import com.theveloper.pixelplay.data.spatial.SpatialFixtures
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * §7: changing preset or bypass ramps a wet/dry mix over about 80 ms; never a click. Checked as "no step between two
 * output samples is larger than the steps the signal itself takes" while profiles change and the stage bypasses.
 */
class SpatialStageRampTest {

    private val tables = SpatialPreviewTables.parse(SpatialFixtures.previewTable())
    private val rate = 48_000

    private class Run(val outL: FloatArray, val outR: FloatArray)

    /**
     * Plays a sine through [events] (frame -> preset and wet) in 256-frame blocks, starting from [start]. [sized]: the
     * rings are sized for the whole table first (as the processor does); else each preset sizes what it needs.
     */
    private fun play(
        seconds: Double,
        start: Pair<SpatialPreset?, Boolean>,
        events: Map<Int, Pair<SpatialPreset?, Boolean>>,
        inL: FloatArray = sine(seconds, 440.0, 0.0),
        inR: FloatArray = sine(seconds, 440.0, 0.7),
        sized: Boolean = true,
    ): Run {
        val kernel = SpatialStageKernel()
        if (sized) kernel.ensureCapacity(tables)
        kernel.setRampFrames(rate * SpatialStageProcessor.RAMP_MS / 1000)
        kernel.setTarget(start.first, start.second)
        kernel.reset()
        val frames = inL.size
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)
        val block = 256
        val bl = FloatArray(block); val br = FloatArray(block); val ol = FloatArray(block); val orr = FloatArray(block)
        var done = 0
        while (done < frames) {
            events[done]?.let { (preset, wet) -> kernel.setTarget(preset, wet) }
            val n = minOf(block, frames - done)
            System.arraycopy(inL, done, bl, 0, n)
            System.arraycopy(inR, done, br, 0, n)
            kernel.process(bl, br, ol, orr, n)
            System.arraycopy(ol, 0, outL, done, n)
            System.arraycopy(orr, 0, outR, done, n)
            done += n
        }
        return Run(outL, outR)
    }

    private fun sine(seconds: Double, hz: Double, phase: Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { (0.5 * sin(2 * PI * hz * it / rate + phase)).toFloat() }

    private fun maxStep(signal: FloatArray, from: Int, to: Int): Float {
        var worst = 0f
        for (i in maxOf(from, 1) until to) worst = maxOf(worst, abs(signal[i] - signal[i - 1]))
        return worst
    }

    @Test
    fun `profile changes and bypass never step further than the signal`() {
        val second = rate
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val hall = tables.preset(SpatialProfile.HALL, rate)!!
        val wide = tables.preset(SpatialProfile.WIDE, rate)!!
        // Event frames are multiples of the block size.
        val run = play(
            seconds = 3.0,
            start = studio to true,
            events = mapOf(
                (second * 0.5).toInt() / 256 * 256 to (studio to false), // bypass
                (second * 1.0).toInt() / 256 * 256 to (hall to true), // back on, another profile
                (second * 1.5).toInt() / 256 * 256 to (wide to true), // profile change while wet
                (second * 2.0).toInt() / 256 * 256 to (null to false), // off
            ),
        )
        // The signal's own steps: the settled stretches before each change (studio wet, dry, hall wet, wide wet, dry).
        val settled = listOf(0.3, 0.8, 1.3, 1.8, 2.6).map { (it * second).toInt() }
        val bound = settled.maxOf { from ->
            maxOf(maxStep(run.outL, from, from + second / 10), maxStep(run.outR, from, from + second / 10))
        }
        val worst = maxOf(maxStep(run.outL, second / 10, run.outL.size), maxStep(run.outR, second / 10, run.outR.size))
        assertTrue(worst <= bound * 1.02f + 1e-3f, "largest step $worst against the signal's own $bound")
    }

    @Test
    fun `a ramp takes about 80 ms`() {
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val kernel = SpatialStageKernel()
        kernel.ensureCapacity(tables)
        kernel.setRampFrames(rate * SpatialStageProcessor.RAMP_MS / 1000)
        kernel.setTarget(studio, wet = true)
        kernel.reset()
        assertTrue(kernel.isFullyWet)
        kernel.setTarget(studio, wet = false)
        val zeros = FloatArray(kernel.maxBlock)
        val out = FloatArray(kernel.maxBlock)
        var frames = 0
        while (!kernel.isFullyDry) {
            kernel.process(zeros, zeros, out, out, 64)
            frames += 64
            check(frames < rate) { "the ramp never ended" }
        }
        val ms = frames * 1000 / rate
        assertTrue(ms in 75..90, "ramp took $ms ms")
    }

    @Test
    fun `rings that grow while a preset plays keep its history`() {
        // A kernel that sizes per preset (setTarget alone) grows Studio's 4,096-sample room ring for Concert Hall's
        // 8,559-sample room while Studio is still fading out: Studio must keep reading the same history.
        val second = rate
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val hall = tables.preset(SpatialProfile.HALL, rate)!!
        val switchAt = second / 2 / 256 * 256
        val events = mapOf(switchAt to (hall to true))
        val grown = play(seconds = 1.5, start = studio to true, events = events, sized = false)
        val sized = play(seconds = 1.5, start = studio to true, events = events, sized = true)
        // Until Hall's coefficients take over (after Studio's 80 ms fade out), the two read the same history.
        val swap = switchAt + rate * SpatialStageProcessor.RAMP_MS / 1000
        for (i in 0 until swap) {
            assertEquals(sized.outL[i], grown.outL[i], "left $i")
            assertEquals(sized.outR[i], grown.outR[i], "right $i")
        }
        val bound = maxOf(maxStep(sized.outL, second / 10, sized.outL.size), maxStep(sized.outR, second / 10, sized.outR.size))
        val worst = maxOf(maxStep(grown.outL, second / 10, grown.outL.size), maxStep(grown.outR, second / 10, grown.outR.size))
        assertTrue(worst <= bound * 1.02f + 1e-3f, "largest step $worst against the signal's own $bound")
    }

    @Test
    fun `bypassed it is the input delayed by lowDelay, bit for bit`() {
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val inL = sine(0.2, 1_000.0, 0.0)
        val inR = sine(0.2, 300.0, 0.3)
        val run = play(0.2, start = studio to false, events = emptyMap(), inL = inL, inR = inR)
        val delay = studio.lowDelay
        for (i in inL.indices) {
            val expectedL = if (i >= delay) inL[i - delay] else 0f
            val expectedR = if (i >= delay) inR[i - delay] else 0f
            assertEquals(expectedL, run.outL[i], "left $i")
            assertEquals(expectedR, run.outR[i], "right $i")
        }
    }

    @Test
    fun `without a profile it is a plain copy with no delay`() {
        val inL = sine(0.1, 1_000.0, 0.0)
        val inR = sine(0.1, 300.0, 0.3)
        val run = play(0.1, start = null to false, events = emptyMap(), inL = inL, inR = inR)
        for (i in inL.indices) {
            assertEquals(inL[i], run.outL[i])
            assertEquals(inR[i], run.outR[i])
        }
    }

    @Test
    fun `the soft limiter leaves everything under -1 dBFS alone and bends the rest under full scale`() {
        val t = SpatialStageKernel.LIMIT_THRESHOLD
        assertEquals(0.5f, SpatialStageKernel.softLimit(0.5f))
        assertEquals(-0.89f, SpatialStageKernel.softLimit(-0.89f))
        assertEquals(t, SpatialStageKernel.softLimit(t))
        // Slope 1 where it starts: no corner.
        assertEquals(t + 1e-4f, SpatialStageKernel.softLimit(t + 1e-4f), 1e-6f)
        var last = 0f
        var x = 0.85f
        while (x <= 1.45f) {
            val y = SpatialStageKernel.softLimit(x)
            assertTrue(y >= last, "monotonic at $x")
            assertTrue(y < 1f, "under full scale at $x")
            assertEquals(-y, SpatialStageKernel.softLimit(-x), "symmetric at $x")
            last = y
            x += 0.005f
        }
        // The formula of spec section 7 at one point: t + (1 - t) * tanh((1.2 - t) / (1 - t)).
        val expected = t + (1f - t) * kotlin.math.tanh((1.2f - t) / (1f - t))
        assertEquals(expected, SpatialStageKernel.softLimit(1.2f))
    }

    @Test
    fun `the dry delay changes only while it is not heard`() {
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val kernel = SpatialStageKernel()
        kernel.ensureCapacity(tables)
        kernel.setRampFrames(rate * SpatialStageProcessor.RAMP_MS / 1000)
        kernel.setTarget(null, false)
        kernel.reset()
        assertEquals(0, kernel.currentDryDelay)
        kernel.setTarget(studio, wet = true)
        val zeros = FloatArray(kernel.maxBlock)
        val out = FloatArray(kernel.maxBlock)
        kernel.process(zeros, zeros, out, out, 256)
        assertEquals(0, kernel.currentDryDelay, "still heard during the ramp")
        repeat(40) { kernel.process(zeros, zeros, out, out, 256) }
        assertTrue(kernel.isFullyWet)
        assertEquals(studio.lowDelay, kernel.currentDryDelay)
    }
}
