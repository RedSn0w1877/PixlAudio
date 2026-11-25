package com.theveloper.pixelplay.brain

/**
 * Flow ordering (docs/brain-spec.md, 6.5 `flowCost`, stage 5): the cost of putting a track after another one, from tempo, loudness
 * and energy, and the energy arc a finite list follows. It is subtracted from a slot's value in [ListBuilder], so a list steps
 * smoothly instead of jumping from a ballad to drum and bass; it never overrides a hard rule, and it is small next to the score
 * (at most about 0.4 of `z`), so taste still decides and flow breaks near-ties.
 *
 * ```
 * flowCost(i)  = 0.15 * max(0, tempoDist(prev, i) - 0.17) + min(0.15, 0.05 * max(0, |gainPrev - gain_i| - 4) / 4)
 *              + min(0.1, 0.1 * max(0, |energyPrev - energy_i| - 32) / 64)                       (stage 5)
 *              + arc(i)                                                                            (finite lists, stage 5)
 * tempoDist    = min over k in {0.5, 1, 2} of |log2(bpm_a * k / bpm_b)|
 * arc(i)       = min(0.1, 0.1 * max(0, |energy_i - target(t)| - 20) / 64),  t = slot / (count - 1)
 * target(t)    = mid + 36 * shape(t);  shape = -0.5 + t / 0.65 up to t = 0.65, then 0.5 - 0.8 (t - 0.65) / 0.35
 * mid          = the median energy of the candidates whose energy is known (the lower middle for an even count)
 * ```
 * A value that is unknown on either side costs 0. `prev` is the slot before, or [Context.lead] (what plays before the list) for the
 * first one.
 */
object Flow {
    const val TEMPO_W = 0.15
    const val TEMPO_FREE = 0.17
    const val GAIN_W = 0.05
    const val GAIN_FREE_DB = 4.0
    const val GAIN_MAX = 0.15
    const val ENERGY_W = 0.1
    const val ENERGY_FREE = 32
    const val ENERGY_MAX = 0.1
    const val ARC_W = 0.1
    const val ARC_FREE = 20
    const val ARC_AMPLITUDE = 36.0
    const val ARC_PEAK = 0.65

    /** What flow reads about one track. Unknown: tempo 0, gain [TrackDna.GAIN_UNKNOWN], energy -1. */
    class Facts(val tempoBpmX10: Int, val gainDbX10: Int, val energy: Int) {
        companion object {
            val UNKNOWN = Facts(0, TrackDna.GAIN_UNKNOWN, -1)
        }
    }

    /**
     * The facts of a list's candidates and of what plays before it. [arc] is for finite lists (mixes): the energy rises to a peak
     * about two thirds in and settles at the end. Autoplay and stations have no end, so no arc, only smooth steps.
     */
    class Context(
        val facts: Map<Long, Facts>,
        val lead: Facts = Facts.UNKNOWN,
        val arc: Boolean = false,
    ) {
        /** [mid] of the arc. Computed once. */
        val mid: Int = median(facts)

        fun of(rid: Long): Facts = facts[rid] ?: Facts.UNKNOWN
    }

    /** The lower median of the known energies (sorted, so the map's order cannot reach the result); -1 for none. */
    fun median(facts: Map<Long, Facts>): Int {
        var n = 0
        for (f in facts.values) if (f.energy >= 0) n++
        if (n == 0) return -1
        val e = IntArray(n)
        var i = 0
        for (f in facts.values) if (f.energy >= 0) e[i++] = f.energy
        e.sort()
        return e[(n - 1) / 2]
    }

    /** `min over k in {0.5, 1, 2} of |log2(a * k / b)|`; 0 when either is unknown. */
    fun tempoDist(aX10: Int, bX10: Int): Double {
        if (aX10 <= 0 || bX10 <= 0) return 0.0
        val a = aX10.toDouble()
        val b = bX10.toDouble()
        var best = BrainMath.abs(BrainMath.log2(a / b))
        val half = BrainMath.abs(BrainMath.log2(a * 0.5 / b))
        if (half < best) best = half
        val twice = BrainMath.abs(BrainMath.log2(a * 2.0 / b))
        if (twice < best) best = twice
        return best
    }

    /** The step from [prev] to [next]: tempo, loudness and energy. */
    fun step(prev: Facts, next: Facts): Double {
        var cost = 0.0
        val td = tempoDist(prev.tempoBpmX10, next.tempoBpmX10)
        if (td > TEMPO_FREE) cost += TEMPO_W * (td - TEMPO_FREE)
        if (prev.gainDbX10 != TrackDna.GAIN_UNKNOWN && next.gainDbX10 != TrackDna.GAIN_UNKNOWN) {
            val dg = BrainMath.abs((prev.gainDbX10 - next.gainDbX10).toDouble() / 10.0)
            if (dg > GAIN_FREE_DB) cost += BrainMath.min(GAIN_MAX, GAIN_W * (dg - GAIN_FREE_DB) / GAIN_FREE_DB)
        }
        if (prev.energy >= 0 && next.energy >= 0) {
            val de = if (prev.energy > next.energy) prev.energy - next.energy else next.energy - prev.energy
            if (de > ENERGY_FREE) cost += BrainMath.min(ENERGY_MAX, ENERGY_W * (de - ENERGY_FREE).toDouble() / 64.0)
        }
        return cost
    }

    /** The arc's energy at slot [slot] of [count]; -1 when there is no arc (no known energy, or one slot). */
    fun target(mid: Int, slot: Int, count: Int): Int {
        if (mid < 0 || count < 2) return -1
        val t = slot.toDouble() / (count - 1).toDouble()
        val shape = if (t <= ARC_PEAK) -0.5 + t / ARC_PEAK else 0.5 - 0.8 * (t - ARC_PEAK) / (1.0 - ARC_PEAK)
        val v = BrainMath.floorToLong(mid.toDouble() + ARC_AMPLITUDE * shape + 0.5).toInt()
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    /** What the arc costs a track of energy [energy] at slot [slot]. */
    fun arc(mid: Int, slot: Int, count: Int, energy: Int): Double {
        if (energy < 0) return 0.0
        val target = target(mid, slot, count)
        if (target < 0) return 0.0
        val d = if (energy > target) energy - target else target - energy
        if (d <= ARC_FREE) return 0.0
        return BrainMath.min(ARC_W, ARC_W * (d - ARC_FREE).toDouble() / 64.0)
    }

    /** The whole flow cost of [next] in slot [slot] of a list of [count] after [prev]. */
    fun cost(ctx: Context, prev: Facts, next: Facts, slot: Int, count: Int): Double {
        var c = step(prev, next)
        if (ctx.arc) c += arc(ctx.mid, slot, count, next.energy)
        return c
    }

    /**
     * How rough a finished order is: the sum of [step] over consecutive tracks (the golden files and the tests compare orders with it;
     * the planner never calls it).
     */
    fun roughness(order: List<Facts>): Double {
        var s = 0.0
        for (i in 1 until order.size) s += step(order[i - 1], order[i])
        return s
    }
}
