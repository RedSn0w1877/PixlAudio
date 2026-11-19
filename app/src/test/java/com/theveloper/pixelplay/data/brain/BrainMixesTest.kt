package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.blocking.BlockedArtist
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.RadioRowCodec
import com.theveloper.pixelplay.data.brain.store.RecentTrackRow
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.BrainRefreshScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Daily Mix and Your Mix with new music (docs/brain-spec.md, 6.2, 6.4, 8.2, stage 2a), end to end over
 * fakes: a listener with a few weeks of plays, a library, and radios the worker left in the cache.
 * No network, no phone: the real planner, the real served log, the real graph store.
 */
class BrainMixesTest {
    private val zone = java.util.TimeZone.getDefault()

    // The local day of a mix starts at 04:00 in the phone's zone: the tests run in one fixed zone.
    @BeforeEach fun fixZone() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    @AfterEach fun restoreZone() = java.util.TimeZone.setDefault(zone)

    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private val noRebuild = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }
    private val engine = BrainEngine(store, { noRebuild }, clock)

    private val radioLists = FakeRadioListDao()
    private val seedDao = FakeSeedDao(radioLists)
    private val servedDao = FakeServedListDao()
    private val edgeDao = FakeGraphEdgeDao()
    private val states = FakeBrainStateDao()
    private val served = ServedListStore(servedDao, clock)
    private val graphEdges = GraphEdgeStore(edgeDao, seedDao, states)
    private val repository = BrainRepository(radioLists, resolver, FakeRadioSource(), clock, graphEdges)

    private val library: List<Song> = (0 until 120).map { mixSong("s$it", "Lib Song $it", "Lib Artist ${it % 40}", album = "Lib Album $it") }
    private val music = mockk<MusicRepository> {
        coEvery { getAllSongsOnce() } returns library
        coEvery { getFavoriteSongIdsOnce() } returns (0 until 30).map { "s$it" }.toSet()
        every { getSongsByIds(any()) } answers { flowOf(library.filter { it.id in firstArg<List<String>>() }) }
    }
    private val dailyMixManager = mockk<DailyMixManager> {
        coEvery { personalizedPicks(any(), any(), any(), any()) } returns emptyList()
    }
    private var blockedIndex = BlockedArtistIndex.EMPTY
    private val blocked = mockk<BlockedArtistsRepository> { every { current } answers { blockedIndex } }
    private val taste = mockk<TasteAdjustmentRepository> {
        coEvery { snapshot(any()) } returns TasteAdjustments.NONE
        every { version } returns MutableStateFlow(0L)
    }
    private val playEvents = mockk<PlayEventDao> {
        coEvery { ignoredThreeTimes(any()) } returns emptyList()
        coEvery { stampsOf(any(), any()) } returns emptyList()
    }
    private val playable = mockk<PlayableStore>(relaxed = true)
    private val novelty = MutableStateFlow(Novelty.BLEND)
    private val stations = mockk<StationStore> { every { this@mockk.novelty } returns this@BrainMixesTest.novelty }
    private val switch = mockk<BrainSwitch>(relaxed = true)
    private val scheduler = mockk<BrainRefreshScheduler>(relaxed = true)

    // Stage 2b: no islands, no DNA, no picked artists: the mixes are the ones stage 2a built.
    private val islandStore = mockk<IslandStore> {
        coEvery { current() } returns null
        coEvery { tagShares() } returns emptyMap()
    }
    private val candidateDna = mockk<CandidateDna> {
        coEvery { enrich(any()) } answers { firstArg<List<com.theveloper.pixelplay.brain.Candidate>>() to CandidateDna.Known(emptyMap(), null) }
    }
    private val pickedArtists = mockk<PickedArtists> {
        coEvery { hasPicks() } returns false
        coEvery { all() } returns emptyList()
    }

    private fun mixes(engine: BrainEngine = this.engine) = BrainMixes(
        engine, repository, resolver, music, dailyMixManager, TasteConstraintAdapter(blocked, taste), seedDao, playEvents,
        states, served, graphEdges, playable, stations, switch, scheduler, clock,
        islandStore, mockk(relaxed = true), candidateDna, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        blocked, taste, mockk(relaxed = true), pickedArtists,
    )

    private suspend fun libraryRid(song: Song): Long =
        resolver.resolveTrack(Ids.TrackKeys(nameKey = Ids.nameKey(LibraryIndex.primaryArtistName(song), song.title)), song.duration.toInt())

    /** The listener finished the first [plays] library songs, a few days ago; the first [seeds] of them are seeds. */
    private suspend fun listen(plays: Int, seeds: Int) {
        val rows = ArrayList<RecentTrackRow>()
        for (i in 0 until plays) {
            val song = library[i]
            val rid = libraryRid(song)
            val aid = resolver.aid(LibraryIndex.primaryArtistName(song))
            val started = now - (5 * 86_400_000L) + i * 3_600_000L
            val event = PlayEvent(
                eventId = (started shl 16) or i.toLong(), origin = Origin.LIVE, rid = rid, primaryAid = aid, startedAtMs = started,
                playedMs = 190_000, durationMs = 200_000, startReason = StartReason.TAP, endReason = EndReason.TRACK_DONE,
                surface = Surface.PLAYLIST, arm = Arm.NONE,
            )
            engine.withState { engine.applyLocked(it, event) }
            if (i < seeds) rows.add(RecentTrackRow(rid, aid, started))
        }
        seedDao.recent = rows
    }

    /** Cached radios of the seeds: 40 rows each out of 70 new recordings, so the lists overlap. */
    private suspend fun cacheRadios(seeds: Int) {
        for (s in 0 until seeds) {
            val seedRid = libraryRid(library[s])
            val rows = (0 until 40).map { k ->
                val n = (s * 13 + k) % 70
                val artist = "New Artist ${n % 14}"
                val title = "New Song $n"
                StoredRadioRow(mixRow(1_000 + n, artist, title), resolver.rid(artist, title))
            }
            radioLists.upsert(
                RadioListEntity(seedRid, RadioListEntity.KIND_SONG, "", now - 86_400_000L, now - 86_400_000L, true,
                    RadioRowCodec.encodeRows(rows), RadioRowCodec.encodeChips(emptyList()))
            )
        }
    }

    private fun novelCount(view: MixView) = view.novelIds.size

    @Test
    fun `a Daily Mix has 30 songs, some of them new, each with a tag that carries the mix into the queue`() = runTest {
        listen(plays = 30, seeds = 6)
        cacheRadios(6)
        val view = mixes().today(MixPlanner.KIND_DAILY)!!
        assertEquals(30, view.songs.size)
        assertEquals(view.songs.size, view.songs.map { it.id }.toSet().size, "no track twice")
        assertTrue(novelCount(view) in 3..15, "about a quarter is music the listener does not have: ${novelCount(view)}")
        assertEquals(6, view.seedLists)
        assertTrue(view.songs.filter { it.id in view.novelIds }.all { it.contentUriString.startsWith(EngineTrack.CONTENT_URI_PREFIX) })
        assertTrue(view.songs.filter { it.id !in view.novelIds }.all { it.id.startsWith("s") })
        for (song in view.songs) {
            val tag = view.tags[song.id]!!
            assertEquals(Surface.nameOf(Surface.DAILY_MIX), tag.surface)
            assertEquals("daily", tag.surfaceId)
            assertTrue(tag.chosen)
            assertEquals(view.recId, tag.recId)
            assertTrue(tag.arm != null && tag.slot >= 0)
        }
        // Every new track was registered to play before the list was handed out.
        coVerify { playable.register(match { it.size == novelCount(view) }, any()) }
        // The last slot of a finite list is a familiar one (6.5).
        assertTrue(view.songs.last().id !in view.novelIds)
        verify { scheduler.ensureScheduled() }
    }

    @Test
    fun `the mix is the same all day, comes back after a restart without the brain, and is saved in the served log`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val first = mixes().today(MixPlanner.KIND_DAILY)!!
        assertEquals(1, servedDao.rows.size)

        now += 5 * 3_600_000L // later the same local day... unless 04:00 passed; the test day is mid-day
        val again = mixes().today(MixPlanner.KIND_DAILY)!!
        assertEquals(first.songs.map { it.id }, again.songs.map { it.id })
        assertEquals(1, servedDao.rows.size, "nothing was built again")

        val restarted = BrainEngine(store, { noRebuild }, clock)
        val afterRestart = mixes(restarted).saved(MixPlanner.KIND_DAILY)!!
        assertEquals(first.songs.map { it.id }, afterRestart.songs.map { it.id })
        assertFalse(restarted.isLoaded, "reading a saved mix does not load the brain")
        assertEquals(first.novelIds, afterRestart.novelIds)
    }

    @Test
    fun `no track is in two mixes of one day, and Your Mix leaves the Daily Mix alone`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        val daily = m.today(MixPlanner.KIND_DAILY)!!
        val your = m.today(MixPlanner.KIND_YOUR)!!
        assertEquals(60, your.songs.size)
        assertTrue(daily.songs.map { it.id }.intersect(your.songs.map { it.id }.toSet()).isEmpty())
        assertEquals(Surface.nameOf(Surface.YOUR_MIX), your.tags.values.first().surface)
        // Your Mix gives new music less room than the Daily Mix does (6.2: 0.8 of its share).
        assertTrue(novelCount(your).toDouble() / 60 <= novelCount(daily).toDouble() / 30 + 0.12)
    }

    @Test
    fun `Regenerate draws another list, the served log counts the one it replaced, and the other mix stays clear`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        val daily = m.today(MixPlanner.KIND_DAILY)!!
        val first = m.today(MixPlanner.KIND_YOUR)!!
        now += 60_000L
        val second = m.today(MixPlanner.KIND_YOUR, regenerate = true)!!
        assertEquals(3, servedDao.rows.size)
        assertTrue(first.songs.map { it.id } != second.songs.map { it.id }, "another draw")
        assertTrue(daily.songs.map { it.id }.intersect(second.songs.map { it.id }.toSet()).isEmpty())
        // The regenerated list is today's mix from now on.
        assertEquals(second.songs.map { it.id }, m.saved(MixPlanner.KIND_YOUR)!!.songs.map { it.id })
    }

    @Test
    fun `Familiar gives less new music than Discover`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        novelty.value = Novelty.FAMILIAR
        now += 60_000L
        val familiar = novelCount(m.today(MixPlanner.KIND_DAILY, regenerate = true)!!)
        novelty.value = Novelty.DISCOVER
        now += 60_000L
        val discover = novelCount(m.today(MixPlanner.KIND_DAILY, regenerate = true)!!)
        assertTrue(familiar < discover, "familiar $familiar, discover $discover")
    }

    @Test
    fun `with no radio cached the mix is the library alone, and it is built again once radios arrive`() = runTest {
        listen(30, 6)
        val m = mixes()
        val libraryOnly = m.today(MixPlanner.KIND_DAILY)!!
        assertEquals(0, novelCount(libraryOnly))
        assertEquals(30, libraryOnly.songs.size)

        cacheRadios(6)
        assertNotNull(m.saved(MixPlanner.KIND_DAILY), "too young to rebuild: it is still the day's mix")
        now += 20 * 60_000L
        assertNull(m.saved(MixPlanner.KIND_DAILY), "radios are cached now: the saved one is stale")
        val withNew = m.today(MixPlanner.KIND_DAILY)!!
        assertTrue(novelCount(withNew) > 0)
        // And once it has new music it is the day's mix and stays.
        assertNotNull(m.saved(MixPlanner.KIND_DAILY))
    }

    @Test
    fun `an artist blocked after the mix was built is gone from it when it is read back`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        val built = m.today(MixPlanner.KIND_DAILY)!!
        val victim = built.songs.first { it.id !in built.novelIds }
        blockedIndex = BlockedArtistIndex(listOf(BlockedArtist(999_999L, LibraryIndex.primaryArtistName(victim))))
        val back = m.saved(MixPlanner.KIND_DAILY)!!
        assertTrue(back.songs.none { LibraryIndex.primaryArtistName(it) == LibraryIndex.primaryArtistName(victim) })
        assertTrue(back.songs.size < built.songs.size)
    }

    @Test
    fun `a mix that lost library songs is not replaced by a different list`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        val built = m.today(MixPlanner.KIND_DAILY)!!
        assertTrue(built.novelIds.size >= BrainMixes.MIN_SAVED_SONGS, "the test needs a mix with new music")
        // Every library song of the mix has left the library (a sync, an unlike).
        every { music.getSongsByIds(any()) } returns flowOf(emptyList())

        val again = m.today(MixPlanner.KIND_DAILY)!!

        assertEquals(built.builtAtMs, again.builtAtMs)
        assertEquals(1, servedDao.rows.size, "no second list was drawn")
        assertTrue(again.songs.isNotEmpty() && again.songs.all { it.id in built.novelIds })
    }

    @Test
    fun `a song the listener removes from the mix stays out`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        val m = mixes()
        val built = m.today(MixPlanner.KIND_DAILY)!!
        val gone = built.songs[2]
        m.removeItem(MixPlanner.KIND_DAILY, gone.id)
        val back = m.saved(MixPlanner.KIND_DAILY)!!
        assertEquals(built.songs.size - 1, back.songs.size)
        assertTrue(back.songs.none { it.id == gone.id })
    }

    @Test
    fun `a mix built from what is cached also writes the graph, and the radios of earlier days are edges after one conversion`() = runTest {
        listen(30, 6)
        cacheRadios(6)
        mixes().today(MixPlanner.KIND_DAILY)
        assertTrue(edgeDao.rows.isNotEmpty(), "the lists stage 1 left behind were converted when the first mix was built")
        assertNotNull(states.values[GraphEdgeStore.KEY_BACKFILL])
    }

    @Test
    fun `a library with nothing in it and no radios makes no mix, so the legacy mixes can step in`() = runTest {
        coEvery { music.getAllSongsOnce() } returns emptyList()
        assertNull(mixes().today(MixPlanner.KIND_DAILY))
        assertEquals(0, servedDao.rows.size)
    }
}
