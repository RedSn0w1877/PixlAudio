package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.data.brain.store.FeedbackDao
import com.theveloper.pixelplay.data.brain.store.FeedbackEntity
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Don't count this" and the private session (docs/brain-spec.md, 8.5, 5.2 "Undo"). */
class LearnExclusionTest {

    private class FakeFeedbackDao : FeedbackDao {
        val rows = LinkedHashMap<Long, FeedbackEntity>()
        override suspend fun insert(row: FeedbackEntity) { rows[row.id] = row }
        override suspend fun undo(id: Long, nowMs: Long) { rows[id]?.let { rows[id] = it.copy(undoneAtMs = nowMs) } }
        override suspend fun active(): List<FeedbackEntity> = rows.values.filter { it.undoneAtMs == null }
    }

    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private val catalog = FakeTrackCatalog()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val noRebuild = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }
    private val engine = BrainEngine(store, { noRebuild }, clock)
    private val feedbackDao = FakeFeedbackDao()
    private val tracker = mockk<ListeningStatsTracker>(relaxed = true)
    private val exclusion = LearnExclusion(engine, store, feedbackDao, { tracker }, clock)
    private val privateSession = PrivateSession(feedbackDao, clock, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    private val recorder = BrainEventRecorder(engine, resolver, catalog, store, HomeAnchorStore(store), privateSession)

    init {
        catalog.add("song-a", "Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại")
        catalog.add("song-b", "NewJeans", "Ditto")
    }

    private fun play(songId: String, playedMs: Long = 200_000L, endReason: Int = EndReason.TRACK_DONE): FinishedPlay {
        val started = now
        now += playedMs + 1_000L
        return FinishedPlay(
            songId = songId,
            context = PlayContext(title = "", artistDisplay = "", startReason = StartReason.TAP),
            startedAtMs = started, playedMs = playedMs, durationMs = 200_000L, endReason = endReason, flags = 0, tzOffsetMin = 0,
        )
    }

    private fun trackPos(rid: Long): Long = store.affinity[Family.TRACK to rid]?.posQ ?: 0L

    @Test
    fun `a song's plays are flagged, their weight leaves the tables at once, and a row says why`() = runTest {
        repeat(3) { recorder.record(play("song-a")) }
        recorder.record(play("song-b"))
        val ridA = resolver.rid("Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại")
        val ridB = resolver.rid("NewJeans", "Ditto")
        assertTrue(trackPos(ridA) > 0)
        val posB = trackPos(ridB)

        val excluded = exclusion.excludeRecordings(listOf(ridA), FeedbackKinds.TARGET_SONG, ridA, playingSongId = "song-a")

        assertNotNull(excluded)
        assertEquals(3, excluded!!.count)
        assertEquals(0L, trackPos(ridA), "the song's own weight is gone")
        assertEquals(posB, trackPos(ridB), "another song is untouched")
        assertTrue(store.events.values.filter { it.rid == ridA }.all { it.has(EventFlags.LEARN_EXCLUDED) })
        assertFalse(store.events.values.first { it.rid == ridB }.has(EventFlags.LEARN_EXCLUDED))
        // The play in flight is flagged too.
        verify { tracker.markFlag("song-a", EventFlags.LEARN_EXCLUDED) }
        val row = feedbackDao.rows.values.single()
        assertEquals(FeedbackKinds.DONT_COUNT, row.kind)
        assertEquals(FeedbackKinds.TARGET_SONG, row.targetKind)
        assertEquals(ridA, row.targetId)
        assertNull(row.undoneAtMs)
    }

    @Test
    fun `asking again changes nothing, and undo gives the plays their flag back`() = runTest {
        repeat(2) { recorder.record(play("song-a")) }
        val ridA = resolver.rid("Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại")
        val first = exclusion.excludeRecordings(listOf(ridA), FeedbackKinds.TARGET_SONG, ridA, null)!!
        assertEquals(2, first.count)
        val again = exclusion.excludeRecordings(listOf(ridA), FeedbackKinds.TARGET_SONG, ridA, null)
        assertEquals(0, again!!.count)

        exclusion.undo(first)

        assertTrue(store.events.values.none { it.has(EventFlags.LEARN_EXCLUDED) })
        assertNotNull(feedbackDao.rows[first.feedbackId]!!.undoneAtMs)
    }

    @Test
    fun `the last hour takes every live play of the hour and nothing older`() = runTest {
        now -= 3 * 3_600_000L
        recorder.record(play("song-b"))
        now += 3 * 3_600_000L
        recorder.record(play("song-a"))
        val ridB = resolver.rid("NewJeans", "Ditto")

        val excluded = exclusion.excludeLastHour(playingSongId = null)

        assertEquals(1, excluded!!.count)
        assertFalse(store.events.values.first { it.rid == ridB }.has(EventFlags.LEARN_EXCLUDED), "three hours ago stays")
        assertEquals(FeedbackKinds.TARGET_LAST_HOUR, feedbackDao.rows.values.single().targetKind)
    }

    @Test
    fun `nothing to leave out is an empty answer, not a row`() = runTest {
        assertNull(exclusion.excludeRecordings(emptyList(), FeedbackKinds.TARGET_SONG, 0L, null))
        val none = exclusion.excludeLastHour(null)
        assertEquals(0, none!!.count)
    }

    // ── Private session ──────────────────────────────────────────────────────

    @Test
    fun `a private session is off at the start, and a play that overlaps it is logged as excluded`() = runTest {
        assertFalse(privateSession.active.value)
        recorder.record(play("song-a"))
        privateSession.set(true)
        assertTrue(privateSession.active.value)
        now += 10_000L
        recorder.record(play("song-b"))
        privateSession.set(false)
        now += 5_000L
        recorder.record(play("song-a"))

        val events = store.events.values.toList()
        assertFalse(events[0].has(EventFlags.LEARN_EXCLUDED), "before the session")
        assertTrue(events[1].has(EventFlags.LEARN_EXCLUDED), "during it")
        assertFalse(events[2].has(EventFlags.LEARN_EXCLUDED), "after it")
        // The private play left the tables alone; the log still has it.
        assertEquals(0L, store.affinity[Family.TRACK to resolver.rid("NewJeans", "Ditto")]?.posQ ?: 0L)
        // The span is in `feedback`, closed when the session ended.
        val span = feedbackDao.rows.values.single()
        assertEquals(FeedbackKinds.PRIVATE_SPAN, span.kind)
        assertNotNull(span.undoneAtMs)
    }

    @Test
    fun `a play that was sounding when the session began is private too`() = runTest {
        val started = now
        privateSession.set(false)
        now += 30_000L
        privateSession.set(true)
        now += 30_000L
        assertTrue(privateSession.overlaps(started, now))
        assertFalse(privateSession.overlaps(started, started + 1_000L))
    }
}
