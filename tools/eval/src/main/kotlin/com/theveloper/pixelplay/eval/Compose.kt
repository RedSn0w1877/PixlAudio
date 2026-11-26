package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Features
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.ListBuilder
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.RankerModel
import com.theveloper.pixelplay.brain.SessionState

/**
 * The composed list (docs/brain-spec.md 6.5, two lanes): before each held-out play that changes artist, the listener's known tracks
 * go through [ListBuilder.build] itself, the way StationPlanner hands them over, and the list it returns is measured.
 *
 * What stands in for the app's lanes: this data has no library and no unheard catalogue, so a track's lane is its artist's state.
 * An artist played at most [Replayer.RARE_PLAYS] times, or not in 30 days, is the discovery lane (`novel`, the candidates the
 * novelty share is about); every other artist is the familiar lane. The novelty share is the session's own `nu` (autoplay's rule,
 * [SessionState.nuSession]). Tracks played in the last 4 hours are left out, as the planner does. Lists cap one track per artist,
 * so the first 20 picks are 20 distinct artists and hit@20 means what it means in the plain ranking; the genre hit takes the
 * first 20 distinct top genres of a [GENRE_COUNT]-pick list. Graph and flow are empty (no co-plays, no audio features here).
 */
object Compose {
    /** The lists measured, each with its familiar-lane scorer and its discovery-lane scorer. */
    const val SHIPPED_PRIOR = 0
    const val SHIPPED_LEARNED = 1
    const val ARTIST_RATE = 2
    /** Familiar by the choice prior (M0 + Ranker.choiceShift), discovery by the finish prior: what ships while the gate has the prior on. */
    const val TWO_LANE_PRIOR = 3
    /** Familiar by the choice prior, never learned, discovery by the learned finish model (an alternative, not shipped). */
    const val TWO_LANE_FIXED = 4
    /** Familiar by the learned finish mean plus Ranker.choiceShift, discovery by the learned finish model: what ships with the learned weights on. */
    const val TWO_LANE_OFFSET = 5
    /** Familiar by a second learner started from the choice prior on the same events (an alternative, not shipped: it needs a second model). */
    const val TWO_LANE_LEARNER = 6
    const val LISTS = 7

    val NAMES = arrayOf(
        "shipped, prior", "shipped, learned", "artist rate", "two lanes, prior", "two lanes, fixed choice",
        "two lanes, choice offset", "two lanes, choice learner",
    )

    const val GENRE_COUNT = 60

    /** Candidates kept per lane before the build (by lane z, plus the best by lane p for the opener): far more than 60 picks need. */
    private const val KEEP_Z = 250
    private const val KEEP_P = 40

    private const val FOUR_HOURS_MS = 4L * 3_600_000L

    class Stats {
        val artistHits = IntArray(LISTS)
        val genreHits = IntArray(LISTS)
        val cold = DoubleArray(LISTS)
        var artistPoints = 0
        var genrePoints = 0
    }

    private class Row(val rid: Long, val aid: Long, val novel: Boolean, val lastMs: Long, val top: Int, val z: DoubleArray, val p: DoubleArray)

    fun measure(
        l: Listener, cfg: Config, mc: DoubleArray, pool: Map<Long, LongArray>, poolN: Map<Long, Int>, plays: Map<Long, Int>,
        affinity: Affinity.State, session: SessionState, t: Long, p0: Double, bias: Double,
        model: RankerModel, choiceModel: RankerModel, m0: DoubleArray, e: PlayEvent, out: Stats,
    ) {
        val q0 = Ranker.priorPrecision()
        val y = DoubleArray(Ranker.D)
        val rows = ArrayList<Row>()
        // The shipped choice scorer: the finish weights plus Ranker.choiceShift, for the prior and for the learned mean.
        val mcPrior = Ranker.choiceWeights(m0)
        val mOff = Ranker.choiceWeights(model.m)
        val qOffset = model.q
        for ((aid, rids) in pool) {
            val cold = (plays[aid] ?: 0) <= Replayer.RARE_PLAYS || t - Affinity.lastMs(affinity, Family.ARTIST, aid) > Replayer.COLD_MS
            val ar = Replayer.artistRate(affinity, aid, t)
            for (j in 0 until (poolN[aid] ?: 0)) {
                val rid = rids[j]
                val last = Affinity.lastMs(affinity, Family.TRACK, rid)
                if (last != 0L && t - last < FOUR_HOURS_MS) continue
                val tv = if (cfg.useTags) l.tags[rid] else null
                Features.compute(Candidate(rid = rid, aid = aid, inLibrary = !cold, tags = tv), Arm.NONE, 0.0, 0, affinity, session, Graph.EMPTY, t, p0, false, y)
                Replayer.extra(cfg, affinity, aid, t, y)
                val zPrior = Ranker.zBiased(m0, bias, y)
                val zLearned = Ranker.zBiased(model.m, bias, y)
                val zChoice = Ranker.zBiased(mcPrior, bias, y)
                val zOff = Ranker.zBiased(mOff, bias, y)
                val zCl = Ranker.zBiased(choiceModel.m, bias, y)
                val z = doubleArrayOf(zPrior, zLearned, BrainMath.logit(BrainMath.clip(ar, 1e-4, 1 - 1e-4)), zChoice, zOff, zCl)
                val p = doubleArrayOf(
                    Ranker.p(zPrior, y, q0), Ranker.p(zLearned, y, model.q), ar, Ranker.p(zChoice, y, q0), Ranker.p(zOff, y, qOffset),
                    Ranker.p(zCl, y, choiceModel.q),
                )
                rows.add(Row(rid, aid, cold, last, if (tv != null && !tv.isEmpty) tv.top else -1, z, p))
            }
        }
        if (rows.isEmpty()) return
        out.artistPoints++
        val nextTags = if (cfg.useTags) l.tags[e.rid] else null
        val nextTop = if (nextTags != null && !nextTags.isEmpty) nextTags.top else -1
        if (nextTop >= 0) out.genrePoints++
        val tz = if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else e.tzOffsetMin
        val nu = BrainMath.clip(session.nuSession, 0.0, 1.0)
        for (list in 0 until LISTS) {
            // Score index (into Row.z / Row.p) of the familiar lane and of the discovery lane.
            val (fam, dis) = when (list) {
                SHIPPED_PRIOR -> 0 to 0
                SHIPPED_LEARNED -> 1 to 1
                ARTIST_RATE -> 2 to 2
                TWO_LANE_PRIOR -> 3 to 0
                TWO_LANE_FIXED -> 3 to 1
                TWO_LANE_OFFSET -> 4 to 1
                else -> 5 to 1
            }
            val keep = HashSet<Int>()
            for (lane in 0..1) {
                val idx = rows.indices.filter { rows[it].novel == (lane == 1) }
                val s = if (lane == 1) dis else fam
                idx.sortedByDescending { rows[it].z[s] }.take(KEEP_Z).forEach { keep.add(it) }
                idx.sortedByDescending { rows[it].p[s] }.take(KEEP_P).forEach { keep.add(it) }
            }
            val scored = keep.sorted().map { k ->
                val r = rows[k]
                val s = if (r.novel) dis else fam
                ListBuilder.Scored(
                    c = Candidate(rid = r.rid, aid = r.aid, inLibrary = !r.novel),
                    arm = Arm.NONE, z = r.z[dis], p = r.p[dis], novel = r.novel, bestRank = 0, bestSeed = -1, newestSeedRank = 0,
                    lastMs = r.lastMs, retrQ = 0, zChoice = r.z[s], pChoice = r.p[s],
                )
            }
            val tops = HashMap<Long, Int>()
            for (k in keep) tops[rows[k].rid] = rows[k].top
            val picks = ListBuilder.build(
                ListBuilder.Request(count = GENRE_COUNT, prior = emptyList(), nu = nu, tieSeed = e.eventId, nowMs = t, tzOffsetMin = tz, maxPerArtist = 1),
                scored, Graph.EMPTY,
            )
            val first = picks.take(Replayer.TOP_K)
            if (first.any { it.scored.c.aid == e.primaryAid }) out.artistHits[list]++
            if (first.isNotEmpty()) out.cold[list] += first.count { it.scored.novel }.toDouble() / first.size
            if (nextTop >= 0) {
                val genres = LinkedHashSet<Int>()
                for (pk in picks) {
                    val g = tops[pk.scored.c.rid] ?: -1
                    if (g >= 0) genres.add(g)
                    if (genres.size >= Replayer.TOP_K) break
                }
                if (nextTop in genres) out.genreHits[list]++
            }
        }
    }
}
