package com.theveloper.pixelplay.data.playlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaylistOrderTest {
    @Test
    fun `dragging visible songs preserves unavailable and concurrently added entries`() {
        assertEquals(
            listOf("c", "a", "unavailable", "new"),
            mergePlaylistOrder(listOf("a", "unavailable", "c", "new"), listOf("c", "a"))
        )
    }

    @Test
    fun `stale drag cannot reintroduce deleted songs or duplicate entries`() {
        assertEquals(
            listOf("b", "a"),
            mergePlaylistOrder(listOf("a", "b"), listOf("deleted", "b", "b", "a"))
        )
    }
}
