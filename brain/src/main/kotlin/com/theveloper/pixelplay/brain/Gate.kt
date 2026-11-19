package com.theveloper.pixelplay.brain

/**
 * The frozen-prior gate (docs/brain-spec.md, 5.12 point 8) and the figures "Your sound" shows (8.6, section 7).
 *
 * The switch is decided by prediction, not by a traffic split: at every weekly rebuild the learned weights and the prior `M0`
 * both predict the listener's last [WINDOW] graded plays prequentially (each play predicted before it was learned from), and the
 * learned weights are used only while their log-loss is the lower one. Pure and deterministic: log-loss and accuracy are sums over
 * the same arrays in the same order on both platforms, with the hand-rolled `ln`.
 *
 * Honesty rule of stage 3: every number here comes from the listener's own graded plays (`r = 1` finished, `r <= 0.1` skipped). The
 * learner has run only on synthetic data so far; no real-data accuracy is claimed anywhere until a real export has been scored.
 */
object Gate {
    /** The graded plays the two weight vectors are compared on (5.12 point 8). */
    const val WINDOW = 2000

    /** Fewer graded plays than this: the standard recipe stays on, and Your sound says it is still getting to know the listener. */
    const val MIN_PLAYS = 300

    /** Weeks in a row the learned weights must win before they are used. */
    const val WINS_NEEDED = 2

    /** Two evaluations closer than this count as one week. */
    const val WEEK_MS = 6L * 86_400_000L

    /** A month, for "up from ... a month ago". */
    const val MONTH_MS = 30L * 86_400_000L

    /** A prediction is kept this far from 0 and 1 so the log-loss is finite. */
    const val P_EDGE = 1.0e-4

    /** What the gate decided, and when. [useLearned] is false until the learned weights have won [WINS_NEEDED] weeks running. */
    class State(
        val useLearned: Boolean = false,
        /** Weeks in a row the learned log-loss was the lower one (counted at most once per [WEEK_MS]). */
        val winStreak: Int = 0,
        /** When the last counted evaluation ran; 0 for never. */
        val atMs: Long = 0L,
    )

    /** The graded plays of one replay, in time order, predicted by both weight vectors before each was learned from. */
    class Predictions(
        val times: LongArray,
        val sessions: LongArray,
        /** True: finished (`r = 1`). False: skipped (`r <= 0.1`). */
        val finished: BooleanArray,
        val learned: DoubleArray,
        val prior: DoubleArray,
        /** The listener's base finish rate at that moment: a prediction at or above it calls the play a finish. */
        val threshold: DoubleArray,
    ) {
        val size: Int get() = times.size
    }

    /** The figures of one evaluation. */
    class Summary(
        /** Graded plays in the window the log-losses and accuracies are over. */
        val plays: Int,
        /** Graded plays in the whole history. */
        val playsTotal: Int,
        val logLossLearned: Double,
        val logLossPrior: Double,
        /** Share of the window's graded plays whose finish or skip the learned weights called right; 0 when [plays] is 0. */
        val accLearned: Double,
        val accPrior: Double,
        /** The learned accuracy on the window that ended a month earlier; null with fewer than [MIN_PLAYS] graded plays then. */
        val accLearnedBefore: Double?,
        val accPriorBefore: Double?,
    ) {
        /** Relative improvement of the learned log-loss over the prior's; 0 with no plays. */
        val improvement: Double get() = if (plays == 0 || logLossPrior <= 0.0) 0.0 else (logLossPrior - logLossLearned) / logLossPrior
        val learnedBetter: Boolean get() = plays >= MIN_PLAYS && logLossLearned < logLossPrior
    }

    private fun clipP(p: Double): Double = BrainMath.clip(p, P_EDGE, 1.0 - P_EDGE)

    /** Mean log-loss of [p] against [y] over `[from, to)`. */
    fun logLoss(p: DoubleArray, y: BooleanArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val q = clipP(p[i])
            sum += if (y[i]) -BrainMath.ln(q) else -BrainMath.ln(1.0 - q)
        }
        return sum / (to - from).toDouble()
    }

    /** Share of `[from, to)` where "prediction at or above [thr]" matches [y]. */
    fun accuracy(p: DoubleArray, thr: DoubleArray, y: BooleanArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var right = 0
        for (i in from until to) if ((p[i] >= thr[i]) == y[i]) right++
        return right.toDouble() / (to - from).toDouble()
    }

    /** The figures over the last [WINDOW] graded plays of [pred], and the accuracy a month before [nowMs]. */
    fun summarize(pred: Predictions, nowMs: Long): Summary {
        val n = pred.size
        val from = if (n > WINDOW) n - WINDOW else 0
        // The window that ended a month ago: the last WINDOW graded plays that started at or before nowMs - MONTH_MS.
        var endBefore = 0
        while (endBefore < n && pred.times[endBefore] <= nowMs - MONTH_MS) endBefore++
        val fromBefore = if (endBefore > WINDOW) endBefore - WINDOW else 0
        val hasBefore = endBefore - fromBefore >= MIN_PLAYS
        return Summary(
            plays = n - from,
            playsTotal = n,
            logLossLearned = logLoss(pred.learned, pred.finished, from, n),
            logLossPrior = logLoss(pred.prior, pred.finished, from, n),
            accLearned = accuracy(pred.learned, pred.threshold, pred.finished, from, n),
            accPrior = accuracy(pred.prior, pred.threshold, pred.finished, from, n),
            accLearnedBefore = if (hasBefore) accuracy(pred.learned, pred.threshold, pred.finished, fromBefore, endBefore) else null,
            accPriorBefore = if (hasBefore) accuracy(pred.prior, pred.threshold, pred.finished, fromBefore, endBefore) else null,
        )
    }

    /**
     * The gate's decision after one evaluation (5.12 point 8). Off at once when the learned weights are not better (or there are too few
     * graded plays); on only after [WINS_NEEDED] counted weeks of winning in a row; once on, it stays on while it keeps winning.
     * An evaluation less than [WEEK_MS] after the last counted one changes the streak only downward, never upward.
     */
    fun decide(prev: State, summary: Summary, nowMs: Long): State {
        if (!summary.learnedBetter) return State(false, 0, nowMs)
        val newWeek = prev.atMs == 0L || nowMs - prev.atMs >= WEEK_MS || nowMs < prev.atMs
        if (!newWeek) return prev
        val streak = if (prev.winStreak >= WINS_NEEDED) prev.winStreak else prev.winStreak + 1
        return State(prev.useLearned || streak >= WINS_NEEDED, streak, nowMs)
    }

    /** True when graded plays flagged as a control session, or not, have enough plays to be shown side by side (5.12 point 8). */
    const val CONTROL_MIN_PLAYS = 300

    /** Completion rate of a set of plays; `null` below [CONTROL_MIN_PLAYS]. */
    fun shownRate(finished: Int, total: Int): Double? = if (total < CONTROL_MIN_PLAYS) null else finished.toDouble() / total.toDouble()
}
