package com.theveloper.pixelplay.presentation.viewmodel

import android.os.SystemClock
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.FinishedPlay
import com.theveloper.pixelplay.data.brain.PlayContext
import com.theveloper.pixelplay.data.brain.PlaySink
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the tracker hands to the music brain (docs/brain-spec.md, 4.2): every play with its context and
 * end reason, before the five-second floor, and what it keeps away from the legacy counters.
 */
class ListeningStatsTrackerBrainTest {

    private class RecordingSink(private val outside: Set<String> = emptySet()) : PlaySink {
        val finished = CopyOnWriteArrayList<FinishedPlay>()
        val open = CopyOnWriteArrayList<FinishedPlay?>()
        override fun onPlayFinished(play: FinishedPlay) { finished.add(play) }
        override fun onPlayOpen(play: FinishedPlay?) { open.add(play) }
        override suspend fun isOutsideLibrary(songId: String, context: PlayContext?): Boolean = songId in outside
    }

    private val dailyMixManager: DailyMixManager = mockk(relaxed = true)
    private val playbackStatsRepository: PlaybackStatsRepository = mockk(relaxed = true)
    private val tasteRepository: MusicTasteRepository = mockk(relaxed = true)
    private var clock = 10_000L

    @BeforeEach
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun tracker(sink: PlaySink) = ListeningStatsTracker(dailyMixManager, playbackStatsRepository, tasteRepository, sink)

    private fun context(startReason: Int = StartReason.TAP, engine: Boolean = false) = PlayContext(
        title = "Title",
        artistDisplay = "Artist",
        startReason = startReason,
        tag = if (engine) BrainItemTag(surface = "autoplay", recId = 1L, slot = 0, arm = "RADIO") else null,
    )

    private fun ListeningStatsTracker.start(songId: String, context: PlayContext? = context(), endReason: Int = EndReason.UNKNOWN, playing: Boolean = true) =
        onTrackChanged(songId, 0L, 200_000L, 0L, playing, context, endReason)

    @Test
    fun `a skip after two seconds reaches the brain with its reason, and not the legacy stores`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 2_000L
        tracker.start("b", context(StartReason.NEXT), endReason = EndReason.NEXT)

        val play = sink.finished.single()
        assertEquals("a", play.songId)
        assertEquals(2_000L, play.playedMs)
        assertEquals(EndReason.NEXT, play.endReason)
        assertEquals(StartReason.TAP, play.context.startReason)
        assertEquals(200_000L, play.durationMs)
        // Under five seconds: the legacy history and counters never see it, as before.
        Thread.sleep(150)
        coVerify(exactly = 0) { playbackStatsRepository.recordPlayback(any(), any(), any()) }
        coVerify(exactly = 0) { dailyMixManager.recordPlay(any(), any(), any()) }
    }

    @Test
    fun `every finalisation carries the reason the service gave`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 190_000L
        tracker.start("b", context(StartReason.AUTOPLAY, engine = true), endReason = EndReason.TRACK_DONE)
        clock += 40_000L
        tracker.finalizeCurrentSession(endReason = EndReason.TRACK_DONE) // STATE_ENDED
        tracker.start("c")
        clock += 8_000L
        tracker.onPlaybackStopped() // service teardown
        tracker.start("d")
        clock += 8_000L
        tracker.ensureSession("e", 0L, 200_000L, 0L, true, context(), EndReason.QUEUE_EDIT)
        clock += 1_000L
        tracker.finalizeCurrentSession(endReason = EndReason.ERROR)

        assertEquals(
            listOf("a" to EndReason.TRACK_DONE, "b" to EndReason.TRACK_DONE, "c" to EndReason.STOPPED, "d" to EndReason.QUEUE_EDIT, "e" to EndReason.ERROR),
            sink.finished.map { it.songId to it.endReason },
        )
        assertEquals(StartReason.AUTOPLAY, sink.finished[1].context.startReason)
        assertTrue(sink.finished[1].context.isEngineOwned)
    }

    @Test
    fun `listened time is time spent playing - pauses do not count`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 10_000L
        tracker.onPlayStateChanged(isPlaying = false, positionMs = 10_000L)
        clock += 600_000L // paused for ten minutes
        tracker.onPlayStateChanged(isPlaying = true, positionMs = 10_000L)
        clock += 5_000L
        tracker.finalizeCurrentSession(endReason = EndReason.NEXT)
        assertEquals(15_000L, sink.finished.single().playedMs)
        // The play was written down when it started and when it paused; cleared when it ended.
        assertEquals(listOf(0L, 10_000L), sink.open.filterNotNull().map { it.playedMs })
        assertEquals(null, sink.open.last())
    }

    @Test
    fun `an engine track without a library row leaves the legacy counters alone but stays in the history`() {
        val sink = RecordingSink(outside = setOf("engine-1"))
        val tracker = tracker(sink)
        tracker.start("engine-1", context(StartReason.AUTOPLAY, engine = true))
        clock += 60_000L
        tracker.start("library-1", context(), endReason = EndReason.TRACK_DONE)
        clock += 60_000L
        tracker.finalizeCurrentSession(endReason = EndReason.NEXT)

        coVerify(timeout = 2_000) { playbackStatsRepository.recordPlayback("engine-1", 60_000L, any()) }
        coVerify(timeout = 2_000) { playbackStatsRepository.recordPlayback("library-1", 60_000L, any()) }
        coVerify(timeout = 2_000) { dailyMixManager.recordPlay("library-1", 60_000L, any()) }
        coVerify(timeout = 2_000) { tasteRepository.record("library-1", 60_000L, any(), any(), any(), any()) }
        coVerify(exactly = 0) { dailyMixManager.recordPlay("engine-1", any(), any()) }
        coVerify(exactly = 0) { tasteRepository.record("engine-1", any(), any(), any(), any(), any()) }
        // Recently Played reads this list: the engine track is in it.
        assertEquals(listOf("library-1", "engine-1"), tracker.playbackHistory.value.map { it.songId })
        assertEquals(2, sink.finished.size)
    }

    @Test
    fun `the ui going away does not cut the play in two`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 30_000L
        tracker.onCleared() // the activity was closed; the service plays on
        assertTrue(sink.finished.isEmpty())
        clock += 40_000L
        tracker.ensureSession("a", 70_000L, 200_000L, 0L, true, context(StartReason.UNKNOWN), EndReason.UNKNOWN)
        clock += 100_000L
        tracker.finalizeCurrentSession(endReason = EndReason.TRACK_DONE)
        val play = sink.finished.single()
        assertEquals(170_000L, play.playedMs)
        assertEquals(EndReason.TRACK_DONE, play.endReason)
        assertEquals(StartReason.TAP, play.context.startReason)
    }

    @Test
    fun `a hand-over and its transition callback announce one play, not two`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 180_000L
        // The crossfade publishes the next player...
        tracker.ensureSession("b", 0L, 200_000L, 0L, true, context(StartReason.AUTO_ADVANCE), EndReason.TRACK_DONE)
        clock += 120L
        // ...and then the transition callback names the same track again.
        tracker.start("b", context(StartReason.AUTOPLAY, engine = true), endReason = EndReason.TRACK_DONE)
        clock += 50_000L
        tracker.finalizeCurrentSession(endReason = EndReason.NEXT)
        assertEquals(listOf("a", "b"), sink.finished.map { it.songId })
        assertEquals(50_120L, sink.finished[1].playedMs)
        assertEquals(StartReason.AUTOPLAY, sink.finished[1].context.startReason)
        // Repeat-one is different: the same track again after a full play is a new play.
        tracker.start("c")
        clock += 200_000L
        tracker.start("c", context(StartReason.AUTO_ADVANCE), endReason = EndReason.TRACK_DONE)
        clock += 10_000L
        tracker.finalizeCurrentSession(endReason = EndReason.NEXT)
        assertEquals(listOf("a", "b", "c", "c"), sink.finished.map { it.songId })
    }

    @Test
    fun `a restored, paused item that never played is not a play attempt`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("restored", context(StartReason.RESTORE), playing = false)
        clock += 60_000L
        tracker.onPlaybackStopped()
        assertTrue(sink.finished.isEmpty())
        assertTrue(sink.open.isEmpty()) // nothing was written at app start
        // Skipped before a sound came out: logged, flagged as never audible, worth nothing.
        tracker.start("restored", context(StartReason.RESTORE), playing = false)
        clock += 1_000L
        tracker.start("next", context(StartReason.NEXT), endReason = EndReason.NEXT)
        val play = sink.finished.single()
        assertEquals(0L, play.playedMs)
        assertTrue(play.flags and EventFlags.NEVER_AUDIBLE != 0)
    }

    @Test
    fun `likes and seeks are flagged on the play in flight`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 5_000L
        tracker.onSeek(5_000L, 0L) // too early to mean anything
        tracker.markFlag("other-song", EventFlags.LIKED_DURING) // not the one playing
        clock += 40_000L
        tracker.markFlag("a", EventFlags.LIKED_DURING)
        tracker.onSeek(45_000L, 30_000L) // back by 15 s after 30 s of listening
        clock += 20_000L
        tracker.onSeek(50_000L, 0L) // back to the start: a replay
        tracker.finalizeCurrentSession(endReason = EndReason.NEXT)
        val flags = sink.finished.single().flags
        assertEquals(EventFlags.LIKED_DURING or EventFlags.SEEK_BACK or EventFlags.REPLAYED, flags)
    }

    @Test
    fun `previous pressed twice to go back a track is not a replay of the track being left`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 60_000L
        // First press: back to the start. Second press, a moment later: the track before.
        tracker.onSeek(fromPositionMs = 60_000L, toPositionMs = 0L)
        clock += 800L
        tracker.start("b", context(StartReason.PREV), endReason = EndReason.PREV)
        assertEquals(0, sink.finished.single().flags and EventFlags.REPLAYED)

        // Back to the start and then listening on IS a replay, however the play ends later.
        tracker.start("c", endReason = EndReason.NEXT)
        clock += 60_000L
        tracker.onSeek(fromPositionMs = 60_000L, toPositionMs = 0L)
        clock += 45_000L
        tracker.start("d", context(StartReason.PREV), endReason = EndReason.PREV)
        val replayed = sink.finished.first { it.songId == "c" }
        assertTrue(replayed.flags and EventFlags.REPLAYED != 0)
    }

    @Test
    fun `a queue rebuilt around the playing track does not cut the play in two`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.start("a")
        clock += 40_000L
        // What the service does for a PLAYLIST_CHANGED onto the same track at the same position
        // (shuffle switched off): it keeps the session instead of forcing a new one.
        tracker.ensureSession("a", 40_000L, 200_000L, 0L, true, context(), EndReason.UNKNOWN)
        clock += 150_000L
        tracker.start("b", context(StartReason.AUTO_ADVANCE), endReason = EndReason.TRACK_DONE)
        val play = sink.finished.single()
        assertEquals("a", play.songId)
        assertEquals(190_000L, play.playedMs)
        assertEquals(EndReason.TRACK_DONE, play.endReason)
    }

    @Test
    fun `a caller without context, like cast, records nothing for the brain and everything as before`() {
        val sink = RecordingSink()
        val tracker = tracker(sink)
        tracker.onTrackChanged("cast-song", 0L, 200_000L, true)
        clock += 60_000L
        tracker.onPlaybackStopped()
        assertTrue(sink.finished.isEmpty())
        coVerify(timeout = 2_000) { playbackStatsRepository.recordPlayback("cast-song", 60_000L, any()) }
        coVerify(timeout = 2_000) { dailyMixManager.recordPlay("cast-song", 60_000L, any()) }
    }
}
