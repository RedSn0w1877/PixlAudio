package com.theveloper.pixelplay.brain

/**
 * List calibration across islands (docs/brain-spec.md, 6.5, 5.12 rule 7): a list that spans several islands
 * keeps the mix the listener's taste has, so the biggest island cannot absorb every slot.
 *
 * ```
 * P(c)         = W_c^0.75 normalised over islands, floor 0.03 (then normalised again)
 * Q            = the island mix of the list so far, smoothed 0.99 Q + 0.01 P
 * calibGain(i) = KL(P || Q_without) - KL(P || Q_with_i)
 * ```
 * `value(i)` of the list builder adds `kappa * calibGain(i)` with `kappa = 0.4`.
 */
class Calibration(islandWeights: DoubleArray) {
    private val p: DoubleArray
    private val counts = IntArray(islandWeights.size)
    private var total = 0

    init {
        val raw = DoubleArray(islandWeights.size)
        var sum = 0.0
        for (c in raw.indices) {
            val w = islandWeights[c]
            raw[c] = if (w > 0.0) BrainMath.exp(0.75 * BrainMath.ln(w)) else 0.0
            sum += raw[c]
        }
        val n = raw.size
        for (c in 0 until n) raw[c] = if (sum > 0.0) raw[c] / sum else 1.0 / n.toDouble()
        var sum2 = 0.0
        for (c in 0 until n) {
            if (raw[c] < FLOOR) raw[c] = FLOOR
            sum2 += raw[c]
        }
        for (c in 0 until n) raw[c] /= sum2
        p = raw
    }

    val islands: Int get() = p.size

    private fun q(c: Int, extra: Int): Double {
        val t = total + (if (extra >= 0) 1 else 0)
        val share = if (t == 0) p[c] else ((counts[c] + (if (c == extra) 1 else 0)).toDouble() / t.toDouble())
        return 0.99 * share + 0.01 * p[c]
    }

    private fun kl(extra: Int): Double {
        var s = 0.0
        for (c in p.indices) s += p[c] * BrainMath.ln(p[c] / q(c, extra))
        return s
    }

    /** What adding a track of island [island] does to the list's mix: positive when it moves Q towards P. */
    fun gain(island: Int): Double {
        if (island < 0 || island >= p.size) return 0.0
        return kl(-1) - kl(island)
    }

    fun add(island: Int) {
        if (island < 0 || island >= p.size) return
        counts[island]++
        total++
    }

    /** `KL(P || Q)` of the list so far (test 5: at most 0.15 over a day). */
    fun divergence(): Double = kl(-1)

    companion object {
        const val FLOOR = 0.03
        const val KAPPA = 0.4
    }
}

/**
 * How alike two tracks are, with embeddings and tags (docs/brain-spec.md, 5.3):
 * ```
 * sim(a, b) = 1                                  if the same rid
 *           = (0.6 * S + 0.4 * T) / (0.6 + 0.4 * hasT)
 * S         = cos_int8(e_a, e_b)  when both have an embedding of this version, else adj(a, b)
 * T, hasT   = tagdot(a, b), 1     when both have a tag vector, else 0, 0
 * ```
 * [Similarity.sim] (the `adj` branch alone) is what the stage 1 and 2a lists use and stays as it is.
 */
class SimilarityIndex(
    private val graph: Graph,
    private val embeddings: Embeddings?,
    private val tags: Map<Long, Tags.Vector>,
) : Similarity.Source {
    override fun sim(a: Long, aAid: Long, b: Long, bAid: Long): Double {
        if (a == b) return 1.0
        val ia = embeddings?.indexOf(a) ?: -1
        val ib = embeddings?.indexOf(b) ?: -1
        val s = if (ia >= 0 && ib >= 0 && embeddings != null) {
            BrainMath.clip(embeddings.cosAt(ia, ib), 0.0, 1.0)
        } else {
            Similarity.sim(graph, a, aAid, b, bAid)
        }
        val ta = tags[a]
        val tb = tags[b]
        if (ta != null && tb != null && !ta.isEmpty && !tb.isEmpty) {
            val t = Tags.tagdot(ta, tb)
            return (0.6 * s + 0.4 * t) / 1.0
        }
        return (0.6 * s) / 0.6
    }
}
