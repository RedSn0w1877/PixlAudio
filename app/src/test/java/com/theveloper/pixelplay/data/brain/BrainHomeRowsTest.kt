package com.theveloper.pixelplay.data.brain

import android.content.Context
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "Because you listened to X" rows of Home (docs/brain-spec.md, 8.1): built from the anchors'
 * cached radios, music the listener does not have, twelve cards, a track once on the page; nothing
 * at app start, and nothing rebuilt that did not change.
 */
class BrainHomeRowsTest {

    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val noRebuild = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }
    private val engine = BrainEngine(store, { noRebuild }, clock)
    private val anchorStore = HomeAnchorStore(store)
    private val brainRepository = mockk<BrainRepository>()
    private val music = mockk<MusicRepository>()
    private val blocked = mockk<BlockedArtistsRepository> { every { current } returns BlockedArtistIndex.EMPTY }
    private val tasteVersion = MutableStateFlow(0L)
    private val taste = mockk<TasteAdjustmentRepository> {
        coEvery { snapshot(any()) } returns TasteAdjustments.NONE
        every { version } returns tasteVersion
    }
    private val playEvents = mockk<PlayEventDao> { coEvery { ignoredThreeTimes(any()) } returns emptyList() }
    private val spotifyDao = mockk<SpotifyDao> { coEvery { getMatchedVideoId(any()) } answers { "vid-" + firstArg<String>() } }
    private val playable = mockk<PlayableStore>(relaxed = true)
    private val switch = mockk<BrainSwitch> { every { youTubeSourceEnabledFlow } returns flowOf(true) }
    private val network = mockk<StreamNetworkConditions> {
        every { current() } returns StreamPrefetchPolicy.Conditions(StreamPrefetchPolicy.Network.WIFI, metered = false, dataSaver = false)
    }
    private val context = mockk<Context> { every { filesDir } returns File(System.getProperty("java.io.tmpdir")) }

    private val rows = BrainHomeRows(
        context, anchorStore, brainRepository, engine, resolver, music, blocked, taste, playEvents, spotifyDao,
        playable, switch, network, clock,
    )

    private suspend fun anchor(mediaId: String, artist: String, title: String, agoMs: Long = 60_000L): HomeAnchor =
        HomeAnchor(
            rid = resolver.rid(artist, title), aid = resolver.aid(artist), mediaId = mediaId, title = title,
            primaryArtist = artist, spotifyId = "sp-$mediaId", durationMs = 200_000L, heardAtMs = now - agoMs,
        )

    private fun saveAnchors(vararg anchors: HomeAnchor) {
        store.putState(HomeAnchorStore.KEY, HomeAnchorStore.encode(anchors.toList()))
    }

    /** A radio of [count] official-audio rows by ten artists, three rows each at most. */
    private suspend fun radio(seed: HomeAnchor, prefix: String, count: Int = 30): StoredRadio {
        val stored = ArrayList<StoredRadioRow>(count)
        for (i in 1..count) {
            val artist = "$prefix Artist ${i % 10}"
            val title = "$prefix Song $i"
            stored.add(
                StoredRadioRow(
                    RadioRow(
                        videoId = "$prefix-v$i", title = title, artists = listOf(RadioArtistRef(artist)),
                        durationSec = 200, kind = MusicVideoKind.ATV,
                    ),
                    resolver.rid(artist, title),
                )
            )
        }
        return StoredRadio(seed.rid, RadioListEntity.KIND_SONG, stored, fromCache = true)
    }

    private suspend fun loadBrain() {
        engine.withState { }
        engine.awaitRebuild()
    }

    @Test
    fun `nothing is built before the brain was used in this process`() = runTest {
        saveAnchors(anchor("m1", "Sabrina Carpenter", "Espresso"))
        assertFalse(engine.isLoaded)
        assertNull(rows.refreshIfDue())
        coVerify(exactly = 0) { brainRepository.radioFor(any(), any(), any()) }
        assertTrue(rows.cached().isEmpty())
    }

    @Test
    fun `a row is twelve tracks the listener does not have, two per artist at most, each with its tags`() = runTest {
        val espresso = anchor("m1", "Sabrina Carpenter", "Espresso")
        saveAnchors(espresso)
        coEvery { brainRepository.radioFor(any(), any(), any()) } returns radio(espresso, "A")
        // The library already has one of the radio's rows: it is not a card.
        coEvery { music.getAllSongsOnce() } returns listOf(
            Song.emptySong().copy(id = "lib", title = "A Song 3", artist = "A Artist 3", duration = 200_000L)
        )
        loadBrain()

        val built = rows.refreshIfDue()
        assertNotNull(built)
        val row = built!!.single()
        assertEquals(BrainHomeRow.idOf(espresso.rid), row.id)
        assertEquals("Espresso", row.anchor.title)
        assertEquals(BrainHomeRows.CARDS, row.items.size)
        assertTrue(row.items.none { it.song.title == "A Song 3" })
        assertTrue(row.items.groupBy { it.track.artists.first().name }.values.all { it.size <= 2 })
        assertEquals((0 until BrainHomeRows.CARDS).toList(), row.items.map { it.slot })
        // Every card is a track from outside the library, playable through the `playable` path.
        assertTrue(row.items.all { it.song.contentUriString.startsWith(EngineTrack.CONTENT_URI_PREFIX) })

        val tags = row.tags()
        assertEquals(BrainHomeRows.CARDS, tags.size)
        val first = tags.getValue(row.items[0].song.id)
        assertEquals("homeShelf", first.surface)
        assertEquals(row.id, first.surfaceId)
        assertEquals(row.recId, first.recId)
        assertEquals(0, first.slot)
        assertEquals(Arm.nameOf(Arm.RADIO), first.arm)
        assertEquals(WhyLine.nameOf(WhyLine.BECAUSE_LISTENED), first.whyKey)
        assertEquals("Espresso", first.whyArg)
        // The listener plays the row themselves: its tracks are theirs in the queue, not the engine's tail.
        assertTrue(first.chosen)
        assertFalse(first.isEngineOwned)

        // The same anchors and the same taste: nothing is rebuilt, and the saved rows are what Home shows.
        assertNull(rows.refreshIfDue())
        coVerify(exactly = 1) { brainRepository.radioFor(any(), any(), any()) }
        assertEquals(row.items.map { it.song.id }, rows.cached().single().items.map { it.song.id })
    }

    @Test
    fun `two anchors give two rows and a track is on the page once`() = runTest {
        val espresso = anchor("m1", "Sabrina Carpenter", "Espresso", agoMs = 60_000L)
        val ditto = anchor("m2", "NewJeans", "Ditto", agoMs = 120_000L)
        saveAnchors(espresso, ditto)
        // Both radios hold the same tracks.
        val shared = radio(espresso, "A")
        coEvery { brainRepository.radioFor(match { it.mediaId == "m1" }, any(), any()) } returns shared
        coEvery { brainRepository.radioFor(match { it.mediaId == "m2" }, any(), any()) } returns
            StoredRadio(ditto.rid, RadioListEntity.KIND_SONG, shared.rows, fromCache = true)
        coEvery { music.getAllSongsOnce() } returns emptyList()
        loadBrain()

        val built = rows.refreshIfDue()!!
        assertEquals(listOf("Espresso", "Ditto"), built.map { it.anchor.title })
        val all = built.flatMap { row -> row.items.map { it.track.rid } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a radio with too little to show gives no row, and is not asked again at once`() = runTest {
        val espresso = anchor("m1", "Sabrina Carpenter", "Espresso")
        saveAnchors(espresso)
        coEvery { brainRepository.radioFor(any(), any(), any()) } returns radio(espresso, "A", count = 4)
        coEvery { music.getAllSongsOnce() } returns emptyList()
        loadBrain()

        assertNull(rows.refreshIfDue())
        assertTrue(rows.cached().isEmpty())
        assertNull(rows.refreshIfDue())
        coVerify(exactly = 1) { brainRepository.radioFor(any(), any(), any()) }
    }

    @Test
    fun `a taste change rebuilds the rows, and muted music is not a card`() = runTest {
        val espresso = anchor("m1", "Sabrina Carpenter", "Espresso")
        saveAnchors(espresso)
        coEvery { brainRepository.radioFor(any(), any(), any()) } returns radio(espresso, "A")
        coEvery { music.getAllSongsOnce() } returns emptyList()
        loadBrain()
        val before = rows.refreshIfDue()!!.single()

        // "Not for me" on the first card.
        val muted = before.items[0].song
        val adjustments = mockk<TasteAdjustments> {
            every { isEmpty } returns false
            every { excludes(any()) } answers { firstArg<Song>().id == muted.id }
            every { blocks(any()) } returns false
            every { combinedMultiplier(any()) } returns 1.0
        }
        coEvery { taste.snapshot(any()) } returns adjustments
        tasteVersion.value = 1L

        val after = rows.refreshIfDue()!!.single()
        assertTrue(after.items.none { it.song.id == muted.id })
        assertEquals(BrainHomeRows.CARDS, after.items.size)
    }

    @Test
    fun `a card can only be played after its tracks are registered as playable`() = runTest {
        val espresso = anchor("m1", "Sabrina Carpenter", "Espresso")
        saveAnchors(espresso)
        coEvery { brainRepository.radioFor(any(), any(), any()) } returns radio(espresso, "A")
        coEvery { music.getAllSongsOnce() } returns emptyList()
        loadBrain()
        val row = rows.refreshIfDue()!!.single()

        rows.prepareForPlayback(row)
        coVerify { playable.register(match { tracks -> tracks.map { it.songId.toString() } == row.items.map { it.song.id } }, any()) }
    }

    @Test
    fun `an anchor older than seven days is not shown`() = runTest {
        val old = anchor("m1", "Sabrina Carpenter", "Espresso", agoMs = 8L * 24 * 60 * 60 * 1000)
        saveAnchors(old)
        coEvery { music.getAllSongsOnce() } returns emptyList()
        loadBrain()
        assertNull(rows.refreshIfDue())
        assertTrue(rows.cached().isEmpty())
        coVerify(exactly = 0) { brainRepository.radioFor(any(), any(), any()) }
    }
}
