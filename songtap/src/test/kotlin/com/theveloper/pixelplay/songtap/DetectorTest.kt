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
        // Outside the strict near gate nothing can fire, however the signal moves.
        assertTrue(fires(series(20 to -90, 30 to -58)).isEmpty())
    }

    @Test
    fun `phones brought close gently and held fire as hold, once, with no jump and no jerk`() {
        // From far to touching over 4 seconds (no 15 dB jump at any moment), then held: the old rules never fire here.
        val f = fires((0 until 120).map { -90 + it / 3 } + series(30 to -46))
        assertEquals(1, f.size)
        assertEquals(FireReason.HOLD, f[0].second)
        assertTrue(f[0].first >= SongTapTuning.CLOSE_HOLD_MS, "fired at ${f[0].first}")
    }

    @Test
    fun `a signal held just outside the hold level does not fire`() {
        assertTrue(fires((0 until 105).map { -90 + it / 3 } + series(40 to -54)).isEmpty())
    }

    @Test
    fun `a steady strong signal fires the hold once, and again only after the phones came apart`() {
        assertEquals(1, fires(series(80 to -45)).size)
        val again = fires(series(20 to -75, 12 to -50, 25 to -75, 25 to -50))
        assertEquals(2, again.size)
        assertTrue(again[1].first - again[0].first >= SongTapTuning.COOLDOWN_MS)
    }

    @Test
    fun `a phone seen only inside the old loose gate cannot fire`() {
        // -66 was near under spec 4. The strict gate keeps a connection and a tap from happening at that distance.
        assertTrue(fires(series(20 to -90, 20 to -66), impulseAt = 2_000L).isEmpty())
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
        // Both the dwell (a held jump) and the hold (a held strong signal) apply; the hold is the earlier of the two.
        assertEquals(FireReason.HOLD, f[0].second)
        val withJerk = fires(series(20 to -62, 10 to -42), impulseAt = 2_050L)
        assertTrue(f[0].first > withJerk[0].first)
    }

    @Test
    fun `phones that stay together fire once and not again every cooldown`() {
        // The signal stays 20 dB up for 8 seconds after the tap (the phones are still touching).
        val f = fires(series(20 to -62, 80 to -42), impulseAt = 2_050L)
        assertEquals(1, f.size)
        val noJerk = fires(series(20 to -62, 80 to -42))
        assertEquals(1, noJerk.size)
    }

    @Test
    fun `a slow approach that stops short of contact does not fire`() {
        // From far to about one metre in 3 seconds, then it stays there: not a tap (the dwell path needs a much stronger signal).
        val ramp = (0 until 30).map { -90 + it }
        assertTrue(fires(ramp + series(40 to -61)).isEmpty())
    }

    @Test
    fun `a jump that holds for good is taken as the new level after a while`() {
        var state = DetectorState.INITIAL
        for (i in 0 until 20) state = SongTapDetector.step(state, RssiSample(i * 100L, -90), -1L).state
        for (i in 20 until 60) state = SongTapDetector.step(state, RssiSample(i * 100L, -70), -1L).state
        assertTrue(state.heldSinceMs < 0L)
        assertTrue(state.baselineQ > -75 * RSSI_SCALE)
    }

    @Test
    fun `a spike that does not hold does not fire`() {
        assertTrue(fires(series(20 to -62, 1 to -40, 20 to -62), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `a jerk long before the jump does not count`() {
        val f = fires(series(20 to -62, 10 to -42), impulseAt = 100L)
        assertEquals(FireReason.HOLD, f[0].second)
        // At a signal inside the near gate but weaker than the hold level only the dwell path remains.
        val dwell = fires(series(20 to -75, 15 to -54), impulseAt = 100L)
        assertEquals(FireReason.DWELL, dwell[0].second)
    }

    @Test
    fun `the absolute floor keeps a far jump from firing`() {
        assertTrue(fires(series(20 to -95, 10 to -78), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `the same jump fires the same way on phones that hear it a few dB louder or quieter`() {
        // Relative, not absolute: the strict gate is the only absolute number, and it only says "close enough".
        val quiet = fires(series(20 to -72, 10 to -54), impulseAt = 2_050L)
        val loud = fires(series(20 to -69, 10 to -51), impulseAt = 2_050L)
        assertEquals(1, quiet.size)
        assertEquals(FireReason.IMPULSE, quiet[0].second)
        assertEquals(1, loud.size)
        assertEquals(FireReason.IMPULSE, loud[0].second)
        assertTrue((quiet[0].first - loud[0].first) in -200L..200L)
    }

    @Test
    fun `a second tap inside the cooldown does not fire`() {
        // The first tap fires at 2300 and the baseline then follows the phones apart for 1.2 s; the second jump is inside the cooldown, which runs to 4300.
        val f = fires(series(20 to -62, 5 to -42, 12 to -62, 8 to -42), impulseAt = 2_050L)
        assertEquals(1, f.filter { it.first < 4_000L }.size)
        // Whatever fires next waits for the cooldown (the hold path is a second, deliberate, hold).
        assertTrue(f.size == 1 || f[1].first - f[0].first >= SongTapTuning.COOLDOWN_MS)
        // The same second tap, long enough to outlast the cooldown, fires.
        val g = fires(series(20 to -62, 5 to -42, 12 to -62, 14 to -42), impulseAt = 2_050L)
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
        for ((i, r) in listOf(-80, -66, -58, -56, -52, -52, -52, -60, -60, -60, -60, -68, -68, -68, -68).withIndex()) {
            state = SongTapDetector.step(state, RssiSample(i * 100L, r), -1L).state
            near += state.near
        }
        assertFalse(near[0])
        assertFalse(near[3], "-56 is outside the strict gate")
        assertTrue(near[6])
        assertTrue(near[10], "-60 is inside the exit level, so it stays near")
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
