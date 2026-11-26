package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Eval
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Features
import com.theveloper.pixelplay.brain.GradeTracker
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.LearnReplay
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.RankerModel
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.brain.SessionState
import com.theveloper.pixelplay.brain.Tags

/** The hyperparameters a replay runs under. [DEFAULT] is exactly what the engine ships. */
class Config(
    /** The prior means, index 0 (the bias) ignored: it is always the live `logit(p0)`. */
    val m0: DoubleArray = DoubleArray(Ranker.D) { Ranker.m0(it) },
    val trust: Reward.Trust = Reward.Trust.DEFAULT,
    val stepClip: Double = Ranker.STEP_CLIP,
    val hFloor: Double = Ranker.H_FLOOR,
    /** Feed the atlas tag vectors (features 4 and 24, the tag affinities and the session's tag penalty), as live scoring does. */
    val useTags: Boolean = true,
    /** Experiments only: an extra feature written into [EXTRA_SLOT] after [Features.compute] (0 none). */
    val extra: Int = 0,
) {
    fun withM0(i: Int, v: Double): Config = Config(m0.copyOf().also { it[i] = v }, trust, stepClip, hFloor, useTags, extra)
    fun withExtra(x: Int): Config = Config(m0, trust, stepClip, hFloor, useTags, x)

    companion object {
        val DEFAULT = Config()
    }
}

/** What one replay of one listener measured, on its held-out plays. */
class Run(
    val sessions: LongArray,
    val finished: BooleanArray,
    val prior: DoubleArray,
    val learned: DoubleArray,
    val artistRate: DoubleArray,
    /** Next-artist and next-genre hits at 20, per scorer (prior, learned, artist rate), and the number of points. */
    val artistHits: IntArray,
    val artistPoints: Int,
    val genreHits: IntArray,
    val genrePoints: Int,
    val model: RankerModel,
    /** Per scorer: the summed share of the top 20 artists the listener had played at most [Replayer.RARE_PLAYS] times (over [artistPoints]). */
    val rareShare: DoubleArray = DoubleArray(3),
    /** The composed lists of [Compose], when the run was given a choice prior. */
    val composed: Compose.Stats? = null,
)

object Replayer {
    /** The held-out part of a listener: the last 30% of the events by time (prequential before and inside it). */
    const val HOLD_OUT = 0.30
    const val TOP_K = 20

    /** Tracks per artist kept as candidates for the next-artist ranking. */
    private const val PER_ARTIST = 3

    /** An artist played at most this many times before is "barely known" for the discovery share of the top 20. */
    const val RARE_PLAYS = 2

    /** ... or not played for 30 days. */
    const val COLD_MS = 30L * 86_400_000L

    /** Slot the experiments write into: 9 (in_playlists) is never computed in this data. */
    const val EXTRA_SLOT = 9

    fun extra(cfg: Config, affinity: Affinity.State, aid: Long, t: Long, x: DoubleArray) {
        if (cfg.extra == 0 || aid == 0L) return
        val k = affinity.counter(Family.ARTIST, aid) ?: return
        val s = affinity.sum(Family.ARTIST)
        val tot = Affinity.posRecent(s, t) + Affinity.negRecent(s, t)
        val totL = Affinity.pos(s, t) + Affinity.neg(s, t)
        val w = Affinity.posRecent(k, t) + Affinity.negRecent(k, t)
        val wL = Affinity.pos(k, t) + Affinity.neg(k, t)
        x[EXTRA_SLOT] = when (cfg.extra) {
            1 -> if (tot < 1.0) 0.0 else com.theveloper.pixelplay.brain.BrainMath.ln(1.0 + 100.0 * w / tot) / com.theveloper.pixelplay.brain.BrainMath.ln(101.0)
            2 -> if (totL < 1.0) 0.0 else com.theveloper.pixelplay.brain.BrainMath.ln(1.0 + 100.0 * wL / totL) / com.theveloper.pixelplay.brain.BrainMath.ln(101.0)
            3 -> minOf(1.0, com.theveloper.pixelplay.brain.BrainMath.ln(1.0 + w) / com.theveloper.pixelplay.brain.BrainMath.ln(64.0))
            else -> 0.0
        }
    }

    fun cutOf(n: Int): Int = n - (n * HOLD_OUT).toInt()

    /**
     * Replays [l] in event order exactly as [LearnReplay.run] does (session, grade, features from the state before the play, predict,
     * forget, update, then the tables and the session), plus the tag parts the live path adds when a track has tags, and records the
     * three predictions of every labelled held-out play. With [ranking] it also ranks the listener's known artists and genres before
     * every held-out play that changes artist.
     */
    fun run(l: Listener, cfg: Config, ranking: Boolean = false, choice: DoubleArray? = null): Run {
        val events = l.events
        val cut = cutOf(events.size)
        val affinity = Affinity.State()
        val tracker = GradeTracker(affinity.completions, cfg.trust)
        var session = SessionState()
        val m0 = cfg.m0.copyOf().also { it[0] = 0.0 }
        val q0 = Ranker.priorPrecision()
        val model = RankerModel(m0.copyOf(), q0.copyOf(), m0, LearnReplay.dayOf(events[0], events[0].startedAtMs))
        // [Compose]: a second learner started from the choice prior, on the same events.
        val mc = choice?.copyOf()?.also { it[0] = 0.0 }
        val choiceModel = if (mc != null) RankerModel(mc.copyOf(), q0.copyOf(), mc, LearnReplay.dayOf(events[0], events[0].startedAtMs)) else null
        val composed = if (mc != null) Compose.Stats() else null
        val x = DoubleArray(Ranker.D)
        val sess = ArrayList<Long>()
        val fin = ArrayList<Boolean>()
        val pp = ArrayList<Double>()
        val pl = ArrayList<Double>()
        val pa = ArrayList<Double>()
        val artistHits = IntArray(3)
        val genreHits = IntArray(3)
        var artistPoints = 0
        var genrePoints = 0
        // Ranking pool: per artist its last few distinct tracks.
        val pool = LinkedHashMap<Long, LongArray>()
        val poolN = HashMap<Long, Int>()
        val plays = HashMap<Long, Int>()
        val rare = DoubleArray(3)
        val y = DoubleArray(Ranker.D)
        var prevAid = 0L
        for ((i, e) in events.withIndex()) {
            val t = e.startedAtMs
            val day = LearnReplay.dayOf(e, t)
            if (Session.isExpired(session, t)) session = SessionState()
            Session.begin(session, t, e.startReason)
            val g = tracker.grade(e)
            val p0 = Affinity.p0(affinity, t)
            val tags = if (cfg.useTags) l.tags[e.rid] else null
            Features.compute(Candidate(rid = e.rid, aid = e.primaryAid, inLibrary = true, tags = tags), Arm.NONE, 0.0, 0, affinity, session, Graph.EMPTY, t, p0, false, x)
            extra(cfg, affinity, e.primaryAid, t, x)
            val bias = Ranker.biasOf(p0)
            Ranker.forget(model, day)
            if (choiceModel != null) Ranker.forget(choiceModel, day)
            if (i >= cut) {
                if (ranking && e.primaryAid != prevAid && pool.isNotEmpty()) {
                    artistPoints++
                    val hasTop = tags != null && !tags.isEmpty
                    if (hasTop) genrePoints++
                    rank(l, cfg, pool, poolN, affinity, session, t, p0, bias, model, m0, y, e, tags, artistHits, genreHits, plays, rare)
                    if (composed != null) Compose.measure(l, cfg, mc!!, pool, poolN, plays, affinity, session, t, p0, bias, model, choiceModel!!, m0, e, composed)
                }
                if (l.label[i] != Listener.UNKNOWN && g.c > 0.0) {
                    sess.add(e.sessionId)
                    fin.add(l.label[i] == Listener.FINISHED)
                    pl.add(Ranker.predict(model, x, bias))
                    pp.add(priorP(m0, bias, x, q0))
                    pa.add(Eval.ARTIST_RATE.score(e, affinity, t))
                }
            }
            Ranker.applyEvent(model, x, g.r, g.c, bias, day, e.eventId, cfg.stepClip, cfg.hFloor)
            if (choiceModel != null) Ranker.applyEvent(choiceModel, x, g.r, g.c, bias, day, e.eventId, cfg.stepClip, cfg.hFloor)
            val novel = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
            Affinity.applyEvent(affinity, e, g, t)
            if (tags != null) Affinity.applyTags(affinity, e, g, tags, t)
            Session.apply(session, e, g, novel)
            if (tags != null) Session.applyTags(session, tags, g.h, g.isCompletion)
            if (ranking) {
                addToPool(pool, poolN, e.primaryAid, e.rid)
                if (e.primaryAid != 0L) plays[e.primaryAid] = (plays[e.primaryAid] ?: 0) + 1
            }
            prevAid = e.primaryAid
        }
        return Run(
            sess.toLongArray(), fin.toBooleanArray(), pp.toDoubleArray(), pl.toDoubleArray(), pa.toDoubleArray(),
            artistHits, artistPoints, genreHits, genrePoints, model, rare, composed,
        )
    }

    /** The frozen prior's `p`: weights [m0] with the live intercept, the prior precisions. */
    fun priorP(m0: DoubleArray, bias: Double, x: DoubleArray, q0: DoubleArray): Double = Ranker.p(Ranker.zBiased(m0, bias, x), x, q0)

    private fun addToPool(pool: LinkedHashMap<Long, LongArray>, poolN: HashMap<Long, Int>, aid: Long, rid: Long) {
        if (aid == 0L) return
        val a = pool.getOrPut(aid) { LongArray(PER_ARTIST) }
        val n = poolN[aid] ?: 0
        for (k in 0 until minOf(n, PER_ARTIST)) if (a[k] == rid) return
        for (k in PER_ARTIST - 1 downTo 1) a[k] = a[k - 1]
        a[0] = rid
        poolN[aid] = minOf(n + 1, PER_ARTIST)
    }

    private fun rank(
        l: Listener, cfg: Config, pool: Map<Long, LongArray>, poolN: Map<Long, Int>, affinity: Affinity.State, session: SessionState,
        t: Long, p0: Double, bias: Double, model: RankerModel, m0: DoubleArray, y: DoubleArray, e: com.theveloper.pixelplay.brain.PlayEvent,
        tags: Tags.Vector?, artistHits: IntArray, genreHits: IntArray, plays: Map<Long, Int>, rare: DoubleArray,
    ) {
        val q0 = Ranker.priorPrecision()
        val nA = pool.size
        val aids = LongArray(nA)
        val best = Array(3) { DoubleArray(nA) { -1.0 } }
        val genreBest = Array(3) { HashMap<Int, Double>() }
        var k = 0
        for ((aid, rids) in pool) {
            aids[k] = aid
            val ar = artistRate(affinity, aid, t)
            for (j in 0 until (poolN[aid] ?: 0)) {
                val rid = rids[j]
                val tv = if (cfg.useTags) l.tags[rid] else null
                Features.compute(Candidate(rid = rid, aid = aid, inLibrary = true, tags = tv), Arm.NONE, 0.0, 0, affinity, session, Graph.EMPTY, t, p0, false, y)
                extra(cfg, affinity, aid, t, y)
                val s = doubleArrayOf(priorP(m0, bias, y, q0), Ranker.predict(model, y, bias), ar)
                for (m in 0 until 3) {
                    if (s[m] > best[m][k]) best[m][k] = s[m]
                    if (tv != null && !tv.isEmpty) {
                        val gm = genreBest[m]
                        val cur = gm[tv.top]
                        if (cur == null || s[m] > cur) gm[tv.top] = s[m]
                    }
                }
            }
            k++
        }
        for (m in 0 until 3) {
            val idx = (0 until nA).sortedWith(compareByDescending<Int> { best[m][it] }.thenBy { aids[it] }).take(TOP_K)
            if (idx.any { aids[it] == e.primaryAid }) artistHits[m]++
            if (idx.isNotEmpty()) rare[m] += idx.count { (plays[aids[it]] ?: 0) <= RARE_PLAYS || t - Affinity.lastMs(affinity, Family.ARTIST, aids[it]) > COLD_MS }.toDouble() / idx.size
            if (tags != null && !tags.isEmpty) {
                val top = genreBest[m].entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(TOP_K)
                if (top.any { it.key == tags.top }) genreHits[m]++
            }
        }
    }

    /** True when this loop, with tags off and the shipped constants, leaves the model bit for bit where [LearnReplay.run] leaves it. */
    fun matchesEngine(l: Listener): Boolean {
        val mine = run(l, Config(useTags = false)).model
        val nowMs = l.events.last().startedAtMs
        val engine = LearnReplay.run(l.events.iterator(), com.theveloper.pixelplay.brain.LibraryFacts.EMPTY, nowMs, emptySet(), 0).model
        // LearnReplay forgets once more up to nowMs at the end; ours stops at the last play's day, which is the same day.
        for (i in 0 until Ranker.D) if (mine.m[i] != engine.m[i] || mine.q[i] != engine.q[i]) return false
        return true
    }

    /** [Eval.ARTIST_RATE] for an artist id: the artist's plain finish rate, shrunk by one pseudo-play. */
    fun artistRate(affinity: Affinity.State, aid: Long, t: Long): Double {
        val k = affinity.counter(Family.ARTIST, aid) ?: return 0.5
        val p = Affinity.pos(k, t)
        val n = Affinity.neg(k, t)
        return (p + 0.5) / (p + n + 1.0)
    }
}
