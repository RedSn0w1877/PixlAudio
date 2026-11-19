package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Test 6 of docs/brain-spec.md, section 11 (the learned weights against the frozen prior), on a REAL listening export. Local only: it runs
 * when the owner points it at a file, prints what it measured, and never asserts a number. In CI (no file) it is skipped.
 *
 * ```
 * export ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"
 * PIXLAUDIO_EVAL_EXPORT="C:\path\to\play-events.jsonl" ./gradlew :brain:test --tests "com.theveloper.pixelplay.brain.RealExportTest6" -i
 * ```
 *
 * The file is the same one test 1 reads (`BrainEvalTest`): the event log, one `PlayEvent.encode()` line per play, oldest first. The Android
 * Developer mode action "Export play events" writes it; the iOS `events-YYYY-MM.pbl` segments are the same lines. Nothing is uploaded, and
 * the file stays out of git. The report goes to `brain/build/reports/brain-test6.txt`.
 *
 * What the report says: how many graded plays the file holds, the log-loss of the learned weights and of the prior over the last 2,000, their
 * relative gain, the interval of the per-play gain over resampled sessions, the accuracy of both, and whether the test passes (at least 2,000
 * graded plays, a gain of at least 1%, an interval above 0). Until someone has run this on a real export there is NO real-data number
 * for the learner anywhere in this repository, and the legacy engine stays.
 *
 * The favourites are not in the file, so feature 8 (liked) is 0 here: the figure is a little conservative for a listener who likes a lot.
 */
class RealExportTest6 {

    @Test
    fun `test 6 on a real export`() {
        val path = System.getenv("PIXLAUDIO_EVAL_EXPORT")
        assumeTrue(!path.isNullOrBlank(), "set PIXLAUDIO_EVAL_EXPORT to a play-events.jsonl to run test 6 on real data")
        val file = File(path!!)
        assumeTrue(file.isFile, "no such file: $path")
        val events = file.useLines { lines -> lines.filter { it.isNotBlank() }.map { PlayEvent.decode(it) }.toList() }.sortedBy { it.eventId }
        assumeTrue(events.size >= 200, "an export of ${events.size} events is too short to say anything")
        val nowMs = events.last().startedAtMs + 3_600_000L
        val started = System.nanoTime()
        val run = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, nowMs, emptySet(), 0)
        val millis = (System.nanoTime() - started) / 1_000_000
        val summary = Gate.summarize(run.predictions, nowMs)
        val t6 = LearnReplay.test6(run.predictions)
        val text = buildString {
            appendLine("Test 6: learned weights against the frozen prior (real export: $path)")
            appendLine("events replayed:        ${run.events}   (replay took $millis ms)")
            appendLine("graded plays:           ${run.predictions.size}   (finished or skipped; the last ${Gate.WINDOW} are compared)")
            appendLine("log-loss learned:       ${fmt(t6.logLossLearned)}")
            appendLine("log-loss prior:         ${fmt(t6.logLossPrior)}")
            appendLine("gain:                   ${fmt(t6.improvement * 100.0)} %  (needs at least ${LearnReplay.TEST6_MIN_IMPROVEMENT * 100.0} %)")
            appendLine("per-play gain interval: [${fmt(t6.gainLow)}, ${fmt(t6.gainHigh)}]  (needs the low end above 0)")
            appendLine("accuracy learned:       ${fmt(summary.accLearned * 100.0)} %   prior: ${fmt(summary.accPrior * 100.0)} %  (finishes and skips)")
            appendLine("enough graded plays:    ${t6.enoughPlays}  (needs ${LearnReplay.TEST6_MIN_PLAYS})")
            appendLine("test 6 passes:          ${t6.passes}")
            appendLine("weights moved by:       ${run.model.updates} plays; gate would be on after two such weeks: ${summary.learnedBetter}")
            appendLine("This is one listener's one export. It is the only real-data number; nothing else in the repository is.")
        }
        println(text)
        val out = File("build/reports/brain-test6.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)
}
