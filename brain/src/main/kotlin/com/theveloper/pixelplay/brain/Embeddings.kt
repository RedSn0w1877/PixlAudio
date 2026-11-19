package com.theveloper.pixelplay.brain

/**
 * Graph embeddings (docs/brain-spec.md, 5.3): one Int8[64] vector per node, found by FastRP over the
 * similarity graph. The vectors of one [version] can be compared with each other and with nothing else.
 *
 * Similarity is an exact integer dot product over precomputed integer norms, so both platforms get the
 * same number from the same bytes.
 */
class Embeddings(
    val version: Int,
    /** Node ids, ascending. */
    private val ids: LongArray,
    /** `ids.size * DIM` signed bytes. */
    private val data: ByteArray,
) {
    private val norms2 = IntArray(ids.size) { i ->
        var s = 0
        for (j in 0 until DIM) {
            val v = data[i * DIM + j].toInt()
            s += v * v
        }
        s
    }

    val size: Int get() = ids.size

    fun idAt(index: Int): Long = ids[index]

    fun indexOf(id: Long): Int {
        var lo = 0
        var hi = ids.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ids[mid]
            if (v == id) return mid
            if (v < id) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    fun has(id: Long): Boolean = indexOf(id) >= 0

    /** The 64 bytes of [id], or null when it has none (or its vector is all zero). */
    fun vector(id: Long): ByteArray? {
        val i = indexOf(id)
        if (i < 0 || norms2[i] == 0) return null
        return data.copyOfRange(i * DIM, i * DIM + DIM)
    }

    /** `cos_int8`: the exact integer dot product over the integer norms; 0 when either has no direction. */
    fun cos(a: Long, b: Long): Double {
        val i = indexOf(a)
        val j = indexOf(b)
        if (i < 0 || j < 0) return 0.0
        return cosAt(i, j)
    }

    fun cosAt(i: Int, j: Int): Double {
        if (norms2[i] == 0 || norms2[j] == 0) return 0.0
        var dot = 0
        for (k in 0 until DIM) dot += data[i * DIM + k].toInt() * data[j * DIM + k].toInt()
        return dot.toDouble() / (BrainMath.sqrt(norms2[i].toDouble()) * BrainMath.sqrt(norms2[j].toDouble()))
    }

    /** The raw table, for storage: ids ascending and the bytes in the same order. */
    fun idsCopy(): LongArray = ids.copyOf()

    fun dataCopy(): ByteArray = data.copyOf()

    companion object {
        const val DIM = 64

        /** The same embedding table, read back from storage. [ids] must be ascending. */
        fun of(version: Int, ids: LongArray, data: ByteArray): Embeddings {
            require(data.size == ids.size * DIM) { "embedding table size" }
            return Embeddings(version, ids, data)
        }

        /** `cos_int8` of two stored vectors. */
        fun cos(a: ByteArray, b: ByteArray): Double {
            var dot = 0
            var na = 0
            var nb = 0
            for (k in 0 until DIM) {
                val x = a[k].toInt()
                val y = b[k].toInt()
                dot += x * y
                na += x * x
                nb += y * y
            }
            if (na == 0 || nb == 0) return 0.0
            return dot.toDouble() / (BrainMath.sqrt(na.toDouble()) * BrainMath.sqrt(nb.toDouble()))
        }
    }
}

/**
 * FastRP over the similarity graph (5.3). No training loop and no randomness beyond a hash:
 *
 * ```
 * R[v][j] in { +sqrt(3), 0, -sqrt(3) }:  h = splitmix64(fnv1a64(nodeId) xor j);  h mod 6 == 0 -> +,  == 1 -> -,  else 0
 * A~ = D^-1 A (row-normalised, neighbours in ascending id order)
 * N1 = A~ R;  N2 = A~ N1;  N3 = A~ N2;   each row L2-normalised after each pass
 * E  = N2 + N3;   e = round_half_up(127 * E / ||E||)  as Int8
 * ```
 * `fnv1a64(nodeId)` is the hash of the node id's 8 bytes, little endian. A node whose neighbours are all
 * invisible to it (a row of zeros) ends with the zero vector, which compares as "no embedding".
 */
object FastRp {
    const val VERSION = 1
    private val SQRT3 = BrainMath.sqrt(3.0)

    /** The sparse projection entry `R[node][j]`. */
    fun projection(nodeHash: Long, j: Int): Double {
        val h = BrainHash.splitmix64(nodeHash xor j.toLong())
        val m = java.lang.Long.remainderUnsigned(h, 6L).toInt()
        return if (m == 0) SQRT3 else if (m == 1) -SQRT3 else 0.0
    }

    fun nodeHash(id: Long): Long {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((id ushr (8 * i)) and 0xFF).toByte()
        return BrainHash.fnv1a64(b)
    }

    fun embed(graph: Graph): Embeddings {
        val ids = graph.nodeIds()
        val n = ids.size
        val d = Embeddings.DIM
        if (n == 0) return Embeddings.of(VERSION, ids, ByteArray(0))
        // Neighbour indices and row-normalised weights, once.
        val nbr = arrayOfNulls<IntArray>(n)
        val wts = arrayOfNulls<DoubleArray>(n)
        val index = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) index[ids[i]] = i
        for (i in 0 until n) {
            val nb = graph.neighboursOf(ids[i])
            val w = graph.weightsOf(ids[i])
            var total = 0.0
            for (k in nb.indices) total += w[k]
            var kept = 0
            val tmpN = IntArray(nb.size)
            val tmpW = DoubleArray(nb.size)
            for (k in nb.indices) {
                val idx = index[nb[k]] ?: continue
                tmpN[kept] = idx
                tmpW[kept] = if (total > 0.0) w[k] / total else 0.0
                kept++
            }
            nbr[i] = tmpN.copyOf(kept)
            wts[i] = tmpW.copyOf(kept)
        }
        var cur = DoubleArray(n * d)
        for (i in 0 until n) {
            val h = nodeHash(ids[i])
            for (j in 0 until d) cur[i * d + j] = projection(h, j)
        }
        val sum = DoubleArray(n * d)
        for (pass in 1..3) {
            val next = DoubleArray(n * d)
            for (i in 0 until n) {
                val nb = nbr[i]!!
                val w = wts[i]!!
                for (k in nb.indices) {
                    val base = nb[k] * d
                    val wk = w[k]
                    for (j in 0 until d) next[i * d + j] += wk * cur[base + j]
                }
                var norm = 0.0
                for (j in 0 until d) norm += next[i * d + j] * next[i * d + j]
                if (norm > 0.0) {
                    val inv = 1.0 / BrainMath.sqrt(norm)
                    for (j in 0 until d) next[i * d + j] *= inv
                }
            }
            cur = next
            if (pass >= 2) for (x in 0 until n * d) sum[x] += cur[x]
        }
        val out = ByteArray(n * d)
        for (i in 0 until n) {
            var norm = 0.0
            for (j in 0 until d) norm += sum[i * d + j] * sum[i * d + j]
            if (norm <= 0.0) continue
            val inv = 127.0 / BrainMath.sqrt(norm)
            for (j in 0 until d) {
                var q = BrainMath.floorToLong(sum[i * d + j] * inv + 0.5)
                if (q > 127L) q = 127L
                if (q < -127L) q = -127L
                out[i * d + j] = q.toInt().toByte()
            }
        }
        return Embeddings.of(VERSION, ids, out)
    }
}
