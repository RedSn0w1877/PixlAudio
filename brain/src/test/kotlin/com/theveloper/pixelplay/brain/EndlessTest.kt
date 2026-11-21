package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndlessTest {

    private fun t(rid: Long, aid: Long, w: Double) = EndlessSeeds.Track(rid, aid, w)

    @Test
    fun `one seed per island, biggest island first, the most played track of each`() {
        val islands = listOf(
            EndlessSeeds.IslandIn(1, 2.0, listOf(t(10, 1, 3.0), t(11, 2, 9.0))),
            EndlessSeeds.IslandIn(2, 5.0, listOf(t(20, 3, 1.0))),
            EndlessSeeds.IslandIn(3, 1.0, listOf(t(30, 4, 0.0))),
        )
        val seeds = EndlessSeeds.pick(islands, emptyList())
        assertEquals(listOf(20L, 11L, 30L), seeds.map { it.rid })
        assertEquals(listOf(2, 1, 3), seeds.map { it.islandId })
    }

    @Test
    fun `at most five seeds and no artist twice`() {
        val islands = (1..8).map { EndlessSeeds.IslandIn(it, (10 - it).toDouble(), listOf(t(it * 10L, if (it <= 2) 7L else it.toLong(), 1.0), t(it * 10L + 1, 50L + it, 0.5))) }
        val seeds = EndlessSeeds.pick(islands, emptyList())
        assertEquals(5, seeds.size)
        assertEquals(seeds.size, seeds.map { it.aid }.filter { it != 0L }.toSet().size)
        // The second island's best track is by the artist the first island already seeded with: its next track stands in.
        assertEquals(listOf(10L, 21L), seeds.take(2).map { it.rid })
    }

    @Test
    fun `an island with no usable track is skipped, ties go to the lower recording id`() {
        val islands = listOf(
            EndlessSeeds.IslandIn(1, 3.0, emptyList()),
            EndlessSeeds.IslandIn(2, 2.0, listOf(t(0, 1, 9.0), t(25, 2, 4.0), t(24, 3, 4.0))),
            EndlessSeeds.IslandIn(3, 1.0, listOf(t(30, 4, 1.0))),
        )
        assertEquals(listOf(24L, 30L), EndlessSeeds.pick(islands, emptyList()).map { it.rid })
    }

    @Test
    fun `fewer than two islands fall back to the top artists`() {
        val one = listOf(EndlessSeeds.IslandIn(1, 5.0, listOf(t(10, 1, 3.0))))
        val artists = listOf(
            EndlessSeeds.ArtistIn(70, 1.0, listOf(t(701, 70, 1.0))),
            EndlessSeeds.ArtistIn(71, 9.0, listOf(t(711, 71, 2.0), t(712, 71, 5.0))),
            EndlessSeeds.ArtistIn(72, 4.0, emptyList()),
        )
        val seeds = EndlessSeeds.pick(one, artists)
        assertEquals(listOf(712L, 701L), seeds.map { it.rid })
        assertTrue(seeds.all { it.islandId == -1 })
        assertTrue(EndlessSeeds.pick(emptyList(), emptyList()).isEmpty())
        // Two islands that cannot give two seeds (one artist) fall back as well.
        val same = listOf(
            EndlessSeeds.IslandIn(1, 2.0, listOf(t(10, 1, 1.0))),
            EndlessSeeds.IslandIn(2, 1.0, listOf(t(20, 1, 1.0))),
        )
        assertEquals(listOf(30L), EndlessSeeds.pick(same, listOf(EndlessSeeds.ArtistIn(2, 1.0, listOf(t(30, 2, 1.0))))).map { it.rid })
    }

    private fun slot(
        artist: String = "A", genre: String = "", fact: String = "", year: Int = 0, island: String = "", fresh: Boolean = false, side: Boolean = false,
    ) = HostNotes.Slot(artist, genre, fact, year, island, fresh, side)

    @Test
    fun `a slot with nothing real to say has no note`() {
        assertTrue(HostNotes.notes(List(12) { slot() }).isEmpty())
        // A year outside 1900 to 2100 is not a year; a fact that is too long is dropped, not cut.
        assertTrue(HostNotes.notes(listOf(slot(year = 1850), slot(year = 2500), slot(genre = "Pop", fact = "a b c d e f g h i j k l m n o p"))).isEmpty())
    }

    @Test
    fun `each kind fills its template from its own data`() {
        val notes = HostNotes.notes(
            listOf(
                slot(artist = "Mono", fresh = true),
                slot(), slot(), slot(), slot(),
                slot(genre = "Shoegaze"), // the genre before is "": no step sideways
                slot(), slot(), slot(), slot(),
                slot(island = "Bedroom pop"),
                slot(), slot(), slot(), slot(),
                slot(genre = "Dream pop", fact = "Hazy pop made of echo."),
                slot(), slot(), slot(), slot(),
                slot(year = 1999),
            )
        )
        assertEquals(
            listOf(
                "New from Mono, an artist you play.",
                "From your Bedroom pop side.",
                "Dream pop: Hazy pop made of echo.",
                "Released in 1999.",
            ),
            notes.map { HostNotes.text(it) },
        )
        assertEquals(listOf(0, 10, 15, 20), notes.map { it.index })
    }

    @Test
    fun `a step sideways names the genre it comes from and needs one`() {
        val slots = listOf(slot(genre = "Hip hop"), slot(genre = "Hip hop"), slot(genre = "Bedroom pop", side = true))
        assertTrue(HostNotes.notes(slots.take(2) + slot(genre = "Hip hop", side = true)).isEmpty())
        val note = HostNotes.notes(listOf(slots[0], slots[1], slot(genre = "Bedroom pop", side = true, island = "x"))).single()
        assertEquals("A step sideways, from Hip hop to Bedroom pop.", HostNotes.text(note))
        assertEquals(2, note.index)
    }

    @Test
    fun `at most one note per five tracks and none inside a set note`() {
        val all = List(40) { slot(year = 2000 + it % 20, island = "Pop") }
        val notes = HostNotes.notes(all)
        for (i in 1 until notes.size) assertTrue(notes[i].index - notes[i - 1].index >= 5)
        val taken = listOf(HostNotes.Taken(2, 4), HostNotes.Taken(14, 6))
        val withSet = HostNotes.notes(all, taken)
        for (n in withSet) {
            for (s in taken) {
                val inside = n.index >= s.index && n.index < s.index + s.span
                assertFalse(inside, "note ${n.index} inside the set note at ${s.index}")
                assertTrue(n.index >= s.index + maxOf(s.span, 5) || n.index <= s.index - 5, "note ${n.index} too near ${s.index}")
            }
        }
        for (i in 1 until withSet.size) assertTrue(withSet[i].index - withSet[i - 1].index >= 5)
    }

    @Test
    fun `a text with a word the app never says, or a long sentence, is not spoken`() {
        assertTrue(HostNotes.isSpeakable("From your Bedroom pop side."))
        assertFalse(HostNotes.isSpeakable("Dance music with a lossless feel."))
        assertFalse(HostNotes.isSpeakable("Learn the genre."))
        assertFalse(HostNotes.isSpeakable("One. Two. Three."))
        assertFalse(HostNotes.isSpeakable("A very very very very very very very very very very long sentence here."))
        assertFalse(HostNotes.isSpeakable(""))
        // A genre fact that holds a forbidden word never becomes a note.
        assertTrue(HostNotes.notes(listOf(slot(genre = "House", fact = "Dance music with a lossless feel."))).isEmpty())
    }

    @Test
    fun `every kind's text is short enough to be spoken`() {
        val notes = listOf(
            HostNotes.Note(0, HostNotes.KIND_NEW_FROM_ARTIST, "HIEUTHUHAI", ""),
            HostNotes.Note(0, HostNotes.KIND_SIDEWAYS, "Hip hop", "Bedroom pop"),
            HostNotes.Note(0, HostNotes.KIND_ISLAND, "Soft pop & R&B", ""),
            HostNotes.Note(0, HostNotes.KIND_GENRE_FACT, "Dream pop", "Hazy pop made of echo and reverb"),
            HostNotes.Note(0, HostNotes.KIND_YEAR, "2019", ""),
        )
        for (n in notes) assertTrue(HostNotes.isSpeakable(HostNotes.text(n)), HostNotes.text(n))
    }

    @Test
    fun `fact reads the first sentence only`() {
        assertEquals("Hushed pop", HostNotes.factOf("Hushed pop. It began in 1985."))
        assertEquals("", HostNotes.factOf("  "))
        assertEquals("No full stop", HostNotes.factOf("No full stop"))
    }
}
