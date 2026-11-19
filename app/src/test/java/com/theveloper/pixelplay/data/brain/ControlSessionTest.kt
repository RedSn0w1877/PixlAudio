package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Gate
import com.theveloper.pixelplay.brain.LearnReplay
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.Route
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Control sessions (docs/brain-spec.md, 5.12 point 8): which weights ranked a list, and whether it was a control, is decided when the list is
 * BUILT and carried with each queue entry. The recorder reads it at the end of the play and never works it out then, so the completion rate
 * of the control group is measured on what was really ranked with the prior.
 */
class ControlSessionTest {
    private val store = MemoryBrainStore()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val rebuilder = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }
    private val engine = BrainEngine(store, { rebuilder }, clock)
    private val resolver = MemoryTrackResolver()
    private val catalog = FakeTrackCatalog().also {
        it.add("engine-1", "d4vd", "Here With Me", inLibrary = false)
    }
    private val recorder = BrainEventRecorder(engine, resolver, catalog, store)

    private fun session(control: Boolean): Long {
        var id = 1L
        while (Ranker.isControlSession(id) != control) id++
        return id
    }

    private fun play(tag: BrainItemTag): FinishedPlay {
        val started = now
        now += 201_000L
        return FinishedPlay(
            songId = "engine-1",
            context = PlayContext(title = "", artistDisplay = "", startReason = StartReason.AUTOPLAY, tag = tag, route = Route.BLUETOOTH),
            startedAtMs = started, playedMs = 200_000L, durationMs = 200_000L, endReason = EndReason.TRACK_DONE,
        )
    }

    @Test
    fun `the choice is made when the list is built and says what ranked it`() = runTest {
        engine.withState { st ->
            st.learner.gate = Gate.State(useLearned = true, winStreak = 2, atMs = now)
            st.learner.learnVersion = LearnReplay.VERSION
            val control = st.rankingChoice(session(true))
            assertNull(control.source, "a control session is ranked with the prior")
            assertTrue(control.control)
            assertEquals(0, control.modelVersion)
            val learned = st.rankingChoice(session(false))
            assertNotNull(learned.source)
            assertFalse(learned.control)
            assertEquals(BrainEventRecorder.MODEL_LEARNED, learned.modelVersion)
            st.learner.gate = Gate.State()
            val prior = st.rankingChoice(session(false))
            assertNull(prior.source)
            assertFalse(prior.control, "a session that is not a control and has the prior on is not a control")
            assertEquals(0, prior.modelVersion)
        }
    }

    @Test
    fun `the recorder reads the choice from the entry, not from the session that is running when the play ends`() = runTest {
        // The entry was built as a control; the play ends in a session that is NOT a control one (the list outlived its session).
        val controlEntry = BrainItemTag(surface = "autoplay", surfaceId = "s", recId = 1L, slot = 0, arm = "RADIO", control = true, modelVersion = 0)
        recorder.record(play(controlEntry))
        // The entry was built with the learned weights on; the gate has since gone off.
        val learnedEntry = BrainItemTag(surface = "autoplay", surfaceId = "s", recId = 2L, slot = 0, arm = "RADIO", control = false, modelVersion = 1)
        recorder.record(play(learnedEntry))
        // A queue saved by an older build carries nothing: the prior, not a control.
        val oldEntry = BrainItemTag(surface = "autoplay", surfaceId = "s", recId = 3L, slot = 0, arm = "RADIO")
        recorder.record(play(oldEntry))
        val events = store.events.values.sortedBy { it.startedAtMs }
        assertEquals(3, events.size)
        assertTrue(events[0].has(EventFlags.CONTROL_SESSION))
        assertEquals(0, events[0].modelVersion)
        assertFalse(events[1].has(EventFlags.CONTROL_SESSION))
        assertEquals(1, events[1].modelVersion)
        assertFalse(events[2].has(EventFlags.CONTROL_SESSION))
        assertEquals(0, events[2].modelVersion)
    }

    @Test
    fun `the flag and the model version survive the extras and the saved queue`() {
        val tag = BrainItemTag(surface = "station", surfaceId = "x", recId = 5L, slot = 1, arm = "FAV", control = true, modelVersion = 0)
        val snap = PlaybackQueueItemSnapshot(mediaId = "m", uri = "spotify://0123456789abcdef012345", title = "t", artist = "a", albumTitle = "al", artworkUri = null, durationMs = 1L)
            .withBrainTag(tag)
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val back = json.decodeFromString(PlaybackQueueItemSnapshot.serializer(), json.encodeToString(PlaybackQueueItemSnapshot.serializer(), snap))
        val restored = back.brainTag()!!
        assertTrue(restored.control)
        assertEquals(0, restored.modelVersion)
        val learned = snap.withBrainTag(tag.copy(control = false, modelVersion = 1)).brainTag()!!
        assertFalse(learned.control)
        assertEquals(1, learned.modelVersion)
        // A tag with nothing but the decision is not empty: it must not be dropped on the way.
        assertFalse(BrainItemTag(modelVersion = 0).isEmpty)
        assertTrue(BrainItemTag().isEmpty)
    }

    @Test
    fun `a stored mix and a stored home row keep the choice they were built with`() {
        val mix = StoredMix(kind = 0, builtAtMs = 1L, seed = 2L, items = emptyList(), control = true, model = 0)
        val back = MixCodec.decode(MixCodec.encode(mix))!!
        assertTrue(back.control)
        val learned = MixCodec.decode(MixCodec.encode(mix.copy(control = false, model = 1)))!!
        assertFalse(learned.control)
        assertEquals(1, learned.model)
        // A mix saved before this build has neither field: the prior, no control.
        val old = MixCodec.decode(MixCodec.encode(StoredMix(kind = 0, builtAtMs = 1L, seed = 2L, items = emptyList())))!!
        assertFalse(old.control)
        assertEquals(0, old.model)
    }
}
