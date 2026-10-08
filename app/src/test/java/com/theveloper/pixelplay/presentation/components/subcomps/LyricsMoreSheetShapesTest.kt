package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricsMoreSheetShapesTest {

    @Test
    fun `a single row gets both outer corners`() {
        assertEquals(18.dp to 24.dp, groupRowCorners(0, 1))
    }

    @Test
    fun `the first row is rounded on top, the last at the bottom, the rest inside`() {
        for (count in 2..4) {
            assertEquals(18.dp to 8.dp, groupRowCorners(0, count), "first of $count")
            for (middle in 1 until count - 1) {
                assertEquals(8.dp to 8.dp, groupRowCorners(middle, count), "row $middle of $count")
            }
            assertEquals(8.dp to 24.dp, groupRowCorners(count - 1, count), "last of $count")
        }
    }
}
