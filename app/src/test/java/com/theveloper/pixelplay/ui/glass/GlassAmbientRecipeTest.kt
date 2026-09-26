package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.ui.glass.theme.GlassAmbientRecipe
import com.theveloper.pixelplay.ui.glass.theme.GlassAmbientSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAmbientRecipeTest {

    private fun spec(
        artUri: String? = "content://art/1",
        isDark: Boolean = true,
        blobs: List<Color>? = listOf(Color.Red, Color.Green, Color.Blue),
    ) = GlassAmbientSpec(artUri, isDark, blobs, 1080, 2400)

    @Test
    fun bakeSize_isAQuarterRoundedUp() {
        assertEquals(270, GlassAmbientRecipe.bakeWidth(1080))
        assertEquals(600, GlassAmbientRecipe.bakeHeight(2400))
        assertEquals(271, GlassAmbientRecipe.bakeWidth(1081))
        assertEquals(1, GlassAmbientRecipe.bakeWidth(0))
        assertEquals(1, GlassAmbientRecipe.bakeHeight(1))
    }

    @Test
    fun stopCenters_scaleNexHomeNebulaToTheCanvas() {
        val c = GlassAmbientRecipe.StopCenters
        assertEquals(4, c.size)
        assertEquals(0f, c[0].x, 0.001f)
        assertEquals(0f, c[0].y, 0.001f)
        assertEquals(1200f / 1080f, c[1].x, 1e-5f)
        assertEquals(0.125f, c[1].y, 1e-5f)
        assertEquals(200f / 1080f, c[2].x, 1e-5f)
        assertEquals(2200f / 2400f, c[2].y, 1e-5f)
        assertEquals(1000f / 1080f, c[3].x, 1e-5f)
        assertEquals(0.875f, c[3].y, 1e-5f)
        assertEquals(1300f / 2400f, GlassAmbientRecipe.STOP_RADIUS_OF_HEIGHT, 1e-6f)
    }

    @Test
    fun albumBlobs_areThreeStopsInNebulasRange() {
        val stops = GlassAmbientRecipe.stops(spec())
        assertEquals(3, stops.size)
        assertEquals(Color.Red, stops[0].color)
        assertEquals(GlassAmbientRecipe.StopCenters[0], stops[0].center)
        assertEquals(GlassAmbientRecipe.StopCenters[1], stops[1].center)
        assertEquals(GlassAmbientRecipe.StopCenters[3], stops[2].center)
        stops.forEach { assertTrue(it.alpha in 0.55f..0.9f) }
    }

    @Test
    fun extraBlobs_areIgnored() {
        val stops = GlassAmbientRecipe.stops(
            spec(blobs = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan))
        )
        assertEquals(3, stops.size)
    }

    @Test
    fun noBlobs_darkFallsBackToNexHomeNebula() {
        val stops = GlassAmbientRecipe.stops(spec(artUri = null, blobs = null))
        assertEquals(GlassAmbientRecipe.Nebula, stops)
        assertEquals(Color(0xFF241134), stops[0].color)
        assertEquals(1f, stops[0].alpha, 0f)
        assertEquals(0.55f, stops[3].alpha, 0f)
    }

    @Test
    fun noBlobs_lightIsJustTheBase() {
        assertTrue(GlassAmbientRecipe.stops(spec(isDark = false, blobs = null)).isEmpty())
        assertTrue(GlassAmbientRecipe.stops(spec(isDark = false, blobs = emptyList())).isEmpty())
    }

    @Test
    fun scrim_onlyOverArtwork() {
        assertTrue(GlassAmbientRecipe.hasScrim(spec()))
        assertFalse(GlassAmbientRecipe.hasScrim(spec(artUri = null)))
    }

    @Test
    fun darkScrim_isNexHomesAndRisesForBrightArt() {
        assertEquals(0.35f, GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = 0.1f), 1e-6f)
        assertEquals(0.35f, GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = 0.35f), 1e-6f)
        assertEquals(0.50f, GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = 0.9f), 1e-6f)
        val mid = GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = 0.5f)
        assertTrue(mid > 0.35f && mid < 0.5f)
    }

    @Test
    fun lightScrim_risesForDarkArt() {
        assertEquals(0.40f, GlassAmbientRecipe.scrimAlpha(isDark = false, meanLuma = 0.8f), 1e-6f)
        assertEquals(0.55f, GlassAmbientRecipe.scrimAlpha(isDark = false, meanLuma = 0.1f), 1e-6f)
    }

    @Test
    fun scrim_isMonotonicAndClamped() {
        var last = -1f
        for (i in 0..20) {
            val a = GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = i / 20f)
            assertTrue(a >= last)
            last = a
        }
        assertEquals(0.35f, GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = -3f), 1e-6f)
        assertEquals(0.5f, GlassAmbientRecipe.scrimAlpha(isDark = true, meanLuma = 7f), 1e-6f)
    }

    @Test
    fun scrimColor_followsTheTheme() {
        assertEquals(Color.Black.copy(alpha = 0.4f), GlassAmbientRecipe.scrimColor(isDark = true, alpha = 0.4f))
        assertEquals(Color.White.copy(alpha = 0.4f), GlassAmbientRecipe.scrimColor(isDark = false, alpha = 0.4f))
    }

    @Test
    fun luma_isRec709() {
        assertEquals(1f, GlassAmbientRecipe.luma(1f, 1f, 1f), 1e-5f)
        assertEquals(0f, GlassAmbientRecipe.luma(0f, 0f, 0f), 0f)
        assertEquals(0.7152f, GlassAmbientRecipe.luma(0f, 1f, 0f), 1e-6f)
    }

    @Test
    fun specEquality_isTheCacheKey() {
        assertEquals(spec(), spec())
        assertFalse(spec() == spec(isDark = false))
        assertFalse(spec() == spec(artUri = "content://art/2"))
    }
}
