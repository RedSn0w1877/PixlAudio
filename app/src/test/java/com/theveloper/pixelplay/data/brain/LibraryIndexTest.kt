package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.store.RadioRowCodec
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioChip
import com.theveloper.pixelplay.data.youtube.RadioRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryIndexTest {

    private fun song(id: String, title: String, artist: String, durationMs: Long = 200_000L, artists: List<String> = emptyList()) = Song(
        id = id, title = title, artist = artist, artistId = 1L,
        artists = artists.mapIndexed { i, name -> ArtistRef(id = i.toLong(), name = name, isPrimary = i == 0) },
        album = "Album", albumId = 1L, path = "", contentUriString = "spotify://x", albumArtUriString = null,
        duration = durationMs, mimeType = null, bitrate = null, sampleRate = null,
    )

    private fun row(title: String, artist: String, seconds: Int = 200) = RadioRow(
        videoId = "v", title = title, artists = listOf(RadioArtistRef(artist, "UCx")), durationSec = seconds, kind = MusicVideoKind.ATV,
    )

    @Test
    fun `a radio row matches the library recording across spelling, case and a remaster tag`() {
        val dreams = song("1", "Dreams (2004 Remaster)", "Fleetwood Mac", 257_000)
        val index = LibraryIndex(listOf(dreams, song("2", "Chạy Ngay Đi", "Sơn Tùng M-TP", 248_000)))
        assertSame(dreams, index.match(row("Dreams", "FLEETWOOD MAC", 258)))
        assertEquals("2", index.match(row("CHẠY NGAY ĐI", "Son Tung M-TP", 249))?.id)
        assertNull(index.match(row("Dreams", "Someone Else", 258)))
        assertNull(index.match(row("Rhiannon", "Fleetwood Mac", 258)))
    }

    @Test
    fun `a different length under the same name is another recording, an unknown length is accepted`() {
        val index = LibraryIndex(listOf(song("1", "Hello", "Adele", 295_000)))
        assertNull(index.match(row("Hello", "Adele", 180)))
        assertEquals("1", index.match(row("Hello", "Adele", 297))?.id)
        assertEquals("1", index.match(row("Hello", "Adele", 0))?.id)
        // A live version is not the studio recording.
        assertNull(index.match(row("Hello (Live)", "Adele", 295)))
    }

    @Test
    fun `the primary artist is the first of the split list - a joined display string is never split`() {
        val joined = song("1", "EARFQUAKE", "Tyler, The Creator, Playboi Carti", artists = listOf("Tyler, The Creator", "Playboi Carti"))
        assertEquals("Tyler, The Creator", LibraryIndex.primaryArtistName(joined))
        assertEquals("tyler the creator", LibraryIndex.artistKeyOf(joined))
        val index = LibraryIndex(listOf(joined))
        assertEquals("1", index.match(row("EARFQUAKE", "Tyler, The Creator"))?.id)
        assertEquals(listOf(joined), index.byPrimaryArtist("tyler the creator"))
        assertTrue(index.byPrimaryArtist("playboi carti").isEmpty())
        // No split list: the legacy field is the fallback, as one name.
        assertEquals("Adele", LibraryIndex.primaryArtistName(song("2", "Hello", "Adele")))
    }

    @Test
    fun `radio rows survive the blob codec`() {
        val rows = listOf(
            StoredRadioRow(row("Chúng Ta Của Hiện Tại", "Sơn Tùng M-TP").copy(album = "Album", year = 2020, explicit = true), rid = -42L),
            StoredRadioRow(row("Second", "B"), rid = 7L),
        )
        assertEquals(rows, RadioRowCodec.decodeRows(RadioRowCodec.encodeRows(rows)))
        assertTrue(RadioRowCodec.decodeRows(ByteArray(0)).isEmpty())
        assertTrue(RadioRowCodec.decodeRows(byteArrayOf(1, 2, 3)).isEmpty(), "a damaged blob is an empty list, not a crash")
        val chips = listOf(RadioChip("V-pop", "RDATxyz", "abc"), RadioChip("Popular", "RDATq"))
        assertEquals(chips, RadioRowCodec.decodeChips(RadioRowCodec.encodeChips(chips)))
    }

    @Test
    fun `the host budget allows a burst, refills slowly and survives a clock that goes back`() {
        var now = 0L
        val budget = HostBudget(capacity = 3, refillIntervalMs = 1_000L, clock = { now })
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        now = 999
        assertFalse(budget.tryAcquire())
        now = 1_000
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        now = 100_000
        assertEquals(3, budget.available(), "never more than the capacity")
        now = 50_000
        assertEquals(3, budget.available())
        assertTrue(budget.tryAcquire())
        assertEquals(2, budget.available())
    }

    @Test
    fun `the default budget is sixty a day`() {
        assertEquals(60, HostBudget.DAILY_LIMIT)
        assertEquals(2, HostBudget.MAX_PARALLEL)
        var now = 0L
        val budget = HostBudget(clock = { now })
        var granted = 0
        while (budget.tryAcquire()) granted++
        assertEquals(HostBudget.BURST, granted)
        now = HostBudget.DAY_MS
        var later = 0
        while (budget.tryAcquire()) later++
        assertEquals(HostBudget.BURST, later)
    }
}
