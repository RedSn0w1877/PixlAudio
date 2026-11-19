package com.theveloper.pixelplay.brain

/**
 * The served-log repetition budget (docs/brain-spec.md, 1 promise 2, 6.4, 6.5 `servedPen`, 12.1): what a
 * generated list may repeat. Pure: the caller reads the served log and hands over plain lists.
 *
 * Three rules live here, all of them about music the app already served in a generated list:
 *  - a recording is never in two mixes built on the same local day ([takenOnDay]; the day of a daily
 *    list starts at 04:00, rule 12 of 10.3);
 *  - a recording served often in the last 30 days costs value in the next list ([servedPenalty]);
 *  - the log is kept 60 days ([RETENTION_MS]), the longest "stays away" rule of 6.1 needs.
 */
object Repetition {
    const val DAY_MS = 86_400_000L

    /** How far back "times served" looks (6.5). */
    const val WINDOW_MS = 30L * DAY_MS

    /** How long a served list is kept (3.8). */
    const val RETENTION_MS = 60L * DAY_MS

    /**
     * The penalty at three serves or more: `MAX_PENALTY * min(1, timesServedLast30Days / 3)` (6.5).
     * The spec says 0.4 and marks it [E]. Measured on the three synthetic listeners of the harness
     * (`EvalTest`, 30 simulated days) 0.4 gave 0.32 / 0.47 / 0.40 distinct tracks, below the 0.60 that
     * stage 2's "done means" asks; 1.2 gives 0.67 / 0.84 / 0.72 (handoff, deviation 82).
     */
    const val MAX_PENALTY = 1.2
    const val FULL_PENALTY_AT = 3

    /** A day starts at 04:00 local: `floorDiv(ms + tz * 60_000 - 14_400_000, 86_400_000)`. */
    fun mixDay(ms: Long, tzOffsetMin: Int): Long = BrainMath.floorDiv(ms + tzOffsetMin * 60_000L - 14_400_000L, DAY_MS)

    fun servedPenalty(timesServedLast30Days: Int, max: Double = MAX_PENALTY): Double {
        if (timesServedLast30Days <= 0) return 0.0
        val share = BrainMath.min(1.0, timesServedLast30Days.toDouble() / FULL_PENALTY_AT.toDouble())
        return max * share
    }

    /** One list the app served: the surface it was for ([Surface]), when it was built, its recordings. */
    class Served(val surface: Int, val builtAtMs: Long, val rids: LongArray, val surfaceId: String = "")

    /** True for the surfaces whose lists are mixes: a recording may be in only one of them a day. */
    fun isMixSurface(surface: Int): Boolean =
        surface == Surface.DAILY_MIX || surface == Surface.YOUR_MIX || surface == Surface.NAMED_MIX

    /**
     * How many of the lists served in the last 30 days hold each recording (a recording counts once per
     * list). A list built in the future (a clock that was set ahead) does not count.
     */
    fun timesServed(lists: List<Served>, nowMs: Long): HashMap<Long, Int> {
        val out = HashMap<Long, Int>()
        for (list in lists) {
            val age = nowMs - list.builtAtMs
            if (age < 0L || age >= WINDOW_MS) continue
            val seen = HashSet<Long>(list.rids.size * 2)
            for (rid in list.rids) if (rid != 0L && seen.add(rid)) out[rid] = (out[rid] ?: 0) + 1
        }
        return out
    }

    /**
     * The recordings that mixes of the same local day as [nowMs] already hold, except the lists of
     * [exceptSurface] (a list that is being rebuilt replaces its own earlier build).
     */
    fun takenOnDay(lists: List<Served>, nowMs: Long, tzOffsetMin: Int, exceptSurface: Int = -1, exceptId: String? = null): HashSet<Long> {
        val today = mixDay(nowMs, tzOffsetMin)
        val out = HashSet<Long>()
        for (list in lists) {
            if (!isMixSurface(list.surface)) continue
            // Stage 2b: with an id, only the very list being rebuilt is skipped; the other lists of its surface count.
            if (exceptId != null) {
                if (list.surface == exceptSurface && list.surfaceId == exceptId) continue
            } else if (list.surface == exceptSurface) continue
            if (mixDay(list.builtAtMs, tzOffsetMin) != today) continue
            for (rid in list.rids) if (rid != 0L) out.add(rid)
        }
        return out
    }

    /** Lists built before this time are dropped from the log. */
    fun keepAfter(nowMs: Long): Long = nowMs - RETENTION_MS

    /** Health (test 5): distinct recordings over all recordings of [builds] (several days of one mix). 1.0 for no builds. */
    fun distinctRatio(builds: List<LongArray>): Double {
        var total = 0
        val distinct = HashSet<Long>()
        for (b in builds) for (rid in b) {
            total++
            distinct.add(rid)
        }
        if (total == 0) return 1.0
        return distinct.size.toDouble() / total.toDouble()
    }

    /** Health (test 5): the most any one artist holds of a list, as a share of it. */
    fun maxArtistShare(aids: LongArray): Double {
        if (aids.isEmpty()) return 0.0
        val counts = HashMap<Long, Int>()
        var best = 0
        for (a in aids) {
            if (a == 0L) continue
            val n = (counts[a] ?: 0) + 1
            counts[a] = n
            if (n > best) best = n
        }
        return best.toDouble() / aids.size.toDouble()
    }
}
