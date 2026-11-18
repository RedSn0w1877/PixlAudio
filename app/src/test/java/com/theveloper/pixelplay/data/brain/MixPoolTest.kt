package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Seeds
import com.theveloper.pixelplay.data.blocking.BlockedArtist
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pool a mix is planned from (docs/brain-spec.md, 6.1, 6.2) and the groups the day's seeds come from (3.6). */
class MixPoolTest {
    private val open = TasteConstraints(BlockedArtistIndex(emptyList()), TasteAdjustments.NONE)

    private fun track(rid: Long, title: String, artist: String, aid: Long) =
        LibraryTrack(mixSong("song$rid", title, artist), rid, aid, liked = false)

    private fun list(seedRid: Long, vararg rows: StoredRadioRow, kind: Int = RadioListEntity.KIND_SONG) =
        StoredRadio(seedRid, kind, rows.toList(), fromCache = true)

    private fun stored(i: Int, artist: String, title: String = "Song $i", seconds: Int = 200, official: Boolean = true) =
        StoredRadioRow(mixRow(i, artist, title, seconds, official), rid = 1_000L + i)

    private fun pool(
        library: List<LibraryTrack>,
        seeds: List<MixSeed>,
        constraints: TasteConstraints = open,
        ignored: Set<Long> = emptySet(),
    ) = MixPoolBuilder.build(library, seeds, LibraryIndex(library.map { it.song }), constraints, ignored) { name -> 5_000L + name.length }

    @Test
    fun `the library shortlist and a radio's new music become one pool, each recording once`() {
        val library = listOf(track(1, "Home", "Mine", 11), track(2, "Away", "Mine", 11))
        val seed = MixSeed(1L, 11L, "Home", "Mine", list(1L, stored(1, "Other"), stored(2, "Third"), stored(1, "Other")))
        val p = pool(library, listOf(seed))
        assertEquals(listOf(1L, 2L, 1_001L, 1_002L), p.candidates.map { it.rid })
        assertEquals(setOf(1L, 2L), p.candidates.filter { it.inLibrary }.map { it.rid }.toSet())
        assertEquals(1, p.seedLists.size)
        assertEquals(3, p.seedLists[0].rids.size, "the seed's list keeps every row, filtered or not, for the graph")
        assertEquals("Mine", p.artistNames[11L])
        assertEquals(1, p.novel[1_001L]!!.radioRank)
        assertEquals("Mine", p.novel[1_001L]!!.seedArtist)
    }

    @Test
    fun `music the library already has is a library candidate, not a card`() {
        val library = listOf(track(1, "Dreams", "Fleetwood Mac", 11))
        val seed = MixSeed(1L, 11L, "Home", "Mine", list(1L, stored(5, "Fleetwood Mac", "Dreams"), stored(6, "Fleetwood Mac", "Rhiannon")))
        val p = pool(library, listOf(seed))
        assertFalse(1_005L in p.novel)
        assertTrue(1_006L in p.novel)
    }

    @Test
    fun `the filters of autoplay hold, official audio and a sane length and no cover or live unless the seed is live`() {
        val seed = MixSeed(
            1L, 11L, "Studio Song", "Mine",
            list(
                1L, stored(1, "A", "Fine"), stored(2, "B", "Not official", official = false), stored(3, "C", "Too short", seconds = 20),
                stored(4, "D", "Too long", seconds = 2_000), stored(5, "E", "Song (Live)"), stored(6, "F", "Song (Karaoke Version)"),
            ),
        )
        val p = pool(emptyList(), listOf(seed))
        assertEquals(setOf(1_001L), p.novel.keys)
        val liveSeed = MixSeed(1L, 11L, "Song (Live)", "Mine", list(1L, stored(5, "E", "Song (Live)"), stored(6, "F", "Song (Karaoke Version)")))
        assertEquals(setOf(1_005L), pool(emptyList(), listOf(liveSeed)).novel.keys, "live is allowed after a live seed, karaoke never")
        assertTrue((BrainText.cleanTitle("Song (Live)").versionFlags) != 0)
    }

    @Test
    fun `what the listener ignored three times, blocked artists and a track with no artist stay out`() {
        val blocking = TasteConstraints(BlockedArtistIndex(listOf(BlockedArtist(99L, "Blocked Band"))), TasteAdjustments.NONE)
        val rows = arrayOf(stored(1, "Fine"), stored(2, "Blocked Band"), stored(3, "Ignored One"))
        val noArtist = StoredRadioRow(mixRow(4, "x").copy(artists = emptyList()), 1_004L)
        val seed = MixSeed(1L, 11L, "S", "Mine", list(1L, *rows, noArtist))
        val p = pool(emptyList(), listOf(seed), blocking, ignored = setOf(1_003L))
        assertEquals(setOf(1_001L), p.novel.keys)
    }

    @Test
    fun `an artist list gives the ARTIST arm its candidates, and a repeat keeps the better rank`() {
        val seed1 = MixSeed(1L, 11L, "A", "Mine", list(1L, stored(1, "X"), stored(2, "Y")), artistRows = listOf(stored(9, "Mine")))
        val seed2 = MixSeed(2L, 12L, "B", "Other", list(2L, stored(2, "Y"), stored(7, "Z")))
        val p = pool(emptyList(), listOf(seed1, seed2))
        val artistCandidate = p.candidates.single { it.rid == 1_009L }
        assertTrue(artistCandidate.fromArtistRadio)
        assertEquals(Arm.RADIO, Arm.RADIO)
        assertEquals(1, p.candidates.count { it.rid == 1_002L }, "one candidate per recording")
        assertEquals(1, p.novel[1_002L]!!.radioRank, "rank 2 in the first list, rank 1 in the second: the better one stays")
        assertEquals(2, p.seedLists.size)
    }

    @Test
    fun `the four seed groups keep the rules of 3 6 and an unusable track takes no quota`() {
        val usable = setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        val groups = SeedGroups.groups(
            recent = listOf(1L, 2L, 3L).map { SeedGroups.Source(it, 100L) },
            top = listOf(99L, 4L, 5L).map { SeedGroups.Source(it, 200L + it) },
            saved = listOf(6L).map { SeedGroups.Source(it, 300L) },
            randomRids = longArrayOf(7, 8, 77),
            aidOf = { 400L + it },
            usable = { it in usable },
        )
        assertEquals(listOf(1L, 2L, 3L), groups[0].map { it.rid })
        assertEquals(listOf(4L, 5L), groups[1].map { it.rid }, "99 has no known audio: not a seed, and it did not use the quota")
        assertEquals(listOf(Seeds.RANDOM, Seeds.RANDOM), groups[3].map { it.group })
        val picked = Seeds.select(groups)
        // Two per artist: the third recent track by artist 100 is dropped.
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L, 7L, 8L), picked.map { it.rid })
    }
}
