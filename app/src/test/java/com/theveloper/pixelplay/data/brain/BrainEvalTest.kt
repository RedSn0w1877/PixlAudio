package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.Eval
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.LibraryFacts
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Test 1 of the evaluation harness (docs/brain-spec.md, 11, 14 "Done means"): the brain's prior scorer
 * against the legacy engine's score, as AUC for "finished" against "skipped" on held-out plays.
 *
 * Two parts:
 *  - a unit test that runs in CI on a made-up listener, to prove the pairing works end to end (the
 *    legacy score is rebuilt from the events, the brain's from the same events, both are compared). It
 *    says nothing about any real listener;
 *  - [real export], which runs only when the owner points it at a file, and prints what it measures. It
 *    never runs in CI and never asserts a number.
 *
 * To run test 1 on a real export (local, nothing is uploaded; the file stays out of git):
 *
 *     export ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"
 *     PIXLAUDIO_EVAL_EXPORT="C:\path\to\play-events.jsonl" \
 *       ./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.BrainEvalTest" -i
 *
 * The file is the event log, one `PlayEvent.encode()` line per play, oldest first: the Android
 * Developer mode action "Export play events" writes it; the iOS `events-YYYY-MM.pbl` segments are the
 * same lines, so `cat events-*.pbl > play-events.jsonl` works for an iPhone's history. The report is
 * printed to the test output and written to `app/build/reports/brain-eval.txt`.
 */
class BrainEvalTest {

    private class Report(val lines: List<String>) { override fun toString() = lines.joinToString("\n") }

    private fun measure(events: List<PlayEvent>, resamples: Int): Pair<Eval.SkipReport, Report> {
        val cut = Eval.cutIndex(events)
        val legacy = LegacyScoreReplay.scores(events, cut)
        // The score is a double; the report wants a monotone integer key it can rank exactly.
        val baselines = listOf(
            "legacy" to Eval.Baseline { e, _, _ -> legacy[e.eventId] ?: 0.0 },
            "artistRate" to Eval.ARTIST_RATE,
            "trackCompletions" to Eval.TRACK_COMPLETIONS,
        )
        val report = Eval.skipPrediction(events, LibraryFacts.EMPTY, Eval.Config(resamples = resamples), baselines)
        val lines = ArrayList<String>()
        lines.add("events ${events.size}: trained on ${report.trainEvents}, held out ${report.heldOutEvents} (finished ${report.finished}, skipped ${report.skipped})")
        lines.add("brain prior AUC ${"%.4f".format(report.brainAuc)}, calibration error ${"%.4f".format(report.calibrationError)}")
        for (i in report.baselineNames.indices) {
            lines.add(
                "  vs ${report.baselineNames[i]}: AUC ${"%.4f".format(report.baselineAuc[i])}, brain minus it ${"%+.4f".format(report.delta[i])} " +
                    "(95% over sessions ${"%+.4f".format(report.deltaLow[i])} to ${"%+.4f".format(report.deltaHigh[i])})",
            )
        }
        val gap = report.delta[0]
        lines.add(
            if (gap >= 0.05) "test 1: the brain is at least 0.05 AUC above the legacy score (the 14 target)."
            else "test 1: the brain is NOT 0.05 AUC above the legacy score: BrainSwitch stays legacy for Home ranking and the gap is investigated first (14).",
        )
        return report to Report(lines)
    }

    /** A made-up listener: 40 days, 6 artists they love and 6 they skip, 20 plays a day. */
    private fun madeUpListener(): List<PlayEvent> {
        val rng = BrainHash.Rng(BrainHash.fnv1a64("eval-unit-test"))
        val t0 = 1_750_000_000_000L
        val out = ArrayList<PlayEvent>()
        var seq = 0L
        for (day in 0 until 40) {
            var at = t0 + day * 86_400_000L
            for (k in 0 until 20) {
                val artist = (rng.uniform() * 12).toInt()
                val track = (rng.uniform() * 10).toInt()
                val rid = BrainHash.fnv1a64("rid $artist $track")
                val loves = artist < 6
                val done = rng.uniform() < (if (loves) 0.85 else 0.2)
                seq++
                out.add(
                    PlayEvent(
                        eventId = (at shl 16) or (seq and 0xFFFF), origin = Origin.LIVE, rid = rid, primaryAid = BrainHash.fnv1a64("aid $artist"),
                        startedAtMs = at, playedMs = if (done) 190_000 else 4_000, durationMs = 200_000,
                        startReason = StartReason.TAP, endReason = if (done) EndReason.TRACK_DONE else EndReason.NEXT,
                        surface = Surface.PLAYLIST, sessionId = day * 10L + k / 10, arm = Arm.NONE,
                    )
                )
                at += if (done) 200_000L else 5_000L
            }
        }
        return out
    }

    @Test
    fun `the legacy score and the brain's are compared on the same held-out plays`() {
        val events = madeUpListener()
        val (report, text) = measure(events, resamples = 50)
        println(text)
        assertEquals(events.size, report.trainEvents + report.heldOutEvents)
        assertEquals(listOf("legacy", "artistRate", "trackCompletions"), report.baselineNames)
        assertTrue(report.finished > 50 && report.skipped > 50)
        assertTrue(report.brainAuc in 0.0..1.0 && report.baselineAuc[0] in 0.0..1.0)
        // The made-up listener has taste by artist, which both scorers can find; neither is at chance.
        assertTrue(report.brainAuc > 0.6, "brain ${report.brainAuc}")
        assertTrue(report.baselineAuc[0] > 0.55, "legacy ${report.baselineAuc[0]}")
        assertTrue(report.deltaLow[0] <= report.delta[0] + 1e-9 && report.delta[0] <= report.deltaHigh[0] + 1e-9)
    }

    @Test
    fun `the legacy score of an event uses only the plays before its day`() {
        val events = madeUpListener()
        val cut = Eval.cutIndex(events)
        val full = LegacyScoreReplay.scores(events, cut)
        // Change what happens on the very last day beyond recognition: every earlier day's score is the same.
        val lastDayStart = Math.floorDiv(events.last().startedAtMs, 86_400_000L) * 86_400_000L
        val changed = events.map {
            if (it.startedAtMs >= lastDayStart) PlayEvent(
                eventId = it.eventId, origin = it.origin, rid = it.rid, primaryAid = it.primaryAid, startedAtMs = it.startedAtMs,
                playedMs = 2_000, durationMs = it.durationMs, startReason = it.startReason, endReason = EndReason.NEXT,
                surface = it.surface, sessionId = it.sessionId, arm = it.arm,
            ) else it
        }
        val other = LegacyScoreReplay.scores(changed, cut)
        for (e in events) {
            if (e.startedAtMs >= lastDayStart || e.eventId !in full) continue
            assertEquals(full[e.eventId]!!.toRawBits(), other[e.eventId]!!.toRawBits(), "event ${e.eventId}")
        }
    }

    @Test
    fun `real export`() {
        val path = System.getenv("PIXLAUDIO_EVAL_EXPORT")
        assumeTrue(!path.isNullOrBlank(), "set PIXLAUDIO_EVAL_EXPORT to a play-events.jsonl to run test 1 on real data")
        val file = File(path!!)
        assumeTrue(file.isFile, "no such file: $path")
        val events = file.useLines { lines ->
            lines.filter { it.isNotBlank() }.map { PlayEvent.decode(it) }.toList()
        }.sortedBy { it.eventId }
        assumeTrue(events.size >= 200, "an export of ${events.size} events is too short to hold out anything")
        val (_, text) = measure(events, resamples = 200)
        println(text)
        val out = File("build/reports/brain-eval.txt")
        out.parentFile?.mkdirs()
        out.writeText(text.toString() + "\n")
    }
}
