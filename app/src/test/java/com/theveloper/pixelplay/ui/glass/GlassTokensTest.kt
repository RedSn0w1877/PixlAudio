package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.Highlight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassTokensTest {

    private val colors = GlassColors(
        surface = Color(0xFFF7F2FA),
        surfaceContainerLow = Color(0xFFF3EDF7),
        surfaceContainerHigh = Color(0xFFECE6F0),
        primary = Color(0xFF6750A4),
        onSurface = Color(0xFF1D1B20)
    )

    private fun recipe(
        role: GlassRole,
        t: Float = 0.55f,
        isDark: Boolean = false,
        capability: GlassCapability = GlassCapability.Full,
        highContrast: Boolean = false
    ) = GlassTokens.recipe(role, t, isDark, capability, highContrast, colors)

    @Test
    fun `nav bar tint follows the transparency dial`() {
        assertEquals(0.30f, recipe(GlassRole.NavBar, t = 0f).tintAlpha, 1e-4f)
        assertEquals(0.72f, recipe(GlassRole.NavBar, t = 1f).tintAlpha, 1e-4f)
        assertEquals(colors.surface.copy(alpha = 0.72f), recipe(GlassRole.NavBar, t = 1f).tint)
    }

    @Test
    fun `full capability keeps the lens and the recipe's highlight`() {
        val bar = recipe(GlassRole.NavBar)
        assertTrue(bar.hasLens)
        assertEquals(8.dp, bar.blur)
        assertEquals(Highlight.Default, bar.highlight)
    }

    @Test
    fun `blur-only drops the lens, widens the blur and tints heavier`() {
        val full = recipe(GlassRole.IconButton)
        val blurOnly = recipe(GlassRole.IconButton, capability = GlassCapability.BlurOnly)
        assertFalse(blurOnly.hasLens)
        assertEquals(full.blur * 1.5f, blurOnly.blur)
        assertEquals(full.tintAlpha + 0.20f, blurOnly.tintAlpha, 1e-4f)
        assertEquals(Highlight.Plain.style, blurOnly.highlight?.style)
    }

    @Test
    fun `blur-only nav bar never drops below half opacity`() {
        val bar = recipe(GlassRole.NavBar, t = 0f, capability = GlassCapability.BlurOnly)
        assertTrue(bar.tintAlpha >= 0.50f)
    }

    @Test
    fun `high contrast adds a border and a heavier tint`() {
        val normal = recipe(GlassRole.Group)
        val contrast = recipe(GlassRole.Group, highContrast = true)
        assertEquals(1.dp, contrast.border)
        assertEquals(colors.onSurface.copy(alpha = 0.5f), contrast.borderColor)
        assertEquals(normal.tintAlpha + 0.25f, contrast.tintAlpha, 1e-4f)
    }

    @Test
    fun `sheets use the plain highlight and a lighter blur in dark mode`() {
        assertEquals(Highlight.Plain, recipe(GlassRole.Sheet).highlight)
        assertEquals(16.dp, recipe(GlassRole.Sheet, isDark = false).blur)
        assertEquals(12.dp, recipe(GlassRole.Sheet, isDark = true).blur)
        assertEquals(GlassColorFilter.Controls, recipe(GlassRole.Sheet).colorFilter)
    }

    @Test
    fun `thumb is opaque white at rest and clear when lifted`() {
        val thumb = recipe(GlassRole.Thumb)
        assertTrue(thumb.materializes)
        assertEquals(Color.White, thumb.tint)
        assertEquals(0f, thumb.materializedTint.alpha, 1e-4f)
        assertTrue(thumb.chromaticAberration)
    }

    @Test
    fun `withTintColor keeps the recipe's alpha`() {
        val sheet = recipe(GlassRole.Sheet).withTintColor(Color.Red)
        assertEquals(Color.Red.copy(alpha = sheet.tintAlpha), sheet.tint)
    }

    @Test
    fun `capability tiers by API level`() {
        assertEquals(GlassCapability.Full, GlassCapability.forDevice(33))
        assertEquals(GlassCapability.Full, GlassCapability.forDevice(36))
        assertEquals(GlassCapability.BlurOnly, GlassCapability.forDevice(31))
        assertEquals(GlassCapability.BlurOnly, GlassCapability.forDevice(32))
        assertEquals(GlassCapability.None, GlassCapability.forDevice(30))
    }

    @Test
    fun `style resolves to Material 3 without glass capability or with blur disabled`() {
        val glass = AppUiStyle.LiquidGlass.name
        assertEquals(AppUiStyle.LiquidGlass, AppUiStyle.resolve(glass, false, GlassCapability.Full))
        assertEquals(AppUiStyle.LiquidGlass, AppUiStyle.resolve(glass, false, GlassCapability.BlurOnly))
        assertEquals(AppUiStyle.Material3, AppUiStyle.resolve(glass, false, GlassCapability.None))
        assertEquals(AppUiStyle.Material3, AppUiStyle.resolve(glass, true, GlassCapability.Full))
        assertEquals(
            AppUiStyle.Material3,
            AppUiStyle.resolve(AppUiStyle.Material3.name, false, GlassCapability.Full)
        )
    }

    @Test
    fun `slider values snap to the nearest step`() {
        // 0..100 with 3 steps → stops at 0, 25, 50, 75, 100.
        assertEquals(25f, snapToSteps(30f, 0f..100f, 3), 1e-4f)
        assertEquals(50f, snapToSteps(38f, 0f..100f, 3), 1e-4f)
        assertEquals(100f, snapToSteps(140f, 0f..100f, 3), 1e-4f)
        assertEquals(0f, snapToSteps(-5f, 0f..100f, 3), 1e-4f)
        // No steps: continuous, only clamped.
        assertEquals(37.5f, snapToSteps(37.5f, 0f..100f, 0), 1e-4f)
    }
}
