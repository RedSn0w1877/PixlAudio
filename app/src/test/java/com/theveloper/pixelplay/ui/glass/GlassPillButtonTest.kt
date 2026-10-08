package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassPillButtonTest {

    @Test
    fun prominentEnabledPill_isTheAccentAtTheLitAlpha() {
        for (palette in listOf(GlassPalette.Dark, GlassPalette.Light)) {
            assertEquals(palette.accent.copy(alpha = 0.38f), glassPillTint(palette, prominent = true, enabled = true))
        }
    }

    @Test
    fun secondaryOrDisabledPill_isTheSubtleTint() {
        for (palette in listOf(GlassPalette.Dark, GlassPalette.Light)) {
            assertEquals(palette.tintSubtle, glassPillTint(palette, prominent = false, enabled = true))
            assertEquals(palette.tintSubtle, glassPillTint(palette, prominent = true, enabled = false))
            assertEquals(palette.tintSubtle, glassPillTint(palette, prominent = false, enabled = false))
        }
    }

    @Test
    fun pillTint_followsTheTrackAccent() {
        val album = GlassPalette.of(isDark = true, accent = Color(0xFFFF5A36))
        assertEquals(Color(0xFFFF5A36).copy(alpha = GlassLitAlpha), glassPillTint(album, prominent = true, enabled = true))
    }
}
