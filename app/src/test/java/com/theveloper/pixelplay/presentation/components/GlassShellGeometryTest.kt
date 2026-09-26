package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Glass mode's shell geometry: the mini player floats [MiniPlayerBottomSpacer] above NexHome's tab
 * bar, which sits 8 dp above the gesture handle.
 */
class GlassShellGeometryTest {

    @Test
    fun glassNavBarOccupiedHeight_isBarPlusGapPlusInset() {
        assertEquals(64.dp + 8.dp + 24.dp, resolveGlassNavBarOccupiedHeight(24.dp))
        assertEquals(72.dp, resolveGlassNavBarOccupiedHeight(0.dp))
    }

    @Test
    fun glassMiniPlayerCard_isACapsuleWhenCollapsed() {
        assertEquals(MiniPlayerHeight / 2, GlassMiniPlayerCorner)
        assertEquals(32.dp, GlassMiniPlayerCorner)
    }

    @Test
    fun glassMiniPlayer_floatsAboveTheGlassBar() {
        // The collapsed card's bottom sits the spacer above the occupied height.
        val containerPx = 2000f
        val occupiedPx = 96f
        val y = calculatePlayerSheetCollapsedTargetY(
            containerHeightPx = containerPx,
            collapsedContentHeightPx = 64f,
            bottomMarginPx = occupiedPx,
            bottomSpacerPx = 8f
        )
        assertEquals(containerPx - 64f - occupiedPx - 8f, y, 0.001f)
    }
}
