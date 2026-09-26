package com.theveloper.pixelplay.ui.glass

import com.theveloper.pixelplay.ui.glass.light.lerpAxisAngle
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.utils.ProgressConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class GlassKitMathTest {

    @Test
    fun progressConverter_isARubberBand() {
        val c = ProgressConverter.Default
        assertEquals(0f, c.convert(0f), 0f)
        assertEquals(1f - exp(-1f), c.convert(1f), 1e-6f)
        // Antisymmetric, bounded by ±1, monotonic.
        assertEquals(-c.convert(0.7f), c.convert(-0.7f), 1e-6f)
        assertTrue(c.convert(3f) < 1f)
        assertTrue(c.convert(50f) <= 1f)
        assertTrue(c.convert(0.5f) < c.convert(0.6f))
    }

    @Test
    fun lerpAxisAngle_takesTheShortestPathOnA180Axis() {
        assertEquals(10f, lerpAxisAngle(0f, 10f, 1f), 1e-5f)
        assertEquals(5f, lerpAxisAngle(0f, 10f, 0.5f), 1e-5f)
        // 170° is the same axis as -10°: turning there from 0° goes backwards by 10°.
        assertEquals(-10f, lerpAxisAngle(0f, 170f, 1f), 1e-5f)
        assertEquals(-5f, lerpAxisAngle(0f, 170f, 0.5f), 1e-5f)
        // Fraction 0 never moves.
        assertEquals(42f, lerpAxisAngle(42f, -120f, 0f), 1e-5f)
        // Never turns more than 90° in either direction.
        for (to in -360..360 step 7) {
            val end = lerpAxisAngle(0f, to.toFloat(), 1f)
            assertTrue(abs(end) <= 90f + 1e-4f)
        }
    }

    @Test
    fun motionTokens_matchNexHome() {
        assertEquals(1.07f, LiquidMotion.TilePressScale, 0f)
        assertEquals(1.16f, LiquidMotion.ButtonPressScale, 0f)
        assertEquals(1.12f, LiquidMotion.OrbPressScale, 0f)
        assertEquals(1.6f, LiquidMotion.LensThicken, 0f)
        assertEquals(0.08f, LiquidMotion.JellyStretch, 0f)
        assertEquals(0.06f, LiquidMotion.JellyFollow, 0f)
        assertEquals(1.15f, LiquidMotion.BlobPressedScaleBoost, 0f)
        // Presses swell, never shrink.
        assertTrue(LiquidMotion.TilePressScale > 1f)
        assertTrue(LiquidMotion.ButtonPressScale > 1f)
        assertTrue(LiquidMotion.OrbPressScale > 1f)
        assertEquals(0.34f, LiquidMotion.ReleaseSpring.dampingRatio, 0f)
        assertEquals(150f, LiquidMotion.ReleaseSpring.stiffness, 0f)
        assertEquals(0.62f, LiquidMotion.EnterSpring.dampingRatio, 0f)
        assertEquals(170f, LiquidMotion.EnterSpring.stiffness, 0f)
    }
}
