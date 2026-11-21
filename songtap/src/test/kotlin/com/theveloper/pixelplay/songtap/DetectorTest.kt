package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorTest {

    /** Runs [rssi] (already normalised) at 100 ms spacing; [impulseAt] is when a jerk happened (known from then on). Returns the fire times. */
    private fun fires(
        rssi: List<Int>,
        impulseAt: Long = -1L,
        profile: SongTapSensitivity = SongTapSensitivity.NORMAL,
    ): List<Pair<Long, FireReason>> {
        var state = DetectorState.INITIAL
        val out = ArrayList<Pair<Long, FireReason>>()
        for ((i, r) in rssi.withIndex()) {
            val t = i * 100L
            val step = SongTapDetector.step(state, RssiSample(t, r), if (impulseAt in 0..t) impulseAt else -1L, profile)
            state = step.state
            if (step.fire != FireReason.NONE) out += t to step.fire
        }
        return out
    }

    private fun series(vararg parts: Pair<Int, Int>): List<Int> = parts.flatMap { (n, v) -> List(n) { v } }

    @Test
    fun `the profiles are the owner's numbers`() {
        val n = SongTapSensitivity.NORMAL
        assertEquals(listOf(3, 1, 15, 6), listOf(n.baselineShift, n.fastShift, n.jumpDb, n.freezeDb))
        assertEquals(listOf(150L, 400L, 400L, 2_000L), listOf(n.holdMs, n.dwellMs, n.impulseWindowMs, n.cooldownMs))
        assertEquals(1_800, n.jerkMilliG)
        val h = SongTapSensitivity.HIGH
        assertEquals(listOf(3, 1, 9, 6), listOf(h.baselineShift, h.fastShift, h.jumpDb, h.freezeDb))
        assertEquals(listOf(60L, 200L, 400L, 1_200L), listOf(h.holdMs, h.dwellMs, h.impulseWindowMs, h.cooldownMs))
        assertEquals(900, h.jerkMilliG)
        assertEquals(SongTapSensitivity.HIGH, SongTapSensitivity.DEFAULT)
        assertEquals(SongTapSensitivity.HIGH, SongTapSensitivity.fromCode(null))
        assertEquals(SongTapSensitivity.HIGH, SongTapSensitivity.fromCode("loud"))
        assertEquals(SongTapSensitivity.NORMAL, SongTapSensitivity.fromCode("normal"))
        // The gates are on the iPhone scale.
        assertEquals(-70, SongTapTuning.NEAR_ENTER_DBM)
        assertEquals(-78, SongTapTuning.NEAR_EXIT_DBM)
        assertEquals(-85, SongTapTuning.LIST_ENTER_DBM)
        assertEquals(-92, SongTapTuning.LIST_EXIT_DBM)
        assertEquals(-45, SongTapTuning.CLOSE_HOLD_DBM)
        assertEquals(250L, SongTapTuning.CLOSE_HOLD_MS)
    }

    @Test
    fun `a steady signal never fires`() {
        for (p in SongTapSensitivity.entries) {
            assertTrue(fires(series(60 to -60), profile = p).isEmpty())
            assertTrue(fires(series(20 to -95, 30 to -80), profile = p).isEmpty())
        }
    }

    @Test
    fun `phones brought close gently and held fire as hold, once, with no jump and no jerk`() {
        // From far to a few cm over 12 seconds (no 15 dB jump at any moment), then held at -40: the old rules never fire here.
        val f = fires((0 until 150).map { -90 + it / 3 } + series(30 to -40))
        assertEquals(1, f.size)
        assertEquals(FireReason.HOLD, f[0].second)
    }

    @Test
    fun `a signal held just outside the hold level does not fire`() {
        assertTrue(fires((0 until 120).map { -90 + it / 3 } + series(40 to -47)).isEmpty())
    }

    @Test
    fun `the hold never fires at rest, resting is about minus 60`() {
        for (p in SongTapSensitivity.entries) {
            assertTrue(fires(series(200 to -60), profile = p).isEmpty())
            assertTrue(fires(series(200 to -50), profile = p).isEmpty(), "-50 is still 'a couple of cm away', not a hold")
            assertTrue(fires(series(200 to -46), profile = p).isEmpty())
        }
    }

    @Test
    fun `a steady strong signal fires the hold once for 250 ms of it, and again only after the phones came apart`() {
        val f = fires(series(80 to -40))
        assertEquals(1, f.size)
        assertEquals(FireReason.HOLD, f[0].second)
        // The first sample is the baseline; the hold timer starts there and must run for the hold time.
        assertTrue(f[0].first >= SongTapTuning.CLOSE_HOLD_MS, "fired at ${f[0].first}")
        // Resting 20 to 30 cm apart (-60) is "apart" for the hold: a second touch counts.
        val again = fires(series(30 to -62, 12 to -40, 40 to -62, 25 to -40))
        assertEquals(2, again.size)
        assertTrue(again[1].first - again[0].first >= SensitivityNormal.cooldownMs)
        // Sliding off to -52 only (not below -55) does not re-arm it.
        assertEquals(1, fires(series(30 to -62, 12 to -40, 40 to -52, 25 to -40)).size)
    }

    private val SensitivityNormal = SongTapSensitivity.NORMAL

    @Test
    fun `a phone seen only inside the list gate but outside the near gate cannot fire`() {
        assertTrue(fires(series(20 to -95, 20 to -73), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `a jump that holds with a jerk fires as impulse`() {
        val f = fires(series(20 to -64, 10 to -44), impulseAt = 2_050L)
        assertEquals(1, f.size)
        assertEquals(FireReason.IMPULSE, f[0].second)
        assertTrue(f[0].first in 2_100L..2_700L, "fired at ${f[0].first}")
    }

    @Test
    fun `a gentle tap with no jerk fires only after the dwell`() {
        // -64 to -46: an 18 dB jump that never reaches the hold level (-45).
        val f = fires(series(20 to -64, 10 to -46))
        assertEquals(1, f.size)
        assertEquals(FireReason.DWELL, f[0].second)
        val withJerk = fires(series(20 to -64, 10 to -46), impulseAt = 2_050L)
        assertEquals(FireReason.IMPULSE, withJerk[0].second)
        assertTrue(f[0].first > withJerk[0].first)
    }

    @Test
    fun `phones that stay together fire once and not again every cooldown`() {
        val f = fires(series(20 to -64, 80 to -44), impulseAt = 2_050L)
        assertEquals(1, f.size)
        val noJerk = fires(series(20 to -64, 80 to -44))
        assertEquals(1, noJerk.size)
    }

    @Test
    fun `a slow approach that stops short of contact does not fire`() {
        // From far to about one metre in 3 seconds, then it stays there: not a tap (the dwell path needs a much stronger signal).
        val ramp = (0 until 30).map { -90 + it }
        for (p in SongTapSensitivity.entries) assertTrue(fires(ramp + series(40 to -61), profile = p).isEmpty())
    }

    @Test
    fun `a jump that holds for good is taken as the new level after a while`() {
        var state = DetectorState.INITIAL
        for (i in 0 until 20) state = SongTapDetector.step(state, RssiSample(i * 100L, -90), -1L, SongTapSensitivity.NORMAL).state
        for (i in 20 until 60) state = SongTapDetector.step(state, RssiSample(i * 100L, -70), -1L, SongTapSensitivity.NORMAL).state
        assertTrue(state.heldSinceMs < 0L)
        assertTrue(state.baselineQ > -75 * RSSI_SCALE)
    }

    @Test
    fun `a spike that does not hold does not fire`() {
        for (p in SongTapSensitivity.entries) assertTrue(fires(series(20 to -62, 1 to -40, 20 to -62), impulseAt = 2_000L, profile = p).isEmpty())
    }

    @Test
    fun `a jerk long before the jump does not count`() {
        // At a signal that is not at the hold level only the dwell path remains.
        val dwell = fires(series(20 to -75, 15 to -56), impulseAt = 100L)
        assertEquals(FireReason.DWELL, dwell[0].second)
    }

    @Test
    fun `the absolute floor keeps a far jump from firing`() {
        assertTrue(fires(series(20 to -95, 10 to -80), impulseAt = 2_000L).isEmpty())
    }

    @Test
    fun `the same jump fires the same way on phones that hear it a few dB louder or quieter`() {
        // Relative, not absolute: the gates only say "close enough".
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
        // NORMAL: the first tap fires at about 2200 and the cooldown runs to about 4200; the second jump is inside it.
        val f = fires(series(20 to -64, 8 to -46, 10 to -64, 8 to -46), impulseAt = 2_050L)
        assertEquals(1, f.filter { it.first < 4_000L }.size)
        // The same second tap, long enough to outlast the cooldown, fires.
        val g = fires(series(20 to -64, 8 to -46, 20 to -64, 20 to -46), impulseAt = 2_050L)
        assertEquals(2, g.size)
        assertTrue(g[1].first - g[0].first >= SensitivityNormal.cooldownMs)
    }

    @Test
    fun `high reacts to a smaller, shorter jump than normal`() {
        // A 12 dB step held for 800 ms: not a tap for NORMAL (15 dB), a tap for HIGH (9 dB held 200 ms).
        val step = series(20 to -64, 8 to -52, 10 to -64)
        assertTrue(fires(step, profile = SongTapSensitivity.NORMAL).isEmpty())
        val high = fires(step, profile = SongTapSensitivity.HIGH)
        assertEquals(1, high.size)
        assertEquals(FireReason.DWELL, high[0].second)
        // With a 900 milli g bump the jump needs to hold only 60 ms.
        val bump = fires(series(20 to -64, 6 to -52, 10 to -64), impulseAt = 2_000L, profile = SongTapSensitivity.HIGH)
        assertEquals(FireReason.IMPULSE, bump[0].second)
        assertTrue(bump[0].first < high[0].first)
        // And it may fire again after 1.2 s instead of 2 s.
        val twice = fires(series(20 to -64, 6 to -48, 10 to -64, 8 to -48), profile = SongTapSensitivity.HIGH)
        assertEquals(2, twice.size)
        assertEquals(1, fires(series(20 to -64, 6 to -48, 10 to -64, 8 to -48), profile = SongTapSensitivity.NORMAL).size)
        assertTrue(twice[1].first - twice[0].first in SongTapSensitivity.HIGH.cooldownMs until SongTapSensitivity.NORMAL.cooldownMs)
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
        val near = ArrayList<Boolean>()
        for ((i, r) in listOf(-90, -80, -73, -71, -69, -69, -69, -75, -75, -75, -75, -82, -82, -82, -82).withIndex()) {
            state = SongTapDetector.step(state, RssiSample(i * 100L, r), -1L).state
            near += state.near
        }
        assertFalse(near[0])
        assertFalse(near[3], "-71 is outside the near gate (-70)")
        assertTrue(near[6])
        assertTrue(near[10], "-75 is inside the exit level, so it stays near")
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

    @Test
    fun `the jerk threshold is the profile's`() {
        fun hits(profile: SongTapSensitivity, step: Int): Boolean {
            val s = SongTapImpulse.step(SongTapImpulse.step(ImpulseState.INITIAL, AccelSample(0, 0, 0, 1000), profile).state, AccelSample(20, 0, 0, 1000 + step), profile)
            return s.impulse
        }
        assertTrue(hits(SongTapSensitivity.HIGH, 1_000))
        assertFalse(hits(SongTapSensitivity.NORMAL, 1_000))
        assertTrue(hits(SongTapSensitivity.NORMAL, 1_800))
        assertFalse(hits(SongTapSensitivity.HIGH, 899))
    }
}
