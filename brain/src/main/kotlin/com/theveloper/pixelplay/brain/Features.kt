package com.theveloper.pixelplay.brain

/** One track a list could contain, by id (the core never sees a `Song`). */
class Candidate(
    val rid: Long,
    /** Primary artist; 0 when unknown. */
    val aid: Long,
    /** A hash of the folded album name; 0 when unknown. */
    val albumKey: Long = 0L,
    /** True when the recording is in the listener's library (it plays from the library row). */
    val inLibrary: Boolean = false,
    /** Favourite or Spotify Liked Songs. */
    val liked: Boolean = false,
    /** True when the row came from an artist radio rather than a song radio. */
    val fromArtistRadio: Boolean = false,
    /** The listener's boost / reduce adjustments for it (5.11): `ln(product)`, 0 for none. */
    val boostLn: Double = 0.0,
    /** True when the row came from the top songs of an artist close to the seed's (arm SIM, 6.1). */
    val fromSimilarArtist: Boolean = false,
    /** Feature 10, `clip((B + 4) / 6, 0, 1)` of [RepeatActivation]; 0 for a track never played or an unknown history. */
    val actr: Double = 0.0,
)

/**
 * The feature vector of docs/brain-spec.md, 5.8 (D = 51). Stage 1 computes features 0, 1, 2, 3, 8,
 * 12-18, 22, 23, 25, 26, 36, 37 and 38; stage 2a adds 10 and 11 (repeat activation, given by the caller
 * in [Candidate.actr]); every other entry is exactly 0, because its input does not
 * exist yet or its prior weight is 0. A feature whose input is unknown is exactly 0.
 */
object Features {
    const val D = Ranker.D

    const val BIAS = 0
    const val TRACK_AFF = 1
    const val ARTIST_AFF = 2
    const val ARTIST_AFF_RECENT = 3
    const val LIKED = 8
    const val ACTR = 10
    const val ACTR_SQ = 11
    const val SINCE_LAST = 12
    const val UNHEARD_TRACK = 17
    const val UNHEARD_ARTIST = 18
    const val SESS_POS_SIM = 22
    const val SESS_NEG_SIM = 23
    const val PREV_COMPLETED = 25
    const val SKIP_RUN = 26
    const val ARM_AFF = 36
    const val RETRIEVAL = 37
    const val RADIO_RANK = 38

    private const val HOUR_MS = 3_600_000L

    /**
     * Fills [out] (length [D]) for one candidate. [arm] is the arm it is served under, [retrieval] its
     * percentile within that arm (feature 37), [bestRank] its best radio rank or 0. With [allNovel]
     * (lists that hold only unheard music, 5.12 rule 6) features 1 and 8 are zeroed.
     */
    fun compute(
        c: Candidate,
        arm: Int,
        retrieval: Double,
        bestRank: Int,
        affinity: Affinity.State,
        session: SessionState,
        graph: Graph,
        nowMs: Long,
        p0: Double,
        allNovel: Boolean,
        out: DoubleArray,
    ) {
        for (i in 0 until D) out[i] = 0.0
        out[BIAS] = 1.0
        if (!allNovel) {
            out[TRACK_AFF] = Affinity.aff(affinity, Family.TRACK, c.rid, nowMs, p0) / 3.0
            if (c.liked) out[LIKED] = 1.0
            // Repeat activation (5.6): where the listener's own point of fatigue sits (features 10 and 11).
            out[ACTR] = c.actr
            out[ACTR_SQ] = c.actr * c.actr
        }
        if (c.aid != 0L) {
            out[ARTIST_AFF] = Affinity.aff(affinity, Family.ARTIST, c.aid, nowMs, p0) / 3.0
            out[ARTIST_AFF_RECENT] = Affinity.affRecent(affinity, Family.ARTIST, c.aid, nowMs, p0) / 3.0
        }
        if (Affinity.isHeard(affinity, Family.TRACK, c.rid)) {
            val last = Affinity.lastMs(affinity, Family.TRACK, c.rid)
            val dt = if (nowMs > last) nowMs - last else 0L
            val bucket = if (dt < 2L * HOUR_MS) 0 else if (dt < 24L * HOUR_MS) 1 else if (dt < 168L * HOUR_MS) 2
            else if (dt < 720L * HOUR_MS) 3 else 4
            out[SINCE_LAST + bucket] = 1.0
        } else {
            out[UNHEARD_TRACK] = 1.0
        }
        if (c.aid != 0L && !Affinity.isHeard(affinity, Family.ARTIST, c.aid)) out[UNHEARD_ARTIST] = 1.0

        out[SESS_POS_SIM] = sessPosSim(c, session, graph)
        out[SESS_NEG_SIM] = sessNegSim(c, session, graph)
        if (session.prevCompleted) out[PREV_COMPLETED] = 1.0
        out[SKIP_RUN] = BrainMath.min(1.0, session.skipRun.toDouble() / 3.0)
        if (arm != Arm.NONE) out[ARM_AFF] = Affinity.aff(affinity, Family.ARM, arm.toLong(), nowMs, p0) / 3.0
        out[RETRIEVAL] = retrieval
        if (bestRank > 0) out[RADIO_RANK] = 1.0 / BrainMath.log2(2.0 + bestRank.toDouble())
    }

    /** Feature 22: `max over j of 0.7^j * sim(i, pos_j)`. */
    fun sessPosSim(c: Candidate, session: SessionState, graph: Graph): Double {
        var best = 0.0
        var w = 1.0
        for (j in 0 until session.posCount) {
            val v = w * Similarity.sim(graph, c.rid, c.aid, session.recentPos[j], session.recentPosAid[j])
            if (v > best) best = v
            w *= 0.7
        }
        return best
    }

    /** Feature 23: `max over j of h_j * 0.8^j * sim(i, neg_j)`. */
    fun sessNegSim(c: Candidate, session: SessionState, graph: Graph): Double {
        var best = 0.0
        var w = 1.0
        for (j in 0 until session.negCount) {
            val v = session.recentNegH[j] * w * Similarity.sim(graph, c.rid, c.aid, session.recentNeg[j], session.recentNegAid[j])
            if (v > best) best = v
            w *= 0.8
        }
        return best
    }
}
