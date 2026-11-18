package com.theveloper.pixelplay.brain

/**
 * The similarity graph (docs/brain-spec.md, 3.6): one sparse undirected weighted graph over recording
 * ids and artist ids. Stage 1 builds it in memory, when a pool is built, from the radio lists of the
 * session's seeds plus one edge from every track to its primary artist. Nothing is persisted.
 *
 * Each node keeps its [MAX_EDGES] heaviest edges (ties: the smaller neighbour id). When one pair gets
 * edges of several kinds, the weight is the largest.
 */
class Graph private constructor(
    private val neighbours: HashMap<Long, LongArray>,
    private val weights: HashMap<Long, DoubleArray>,
    private val primaryArtist: HashMap<Long, Long>,
) {
    /** The primary artist of a track node; 0 when unknown. */
    fun artistOf(rid: Long): Long = primaryArtist[rid] ?: 0L

    val nodeCount: Int get() = neighbours.size

    /** Neighbour ids of [node], ascending. Empty when the node is unknown. */
    fun neighboursOf(node: Long): LongArray = neighbours[node] ?: EMPTY_IDS

    fun weightsOf(node: Long): DoubleArray = weights[node] ?: EMPTY_WEIGHTS

    private fun weightIn(node: Long, other: Long): Double {
        val ids = neighbours[node] ?: return 0.0
        var lo = 0
        var hi = ids.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ids[mid]
            if (v == other) return weights[node]!![mid]
            if (v < other) lo = mid + 1 else hi = mid - 1
        }
        return 0.0
    }

    /** The weight of the edge between [a] and [b]: kept by either end. 0 when there is none. */
    fun edgeWeight(a: Long, b: Long): Double = BrainMath.max(weightIn(a, b), weightIn(b, a))

    /** `0.5 * max over common neighbours n of min(w(a, n), w(b, n))`, over the edges each end kept. */
    fun viaCommonNeighbour(a: Long, b: Long): Double {
        val na = neighbours[a] ?: return 0.0
        val nb = neighbours[b] ?: return 0.0
        val wa = weights[a]!!
        val wb = weights[b]!!
        var best = 0.0
        var i = 0
        var j = 0
        while (i < na.size && j < nb.size) {
            val x = na[i]
            val y = nb[j]
            if (x == y) {
                val m = BrainMath.min(wa[i], wb[j])
                if (m > best) best = m
                i++
                j++
            } else if (x < y) i++ else j++
        }
        return 0.5 * best
    }

    class Builder {
        private val edges = HashMap<Long, HashMap<Long, Double>>()
        private val primaryArtist = HashMap<Long, Long>()

        private fun put(a: Long, b: Long, w: Double) {
            if (a == b || a == 0L || b == 0L) return
            val ma = edges.getOrPut(a) { HashMap() }
            val old = ma[b]
            if (old == null || w > old) ma[b] = w
            val mb = edges.getOrPut(b) { HashMap() }
            val oldB = mb[a]
            if (oldB == null || w > oldB) mb[a] = w
        }

        /**
         * One list: [seed] to the row at rank k (k = 1 for the first row of [rids]) with weight
         * `factor / log2(2 + k)`. [factor] is [RADIO], [RELATED] or [CHIP].
         */
        fun addList(seed: Long, rids: LongArray, factor: Double): Builder {
            for (i in rids.indices) put(seed, rids[i], factor / BrainMath.log2(2.0 + (i + 1).toDouble()))
            return this
        }

        /** A track and its primary artist: an edge of weight [TRACK_ARTIST], and what "same artist" reads. */
        fun addTrack(rid: Long, primaryAid: Long): Builder {
            if (rid == 0L || primaryAid == 0L) return this
            if (!primaryArtist.containsKey(rid)) primaryArtist[rid] = primaryAid
            put(rid, primaryAid, TRACK_ARTIST)
            return this
        }

        fun build(): Graph {
            val neighbours = HashMap<Long, LongArray>(edges.size * 2)
            val weights = HashMap<Long, DoubleArray>(edges.size * 2)
            for ((node, map) in edges) {
                var ids = map.keys.toLongArray()
                ids.sort()
                if (ids.size > MAX_EDGES) {
                    // Keep the heaviest; ties go to the smaller neighbour id. Order by a quantised key.
                    val order = ids.sortedWith { x, y ->
                        val kx = BrainMath.orderKey(map[x]!!)
                        val ky = BrainMath.orderKey(map[y]!!)
                        if (kx != ky) (if (kx > ky) -1 else 1) else (if (x < y) -1 else if (x > y) 1 else 0)
                    }
                    ids = LongArray(MAX_EDGES) { order[it] }
                    ids.sort()
                }
                neighbours[node] = ids
                weights[node] = DoubleArray(ids.size) { map[ids[it]]!! }
            }
            return Graph(neighbours, weights, HashMap(primaryArtist))
        }
    }

    companion object {
        const val MAX_EDGES = 64

        /** Radio seed to a row of its list. */
        const val RADIO = 1.0

        /** Related-tab "You might also like". */
        const val RELATED = 0.8

        /** A tuning-chip radio row (stage 2). */
        const val CHIP = 0.6

        const val TRACK_ARTIST = 0.5

        private val EMPTY_IDS = LongArray(0)
        private val EMPTY_WEIGHTS = DoubleArray(0)

        val EMPTY: Graph = Builder().build()
    }
}

/**
 * How alike two tracks are, in 0..1 (docs/brain-spec.md, 5.3). Stage 1 has no embeddings and no tags,
 * so this is the `adj` branch alone: 0.8 for the same primary artist, the edge weight between the two,
 * or half the weakest link through a common neighbour.
 *
 * Scale to remember: 0.8 same artist; 0.63 a seed and the first row of its radio; 0.30 its 8th row;
 * at most 0.25 between two rows of one radio. So a threshold of 0.5 or 0.6 means "same primary
 * artist", and 0.3 means "same artist, or among the first 8 rows of a seed's radio".
 */
object Similarity {
    const val SAME_ARTIST = 0.8

    fun sim(graph: Graph, a: Long, aArtist: Long, b: Long, bArtist: Long): Double {
        if (a == b) return 1.0
        var best = if (aArtist != 0L && aArtist == bArtist) SAME_ARTIST else 0.0
        val direct = BrainMath.min(1.0, graph.edgeWeight(a, b))
        if (direct > best) best = direct
        val via = graph.viaCommonNeighbour(a, b)
        if (via > best) best = via
        return best
    }

    /** [sim] with each track's primary artist read from the graph. */
    fun sim(graph: Graph, a: Long, b: Long): Double = sim(graph, a, graph.artistOf(a), b, graph.artistOf(b))
}
