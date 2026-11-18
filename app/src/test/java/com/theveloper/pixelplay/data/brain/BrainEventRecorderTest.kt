package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.Route
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.brain.store.BrainStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The event recorder (docs/brain-spec.md, 4.1, 4.2): what reaches `play_event`, with which reasons and
 * context, and what a play does to the affinity rows and the session.
 */
class BrainEventRecorderTest {

    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private val catalog = FakeTrackCatalog()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }

    /** Nothing to migrate in these tests: the state serves as it is. */
    private val noRebuild = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }

    private val engine = BrainEngine(store, { noRebuild }, clock)
    private val recorder = BrainEventRecorder(engine, resolver, catalog, store)

    init {
        catalog.add("song-a", "Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại", others = listOf("Guest"))
        catalog.add("song-b", "NewJeans", "Ditto")
        catalog.add("song-c", "Nirvana", "Lithium")
        catalog.add("engine-1", "d4vd", "Here With Me", inLibrary = false)
    }

    private fun play(
        songId: String,
        playedMs: Long,
        endReason: Int,
        startReason: Int = StartReason.TAP,
        tag: BrainItemTag? = null,
        flags: Int = 0,
        durationMs: Long = 200_000L,
    ): FinishedPlay {
        val started = now
        now += playedMs + 1_000L
        return FinishedPlay(
            songId = songId,
            context = PlayContext(
                title = "", artistDisplay = "", startReason = startReason, tag = tag,
                retrQ = if (tag?.isEngineOwned == true) 640 else -1,
                radioRank = if (tag?.isEngineOwned == true) 7 else -1,
                shuffle = true, route = Route.BLUETOOTH,
            ),
            startedAtMs = started,
            playedMs = playedMs,
            durationMs = durationMs,
            endReason = endReason,
            flags = flags,
            tzOffsetMin = 420,
        )
    }

    private val autoplayTag = BrainItemTag(surface = "autoplay", surfaceId = "seed", recId = 99L, slot = 2, arm = "RADIO")

    @Test
    fun `a skip after two seconds is in the log with endReason next`() = runTest {
        val reaction = recorder.record(play("song-a", 2_000L, EndReason.NEXT))
        assertNotNull(reaction)
        val event = store.events.values.single()
        assertEquals(EndReason.NEXT, event.endReason)
        assertEquals(2_000, event.playedMs)
        assertEquals(Origin.LIVE, event.origin)
        assertTrue(reaction!!.graded.isSkip)
        assertEquals(1.0, reaction.graded.h)
        assertEquals(resolver.rid("Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại"), event.rid)
        assertEquals(resolver.aid("Sơn Tùng M-TP"), event.primaryAid)
        assertEquals(listOf(resolver.aid("Guest")), event.otherAids.toList())
        assertEquals(420, event.tzOffsetMin)
        assertTrue(event.has(EventFlags.SHUFFLE))
        assertEquals(Route.BLUETOOTH, event.route)
        assertEquals(event.startedAtMs, event.eventId ushr 16)
    }

    @Test
    fun `the item's extras travel into the event - surface, list, slot, arm, retrieval`() = runTest {
        recorder.record(play("engine-1", 200_000L, EndReason.TRACK_DONE, StartReason.AUTOPLAY, autoplayTag))
        val event = store.events.values.single()
        assertEquals(Surface.AUTOPLAY, event.surface)
        assertEquals("seed", event.surfaceId)
        assertEquals(99L, event.recId)
        assertEquals(2, event.slot)
        assertEquals(Arm.RADIO, event.arm)
        assertEquals(640, event.retrQ)
        assertEquals(7, event.radioRank)
        assertEquals(StartReason.AUTOPLAY, event.startReason)
        assertEquals(EndReason.TRACK_DONE, event.endReason)
        // A play the listener started carries no arm and counts as their own choice.
        recorder.record(play("song-b", 200_000L, EndReason.TRACK_DONE, tag = BrainItemTag(surface = "playlist", surfaceId = "abc")))
        val own = store.events.values.last()
        assertEquals(Surface.PLAYLIST, own.surface)
        assertEquals(Arm.NONE, own.arm)
        assertEquals(-1, own.slot)
    }

    @Test
    fun `the event and the affinity rows it touched are written together`() = runTest {
        recorder.record(play("engine-1", 200_000L, EndReason.TRACK_DONE, StartReason.AUTOPLAY, autoplayTag))
        val rid = resolver.rid("d4vd", "Here With Me")
        val aid = resolver.aid("d4vd")
        assertNotNull(store.affinity[Family.TRACK to rid])
        assertNotNull(store.affinity[Family.ARTIST to aid])
        assertNotNull(store.affinity[Family.ARM to Arm.RADIO.toLong()])
        assertNotNull(store.affinity[Family.ALL to Family.ALL_KEY])
        assertNotNull(store.affinity[Family.SUM_BASE + Family.ARTIST to Family.ALL_KEY])
        assertEquals(1L, store.affinity[Family.COMPLETIONS to rid]!!.posQ)
        // A finished engine pick counts 0.6: less than a track the listener chose.
        assertEquals(629_146L, store.affinity[Family.TRACK to rid]!!.posQ)
        // What is in memory is what is on disk.
        engine.withState { state ->
            assertEquals(state.affinity.counter(Family.TRACK, rid)!!.posQ, store.affinity[Family.TRACK to rid]!!.posQ)
        }
    }

    @Test
    fun `the same play handed over twice is one event`() = runTest {
        val once = play("song-b", 150_000L, EndReason.NEXT)
        assertNotNull(recorder.record(once))
        assertNull(recorder.record(once))
        assertEquals(1, store.events.size)
        val rid = resolver.rid("NewJeans", "Ditto")
        engine.withState { state -> assertEquals(1, state.session.posCount) }
        assertEquals(1, store.affinity.keys.count { it.first == Family.TRACK && it.second == rid })
    }

    @Test
    fun `plays are stamped with their session, and a session ends after thirty idle minutes`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        recorder.record(play("song-b", 200_000L, EndReason.TRACK_DONE, StartReason.AUTO_ADVANCE))
        recorder.record(play("song-c", 200_000L, EndReason.TRACK_DONE, StartReason.AUTO_ADVANCE))
        val first = store.events.values.toList()
        assertEquals(listOf(0, 1, 2), first.map { it.posInSession })
        assertEquals(listOf(0, 1, 2), first.map { it.passiveRun })
        assertEquals(1, first.map { it.sessionId }.toSet().size)
        now += 31L * 60 * 1000
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        val later = store.events.values.last()
        assertEquals(0, later.posInSession)
        assertTrue(later.sessionId != first[0].sessionId)
    }

    @Test
    fun `only the listener's choices become anchors, with what a radio request needs`() = runTest {
        val own = recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))!!
        assertEquals(listOf("song-a"), own.anchors.map { it.mediaId })
        assertEquals("Sơn Tùng M-TP", own.anchors[0].primaryArtist)
        val engineOnly = recorder.record(play("engine-1", 200_000L, EndReason.TRACK_DONE, StartReason.AUTOPLAY, autoplayTag))!!
        assertEquals(listOf("song-a"), engineOnly.anchors.map { it.mediaId })
        val second = recorder.record(play("song-b", 150_000L, EndReason.NEXT))!!
        assertEquals(listOf("song-b", "song-a"), second.anchors.map { it.mediaId })
    }

    @Test
    fun `three engine picks skipped in a row change direction, to the next anchor only`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        recorder.record(play("song-b", 200_000L, EndReason.TRACK_DONE))
        catalog.add("e2", "X", "One", inLibrary = false)
        catalog.add("e3", "Y", "Two", inLibrary = false)
        catalog.add("e4", "Z", "Three", inLibrary = false)
        catalog.add("e5", "W", "Four", inLibrary = false)
        assertFalse(recorder.record(play("e2", 3_000L, EndReason.NEXT, StartReason.AUTOPLAY, autoplayTag))!!.changedDirection)
        val second = recorder.record(play("e3", 3_000L, EndReason.NEXT, StartReason.AUTOPLAY, autoplayTag))!!
        assertFalse(second.changedDirection)
        assertEquals(0, second.turn)
        // A hard skip: that artist's picks leave the tail.
        assertTrue(second.artistBlocked)
        assertEquals("y", second.artistKey)
        val third = recorder.record(play("e4", 3_000L, EndReason.NEXT, StartReason.AUTOPLAY, autoplayTag))!!
        assertTrue(third.changedDirection)
        assertEquals(1, third.turn)
        // The normal anchors are still reported; the turn follows the older one.
        assertEquals(listOf("song-b", "song-a"), third.anchors.map { it.mediaId })
        assertEquals(listOf("song-a"), third.turnAnchors.map { it.mediaId })
        assertFalse(third.needsOtherDirection)
        // While the engine's own picks play, the turn holds.
        val after = recorder.record(play("e5", 200_000L, EndReason.TRACK_DONE, StartReason.AUTOPLAY, autoplayTag))!!
        assertFalse(after.changedDirection)
        assertEquals(1, after.turn)
        assertEquals(listOf("song-a"), after.turnAnchors.map { it.mediaId })
    }

    @Test
    fun `the turn ends when the listener plays a track of their own`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        recorder.record(play("song-b", 200_000L, EndReason.TRACK_DONE))
        catalog.add("e2", "X", "One", inLibrary = false)
        catalog.add("e3", "Y", "Two", inLibrary = false)
        catalog.add("e4", "Z", "Three", inLibrary = false)
        for (id in listOf("e2", "e3", "e4")) recorder.record(play(id, 3_000L, EndReason.NEXT, StartReason.AUTOPLAY, autoplayTag))
        // They tap a song and hear it out: autoplay follows that song again, not the detour.
        val own = recorder.record(play("song-c", 200_000L, EndReason.TRACK_DONE))!!
        assertEquals(0, own.turn)
        assertTrue(own.turnAnchors.isEmpty())
        assertFalse(own.needsOtherDirection)
        assertEquals("song-c", own.anchors.first().mediaId)
    }

    @Test
    fun `skipping through one's own playlist changes nothing about the direction`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        for (id in listOf("song-b", "song-c", "song-b", "song-c")) {
            val reaction = recorder.record(play(id, 3_000L, EndReason.NEXT))!!
            assertFalse(reaction.changedDirection)
            assertEquals(0, reaction.turn)
        }
    }

    @Test
    fun `a play that was cut off by a process death is recorded as killed`() = runTest {
        // What the tracker writes when a play starts and when it pauses.
        val open = play("song-c", 45_000L, EndReason.KILLED)
        val writer = BrainEventRecorder(engine, resolver, catalog, store)
        writer.onPlayOpen(open)
        // Give the worker its turn; it runs on a real dispatcher.
        val deadline = System.currentTimeMillis() + 5_000L
        while (store.state[BrainStore.KEY_OPEN_PLAY] == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull(store.state[BrainStore.KEY_OPEN_PLAY])

        // A new process: the first message finds the play that was left open.
        val afterRestart = BrainEventRecorder(engine, resolver, catalog, store)
        afterRestart.onPlayOpen(null)
        while (store.events.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val event = store.events.values.single()
        assertEquals(EndReason.KILLED, event.endReason)
        assertEquals(45_000, event.playedMs)
        assertEquals(resolver.rid("Nirvana", "Lithium"), event.rid)
        assertNull(store.state[BrainStore.KEY_OPEN_PLAY])
    }

    @Test
    fun `a track nobody can name is not recorded, and only outside tracks skip the legacy counters`() = runTest {
        assertNull(recorder.record(play("unknown-id", 60_000L, EndReason.NEXT)))
        assertTrue(store.events.isEmpty())
        val engineContext = PlayContext(title = "", artistDisplay = "", tag = autoplayTag)
        assertTrue(recorder.isOutsideLibrary("engine-1", engineContext))
        assertFalse(recorder.isOutsideLibrary("song-a", engineContext)) // an engine pick the library has
        assertFalse(recorder.isOutsideLibrary("engine-1", null)) // no context: the legacy path as it was
    }

    @Test
    fun `nothing is loaded until a play is recorded`() = runTest {
        assertFalse(engine.isLoaded)
        assertNull(store.state[BrainStore.KEY_FIRST_USE_MS])
        recorder.record(play("song-a", 60_000L, EndReason.NEXT))
        assertTrue(engine.isLoaded)
        assertNotNull(store.state[BrainStore.KEY_FIRST_USE_MS])
    }
}
