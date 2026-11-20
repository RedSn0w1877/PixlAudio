package com.theveloper.pixelplay.brain

/**
 * The Home shelf bandit (docs/brain-spec.md, 5.10 "Home shelves", 8.1): a cascade bandit with one Beta posterior per (shelf type, daypart).
 *
 * - Priors, pessimistic on purpose: Made for you (3, 7), On repeat (2, 8), every other shelf (1, 9).
 * - Reward 1: a play of 30 s or more started from that shelf. Reward 0: the shelf was on screen for at least 1 s and the visit ended, or a
 *   LOWER shelf was used. Only shelves at or above the last one seen are updated ([visit]): the listener looked down the page that far.
 * - The order is drawn once per daypart and then frozen ([order]); a shelf type unseen for [FORCE_DAYS] days gets one forced exposure.
 * - With no data the fixed order of stage 2 stays: [order] answers null until [MIN_UPDATES] shelf updates have been seen.
 *
 * [rebuild] makes the same state from visit rows that carry the reward (tests and the iOS port); the app keeps the posteriors themselves,
 * because it does not store the reward in its impression rows. The learned order is used only
 * while the gate has the learned weights on and is reset by "Reset what I've learned" (the caller).
 */
object ShelfBandit {
    const val TYPES = 10
    const val DAYPARTS = 4

    /** The daypart mix shelf (stage 3); types 0..8 are [HomePlanner]'s. */
    const val DAYPART_MIX = HomePlanner.DAYPART_MIX

    /** A shelf type not seen for this many days is shown once at the top ("a forced exposure"). */
    const val FORCE_DAYS = 14

    /**
     * Below this many shelf updates (all types and dayparts together) the stage 2 order stays. The spec gives no number; a visit updates up to
     * seven shelves, so 100 is about fifteen visits, enough that three visits cannot reorder the page from the priors.
     */
    const val MIN_UPDATES = 100

    /** A play counts as the shelf's reward from this long. */
    const val REWARD_PLAY_MS = 30_000L

    /** A shelf counts as seen after this long on screen. */
    const val SEEN_MS = 1_000L

    fun priorAlpha(type: Int): Double = when (type) {
        HomePlanner.MADE_FOR_YOU -> 3.0
        HomePlanner.ON_REPEAT -> 2.0
        else -> 1.0
    }

    fun priorBeta(type: Int): Double = when (type) {
        HomePlanner.MADE_FOR_YOU -> 7.0
        HomePlanner.ON_REPEAT -> 8.0
        else -> 9.0
    }

    class State(
        val alpha: DoubleArray,
        val beta: DoubleArray,
        /** The local day each type was last on screen long enough to count; [Long.MIN_VALUE] for never. */
        val lastSeenDay: LongArray,
        /** The `planKey` ([HomePlanner.planKey]) the frozen order was drawn for; -1 for none. */
        var frozenKey: Long = -1L,
        var frozenOrder: IntArray = IntArray(0),
        /** Shelf updates counted since the state was created or reset. */
        var updates: Long = 0L,
    ) {
        fun copy(): State = State(alpha.copyOf(), beta.copyOf(), lastSeenDay.copyOf(), frozenKey, frozenOrder.copyOf(), updates)

        fun isValid(): Boolean {
            if (alpha.size != TYPES * DAYPARTS || beta.size != alpha.size || lastSeenDay.size != TYPES) return false
            for (i in alpha.indices) {
                if (!BrainMath.isFinite(alpha[i]) || !(alpha[i] > 0.0) || !BrainMath.isFinite(beta[i]) || !(beta[i] > 0.0)) return false
            }
            for (t in frozenOrder) if (t < 0 || t >= TYPES) return false
            return true
        }
    }

    private fun index(type: Int, daypart: Int): Int = type * DAYPARTS + daypart

    fun newState(): State {
        val a = DoubleArray(TYPES * DAYPARTS) { priorAlpha(it / DAYPARTS) }
        val b = DoubleArray(TYPES * DAYPARTS) { priorBeta(it / DAYPARTS) }
        return State(a, b, LongArray(TYPES) { Long.MIN_VALUE })
    }

    /** The mean of the posterior of ([type], [daypart]). */
    fun mean(state: State, type: Int, daypart: Int): Double {
        val i = index(type, daypart)
        return state.alpha[i] / (state.alpha[i] + state.beta[i])
    }

    /**
     * One visit to the page, in the cascade model. [shown] are the shelf types in the order the page showed them (top first); [lastSeen] is the
     * index of the lowest shelf that was on screen for at least [SEEN_MS] (-1 for none); [used] the index of the shelf a play of at least
     * [REWARD_PLAY_MS] was started from (-1 for none). Shelves from the top down to `max(lastSeen, used)` are updated: the used one with
     * reward 1, the others with 0. Returns how many shelves were updated.
     */
    fun visit(state: State, daypart: Int, shown: IntArray, lastSeen: Int, used: Int, today: Long): Int {
        if (daypart < 0 || daypart >= DAYPARTS) return 0
        val last = if (lastSeen > used) lastSeen else used
        if (last < 0) return 0
        val end = if (last >= shown.size) shown.size - 1 else last
        var n = 0
        for (i in 0..end) {
            val t = shown[i]
            if (t < 0 || t >= TYPES) continue
            val j = index(t, daypart)
            if (i == used) state.alpha[j] += 1.0 else state.beta[j] += 1.0
            state.lastSeenDay[t] = today
            state.updates++
            n++
        }
        return n
    }

    /** One draw for ([type], [daypart]): `clip(mu + sd * gaussian12, 0, 1)` of its Beta, as in [ArmBandit.draw]. */
    fun draw(state: State, type: Int, daypart: Int, rng: BrainHash.Rng): Double {
        val i = index(type, daypart)
        val a = state.alpha[i]
        val b = state.beta[i]
        val n = a + b
        val mu = a / n
        val sd = BrainMath.sqrt(a * b / (n * n * (n + 1.0)))
        return BrainMath.clip(mu + sd * rng.gaussian12(), 0.0, 1.0)
    }

    /** True once the posteriors hold enough evidence to reorder the page. */
    fun hasData(state: State): Boolean = state.updates >= MIN_UPDATES

    /**
     * The order of the shelf types [types] (given in stage 2's fixed order) for the page of [planKey]: drawn once per daypart and then frozen
     * in [state], so a shelf does not move while the listener is on it. Null while there is no data ([hasData]), meaning "keep the fixed
     * order". A type in [types] the frozen order does not know goes where it stands in the fixed order; one it holds that is not in
     * [types] is dropped. A type last seen [FORCE_DAYS] or more days ago, or never seen, is moved to the top (the one unseen the longest, never
     * seen first). It counts as seen only when a visit shows it ([visit]), so a shelf the planner left out of the page is not spent: it is forced
     * again at the next plan.
     */
    fun order(state: State, planKey: Long, daypart: Int, types: IntArray, seed: Long, today: Long): IntArray? {
        if (!hasData(state)) return null
        if (state.frozenKey != planKey || state.frozenOrder.isEmpty()) {
            val rng = BrainHash.Rng(seed)
            val theta = DoubleArray(types.size)
            for (i in types.indices) theta[i] = draw(state, types[i], daypart, rng)
            val idx = types.indices.sortedWith { a, b ->
                val ka = BrainMath.orderKey(theta[a])
                val kb = BrainMath.orderKey(theta[b])
                if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
            }
            val drawn = ArrayList<Int>(types.size)
            for (i in idx) drawn.add(types[i])
            // One forced exposure: the type unseen the longest (a type never seen is the longest), if it is 14 days or more.
            var forced = -1
            var oldest = Long.MAX_VALUE
            for (t in types) {
                val seen = state.lastSeenDay[t]
                if (seen != Long.MIN_VALUE && today - seen < FORCE_DAYS) continue
                if (seen < oldest) {
                    oldest = seen
                    forced = t
                }
            }
            if (forced >= 0) {
                drawn.remove(forced)
                drawn.add(0, forced)
            }
            state.frozenKey = planKey
            state.frozenOrder = drawn.toIntArray()
        }
        val out = ArrayList<Int>(types.size)
        for (t in state.frozenOrder) if (types.contains(t)) out.add(t)
        // A type the frozen order never held: at the place it has in the fixed order.
        for (pos in types.indices) {
            val t = types[pos]
            if (!out.contains(t)) out.add(if (pos < out.size) pos else out.size, t)
        }
        return out.toIntArray()
    }


    /**
     * Makes the posteriors from impression rows alone: [rows] are grouped by the visit they belong to (rows of one visit share `visitId`),
     * oldest visit first, each visit's rows in page order. Used to rebuild the state after a reset of the saved one.
     */
    class VisitRow(val visitId: Long, val type: Int, val daypart: Int, val position: Int, val seen: Boolean, val used: Boolean, val day: Long)

    fun rebuild(rows: List<VisitRow>): State {
        val state = newState()
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j < rows.size && rows[j].visitId == rows[i].visitId) j++
            val group = rows.subList(i, j).sortedBy { it.position }
            val shown = IntArray(group.size) { group[it].type }
            var lastSeen = -1
            var used = -1
            for (k in group.indices) {
                if (group[k].seen) lastSeen = k
                if (group[k].used) used = k
            }
            visit(state, group[0].daypart, shown, lastSeen, used, group[0].day)
            i = j
        }
        return state
    }
}

/** The persisted [ShelfBandit.State]: canonical JSON, strict reader (anything wrong gives null and the posteriors start from their priors). */
object ShelfBanditCodec {
    const val FORMAT = "brain-shelf-bandit-v1"

    fun encode(s: ShelfBandit.State): String {
        val w = BrainJson.Writer().beginObject()
        w.string("format", FORMAT)
        w.beginArray("alpha"); for (v in s.alpha) w.doubleItem(v); w.endArray()
        w.beginArray("beta"); for (v in s.beta) w.doubleItem(v); w.endArray()
        w.beginArray("lastSeenDay"); for (v in s.lastSeenDay) w.longItem(v); w.endArray()
        w.long("frozenKey", s.frozenKey)
        w.beginArray("frozenOrder"); for (t in s.frozenOrder) w.intItem(t); w.endArray()
        w.long("updates", s.updates)
        return w.endObject().toString()
    }

    fun decode(text: String): ShelfBandit.State? = try {
        @Suppress("UNCHECKED_CAST")
        val o = BrainJson.parse(text) as? Map<String, Any?>
        if (o == null || o["format"] != FORMAT) null else {
            val n = ShelfBandit.TYPES * ShelfBandit.DAYPARTS
            val state = ShelfBandit.State(
                alpha = doubles(o["alpha"], n) ?: return null,
                beta = doubles(o["beta"], n) ?: return null,
                lastSeenDay = LongArray(ShelfBandit.TYPES).also { out ->
                    val list = o["lastSeenDay"] as List<*>
                    if (list.size != ShelfBandit.TYPES) return null
                    for (i in out.indices) out[i] = (list[i] as String).toLong()
                },
                frozenKey = (o["frozenKey"] as String).toLong(),
                frozenOrder = (o["frozenOrder"] as List<*>).map { (it as Long).toInt() }.toIntArray(),
                updates = (o["updates"] as String).toLong(),
            )
            if (state.isValid()) state else null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun doubles(v: Any?, size: Int): DoubleArray? {
        val list = v as? List<*> ?: return null
        if (list.size != size) return null
        val out = DoubleArray(size)
        for (i in 0 until size) {
            val d = BrainJson.doubleOfHex(list[i] as String)
            if (!BrainMath.isFinite(d)) return null
            out[i] = d
        }
        return out
    }
}
