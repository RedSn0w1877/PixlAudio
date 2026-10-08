package com.theveloper.pixelplay.data.stream

import androidx.media3.common.C
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy.Conditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy.Network
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamPrefetchPolicyTest {

    private val homeWifi = Conditions(Network.WIFI, metered = false, dataSaver = false)
    private val cellular = Conditions(Network.CELLULAR, metered = true, dataSaver = false)

    private fun depth(conditions: Conditions, playing: Boolean = true, connect: Boolean = false) =
        StreamPrefetchPolicy.depth(conditions, isPlaying = playing, isConnectSession = connect)

    @Test
    fun `two songs on unmetered wifi or ethernet, one on cellular and anything else`() {
        assertEquals(2, depth(homeWifi))
        assertEquals(2, depth(Conditions(Network.ETHERNET, metered = false, dataSaver = false)))
        assertEquals(1, depth(cellular))
        assertEquals(1, depth(Conditions(Network.OTHER, metered = true, dataSaver = false)))
        // A phone hotspot or capped Wi-Fi counts as metered.
        assertEquals(1, depth(homeWifi.copy(metered = true)))
    }

    @Test
    fun `nothing while paused, offline, under data saver or during spotify connect`() {
        assertEquals(0, depth(homeWifi, playing = false))
        assertEquals(0, depth(Conditions(Network.OFFLINE, metered = true, dataSaver = false)))
        assertEquals(0, depth(homeWifi.copy(dataSaver = true)))
        assertEquals(0, depth(cellular.copy(dataSaver = true)))
        assertEquals(0, depth(homeWifi, connect = true))
    }

    @Test
    fun `the listening cache never runs under data saver, the next song only on unmetered wifi`() {
        assertTrue(StreamPrefetchPolicy.allowsListeningCache(homeWifi))
        assertTrue(StreamPrefetchPolicy.allowsListeningCache(cellular))
        assertFalse(StreamPrefetchPolicy.allowsListeningCache(cellular.copy(dataSaver = true)))
        assertFalse(StreamPrefetchPolicy.allowsListeningCache(Conditions(Network.OFFLINE, metered = true, dataSaver = false)))

        assertTrue(StreamPrefetchPolicy.allowsFullNextSongCache(homeWifi))
        assertFalse(StreamPrefetchPolicy.allowsFullNextSongCache(homeWifi.copy(metered = true)))
        assertFalse(StreamPrefetchPolicy.allowsFullNextSongCache(homeWifi.copy(dataSaver = true)))
        assertFalse(StreamPrefetchPolicy.allowsFullNextSongCache(cellular.copy(metered = false)))
    }

    @Test
    fun `upcoming follows the given order, as in shuffle`() {
        val shuffleOrder = mapOf(4 to 1, 1 to 7, 7 to 2, 2 to C.INDEX_UNSET)
        val next = { i: Int -> shuffleOrder[i] ?: C.INDEX_UNSET }
        assertEquals(listOf(1, 7), StreamPrefetchPolicy.upcomingIndices(current = 4, depth = 2, next = next))
        assertEquals(listOf(1), StreamPrefetchPolicy.upcomingIndices(current = 4, depth = 1, next = next))
    }

    @Test
    fun `upcoming wraps under repeat all and stops at the end of the queue`() {
        val count = 3
        val repeatAll = { i: Int -> (i + 1) % count }
        val noRepeat = { i: Int -> if (i + 1 < count) i + 1 else C.INDEX_UNSET }
        assertEquals(listOf(0, 1), StreamPrefetchPolicy.upcomingIndices(current = 2, depth = 2, next = repeatAll))
        assertEquals(listOf(2), StreamPrefetchPolicy.upcomingIndices(current = 1, depth = 2, next = noRepeat))
        assertEquals(emptyList<Int>(), StreamPrefetchPolicy.upcomingIndices(current = 2, depth = 2, next = noRepeat))
    }

    @Test
    fun `upcoming never returns the current song or a duplicate`() {
        // A two-song queue under repeat-all: the second step comes back to the current song.
        val twoSongs = { i: Int -> (i + 1) % 2 }
        assertEquals(listOf(1), StreamPrefetchPolicy.upcomingIndices(current = 0, depth = 2, next = twoSongs))
        // A one-song queue under repeat-all.
        assertEquals(emptyList<Int>(), StreamPrefetchPolicy.upcomingIndices(current = 0, depth = 2, next = { 0 }))
        // A broken order that loops between two others.
        val loop = mapOf(0 to 1, 1 to 2, 2 to 1)
        assertEquals(listOf(1, 2), StreamPrefetchPolicy.upcomingIndices(current = 0, depth = 3) { loop.getValue(it) })
    }

    @Test
    fun `no current song or no depth means nothing`() {
        assertEquals(emptyList<Int>(), StreamPrefetchPolicy.upcomingIndices(C.INDEX_UNSET, 2) { it + 1 })
        assertEquals(emptyList<Int>(), StreamPrefetchPolicy.upcomingIndices(0, 0) { it + 1 })
    }
}
