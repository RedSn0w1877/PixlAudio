package com.theveloper.pixelplay.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSheetGeometryTest {

    @Test
    fun glassQueue_isAbout92PercentOnATallScreen() {
        // A 2400 px tall player with a 140 px status bar: 0.92 × 2400 leaves 192 px above.
        val height = resolveQueueSheetHeightPx(containerPx = 2400f, statusTopPx = 140f, minTopGapPx = 24f)
        assertEquals(2208f, height, 1e-3f)
        assertEquals(0.92f, QUEUE_SHEET_HEIGHT_FRACTION, 0f)
    }

    @Test
    fun glassQueue_staysBelowTheStatusBarOnAShortScreen() {
        // Landscape: 0.92 × 1000 = 920 would leave 80 px, less than status bar + gap (90 + 24).
        val height = resolveQueueSheetHeightPx(containerPx = 1000f, statusTopPx = 90f, minTopGapPx = 24f)
        assertEquals(886f, height, 1e-3f)
        assertTrue(1000f - height >= 90f + 24f - 1e-3f)
    }

    @Test
    fun glassQueue_heightIsNeverNegative() {
        assertEquals(0f, resolveQueueSheetHeightPx(containerPx = 50f, statusTopPx = 90f, minTopGapPx = 24f), 0f)
        assertEquals(0f, resolveQueueSheetHeightPx(containerPx = 0f, statusTopPx = 0f, minTopGapPx = 0f), 0f)
    }

    @Test
    fun glassQueue_hiddenOffsetFollowsTheSheetHeight() {
        // QueueSheetState hides the sheet by its measured height plus the bottom padding, so a
        // shorter sheet slides a shorter way and still ends fully off screen.
        val height = resolveQueueSheetHeightPx(containerPx = 2400f, statusTopPx = 140f, minTopGapPx = 24f)
        val bottomPadding = 0f
        val hiddenOffset = height + bottomPadding
        val sheetTop = 2400f - height
        assertEquals(2400f, sheetTop + hiddenOffset, 1e-3f)
    }

    @Test
    fun moreOrbFallback_sitsAtTheEndOfTheCentredRow() {
        // 268 dp row (3 × 56 + 3 × 12 + 64) at density 1: centred in 400 px, 70 tall, 40 px up.
        val rect = queueMoreOrbFallbackRect(
            containerWidth = 400f,
            containerHeight = 800f,
            toolbarBottomPx = 40f,
            rowHeightPx = 70f,
            rowWidthPx = 268f,
            orbPx = 64f,
        )
        assertEquals(66f + 268f - 64f, rect.left, 1e-3f)
        assertEquals(64f, rect.width, 1e-3f)
        assertEquals(64f, rect.height, 1e-3f)
        // Centred in the row: row spans [800 - 40 - 70, 800 - 40].
        assertEquals(800f - 40f - 35f, (rect.top + rect.bottom) / 2f, 1e-3f)
    }

    @Test
    fun savePill_isCentredOnTheToolbarRow() {
        // 1080 × 2208 sheet, toolbar row 70 dp tall sitting 100 px above the bottom (px values).
        val rect = queueSavePillRect(
            containerWidth = 1080f,
            containerHeight = 2208f,
            toolbarBottomPx = 100f,
            rowHeightPx = 210f,
            pillWidthPx = 600f,
            pillHeightPx = 168f,
        )
        assertEquals(240f, rect.left, 1e-3f)
        assertEquals(840f, rect.right, 1e-3f)
        assertEquals(168f, rect.height, 1e-3f)
        // Vertically centred on the row: row spans [2208 - 100 - 210, 2208 - 100].
        val rowCentre = 2208f - 100f - 105f
        assertEquals(rowCentre, (rect.top + rect.bottom) / 2f, 1e-3f)
    }
}
