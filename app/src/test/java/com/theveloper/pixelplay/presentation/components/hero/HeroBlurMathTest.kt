package com.theveloper.pixelplay.presentation.components.hero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroBlurMathTest {

    @Test
    fun centerCrop_squareIntoSquare_isAPlainScale() {
        val crop = HeroBlurMath.centerCrop(96f, 96f, 1080f, 1080f)
        assertEquals(1080f / 96f, crop.scale, 1e-4f)
        assertEquals(0f, crop.dx, 1e-4f)
        assertEquals(0f, crop.dy, 1e-4f)
    }

    @Test
    fun centerCrop_squareIntoShorterBox_fillsWidthAndCentresVertically() {
        // The artist hero: about 0.92 as tall as it is wide.
        val crop = HeroBlurMath.centerCrop(96f, 96f, 1000f, 920f)
        assertEquals(1000f / 96f, crop.scale, 1e-4f)
        assertEquals(0f, crop.dx, 1e-3f)
        assertEquals(-40f, crop.dy, 1e-3f)
    }

    @Test
    fun centerCrop_squareIntoTallerBox_fillsHeightAndCentresHorizontally() {
        val crop = HeroBlurMath.centerCrop(96f, 96f, 800f, 1000f)
        assertEquals(1000f / 96f, crop.scale, 1e-4f)
        assertEquals(-100f, crop.dx, 1e-3f)
        assertEquals(0f, crop.dy, 1e-3f)
    }

    @Test
    fun centerCrop_degenerateSizes_areIdentity() {
        val crop = HeroBlurMath.centerCrop(0f, 96f, 100f, 100f)
        assertEquals(1f, crop.scale, 0f)
        assertEquals(0f, crop.dx, 0f)
        assertEquals(0f, crop.dy, 0f)
    }

    @Test
    fun pageScrim_hitsTheMockupStops() {
        val stops = HeroBlurMath.PAGE_SCRIM_STOPS
        val alphas = HeroBlurMath.PAGE_SCRIM_ALPHAS
        for (i in stops.indices) {
            assertEquals(alphas[i], HeroBlurMath.pageScrimAt(stops[i]), 1e-5f)
        }
    }

    @Test
    fun pageScrim_interpolatesAndClamps() {
        // Halfway between 0.38 (0.38) and 0.60 (0.58).
        assertEquals(0.48f, HeroBlurMath.pageScrimAt(0.49f), 1e-4f)
        assertEquals(HeroBlurMath.PAGE_SCRIM_ALPHAS.first(), HeroBlurMath.pageScrimAt(-1f), 0f)
        assertEquals(1f, HeroBlurMath.pageScrimAt(2f), 0f)
    }

    @Test
    fun pageScrim_isClearOverThePicture() {
        // The top shade is gone by 18 % and the photo stays clear there.
        assertEquals(0f, HeroBlurMath.pageScrimAt(0.18f), 1e-6f)
        assertTrue(HeroBlurMath.pageScrimAt(0.10f) < HeroBlurMath.pageScrimAt(0f))
    }

    @Test
    fun pageRamp_blurIsInBeforeTheBottomThird() {
        assertTrue(HeroBlurMath.PAGE_RAMP_START < HeroBlurMath.PAGE_RAMP_END)
        assertTrue(HeroBlurMath.PAGE_RAMP_END <= 2f / 3f + 1e-6f)
    }

    @Test
    fun pageBackdropLuma_mixesTowardTheBackground() {
        val bright = 0.9f
        val darkBg = 0.05f
        val at = HeroBlurMath.pageBackdropLuma(bright, darkBg, 0.60f)
        // 58 % of the background over the picture there.
        assertEquals(bright + (darkBg - bright) * 0.58f, at, 1e-4f)
        assertTrue(at < bright && at > darkBg)
    }

    @Test
    fun saturate_unchangedAtOne_andKeepsAlphaAndGrey() {
        val pixels = intArrayOf(0xFF336699.toInt(), 0x80808080.toInt())
        val copy = pixels.copyOf()
        HeroBlurMath.saturate(copy, 1f)
        assertTrue(pixels.contentEquals(copy))
        HeroBlurMath.saturate(copy, 1.5f)
        // Grey has no chroma to boost; alpha is kept.
        assertEquals(0x80808080.toInt(), copy[1])
        assertEquals(0xFF, copy[0] ushr 24)
    }
}
