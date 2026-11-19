package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Gate
import com.theveloper.pixelplay.brain.LearnReplay
import com.theveloper.pixelplay.brain.LearnerCodec
import com.theveloper.pixelplay.brain.LibraryFacts
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Ranker
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
 * The learner inside the engine (docs/brain-spec.md, 5.9, 5.12 points 2 and 8): trained by the rebuild, updated by each live play and saved
 * with it, put back to the prior by "Reset what I've learned" without a rebuild bringing it back, and used for ranking only while the gate
 * says so.
 */
class LearnerEngineTest {
    private val store = MemoryBrainStore()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }

    private val rebuilder = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = store.getState(BrainEngine.KEY_LEARN_VERSION) != LearnReplay.VERSION.toString()
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt {
            val after = store.getState(BrainEngine.KEY_LEARN_AFTER)?.toLongOrNull() ?: Long.MIN_VALUE
            val result = ReplayMerger(store).rebuild(emptyList(), LibraryFacts.EMPTY, nowMs, emptyMap(), true, emptySet(), after, 0) { it }
            return StateRebuilder.Rebuilt(
                result.state, result.recentEventIds, mapOf(BrainEngine.KEY_LEARN_VERSION to LearnReplay.VERSION.toString()), result.learned,
            )
        }
    }

    private fun engine() = BrainEngine(store, { rebuilder }, clock)

    private var seq = 0L

    /** Rid 1 (artist 10) is finished, rid 2 (artist 11) skipped, in sessions of twelve. */
    private fun event(i: Int, atMs: Long = 1_750_000_000_000L + i * 400_000L): PlayEvent {
        val finished = i % 2 == 0
        return PlayEvent(
            eventId = (atMs shl 16) or (seq++ and 0xFFFFL), origin = Origin.LIVE,
            rid = if (finished) 1L else 2L, primaryAid = if (finished) 10L else 11L,
            startedAtMs = atMs, tzOffsetMin = 0, playedMs = if (finished) 200_000 else 4_000, durationMs = 200_000,
            startReason = StartReason.TAP, endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT,
            surface = Surface.PLAYLIST, sessionId = (i / 12 + 1).toLong(),
        )
    }

    private fun prefill(n: Int) {
        for (i in 0 until n) store.insertEvent(event(i))
    }

    @Test
    fun `the first use trains the learner from the log and saves it`() = runTest {
        prefill(600)
        val engine = engine()
        engine.withState { }
        engine.awaitRebuild()
        engine.withState { st ->
            assertEquals(LearnReplay.VERSION, st.learner.learnVersion)
            assertTrue(st.learner.model.updates > 300)
            assertNotNull(st.learner.summary)
            assertTrue(st.learner.model.isValid())
            // The gate has seen one evaluation: it needs two weeks running before the learned weights rank.
            assertFalse(st.learner.useLearned)
            assertNull(st.ranking(7L))
        }
        val saved = LearnerCodec.decode(store.getState(BrainEngine.KEY_LEARNER)!!)
        assertNotNull(saved)
        assertEquals(LearnReplay.VERSION.toString(), store.getState(BrainEngine.KEY_LEARN_VERSION))
    }

    @Test
    fun `a live play updates the learner and is saved with it, and a restart loads the same weights`() = runTest {
        prefill(600)
        val engine = engine()
        engine.withState { }
        engine.awaitRebuild()
        val live = event(700, now)
        val before = engine.withState { it.learner.model.updates }
        engine.withState { st -> assertNotNull(engine.applyLocked(st, live)) }
        engine.withState { st ->
            assertEquals(before + 1, st.learner.model.updates)
            assertEquals(live.eventId, st.learner.model.lastEventId)
        }
        val restarted = engine()
        restarted.withState { st ->
            val m = engine.withState { it.learner.model }
            for (i in 0 until Ranker.D) assertEquals(m.m[i].toRawBits(), st.learner.model.m[i].toRawBits(), "m[$i]")
            assertEquals(m.lastEventId, st.learner.model.lastEventId)
        }
        // The same play offered again changes nothing (the event row exists).
        engine.withState { st -> assertNull(engine.applyLocked(st, live)) }
    }

    @Test
    fun `reset puts the weights back to the prior, and the rebuild that follows does not bring them back`() = runTest {
        prefill(600)
        val engine = engine()
        engine.withState { }
        engine.awaitRebuild()
        engine.withState { st -> assertTrue(st.learner.model.updates > 0) }
        now += 1_000L
        engine.resetLearned()
        engine.withState { st ->
            assertTrue(st.learner.model.isPrior())
            assertEquals(0L, st.learner.model.updates)
            assertFalse(st.learner.useLearned)
            // (The summary is not checked here: the forced rebuild may already have replaced it by the time this runs.)
        }
        engine.awaitRebuild()
        // The rebuild replayed the whole log for the tables but learned from nothing before the reset.
        engine.withState { st ->
            assertTrue(st.learner.model.isPrior(), "the old plays are not learned from again")
            assertEquals(0L, st.learner.model.updates)
        }
        // A play after the reset is learned from.
        now += 1_000_000L
        val after = event(800, now)
        engine.withState { st -> engine.applyLocked(st, after) }
        engine.withState { st -> assertEquals(1L, st.learner.model.updates) }
        // And a restart keeps the reset.
        val restarted = engine()
        restarted.withState { st -> assertEquals(1L, st.learner.model.updates) }
    }

    @Test
    fun `a saved learner that cannot be read is replaced by a prior one and a replay is due`() = runTest {
        prefill(300)
        store.putState(BrainEngine.KEY_LEARNER, "{\"format\":\"brain-learner-v1\"}")
        store.putState(BrainEngine.KEY_LEARN_VERSION, LearnReplay.VERSION.toString())
        val engine = engine()
        engine.withState { st ->
            assertTrue(st.learner.model.isPrior())
            assertEquals(0, st.learner.learnVersion)
        }
        engine.awaitRebuild()
        engine.withState { st -> assertEquals(LearnReplay.VERSION, st.learner.learnVersion) }
    }

    @Test
    fun `the learned weights rank only while the gate has them on, and never in a control session`() = runTest {
        prefill(600)
        val engine = engine()
        engine.withState { }
        engine.awaitRebuild()
        engine.withState { st ->
            // Switch the gate on by hand (the unit tests of Gate cover how it decides).
            st.learner.gate = Gate.State(useLearned = true, winStreak = 2, atMs = now)
            var control = 0L
            while (!Ranker.isControlSession(control)) control++
            var normal = 0L
            while (Ranker.isControlSession(normal)) normal++
            assertNull(st.ranking(control), "a control session is ranked with the prior")
            val source = st.ranking(normal)
            assertNotNull(source)
            assertTrue(source!!.usesLearned)
            // A model that went bad does not rank.
            st.learner.model.m[3] = Double.NaN
            assertFalse(st.learner.useLearned)
            assertNull(st.ranking(normal))
        }
    }

    @Test
    fun `a play that sends the model back to the prior asks for a replay`() = runTest {
        prefill(600)
        val engine = engine()
        engine.withState { }
        engine.awaitRebuild()
        engine.withState { st -> st.learner.model.m[0] = Double.NaN }
        val live = event(900, now)
        engine.withState { st -> engine.applyLocked(st, live) }
        engine.awaitRebuild()
        engine.withState { st ->
            assertTrue(st.learner.model.isValid())
            assertTrue(store.affinityRewrites >= 2, "a second replay ran")
        }
    }
}
