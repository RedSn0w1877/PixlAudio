package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPaletteTest {

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }

    @Test
    fun dark_isNexHomeVerbatim() {
        val p = GlassPalette.Dark
        assertTrue(p.isDark)
        assertColor(Color.White, p.primary)
        assertColor(Color.White.copy(alpha = 0.72f), p.secondary)
        assertColor(Color.White.copy(alpha = 0.48f), p.tertiary)
        assertColor(Color.White.copy(alpha = 0.28f), p.quaternary)
        assertColor(Color.Black.copy(alpha = 0.05f), p.tint)
        assertColor(Color.Black.copy(alpha = 0.18f), p.tintStrong)
        assertColor(Color.White.copy(alpha = 0.08f), p.tintSubtle)
        assertColor(Color(0xFF121212).copy(alpha = 0.4f), p.bar)
        assertColor(Color(0xFF787880).copy(alpha = 0.36f), p.track)
        assertColor(Color.Black.copy(alpha = 0.38f), p.dim)
        assertColor(Color.White.copy(alpha = 0.1f), p.blobRest)
        assertColor(Color.White, p.thumb)
        assertColor(Color.Black.copy(alpha = 0.12f), p.orbSurface)
        assertColor(Color.Black.copy(alpha = 0.22f), p.topBar)
        assertColor(Color.Black.copy(alpha = 0.16f), p.well)
        assertColor(Color.Black.copy(alpha = 0.32f), p.scrim)
        assertEquals(-0.1f, p.scrimBrightness, 0f)
        assertEquals(1.25f, p.scrimSaturation, 0f)
        assertColor(Color(0xFF6FE3FF), p.accent)
    }

    @Test
    fun light_usesCatalogValues() {
        val p = GlassPalette.Light
        assertFalse(p.isDark)
        assertColor(Color.Black, p.primary)
        assertColor(Color(0xFFFAFAFA).copy(alpha = 0.4f), p.bar)
        assertColor(Color(0xFF787878).copy(alpha = 0.2f), p.track)
        assertColor(Color.Black.copy(alpha = 0.1f), p.blobRest)
        assertColor(Color.White, p.thumb)
        assertColor(Color(0xFF0088FF), p.accent)
    }

    @Test
    fun of_takesTheAlbumAccent() {
        val album = Color(0xFFFF8FB4)
        val dark = GlassPalette.of(isDark = true, accent = album)
        assertColor(album, dark.accent)
        assertTrue(dark.isDark)
        assertColor(GlassPalette.Dark.tint, dark.tint)

        val light = GlassPalette.of(isDark = false, accent = album)
        assertColor(album, light.accent)
        assertFalse(light.isDark)
        assertColor(GlassPalette.Light.bar, light.bar)
    }

    @Test
    fun of_unspecifiedAccent_keepsTheFallback() {
        assertSame(GlassPalette.Dark, GlassPalette.of(isDark = true, accent = Color.Unspecified))
        assertSame(GlassPalette.Light, GlassPalette.of(isDark = false, accent = Color.Unspecified))
    }

    @Test
    fun contentText_isNeverTheAccent() {
        val p = GlassPalette.of(isDark = true, accent = Color(0xFF52B6FF))
        assertNotEquals(p.accent, p.primary)
        assertNotEquals(p.accent, p.secondary)
    }

    @Test
    fun glow_staysDimInBothThemes() {
        // "Glow, not blind": no palette tint is brighter than NexHome's strongest light surface.
        for (p in listOf(GlassPalette.Dark, GlassPalette.Light)) {
            assertTrue(p.tint.alpha <= 0.3f)
            assertTrue(p.tintSubtle.alpha <= 0.3f)
            assertTrue(p.scrim.alpha <= 0.35f)
        }
    }
}
