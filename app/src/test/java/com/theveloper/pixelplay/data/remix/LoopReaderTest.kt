package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.dsp.LoopReader
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import kotlin.math.abs
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The loop seam is the one place where a small mistake is unmistakably audible, so it gets the
 * most scrutiny: every assertion here is about "does the waveform stay continuous across the
 * wrap", measured as the largest jump between consecutive output samples.
 */
class LoopReaderTest {

    private val sampleRate = 44_100
    private val regionFrames = 8_000
    private val guardFrames = 2_048
    private val xfadeFrames = 1_764 // 40 ms

    /**
     * A ramp that keeps rising through the whole buffer. Inside the region it goes 0 → 1; the
     * guard before the region holds the negative lead-in. A naive loop would jump 1 → 0 at the
     * wrap, so any failure to crossfade shows up as a step of ~1.0.
     */
    private fun rampBuffer(): RemixStemBuffer {
        val total = regionFrames + 2 * guardFrames
        val samples = FloatArray(total) { i ->
            (i - guardFrames).toFloat() / regionFrames
        }
        return RemixStemBuffer(samples, guardFrames, regionFrames, sampleRate)
    }

    private fun sineBuffer(freqHz: Float): RemixStemBuffer {
        val total = regionFrames + 2 * guardFrames
        val samples = FloatArray(total) { i ->
            sin(2.0 * Math.PI * freqHz * (i - guardFrames) / sampleRate).toFloat()
        }
        return RemixStemBuffer(samples, guardFrames, regionFrames, sampleRate)
    }

    private fun largestStep(reader: LoopReader, rate: Float, frames: Int): Float {
        var previous = reader.tap(0f)
        var worst = 0f
        repeat(frames) {
            reader.advance(rate)
            val current = reader.tap(0f)
            val step = abs(current - previous)
            if (step > worst) worst = step
            previous = current
        }
        return worst
    }

    @Test
    fun `seam stays continuous at every tape rate`() {
        for (rate in floatArrayOf(0.6f, 0.85f, 1.0f, 1.2f, 1.4f)) {
            val reader = LoopReader(rampBuffer(), xfadeFrames)
            // Three full laps, so the seam is crossed repeatedly rather than once.
            val worst = largestStep(reader, rate, (regionFrames * 3 / rate).toInt())
            // A naive wrap steps by 1.0. The ramp itself moves rate/regionFrames per frame, and
            // the crossfade roughly doubles that locally; anything under 1% of full scale is
            // inaudible and far from the failure mode.
            assertTrue(worst < 0.01f) { "rate $rate produced a step of $worst (naive wrap would be ~1.0)" }
        }
    }

    @Test
    fun `sine loop does not step at the seam`() {
        val reader = LoopReader(sineBuffer(440f), xfadeFrames)
        val worst = largestStep(reader, 1f, regionFrames * 2)
        // Largest legitimate sample-to-sample change for a 440 Hz sine at 44.1 kHz is ~0.063.
        assertTrue(worst < 0.12f) { "sine seam stepped by $worst" }
    }

    @Test
    fun `crossfade is clamped to what the guard can supply`() {
        // Asking for a longer fade than the guard holds must not read outside the array.
        val reader = LoopReader(rampBuffer(), xfadeFrames = guardFrames * 4)
        val worst = largestStep(reader, 1f, regionFrames * 2)
        assertTrue(worst < 0.05f) { "over-long crossfade produced a step of $worst" }
    }

    @Test
    fun `tap delay reads earlier audio without leaving the buffer`() {
        val reader = LoopReader(rampBuffer(), xfadeFrames)
        repeat(regionFrames / 2) { reader.advance(1f) }
        val now = reader.tap(0f)
        val earlier = reader.tap(30f) // ~0.7 ms, the worst-case inter-aural delay
        assertTrue(earlier < now) { "delayed tap ($earlier) should be behind the playhead ($now)" }
        assertTrue(abs(now - earlier - 30f / regionFrames) < 1e-3f) { "delay was not 30 frames" }
    }

    @Test
    fun `playhead wraps rather than running past the region`() {
        val reader = LoopReader(rampBuffer(), xfadeFrames)
        repeat(regionFrames * 2) { reader.advance(1.4f) }
        assertTrue(reader.positionFrames >= 0f && reader.positionFrames < regionFrames) {
            "playhead escaped the region: ${reader.positionFrames}"
        }
    }
}
