package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.dsp.BinauralPanner
import com.theveloper.pixelplay.data.remix.dsp.Biquad
import com.theveloper.pixelplay.data.remix.dsp.FdnReverb
import com.theveloper.pixelplay.data.remix.dsp.MasterLimiter
import com.theveloper.pixelplay.data.remix.dsp.ParamSmoother
import com.theveloper.pixelplay.data.remix.dsp.StateVariableFilter
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemixDspTest {

    private val sampleRate = 44_100
    private val blockSize = 128

    @Test
    fun `smoother ramps instead of stepping`() {
        val smoother = ParamSmoother(initial = 0f, timeConstantMs = 10f)
        smoother.prepare(sampleRate, blockSize)

        var previous = 0f
        var worstStep = 0f
        repeat(200) {
            var value = smoother.beginBlock(1f)
            repeat(blockSize) {
                worstStep = maxOf(worstStep, abs(value - previous))
                previous = value
                value += smoother.increment
            }
            smoother.endBlock()
        }

        assertTrue(worstStep < 0.01f) { "smoother stepped by $worstStep" }
        assertTrue(smoother.value > 0.99f) { "smoother never arrived: ${smoother.value}" }
    }

    @Test
    fun `smoother settles within roughly its time constant`() {
        val smoother = ParamSmoother(initial = 0f, timeConstantMs = 10f)
        smoother.prepare(sampleRate, blockSize)
        val blocksIn10ms = (sampleRate * 0.010f / blockSize).toInt()
        repeat(blocksIn10ms) {
            smoother.beginBlock(1f)
            smoother.endBlock()
        }
        // One time constant is ~63%.
        assertTrue(smoother.value in 0.5f..0.8f) { "after one tau: ${smoother.value}" }
    }

    @Test
    fun `low pass attenuates above cutoff and passes below`() {
        fun rmsThrough(freq: Float): Float {
            val filter = Biquad().apply { setLowPass(1_000f, 0.707f, sampleRate) }
            var sum = 0.0
            val n = sampleRate
            repeat(n) { i ->
                val x = sin(2.0 * Math.PI * freq * i / sampleRate).toFloat()
                val y = filter.process(x)
                if (i > n / 2) sum += (y * y).toDouble() // skip the settling half
            }
            return kotlin.math.sqrt(sum / (n / 2)).toFloat()
        }

        val passed = rmsThrough(100f)
        val stopped = rmsThrough(8_000f)
        assertTrue(passed > 0.6f) { "passband was attenuated: $passed" }
        assertTrue(stopped < 0.1f) { "stopband leaked: $stopped" }
    }

    @Test
    fun `state variable filter stays stable while swept hard`() {
        val svf = StateVariableFilter()
        var worst = 0f
        var phase = 0.0
        repeat(400) { block ->
            // Sweep 200 Hz -> 18 kHz across 400 blocks, high resonance.
            val cutoff = 200f + (17_800f * block / 400f)
            svf.setCutoff(cutoff, 8f, sampleRate)
            repeat(blockSize) {
                phase += 2.0 * Math.PI * 220.0 / sampleRate
                val x = sin(phase).toFloat()
                svf.process(x)
                worst = maxOf(worst, abs(svf.lowPass), abs(svf.bandPass))
            }
        }
        assertTrue(worst.isFinite()) { "filter produced a non-finite sample" }
        // With Q=8 resonant gain is expected, but a sweep must not run away.
        assertTrue(worst < 20f) { "swept filter blew up to $worst" }
    }

    @Test
    fun `reverb decays, stays finite, and honours relative rt60`() {
        fun tailEnergy(rt60: Float): Double {
            val reverb = FdnReverb(sampleRate)
            reverb.reset()
            reverb.setParams(rt60, damp = 0.3f, preDelayMs = 0f)
            reverb.processSample(1f)
            var energy = 0.0
            repeat(sampleRate) { i ->
                reverb.processSample(0f)
                if (i > sampleRate / 2) {
                    energy += (reverb.outLeft * reverb.outLeft + reverb.outRight * reverb.outRight).toDouble()
                }
            }
            return energy
        }

        val shortTail = tailEnergy(0.5f)
        val longTail = tailEnergy(5f)
        assertTrue(shortTail.isFinite() && longTail.isFinite()) { "reverb went non-finite" }
        assertTrue(longTail > shortTail * 5) { "rt60 barely changed the tail: $shortTail vs $longTail" }
    }

    @Test
    fun `reverb survives a long hot input without blowing up`() {
        val reverb = FdnReverb(sampleRate)
        reverb.setParams(rt60 = 6f, damp = 0.1f, preDelayMs = 20f)
        val random = Random(7)
        var worst = 0f
        repeat(sampleRate * 20) {
            reverb.processSample(random.nextFloat() * 2f - 1f)
            worst = maxOf(worst, abs(reverb.outLeft))
        }
        assertTrue(worst.isFinite()) { "reverb produced NaN/Inf under sustained noise" }
        assertTrue(worst < 200f) { "reverb feedback ran away to $worst" }
    }

    @Test
    fun `limiter holds the ceiling`() {
        val limiter = MasterLimiter(sampleRate)
        var worst = 0f
        repeat(sampleRate) { i ->
            val loud = 4f * sin(2.0 * Math.PI * 200.0 * i / sampleRate).toFloat()
            limiter.processFrame(loud, loud)
            if (i > 1_000) worst = maxOf(worst, abs(limiter.outLeft))
        }
        assertTrue(worst <= 1.0f) { "limiter let $worst through" }
        assertTrue(limiter.limiting) { "limiter should report that it is working" }
    }

    @Test
    fun `panner produces the expected inter-aural delay at the sides`() {
        val panner = BinauralPanner(sampleRate)
        panner.prepare(blockSize)
        // Hard right, one metre away. update() opens a block and endBlock() commits it — the same
        // pairing RemixGraph uses, and without it the smoothers never advance.
        repeat(300) {
            panner.update(x = 1f, y = 0f, z = 0f, yaw = 0f, pitch = 0f)
            panner.endBlock()
        }

        val expected = BinauralPanner.maxItdFrames(sampleRate).toFloat()
        assertTrue(panner.itdLeftFrames > expected * 0.8f) {
            "left ear should lag for a source on the right, got ${panner.itdLeftFrames} of ~$expected"
        }
        assertTrue(panner.itdRightFrames < 1f) {
            "near ear should not be delayed, got ${panner.itdRightFrames}"
        }
    }

    @Test
    fun `panner centres a source directly ahead`() {
        val panner = BinauralPanner(sampleRate)
        panner.prepare(blockSize)
        repeat(300) {
            panner.update(x = 0f, y = 0f, z = -1f, yaw = 0f, pitch = 0f)
            panner.endBlock()
        }
        assertTrue(abs(panner.itdLeftFrames - panner.itdRightFrames) < 1f) {
            "a source ahead should reach both ears together"
        }
    }

    @Test
    fun `turning the listener moves the image to the other ear`() {
        val panner = BinauralPanner(sampleRate)
        panner.prepare(blockSize)
        // Source on the right; then the listener turns 90 degrees right, putting it dead ahead.
        repeat(300) {
            panner.update(x = 1f, y = 0f, z = 0f, yaw = 0f, pitch = 0f)
            panner.endBlock()
        }
        val before = panner.itdLeftFrames
        repeat(300) {
            panner.update(x = 1f, y = 0f, z = 0f, yaw = (Math.PI / 2).toFloat(), pitch = 0f)
            panner.endBlock()
        }
        val after = panner.itdLeftFrames
        assertTrue(after < before * 0.5f) { "head turn did not recentre the source: $before -> $after" }
    }
}
