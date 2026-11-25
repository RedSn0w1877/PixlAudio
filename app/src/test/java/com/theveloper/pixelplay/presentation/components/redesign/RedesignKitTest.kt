package com.theveloper.pixelplay.presentation.components.redesign

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedesignKitTest {

    @Test
    fun `grouped rows round only the ends of the group`() {
        val first = groupedRowShape(0, 4)
        val middle = groupedRowShape(1, 4)
        val last = groupedRowShape(3, 4)
        val single = groupedRowShape(0, 1)
        assertThat(first).isEqualTo(
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        )
        assertThat(middle).isEqualTo(RoundedCornerShape(4.dp))
        assertThat(last).isEqualTo(
            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        )
        assertThat(single).isEqualTo(RoundedCornerShape(20.dp))
    }

    @Test
    fun `default grouped shapes are shared instances, so rows allocate nothing`() {
        assertThat(groupedRowShape(0, 5)).isSameInstanceAs(groupedRowShape(0, 9))
        assertThat(groupedRowShape(2, 5)).isSameInstanceAs(groupedRowShape(3, 9))
        assertThat(groupedRowShape(4, 5)).isSameInstanceAs(groupedRowShape(8, 9))
        assertThat(groupedRowShape(0, 0)).isSameInstanceAs(groupedRowShape(0, 1))
    }

    @Test
    fun `custom corner sizes still follow the first middle last rule`() {
        assertThat(groupedRowShape(0, 3, outer = 28.dp, inner = 8.dp)).isEqualTo(
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        )
        assertThat(groupedRowShape(1, 3, outer = 28.dp, inner = 8.dp)).isEqualTo(RoundedCornerShape(8.dp))
    }

    @Test
    fun `connected group corners are half the height outside and the inner radius inside`() {
        assertThat(groupShape(GroupPosition.First, 40.dp)).isEqualTo(
            RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp)
        )
        assertThat(groupShape(GroupPosition.Last, 44.dp, inner = 8.dp)).isEqualTo(
            RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp)
        )
        assertThat(groupShape(GroupPosition.Middle, 44.dp, inner = 8.dp)).isEqualTo(RoundedCornerShape(8.dp))
        assertThat(groupShape(GroupPosition.Single, 40.dp)).isEqualTo(RoundedCornerShape(20.dp))
    }
}
