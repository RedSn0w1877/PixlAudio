package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The small pure parts of gathering the library's facts: dates in either unit, and which songs share an album. */
class CloudAutoFactsGathererTest {
    private fun song(albumId: Long = -1, album: String = "", artist: String = "Artist", albumArtist: String? = null) =
        Song.emptySong().copy(albumId = albumId, album = album, artist = artist, albumArtist = albumArtist)

    @Test fun `dates in seconds and in milliseconds both end up in milliseconds`() {
        assertEquals(0L, CloudAutoFactsGatherer.normalizeEpochMs(0))
        assertEquals(0L, CloudAutoFactsGatherer.normalizeEpochMs(-5))
        // MediaStore's DATE_ADDED is in seconds.
        assertEquals(1_700_000_000_000L, CloudAutoFactsGatherer.normalizeEpochMs(1_700_000_000))
        // Room's and Spotify's are already in milliseconds.
        assertEquals(1_700_000_000_000L, CloudAutoFactsGatherer.normalizeEpochMs(1_700_000_000_000))
    }

    @Test fun `songs with the same album id are on the same album`() {
        assertEquals(CloudAutoFactsGatherer.albumKey(song(albumId = 7)), CloudAutoFactsGatherer.albumKey(song(albumId = 7, album = "Other")))
        assertNotEquals(CloudAutoFactsGatherer.albumKey(song(albumId = 7)), CloudAutoFactsGatherer.albumKey(song(albumId = 8)))
    }

    @Test fun `without a real album id the album's name and artist say which songs belong together`() {
        val a = CloudAutoFactsGatherer.albumKey(song(albumId = 0, album = " Abbey Road ", artist = "The Beatles"))
        assertEquals(a, CloudAutoFactsGatherer.albumKey(song(albumId = -1, album = "abbey road", artist = "the beatles")))
        // The album artist, when there is one, is what the album belongs to (a compilation isn't one artist's).
        val compilation = CloudAutoFactsGatherer.albumKey(song(album = "Hits", artist = "One", albumArtist = "Various"))
        assertEquals(compilation, CloudAutoFactsGatherer.albumKey(song(album = "Hits", artist = "Two", albumArtist = "Various")))
        assertNotEquals(a, CloudAutoFactsGatherer.albumKey(song(album = "Abbey Road", artist = "Someone Else")))
        // No album at all, or the placeholder: no album to lift anything by.
        assertNull(CloudAutoFactsGatherer.albumKey(song(album = "")))
        assertNull(CloudAutoFactsGatherer.albumKey(song(album = "Unknown Album")))
    }
}
