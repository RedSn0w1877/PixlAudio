package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The evaluation harness of docs/brain-spec.md, 11, on three synthetic listeners (CI) and on small
 * hand-made cases. No number here is a claim about a real listener: the synthetic ones only show that
 * the harness measures what it says.
 */
class EvalTest {

    @Test
    fun `auc counts won pairs exactly and ties as half`() {
        // 2 finished (3, 5) against 2 skipped (1, 3): pairs 3>1 win, 3=3 tie, 5>1 win, 5>3 win = 3.5 of 4.
        assertEquals(0.875, Eval.auc(longArrayOf(3, 5, 1, 3), booleanArrayOf(true, true, false, false)), 0.0)
        assertEquals(1.0, Eval.auc(longArrayOf(9, 8, 1, 2), booleanArrayOf(true, true, false, false)), 0.0)
        assertEquals(0.0, Eval.auc(longArrayOf(1, 2, 8, 9), booleanArrayOf(true, true, false, false)), 0.0)
        assertEquals(0.5, Eval.auc(longArrayOf(4, 4, 4, 4), booleanArrayOf(true, false, true, false)), 0.0)
        assertEquals(0.5, Eval.auc(longArrayOf(1, 2), booleanArrayOf(true, true)), 0.0, "one class only")
    }

    @Test
    fun `the hold-out is the last 8 weeks of a long history and the last 15 percent of a short one`() {
        fun events(days: Int, n: Int) = (0 until n).map {
            PlayEvent(eventId = it.toLong(), origin = Origin.LIVE, rid = 1L, primaryAid = 1L,
                startedAtMs = 1_000_000_000_000L + it * (days * 86_400_000L / n), playedMs = 1000)
        }
        val long = events(200, 2000)
        val cutLong = Eval.cutIndex(long)
        assertTrue(long[cutLong].startedAtMs >= long.last().startedAtMs - 8 * Eval.WEEK_MS)
        assertTrue(cutLong == 0 || long[cutLong - 1].startedAtMs < long.last().startedAtMs - 8 * Eval.WEEK_MS)
        val short = events(40, 1000)
        assertEquals(850, Eval.cutIndex(short))
    }

    @Test
    fun `three synthetic listeners are measured, and the brain's prior scorer sees who they are`() {
        for (listener in SyntheticListeners.all()) {
            val report = Eval.skipPrediction(listener.events, LibraryFacts.EMPTY, Eval.Config(resamples = 100))
            println(
                "eval ${listener.name}: held out ${report.heldOutEvents} (finished ${report.finished}, skipped ${report.skipped}) " +
                    "brain AUC ${"%.3f".format(report.brainAuc)}, artist rate ${"%.3f".format(report.baselineAuc[0])}, " +
                    "completions ${"%.3f".format(report.baselineAuc[1])}, ECE ${"%.3f".format(report.calibrationError)}",
            )
            assertTrue(report.heldOutEvents > 100, listener.name)
            assertTrue(report.finished > 20 && report.skipped > 20, "both classes are there: ${listener.name}")
            assertTrue(report.brainAuc in 0.0..1.0)
            // The listeners have a real structure to find; the fixed prior weights must find some of it.
            assertTrue(report.brainAuc > 0.6, "${listener.name}: brain AUC ${report.brainAuc}")
            for (i in report.baselineAuc.indices) {
                assertTrue(report.deltaLow[i] <= report.delta[i] + 1e-9 && report.delta[i] <= report.deltaHigh[i] + 1e-9, "the interval holds the estimate: ${listener.name}")
            }
            assertTrue(report.calibrationError in 0.0..1.0)
        }
    }

    @Test
    fun `the report is the same when it is run again`() {
        val listener = SyntheticListeners.loyal()
        val a = Eval.skipPrediction(listener.events, LibraryFacts.EMPTY, Eval.Config(resamples = 40, seed = 3L))
        val b = Eval.skipPrediction(listener.events, LibraryFacts.EMPTY, Eval.Config(resamples = 40, seed = 3L))
        assertEquals(a.brainAuc.toRawBits(), b.brainAuc.toRawBits())
        assertEquals(a.deltaLow.toList(), b.deltaLow.toList())
        assertEquals(a.calibrationError.toRawBits(), b.calibrationError.toRawBits())
    }

    @Test
    fun `nothing after the cut influences a prediction before it`() {
        val listener = SyntheticListeners.explorer()
        val events = listener.events
        val config = Eval.Config(resamples = 0)
        val full = Eval.skipPrediction(events, LibraryFacts.EMPTY, config)
        // The same history with its very last events changed beyond recognition: every earlier
        // prediction (all but the changed rows) must be bit-identical.
        val changed = events.take(events.size - 20) + events.takeLast(20).map {
            PlayEvent(eventId = it.eventId, origin = it.origin, rid = it.rid, primaryAid = it.primaryAid, startedAtMs = it.startedAtMs,
                playedMs = 1000, durationMs = it.durationMs, startReason = it.startReason, endReason = EndReason.NEXT,
                surface = it.surface, sessionId = it.sessionId, posInSession = it.posInSession)
        }
        val other = Eval.skipPrediction(changed, LibraryFacts.EMPTY, config)
        val n = minOf(full.labeled.size, other.labeled.size) - 20
        assertTrue(n > 100)
        for (i in 0 until n) assertEquals(full.labeled[i].brain.toRawBits(), other.labeled[i].brain.toRawBits(), "prediction $i")
    }

    @Test
    fun `an extra baseline is scored from the state before the event`() {
        val listener = SyntheticListeners.loyal()
        var calls = 0
        val seenPlayed = HashSet<Long>()
        val spy = Eval.Baseline { e, affinity, _ ->
            calls++
            // The event itself has not been applied yet.
            if (affinity.completions[e.rid] ?: 0 > 0) seenPlayed.add(e.rid)
            0.5
        }
        val report = Eval.skipPrediction(listener.events, LibraryFacts.EMPTY, Eval.Config(resamples = 0), listOf("spy" to spy))
        assertEquals(report.heldOutEvents, calls)
        assertEquals(0.5, report.baselineAuc[0], 0.0, "a constant predictor has no skill")
    }

    // ── test 5 ──

    private fun health(listener: SyntheticListeners.Listener, multiplier: Double = 1.0): Eval.HealthReport {
        val start = Replay.rebuild(listener.events.iterator(), LibraryFacts.EMPTY, listener.nowMs)
        val world = Eval.HealthWorld(listener.library, listener.novel, listener.seeds, listener.finishRate)
        return Eval.listHealth(world, start, days = 30, nowMs = listener.nowMs + 3_600_000L, nuMultiplier = multiplier)
    }

    @Test
    fun `over 30 simulated days a daily mix serves mostly different music and no track is in two mixes of one day`() {
        for (listener in SyntheticListeners.all()) {
            val h = health(listener)
            println(
                "health ${listener.name}: distinct ${"%.3f".format(h.distinctRatio)} maxArtistShare ${"%.3f".format(h.maxArtistShare)} " +
                    "noveltyGap ${"%.3f".format(h.maxNoveltyGap)} overlap ${h.maxSameDayOverlap} shortest ${h.shortestList}",
            )
            assertEquals(30, h.days)
            assertEquals(0, h.maxSameDayOverlap, "no track in two mixes on one day: ${listener.name}")
            assertTrue(h.distinctRatio >= 0.60, "${listener.name}: distinct ${h.distinctRatio}")
            assertTrue(h.maxArtistShare <= 0.10 + 1e-9, "${listener.name}: one artist holds ${h.maxArtistShare}")
            assertTrue(h.maxNoveltyGap <= 0.05 + 1e-9, "${listener.name}: novelty gap ${h.maxNoveltyGap}")
            assertEquals(true, h.shortestList >= 25, "${listener.name}: shortest list ${h.shortestList}")
        }
    }

    @Test
    fun `the switch moves the share of new music`() {
        val listener = SyntheticListeners.explorer()
        val start = { Replay.rebuild(listener.events.iterator(), LibraryFacts.EMPTY, listener.nowMs) }
        val world = Eval.HealthWorld(listener.library, listener.novel, listener.seeds, listener.finishRate)
        fun novelShare(multiplier: Double): Double {
            val req = MixPlanner.Request(MixPlanner.KIND_DAILY, listener.library + listener.novel, listener.seeds,
                listener.nowMs + 3_600_000L, 0, nuMultiplier = multiplier, salt = 1L)
            val plan = MixPlanner.plan(req, start())
            val novel = listener.novel.map { it.rid }.toSet()
            return plan.picks.count { it.rid in novel }.toDouble() / plan.picks.size
        }
        val familiar = novelShare(0.4)
        val blend = novelShare(1.0)
        val discover = novelShare(2.0)
        println("novel share: familiar $familiar blend $blend discover $discover")
        assertTrue(familiar < blend && blend < discover, "$familiar $blend $discover")
        assertFalse(world.novel.isEmpty())
    }
}
