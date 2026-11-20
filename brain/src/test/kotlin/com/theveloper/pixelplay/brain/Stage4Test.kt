package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stage 4, milestone 1: the served feature vector and the gate's margin and interval. */
class Stage4Test {

    // ── The served vector ────────────────────────────────────────────────────

    private fun vector(vararg pairs: Pair<Int, Double>): DoubleArray {
        val x = DoubleArray(Ranker.D)
        x[0] = 1.0
        for ((i, v) in pairs) x[i] = v
        return x
    }

    @Test
    fun `a vector is kept to one 4096th and reads back exactly`() {
        val x = vector(1 to 0.5, 2 to -1.25, 4 to 0.123456, 38 to 0.3010299956639812)
        val bytes = ServedVector.quantize(x)
        assertEquals(ServedVector.BYTES, bytes.size)
        val back = ServedVector.dequantize(bytes)!!
        for (i in x.indices) assertTrue(kotlin.math.abs(back[i] - x[i]) <= 0.5 / ServedVector.SCALE + 1e-12, "feature $i")
        assertEquals(1.0, back[0])
        // The stored form reads back to the same bytes, and quantising a stored value changes nothing.
        assertArrayEquals(bytes, ServedVector.fromHex(ServedVector.toHex(bytes)))
        assertArrayEquals(bytes, ServedVector.quantize(back))
    }

    @Test
    fun `a value past the limit is clipped and a value that is not finite is stored as zero`() {
        val x = vector(1 to 100.0, 2 to -100.0, 3 to Double.NaN, 4 to Double.POSITIVE_INFINITY)
        val back = ServedVector.dequantize(ServedVector.quantize(x))!!
        assertEquals(32767.0 / ServedVector.SCALE, back[1])
        assertEquals(-32767.0 / ServedVector.SCALE, back[2])
        assertEquals(0.0, back[3])
        assertEquals(0.0, back[4])
    }

    @Test
    fun `a stored vector of the wrong size or text is no vector at all`() {
        assertNull(ServedVector.dequantize(null))
        assertNull(ServedVector.dequantize(ByteArray(ServedVector.BYTES - 1)))
        assertNull(ServedVector.fromHex(""))
        assertNull(ServedVector.fromHex("00".repeat(ServedVector.BYTES - 1)))
        assertNull(ServedVector.fromHex("0G".repeat(ServedVector.BYTES)))
        assertNull(ServedVector.fromHex("0A".repeat(ServedVector.BYTES)))
        assertNotNull(ServedVector.fromHex("0a".repeat(ServedVector.BYTES)))
    }

    private fun event(id: Long, finished: Boolean, served: ByteArray?, day: Long = 20_000L): PlayEvent {
        val t = day * BrainMath.DAY_MS + (id % 100) * 600_000L
        return PlayEvent(
            eventId = (t shl 16) or id, origin = Origin.LIVE, rid = 1000L + id % 7, primaryAid = 50L + id % 3, startedAtMs = t, tzOffsetMin = 0,
            playedMs = if (finished) 200_000 else 3_000, durationMs = 200_000, startReason = StartReason.AUTOPLAY,
            endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT, surface = Surface.AUTOPLAY, arm = Arm.RADIO,
            sessionId = 1L, servedX = served,
        )
    }

    @Test
    fun `an event line carries the vector when it has one and is the old line when it has none`() {
        val without = event(3, true, null)
        assertFalse(without.encode().contains("servedX"))
        // A line written before stage 4 reads back with no vector.
        assertNull(PlayEvent.decode(without.encode()).servedX)
        val served = ServedVector.quantize(vector(1 to 0.75, 22 to 0.4))
        val line = event(3, true, served).encode()
        assertTrue(line.endsWith("\"servedX\":\"${ServedVector.toHex(served)}\"}"))
        assertArrayEquals(served, PlayEvent.decode(line).servedX)
        // A vector of the wrong size is dropped on both sides rather than stored.
        assertFalse(event(3, true, ByteArray(5)).encode().contains("servedX"))
    }

    @Test
    fun `the learner trains on the vector a play was served with and falls back to the rebuilt one`() {
        val served = ServedVector.quantize(vector(1 to 0.5, 2 to 0.25, 4 to 0.9, 5 to 0.8, 10 to 0.6, 22 to 0.7, 38 to 0.4))
        val events = (0 until 40).map { event(it.toLong(), it % 3 != 0, if (it % 4 == 3) null else served) }
        val now = 21_000L * BrainMath.DAY_MS
        val with = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        val stripped = events.map { PlayEvent(
            eventId = it.eventId, origin = it.origin, rid = it.rid, primaryAid = it.primaryAid, startedAtMs = it.startedAtMs, tzOffsetMin = 0,
            playedMs = it.playedMs, durationMs = it.durationMs, startReason = it.startReason, endReason = it.endReason, surface = it.surface,
            arm = it.arm, sessionId = it.sessionId,
        ) }
        val without = LearnReplay.run(stripped.iterator(), LibraryFacts.EMPTY, now, emptySet())
        // The weights the served vector reaches (5 island_sim, 10 actr) moved only when it was used.
        assertEquals(Ranker.m0(5), without.model.m[5])
        assertNotEquals(Ranker.m0(5), with.model.m[5])
        assertNotEquals(Ranker.m0(10), with.model.m[10])
        assertEquals(events.size, with.events)
    }

    @Test
    fun `a live play and a replayed play train on the same served vector`() {
        val served = ServedVector.quantize(vector(1 to 0.5, 4 to 0.9, 5 to 0.8, 22 to 0.7))
        val events = (0 until 60).map { event(it.toLong(), it % 3 != 0, served) }
        val now = 21_000L * BrainMath.DAY_MS
        val replayed = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        val affinity = Affinity.State()
        Replay.applyFacts(affinity, LibraryFacts.EMPTY)
        val tracker = GradeTracker(affinity.completions)
        var session = SessionState()
        val live = Ranker.newModel(LearnReplay.dayOf(events[0], events[0].startedAtMs))
        for (e in events) {
            if (Session.isExpired(session, e.startedAtMs)) session = SessionState()
            Session.begin(session, e.startedAtMs, e.startReason)
            Brain.onEvent(affinity, tracker, session, e) { ev, g -> LearnReplay.applyLive(live, ev, g, false, affinity, session, now) }
        }
        Ranker.forget(live, BrainMath.localDay(now, 0))
        for (i in 0 until Ranker.D) {
            assertEquals(replayed.model.m[i].toRawBits(), live.m[i].toRawBits(), "m[$i]")
            assertEquals(replayed.model.q[i].toRawBits(), live.q[i].toRawBits(), "q[$i]")
        }
    }

    @Test
    fun `the planner hands every pick the vector it scored it with, and the picks themselves do not change`() {
        val rids = LongArray(30) { 1000L + it }
        val candidates = rids.mapIndexed { i, rid -> Candidate(rid = rid, aid = 500L + i, inLibrary = false, popPermille = 600) }
        val seeds = listOf(Retrieval.SeedList(1L, 10L, rids))
        val affinity = Affinity.State()
        val session = SessionState()
        val graph = StationPlanner.buildGraph(seeds, candidates, session)
        val plan = StationPlanner.plan(
            StationPlanner.Request(StationPlanner.MODE_AUTOPLAY, 8, candidates, seeds, emptyList(), 1_700_000_000_000L, 0, 1.0, tieSeed = 1L),
            affinity, session, graph,
        )
        assertEquals(8, plan.picks.size)
        for (pick in plan.picks) {
            val x = ServedVector.dequantize(pick.servedX)!!
            assertEquals(1.0, x[Features.BIAS])
            // What the pick says about itself is in the vector: its retrieval percentile and its radio rank.
            assertTrue(kotlin.math.abs(x[Features.RETRIEVAL] - pick.retrQ / 1000.0) <= 0.0006)
            assertTrue(x[Features.RADIO_RANK] > 0.0)
            // Popularity (feature 20) is one the rebuilt vector never has.
            assertTrue(kotlin.math.abs(x[Features.POP] - 0.1) <= 1.0 / ServedVector.SCALE)
            assertEquals(1.0, x[Features.UNHEARD_TRACK])
        }
        assertEquals(rids.take(8), plan.picks.map { it.rid })
    }

    // ── The gate ─────────────────────────────────────────────────────────────

    private fun summary(plays: Int, learned: Double, prior: Double, total: Int = plays) =
        Gate.Summary(plays, total, learned, prior, 0.6, 0.55, null, null)

    private val week = 7L * 86_400_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun `a week that wins by less than one percent is not a win`() {
        val small = summary(500, 0.5472, 0.55)
        assertTrue(small.learnedBetter)
        assertFalse(small.learnedClearlyBetter)
        var state = Gate.State()
        for (k in 0 until 4) state = Gate.decide(state, small, t0 + k * week)
        assertFalse(state.useLearned)
        assertEquals(0, state.winStreak)
        val clear = summary(500, 0.54, 0.55)
        assertTrue(clear.learnedClearlyBetter)
        state = Gate.decide(state, clear, t0 + 5 * week)
        assertEquals(1, state.winStreak)
        // A better-but-not-clear week ends a streak that has not switched anything on.
        state = Gate.decide(state, small, t0 + 6 * week)
        assertEquals(0, state.winStreak)
        state = Gate.decide(state, clear, t0 + 7 * week)
        state = Gate.decide(state, clear, t0 + 8 * week)
        assertTrue(state.useLearned)
        // Once on, a week that is better by less than one percent leaves it alone; one that is not better turns it off at once.
        assertTrue(Gate.decide(state, small, t0 + 9 * week).useLearned)
        assertFalse(Gate.decide(state, summary(500, 0.56, 0.55), t0 + 9 * week).useLearned)
    }

    @Test
    fun `from 2000 graded plays a week counts only with the interval above zero`() {
        val big = summary(2000, 0.53, 0.55, total = 2600)
        var state = Gate.State()
        state = Gate.decide(state, big, t0, intervalAboveZero = false)
        state = Gate.decide(state, big, t0 + week, intervalAboveZero = false)
        assertFalse(state.useLearned)
        assertEquals(0, state.winStreak)
        state = Gate.decide(state, big, t0 + 2 * week, intervalAboveZero = true)
        state = Gate.decide(state, big, t0 + 3 * week, intervalAboveZero = true)
        assertTrue(state.useLearned)
        // Below 2,000 plays in all the interval is not asked for.
        val few = summary(500, 0.53, 0.55, total = 1500)
        assertEquals(1, Gate.decide(Gate.State(), few, t0, intervalAboveZero = false).winStreak)
    }
}
