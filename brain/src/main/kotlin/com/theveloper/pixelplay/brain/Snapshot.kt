package com.theveloper.pixelplay.brain

/**
 * The canonical `brain-snapshot-v1` (docs/brain-spec.md, 3.8, 10.1, 10.3): the affinity tables, the
 * pseudo-counts and the completion counts as one JSON object, rows in the canonical order of 10.3,
 * rule 2. It is the fixture format of the golden vectors and of the evaluation harness, and (from
 * stage 3) the `brain` backup module.
 *
 * Stage 2a holds the affinity state only; islands, ranker weights and posteriors arrive with the
 * stages that own them, as new keys beside `rows` (a reader ignores keys it does not know).
 *
 * Each row is `[storedFamily, key, posQ, negQ, lastMs, posRQ, negRQ, lastRMs]` with the family codes
 * of [Family]: 0-7 by key, then the sums (16+), the pseudo-counts (32+) and the completion counts (48).
 */
object Snapshot {
    const val FORMAT = "brain-snapshot-v1"

    fun encode(state: Affinity.State): String {
        val w = BrainJson.Writer().beginObject()
        w.string("format", FORMAT)
        w.int("specVersion", Brain.SPEC_VERSION)
        w.beginArray("rows")
        fun row(family: Int, key: Long, k: Affinity.Counter) {
            w.beginArray().intItem(family).longItem(key).longItem(k.posQ).longItem(k.negQ).longItem(k.lastMs)
                .longItem(k.posRQ).longItem(k.negRQ).longItem(k.lastRMs).endArray()
        }
        for (f in 0 until Family.COUNT) for (key in state.sortedKeys(f)) row(f, key, state.counter(f, key)!!)
        for (f in 0 until Family.COUNT) {
            val s = state.sum(f)
            if (s.posQ != 0L || s.negQ != 0L || s.lastMs != 0L) row(Family.SUM_BASE + f, Family.ALL_KEY, s)
        }
        for (f in 0 until Family.COUNT) for (key in state.sortedPriorKeys(f)) {
            val p = state.prior(f, key)!!
            row(Family.PRIOR_BASE + f, key, Affinity.Counter(p.posQ, p.negQ))
        }
        for (rid in state.completions.keys.sorted()) {
            row(Family.COMPLETIONS, rid, Affinity.Counter(posQ = state.completions[rid]!!.toLong()))
        }
        w.endArray()
        return w.endObject().toString()
    }

    /** The state [encode] wrote. Throws [IllegalArgumentException] for text that is not a snapshot. */
    fun decode(text: String): Affinity.State {
        val root = BrainJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("not an object")
        if (root["format"] != FORMAT) throw IllegalArgumentException("not a $FORMAT")
        val rows = root["rows"] as? List<*> ?: throw IllegalArgumentException("no rows")
        val state = Affinity.State()
        for (r in rows) {
            val row = r as? List<*> ?: throw IllegalArgumentException("bad row")
            if (row.size != 8) throw IllegalArgumentException("bad row length ${row.size}")
            fun long(i: Int): Long = when (val v = row[i]) {
                is Long -> v
                is String -> v.toLong()
                else -> throw IllegalArgumentException("bad number in row")
            }
            state.load(long(0).toInt(), long(1), long(2), long(3), long(4), long(5), long(6), long(7))
        }
        return state
    }
}
