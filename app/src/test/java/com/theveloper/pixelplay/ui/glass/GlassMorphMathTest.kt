package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.geometry.Rect
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMorphMathTest {

    private val circle = Rect(left = 300f, top = 800f, right = 364f, bottom = 864f)
    private val pill = Rect(left = 80f, top = 804f, right = 310f, bottom = 860f)

    @Test
    fun lerpMorphRect_hitsBothEnds() {
        assertEquals(circle, lerpMorphRect(circle, pill, 0f))
        assertEquals(pill, lerpMorphRect(circle, pill, 1f))
    }

    @Test
    fun lerpMorphRect_midpointIsHalfway() {
        val mid = lerpMorphRect(circle, pill, 0.5f)
        assertEquals(190f, mid.left, 1e-4f)
        assertEquals(802f, mid.top, 1e-4f)
        assertEquals(337f, mid.right, 1e-4f)
        assertEquals(862f, mid.bottom, 1e-4f)
    }

    @Test
    fun lerpMorphRect_clampsTheSpringOvershoot() {
        // The open spring overshoots past 1 and the close spring can't go below 0 here, but a
        // negative fraction must never shrink the circle either.
        assertEquals(pill, lerpMorphRect(circle, pill, 1.12f))
        assertEquals(circle, lerpMorphRect(circle, pill, -0.2f))
        val overshoot = lerpMorphRect(circle, pill, 3f)
        assertTrue(overshoot.width > 0f && overshoot.height > 0f)
    }

    @Test
    fun morphFadeOut_isGoneByItsEnd() {
        assertEquals(1f, morphFadeOut(0f), 0f)
        assertEquals(0.5f, morphFadeOut(0.2f), 1e-6f)
        assertEquals(0f, morphFadeOut(0.4f), 1e-6f)
        assertEquals(0f, morphFadeOut(1.1f), 0f)
        assertEquals(1f, morphFadeOut(-0.1f), 0f)
    }

    @Test
    fun morphFadeIn_waitsForItsStart() {
        assertEquals(0f, morphFadeIn(0f), 0f)
        assertEquals(0f, morphFadeIn(0.5f), 0f)
        assertEquals(0.5f, morphFadeIn(0.75f), 1e-6f)
        assertEquals(1f, morphFadeIn(1f), 1e-6f)
        assertEquals(1f, morphFadeIn(1.2f), 0f)
        // A staggered start (the menu's upper pill) still ends at 1.
        assertEquals(0f, morphFadeIn(0.35f, start = 0.35f), 0f)
        assertEquals(1f, morphFadeIn(1f, start = 0.35f), 1e-6f)
    }

    @Test
    fun morphFades_areMonotonic() {
        var lastOut = morphFadeOut(0f)
        var lastIn = morphFadeIn(0f)
        for (i in 1..100) {
            val p = i / 100f
            val out = morphFadeOut(p)
            val fadeIn = morphFadeIn(p)
            assertTrue(out <= lastOut)
            assertTrue(fadeIn >= lastIn)
            assertTrue(out in 0f..1f && fadeIn in 0f..1f)
            lastOut = out
            lastIn = fadeIn
        }
    }

    @Test
    fun morphSprings_openWithOvershootAndCloseWithout() {
        assertEquals(0.72f, LiquidMotion.MorphOpenSpring.dampingRatio, 0f)
        assertEquals(360f, LiquidMotion.MorphOpenSpring.stiffness, 0f)
        assertEquals(1f, LiquidMotion.MorphCloseSpring.dampingRatio, 0f)
        assertEquals(520f, LiquidMotion.MorphCloseSpring.stiffness, 0f)
        // Faster than the sheet's lens bloom.
        assertTrue(LiquidMotion.MorphOpenSpring.stiffness > LiquidMotion.EnterSpring.stiffness)
    }
}
