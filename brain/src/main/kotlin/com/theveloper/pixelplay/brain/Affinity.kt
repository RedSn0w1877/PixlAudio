package com.theveloper.pixelplay.brain

/** The key families of the affinity tables (5.2). Codes are stored in `affinity.family`: never renumber. */
object Family {
    const val TRACK = 0
    const val ARTIST = 1
    const val ARM = 2
    /** One key, [ALL_KEY]: everything the listener played. */
    const val ALL = 3
    // From stage 2 and 3; reserved so the codes never move.
    const val TAG = 4
    const val DECADE = 5
    const val ISLAND = 6
    const val CTX = 7

    const val COUNT = 8

    /** The single key of [ALL], and the key of every family's sum row. */
    const val ALL_KEY = 0L

    /** `affinity.family` of a family's total row (`sum:<family>`): [SUM_BASE] + family. */
    const val SUM_BASE = 16

    /** `affinity.family` of a family's pseudo-count rows (4.4): [PRIOR_BASE] + family. `posQ`, `negQ` only. */
    const val PRIOR_BASE = 32

    /** `affinity.family` of the per-track completion counts ([Affinity.State.completions]); `posQ` is the count. */
    const val COMPLETIONS = 48

    /** The shrinkage weight `a` of 5.2. */
    fun shrink(family: Int): Double = when (family) {
        TRACK -> 2.0
        TAG -> 8.0
        else -> 4.0
    }
}

/**
 * Affinity (docs/brain-spec.md, 5.2): for every key, decayed counts of how often what played was
 * finished (`pos`) and was not (`neg`), kept twice: a 120-day half-life and a 21-day "recent" copy.
 *
 * Counters are Q20 integers in memory as well as on disk; every update converts to double, computes,
 * and converts back, so both platforms hold the same integers after every event. `aff` and `share`
 * are rates: being played more cannot by itself raise them.
 */
object Affinity {

    /** 120 days in milliseconds. */
    const val HALF_LIFE_MS: Double = 10_368_000_000.0

    /** 21 days in milliseconds. */
    const val RECENT_HALF_LIFE_MS: Double = 1_814_400_000.0

    /** A clock more than this far behind the newest counter means the clock jumped: rebuild. */
    const val CLOCK_JUMP_MS: Long = 48L * 60 * 60 * 1000

    class Counter(
        @JvmField var posQ: Long = 0L,
        @JvmField var negQ: Long = 0L,
        @JvmField var lastMs: Long = 0L,
        @JvmField var posRQ: Long = 0L,
        @JvmField var negRQ: Long = 0L,
        @JvmField var lastRMs: Long = 0L,
    )

    class Prior(@JvmField var posQ: Long = 0L, @JvmField var negQ: Long = 0L)

    /** The affinity tables. A cache: [Replay.rebuild] reproduces it from the event log and the facts. */
    class State {
        internal val tables: Array<HashMap<Long, Counter>> = Array(Family.COUNT) { HashMap() }
        internal val sums: Array<Counter> = Array(Family.COUNT) { Counter() }
        internal val priors: Array<HashMap<Long, Prior>> = Array(Family.COUNT) { HashMap() }

        /** Past plays with `r >= 0.8`, per rid: the `pastCompletions` of [Reward.Context]. */
        val completions: HashMap<Long, Int> = HashMap()

        /** The newest event time any counter has seen. */
        var newestMs: Long = 0L
            internal set

        /** `(family, key)` pairs written since [drainTouched]; family is the stored code (sums and completions included). */
        private val touchedFamilies = ArrayList<Int>()
        private val touchedKeys = ArrayList<Long>()

        internal fun touched(family: Int, key: Long) {
            touchedFamilies.add(family)
            touchedKeys.add(key)
        }

        /** Hands the rows written since the last call to [sink], then forgets them. A row can repeat. */
        fun drainTouched(sink: (family: Int, key: Long) -> Unit) {
            for (i in touchedFamilies.indices) sink(touchedFamilies[i], touchedKeys[i])
            touchedFamilies.clear()
            touchedKeys.clear()
        }

        fun counter(family: Int, key: Long): Counter? = tables[family][key]

        fun sum(family: Int): Counter = sums[family]

        fun prior(family: Int, key: Long): Prior? = priors[family][key]

        fun size(family: Int): Int = tables[family].size

        /** Keys of [family] in ascending order (the canonical order of 10.3, rule 2). */
        fun sortedKeys(family: Int): LongArray = tables[family].keys.toLongArray().also { it.sort() }

        fun sortedPriorKeys(family: Int): LongArray = priors[family].keys.toLongArray().also { it.sort() }

        /** Loads one stored row; [storedFamily] is the `affinity.family` code. Unknown codes are ignored. */
        fun load(storedFamily: Int, key: Long, posQ: Long, negQ: Long, lastMs: Long, posRQ: Long, negRQ: Long, lastRMs: Long) {
            when {
                storedFamily >= 0 && storedFamily < Family.COUNT -> {
                    tables[storedFamily][key] = Counter(posQ, negQ, lastMs, posRQ, negRQ, lastRMs)
                    if (lastMs > newestMs) newestMs = lastMs
                }
                storedFamily >= Family.SUM_BASE && storedFamily < Family.SUM_BASE + Family.COUNT ->
                    sums[storedFamily - Family.SUM_BASE] = Counter(posQ, negQ, lastMs, posRQ, negRQ, lastRMs)
                storedFamily >= Family.PRIOR_BASE && storedFamily < Family.PRIOR_BASE + Family.COUNT ->
                    priors[storedFamily - Family.PRIOR_BASE][key] = Prior(posQ, negQ)
                storedFamily == Family.COMPLETIONS -> completions[key] = posQ.toInt()
            }
        }

        fun clearPriors() {
            for (table in priors) table.clear()
        }

        /** Adds pseudo-counts (4.4). They do not decay; they are recomputed from the facts at every rebuild. */
        fun addPrior(family: Int, key: Long, posQ: Long, negQ: Long) {
            val p = priors[family].getOrPut(key) { Prior() }
            p.posQ += posQ
            p.negQ += negQ
        }
    }

    fun decay(dtMs: Long, halfLifeMs: Double): Double =
        BrainMath.exp(-(BrainMath.LN2 * dtMs.toDouble()) / halfLifeMs)

    /**
     * One update of one key and of its family's sum key (5.2 `apply`). [part] is 1, or 0.5 for a
     * credited artist who is not the primary one. With `c = 0` this only moves `lastMs`.
     */
    fun apply(state: State, family: Int, key: Long, r: Double, c: Double, part: Double, tMs: Long) {
        val counter = state.tables[family].getOrPut(key) { Counter() }
        bump(counter, r, c, part, tMs)
        bump(state.sums[family], r, c, part, tMs)
        if (tMs > state.newestMs) state.newestMs = tMs
        state.touched(family, key)
        state.touched(Family.SUM_BASE + family, Family.ALL_KEY)
    }

    private fun bump(k: Counter, r: Double, c: Double, part: Double, tMs: Long) {
        // The 120-day copy.
        var pos: Double
        var neg: Double
        var g: Double
        if (tMs >= k.lastMs) {
            val d = if (k.posQ == 0L && k.negQ == 0L) 1.0 else decay(tMs - k.lastMs, HALF_LIFE_MS)
            pos = BrainMath.fromQ(k.posQ) * d
            neg = BrainMath.fromQ(k.negQ) * d
            g = 1.0
            k.lastMs = tMs
        } else {
            // A late event: shrink the increment, never grow the state.
            pos = BrainMath.fromQ(k.posQ)
            neg = BrainMath.fromQ(k.negQ)
            g = decay(k.lastMs - tMs, HALF_LIFE_MS)
        }
        k.posQ = BrainMath.toQ(pos + g * c * r * part)
        k.negQ = BrainMath.toQ(neg + g * c * (1.0 - r) * part)

        // The 21-day copy.
        if (tMs >= k.lastRMs) {
            val d = if (k.posRQ == 0L && k.negRQ == 0L) 1.0 else decay(tMs - k.lastRMs, RECENT_HALF_LIFE_MS)
            pos = BrainMath.fromQ(k.posRQ) * d
            neg = BrainMath.fromQ(k.negRQ) * d
            g = 1.0
            k.lastRMs = tMs
        } else {
            pos = BrainMath.fromQ(k.posRQ)
            neg = BrainMath.fromQ(k.negRQ)
            g = decay(k.lastRMs - tMs, RECENT_HALF_LIFE_MS)
        }
        k.posRQ = BrainMath.toQ(pos + g * c * r * part)
        k.negRQ = BrainMath.toQ(neg + g * c * (1.0 - r) * part)
    }

    /**
     * Everything one graded event does to the tables: the track, its artists (primary 1, others 0.5),
     * its arm when the engine served it, and the key `all`. Events that were never audible or are
     * excluded from learning touch nothing.
     */
    fun applyEvent(state: State, e: PlayEvent, g: Reward.Graded, tMs: Long) {
        if (e.has(EventFlags.NEVER_AUDIBLE) || e.has(EventFlags.LEARN_EXCLUDED)) return
        if (e.rid != 0L) apply(state, Family.TRACK, e.rid, g.r, g.c, 1.0, tMs)
        if (e.primaryAid != 0L) apply(state, Family.ARTIST, e.primaryAid, g.r, g.cOther, 1.0, tMs)
        for (aid in e.otherAids) {
            if (aid != 0L && aid != e.primaryAid) apply(state, Family.ARTIST, aid, g.r, g.cOther, 0.5, tMs)
        }
        if (e.arm != Arm.NONE) apply(state, Family.ARM, e.arm.toLong(), g.r, g.cOther, 1.0, tMs)
        apply(state, Family.ALL, Family.ALL_KEY, g.r, g.cOther, 1.0, tMs)
        if (g.isCompletion && e.rid != 0L) {
            state.completions[e.rid] = (state.completions[e.rid] ?: 0) + 1
            state.touched(Family.COMPLETIONS, e.rid)
        }
    }

    /**
     * The tag part of [applyEvent] (5.2): each of the track's tags gets the play with the weight `q / 255` as its part, in the
     * tag vector's order. Called next to [applyEvent] when the track's tags are known; a play that was never audible or is excluded
     * from the counters touches nothing, as there.
     */
    fun applyTags(state: State, e: PlayEvent, g: Reward.Graded, tags: Tags.Vector, tMs: Long) {
        if (e.has(EventFlags.NEVER_AUDIBLE) || e.has(EventFlags.LEARN_EXCLUDED)) return
        for (t in tags.tags) apply(state, Family.TAG, t.atlasId.toLong(), g.r, g.cOther, t.q.toDouble() / 255.0, tMs)
    }

    /**
     * Takes one event's own contribution back out of the tables (5.2, "Undo (stage 2)"): "Don't count
     * this" removes what [applyEvent] added, `c * r * part` and `c * (1 - r) * part` decayed to the
     * counter's own time, floored at 0, for every key the event touched, and its completion. [tMs] is the
     * time the event was applied with (its start, clamped to now). It does not look at the event's flags:
     * the caller flags the event, and a rebuild from the log makes the tables exact again.
     *
     * [g] is the event's own grade. A grade made without the context of the time (how often the track was
     * finished before, the skip streak) can differ from the one that was applied, so this is the quick
     * answer, not the exact one.
     */
    fun subtract(state: State, e: PlayEvent, g: Reward.Graded, tMs: Long) {
        if (e.has(EventFlags.NEVER_AUDIBLE)) return
        if (e.rid != 0L) remove(state, Family.TRACK, e.rid, g.r, g.c, 1.0, tMs)
        if (e.primaryAid != 0L) remove(state, Family.ARTIST, e.primaryAid, g.r, g.cOther, 1.0, tMs)
        for (aid in e.otherAids) {
            if (aid != 0L && aid != e.primaryAid) remove(state, Family.ARTIST, aid, g.r, g.cOther, 0.5, tMs)
        }
        if (e.arm != Arm.NONE) remove(state, Family.ARM, e.arm.toLong(), g.r, g.cOther, 1.0, tMs)
        remove(state, Family.ALL, Family.ALL_KEY, g.r, g.cOther, 1.0, tMs)
        if (g.isCompletion && e.rid != 0L) {
            val count = state.completions[e.rid] ?: 0
            if (count > 0) {
                state.completions[e.rid] = count - 1
                state.touched(Family.COMPLETIONS, e.rid)
            }
        }
    }

    private fun remove(state: State, family: Int, key: Long, r: Double, c: Double, part: Double, tMs: Long) {
        val counter = state.tables[family][key] ?: return
        unbump(counter, r, c, part, tMs)
        unbump(state.sums[family], r, c, part, tMs)
        state.touched(family, key)
        state.touched(Family.SUM_BASE + family, Family.ALL_KEY)
    }

    private fun unbump(k: Counter, r: Double, c: Double, part: Double, tMs: Long) {
        // What the event added is held at the counter's reference time: it decayed from [tMs] to there.
        var g = if (tMs >= k.lastMs) 1.0 else decay(k.lastMs - tMs, HALF_LIFE_MS)
        k.posQ = BrainMath.toQ(BrainMath.max(0.0, BrainMath.fromQ(k.posQ) - g * c * r * part))
        k.negQ = BrainMath.toQ(BrainMath.max(0.0, BrainMath.fromQ(k.negQ) - g * c * (1.0 - r) * part))
        g = if (tMs >= k.lastRMs) 1.0 else decay(k.lastRMs - tMs, RECENT_HALF_LIFE_MS)
        k.posRQ = BrainMath.toQ(BrainMath.max(0.0, BrainMath.fromQ(k.posRQ) - g * c * r * part))
        k.negRQ = BrainMath.toQ(BrainMath.max(0.0, BrainMath.fromQ(k.negRQ) - g * c * (1.0 - r) * part))
    }

    // ── Reads (never write) ──────────────────────────────────────────────────

    private fun since(nowMs: Long, lastMs: Long): Long = if (nowMs > lastMs) nowMs - lastMs else 0L

    fun pos(k: Counter, nowMs: Long): Double =
        if (k.posQ == 0L) 0.0 else BrainMath.fromQ(k.posQ) * decay(since(nowMs, k.lastMs), HALF_LIFE_MS)

    fun neg(k: Counter, nowMs: Long): Double =
        if (k.negQ == 0L) 0.0 else BrainMath.fromQ(k.negQ) * decay(since(nowMs, k.lastMs), HALF_LIFE_MS)

    fun posRecent(k: Counter, nowMs: Long): Double =
        if (k.posRQ == 0L) 0.0 else BrainMath.fromQ(k.posRQ) * decay(since(nowMs, k.lastRMs), RECENT_HALF_LIFE_MS)

    fun negRecent(k: Counter, nowMs: Long): Double =
        if (k.negRQ == 0L) 0.0 else BrainMath.fromQ(k.negRQ) * decay(since(nowMs, k.lastRMs), RECENT_HALF_LIFE_MS)

    /** The listener's base completion rate, from the key `all`: 0.5 until it has one play's worth of weight. */
    fun p0(state: State, nowMs: Long): Double {
        val all = state.tables[Family.ALL][Family.ALL_KEY] ?: return 0.5
        val p = pos(all, nowMs)
        val n = neg(all, nowMs)
        if (p + n < 1.0) return 0.5
        return BrainMath.clip(p / (p + n), 0.2, 0.8)
    }

    /**
     * "Do they finish it when it plays", in -3..3, relative to their base rate [p0]. Exactly 0 for a
     * key with no plays and no pseudo-counts.
     */
    fun aff(state: State, family: Int, key: Long, nowMs: Long, p0: Double): Double {
        val k = state.tables[family][key]
        val prior = state.priors[family][key]
        val p = if (k == null) 0.0 else pos(k, nowMs)
        val n = if (k == null) 0.0 else neg(k, nowMs)
        val pp = if (prior == null) 0.0 else BrainMath.fromQ(prior.posQ)
        val pn = if (prior == null) 0.0 else BrainMath.fromQ(prior.negQ)
        if (p == 0.0 && n == 0.0 && pp == 0.0 && pn == 0.0) return 0.0
        return rate(p + pp, n + pn, Family.shrink(family), p0)
    }

    /** [aff] over the 21-day copy. Pseudo-counts are not recent evidence and are left out. */
    fun affRecent(state: State, family: Int, key: Long, nowMs: Long, p0: Double): Double {
        val k = state.tables[family][key] ?: return 0.0
        val p = posRecent(k, nowMs)
        val n = negRecent(k, nowMs)
        if (p == 0.0 && n == 0.0) return 0.0
        return rate(p, n, Family.shrink(family), p0)
    }

    private fun rate(pos: Double, neg: Double, a: Double, p0: Double): Double =
        BrainMath.clip(
            BrainMath.ln((pos + a * p0) / (neg + a * (1.0 - p0))) - BrainMath.ln(p0 / (1.0 - p0)),
            -3.0,
            3.0,
        )

    /** "How much of their listening is it": the key's weight over its family's; 0 while the family has under 1. */
    fun share(state: State, family: Int, key: Long, nowMs: Long): Double {
        val k = state.tables[family][key] ?: return 0.0
        val s = state.sums[family]
        val total = pos(s, nowMs) + neg(s, nowMs)
        if (total < 1.0) return 0.0
        return (pos(k, nowMs) + neg(k, nowMs)) / total
    }

    /** The decayed weight of everything recorded for a key (`pos + neg`): roughly "how many plays". */
    fun weight(state: State, family: Int, key: Long, nowMs: Long): Double {
        val k = state.tables[family][key] ?: return 0.0
        return pos(k, nowMs) + neg(k, nowMs)
    }

    /** When the key was last played; 0 when it never was. */
    fun lastMs(state: State, family: Int, key: Long): Long = state.tables[family][key]?.lastMs ?: 0L

    fun isHeard(state: State, family: Int, key: Long): Boolean = state.tables[family].containsKey(key)

    /** True when the clock is far behind what the state has seen (5.2): the state must be rebuilt. */
    fun clockJumpedBack(state: State, nowMs: Long): Boolean = nowMs + CLOCK_JUMP_MS < state.newestMs
}
