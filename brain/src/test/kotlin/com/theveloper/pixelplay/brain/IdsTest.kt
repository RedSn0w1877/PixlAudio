package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {

    private val dreams = Ids.nameKey("Fleetwood Mac", "Dreams (2004 Remaster)")

    @Test
    fun `nameKey folds the artist, cleans the title and ignores the remaster flag`() {
        assertEquals("n:fleetwood mac|dreams|0", dreams)
        assertEquals(dreams, Ids.nameKey("FLEETWOOD MAC", "Dreams"))
        assertEquals("n:eagles|hotel california|1", Ids.nameKey("Eagles", "Hotel California (Live)"))
        assertEquals("n:son tung m tp|chay ngay di|0", Ids.nameKey("Sơn Tùng M-TP", "Chạy Ngay Đi"))
        assertNull(Ids.nameKey("", "Dreams"))
        assertNull(Ids.nameKey("Artist", "!!!"))
    }

    @Test
    fun `mint is fnv of the first key, and steps past zero and taken ids`() {
        assertEquals(BrainHash.fnv1a64("y:abc"), Ids.mint("y:abc") { false })
        val first = BrainHash.fnv1a64("y:abc")
        assertEquals(BrainHash.fnv1a64("y:abc#1"), Ids.mint("y:abc") { it == first })
        val second = BrainHash.fnv1a64("y:abc#1")
        assertEquals(BrainHash.fnv1a64("y:abc#2"), Ids.mint("y:abc") { it == first || it == second })
    }

    @Test
    fun `step 1 - a known strong key decides, in the order s then i then y`() {
        val table = Ids.MemoryAliasTable()
        val a = table.resolveTrack(Ids.TrackKeys(spotifyId = "SP1"), 200_000)!!
        val b = table.resolveTrack(Ids.TrackKeys(videoId = "VID1"), 200_000)!!
        assertNotEquals(a, b)
        // Both known: the Spotify key wins and the two recordings become one.
        val both = Ids.resolveTrack(Ids.TrackKeys(spotifyId = "SP1", videoId = "VID1"), 200_000, table)!!
        assertEquals(a, both.id)
        assertEquals(1, both.merges.size)
        assertEquals(b, both.merges[0].from)
        assertEquals(a, both.merges[0].into)
        table.apply(both)
        assertEquals(a, table.resolveTrack(Ids.TrackKeys(videoId = "VID1"), 0))
    }

    @Test
    fun `step 2 - the name key bridges a library track and a radio row of the same length`() {
        val table = Ids.MemoryAliasTable()
        val library = table.resolveTrack(Ids.TrackKeys(spotifyId = "SP1", nameKey = dreams), 257_000)!!
        val radio = table.resolveTrack(Ids.TrackKeys(videoId = "VID1", nameKey = dreams), 258_500)!!
        assertEquals(library, radio)
        // The radio's video id is now an alias of the library recording.
        assertEquals(library, table.resolveTrack(Ids.TrackKeys(videoId = "VID1"), 0))
    }

    @Test
    fun `step 2 - a different length under the same name is another recording, and keeps the name key`() {
        val table = Ids.MemoryAliasTable()
        val studio = table.resolveTrack(Ids.TrackKeys(spotifyId = "SP1", nameKey = dreams), 257_000)!!
        val edit = Ids.resolveTrack(Ids.TrackKeys(videoId = "VID2", nameKey = dreams), 201_000, table)!!
        assertNotEquals(studio, edit.id)
        assertTrue(edit.minted)
        assertEquals(listOf("y:VID2"), edit.aliases.map { it.key })
        table.apply(edit)
        // The name still points at the first recording.
        assertEquals(studio, table.resolveTrack(Ids.TrackKeys(nameKey = dreams), 0))
    }

    @Test
    fun `step 2 - a names-only import or an unknown length always accepts the name`() {
        val table = Ids.MemoryAliasTable()
        val library = table.resolveTrack(Ids.TrackKeys(spotifyId = "SP1", nameKey = dreams), 257_000)!!
        assertEquals(library, table.resolveTrack(Ids.TrackKeys(nameKey = dreams), 100_000))
        assertEquals(library, table.resolveTrack(Ids.TrackKeys(videoId = "VID9", nameKey = dreams), 0))
    }

    @Test
    fun `step 3 - mints from the strongest key the caller has`() {
        val table = Ids.MemoryAliasTable()
        assertEquals(
            BrainHash.fnv1a64("i:USABC1234567"),
            table.resolveTrack(Ids.TrackKeys(isrc = "usabc1234567", videoId = "V", nameKey = dreams), 1)
        )
        val other = Ids.MemoryAliasTable()
        assertEquals(BrainHash.fnv1a64(dreams!!), other.resolveTrack(Ids.TrackKeys(nameKey = dreams), 0))
        assertNull(other.resolveTrack(Ids.TrackKeys(), 0))
    }

    @Test
    fun `step 4 - every new key is stored once and a second resolve writes nothing`() {
        val table = Ids.MemoryAliasTable()
        val keys = Ids.TrackKeys(spotifyId = "SP1", isrc = "USABC1234567", videoId = "V1", nameKey = dreams)
        val first = Ids.resolveTrack(keys, 257_000, table)!!
        assertEquals(listOf("s:SP1", "i:USABC1234567", "y:V1", dreams), first.aliases.map { it.key })
        table.apply(first)
        val second = Ids.resolveTrack(keys, 257_000, table)!!
        assertEquals(first.id, second.id)
        assertTrue(second.aliases.isEmpty())
        assertTrue(second.merges.isEmpty())
        assertEquals(4, table.aliasCount)
    }

    @Test
    fun `merge chains are flattened`() {
        val table = Ids.MemoryAliasTable()
        val a = table.resolveTrack(Ids.TrackKeys(spotifyId = "A"), 0)!!
        val b = table.resolveTrack(Ids.TrackKeys(isrc = "B"), 0)!!
        val c = table.resolveTrack(Ids.TrackKeys(videoId = "C"), 0)!!
        table.resolveTrack(Ids.TrackKeys(isrc = "B", videoId = "C"), 0) // c into b
        assertEquals(b, table.survivorOf(c))
        table.resolveTrack(Ids.TrackKeys(spotifyId = "A", isrc = "B"), 0) // b into a
        assertEquals(a, table.survivorOf(b))
        assertEquals(a, table.survivorOf(c))
        assertEquals(a, table.resolveTrack(Ids.TrackKeys(videoId = "C"), 0))
    }

    @Test
    fun `artists join by folded name and a name hit is always accepted`() {
        val table = Ids.MemoryAliasTable()
        val library = table.resolveArtist(Ids.ArtistKeys(name = "Sơn Tùng M-TP"))!!
        val radio = table.resolveArtist(Ids.ArtistKeys(youTubeChannelId = "UCabc", name = "SON TUNG M-TP"))!!
        assertEquals(library, radio)
        assertEquals(library, table.resolveArtist(Ids.ArtistKeys(youTubeChannelId = "UCabc")))
        assertEquals(BrainHash.fnv1a64("na:son tung m tp"), library)
        assertNull(table.resolveArtist(Ids.ArtistKeys(name = "  ")))
    }
}
