package com.theveloper.pixelplay.brain

/**
 * Repeat activation (docs/brain-spec.md, 5.6, stage 2): how "active" a track still is in the
 * listener's memory, from the hours since each of its past plays,
 *
 *     B(t, now) = ln( sum_j c_j * powNeg( hours(now - t_j) + 1, d ) ),   d = 0.5
 *
 * Features 10 and 11 of 5.8 are `clip((B + 4) / 6, 0, 1)` and its square, so the ranker can place the
 * listener's own point of fatigue: just played reads high (the track is "hot", and the weight on it is
 * negative once squared), long ago reads low.
 *
 * A track keeps its last [MAX_PLAYS] plays and one aggregate bucket for every older one: the bucket
 * holds their total weight and the weighted mean of their times, and counts as one play at that time.
 * Plain `+ - * /` and the hand-rolled `ln` and `powNeg` of 10.2 only, so both platforms agree to the bit.
 * `d` is fixed at 0.5 in stages 1 and 2 (it is fitted per listener from stage 3).
 */
object RepeatActivation {
    const val MAX_PLAYS = 30
    const val DECAY_D = 0.5
    private const val HOUR_MS = 3_600_000.0

    /** The plays of one track, oldest first; [aggC] and [aggT] are the older plays folded into one. */
    class History {
        internal val times = LongArray(MAX_PLAYS)
        internal val weights = DoubleArray(MAX_PLAYS)
        var count = 0
            internal set

        /** Total weight of the folded plays; 0 when none were folded. */
        var aggC = 0.0
            internal set

        /** Weighted mean time (ms) of the folded plays. */
        var aggT = 0.0
            internal set

        /** Records a play at [tMs] with weight [c] (its trust weight, 4.3). Plays must come oldest first. */
        fun add(tMs: Long, c: Double) {
            if (c <= 0.0) return
            if (count == MAX_PLAYS) {
                // The oldest play joins the bucket.
                val oldT = times[0].toDouble()
                val oldC = weights[0]
                val total = aggC + oldC
                aggT = (aggT * aggC + oldT * oldC) / total
                aggC = total
                for (i in 1 until MAX_PLAYS) {
                    times[i - 1] = times[i]
                    weights[i - 1] = weights[i]
                }
                count--
            }
            times[count] = tMs
            weights[count] = c
            count++
        }

        val isEmpty: Boolean get() = count == 0 && aggC == 0.0
    }

    /**
     * `B` at [nowMs], or `Double.NaN` when the track has no play with weight (never played: the feature
     * is 0, not low). A play in the future (a clock set back) counts as just now.
     */
    fun b(history: History, nowMs: Long): Double {
        var sum = 0.0
        for (i in 0 until history.count) {
            sum += history.weights[i] * BrainMath.powNeg(hours(nowMs.toDouble() - history.times[i].toDouble()) + 1.0, DECAY_D)
        }
        if (history.aggC > 0.0) {
            sum += history.aggC * BrainMath.powNeg(hours(nowMs.toDouble() - history.aggT) + 1.0, DECAY_D)
        }
        if (sum <= 0.0) return Double.NaN
        return BrainMath.ln(sum)
    }

    /** Feature 10: `clip((B + 4) / 6, 0, 1)`; 0 for a track with no weighted play. */
    fun feature(history: History?, nowMs: Long): Double {
        if (history == null || history.isEmpty) return 0.0
        val b = b(history, nowMs)
        if (b != b) return 0.0
        return BrainMath.clip((b + 4.0) / 6.0, 0.0, 1.0)
    }

    private fun hours(deltaMs: Double): Double = if (deltaMs <= 0.0) 0.0 else deltaMs / HOUR_MS
}
