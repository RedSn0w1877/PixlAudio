package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.dsp.BinauralPanner
import com.theveloper.pixelplay.data.remix.dsp.LoopReader
import com.theveloper.pixelplay.data.remix.dsp.MasterLimiter
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import com.theveloper.pixelplay.data.remix.dsp.Resampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One test per defect found when a real listener on real headphones called the studio "gritty and
 * staticy" and said the spatialisation "doesn't really work". Each test pins the specific failure
 * that was measured, so none of them can quietly come back.
 */
class RemixArtifactRegressionTest {

    private val rate = 48_000

    // ───────────────────────────────────────────────────────────────── grit

    /**
     * The limiter used to end in `tanh(x * gain)` on every sample: 2 % THD at −6 dBFS, 5.5 % at
     * its own operating point. Below the threshold it must now be a straight wire.
     */
    @Test
    fun `limiter is bit-transparent below its threshold`() {
        val limiter = MasterLimiter(rate)
        var worst = 0f
        repeat(rate) { i ->
            val x = 0.7f * sin(2.0 * PI * 440.0 * i / rate).toFloat()
            limiter.processFrame(x, -x)
            worst = maxOf(worst, abs(limiter.outLeft - x), abs(limiter.outRight + x))
        }
        assertEquals(0f, worst, "a −3 dBFS sine must pass untouched, but was bent by up to $worst")
        assertTrue(!limiter.limiting)
    }

    @Test
    fun `limiter still holds the ceiling on a hot signal`() {
        val limiter = MasterLimiter(rate)
        var worst = 0f
        repeat(rate) { i ->
            val x = 4f * sin(2.0 * PI * 200.0 * i / rate).toFloat()
            limiter.processFrame(x, x)
            if (i > 2_000) worst = maxOf(worst, abs(limiter.outLeft))
        }
        assertTrue(worst <= 1.0f) { "limiter let $worst through" }
    }

    /**
     * 44.1 → 48 kHz used to be done by the tape interpolator on the audio thread, leaving images
     * ~33 dB down. Converting once with a real filter must keep a tone's frequency and level and
     * leave the rest of the spectrum clean.
     */
    @Test
    fun `resampler converts 44k1 to 48k cleanly`() {
        val from = 44_100
        val guard = 256
        val region = from // one second
        val freq = 1_000.0
        val src = FloatArray(region + 2 * guard) { i ->
            (0.5 * sin(2.0 * PI * freq * (i - guard) / from)).toFloat()
        }
        val out = Resampler.toRate(RemixStemBuffer(src, guard, region, from, guard), rate)

        assertEquals(rate, out.sampleRate)
        assertEquals(rate, out.regionFrames)

        // Least-squares fit of the ideal 1 kHz sine at 48 kHz over the middle of the region; the
        // residual is everything the converter added.
        val start = out.guardFrames + 2_000
        val n = 30_000
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var ys = 0.0; var yc = 0.0
        for (k in 0 until n) {
            val t = (start + k - out.guardFrames).toDouble() / rate
            val s = sin(2 * PI * freq * t); val c = cos(2 * PI * freq * t)
            val y = out.samples[start + k].toDouble()
            ss += s * s; cc += c * c; sc += s * c; ys += y * s; yc += y * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        var residual = 0.0; var signal = 0.0
        for (k in 0 until n) {
            val t = (start + k - out.guardFrames).toDouble() / rate
            val fit = a * sin(2 * PI * freq * t) + b * cos(2 * PI * freq * t)
            val y = out.samples[start + k].toDouble()
            residual += (y - fit) * (y - fit); signal += fit * fit
        }
        val snrDb = 10 * kotlin.math.log10(signal / residual.coerceAtLeast(1e-30))
        val amplitude = sqrt(a * a + b * b)

        assertTrue(abs(amplitude - 0.5) < 0.005) { "level changed: $amplitude" }
        assertTrue(snrDb > 70) { "converter added noise/images: SNR only $snrDb dB" }
    }

    @Test
    fun `resampler keeps a constant constant`() {
        val guard = 128
        val src = FloatArray(4_000 + 2 * guard) { 0.3f }
        val out = Resampler.toRate(RemixStemBuffer(src, guard, 4_000, 44_100, guard), rate)
        for (i in out.guardFrames until out.guardFrames + out.regionFrames) {
            assertTrue(abs(out.samples[i] - 0.3f) < 1e-3f) { "DC drifted to ${out.samples[i]} at $i" }
        }
    }

    /**
     * A loop from 0:00 has no audio before it. The seam used to crossfade into that silent guard
     * and snap back to full level — a click and a 43 ms dip every lap.
     */
    @Test
    fun `a loop with no lead-in joins without a click`() {
        val guard = 2_048
        val region = 9_600
        val samples = FloatArray(region + 2 * guard)
        // Loud, steady content inside the region; nothing at all in front of it. A *cosine*, so
        // the region opens at full level: the old seam faded to the silent guard and then snapped
        // straight to 0.8. A sine would open at zero and hide exactly the jump this test exists
        // to catch.
        for (i in 0 until region + guard) {
            samples[guard + i] = 0.8f * cos(2.0 * PI * 220.0 * i / rate).toFloat()
        }
        val buffer = RemixStemBuffer(samples, guard, region, rate, leadInFrames = 0)
        val reader = LoopReader(buffer, xfadeFrames = 1_920)

        var previous = reader.tap(0f)
        var worst = 0f
        repeat(region * 3) {
            reader.advance(1f)
            val v = reader.tap(0f)
            worst = maxOf(worst, abs(v - previous))
            previous = v
        }
        // A 220 Hz sine at 0.8 moves at most 2π·220·0.8/48000 ≈ 0.023 per sample on its own.
        assertTrue(worst < 0.05f) { "seam jumped by $worst" }
    }

    // ──────────────────────────────────────────────────────── spatialisation

    private fun settle(p: BinauralPanner, x: Float, z: Float) {
        repeat(400) {
            p.update(x = x, y = 0f, z = z, yaw = 0f, pitch = 0f)
            p.endBlock()
        }
    }

    private fun itd(p: BinauralPanner): Float = p.itdLeftFrames - p.itdRightFrames

    /**
     * ITD used to be fed the full ±π azimuth, so a source dead behind got 0.80 ms of one-ear delay
     * (past the physical maximum) with zero level difference — contradictory cues that localise
     * nowhere. Behind must now be as centred as ahead.
     */
    @Test
    fun `a source dead behind reaches both ears together`() {
        val p = BinauralPanner(rate).apply { prepare(128) }
        settle(p, x = 0f, z = 2.5f)
        assertTrue(abs(itd(p)) < 0.5f) { "dead-behind source got ${itd(p)} frames of ITD" }
    }

    @Test
    fun `rear sources mirror their front twins`() {
        val front = BinauralPanner(rate).apply { prepare(128) }
        val rear = BinauralPanner(rate).apply { prepare(128) }
        val a = (PI / 4).toFloat()
        settle(front, x = 2.5f * sin(a), z = -2.5f * cos(a)) // 45° front-right
        settle(rear, x = 2.5f * sin(a), z = 2.5f * cos(a))   // 135° rear-right
        assertTrue(abs(itd(front) - itd(rear)) < 0.5f) {
            "45° gave ${itd(front)} frames but 135° gave ${itd(rear)}"
        }
        assertTrue(itd(front) > 5f) { "a source at 45° should clearly lead one ear" }
    }

    @Test
    fun `no position exceeds the physical maximum ITD`() {
        val maxFrames = BinauralPanner.maxItdFrames(rate).toFloat()
        for (deg in 0 until 360 step 15) {
            val p = BinauralPanner(rate).apply { prepare(128) }
            val a = Math.toRadians(deg.toDouble()).toFloat()
            settle(p, x = 2.5f * sin(a), z = -2.5f * cos(a))
            assertTrue(abs(itd(p)) <= maxFrames) { "$deg° produced ${itd(p)} > $maxFrames frames" }
        }
    }

    /**
     * The distance law was a raw 1/d with a 0.25 m floor: +12 dB for a puck dragged onto the
     * listener, fed straight into the always-on tanh.
     */
    @Test
    fun `dragging a stem onto the listener cannot boost it past plus six dB`() {
        val guard = 64
        val region = 4_800
        val buffer = RemixStemBuffer(FloatArray(region + 2 * guard) { 0.5f }, guard, region, rate, guard)
        val reader = LoopReader(buffer, 32)
        val p = BinauralPanner(rate).apply { prepare(128) }
        settle(p, x = 0f, z = 0.01f)
        p.update(0f, 0f, 0.01f, 0f, 0f)
        var peak = 0f
        repeat(128) { p.processSample(reader); peak = maxOf(peak, abs(p.outLeft)) }
        p.endBlock()
        assertTrue(peak <= 0.5f * 2f + 1e-3f) { "a 0.5 input came out at $peak" }
    }

    /**
     * The mid/side fallback never flipped the side's polarity, so the pair could not rebuild the
     * record's stereo image. On the centre line, mid + inverted side must give back L and R.
     */
    @Test
    fun `mid and side on the centre line rebuild the original stereo`() {
        val guard = 256
        val region = 9_600
        // Different content in each channel, so a collapsed or swapped image cannot pass.
        val left = FloatArray(region + 2 * guard) { i -> (0.4 * sin(2 * PI * 300.0 * i / rate)).toFloat() }
        val right = FloatArray(region + 2 * guard) { i -> (0.4 * sin(2 * PI * 520.0 * i / rate)).toFloat() }
        assertStereoRebuilt(left, right, guard, region)
    }

    /** Correlation of the rebuilt channels against the originals: 1.0 means the image came back. */
    private fun assertStereoRebuilt(left: FloatArray, right: FloatArray, guard: Int, region: Int) {
        val mid = FloatArray(left.size) { (left[it] + right[it]) * 0.5f }
        val side = FloatArray(left.size) { (left[it] - right[it]) * 0.5f }
        val midReader = LoopReader(RemixStemBuffer(mid, guard, region, rate, guard), 64)
        val sideReader = LoopReader(RemixStemBuffer(side, guard, region, rate, guard), 64)
        val pm = BinauralPanner(rate).apply { prepare(128) }
        val ps = BinauralPanner(rate).apply { prepare(128); rightPolarity = -1f }
        settle(pm, 0f, -2.5f); settle(ps, 0f, -2.5f)

        val outL = FloatArray(4_096); val outR = FloatArray(4_096)
        var n = 0
        while (n < outL.size) {
            pm.update(0f, 0f, -2.5f, 0f, 0f); ps.update(0f, 0f, -2.5f, 0f, 0f)
            repeat(128) {
                midReader.advance(1f); sideReader.advance(1f)
                pm.processSample(midReader); ps.processSample(sideReader)
                outL[n] = pm.outLeft + ps.outLeft
                outR[n] = pm.outRight + ps.outRight
                n++
            }
            pm.endBlock(); ps.endBlock()
        }
        // The readers started at position 0 and advanced before the first read, so output n
        // corresponds to source frame guard + n + 1.
        fun corr(a: FloatArray, b: FloatArray, from: Int): Double {
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 512 until a.size) {
                val x = a[i].toDouble(); val y = b[guard + i + 1].toDouble()
                ab += x * y; aa += x * x; bb += y * y
            }
            return ab / sqrt(aa * bb)
        }
        val cl = corr(outL, left, guard)
        val cr = corr(outR, right, guard)
        assertTrue(cl > 0.995) { "left channel not rebuilt: correlation $cl" }
        assertTrue(cr > 0.995) { "right channel not rebuilt: correlation $cr" }
    }
}
