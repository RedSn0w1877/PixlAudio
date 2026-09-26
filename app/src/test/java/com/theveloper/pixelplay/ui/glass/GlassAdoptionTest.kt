package com.theveloper.pixelplay.ui.glass

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.presentation.components.forGlassContent
import com.theveloper.pixelplay.ui.glass.components.snapSliderValue
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAdoptionTest {

    @Test
    fun sliderSteps_snapToEqualIntervals() {
        val range = 0f..10f
        // No steps: only clamped.
        assertEquals(3.3f, snapSliderValue(3.3f, range, 0), 1e-6f)
        assertEquals(10f, snapSliderValue(12f, range, 0), 1e-6f)
        // 4 steps = 5 intervals of 2.
        assertEquals(4f, snapSliderValue(3.3f, range, 4), 1e-5f)
        assertEquals(2f, snapSliderValue(2.9f, range, 4), 1e-5f)
        assertEquals(0f, snapSliderValue(-1f, range, 4), 1e-5f)
        assertEquals(10f, snapSliderValue(9.5f, range, 4), 1e-5f)
        // Offset range.
        assertEquals(1.5f, snapSliderValue(1.4f, 0.5f..2.5f, 3), 1e-5f)
    }

    @Test
    fun glassContentRemap_keepsOriginalColoursAtLowerAlpha() {
        val base = darkColorScheme()
        val glass = base.forGlassContent(GlassPalette.Dark)
        // Pages and surfaces are clear, but keep their RGB, so `surface.copy(alpha = x)` overlays
        // still draw the Material colour.
        assertEquals(0f, glass.background.alpha, 0f)
        assertEquals(0f, glass.surface.alpha, 0f)
        assertEquals(base.surface.copy(alpha = 0.9f), glass.surface.copy(alpha = 0.9f))
        assertEquals(base.surfaceVariant.copy(alpha = 0.3f), glass.surfaceVariant.copy(alpha = 0.3f))
        // Containers are translucent versions of themselves, in the same order.
        assertTrue(glass.surfaceContainerLow.alpha < glass.surfaceContainerHigh.alpha)
        assertEquals(base.surfaceContainer.red, glass.surfaceContainer.red, 1e-6f)
        // Text takes the palette, accents stay the scheme's own.
        assertEquals(Color.White, glass.onSurface)
        assertEquals(base.primary, glass.primary)
    }

    @Test
    fun glassContentRemap_isIdempotent() {
        val once = lightColorScheme().forGlassContent(GlassPalette.Light)
        val twice = once.forGlassContent(GlassPalette.Light)
        assertEquals(once.surface, twice.surface)
        assertEquals(once.surfaceContainerHighest, twice.surfaceContainerHighest)
        assertEquals(once.primaryContainer, twice.primaryContainer)
        assertEquals(Color.Black, once.onSurface)
    }
}
