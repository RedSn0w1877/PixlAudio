package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Endless Listening as a station (source 6): how it is saved, that old strings still read, and what is true of it. */
class EndlessStationTest {

    @Test
    fun `an endless station keeps its source and its five seeds through a restart`() {
        val station = Station.endless(listOf("10", "11", "12", "13", "14"), "Endless Listening", 9L)
        assertEquals(Station.SOURCE_ENDLESS, station.source)
        assertEquals(6, Station.SOURCE_ENDLESS)
        assertEquals(Session.SEED_STATION_SONG, station.kind)
        assertTrue(station.isEndless)
        assertFalse(station.isArtist)
        val back = Station.decode(station.encode())!!
        assertEquals(station, back)
        assertEquals(Station.SOURCE_ENDLESS, back.source)
        assertEquals(listOf("10", "11", "12", "13", "14"), back.seedMediaIds)
        assertEquals("Endless Listening", back.title)
    }

    @Test
    fun `it is a station of its own, not the same as a playlist station from the same first seed`() {
        val endless = Station.endless(listOf("10", "11"), "Endless Listening", 1L)
        assertNotEquals(endless.id, Station.fromSource(Station.SOURCE_PLAYLIST, listOf("10", "11"), "Endless Listening", 1L).id)
        assertNotEquals(endless.id, Station.song("10", "T", "A", 1L).id)
        // The same seeds on another day are the same station (its id is for its plays' surface, not the day).
        assertEquals(endless.id, Station.endless(listOf("10", "11"), "Endless Listening", 99L).id)
    }

    @Test
    fun `strings saved before source 6 existed still decode, and a source beyond it is refused`() {
        val sep = "\u001f"
        val five = listOf("2", "m1", "Title", "Artist", "7").joinToString(sep)
        assertEquals(Station.SOURCE_SONG, Station.decode(five)!!.source)
        for (source in 0..5) {
            val seven = listOf("2", "m1", "T", "A", "7", source.toString(), "m1").joinToString(sep)
            val decoded = Station.decode(seven)
            assertEquals(source, decoded!!.source, "source $source")
            assertFalse(decoded.isEndless)
        }
        val endless = listOf("2", "m1", "Endless Listening", "", "7", "6", "m1\u001em2").joinToString(sep)
        assertEquals(listOf("m1", "m2"), Station.decode(endless)!!.seedMediaIds)
        assertTrue(Station.decode(endless)!!.isEndless)
        assertNull(Station.decode(listOf("2", "m1", "T", "A", "7", "7", "m1").joinToString(sep)))
        assertNull(Station.decode(null))
        assertNull(Station.decode(""))
    }
}
