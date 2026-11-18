package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The hand-rolled maths against the JVM's own, as an oracle (allowed in tests only), and its edges. */
class BrainMathTest {

    private fun close(expected: Double, actual: Double, rel: Double = 4e-15) {
        val scale = if (Math.abs(expected) > 1e-300) Math.abs(expected) else 1.0
        assertTrue(Math.abs(expected - actual) <= rel * scale, "expected $expected, got $actual")
    }

    @Test
    fun `ln matches the platform within a few ulps over 600 binades`() {
        val rng = BrainHash.Rng(42)
        repeat(20_000) {
            val exp = (1023 - 300 + (rng.uniform() * 600).toInt()).toLong()
            val x = Double.fromBits((exp shl 52) or (rng.nextLong() ushr 12))
            close(Math.log(x), BrainMath.ln(x))
        }
        assertEquals(0.0, BrainMath.ln(1.0))
        close(Math.log(Double.MIN_VALUE), BrainMath.ln(Double.MIN_VALUE))
        close(Math.log(Double.MAX_VALUE), BrainMath.ln(Double.MAX_VALUE))
    }

    @Test
    fun `exp matches the platform within a few ulps`() {
        val rng = BrainHash.Rng(43)
        repeat(20_000) {
            val x = (rng.uniform() * 2.0 - 1.0) * 700.0
            close(Math.exp(x), BrainMath.exp(x))
        }
        assertEquals(1.0, BrainMath.exp(0.0))
        assertEquals(BrainMath.exp(700.0), BrainMath.exp(5000.0))
        assertEquals(BrainMath.exp(-700.0), BrainMath.exp(-5000.0))
    }

    @Test
    fun `nothing returns NaN and the documented domain values hold`() {
        val bad = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in bad) {
            assertEquals(-700.0, BrainMath.ln(x))
            assertEquals(0.0, BrainMath.exp(x))
            assertEquals(0.0, BrainMath.sigmoid(x))
            assertEquals(0L, BrainMath.toQ(x))
            assertEquals(0L, BrainMath.orderKey(x))
        }
        assertEquals(-700.0, BrainMath.ln(0.0))
        assertEquals(-700.0, BrainMath.ln(-3.0))
        assertFalse(BrainMath.powNeg(0.0, 0.5).isNaN())
    }

    @Test
    fun `sigmoid is symmetric and bounded`() {
        assertEquals(0.5, BrainMath.sigmoid(0.0))
        for (x in doubleArrayOf(0.1, 1.0, 5.0, 30.0)) {
            close(1.0, BrainMath.sigmoid(x) + BrainMath.sigmoid(-x), 1e-15)
            assertTrue(BrainMath.sigmoid(x) > 0.5 && BrainMath.sigmoid(x) <= 1.0)
        }
    }

    @Test
    fun `Q20 counters round to nearest and clip`() {
        assertEquals(1_048_576L, BrainMath.toQ(1.0))
        assertEquals(0L, BrainMath.toQ(-5.0))
        assertEquals(1L, BrainMath.toQ(1.0 / 1_048_576.0))
        assertEquals(1L shl 60, BrainMath.toQ(1e30))
        assertEquals(2.5, BrainMath.fromQ(BrainMath.toQ(2.5)))
        assertEquals(-1L, BrainMath.orderKey(-1.0 / 1_048_576.0))
    }

    @Test
    fun `floor division and local days work before 1970 and with negative offsets`() {
        assertEquals(-1L, BrainMath.floorDiv(-1L, 86_400_000L))
        assertEquals(86_399_999L, BrainMath.floorMod(-1L, 86_400_000L))
        assertEquals(0L, BrainMath.localDay(0L, 0))
        assertEquals(-1L, BrainMath.localDay(0L, -60))
        // 1970-01-01 was a Thursday (3); 2025-10-11 is a Saturday.
        assertEquals(3, BrainMath.weekday(0L))
        assertTrue(BrainMath.isWeekend(1_760_140_800_000L, 0))
        assertFalse(BrainMath.isWeekend(1_760_140_800_000L - 86_400_000L, 0))
    }

    @Test
    fun `the prior tables have 51 entries and the listed stage 1 weights`() {
        assertEquals(51, Ranker.D)
        val w = Ranker.weights(0.5)
        assertEquals(51, w.size)
        assertEquals(0.0, w[0])
        assertEquals(1.2, w[Features.TRACK_AFF])
        assertEquals(-1.5, w[Features.SINCE_LAST])
        assertEquals(0.2, w[Features.SINCE_LAST + 4])
        assertEquals(-0.4, w[Features.UNHEARD_ARTIST])
        assertEquals(0.5, w[Features.RETRIEVAL])
        assertEquals(0.3, w[Features.RADIO_RANK])
        assertEquals(0.4, w[Features.ARM_AFF])
        for (i in 44 until 51) assertEquals(0.0, w[i])
        assertEquals(0.5, Ranker.sd0(50))
        assertEquals(0.2, Ranker.sd0(30))
        close(Math.log(0.8 / 0.2), Ranker.weights(0.8)[0])
    }
}
