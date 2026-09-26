package com.theveloper.pixelplay.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualStyleTest {

    @Test
    fun glassPreference_onCapableDevice_isGlassMode() {
        assertTrue(VisualStyle.isGlassMode(VisualStyle.LIQUID_GLASS, disableBlurAllOver = false, sdkInt = 33))
        assertTrue(VisualStyle.isGlassMode(VisualStyle.LIQUID_GLASS, disableBlurAllOver = false, sdkInt = 31))
    }

    @Test
    fun material3Preference_isNeverGlassMode() {
        assertFalse(VisualStyle.isGlassMode(VisualStyle.MATERIAL3, disableBlurAllOver = false, sdkInt = 36))
    }

    @Test
    fun missingOrUnknownPreference_keepsTheStoredDefault() {
        assertTrue(VisualStyle.isGlassMode(null, disableBlurAllOver = false, sdkInt = 33))
        assertTrue(VisualStyle.isGlassMode("Something", disableBlurAllOver = false, sdkInt = 33))
    }

    @Test
    fun disableBlurAllOver_winsOverTheChoice() {
        assertFalse(VisualStyle.isGlassMode(VisualStyle.LIQUID_GLASS, disableBlurAllOver = true, sdkInt = 36))
    }

    @Test
    fun api30_fallsBackToMaterial3() {
        assertFalse(VisualStyle.isGlassMode(VisualStyle.LIQUID_GLASS, disableBlurAllOver = false, sdkInt = 30))
    }
}
