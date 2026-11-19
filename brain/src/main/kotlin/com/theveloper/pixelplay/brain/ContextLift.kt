package com.theveloper.pixelplay.brain

/**
 * Context (docs/brain-spec.md, 5.10): four dayparts (05-11, 11-17, 17-22, 22-05 local) times weekday and weekend make eight buckets `b`; the
 * keys are the listener's islands.
 *
 * ```
 * n[k][b] += c * r, decayed with H = 120 days ;  N[b] = sum_k n[k][b] ;  P(k) = sum_b n[k][b] / sum_b N[b]
 * lift(k | b) = clip( ln( (n[k][b] + 20 * P(k)) / (N[b] + 20) ) - ln P(k), -1.5, 1.5 )   ;  0 unless the bucket has at least 50 plays
 * ```
 *
 * The table is a pure function of the plays and of now: [Builder] reads the log once, so nothing here needs its own persistence. An imported
 * play has no timezone; its hour is read with the device's offset now (the caller resolves it: spec, "unless the listener sets a home
 * timezone").
 *
 * The dayparts here are the spec's. The Home plan's own daypart ([HomePlanner.daypart], stage 2) cuts morning at 12, not 11; the two agree
 * except for the hour 11, and each is used only for what it was written for (see the stage 3 handoff).
 */
object ContextLift {
    const val BUCKETS = 8

    /** Half-life of the counts, in days. */
    const val HALF_LIFE_DAYS = 120.0

    /** The smoothing mass of the lift formula. */
    const val SMOOTH = 20.0

    const val LIFT_CLIP = 1.5

    /** A bucket with fewer plays than this has no lift: 0 for every island. */
    const val MIN_PLAYS = 50

    const val MORNING = 0
    const val DAY = 1
    const val EVENING = 2
    const val NIGHT = 3

    /** 05-11 morning, 11-17 day, 17-22 evening, 22-05 night, by the local hour. */
    fun daypart(startedAtMs: Long, tzOffsetMin: Int): Int {
        val hour = BrainMath.floorMod(BrainMath.floorDiv(startedAtMs + tzOffsetMin * 60_000L, 3_600_000L), 24L).toInt()
        return when {
            hour >= 5 && hour < 11 -> MORNING
            hour >= 11 && hour < 17 -> DAY
            hour >= 17 && hour < 22 -> EVENING
            else -> NIGHT
        }
    }

    /** `daypart + 4 * weekend`: 0..7. */
    fun bucket(startedAtMs: Long, tzOffsetMin: Int): Int =
        daypart(startedAtMs, tzOffsetMin) + if (BrainMath.isWeekend(startedAtMs, tzOffsetMin)) 4 else 0

    fun isWeekendBucket(bucket: Int): Boolean = bucket >= 4

    fun daypartOfBucket(bucket: Int): Int = bucket % 4

    /** The counts of every island in every bucket, and what the lift reads from them. */
    class Table(
        /** Island ids, ascending. */
        val islandIds: IntArray,
        /** `n[k][b]` by index in [islandIds]. */
        private val n: Array<DoubleArray>,
        /** `N[b]`. */
        private val total: DoubleArray,
        /** How many plays each bucket holds (not decayed). */
        val plays: IntArray,
    ) {
        private val grand: Double = run {
            var s = 0.0
            for (b in 0 until BUCKETS) s += total[b]
            s
        }

        fun indexOf(islandId: Int): Int {
            var lo = 0
            var hi = islandIds.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = islandIds[mid]
                if (v == islandId) return mid
                if (v < islandId) lo = mid + 1 else hi = mid - 1
            }
            return -1
        }

        /** True when the bucket has enough plays for a lift to mean anything. */
        fun hasLift(bucket: Int): Boolean = bucket in 0 until BUCKETS && plays[bucket] >= MIN_PLAYS

        /** `lift(k | b)`: 0 for an island the table does not know, a bucket under [MIN_PLAYS] plays, or an island with no counts at all. */
        fun lift(islandId: Int, bucket: Int): Double {
            if (!hasLift(bucket)) return 0.0
            val k = indexOf(islandId)
            if (k < 0 || grand <= 0.0) return 0.0
            var row = 0.0
            for (b in 0 until BUCKETS) row += n[k][b]
            val pk = row / grand
            if (!(pk > 0.0)) return 0.0
            val v = BrainMath.ln((n[k][bucket] + SMOOTH * pk) / (total[bucket] + SMOOTH)) - BrainMath.ln(pk)
            return BrainMath.clip(v, -LIFT_CLIP, LIFT_CLIP)
        }

        /**
         * The island with the largest `W_c * exp(lift)` in [bucket] (6.2, the daypart mix), among [candidates] (island id and its weight `W_c`);
         * ties to the lower id. -1 when [candidates] is empty or the bucket has no lift yet.
         */
        fun leader(bucket: Int, candidates: List<Pair<Int, Double>>): Int {
            if (!hasLift(bucket)) return -1
            var best = -1
            var bestScore = 0.0
            for ((id, weight) in candidates.sortedBy { it.first }) {
                val score = weight * BrainMath.exp(lift(id, bucket))
                if (best < 0 || score > bestScore) {
                    best = id
                    bestScore = score
                }
            }
            return best
        }
    }

    /** Collects plays, then makes the [Table]. Deterministic: islands are summed in ascending id order. */
    class Builder(private val nowMs: Long) {
        private val rows = HashMap<Int, DoubleArray>()
        private val plays = IntArray(BUCKETS)

        /**
         * One graded play of an island, with its trust [c] and reward [r]; [tzOffsetMin] is the offset to read its hour with. A play in the
         * future counts as now. A play with no island, or no trust, is not counted (the bucket's play count is plays that have an island).
         */
        fun add(islandId: Int, startedAtMs: Long, tzOffsetMin: Int, c: Double, r: Double) {
            if (islandId < 0 || !(c > 0.0) || !BrainMath.isFinite(c) || !BrainMath.isFinite(r)) return
            val b = bucket(startedAtMs, tzOffsetMin)
            plays[b]++
            val weight = c * BrainMath.clip(r, 0.0, 1.0)
            if (!(weight > 0.0)) return
            val t = if (startedAtMs > nowMs) nowMs else startedAtMs
            val w = weight * Affinity.decay(nowMs - t, HALF_LIFE_DAYS * BrainMath.DAY_MS.toDouble())
            rows.getOrPut(islandId) { DoubleArray(BUCKETS) }[b] += w
        }

        fun build(): Table {
            val ids = rows.keys.sorted().toIntArray()
            val n = Array(ids.size) { rows[ids[it]]!! }
            val total = DoubleArray(BUCKETS)
            for (k in ids.indices) for (b in 0 until BUCKETS) total[b] += n[k][b]
            return Table(ids, n, total, plays.copyOf())
        }
    }
}
