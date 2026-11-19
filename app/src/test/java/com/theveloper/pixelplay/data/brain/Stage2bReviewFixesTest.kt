package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Embeddings
import com.theveloper.pixelplay.brain.FastRp
import com.theveloper.pixelplay.brain.Islands
import com.theveloper.pixelplay.brain.Repetition
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.source.MbArtist
import com.theveloper.pixelplay.data.brain.source.MbGenreVote
import com.theveloper.pixelplay.data.brain.source.MbRecordingHit
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.DnaSource
import com.theveloper.pixelplay.data.brain.store.EmbeddingDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity
import com.theveloper.pixelplay.data.brain.store.IslandDao
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The fixes of the independent review of stage 2b: whole-fit transactions, the Spotify switch, caps, names, numbers. */
class Stage2bReviewFixesTest {
    private val atlas = KnowledgeFixtures.atlas
    private val now = 1_795_000_000_000L

    // ---- the fit is written as one transaction ----------------------------------------------------------------------

    private class Tx : BrainTransactor(mockk<MusicBrainDatabase>(relaxed = true)) {
        var inside = false
        var runs = 0
        override suspend fun <T> run(block: suspend () -> T): T {
            runs++
            inside = true
            try { return block() } finally { inside = false }
        }
    }

    private fun island(id: Int) = Islands.Island(
        id = id, centroid = DoubleArray(Islands.DIM) { if (it == 0) 1.0 else 0.0 }, medoidRid = 10L + id,
        topTags = IntArray(0), liftTags = IntArray(0), topArtists = LongArray(0), weight = 5.0, recentWeight = 1.0, size = 3,
    )

    private fun table() = Embeddings.of(FastRp.VERSION, longArrayOf(1L, 2L), ByteArray(2 * Embeddings.DIM))

    @Test
    fun `embeddings, islands, members, the last id and the tag shares are all written inside one transaction`() = runTest {
        val tx = Tx()
        val emb = mockk<EmbeddingDao>()
        val isl = mockk<IslandDao>()
        val states = mockk<BrainStateDao>()
        val outside = ArrayList<String>()
        fun note(what: String) { if (!tx.inside) outside.add(what) }
        coEvery { emb.clear() } coAnswers { note("emb.clear") }
        coEvery { emb.upsertAll(any()) } coAnswers { note("emb.upsert") }
        coEvery { isl.clearIslands() } coAnswers { note("clearIslands") }
        coEvery { isl.clearMembers() } coAnswers { note("clearMembers") }
        coEvery { isl.upsertIslands(any()) } coAnswers { note("upsertIslands") }
        coEvery { isl.upsertMembers(any()) } coAnswers { note("upsertMembers") }
        coEvery { states.put(any()) } coAnswers { note("state:" + (firstArg<BrainStateEntity>()).key) }
        val store = IslandStore(emb, isl, states, tx)
        val fit = Islands.Fit(listOf(island(1), island(2)), longArrayOf(1L, 2L), intArrayOf(1, 2), 2, intArrayOf(7), doubleArrayOf(0.5))
        store.saveFit(table(), fit, now)
        assertEquals(1, tx.runs)
        assertTrue(outside.isEmpty(), "written outside the transaction: $outside")
        coVerify { isl.upsertIslands(any()) }
        coVerify { states.put(match { it.key == IslandStore.KEY_LAST_ID }) }
        assertEquals(2, store.current()!!.islands.size, "the cache follows the saved fit")
    }

    @Test
    fun `a write that fails half way throws and the cached islands stay the previous ones`() = runTest {
        val tx = Tx()
        val emb = mockk<EmbeddingDao>(relaxed = true)
        val isl = mockk<IslandDao>(relaxed = true)
        val states = mockk<BrainStateDao>(relaxed = true)
        val store = IslandStore(emb, isl, states, tx)
        val first = Islands.Fit(listOf(island(1)), longArrayOf(1L), intArrayOf(1), 1)
        store.saveFit(table(), first, now)
        coEvery { isl.upsertMembers(any()) } coAnswers { throw IllegalStateException("killed") }
        val second = Islands.Fit(listOf(island(5), island(6)), longArrayOf(1L), intArrayOf(5), 6)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { store.saveFit(table(), second, now + 1) } }
        assertEquals(listOf(1), store.current()!!.islands.map { it.id }, "the memory cache is not ahead of the database")
    }

    @Test
    fun `a fit that placed nothing keeps the stored islands and says so`() = runTest {
        val tx = Tx()
        val emb = mockk<EmbeddingDao>(relaxed = true)
        val isl = mockk<IslandDao>(relaxed = true)
        val states = mockk<BrainStateDao>(relaxed = true)
        val store = IslandStore(emb, isl, states, tx)
        store.saveFit(table(), Islands.Fit(emptyList(), LongArray(0), IntArray(0), 0), now)
        coVerify(exactly = 0) { isl.clearIslands() }
        coVerify(exactly = 0) { isl.clearMembers() }
        coVerify { states.put(match { it.key == IslandStore.KEY_EMPTY_FIT_AT }) }
    }

    private fun builder(store: IslandStore) = IslandBuilder(
        store, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), BrainClock { now },
    )

    private fun set(fitAt: Long) = IslandSet(listOf(island(1)), fitAt, emptyMap())

    @Test
    fun `a fit is due again when the embedding rows do not match what was counted, or the DNA changed under it`() = runTest {
        val store = mockk<IslandStore> {
            coEvery { current() } returns set(now - 1_000L)
            coEvery { lastEmptyFitMs() } returns 0L
            coEvery { refitMarked() } returns false
            coEvery { embeddingsIntact() } returns true
        }
        assertFalse(builder(store).isDue(now), "fresh and intact")
        coEvery { store.embeddingsIntact() } returns false
        assertTrue(builder(store).isDue(now), "a damaged embedding table is repaired by the next fit, not in a week")
        coEvery { store.embeddingsIntact() } returns true
        coEvery { store.refitMarked() } returns true
        assertTrue(builder(store).isDue(now), "the Spotify switch changed the DNA")
    }

    @Test
    fun `a fit that placed nothing is not tried again for a day`() = runTest {
        val store = mockk<IslandStore> {
            coEvery { current() } returns null
            coEvery { lastEmptyFitMs() } returns now - 3_600_000L
            coEvery { refitMarked() } returns false
            coEvery { embeddingsIntact() } returns true
        }
        assertFalse(builder(store).isDue(now))
        coEvery { store.lastEmptyFitMs() } returns now - IslandBuilder.EMPTY_FIT_RETRY_MS - 1
        assertTrue(builder(store).isDue(now))
    }

    // ---- the graph given to FastRP is bounded ---------------------------------------------------------------------------

    private fun edge(a: Long, b: Long, w: Int) = GraphEdgeEntity(a, b, GraphEdgeEntity.TYPE_RADIO, w, 0, 0L)

    @Test
    fun `edges between library nodes always stay and the heaviest others fill what the cap allows`() {
        val library = setOf(1L, 2L, 3L)
        val edges = listOf(edge(1, 2, 10), edge(1, 100, 900), edge(2, 101, 800), edge(100, 101, 700), edge(3, 102, 5), edge(103, 104, 1))
        val kept = IslandBuilder.capEdges(edges, library, 5)
        val nodes = HashSet<Long>(library).also { s -> kept.forEach { s.add(it.src); s.add(it.dst) } }
        assertTrue(nodes.size <= 5, "nodes: $nodes")
        assertTrue(edges[0] in kept)
        // The two heaviest edges to the outside fill the cap; the edge between the two outside nodes then costs no new node, so it stays too.
        assertEquals(setOf(1L to 100L, 2L to 101L, 100L to 101L), kept.filter { it != edges[0] }.map { it.src to it.dst }.toSet())
        assertTrue(edges[4] !in kept && edges[5] !in kept, "the light ones that would need new nodes are dropped")
        assertEquals(edges.size, IslandBuilder.capEdges(edges, library, 100).size, "under the cap nothing is dropped")
    }

    // ---- the Spotify switch ---------------------------------------------------------------------------------------------

    private fun id(n: String) = atlas.idForName(n).also { assertTrue(it >= 0, n) }

    @Test
    fun `with the Spotify switch off, genres and the year Spotify gave are taken out of a stored row`() {
        val on = DnaTarget(1L, 5L, "sp1", null, "Song 1", "Band", durationMs = 1, spotifyGenres = listOf("shoegaze"), spotifyReleaseDate = "2011-01-01")
        val before = DnaAssembler.assemble(on, DnaEvidence(), null, null, atlas, 1, now, 2027)
        assertEquals(id("shoegaze"), Tags.decode(before.track.tags).top)
        assertEquals(2011, before.track.firstYear)
        assertTrue(before.track.fetchedMask and DnaSource.SPOTIFY_GENRES != 0)
        val off = DnaTarget(1L, 5L, "sp1", null, "Song 1", "Band", durationMs = 1, spotifyOff = true)
        val after = DnaAssembler.assemble(off, DnaEvidence(), before.track, before.artist, atlas, 1, now + 1, 2027)
        assertTrue(Tags.decode(after.track.tags).isEmpty, "no genre is left that only Spotify said")
        assertTrue(DnaAssembler.decodeClaims(after.track.tagInputs).none { it.provider == DnaAssembler.P_SPOTIFY })
        assertTrue(DnaAssembler.decodeClaims(after.artist!!.tagInputs).none { it.provider == DnaAssembler.P_SPOTIFY })
        assertEquals(0, after.track.firstYear, "the year only Spotify said is gone too")
        assertEquals(0, after.track.fetchedMask and DnaSource.SPOTIFY_GENRES)
        assertEquals(-1, after.track.energy, "energy follows the tags: unknown again")
    }

    @Test
    fun `genres another source gave stay when the Spotify switch goes off`() {
        val on = DnaTarget(1L, 5L, "sp1", null, "Song 1", "Band", durationMs = 1, spotifyGenres = listOf("shoegaze"))
        val before = DnaAssembler.assemble(
            on, DnaEvidence(mbArtist = MbArtist("m1", "Band", "GB", listOf(MbGenreVote("techno", atlas.genre(id("techno"))!!.mbid, 9)), emptyList()), tried = DnaSource.MUSICBRAINZ),
            null, null, atlas, 1, now, 2027,
        )
        val off = DnaTarget(1L, 5L, "sp1", null, "Song 1", "Band", durationMs = 1, spotifyOff = true)
        val after = DnaAssembler.assemble(off, DnaEvidence(), before.track, before.artist, atlas, 1, now + 1, 2027)
        val ids = Tags.decode(after.track.tags).tags.map { it.atlasId }
        assertTrue(id("techno") in ids)
        assertTrue(id("shoegaze") !in ids)
    }

    @Test
    fun `a refresh that does not look the recording up again keeps the stored first-release year`() {
        val t = DnaTarget(1L, 5L, null, null, "Song 1", "Band", durationMs = 1)
        val hit = MbRecordingHit("rec", 100, "Song 1", "", 0, listOf("Band"), "art", "1975-03-01", emptyList())
        val first = DnaAssembler.assemble(t, DnaEvidence(mb = hit, tried = DnaSource.MUSICBRAINZ), null, null, atlas, 1, now, 2027)
        assertEquals(1975, first.track.firstYear)
        // Ninety days later only the artist's genres are asked: tried says MusicBrainz answered, the recording is not in the evidence.
        val again = DnaAssembler.assemble(t, DnaEvidence(tried = DnaSource.MUSICBRAINZ), first.track, first.artist, atlas, 1, now + 1, 2027)
        assertEquals(1975, again.track.firstYear)
        assertEquals(TrackDna.YEAR_MUSICBRAINZ, again.track.yearSource)
    }

    // ---- genre names the listener reads ---------------------------------------------------------------------------------

    @Test
    fun `an atlas node whose name holds a word the app never says is absent from what the listener reads`() {
        assertFalse(AtlasNames.isShown("atmospheric black metal"))
        assertFalse(AtlasNames.isShown("Atmospheric drum and bass"))
        assertTrue(AtlasNames.isShown("shoegaze"))
        assertFalse(AtlasNames.isShown(""))
        var hidden = 0
        var withWord = 0
        for (g in atlas.allIds) {
            val genre = atlas.genre(g) ?: continue
            val shown = AtlasNames.genreName(atlas, g)
            if (genre.name.lowercase().contains("atmos")) withWord++
            if (genre.name.isNotBlank() && shown == null) hidden++ else if (shown != null) assertFalse(shown.lowercase().contains("atmos"), shown)
        }
        assertTrue(withWord >= 3, "the atlas has the nodes this is about")
        assertEquals(withWord, hidden, "exactly the nodes with the word are hidden")
    }

    // ---- the served log -------------------------------------------------------------------------------------------------

    @Test
    fun `only the newest build of a list counts as taken`() {
        val day = 1_795_000_000_000L
        val older = Repetition.Served(com.theveloper.pixelplay.brain.Surface.DAILY_MIX, day, longArrayOf(1, 2, 3), "daily:1")
        val newer = Repetition.Served(com.theveloper.pixelplay.brain.Surface.DAILY_MIX, day + 1000, longArrayOf(4, 5), "daily:1")
        val other = Repetition.Served(com.theveloper.pixelplay.brain.Surface.DAILY_MIX, day + 500, longArrayOf(9), "daily:2")
        val newest = listOf(older, newer, other).newestPerList()
        assertEquals(2, newest.size)
        val taken = Repetition.takenOnDay(newest, day + 2000, 0)
        assertEquals(setOf(4L, 5L, 9L), taken)
    }

    // ---- the one-time purge when the Spotify switch is found off ---------------------------------------------------------

    private class MemoryState {
        val map = HashMap<String, ByteArray>()
        val dao = mockk<BrainStateDao>().also { d ->
            coEvery { d.get(any()) } answers { map[firstArg()] }
            coEvery { d.put(any()) } answers { val e = firstArg<BrainStateEntity>(); map[e.key] = e.blob }
        }
    }

    @Test
    fun `the purge takes Spotify claims out of every row once, marks the islands due, and runs again only after the switch was on`() = runTest {
        val tracks = FakeTrackDnaDao()
        val artists = FakeArtistDnaDao()
        val state = MemoryState()
        val on = DnaTarget(1L, 5L, "sp1", null, "Song 1", "Band", durationMs = 1, spotifyGenres = listOf("shoegaze"), spotifyReleaseDate = "2011-01-01")
        val r = DnaAssembler.assemble(on, DnaEvidence(), null, null, atlas, 1, now, 2027)
        tracks.upsert(r.track); artists.upsert(r.artist!!)
        val plain = DnaAssembler.assemble(DnaTarget(2L, 5L, "sp2", null, "Song 2", "Band", durationMs = 1), DnaEvidence(), null, null, atlas, 1, now, 2027)
        tracks.upsert(plain.track)
        var switchOn = true
        val switch = mockk<BrainSwitch> { coEvery { spotifyShapesPicks() } answers { switchOn } }
        val islands = mockk<IslandStore>(relaxed = true)
        val purge = SpotifyDnaPurge(switch, tracks, artists, state.dao, FixtureAtlas(), islands, BrainClock { now + 10 })

        assertEquals(0, purge.run(), "the switch is on: nothing to do")
        assertEquals(id("shoegaze"), Tags.decode(tracks.rows[1L]!!.tags).top)
        switchOn = false
        assertEquals(1, purge.run(), "one row carried Spotify's genres")
        assertTrue(Tags.decode(tracks.rows[1L]!!.tags).isEmpty)
        assertTrue(DnaAssembler.decodeClaims(artists.rows[5L]!!.tagInputs).none { it.provider == DnaAssembler.P_SPOTIFY })
        coVerify(exactly = 1) { islands.markRefit() }
        assertEquals(0, purge.run(), "done: not scanned again")
        switchOn = true
        purge.run()
        switchOn = false
        assertEquals(0, purge.run(), "a switch-off with nothing left to take out changes nothing")
    }
}
