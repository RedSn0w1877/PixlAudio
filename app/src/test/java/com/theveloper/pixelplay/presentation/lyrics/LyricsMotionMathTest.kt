package com.theveloper.pixelplay.presentation.lyrics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Spring conversion table, cascade delays, emphasis reference values, interlude timeline, blur maths. */
class LyricsMotionMathTest {

    // ---- §3.1 spring conversion ----------------------------------------------------------------

    @Test
    fun springConversionTable() {
        assertEquals(0.8333f, LyricsSprings.SlowDampingRatio, 1e-4f)
        assertEquals(100f, LyricsSprings.SlowStiffness, 1e-3f)
        assertEquals(0.980f, LyricsSprings.EndedDampingRatio, 1e-3f)
        assertEquals(155.6f, LyricsSprings.EndedStiffness, 0.05f)
        assertEquals(0.884f, LyricsSprings.ScaleDampingRatio, 1e-3f)
        assertEquals(50f, LyricsSprings.ScaleStiffness, 1e-3f)
        assertEquals(1.1595f, LyricsSprings.NormalDampingRatio, 1e-4f)
    }

    @Test
    fun normalPlaybackDamping_isIndependentOfStiffness() {
        for (k in listOf(170f, 195f, 220f)) {
            assertEquals(LyricsSprings.NormalDampingRatio, LyricsSprings.dampingRatio(0.9f, k, 2.2f * sqrt(k)), 1e-5f)
        }
    }

    @Test
    fun normalPlaybackStiffness_followsTheStartGap() {
        assertEquals(244.44f, LyricsSprings.normalPlaybackStiffness(100), 0.01f)
        assertEquals(244.44f, LyricsSprings.normalPlaybackStiffness(20), 0.01f) // clamped to 100
        assertEquals(188.89f, LyricsSprings.normalPlaybackStiffness(800), 0.01f)
        assertEquals(188.89f, LyricsSprings.normalPlaybackStiffness(5_000), 0.01f) // clamped to 800
        // gap 450: ratio = 0.5^0.2 = 0.87055, k = 213.53, stiffness = 237.25
        assertEquals(237.25f, LyricsSprings.normalPlaybackStiffness(450), 0.01f)
        assertTrue(LyricsSprings.normal(450) === LyricsSprings.normal(450))
    }

    // ---- §3.3 cascade --------------------------------------------------------------------------

    @Test
    fun cascadeDelays_stepFiftyThenDecayFromTheTarget() {
        val tops = floatArrayOf(0f, 100f, 200f, 300f, 400f)
        val heights = FloatArray(5) { 100f }
        val out = FloatArray(5)
        LyricsCascade.computeDelays(tops, heights, 5, targetRow = 2, out = out)
        // §3.3: each row takes the running delay, then `delay += step`; from the target row on,
        // `step /= 1.05` after that row. So the target (row 2) still adds a full 50 ms.
        assertArrayEquals(floatArrayOf(0f, 50f, 100f, 150f, 197.619f), out, 1e-3f)
    }

    @Test
    fun cascadeDelays_rowsAboveTheViewportGetZero() {
        val tops = floatArrayOf(-300f, -150f, 0f, 100f)
        val heights = FloatArray(4) { 100f }
        val out = FloatArray(4) { -1f }
        LyricsCascade.computeDelays(tops, heights, 4, targetRow = 3, out = out)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 50f), out, 1e-3f)
    }

    @Test
    fun cascadeDelays_zeroHeightRowsDoNotConsumeAStep() {
        val tops = floatArrayOf(0f, 100f, 100f, 200f)
        val heights = floatArrayOf(100f, 0f, 100f, 100f)
        val out = FloatArray(4)
        LyricsCascade.computeDelays(tops, heights, 4, targetRow = 0, out = out)
        assertArrayEquals(floatArrayOf(0f, 50f, 50f, 97.619f), out, 1e-3f)
    }

    // ---- §1.4 emphasis ---------------------------------------------------------------------------

    @Test
    fun emphasisReferenceValues() {
        assertEquals(1.0075f, EmphasisMath.peakScale(1_000), 1e-4f)
        assertEquals(1.06f, EmphasisMath.peakScale(2_000), 1e-4f)
        assertEquals(1.07f, EmphasisMath.peakScale(3_000), 5e-3f)

        assertEquals(0.02f, EmphasisMath.peakGlowAlpha(1_000), 2e-3f)
        assertEquals(0.15f, EmphasisMath.peakGlowAlpha(2_000), 3e-3f)
        assertEquals(0.5f, EmphasisMath.peakGlowAlpha(3_000), 1e-4f)
    }

    @Test
    fun emphasisPeak_isReachedThroughTheEnvelope() {
        val amount = EmphasisMath.amount(2_000, isLastWord = false)
        var peak = 0f
        for (k in 0..1000) peak = maxOf(peak, EmphasisMath.scale(EmphasisMath.envelope(k / 1000f), amount))
        assertEquals(1.06f, peak, 1e-3f)
        assertEquals(0f, EmphasisMath.envelope(0f), 1e-4f)
        assertEquals(1f, EmphasisMath.envelope(0.5f), 1e-4f)
        assertEquals(0f, EmphasisMath.envelope(1f), 1e-4f)
    }

    @Test
    fun emphasis_lastWordBoost_andCaps() {
        assertEquals(0.12f, EmphasisMath.amount(1_000, isLastWord = true), 1e-4f)
        assertEquals(1_200f, EmphasisMath.effectiveDurationMs(1_000, isLastWord = true), 1e-3f)
        assertTrue(EmphasisMath.amount(60_000, isLastWord = true) <= EmphasisMath.MAX_AMOUNT)
        assertTrue(EmphasisMath.glow(60_000, isLastWord = true) <= EmphasisMath.MAX_GLOW)
        assertEquals(0.3f, EmphasisMath.glowBlurEm(5f), 1e-6f)
    }

    @Test
    fun emphasisGraphemeTiming_andPush() {
        assertEquals(1_160f, EmphasisMath.graphemeStartMs(1_000, 1_000f, n = 5, i = 2), 1e-3f)
        // Letters spread from the middle: left of centre pushes left, right pushes right.
        assertTrue(EmphasisMath.offsetXEm(1f, 1f, n = 4, i = 0) < 0f)
        assertEquals(0f, EmphasisMath.offsetXEm(1f, 1f, n = 4, i = 2), 1e-6f)
        assertTrue(EmphasisMath.offsetXEm(1f, 1f, n = 4, i = 3) > 0f)
        assertTrue(EmphasisMath.offsetYEm(1f, 1f) < 0f)
    }

    @Test
    fun liftAndHop() {
        assertEquals(0f, EmphasisMath.liftEm(1_000, 1_000, 500), 1e-6f)
        assertEquals(0.05f, EmphasisMath.liftEm(2_000, 1_000, 500), 1e-6f)
        assertEquals(0.10f, EmphasisMath.liftEm(5_000, 1_000, 500, background = true), 1e-6f)
        assertTrue(EmphasisMath.liftEm(1_500, 1_000, 500) > 0.025f) // ease-out is ahead of linear

        val du = 1_000f
        assertEquals(0f, EmphasisMath.hopEm(600, 1_000f, du), 1e-6f)
        assertEquals(0.05f, EmphasisMath.hopEm((600 + du * 1.4f / 2f).toLong(), 1_000f, du), 1e-4f)
    }

    @Test
    fun sweepEdge_entersAndLeavesTheSyllableFully() {
        assertEquals(0f, EmphasisMath.sweepEdgeCenterPx(10f, 110f, 20f, 0f), 1e-6f)
        assertEquals(120f, EmphasisMath.sweepEdgeCenterPx(10f, 110f, 20f, 1f), 1e-6f)
        assertEquals(0.5f, EmphasisMath.syllableProgress(1_500, 1_000, 2_000), 1e-6f)
        assertEquals(1f, EmphasisMath.syllableProgress(3_000, 1_000, 2_000), 1e-6f)
        assertEquals(1f, EmphasisMath.syllableProgress(1_000, 1_000, 1_000), 1e-6f)
    }

    @Test
    fun graphemeBoundaries_keepCombiningMarksTogether() {
        assertArrayEquals(intArrayOf(0, 2, 3), EmphasisMath.graphemeBoundaries("éa"))
        assertArrayEquals(intArrayOf(0), EmphasisMath.graphemeBoundaries(""))
    }

    @Test
    fun alphas() {
        assertEquals(0.20f, KaraokeAlpha.unsung(0f), 1e-6f)
        assertEquals(0.35f, KaraokeAlpha.unsung(1f), 1e-6f)
        assertEquals(0.20f, KaraokeAlpha.sung(0f), 1e-6f)
        assertEquals(1.0f, KaraokeAlpha.sung(1f), 1e-6f)
    }

    // ---- §1.6 interlude ------------------------------------------------------------------------

    private val g0 = 10_000L
    private val g1 = 30_000L

    @Test
    fun interlude_expandAndCollapse() {
        assertEquals(0f, InterludeTimeline.presence(g0 - 1, g0, g1), 0f)
        assertEquals(0f, InterludeTimeline.presence(g0, g0, g1), 1e-6f)
        assertEquals(InterludeTimeline.HIDDEN_SCALE, InterludeTimeline.scale(g0, g0, g1), 1e-6f)
        assertEquals(0.5f, InterludeTimeline.presence(g0 + 150, g0, g1), 1e-3f)
        assertEquals(1f, InterludeTimeline.presence(g0 + 300, g0, g1), 1e-6f)
        assertEquals(1f, InterludeTimeline.expand(g0 + 5_000, g0, g1), 1e-6f)
        assertEquals(0.5f, InterludeTimeline.presence(g1 - 150, g0, g1), 1e-3f)
        assertEquals(0f, InterludeTimeline.presence(g1, g0, g1), 0f)
        assertTrue(InterludeTimeline.isActive(g0, g0, g1))
        assertTrue(!InterludeTimeline.isActive(g1, g0, g1))
    }

    @Test
    fun interlude_dotsFillOneAfterAnother() {
        val d = g1 - g0
        assertEquals(0.3f, InterludeTimeline.dotAlpha(g0, g0, g1, 0), 1e-6f)
        assertEquals(1.0f, InterludeTimeline.dotAlpha(g0 + d / 3, g0, g1, 0), 1e-3f)
        assertEquals(0.3f, InterludeTimeline.dotAlpha(g0 + d / 3, g0, g1, 1), 1e-3f)
        assertEquals(0.65f, InterludeTimeline.dotAlpha(g0 + d / 2, g0, g1, 1), 1e-3f)
        assertEquals(0.3f, InterludeTimeline.dotAlpha(g0 + d / 2, g0, g1, 2), 1e-3f)
        assertEquals(1.0f, InterludeTimeline.dotAlpha(g1 - 1, g0, g1, 2), 1e-3f)
    }

    @Test
    fun interlude_breathingThenFinalPulse() {
        assertEquals(1.0f, InterludeTimeline.baseScale(g0, g0, g1), 1e-6f)
        assertEquals(1.2f, InterludeTimeline.baseScale(g0 + 2_500, g0, g1), 1e-4f)
        assertEquals(1.0f, InterludeTimeline.baseScale(g0 + 5_000, g0, g1), 1e-4f)
        // Pulse fully blended in 500 ms after it starts: 1.1 + 0.3·sin²(π/2) = 1.4
        assertEquals(1.4f, InterludeTimeline.baseScale(g1 - 1_000, g0, g1), 1e-4f)
        assertEquals(1.1f, InterludeTimeline.baseScale(g1 - 500, g0, g1), 1e-4f)
    }

    @Test
    fun interlude_shortGap_skipsBreathing_andLightsAllDots() {
        val s0 = 0L
        val s1 = 2_000L
        assertEquals(1f, InterludeTimeline.baseScale(1_000, s0, s1), 0f)
        for (k in 0 until 3) assertEquals(1f, InterludeTimeline.dotAlpha(1_000, s0, s1, k), 0f)
    }

    @Test
    fun interlude_isContinuous_soSeeksAndPhaseChangesNeverPop() {
        var prevScale = InterludeTimeline.scale(g0 - 10, g0, g1)
        var prevPresence = InterludeTimeline.presence(g0 - 10, g0, g1)
        var t = g0 - 9
        while (t <= g1 + 10) {
            val s = InterludeTimeline.scale(t, g0, g1)
            val p = InterludeTimeline.presence(t, g0, g1)
            assertTrue("scale jump at $t", abs(s - prevScale) < 0.015f)
            assertTrue("presence jump at $t", abs(p - prevPresence) < 0.01f)
            prevScale = s
            prevPresence = p
            t++
        }
        // Pure in t: the same instant always gives the same values (a seek recomputes from t).
        assertEquals(InterludeTimeline.scale(20_123, g0, g1), InterludeTimeline.scale(20_123, g0, g1), 0f)
    }

    // ---- §1 / §1.3 blur ------------------------------------------------------------------------

    @Test
    fun depthBlurTable() {
        val expected = floatArrayOf(0f, 1.6f, 2.4f, 3.2f, 4.0f, 4.8f, 5.0f, 5.0f)
        for (d in expected.indices) assertEquals("d=$d", expected[d], LyricsBlurMath.depthSigmaDp(d, 1f), 1e-5f)
        assertEquals(1.92f, LyricsBlurMath.depthSigmaDp(1, 1.2f), 1e-5f)
    }

    @Test
    fun blurConversions() {
        // σ = 0.57735·r + 0.5  ⇒  r = (σ·density − 0.5) / 0.57735
        assertEquals(6.7550f, LyricsBlurMath.sigmaDpToRadiusPx(1.6f, 2.75f), 1e-3f)
        assertEquals(0f, LyricsBlurMath.sigmaDpToRadiusPx(0.1f, 1f), 0f)
        assertEquals(LyricsBlurMath.sigmaDpToRadiusPx(5f, 3f), LyricsBlurMath.cssShadowBlurToRadiusPx(10f, 3f), 1e-5f)
        assertEquals(1.25f, LyricsBlurMath.quantizeRadiusPx(1.13f), 1e-6f)
        assertEquals(1.0f, LyricsBlurMath.quantizeRadiusPx(1.12f), 1e-6f)
    }

    @Test
    fun api30AlphaFalloff() {
        assertEquals(1f, LyricsBlurMath.fallbackAlphaFactor(0), 0f)
        assertEquals(0.94f, LyricsBlurMath.fallbackAlphaFactor(1), 1e-6f)
        assertEquals(0.152f, 0.2f * LyricsBlurMath.fallbackAlphaFactor(4), 1e-6f)
        assertEquals(0.152f, 0.2f * LyricsBlurMath.fallbackAlphaFactor(9), 1e-6f)
    }
}
