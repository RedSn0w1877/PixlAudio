package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainEventRecorder
import com.theveloper.pixelplay.data.brain.BrainRepository
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.MemoryBrainStore
import com.theveloper.pixelplay.data.brain.MemoryTrackResolver
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.RadioSeed
import com.theveloper.pixelplay.data.brain.StateRebuilder
import com.theveloper.pixelplay.data.brain.Station
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.brain.StoredRadio
import com.theveloper.pixelplay.data.brain.mixRow
import com.theveloper.pixelplay.data.brain.mixSong
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A harness that drives `AutoplayController`'s decisions without Robolectric (stage 1 review, open item 17):
 * the service's queue is a list the test owns, every collaborator is a mock or an in-memory fake, and what
 * is asserted is what the controller ASKS FOR: a radio for the right seed, or nothing at all.
 *
 * What it cannot reach: the media items it builds carry their `brain.*` extras in an Android `Bundle`, which
 * the JVM stubs do not hold, so which row of the queue is the engine's and what a built item says in
 * its extras stay with the instrumented tests and the phone checklist. The decision to look for picks, and for
 * whom, does not depend on a Bundle: that is what these tests hold.
 */
class AutoplayControllerHarnessTest {

    // What a coroutine of the controller throws is kept, not thrown into the test runner's thread: building a
    // media item needs Android's Uri and Bundle, which the JVM stubs do not have (see the class comment).
    private val thrown = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> thrown.add(e) })

    @AfterEach
    fun tearDown() = scope.cancel()

    private class Host(override val scope: CoroutineScope, var output: AutoplayPolicy.RemoteOutput = AutoplayPolicy.RemoteOutput.NONE) :
        AutoplayController.Host {
        override fun remoteOutput() = output
        override suspend fun playableOnRemote(items: List<MediaItem>) = BooleanArray(items.size) { true }
        override fun isRestoringQueue() = false
    }

    private inner class Harness(autoplayOn: Boolean = true, repeatMode: Int = Player.REPEAT_MODE_OFF, station: Station? = null) {
        var queue: List<MediaItem> = emptyList()
        var index = 0

        val player = mockk<Player>(relaxed = true)
        val engine = mockk<com.theveloper.pixelplay.data.service.player.DualPlayerEngine>(relaxed = true)
        val radioSeeds = CopyOnWriteArrayList<RadioSeed>()
        val appended = CopyOnWriteArrayList<List<MediaItem>>()

        val library = listOf(
            mixSong("a", "Song A", "Artist A", spotifyId = "spotifyidAAAAAAAAAAAAAA"),
            mixSong("b", "Song B", "Artist B", spotifyId = "spotifyidBBBBBBBBBBBBBB"),
            mixSong("c", "Song C", "Artist C", spotifyId = "spotifyidCCCCCCCCCCCCCC"),
        )

        val stations = mockk<StationStore>(relaxed = true)
        val brainSwitch = mockk<BrainSwitch>(relaxed = true)
        val brainRepository = mockk<BrainRepository>(relaxed = true)
        val host = Host(scope)
        val queueAttach = QueueAttachSignal()
        val controller: AutoplayController

        init {
            every { engine.masterPlayer } returns player
            every { engine.getFullQueue() } answers { queue }
            every { engine.getCurrentAbsoluteIndex() } answers { index }
            every { engine.isTransitionRunning() } returns false
            every { engine.appendToQueueEnd(any()) } answers { appended.add(firstArg()) }
            every { player.repeatMode } returns repeatMode
            every { player.currentMediaItem } answers { queue.getOrNull(index) }
            every { player.currentPosition } returns 10_000L
            every { player.playWhenReady } returns true
            every { player.hasNextMediaItem() } answers { index + 1 < queue.size }

            every { stations.current } returns MutableStateFlow<Station?>(station)
            every { stations.novelty } returns MutableStateFlow(0)
            every { stations.sourceIds } returns emptySet()
            every { brainSwitch.autoplayEnabledFlow } returns flowOf(autoplayOn)
            every { brainSwitch.youTubeSourceEnabledFlow } returns flowOf(true)

            coEvery { brainRepository.radioFor(any(), any(), any()) } answers {
                radioSeeds.add(firstArg())
                val rows = (1..12).map { com.theveloper.pixelplay.data.brain.store.StoredRadioRow(mixRow(it, "Other $it"), 1000L + it) }
                StoredRadio(seedRid = 7L, kind = RadioListEntity.KIND_SONG, rows = rows, fromCache = true)
            }

            val musicRepository = mockk<com.theveloper.pixelplay.data.repository.MusicRepository>(relaxed = true)
            coEvery { musicRepository.getAllSongsOnce() } returns library
            coEvery { musicRepository.getFavoriteSongIdsOnce() } returns emptySet()
            val spotifyDao = mockk<com.theveloper.pixelplay.data.database.SpotifyDao>(relaxed = true)
            coEvery { spotifyDao.getMatchedVideoId(any()) } answers { "vid" + firstArg<String>().take(10) }
            val engagementDao = mockk<com.theveloper.pixelplay.data.database.EngagementDao>(relaxed = true)
            coEvery { engagementDao.getAllEngagements() } returns emptyList()
            val blocked = mockk<BlockedArtistsRepository>(relaxed = true)
            every { blocked.current } returns BlockedArtistIndex(emptyList())
            val taste = mockk<TasteAdjustmentRepository>(relaxed = true)
            every { taste.version } returns MutableStateFlow(0L)
            coEvery { taste.snapshot(any()) } returns com.theveloper.pixelplay.data.recommendation.TasteAdjustments.of(emptyList(), 0L)
            val tracker = mockk<com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker>(relaxed = true)
            every { tracker.playbackHistory } returns MutableStateFlow(emptyList())
            val playEvents = mockk<PlayEventDao>(relaxed = true)
            coEvery { playEvents.ignoredThreeTimes(any()) } returns emptyList()
            val network = mockk<StreamNetworkConditions>()
            every { network.current() } returns StreamPrefetchPolicy.Conditions(StreamPrefetchPolicy.Network.WIFI, metered = false, dataSaver = false)
            val recorder = mockk<BrainEventRecorder>(relaxed = true)
            every { recorder.reactions } returns MutableSharedFlow()
            val clock = BrainClock { System.currentTimeMillis() }
            val noRebuild = object : StateRebuilder {
                override suspend fun isNeeded(): Boolean = false
                override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
            }

            controller = AutoplayController(
                engine, brainRepository, mockk<PlayableStore>(relaxed = true), musicRepository, spotifyDao, engagementDao,
                mockk(relaxed = true), blocked, taste, tracker, playEvents, brainSwitch, network, clock,
                BrainEngine(MemoryBrainStore(), { noRebuild }, clock), recorder, MemoryTrackResolver(), stations,
                queueAttach, mockk(relaxed = true), mockk(relaxed = true),
            )
            controller.attach(host)
        }

        fun items(count: Int): List<MediaItem> = (0 until count).map { i ->
            MediaItem.Builder()
                .setMediaId(listOf("a", "b", "c")[i % 3])
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Song " + "ABC"[i % 3]).build())
                .build()
        }

        /** Waits until [done] is true, or [ms] pass. */
        fun waitFor(ms: Long = 5_000L, done: () -> Boolean): Boolean = runBlocking {
            withTimeoutOrNull(ms) {
                while (!done()) delay(20)
                true
            } ?: false
        }

        /** True when nothing happened within [ms]. */
        fun staysQuiet(ms: Long = 900L): Boolean = runBlocking {
            delay(ms)
            radioSeeds.isEmpty() && appended.isEmpty()
        }
    }

    @Test
    fun `with two songs left the controller asks for related music around the song that is playing`() {
        val h = Harness()
        h.queue = h.items(2)
        h.index = 0
        h.controller.onTransition()
        assertTrue(h.waitFor { h.radioSeeds.isNotEmpty() }, "a radio was asked for")
        val seed = h.radioSeeds.first()
        assertEquals("Song A", seed.title)
        assertEquals("Artist A", seed.primaryArtist)
        assertEquals("vidspotifyid", seed.videoId.take(12))
        // The only thing that can go wrong after the radio on the JVM is building the Android media item.
        h.waitFor(1_000L) { thrown.isNotEmpty() }
        assertTrue(thrown.all { e -> e.stackTrace.any { it.className.contains("MediaItemBuilder") } }, "unexpected: $thrown")
    }

    @Test
    fun `with plenty of songs left it asks for nothing`() {
        val h = Harness()
        h.queue = h.items(6)
        h.index = 0
        h.controller.onTransition()
        assertTrue(h.staysQuiet(), "no radio and no append while four songs are still queued")
    }

    @Test
    fun `the trigger counts the full queue from the current track, not the length of what is left in the player`() {
        val h = Harness()
        h.queue = h.items(6)
        h.index = 3 // two after the current one
        h.controller.onTransition()
        assertTrue(h.waitFor { h.radioSeeds.isNotEmpty() })
        assertEquals("Song A", h.radioSeeds.first().title)
    }

    @Test
    fun `under repeat nothing is added, and with the switch off nothing is added`() {
        val repeating = Harness(repeatMode = Player.REPEAT_MODE_ALL)
        repeating.queue = repeating.items(2)
        repeating.controller.onTransition()
        assertTrue(repeating.staysQuiet(), "repeat all")

        val off = Harness(autoplayOn = false)
        off.queue = off.items(2)
        off.controller.onTransition()
        assertTrue(off.staysQuiet(), "autoplay off")
    }

    @Test
    fun `on a Cast device no picks are asked for, on a Spotify Connect device they are`() {
        val cast = Harness()
        cast.host.output = AutoplayPolicy.RemoteOutput.CAST
        cast.queue = cast.items(2)
        cast.controller.onTransition()
        assertTrue(cast.staysQuiet(), "cast")

        val connect = Harness()
        connect.host.output = AutoplayPolicy.RemoteOutput.CONNECT
        connect.queue = connect.items(2)
        connect.controller.onTransition()
        assertTrue(connect.waitFor { connect.radioSeeds.isNotEmpty() }, "connect")
    }

    @Test
    fun `while a tapped song's queue is still being attached nothing is decided, and when it is attached the queue is looked at`() {
        val h = Harness()
        h.queue = h.items(1)
        h.queueAttach.onBegin()
        h.controller.onTransition()
        assertTrue(h.staysQuiet(), "a one-item queue that is still being filled is not a short queue")
        // The rest of the queue arrives: it is longer than the trigger, so still nothing.
        h.queue = h.items(6)
        h.queueAttach.onEnd()
        assertTrue(h.staysQuiet(), "an attached queue of six has songs left")
        // The attach ends with the queue as short as it was: now it is a short queue.
        h.queue = h.items(2)
        h.queueAttach.onBegin()
        h.queueAttach.onEnd()
        assertTrue(h.waitFor { h.radioSeeds.isNotEmpty() })
    }

    @Test
    fun `an Endless Listening station asks for the radio of every one of its island seeds and stays the queue's station`() {
        // Three seeds, one for each of three islands; the first is playing. The station started long enough ago to be past its grace.
        val station = Station.endless(listOf("a", "b", "c"), "Endless Listening", System.currentTimeMillis() - 10 * 60_000L)
        val h = Harness(station = station)
        h.queue = h.items(2)
        h.index = 0
        h.controller.onTransition()
        assertTrue(h.waitFor { h.radioSeeds.size >= 3 }, "a radio for each seed, got ${h.radioSeeds.map { it.title }}")
        assertEquals(setOf("Song A", "Song B", "Song C"), h.radioSeeds.map { it.title }.toSet())
        // The queue holds the station's first seed, so the station is not ended.
        io.mockk.verify(exactly = 0) { h.stations.clear() }
        h.waitFor(1_000L) { thrown.isNotEmpty() }
        assertTrue(thrown.all { e -> e.stackTrace.any { it.className.contains("MediaItemBuilder") } }, "unexpected: $thrown")
    }
}
