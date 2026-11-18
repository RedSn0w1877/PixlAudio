package com.theveloper.pixelplay.brain

/**
 * The evaluation harness (docs/brain-spec.md, 11), pure: it takes events and facts and returns a
 * report. Stage 2a runs test 1 (skip prediction, [skipPrediction]) and test 5 (list health over 30
 * simulated daily builds, [listHealth]). Test 7 is the golden files. Tests 2, 3 and 4 need the
 * stage 2b arms and are not here; test 6 is stage 3.
 *
 * Protocol: prequential. Every event is first predicted (from the state built by the events before it)
 * and only then applied, so nothing after a play can influence its prediction. The hold-out is the last
 * 8 weeks of the history, or its last 15% of events when the history is shorter than 16 weeks. Intervals
 * are bootstrap percentiles over resampled sessions, drawn with SplitMix64 so the report is the same on
 * both platforms.
 *
 * Arithmetic is integer where it can be: AUC is an exact count of won pairs, so it does not depend on
 * how a platform rounds a sum of doubles.
 */
object Eval {

    const val WEEK_MS = 7L * 86_400_000L

    class Config(
        val holdOutMs: Long = 8L * WEEK_MS,
        val holdOutFraction: Double = 0.15,
        /** Calibration bins. */
        val bins: Int = 10,
        /** Session resamples for the intervals. */
        val resamples: Int = 200,
        val seed: Long = 1L,
    )

    /**
     * Another predictor to compare with, given the state before the event (read only). A higher score
     * means "will be finished". The legacy engine's score is one of these, supplied by the app.
     */
    fun interface Baseline {
        fun score(e: PlayEvent, affinity: Affinity.State, nowMs: Long): Double
    }

    /** Built in: the artist's plain finish rate so far (pos / (pos + neg), shrunk by one pseudo-play). */
    val ARTIST_RATE = Baseline { e, affinity, now ->
        val k = affinity.counter(Family.ARTIST, e.primaryAid)
        if (k == null) 0.5 else {
            val p = Affinity.pos(k, now)
            val n = Affinity.neg(k, now)
            (p + 0.5) / (p + n + 1.0)
        }
    }

    /** Built in: how often the track was finished before (the popularity-of-this-listener baseline). */
    val TRACK_COMPLETIONS = Baseline { e, affinity, _ -> (affinity.completions[e.rid] ?: 0).toDouble() }

    class Labeled(
        /** True: the play was finished (`r = 1`). False: it was skipped (`r <= 0.1`). */
        val finished: Boolean,
        val sessionId: Long,
        /** The brain's prior scorer: `p` of 5.7 with the weights `M0`. */
        val brain: Double,
        /** One per baseline, in the order they were given. */
        val baselines: DoubleArray,
    )

    class SkipReport(
        /** Events before the cut (training) and after it (held out, graded). */
        val trainEvents: Int,
        val heldOutEvents: Int,
        val labeled: List<Labeled>,
        val finished: Int,
        val skipped: Int,
        val brainAuc: Double,
        val baselineNames: List<String>,
        val baselineAuc: DoubleArray,
        /** `brainAuc - baselineAuc[i]`, and the 2.5 and 97.5 percentiles of that difference over session resamples. */
        val delta: DoubleArray,
        val deltaLow: DoubleArray,
        val deltaHigh: DoubleArray,
        /** Expected calibration error of the brain's `p` over [Config.bins] bins. */
        val calibrationError: Double,
    )

    /** Where the hold-out starts: the index of its first event. [events] are in ascending `eventId`. */
    fun cutIndex(events: List<PlayEvent>, config: Config = Config()): Int {
        if (events.isEmpty()) return 0
        val first = events[0].startedAtMs
        val last = events[events.size - 1].startedAtMs
        if (last - first >= 2L * config.holdOutMs) {
            val from = last - config.holdOutMs
            var i = 0
            while (i < events.size && events[i].startedAtMs < from) i++
            return i
        }
        val held = BrainMath.floorToLong(events.size.toDouble() * config.holdOutFraction).toInt()
        return events.size - (if (held < 1) 1 else held)
    }

    /** Test 1: skip prediction on the held-out plays. */
    fun skipPrediction(
        events: List<PlayEvent>,
        facts: LibraryFacts,
        config: Config = Config(),
        baselines: List<Pair<String, Baseline>> = listOf("artistRate" to ARTIST_RATE, "trackCompletions" to TRACK_COMPLETIONS),
    ): SkipReport {
        val cut = cutIndex(events, config)
        val affinity = Affinity.State()
        Replay.applyFacts(affinity, facts)
        val likedRids = HashSet<Long>()
        for (f in facts.liked) likedRids.add(f.rid)
        val tracker = GradeTracker(affinity.completions)
        var session = SessionState()
        val graph = Graph.EMPTY
        val x = DoubleArray(Features.D)
        val labeled = ArrayList<Labeled>()
        for (i in events.indices) {
            val e = events[i]
            if (Session.isExpired(session, e.startedAtMs)) session = SessionState()
            Session.begin(session, e.startedAtMs, e.startReason)
            var brainP = 0.0
            var others: DoubleArray? = null
            if (i >= cut) {
                val now = e.startedAtMs
                val p0 = Affinity.p0(affinity, now)
                val c = Candidate(rid = e.rid, aid = e.primaryAid, inLibrary = true, liked = e.rid in likedRids)
                Features.compute(c, Arm.NONE, 0.0, 0, affinity, session, graph, now, p0, false, x)
                brainP = Ranker.p(Ranker.z(Ranker.weights(p0), x), x)
                others = DoubleArray(baselines.size) { baselines[it].second.score(e, affinity, now) }
            }
            val novel = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
            val g = tracker.grade(e)
            Affinity.applyEvent(affinity, e, g, e.startedAtMs)
            Session.apply(session, e, g, novel)
            if (i >= cut && g.c > 0.0 && !e.has(EventFlags.NEVER_AUDIBLE)) {
                val finished = g.r >= 1.0
                val skipped = g.r <= 0.1
                if (finished || skipped) labeled.add(Labeled(finished, e.sessionId, brainP, others!!))
            }
        }
        var pos = 0
        for (l in labeled) if (l.finished) pos++
        val names = baselines.map { it.first }
        val brainKeys = LongArray(labeled.size) { BrainMath.orderKey(labeled[it].brain) }
        val brainAuc = auc(brainKeys, BooleanArray(labeled.size) { labeled[it].finished })
        val baseAuc = DoubleArray(baselines.size)
        val baseKeys = Array(baselines.size) { b -> LongArray(labeled.size) { BrainMath.orderKey(labeled[it].baselines[b]) } }
        val labels = BooleanArray(labeled.size) { labeled[it].finished }
        for (b in baselines.indices) baseAuc[b] = auc(baseKeys[b], labels)
        val delta = DoubleArray(baselines.size) { brainAuc - baseAuc[it] }
        val low = DoubleArray(baselines.size)
        val high = DoubleArray(baselines.size)
        if (labeled.isNotEmpty() && pos > 0 && pos < labeled.size && config.resamples > 0) {
            val (lo, hi) = bootstrap(labeled, brainKeys, baseKeys, labels, config)
            for (b in baselines.indices) {
                low[b] = lo[b]
                high[b] = hi[b]
            }
        }
        return SkipReport(
            trainEvents = cut,
            heldOutEvents = events.size - cut,
            labeled = labeled,
            finished = pos,
            skipped = labeled.size - pos,
            brainAuc = brainAuc,
            baselineNames = names,
            baselineAuc = baseAuc,
            delta = delta,
            deltaLow = low,
            deltaHigh = high,
            calibrationError = calibrationError(labeled, config.bins),
        )
    }

    /**
     * The area under the ROC curve of [keys] against [finished], as an exact count of won pairs
     * (ties count half). 0.5 when one of the classes is empty.
     */
    fun auc(keys: LongArray, finished: BooleanArray): Double {
        val n = keys.size
        var pos = 0L
        for (f in finished) if (f) pos++
        val neg = n.toLong() - pos
        if (pos == 0L || neg == 0L) return 0.5
        val order = (0 until n).sortedWith { a, b -> if (keys[a] < keys[b]) -1 else if (keys[a] > keys[b]) 1 else 0 }
        var wins2 = 0L // twice the number of won pairs, so a tie is an integer
        var negBelow = 0L
        var i = 0
        while (i < n) {
            var j = i
            var gp = 0L
            var gn = 0L
            while (j < n && keys[order[j]] == keys[order[i]]) {
                if (finished[order[j]]) gp++ else gn++
                j++
            }
            wins2 += 2L * gp * negBelow + gp * gn
            negBelow += gn
            i = j
        }
        return wins2.toDouble() / (2.0 * pos.toDouble() * neg.toDouble())
    }

    /** Expected calibration error: the weighted mean of |mean prediction - finish rate| over equal-width bins. */
    fun calibrationError(labeled: List<Labeled>, bins: Int): Double {
        if (labeled.isEmpty() || bins <= 0) return 0.0
        val sumP = DoubleArray(bins)
        val sumY = DoubleArray(bins)
        val count = IntArray(bins)
        for (l in labeled) {
            var b = BrainMath.floorToLong(l.brain * bins.toDouble()).toInt()
            if (b < 0) b = 0
            if (b >= bins) b = bins - 1
            sumP[b] += l.brain
            sumY[b] += if (l.finished) 1.0 else 0.0
            count[b]++
        }
        var err = 0.0
        for (b in 0 until bins) {
            if (count[b] == 0) continue
            err += count[b].toDouble() * BrainMath.abs(sumP[b] / count[b].toDouble() - sumY[b] / count[b].toDouble())
        }
        return err / labeled.size.toDouble()
    }

    private fun bootstrap(
        labeled: List<Labeled>,
        brainKeys: LongArray,
        baseKeys: Array<LongArray>,
        labels: BooleanArray,
        config: Config,
    ): Pair<DoubleArray, DoubleArray> {
        // Sessions in order of first appearance; rows of one session stay together.
        val bySession = LinkedHashMap<Long, ArrayList<Int>>()
        for (i in labeled.indices) bySession.getOrPut(labeled[i].sessionId) { ArrayList() }.add(i)
        val groups = bySession.values.toList()
        val rng = BrainHash.Rng(BrainHash.splitmix64(config.seed))
        val nb = baseKeys.size
        val diffs = Array(nb) { DoubleArray(config.resamples) }
        for (r in 0 until config.resamples) {
            val idx = ArrayList<Int>(labeled.size)
            for (g in groups.indices) {
                val pick = groups[BrainMath.floorToLong(rng.uniform() * groups.size.toDouble()).toInt().coerceIn(0, groups.size - 1)]
                idx.addAll(pick)
            }
            val l = BooleanArray(idx.size) { labels[idx[it]] }
            val a = auc(LongArray(idx.size) { brainKeys[idx[it]] }, l)
            for (b in 0 until nb) diffs[b][r] = a - auc(LongArray(idx.size) { baseKeys[b][idx[it]] }, l)
        }
        val lo = DoubleArray(nb)
        val hi = DoubleArray(nb)
        for (b in 0 until nb) {
            diffs[b].sort()
            lo[b] = diffs[b][BrainMath.floorToLong(0.025 * config.resamples.toDouble()).toInt().coerceIn(0, config.resamples - 1)]
            hi[b] = diffs[b][BrainMath.floorToLong(0.975 * config.resamples.toDouble()).toInt().coerceIn(0, config.resamples - 1)]
        }
        return lo to hi
    }

    // ── Test 5: list health ──────────────────────────────────────────────────

    /** The world a list-health simulation runs in. */
    class HealthWorld(
        val library: List<Candidate>,
        val novel: List<Candidate>,
        val seeds: List<Retrieval.SeedList>,
        /** How likely the listener finishes a track, 0..1, by rid. A track not listed is finished half the time. */
        val finishRate: Map<Long, Double>,
    )

    class HealthReport(
        val days: Int,
        /** Distinct recordings over all recordings of the Daily Mixes of the period. Target at least 0.60. */
        val distinctRatio: Double,
        /** The most any artist held of any list (a share of it). Target at most 0.10. */
        val maxArtistShare: Double,
        /** The largest gap between a list's share of new music and the `nu` asked for. Target at most 0.05. */
        val maxNoveltyGap: Double,
        /** The most recordings any day's two mixes share. Target 0. */
        val maxSameDayOverlap: Int,
        val shortestList: Int,
        /** The mean finish rate the world gives the Daily Mix's tracks: how good the music was, by the listener's own taste. */
        val meanFinishRate: Double = 0.0,
    )

    /**
     * Test 5: [days] simulated daily builds of a Daily Mix and a Your Mix from [world], each followed by
     * the listener playing the Daily Mix (finishing a track with the probability the world gives it), so
     * the state moves the way it would. The served log feeds the repetition budget and the same-day rule.
     */
    fun listHealth(
        world: HealthWorld,
        start: Affinity.State,
        days: Int = 30,
        nowMs: Long = 1_760_000_000_000L,
        tzOffsetMin: Int = 0,
        salt: Long = 7L,
        nuMultiplier: Double = 1.0,
        jitter: Double = MixPlanner.JITTER,
        servedMax: Double = Repetition.MAX_PENALTY,
    ): HealthReport {
        val affinity = start
        val tracker = GradeTracker(affinity.completions)
        val served = ArrayList<Repetition.Served>()
        val rng = BrainHash.Rng(BrainHash.splitmix64(salt xor 0x5EEDL))
        val aidOf = HashMap<Long, Long>()
        val novelOf = HashSet<Long>()
        for (c in world.library) aidOf[c.rid] = c.aid
        for (c in world.novel) {
            aidOf[c.rid] = c.aid
            novelOf.add(c.rid)
        }
        val builds = ArrayList<LongArray>()
        var maxShare = 0.0
        var maxGap = 0.0
        var maxOverlap = 0
        var shortest = Int.MAX_VALUE
        var eventSeq = 0L
        var rateSum = 0.0
        var rateCount = 0
        for (day in 0 until days) {
            val t = nowMs + day * Repetition.DAY_MS
            val pool = ArrayList<Candidate>(world.library.size + world.novel.size)
            pool.addAll(world.library)
            pool.addAll(world.novel)
            val times = Repetition.timesServed(served, t)
            val dailyReq = MixPlanner.Request(
                kind = MixPlanner.KIND_DAILY, candidates = pool, seeds = world.seeds, nowMs = t, tzOffsetMin = tzOffsetMin,
                nuMultiplier = nuMultiplier, salt = salt, servedTimes = times,
                excludedRids = Repetition.takenOnDay(served, t, tzOffsetMin, Surface.DAILY_MIX), jitter = jitter, servedMax = servedMax,
            )
            val daily = MixPlanner.plan(dailyReq, affinity)
            val dailyRids = LongArray(daily.picks.size) { daily.picks[it].rid }
            served.add(Repetition.Served(Surface.DAILY_MIX, t, dailyRids))
            val yourReq = MixPlanner.Request(
                kind = MixPlanner.KIND_YOUR, candidates = pool, seeds = world.seeds, nowMs = t + 1, tzOffsetMin = tzOffsetMin,
                nuMultiplier = nuMultiplier, salt = salt, servedTimes = times,
                excludedRids = Repetition.takenOnDay(served, t + 1, tzOffsetMin, Surface.YOUR_MIX), jitter = jitter, servedMax = servedMax,
            )
            val your = MixPlanner.plan(yourReq, affinity)
            val yourRids = LongArray(your.picks.size) { your.picks[it].rid }
            served.add(Repetition.Served(Surface.YOUR_MIX, t + 1, yourRids))
            builds.add(dailyRids)
            for (r in dailyRids) {
                rateSum += world.finishRate[r] ?: 0.5
                rateCount++
            }
            for ((plan, kind) in listOf(daily to MixPlanner.KIND_DAILY, your to MixPlanner.KIND_YOUR)) {
                if (plan.picks.isEmpty()) continue
                if (plan.picks.size < shortest) shortest = plan.picks.size
                val aids = LongArray(plan.picks.size) { aidOf[plan.picks[it].rid] ?: 0L }
                val share = Repetition.maxArtistShare(aids)
                if (share > maxShare) maxShare = share
                var novelCount = 0
                for (p in plan.picks) if (p.rid in novelOf) novelCount++
                val gap = BrainMath.abs(novelCount.toDouble() / plan.picks.size.toDouble() - MixPlanner.nuOf(kind, nuMultiplier))
                if (gap > maxGap) maxGap = gap
            }
            val mine = HashSet<Long>()
            for (r in dailyRids) mine.add(r)
            var overlap = 0
            for (r in yourRids) if (r in mine) overlap++
            if (overlap > maxOverlap) maxOverlap = overlap
            // The listener plays the Daily Mix.
            var at = t + 6L * 3_600_000L
            for (rid in dailyRids) {
                val rate = world.finishRate[rid] ?: 0.5
                val done = rng.uniform() < rate
                val duration = 200_000
                eventSeq++
                val e = PlayEvent(
                    eventId = (at shl 16) or (eventSeq and 0xFFFFL), origin = Origin.LIVE, rid = rid, primaryAid = aidOf[rid] ?: 0L,
                    startedAtMs = at, playedMs = if (done) duration else 6_000, durationMs = duration,
                    startReason = StartReason.TAP, endReason = if (done) EndReason.TRACK_DONE else EndReason.NEXT,
                    surface = Surface.DAILY_MIX, sessionId = at / 1_000L, arm = Arm.NONE,
                )
                val g = tracker.grade(e)
                Affinity.applyEvent(affinity, e, g, at)
                at += if (done) duration.toLong() else 6_000L
            }
        }
        return HealthReport(
            days = days,
            distinctRatio = Repetition.distinctRatio(builds),
            maxArtistShare = maxShare,
            maxNoveltyGap = maxGap,
            maxSameDayOverlap = maxOverlap,
            shortestList = if (shortest == Int.MAX_VALUE) 0 else shortest,
            meanFinishRate = if (rateCount == 0) 0.0 else rateSum / rateCount.toDouble(),
        )
    }
}
