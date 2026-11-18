package com.theveloper.pixelplay.brain

/**
 * Candidate retrieval for stage 1 (docs/brain-spec.md, 6.1): cross-seed co-occurrence over the radio
 * lists of the session's seeds.
 */
object Retrieval {

    /** One seed and its list, best row first: row `i` has rank `i + 1`. */
    class SeedList(
        val seedRid: Long,
        /** The seed's primary artist; 0 when unknown. */
        val seedAid: Long,
        val rids: LongArray,
        /** [Graph.RADIO] or [Graph.RELATED]: what kind of list it is, for the graph. */
        val edgeFactor: Double = Graph.RADIO,
    )

    class Entry(
        @JvmField var cooc: Double = 0.0,
        /** The best (smallest) rank in any seed's list. */
        @JvmField var bestRank: Int = 0,
        /** The index, in the seed list order, of the seed whose list ranks it best. */
        @JvmField var bestSeed: Int = -1,
        @JvmField var firstSeedAid: Long = 0L,
        @JvmField var severalArtists: Boolean = false,
    )

    /**
     * ```
     * cooc(c) = sum over seeds s whose list contains c of  w(s) / (10 + rank_s(c))
     * w(s)    = clip(1 + aff(primary artist of s) / 3, 0.1, 2)
     * cooc   *= 1.25 if c appears under seeds by two or more different artists
     * ```
     * The sum runs in the order of [seeds].
     */
    fun cooc(seeds: List<SeedList>, affinity: Affinity.State, nowMs: Long, p0: Double): HashMap<Long, Entry> {
        val out = HashMap<Long, Entry>()
        for (s in seeds.indices) {
            val seed = seeds[s]
            val a = if (seed.seedAid == 0L) 0.0 else Affinity.aff(affinity, Family.ARTIST, seed.seedAid, nowMs, p0)
            val w = BrainMath.clip(1.0 + a / 3.0, 0.1, 2.0)
            val seenInThisList = HashSet<Long>()
            for (i in seed.rids.indices) {
                val rid = seed.rids[i]
                if (rid == 0L || !seenInThisList.add(rid)) continue
                val rank = i + 1
                val e = out.getOrPut(rid) { Entry() }
                e.cooc += w / (10.0 + rank.toDouble())
                if (e.bestRank == 0 || rank < e.bestRank) {
                    e.bestRank = rank
                    e.bestSeed = s
                }
                if (e.bestSeed < 0) e.bestSeed = s
                if (seed.seedAid != 0L) {
                    if (e.firstSeedAid == 0L) e.firstSeedAid = seed.seedAid
                    else if (e.firstSeedAid != seed.seedAid) e.severalArtists = true
                }
            }
        }
        for (e in out.values) if (e.severalArtists) e.cooc *= 1.25
        return out
    }

    /**
     * Feature 37: a candidate's retrieval-score percentile within its arm,
     * `(candidates of that arm with a strictly smaller quantised score) / max(1, n - 1)`;
     * 0 for a candidate without a retrieval score. [arms] and [scores] are parallel.
     */
    fun percentiles(arms: IntArray, scores: DoubleArray): DoubleArray {
        val n = arms.size
        val keys = LongArray(n) { BrainMath.orderKey(scores[it]) }
        val out = DoubleArray(n)
        for (i in 0 until n) {
            if (scores[i] <= 0.0) continue
            var smaller = 0
            var inArm = 0
            for (j in 0 until n) {
                if (arms[j] != arms[i]) continue
                inArm++
                if (keys[j] < keys[i]) smaller++
            }
            val den = if (inArm - 1 > 1) inArm - 1 else 1
            out[i] = smaller.toDouble() / den.toDouble()
        }
        return out
    }
}
