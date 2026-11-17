package com.theveloper.pixelplay.presentation.components.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHeroMathTest {

    @Test
    fun heroHeightRunsTheTailPastTheTitle() {
        assertEquals(1000 + 450, PlayerHeroMath.heroHeight(width = 1000, contentTop = 1000))
        // A negative title position (nothing measured yet) never shrinks the hero below the tail.
        assertEquals(450, PlayerHeroMath.heroHeight(width = 1000, contentTop = -20))
    }

    @Test
    fun stopsRecoverTheTitlePositionFromTheHeroHeight() {
        val s = PlayerHeroMath.stops(width = 1080f, height = PlayerHeroMath.heroHeight(1080, 1300).toFloat())
        assertEquals(1300f, s.contentTop, 1f)
    }

    @Test
    fun tallScreenRampEndsAtTheBottomOfTheSquare() {
        // Title well below the square: the blur must be complete by the square's bottom edge.
        val w = 1000f
        val s = PlayerHeroMath.stops(w, PlayerHeroMath.heroHeight(1000, 1400).toFloat())
        assertEquals(w * PlayerHeroMath.MAX_RAMP_END_FRACTION, s.rampEnd, 0.5f)
        assertEquals(s.rampEnd - w * PlayerHeroMath.RAMP_FRACTION, s.rampStart, 0.5f)
    }

    @Test
    fun shortScreenRampEndsJustAboveTheTitle() {
        val w = 1000f
        val s = PlayerHeroMath.stops(w, PlayerHeroMath.heroHeight(1000, 800).toFloat())
        assertEquals(800f - w * PlayerHeroMath.RAMP_END_GAP_FRACTION, s.rampEnd, 1f)
        assertTrue(s.rampStart < s.rampEnd)
    }

    @Test
    fun veryShortScreenKeepsTheTopSharp() {
        val w = 1000f
        val s = PlayerHeroMath.stops(w, PlayerHeroMath.heroHeight(1000, 100).toFloat())
        assertEquals(w * PlayerHeroMath.MIN_RAMP_END_FRACTION, s.rampEnd, 0.5f)
        assertTrue(s.rampStart >= 0f)
    }

    @Test
    fun stopsAreOrderedForTheGradients() {
        for (top in intArrayOf(0, 200, 500, 900, 1200, 2000)) {
            val s = PlayerHeroMath.stops(1000f, PlayerHeroMath.heroHeight(1000, top).toFloat())
            assertTrue(s.topScrimEnd <= s.rampStart)
            assertTrue(s.rampStart <= s.rampEnd)
            assertTrue(s.contentTop <= s.height)
            assertTrue(s.fraction(s.height) == 1f)
        }
    }

    @Test
    fun theHeroFadesOutBelowTheTitleWithoutAnEdge() {
        val w = 1000f
        val s = PlayerHeroMath.stops(w, PlayerHeroMath.heroHeight(1000, 1300).toFloat())
        assertEquals(1300f + w * PlayerHeroMath.FADE_START_GAP_FRACTION, s.fadeStart, 1f)
        assertTrue(s.fadeStart < s.height)
        val a = PlayerHeroMath.FADE_OUT_ALPHAS
        // Opaque at the start, gone at the end, never rising, and flat at both ends (smootherstep).
        assertEquals(1f, a.first(), 1e-6f)
        assertEquals(0f, a.last(), 1e-6f)
        for (i in 1 until a.size) assertTrue(a[i] <= a[i - 1])
        assertTrue(1f - a[1] < 0.02f)
        assertTrue(a[a.lastIndex - 1] < 0.02f)
    }

    @Test
    fun emptyHeroIsSafe() {
        val s = PlayerHeroMath.stops(0f, 0f)
        assertEquals(0f, s.fraction(10f), 0f)
        assertEquals(0f, s.rampStart, 0f)
    }

    @Test
    fun saturateLeavesGreyAndAlphaAlone() {
        val grey = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80
        val pixels = intArrayOf(grey, 0x40FF0000)
        PlayerHeroMath.saturate(pixels, 1.2f)
        assertEquals(grey, pixels[0])
        assertEquals(0x40, pixels[1] ushr 24)
    }

    @Test
    fun saturatePushesColourAwayFromGrey() {
        val c = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 100
        val pixels = intArrayOf(c)
        PlayerHeroMath.saturate(pixels, 1.2f)
        val r = (pixels[0] shr 16) and 0xFF
        val g = (pixels[0] shr 8) and 0xFF
        assertTrue(r > 200)
        assertTrue(g < 100)
    }

    @Test
    fun meanLumaReadsOnlyTheRequestedRows() {
        val size = 2
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(black, black, white, white)
        assertEquals(1f, PlayerHeroMath.meanLuma(pixels, size, 1, 2), 0.001f)
        assertEquals(0f, PlayerHeroMath.meanLuma(pixels, size, 0, 1), 0.001f)
        assertEquals(0.5f, PlayerHeroMath.meanLuma(pixels, size, 0, 2), 0.001f)
        assertEquals(0f, PlayerHeroMath.meanLuma(pixels, size, 5, 9), 0f)
    }

    @Test
    fun titleBackdropMixesInTheScrim() {
        val l = PlayerHeroMath.titleBackdropLuma(coverLuma = 1f, backgroundLuma = 0f)
        assertEquals(1f - PlayerHeroMath.TITLE_SCRIM, l, 0.0001f)
    }

    @Test
    fun dragIsRubberBandedAndCapped() {
        assertEquals(30f, PlayerHeroMath.dragOffset(90f, 1000f), 0.0001f)
        assertEquals(-250f, PlayerHeroMath.dragOffset(-5000f, 1000f), 0.0001f)
        assertEquals(0f, PlayerHeroMath.dragOffset(100f, 0f), 0f)
    }
}
