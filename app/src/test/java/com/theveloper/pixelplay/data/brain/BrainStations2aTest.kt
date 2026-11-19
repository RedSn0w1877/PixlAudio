package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.ChipLabels
import com.theveloper.pixelplay.data.brain.source.FetchedRadio
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioChip
import com.theveloper.pixelplay.data.youtube.RadioEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The radio cache's stage 2a parts (docs/brain-spec.md, 3.3, 6.2, 8.3): an artist station for an artist the
 * library lacks, similar artists' top songs, and chips mapped to tags.
 */
class BrainStations2aTest {

    private val dao = FakeRadioListDao()
    private val source = FakeRadioSource()
    private val resolver = MemoryTrackResolver()
    private val now = 1_760_000_000_000L
    private val repository = BrainRepository(dao, resolver, source, { now })

    private fun seedOf(artist: String, title: String, videoId: String) = RadioSeed("m", videoId, title, artist, 200_000L)

    /** 45 rows: the artist's own every third row, the others by neighbours, so the junk detector is satisfied. */
    private fun artistRadio(artist: String, first: Int = 1): FetchedRadio = FetchedRadio(
        (first until first + 45).map { i -> mixRow(i, if (i % 3 == 0) artist else "Neighbour $i", title = "Song $i") },
    )

    // ── An artist station for an artist the library lacks ────────────────────

    @Test
    fun `the artist page's radio button gives the seed, and the list is stored under it`() = runTest {
        source.channels["Nova Star"] = "UCNova Star"
        val endpoint = RadioEndpoint("vid0000003", "RDEMnova", "wAEB")
        source.artistPages["UCNova Star"] = ArtistPage(
            "Nova Star", endpoint,
            topSongs = (101..105).map { mixRow(it, "Nova Star", title = "Top $it") },
            albums = emptyList(), singles = emptyList(), fansAlsoLike = emptyList(),
        )
        source.artistRadios["RDEMnova"] = artistRadio("Nova Star")

        val start = repository.beginArtistStation("Nova Star")

        assertNotNull(start)
        assertEquals("vid0000003", start!!.seedRow.videoId, "the radio button's own track")
        assertEquals("UCNova Star", start.channelId)
        assertTrue(start.seedRid != 0L)
        // Three requests before sound: the search, the page, the radio.
        assertEquals(listOf("channel:Nova Star", "artist:UCNova Star", "artistRadio:RDEMnova"), source.calls)
        // The artist list is in the cache under the seed, so the station's first pool costs no request.
        val stored = dao.rows[Triple(start.seedRid, RadioListEntity.KIND_ARTIST, BrainRepository.ARTIST_STATION_CHIP)]
        assertNotNull(stored)
        assertTrue(stored!!.healthy)
        val cached = repository.cachedArtistRadio(start.seedRid)
        assertTrue(cached.size >= 40, "top songs and the radio: ${cached.size}")
        assertTrue(cached.none { it.row.videoId == "vid0000003" }, "the seed is not its own pick")
    }

    @Test
    fun `an artist nobody can find, or a page with no radio button, starts nothing`() = runTest {
        assertNull(repository.beginArtistStation("Nobody"))
        assertNull(repository.beginArtistStation("  "))
        source.channels["Quiet"] = "UCQuiet"
        source.artistPages["UCQuiet"] = ArtistPage("Quiet", null, emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(repository.beginArtistStation("Quiet"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `with the budget spent nothing is asked`() = runTest {
        val spentRepo = BrainRepository(dao, resolver, source, { now })
        for (i in 0 until HostBudget.DAILY_LIMIT) {
            dao.upsert(RadioListEntity(i.toLong(), RadioListEntity.KIND_SONG, "", now, now, true, ByteArray(0), ByteArray(0)))
        }
        source.channels["Nova Star"] = "UCNova Star"
        assertNull(spentRepo.beginArtistStation("Nova Star"))
        assertTrue(source.calls.isEmpty())
    }

    // ── Similar artists ──────────────────────────────────────────────────────

    private fun withPages() {
        val seedArtist = "Nova Star"
        source.artistPages["UC$seedArtist"] = ArtistPage(
            seedArtist, RadioEndpoint("vid0000003", "RDEMnova", "wAEB"), emptyList(), emptyList(), emptyList(),
            fansAlsoLike = listOf("Echo", "Delta", "Orbit", "Fourth").map { RadioArtistRef(it, "UC$it") },
        )
        var n = 200
        for (name in listOf("Echo", "Delta", "Orbit", "Fourth")) {
            source.artistPages["UC$name"] = ArtistPage(
                name, null, (0 until 5).map { mixRow(n++, name, title = "$name top $it") }, emptyList(), emptyList(), emptyList(),
            )
        }
        // The seed's own song radio names the artist's channel, as a real one does.
        source.songRadios["vid0000003"] = FetchedRadio(
            (3 until 60).map { i -> mixRow(i, if (i % 2 == 1) seedArtist else "Neighbour $i", title = "Radio $i") },
        )
    }

    @Test
    fun `similar artists' top songs come from the first three artists' pages and are cached for two weeks`() = runTest {
        withPages()
        val seed = seedOf("Nova Star", "Seed Song", "vid0000003")

        val rows = repository.similarArtistSongs(seed, allowNetwork = true)

        assertEquals(15, rows.size, "five songs from each of three artists")
        assertTrue(rows.none { it.row.artists.first().name == "Fourth" }, "only the first three are read")
        val calls = source.calls.size
        // The second ask is answered from the cache.
        assertEquals(15, repository.similarArtistSongs(seed, allowNetwork = true).size)
        assertEquals(calls, source.calls.size)
        // Offline the cache still answers.
        assertEquals(15, repository.similarArtistSongs(seed, allowNetwork = false).size)
        // They are not edges of the graph (a list of top songs is not a ranking by closeness).
        assertNotNull(dao.rows.keys.firstOrNull { it.third == BrainRepository.SIMILAR_STATION_CHIP })
    }

    @Test
    fun `no similar artists known is an empty answer that is not asked again for a day`() = runTest {
        source.songRadios["vid0000003"] = FetchedRadio((3 until 60).map { i -> mixRow(i, if (i % 2 == 1) "Nova Star" else "Neighbour $i") })
        source.artistPages["UCNova Star"] = ArtistPage("Nova Star", null, emptyList(), emptyList(), emptyList(), emptyList())
        val seed = seedOf("Nova Star", "Seed Song", "vid0000003")

        assertTrue(repository.similarArtistSongs(seed, allowNetwork = true).isEmpty())
        val calls = source.calls.size
        assertTrue(repository.similarArtistSongs(seed, allowNetwork = true).isEmpty())
        assertEquals(calls, source.calls.size)
    }

    // ── Chips mapped to tags ─────────────────────────────────────────────────

    @Test
    fun `a radio's chips are mapped to genres, decades and moods, and steering chips are left out`() = runTest {
        val rows = (1..60).map { i -> mixRow(i, if (i % 2 == 1) "Nova Star" else "Neighbour $i") }
        source.songRadios["vid0000001"] = FetchedRadio(
            rows,
            chips = listOf("Popular", "Deep cuts", "Pop", "V-pop", "2010s", "Workout", "Something odd", "pop")
                .map { RadioChip(it, "RDAMVMvid0000001", "p-$it") },
        )
        val seed = seedOf("Nova Star", "Seed Song", "vid0000001")
        val radio = repository.radioFor(seed, allowNetwork = true)!!

        val tags = repository.chipTagsOf(radio.seedRid)

        assertEquals(
            listOf(
                ChipLabels.Kind.GENRE to ChipLabels.classify("Pop").key,
                ChipLabels.Kind.GENRE to ChipLabels.classify("V-pop").key,
                ChipLabels.Kind.DECADE to "2010s",
                ChipLabels.Kind.MOOD to ChipLabels.classify("Workout").key,
            ),
            tags.map { it.kind to it.key },
            "steering chips, an unknown label and the repeated Pop are left out",
        )
        // A seed with no stored radio has no tags.
        assertTrue(repository.chipTagsOf(12345L).isEmpty())
    }
}
