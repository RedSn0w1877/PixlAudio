package com.theveloper.pixelplay.brain

/**
 * Training the ranker from the event log (docs/brain-spec.md, 5.9 "Training on history", 11 test 6).
 *
 * [Run] walks the events in ascending id, exactly as [Replay.rebuild] does for the affinity tables (and produces the same tables),
 * and beside them: rebuilds the session state with [Session.apply], computes the feature vector of each play from the state as of
 * just before it, has BOTH weight vectors (the prior `M0` and the learned model) predict it, records the two predictions
 * (the evaluation is prequential: a play is predicted before it is learned from), and then takes one [Ranker.update] step.
 *
 * What the old rows can and cannot give (5.9): the features that need a graph, islands, tags or repeat activation are 0 for a
 * replayed play, so their weights stay at the prior until live data arrives; features 37 and 38 come from the `retrQ` and `radioRank`
 * the event carried since stage 1; the origin one-hot (48-50) is switched on for imported and migrated rows in training only and is
 * 0 in every prediction, as it is when serving.
 *
 * The LIVE path (the app's recorder) computes the same vector with [featuresOf] from the state before the play is applied, so a
 * live update and a replay of the same play agree up to the session's expiry rule.
 */
object LearnReplay {
    /** Bumped when the features a replay computes, their definition or the update rule change: the learned model is rebuilt. */
    const val VERSION = 1

    /** The first of the three origin one-hot features (48 import Spotify, 49 import YouTube Music, 50 migrated history). */
    const val ORIGIN_BASE = 48

    /** A graded play is finished (`r = 1`) or skipped (`r <= 0.1`); anything between is training only. */
    const val SKIPPED_MAX = 0.1

    /** The local day of an event: its own timezone, or UTC for an import. */
    fun dayOf(e: PlayEvent, tMs: Long): Long = BrainMath.localDay(tMs, if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else e.tzOffsetMin)

    /**
     * The feature vector of play [e] from the state just before it (5.8), without the origin one-hot. [liked] says the track was a
     * favourite. Fills [out] (length [Ranker.D]).
     */
    fun featuresOf(e: PlayEvent, liked: Boolean, affinity: Affinity.State, session: SessionState, nowMs: Long, p0: Double, out: DoubleArray) {
        val c = Candidate(rid = e.rid, aid = e.primaryAid, inLibrary = true, liked = liked)
        val retrieval = if (e.retrQ >= 0) e.retrQ.toDouble() / 1000.0 else 0.0
        val bestRank = if (e.radioRank > 0) e.radioRank else 0
        Features.compute(c, e.arm, retrieval, bestRank, affinity, session, Graph.EMPTY, nowMs, p0, false, out)
    }

    /** True for a play that can be graded for the gate and the accuracy: it counted, was heard, and is a clear finish or a clear skip. */
    fun isGradedLabel(e: PlayEvent, g: Reward.Graded): Boolean =
        g.c > 0.0 && !e.has(EventFlags.NEVER_AUDIBLE) && (g.r >= 1.0 || g.r <= SKIPPED_MAX)

    /**
     * One live play into [model] (5.9, "Timing"): the same steps [run] takes for one event, from the state as it is before the play is
     * applied. The caller applies the tables and the session afterwards. Returns [Ranker.UPDATE_APPLIED], [Ranker.UPDATE_SKIPPED] or
     * [Ranker.UPDATE_RESET] (the model went back to its prior: the caller schedules a replay). Cost: one feature vector and 51
     * multiply-adds, no allocation but a 51-double vector.
     */
    fun applyLive(
        model: RankerModel,
        e: PlayEvent,
        g: Reward.Graded,
        liked: Boolean,
        affinity: Affinity.State,
        session: SessionState,
        nowMs: Long,
        /** The arm bandit and the novelty appetite (5.10); null leaves them out. Their update reads the tables as they are before the play. */
        posteriors: Posteriors? = null,
    ): Int {
        val t = if (e.startedAtMs > nowMs) nowMs else e.startedAtMs
        val x = DoubleArray(Ranker.D)
        val p0 = Affinity.p0(affinity, t)
        if (posteriors != null) {
            PosteriorUpdate.onPlay(posteriors, e, g, !Affinity.isHeard(affinity, Family.TRACK, e.rid), t, liveId(e.eventId, posteriors.lastEventId))
        }
        featuresOf(e, liked, affinity, session, t, p0, x)
        val originIndex = originIndexOf(e.origin)
        if (originIndex >= 0) x[originIndex] = 1.0
        return Ranker.applyEvent(model, x, g.r, g.c, Ranker.biasOf(p0), dayOf(e, t), liveId(e.eventId, model.lastEventId))
    }

    /**
     * The id a LIVE play is applied under. An event id carries the phone's clock (`startedAtMs << 16`), so one play written while the clock
     * was a day ahead would leave a watermark that every later, correctly stamped play sits below, and the learner would skip them all until
     * real time caught up. A live play is new by construction (the recorder stores it once, and a rebuild's catch-up skips what the replay
     * read), so when its id is not above the watermark it is applied under the next id after the watermark: the watermark only moves
     * forward, by one. A replay walks the log in id order and uses the real ids.
     */
    fun liveId(eventId: Long, watermark: Long): Long =
        if (eventId > watermark) eventId else if (watermark == Long.MAX_VALUE) watermark else watermark + 1L

    private fun originIndexOf(origin: Int): Int = when (origin) {
        Origin.IMPORT_SPOTIFY -> ORIGIN_BASE
        Origin.IMPORT_YTM -> ORIGIN_BASE + 1
        Origin.MIGRATED_HISTORY -> ORIGIN_BASE + 2
        else -> -1
    }

    class Result(
        /** The affinity tables, equal to [Replay.rebuild]'s. */
        val state: Affinity.State,
        val model: RankerModel,
        val predictions: Gate.Predictions,
        /** Events replayed. */
        val events: Int,
        /** The id of the newest event replayed: the watermark the live path catches up from. */
        val lastEventId: Long,
        /** The arm bandit and the novelty appetite, built by the same pass (plays after "Reset what I've learned" only). */
        val posteriors: Posteriors,
    )

    private class DoubleBuf(capacity: Int = 1024) {
        var a = DoubleArray(capacity)
        var n = 0
        fun add(v: Double) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun done(): DoubleArray = a.copyOf(n)
    }

    private class LongBuf(capacity: Int = 1024) {
        var a = LongArray(capacity)
        var n = 0
        fun add(v: Long) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun done(): LongArray = a.copyOf(n)
    }

    /**
     * Replays [events] (ascending `eventId`, rids already mapped through `track_merge`, as [Replay.rebuild] needs them). Every event
     * time is clamped to [nowMs]. [likedRids] are the favourites (feature 8); [tzNowMin] is the device's offset now, for the last
     * forgetting up to today. [start] lets a caller continue from a model (tests); null starts at the prior.
     */
    fun run(
        events: Iterator<PlayEvent>,
        facts: LibraryFacts,
        nowMs: Long,
        likedRids: Set<Long>,
        tzNowMin: Int = 0,
        /** Plays at or below this event id still build the tables and the session but are not predicted or learned from ("Reset what I've learned"). */
        learnAfterEventId: Long = Long.MIN_VALUE,
    ): Result {
        val affinity = Affinity.State()
        Replay.applyFacts(affinity, facts)
        val tracker = GradeTracker(affinity.completions)
        var session = SessionState()
        var model: RankerModel? = null
        var posteriors: Posteriors? = null
        val x = DoubleArray(Ranker.D)
        val times = LongBuf()
        val sessions = LongBuf()
        val finished = ArrayList<Boolean>()
        val pl = DoubleBuf()
        val pp = DoubleBuf()
        val thr = DoubleBuf()
        var count = 0
        var lastId = Long.MIN_VALUE
        while (events.hasNext()) {
            val e = events.next()
            val t = if (e.startedAtMs > nowMs) nowMs else e.startedAtMs
            val day = dayOf(e, t)
            if (Session.isExpired(session, t)) session = SessionState()
            Session.begin(session, t, e.startReason)
            val g = tracker.grade(e)
            if (e.eventId <= learnAfterEventId) {
                val novelOld = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
                Affinity.applyEvent(affinity, e, g, t)
                Session.apply(session, e, g, novelOld)
                count++
                lastId = e.eventId
                continue
            }
            val m = model ?: Ranker.newModel(day).also {
                it.lastEventId = if (learnAfterEventId == Long.MIN_VALUE) Long.MIN_VALUE else learnAfterEventId
                model = it
            }
            val p0 = Affinity.p0(affinity, t)
            featuresOf(e, e.rid in likedRids, affinity, session, t, p0, x)
            val bias = Ranker.biasOf(p0)
            Ranker.forget(m, day)
            if (isGradedLabel(e, g)) {
                times.add(t)
                sessions.add(e.sessionId)
                finished.add(g.r >= 1.0)
                pl.add(Ranker.predict(m, x, bias))
                pp.add(Ranker.predictPrior(x, p0))
                thr.add(BrainMath.clip(p0, 0.05, 0.95))
            }
            // Training only: the origin one-hot absorbs the bias of imported and migrated rows.
            val originIndex = originIndexOf(e.origin)
            if (originIndex >= 0) x[originIndex] = 1.0
            Ranker.applyEvent(m, x, g.r, g.c, bias, day, e.eventId)
            val novel = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
            val post = posteriors ?: Posteriors.newState(day).also {
                it.lastEventId = if (learnAfterEventId == Long.MIN_VALUE) Long.MIN_VALUE else learnAfterEventId
                posteriors = it
            }
            PosteriorUpdate.onPlay(post, e, g, novel, nowMs)
            Affinity.applyEvent(affinity, e, g, t)
            Session.apply(session, e, g, novel)
            count++
            lastId = e.eventId
        }
        affinity.drainTouched { _, _ -> }
        val finalModel = model ?: Ranker.newModel(BrainMath.localDay(nowMs, tzNowMin)).also {
            it.lastEventId = learnAfterEventId
        }
        Ranker.forget(finalModel, BrainMath.localDay(nowMs, tzNowMin))
        val fin = BooleanArray(finished.size) { finished[it] }
        val pred = Gate.Predictions(times.done(), sessions.done(), fin, pl.done(), pp.done(), thr.done())
        val finalPosteriors = posteriors ?: Posteriors.newState(BrainMath.localDay(nowMs, tzNowMin)).also {
            it.lastEventId = learnAfterEventId
        }
        PosteriorUpdate.advanceTo(finalPosteriors, BrainMath.localDay(nowMs, tzNowMin))
        return Result(affinity, finalModel, pred, count, lastId, finalPosteriors)
    }

    // ── Test 6 (11): learned weights against the frozen prior ──────────────────

    /** The most the interval may be resampled. */
    class Test6Report(
        /** Graded plays the comparison is over (the last [Gate.WINDOW]). */
        val plays: Int,
        val logLossLearned: Double,
        val logLossPrior: Double,
        /** `(prior - learned) / prior`. */
        val improvement: Double,
        /** 2.5 and 97.5 percentiles of the mean per-play log-loss gain over session resamples (positive: learned is better). */
        val gainLow: Double,
        val gainHigh: Double,
        /** At least 2,000 graded plays, a gain of at least 1% and an interval above 0. Otherwise the listener keeps the prior. */
        val passes: Boolean,
        /** False below 2,000 graded plays: the test does not apply and the answer is "keep the prior". */
        val enoughPlays: Boolean,
    )

    const val TEST6_MIN_PLAYS = 2000
    const val TEST6_MIN_IMPROVEMENT = 0.01

    fun test6(pred: Gate.Predictions, resamples: Int = 200, seed: Long = 1L): Test6Report {
        val n = pred.size
        val from = if (n > Gate.WINDOW) n - Gate.WINDOW else 0
        val plays = n - from
        val llL = Gate.logLoss(pred.learned, pred.finished, from, n)
        val llP = Gate.logLoss(pred.prior, pred.finished, from, n)
        val improvement = if (plays == 0 || llP <= 0.0) 0.0 else (llP - llL) / llP
        val enough = n >= TEST6_MIN_PLAYS
        var low = 0.0
        var high = 0.0
        if (plays > 0 && resamples > 0) {
            // Per-session sums of the per-play gain; resampling sessions keeps the plays of one sitting together.
            val groupOf = LinkedHashMap<Long, Int>()
            val sum = ArrayList<Double>()
            val cnt = ArrayList<Int>()
            for (i in from until n) {
                val gi = groupOf.getOrPut(pred.sessions[i]) { sum.add(0.0); cnt.add(0); sum.size - 1 }
                val pl = BrainMath.clip(pred.learned[i], Gate.P_EDGE, 1.0 - Gate.P_EDGE)
                val pr = BrainMath.clip(pred.prior[i], Gate.P_EDGE, 1.0 - Gate.P_EDGE)
                val lossL = if (pred.finished[i]) -BrainMath.ln(pl) else -BrainMath.ln(1.0 - pl)
                val lossP = if (pred.finished[i]) -BrainMath.ln(pr) else -BrainMath.ln(1.0 - pr)
                sum[gi] = sum[gi] + (lossP - lossL)
                cnt[gi] = cnt[gi] + 1
            }
            val groups = sum.size
            val rng = BrainHash.Rng(BrainHash.splitmix64(seed))
            val gains = DoubleArray(resamples)
            for (r in 0 until resamples) {
                var s = 0.0
                var c = 0
                for (g in 0 until groups) {
                    val pick = BrainMath.floorToLong(rng.uniform() * groups.toDouble()).toInt().coerceIn(0, groups - 1)
                    s += sum[pick]
                    c += cnt[pick]
                }
                gains[r] = if (c == 0) 0.0 else s / c.toDouble()
            }
            gains.sort()
            low = gains[BrainMath.floorToLong(0.025 * resamples.toDouble()).toInt().coerceIn(0, resamples - 1)]
            high = gains[BrainMath.floorToLong(0.975 * resamples.toDouble()).toInt().coerceIn(0, resamples - 1)]
        }
        return Test6Report(plays, llL, llP, improvement, low, high, enough && improvement >= TEST6_MIN_IMPROVEMENT && low > 0.0, enough)
    }
}
