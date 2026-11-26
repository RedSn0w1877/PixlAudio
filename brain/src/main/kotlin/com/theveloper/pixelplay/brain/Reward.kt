package com.theveloper.pixelplay.brain

/**
 * From a play to a reward `r` in 0..1 and a trust weight `c` (docs/brain-spec.md, 4.3).
 *
 * Pure and replayable: the event log stores raw facts, and this file is the only place that says
 * what they are worth. A change here is a replay, not a migration.
 */
object Reward {

    const val COMPLETION_R = 0.8
    const val MAX_C = 4.5

    /**
     * The hand-set trust factors of 4.3 and the skip strengths of 5.5, as one value so the evaluation tool (tools/eval) can replay a
     * listener under other numbers. The engine always grades with [Trust.DEFAULT]; changing a default is a change of the spec's
     * constants (and of the golden files), never something a caller decides at run time.
     */
    class Trust(
        /** Base `c` of a live play the listener picked: finished (`r >= 0.5`) and not (`r < 0.5`, often "not now"). */
        val ownKept: Double = 1.0,
        val ownSkipped: Double = 0.6,
        /** Base `c` of a live engine pick: finished (it was put in front of them) and not (an active rejection). */
        val engineKept: Double = 0.6,
        val engineSkipped: Double = 1.0,
        /** Skip inside a streak of 3 or more skips within 60 s. */
        val streakFactor: Double = 0.3,
        /** Skip under 2 s directly after a skip. */
        val quickSkipFactor: Double = 0.3,
        /** Skip of a track with 3 or more past completions, for every key but the track's own. */
        val lovedSkipFactor: Double = 0.3,
        /** Completion with `passiveRun >= 6` and `>= 12`. */
        val passive6: Double = 0.5,
        val passive12: Double = 0.25,
        /** Session skip strength `h`: under 10 s, under 30 s, under half the track (5.5). */
        val hUnder10: Double = 1.0,
        val hUnder30: Double = 0.6,
        val hUnderHalf: Double = 0.2,
    ) {
        companion object {
            val DEFAULT = Trust()
        }
    }

    /** What grading needs beyond the event itself. */
    class Context(
        /** Past plays of this track with `r >= 0.8`. */
        val pastCompletions: Int = 0,
        /** Skips directly before this play, each within 60 s of the next (this play not counted). */
        val skipStreak: Int = 0,
    )

    class Graded(
        val r: Double,
        /** Trust for the track's own key. */
        val c: Double,
        /** Trust for every other key (artist, arm, all): lower when a well-loved track was skipped. */
        val cOther: Double,
        /** Which of the nine `r` rules fired, 1..9. */
        val rule: Int,
        val isSkip: Boolean,
        /** Skip strength for the session: 1.0 under 10 s, 0.6 under 30 s, 0.2 under half; 0 when not a skip. */
        val h: Double,
    ) {
        val isCompletion: Boolean get() = r >= COMPLETION_R
    }

    /** A skip (4.3): ended by next, pickedOther or queueEdit before half the track (30 s when the length is unknown). */
    fun isSkip(e: PlayEvent): Boolean {
        if (e.endReason != EndReason.NEXT && e.endReason != EndReason.PICKED_OTHER && e.endReason != EndReason.QUEUE_EDIT) return false
        if (e.playedMs < 0) return false
        return if (e.durationMs > 0) e.playedMs.toLong() * 2L < e.durationMs.toLong() else e.playedMs < 30_000
    }

    /** The session strength `h` of a skip (5.5, 7.3); 0 when [e] is not a skip. */
    fun skipStrength(e: PlayEvent, pastCompletions: Int, t: Trust = Trust.DEFAULT): Double {
        if (!isSkip(e)) return 0.0
        val h = if (e.playedMs < 10_000) t.hUnder10 else if (e.playedMs < 30_000) t.hUnder30 else t.hUnderHalf
        return if (pastCompletions >= 3 && h > 0.3) 0.3 else h
    }

    fun isEngineSurface(surface: Int): Boolean =
        surface == Surface.HOME_SHELF || surface == Surface.DAILY_MIX || surface == Surface.YOUR_MIX ||
            surface == Surface.NAMED_MIX || surface == Surface.STATION || surface == Surface.AUTOPLAY ||
            surface == Surface.ASSISTANT

    fun grade(e: PlayEvent, ctx: Context, t: Trust = Trust.DEFAULT): Graded {
        // r: the first rule that matches wins.
        val explicitPositive = EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or
            EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED
        val r: Double
        val rule: Int
        if (e.has(EventFlags.NOT_FOR_ME)) {
            r = 0.0; rule = 1
        } else if (e.flags and explicitPositive != 0) {
            r = 1.0; rule = 2
        } else if (e.playedMs == -1) {
            r = 0.75; rule = 3
        } else if (e.endReason == EndReason.TRACK_DONE) {
            r = 1.0; rule = 4
        } else if (e.durationMs > 0 && e.playedMs.toDouble() >= 0.8 * e.durationMs.toDouble()) {
            r = 1.0; rule = 5
        } else if (e.playedMs < 10_000) {
            r = 0.0; rule = 6
        } else if (e.playedMs < 30_000) {
            r = 0.1; rule = 7
        } else if (e.durationMs > 0) {
            r = 0.3 + 0.7 * BrainMath.min(1.0, (e.playedMs.toDouble() / e.durationMs.toDouble()) / 0.8)
            rule = 8
        } else {
            r = 0.6; rule = 9
        }

        val skip = isSkip(e)
        val live = e.origin == Origin.LIVE

        // c: one base value times every factor that applies.
        var c: Double = when (e.origin) {
            Origin.MIGRATED_HISTORY -> 0.8
            Origin.IMPORT_SPOTIFY -> 0.5 * 1.0
            Origin.IMPORT_YTM -> 0.5 * 0.35
            else -> if (isEngineSurface(e.surface)) {
                if (r >= 0.5) t.engineKept else t.engineSkipped
            } else {
                if (r >= 0.5) t.ownKept else t.ownSkipped
            }
        }
        if (rule == 1 || rule == 2) c *= 3.0
        if (e.has(EventFlags.SEEK_BACK) && rule != 2) c *= 1.5
        if (live && e.playedMs < 30_000 &&
            (e.endReason == EndReason.STOPPED || e.endReason == EndReason.KILLED ||
                e.endReason == EndReason.ERROR || e.endReason == EndReason.UNKNOWN)
        ) c *= 0.0
        if (e.endReason == EndReason.PREV) c *= 0.3
        if (skip && ctx.skipStreak + 1 >= 3) c *= t.streakFactor
        if (skip && e.playedMs < 2_000 && ctx.skipStreak >= 1) c *= t.quickSkipFactor
        if (r >= COMPLETION_R) {
            if (e.passiveRun >= 12) c *= t.passive12 else if (e.passiveRun >= 6) c *= t.passive6
        }
        if (e.has(EventFlags.EXPLORE_SLOT)) c *= 1.5
        if (e.has(EventFlags.NEVER_AUDIBLE)) c *= 0.0
        if (e.has(EventFlags.LEARN_EXCLUDED)) c *= 0.0
        if (c > MAX_C) c = MAX_C

        // Skipping a track they have finished three times says "not now", not "I dislike this artist".
        val cOther = if (skip && ctx.pastCompletions >= 3) c * t.lovedSkipFactor else c
        return Graded(r, c, cOther, rule, skip, skipStrength(e, ctx.pastCompletions, t))
    }
}
