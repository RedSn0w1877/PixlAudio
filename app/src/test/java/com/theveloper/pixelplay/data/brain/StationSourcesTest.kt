package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stations from an album and from a playlist (docs/brain-spec.md, 6.2, 8.3): their seeds and their saved form. */
class StationSourcesTest {

    private fun song(id: String, artist: String, title: String = "T$id") = Song.emptySong().copy(
        id = id, title = title, artist = artist, artists = listOf(ArtistRef(id = artist.hashCode().toLong(), name = artist, isPrimary = true)),
    )

    @Test
    fun `the seeds are the most played, then the first in order, two per artist at most`() {
        val songs = listOf(
            song("1", "A"), song("2", "A"), song("3", "A"), song("4", "B"), song("5", "C"), song("6", "C"),
        )
        val plays = mapOf("3" to 9, "5" to 4, "2" to 4, "6" to 1)
        val seeds = StationSeeds.pick(songs, plays, max = 5).map { it.id }
        // 3 (9 plays), then 2 and 5 (4 each, in the source's order), then 6; "1" would be a third of A.
        assertEquals(listOf("3", "2", "5", "6", "1").filterNot { it == "1" } + listOf("4"), seeds)
        assertEquals(StationSeeds.ALBUM_SEEDS, StationSeeds.pick(songs, plays, StationSeeds.ALBUM_SEEDS).size)
    }

    @Test
    fun `with no plays the first songs of the source seed it, and a short source gives what it has`() {
        val songs = listOf(song("1", "A"), song("2", "B"))
        assertEquals(listOf("1", "2"), StationSeeds.pick(songs, emptyMap(), max = 5).map { it.id })
        assertTrue(StationSeeds.pick(emptyList(), emptyMap(), 3).isEmpty())
    }

    @Test
    fun `an album or playlist station keeps its seeds through a restart, and an old saved station still reads`() {
        val station = Station.fromSource(Station.SOURCE_ALBUM, listOf("10", "11", "12"), "After Hours", 5L)
        assertEquals(Session.SEED_STATION_SONG, station.kind)
        assertEquals("10", station.seedMediaId)
        assertFalse(station.isArtist)
        val back = Station.decode(station.encode())!!
        assertEquals(station, back)
        assertEquals(listOf("10", "11", "12"), back.seedMediaIds)
        assertEquals(Station.SOURCE_ALBUM, back.source)
        // Another source from the same first seed is another station.
        assertNotEquals(station.id, Station.fromSource(Station.SOURCE_PLAYLIST, listOf("10"), "After Hours", 5L).id)
        // A song station's id is what it always was; a station saved before these existed (five fields) reads as a song station.
        val song = Station.song("m1", "Title", "Artist", 7L)
        assertEquals(Station.idOf(Session.SEED_STATION_SONG, "m1"), song.id)
        val old = listOf("2", "m1", "Title", "Artist", "7").joinToString("\u001f")
        val decoded = Station.decode(old)!!
        assertEquals(song.id, decoded.id)
        assertEquals(Station.SOURCE_SONG, decoded.source)
        assertEquals(listOf("m1"), decoded.seedMediaIds)
    }

    @Test
    fun `an artist station is an artist source`() {
        val station = Station.artist("m1", "Artist", 9L)
        assertTrue(station.isArtist)
        assertEquals(Station.SOURCE_ARTIST, station.source)
        assertEquals(station, Station.decode(station.encode()))
    }
}
