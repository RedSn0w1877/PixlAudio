package com.theveloper.pixelplay.brain

/**
 * Personalised PageRank retrieval (docs/brain-spec.md, 6.1, stage 2), in the deterministic power-iteration form:
 *
 * ```
 * seeds S = an island's top 30 tracks by w_t ;  p0[t] = w_t / sum(w)
 * repeat 4 times:  p <- 0.20 * p0 + 0.80 * (A~ transposed) p ;  truncate p to its 3,000 largest entries (ties by node id)
 * ppr(t) = p[t] / sqrt(weightedDegree(t))
 * ```
 * `A~` is the row-normalised adjacency of the similarity graph. Every sum runs in ascending node id order, so
 * both platforms add the same numbers in the same order. The division by `sqrt(weightedDegree)` is the
 * counterweight to the popularity lean of radio lists (5.12, rule 4).
 */
object Ppr {
    const val RESTART = 0.20
    const val WALK = 0.80
    const val ITERATIONS = 4
    const val KEEP = 3000
    const val SEEDS = 30

    /** Nodes best first, with their score `ppr(t)`. */
    class Result(val ids: LongArray, val scores: DoubleArray) {
        val size: Int get() = ids.size
    }

    /**
     * [seedIds] and [seedWeights] are parallel; a seed with a weight of 0 or less is ignored. At most
     * [SEEDS] seeds count: the heaviest, ties to the smaller id.
     */
    fun run(graph: Graph, seedIds: LongArray, seedWeights: DoubleArray, top: Int = 300): Result {
        val order = seedIds.indices.filter { seedWeights[it] > 0.0 && seedIds[it] != 0L }.sortedWith { a, b ->
            val ka = BrainMath.orderKey(seedWeights[a])
            val kb = BrainMath.orderKey(seedWeights[b])
            if (ka != kb) (if (ka > kb) -1 else 1) else seedIds[a].compareTo(seedIds[b])
        }.take(SEEDS)
        if (order.isEmpty()) return Result(LongArray(0), DoubleArray(0))
        val p0 = java.util.TreeMap<Long, Double>()
        var total = 0.0
        for (i in order) total += seedWeights[i]
        for (i in order) p0[seedIds[i]] = (p0[seedIds[i]] ?: 0.0) + seedWeights[i] / total
        var p: java.util.TreeMap<Long, Double> = java.util.TreeMap(p0)
        for (iter in 0 until ITERATIONS) {
            val next = java.util.TreeMap<Long, Double>()
            for ((id, mass) in p0) next[id] = RESTART * mass
            for ((v, mass) in p) {
                val nb = graph.neighboursOf(v)
                if (nb.isEmpty()) continue
                val w = graph.weightsOf(v)
                var deg = 0.0
                for (x in w) deg += x
                if (deg <= 0.0) continue
                val share = WALK * mass / deg
                for (k in nb.indices) next[nb[k]] = (next[nb[k]] ?: 0.0) + share * w[k]
            }
            p = truncate(next)
        }
        val ids = ArrayList<Long>(p.size)
        val scores = HashMap<Long, Double>(p.size * 2)
        for ((id, mass) in p) {
            val deg = graph.weightedDegree(id)
            val score = if (deg > 0.0) mass / BrainMath.sqrt(deg) else mass
            ids.add(id)
            scores[id] = score
        }
        ids.sortWith { a, b ->
            val ka = BrainMath.orderKey(scores[a]!!)
            val kb = BrainMath.orderKey(scores[b]!!)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
        }
        val n = if (ids.size < top) ids.size else top
        return Result(LongArray(n) { ids[it] }, DoubleArray(n) { scores[ids[it]]!! })
    }

    private fun truncate(p: java.util.TreeMap<Long, Double>): java.util.TreeMap<Long, Double> {
        if (p.size <= KEEP) return p
        val ids = p.keys.sortedWith { a, b ->
            val ka = BrainMath.orderKey(p[a]!!)
            val kb = BrainMath.orderKey(p[b]!!)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
        }
        val out = java.util.TreeMap<Long, Double>()
        for (i in 0 until KEEP) out[ids[i]] = p[ids[i]]!!
        return out
    }
}
