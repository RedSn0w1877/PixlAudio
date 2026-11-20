package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorTest {

    /** Runs [rssi] at 100 ms spacing; [impulseAt] is when a jerk happened (known from then on). Returns the fire times. */
    private fun fires(rssi: List<Int>, impulseAt: Long = -1L): List<Pair<Long, FireReason>> {
        var state = DetectorState.INITIAL
        val out = ArrayList<Pair<Long, FireReason>>()
        for ((i, r) in rssi.withIndex()) {
            val t = i * 100L
            val step = SongTapDetector.step(state, RssiSample(t, r), if (impulseAt in 0..t) impulseAt else -1L)
            state = step.state
            if (step.fire != FireReason.NONE) out += t to step.fire
        }
        return out
    }

    private fun series(vararg parts: Pair<Int, Int>): List<Int> = parts.flatMap { (n, v) -> List(n) { v } }

    @Test
    fun `a steady signal never fires`() {
        assertTrue(fires(series(60 to -60)).isEmpty())
        assertTrue(fires(series(60 to -45)).isEmpty())
    }

    @Test
    fun `a jump that holds with a jerk fires as impulse`() {
        val f = fires(series(20 to -62, 10 to -42), impulseAt = 2_050L)
        assertEquals(1, f.size)
        assertEquals(FireReason.IMPULSE, f[0].second)
        assertTrue(f[0].first in 2_100L..2_700L, "fired at ${f[0].first}")
    }

    @Test
    fun `a gentle tap with no jerk fires only after the dwell`() {
        val f = fires(series(20 to -62, 10 to -42))
        assertEquals(1, f.size)
        assertEquals(FireReason.DWELL, f[0].second)
        val withJerk = fires(series(20 to -62, 10 to -42), impulseAt = 2_050L)
        assertTrue(f[0].first > withJerk[0].first)
    }

    @Test
    fun `a spike that does not hold does not fire`() {
        assertTrue(fires(series(20 to -62, 1 to -40, 20 to -62), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `a jerk long before the jump does not count`() {
        val f = fires(series(20 to -62, 10 to -42), impulseAt = 100L)
        assertEquals(FireReason.DWELL, f[0].second)
    }

    @Test
    fun `the absolute floor keeps a far jump from firing`() {
        assertTrue(fires(series(20 to -95, 10 to -78), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `the same jump reads the same on a phone that hears everything 20 dB louder`() {
        val quiet = fires(series(20 to -80, 10 to -62), impulseAt = 2_050L)
        val loud = fires(series(20 to -60, 10 to -42), impulseAt = 2_050L)
        assertEquals(1, quiet.size)
        assertEquals(quiet, loud)
    }

    @Test
    fun `a second tap inside the cooldown does not fire`() {
        // The first tap fires at 2400; the second jump holds from 3200 to 3700, long enough for the dwell, but the cooldown runs to 4400.
        val f = fires(series(20 to -62, 5 to -42, 5 to -62, 8 to -42), impulseAt = 2_050L)
        assertEquals(1, f.size)
        // The same second tap, long enough to outlast the cooldown, fires.
        val g = fires(series(20 to -62, 5 to -42, 5 to -62, 14 to -42), impulseAt = 2_050L)
        assertEquals(2, g.size)
        assertTrue(g[1].first - g[0].first >= SongTapTuning.COOLDOWN_MS)
    }

    @Test
    fun `a long gap forgets the peer`() {
        var state = DetectorState.INITIAL
        for (i in 0 until 5) state = SongTapDetector.step(state, RssiSample(i * 100L, -62), -1L).state
        val after = SongTapDetector.step(state, RssiSample(10_000L, -42), -1L)
        assertEquals(-42 * RSSI_SCALE, after.state.baselineQ)
        assertEquals(FireReason.NONE, after.fire)
    }

    @Test
    fun `near has hysteresis`() {
        var state = DetectorState.INITIAL
        var near = ArrayList<Boolean>()
        for ((i, r) in listOf(-80, -72, -69, -69, -69, -75, -75, -75, -75, -80, -80, -80, -80).withIndex()) {
            state = SongTapDetector.step(state, RssiSample(i * 100L, r), -1L).state
            near += state.near
        }
        assertFalse(near[0])
        assertTrue(near[5])
        assertFalse(near.last())
    }

    @Test
    fun `glow grows with the signal`() {
        assertEquals(0, SongTapDetector.glowPermille(-90 * RSSI_SCALE))
        assertEquals(500, SongTapDetector.glowPermille(-40 * RSSI_SCALE))
        val mid = SongTapDetector.glowPermille(-62 * RSSI_SCALE)
        assertTrue(mid in 100..400, "mid=$mid")
    }

    @Test
    fun `floor division rounds down`() {
        assertEquals(-1, SongTapDetector.floorDiv(-1, 256))
        assertEquals(-2, SongTapDetector.floorDiv(-257, 256))
        assertEquals(0, SongTapDetector.floorDiv(255, 256))
        assertEquals(-70, SongTapDetector.floorDiv(-70 * 256, 256))
    }

    @Test
    fun `impulse needs a big change and is debounced`() {
        var state = ImpulseState.INITIAL
        fun feed(t: Long, x: Int, y: Int, z: Int): Boolean {
            val s = SongTapImpulse.step(state, AccelSample(t, x, y, z)); state = s.state; return s.impulse
        }
        assertFalse(feed(0, 0, 0, 1000))
        assertFalse(feed(20, 10, 0, 1020))
        assertTrue(feed(40, 0, 0, 4000))
        assertFalse(feed(60, 0, 0, 1000), "inside the debounce")
        assertFalse(feed(80, 0, 0, 4000), "inside the debounce")
        assertTrue(feed(400, 0, 0, 1000))
    }
}
