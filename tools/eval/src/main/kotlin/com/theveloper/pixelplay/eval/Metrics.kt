package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Eval

/** Per-listener numbers of one replay. */
class UserScore(
    val id: String,
    val n: Int,
    val skipShare: Double,
    /** Log-loss of prior, learned, artist rate. */
    val ll: DoubleArray,
    val auc: DoubleArray,
    /** Learned minus prior, per-play log-loss gain: mean and its 95% interval over resampled sessions. */
    val gain: Double,
    val gainLow: Double,
    val gainHigh: Double,
    val artistHit: DoubleArray,
    val genreHit: DoubleArray,
    /** Share of the top 20 artists that were barely known (played at most [Replayer.RARE_PLAYS] times), per scorer. */
    val rare: DoubleArray = DoubleArray(3),
)

object Metrics {
    private const val EDGE = 1e-4

    fun ll(p: Double, y: Boolean): Double {
        val c = BrainMath.clip(p, EDGE, 1 - EDGE)
        return if (y) -BrainMath.ln(c) else -BrainMath.ln(1 - c)
    }

    fun logLoss(p: DoubleArray, y: BooleanArray): Double {
        if (p.isEmpty()) return 0.0
        var s = 0.0
        for (i in p.indices) s += ll(p[i], y[i])
        return s / p.size
    }

    fun auc(p: DoubleArray, y: BooleanArray): Double = Eval.auc(LongArray(p.size) { BrainMath.orderKey(p[it]) }, y)

    fun score(id: String, r: Run, resamples: Int = 200): UserScore {
        val y = r.finished
        var skips = 0
        for (b in y) if (!b) skips++
        // Session bootstrap of the per-play gain (learned better = positive).
        val groupOf = LinkedHashMap<Long, Int>()
        val sum = ArrayList<Double>()
        val cnt = ArrayList<Int>()
        for (i in y.indices) {
            val gi = groupOf.getOrPut(r.sessions[i]) { sum.add(0.0); cnt.add(0); sum.size - 1 }
            sum[gi] = sum[gi] + (ll(r.prior[i], y[i]) - ll(r.learned[i], y[i]))
            cnt[gi] = cnt[gi] + 1
        }
        val rng = BrainHash.Rng(BrainHash.splitmix64(BrainHash.fnv1a64(id)))
        val gains = DoubleArray(resamples)
        val g = sum.size
        for (k in 0 until resamples) {
            var s = 0.0
            var c = 0
            for (j in 0 until g) {
                val pick = (rng.uniform() * g).toInt().coerceIn(0, g - 1)
                s += sum[pick]; c += cnt[pick]
            }
            gains[k] = if (c == 0) 0.0 else s / c
        }
        gains.sort()
        val llv = doubleArrayOf(logLoss(r.prior, y), logLoss(r.learned, y), logLoss(r.artistRate, y))
        return UserScore(
            id, y.size, if (y.isEmpty()) 0.0 else skips.toDouble() / y.size, llv,
            doubleArrayOf(auc(r.prior, y), auc(r.learned, y), auc(r.artistRate, y)),
            llv[0] - llv[1], gains[(0.025 * resamples).toInt()], gains[(0.975 * resamples).toInt().coerceAtMost(resamples - 1)],
            DoubleArray(3) { if (r.artistPoints == 0) 0.0 else r.artistHits[it].toDouble() / r.artistPoints },
            DoubleArray(3) { if (r.genrePoints == 0) 0.0 else r.genreHits[it].toDouble() / r.genrePoints },
            DoubleArray(3) { if (r.artistPoints == 0) 0.0 else r.rareShare[it] / r.artistPoints },
        )
    }

    fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** A statistic over users and its 95% percentile interval over resampled users. */
    class Ci(val value: Double, val low: Double, val high: Double) {
        fun crossesZero() = low <= 0.0 && high >= 0.0
        override fun toString(): String = "${f(value)} [${f(low)}, ${f(high)}]"
    }

    fun bootstrap(v: DoubleArray, stat: (DoubleArray) -> Double = ::median, resamples: Int = 2000, seed: Long = 7L): Ci {
        if (v.isEmpty()) return Ci(Double.NaN, Double.NaN, Double.NaN)
        val rng = BrainHash.Rng(BrainHash.splitmix64(seed))
        val out = DoubleArray(resamples)
        val buf = DoubleArray(v.size)
        for (k in 0 until resamples) {
            for (j in v.indices) buf[j] = v[(rng.uniform() * v.size).toInt().coerceIn(0, v.size - 1)]
            out[k] = stat(buf)
        }
        out.sort()
        return Ci(stat(v), out[(0.025 * resamples).toInt()], out[(0.975 * resamples).toInt().coerceAtMost(resamples - 1)])
    }

    fun mean(v: DoubleArray): Double = if (v.isEmpty()) Double.NaN else v.sum() / v.size

    fun f(v: Double, digits: Int = 4): String = String.format(java.util.Locale.ROOT, "%.${digits}f", v)
}
