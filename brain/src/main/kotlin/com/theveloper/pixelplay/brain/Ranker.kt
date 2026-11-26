package com.theveloper.pixelplay.brain

/**
 * The one scorer (docs/brain-spec.md, 5.7, 5.8) and, from stage 3, its learner (5.9).
 *
 * [weights] is the hand-set prior `M0`: a fixed formula, not learning. [RankerModel] holds the learned
 * `N(m, 1/q)` per weight; [update] is the diagonal extended-Kalman step of 5.9, [forget] its
 * forgetting, [sampleWeights] the per-list posterior sample of 5.10. The learner has only ever run on
 * synthetic data: the gate of 5.12 point 8 ([Gate]) decides whether its weights are used at all.
 */
object Ranker {
    const val D = 51

    /** pi / 8, as the spec writes it. */
    private const val PROBIT = 0.3927

    /** Prior weights. Index 0 (bias) is `logit(p0)`, filled in by [weights]. */
    private val M0 = doubleArrayOf(
        0.0, // 0 bias
        1.2, // 1 track_aff
        1.0, // 2 artist_aff
        0.4, // 3 artist_aff_recent
        0.8, // 4 tag_aff
        1.0, // 5 island_sim
        0.3, // 6 island_weight
        0.2, // 7 decade_aff
        0.8, // 8 liked
        0.4, // 9 in_playlists
        0.5, // 10 actr
        -0.3, // 11 actr_sq
        -1.5, -0.6, -0.1, 0.1, 0.2, // 12-16 since_last
        -0.3, // 17 unheard_track
        -0.4, // 18 unheard_artist
        -0.5, // 19 novelty_dist
        0.0, // 20 pop
        0.3, // 21 pop_x_mainstream
        0.8, // 22 sess_pos_sim
        -1.0, // 23 sess_neg_sim
        -1.0, // 24 sess_tag_pen
        0.4, // 25 prev_completed
        -0.4, // 26 skip_run
        -0.3, // 27 tempo_jump
        -0.1, // 28 loud_jump
        0.5, // 29 ctx_lift
        0.0, // 30 weekend
        0.0, 0.0, 0.0, 0.0, // 31-34 route
        0.0, // 35 route_x_energy
        0.4, // 36 arm_aff
        0.5, // 37 retrieval
        0.3, // 38 radio_rank
        0.2, // 39 fresh
        -0.2, // 40 duration_dev
        0.0, // 41 explicit_mismatch
        0.0, // 42 passive_ctx
        0.0, // 43 position
        0.0, 0.0, 0.0, 0.0, // 44-47 surface family
        0.0, 0.0, 0.0, // 48-50 origin
    )

    /** Prior standard deviations. */
    private val SD0 = doubleArrayOf(
        0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.3, 0.5, 0.5, 0.5, 0.3,
        0.5, 0.5, 0.5, 0.5, 0.5,
        0.5, 0.5, 0.5, 0.3, 0.3, 0.5, 0.5, 0.5, 0.3, 0.3, 0.3, 0.3, 0.5, 0.2,
        0.2, 0.2, 0.2, 0.2,
        0.2, 0.3, 0.5, 0.3, 0.3, 0.3, 0.3, 0.2, 0.2,
        0.3, 0.3, 0.3, 0.3,
        0.5, 0.5, 0.5,
    )

    /** Prior precisions `q_i = 1 / SD0_i^2`. */
    private val Q0 = DoubleArray(D) { 1.0 / (SD0[it] * SD0[it]) }

    fun m0(index: Int): Double = M0[index]

    /**
     * The "choice" scorer of 6.5 (two lanes): what orders the familiar lane of every list. It is the finish scorer's weights (prior or
     * learned, whatever ranks the list) plus this fixed shift, so its prior is `M0 + CHOICE_SHIFT` (the 2026-11-26 candidate of
     * tools/eval: since_last 12-16 -> 0, track_aff 0.4, artist_aff_recent 1.0, tag_aff 0.4, sess_tag_pen -0.5) and the learner moves
     * it with the same events, without a second model. The discovery lane, skip prediction and the reward keep [weights] as they are.
     */
    private val CHOICE_SHIFT = DoubleArray(D).also {
        it[1] = -0.8
        it[3] = 0.6
        it[4] = -0.4
        it[12] = 1.5
        it[13] = 0.6
        it[14] = 0.1
        it[15] = -0.1
        it[16] = -0.2
        it[24] = 0.5
    }

    fun choiceShift(index: Int): Double = CHOICE_SHIFT[index]

    /** The choice weights for the finish weights [w] (from [weights] or a [WeightSource]): `w + CHOICE_SHIFT`, a new array. */
    fun choiceWeights(w: DoubleArray): DoubleArray {
        val c = w.copyOf()
        for (i in 0 until D) c[i] += CHOICE_SHIFT[i]
        return c
    }

    fun sd0(index: Int): Double = SD0[index]

    /** The weight vector for a listener whose base completion rate is [p0]. */
    fun weights(p0: Double): DoubleArray {
        val w = M0.copyOf()
        w[0] = BrainMath.logit(p0)
        return w
    }

    /** `z = sum_i w_i * x_i`, by index. */
    fun z(w: DoubleArray, x: DoubleArray): Double {
        var z = 0.0
        for (i in 0 until D) z += w[i] * x[i]
        return z
    }

    /** `p = sigmoid(z / sqrt(1 + 0.3927 * s))`, `s = sum_i x_i^2 / q_i`. */
    fun p(z: Double, x: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until D) s += x[i] * x[i] / Q0[i]
        return BrainMath.sigmoid(z / BrainMath.sqrt(1.0 + PROBIT * s))
    }

    /** The prior precision vector, as a copy (a caller may not change the shared one). */
    fun priorPrecision(): DoubleArray = Q0.copyOf()

    // ── The learner (5.9, 5.12) ──────────────────────────────────────────────

    /** The most one update may move a weight. */
    const val STEP_CLIP = 0.25

    /** The floor of the curvature `h = p(1 - p)`. */
    const val H_FLOOR = 0.02

    /** Each `m_i` stays within `m0_i +- BOX_SD * SD0_i` (5.12 point 2). */
    const val BOX_SD = 4.0

    /** Every feature of 5.8 is in about -3..3; an input beyond this is a bug somewhere else and is not learned from. */
    const val X_MAX = 4.0

    /** The trust weight [Reward] can give (`MAX_C`); a larger value is clipped. */
    const val C_MAX = 4.5

    /** Daily forgetting of the precision toward the prior, and of the weight toward `M0`. */
    const val Q_KEEP = 0.995
    const val M_PULL = 0.001

    /** The daily line runs at most this many times for one stretch without a play. */
    const val MAX_FORGET_RUNS = 30

    const val UPDATE_APPLIED = 0

    /** No weight, an input that is not finite or too large, or an event that was already applied: nothing changed. */
    const val UPDATE_SKIPPED = 1

    /** A value was not finite (or the model was not valid): the model went back to its prior; the caller schedules a replay. */
    const val UPDATE_RESET = 2

    /**
     * A fresh model: `m = M0`, `q = 1 / SD0^2`, the forgetting counting from [today].
     *
     * The bias is learned as an OFFSET from the live `logit(p0)`: `m[0]` starts at 0 and is boxed around 0, and the weight vector
     * that ranks ([weightsFor]) and the predictions ([predict], [update]) add `logit(p0)` to it. So the learned model and the
     * prior share one intercept that follows the listener's own finish rate, the gate compares ranking and not calibration, and
     * "Reset what I've learned" returns exactly to `M0` with the live `logit(p0)` (deviation 175 from the spec's "bias fixed at
     * the first rebuild").
     */
    fun newModel(today: Long): RankerModel {
        val m0 = M0.copyOf()
        m0[0] = 0.0
        return RankerModel(m0.copyOf(), Q0.copyOf(), m0, today)
    }

    /** `s = sum_i x_i^2 / q_i`, by index. */
    fun s(q: DoubleArray, x: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until D) s += x[i] * x[i] / q[i]
        return s
    }

    /** `p = sigmoid(z / sqrt(1 + 0.3927 * s))` for any precision vector [q]. */
    fun p(z: Double, x: DoubleArray, q: DoubleArray): Double =
        BrainMath.sigmoid(z / BrainMath.sqrt(1.0 + PROBIT * s(q, x)))

    /**
     * `z` of a model's mean with the intercept [bias] added to index 0, summed in exactly the order [z] sums [weights]: a model that
     * is still at its prior then predicts bit for bit what the prior does, so the gate cannot see a difference that is only rounding.
     */
    fun zBiased(m: DoubleArray, bias: Double, x: DoubleArray): Double {
        var z = (m[0] + bias) * x[0]
        for (i in 1 until D) z += m[i] * x[i]
        return z
    }

    /** The learned model's predicted finish probability for [x]; [bias] is `logit(p0)` of the moment. */
    fun predict(model: RankerModel, x: DoubleArray, bias: Double): Double = p(zBiased(model.m, bias, x), x, model.q)

    /** The frozen prior's prediction for [x], with the same intercept: the weights `M0` and the bias `logit(p0)`. */
    fun predictPrior(x: DoubleArray, p0: Double): Double = p(z(weights(p0), x), x, Q0)

    /** `logit` of a finish rate kept inside 0.05..0.95, the way [weights] callers do. */
    fun biasOf(p0: Double): Double = BrainMath.logit(BrainMath.clip(p0, 0.05, 0.95))

    /**
     * One online step with the soft target [r] and weight [c] (5.9); [bias] is `logit(p0)` of the moment (see [newModel]).
     * Returns [UPDATE_APPLIED], [UPDATE_SKIPPED] or [UPDATE_RESET].
     *
     * A model that is not valid on entry (a value not finite, a precision not above 0) is reset BEFORE anything is computed: a
     * NaN weight under an active feature would otherwise be turned into the edge of its box by the clip. On exit every value is
     * checked again; the step is computed into temporaries and only committed when all of it is finite.
     */
    fun update(
        model: RankerModel,
        x: DoubleArray,
        r: Double,
        c: Double,
        bias: Double = 0.0,
        /** [STEP_CLIP] and [H_FLOOR] unless the evaluation tool (tools/eval) replays under other values. */
        stepClip: Double = STEP_CLIP,
        hFloor: Double = H_FLOOR,
    ): Int {
        if (!model.isValid()) {
            model.resetToPrior()
            return UPDATE_RESET
        }
        if (!(c > 0.0) || !BrainMath.isFinite(c) || !BrainMath.isFinite(r) || !BrainMath.isFinite(bias)) return UPDATE_SKIPPED
        for (i in 0 until D) {
            val xi = x[i]
            if (!BrainMath.isFinite(xi) || xi > X_MAX || xi < -X_MAX) return UPDATE_SKIPPED
        }
        val cc = if (c > C_MAX) C_MAX else c
        val target = BrainMath.clip(r, 0.0, 1.0)
        val m = model.m
        val q = model.q
        val zz = zBiased(m, bias, x)
        val s = s(q, x)
        val pHat = BrainMath.sigmoid(zz)
        var h = pHat * (1.0 - pHat)
        if (h < hFloor) h = hFloor
        val denom = 1.0 + cc * h * s
        val err = target - pHat
        val nm = DoubleArray(D)
        val nq = DoubleArray(D)
        for (i in 0 until D) {
            nm[i] = m[i]
            nq[i] = q[i]
            val xi = x[i]
            if (xi == 0.0) continue
            val qi = q[i]
            val step = BrainMath.clip((xi / qi) * cc * err / denom, -stepClip, stepClip)
            val lo = model.m0[i] - BOX_SD * SD0[i]
            val hi = model.m0[i] + BOX_SD * SD0[i]
            nm[i] = BrainMath.clip(m[i] + step, lo, hi)
            nq[i] = qi / (1.0 - (xi * xi / qi) * cc * h / denom)
        }
        for (i in 0 until D) {
            if (!BrainMath.isFinite(nm[i]) || !BrainMath.isFinite(nq[i]) || !(nq[i] > 0.0)) {
                model.resetToPrior()
                return UPDATE_RESET
            }
        }
        for (i in 0 until D) {
            m[i] = nm[i]
            q[i] = nq[i]
        }
        model.updates++
        return UPDATE_APPLIED
    }

    /**
     * Forgets up to local day [today] (5.9, "daily"), a pure function of the days on which plays were applied and of [today], not of
     * how often it is called: the runs made since the last applied play are `min(gap, 30)` in all, and a second call only makes the
     * runs that are still owed. A [today] before the anchor (a clock set back, or a day once written by a clock that was ahead)
     * makes no run and pulls the anchor back to [today]. Returns the runs made.
     */
    fun forget(model: RankerModel, today: Long): Int {
        if (today < model.anchorDay) {
            model.anchorDay = today
            model.forgetDone = 0
            return 0
        }
        val gap = today - model.anchorDay
        val total = if (gap > MAX_FORGET_RUNS.toLong()) MAX_FORGET_RUNS else gap.toInt()
        val runs = total - model.forgetDone
        if (runs <= 0) return 0
        for (k in 0 until runs) {
            for (i in 0 until D) {
                model.q[i] = Q0[i] + (model.q[i] - Q0[i]) * Q_KEEP
                model.m[i] = model.m[i] + (model.m0[i] - model.m[i]) * M_PULL
            }
        }
        model.forgetDone = total
        return runs
    }

    /**
     * One graded play, the way both the live path and [LearnReplay] apply it: forget up to [day], take one [update] step, move the
     * anchor to [day] and the watermark to [eventId]. An event at or below the watermark was applied already and changes nothing,
     * so a catch-up after a rebuild can never apply a play twice.
     */
    fun applyEvent(
        model: RankerModel,
        x: DoubleArray,
        r: Double,
        c: Double,
        bias: Double,
        day: Long,
        eventId: Long,
        stepClip: Double = STEP_CLIP,
        hFloor: Double = H_FLOOR,
    ): Int {
        if (eventId <= model.lastEventId) return UPDATE_SKIPPED
        forget(model, day)
        val result = update(model, x, r, c, bias, stepClip, hFloor)
        model.anchorDay = day
        model.forgetDone = 0
        model.lastEventId = eventId
        return result
    }

    /** One posterior sample of the weights: `w_i = m_i + gaussian12(rng) / sqrt(q_i)`, i ascending (5.10). Without the intercept. */
    fun sampleWeights(model: RankerModel, rng: BrainHash.Rng): DoubleArray {
        val w = DoubleArray(D)
        for (i in 0 until D) w[i] = model.m[i] + rng.gaussian12() / BrainMath.sqrt(model.q[i])
        return w
    }

    /**
     * The seed of a list's posterior sample and of its arm draws (5.10): `fnv1a64` over the 8 little-endian bytes of [salt], the 4
     * little-endian bytes of the UTF-8 length of [listId], the UTF-8 bytes of [listId] and the 8 little-endian bytes of [localDay]
     * (a length, not a separator: no id can collide with another). `listseed.jsonl` holds vectors.
     */
    fun listSeed(salt: Long, listId: String, localDay: Long): Long {
        val id = listId.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(8 + 4 + id.size + 8)
        for (k in 0 until 8) bytes[k] = ((salt ushr (8 * k)) and 0xFFL).toByte()
        for (k in 0 until 4) bytes[8 + k] = ((id.size ushr (8 * k)) and 0xFF).toByte()
        for (k in id.indices) bytes[12 + k] = id[k]
        for (k in 0 until 8) bytes[12 + id.size + k] = ((localDay ushr (8 * k)) and 0xFFL).toByte()
        return BrainHash.fnv1a64(bytes)
    }

    /**
     * The weight vector that ranks one list. [learned] is false when the gate has the prior on, the listener reset what was
     * learned, there is no (valid) model, or this is a control session (5.12 point 8): then it is `M0` with the bias `logit(p0)`.
     * Otherwise it is the model's mean (or, with [seed] given, one posterior sample) with the intercept `logit(p0)` added to
     * index 0. Never a vector with a value that is not finite: that falls back to the prior.
     */
    fun weightsFor(model: RankerModel?, learned: Boolean, p0: Double, seed: Long?): DoubleArray {
        if (!learned || model == null || !model.isValid()) return weights(p0)
        val w = if (seed == null) model.m.copyOf() else sampleWeights(model, BrainHash.Rng(seed))
        w[0] += biasOf(p0)
        for (i in 0 until D) if (!BrainMath.isFinite(w[i])) return weights(p0)
        return w
    }

    /** The precision vector that goes with [weightsFor]'s answer, for `p`: always a copy. */
    fun precisionFor(model: RankerModel?, learned: Boolean): DoubleArray =
        if (!learned || model == null || !model.isValid()) Q0.copyOf() else model.q.copyOf()

    /**
     * Which weights rank one list: the learned model's (mean or one posterior sample, [seed]) when [learned] says the gate has them
     * on and this is not a control session, else the prior `M0`. Immutable once built, so a list is ranked with one consistent vector.
     */
    class WeightSource(model: RankerModel?, val learned: Boolean, val seed: Long? = null) {
        private val snapshot: RankerModel? = if (learned && model != null && model.isValid()) model.copy() else null

        /** True when the learned weights are in use. */
        val usesLearned: Boolean get() = snapshot != null

        fun weights(p0: Double): DoubleArray = weightsFor(snapshot, snapshot != null, p0, seed)

        /** The precision vector for `p`, or null for the prior's (the planner then uses the shared prior one). */
        fun precision(): DoubleArray? = if (snapshot == null) null else snapshot.q.copyOf()
    }

    /** One session in ten is ranked with `M0` and flagged (5.12 point 8): `splitmix64(sessionId) mod 10 == 0`, unsigned. */
    fun isControlSession(sessionId: Long): Boolean =
        java.lang.Long.remainderUnsigned(BrainHash.splitmix64(sessionId), 10L) == 0L
}

/**
 * What the learner holds (5.9, 5.13): the mean and precision of each of the 51 weights, the prior they are boxed around, the forgetting
 * anchor, the watermark of the last play applied, and how many plays moved the weights since the model was last at its prior.
 * A cache: [LearnReplay] reproduces it from the log.
 *
 * Not thread safe, by design: one owner (the engine's lock) reads and changes it, and a rebuild produces a new model that replaces
 * the old one under the same lock.
 */
class RankerModel(
    val m: DoubleArray,
    val q: DoubleArray,
    /** The prior mean (`M0`, bias 0); each `m_i` is boxed around it. */
    val m0: DoubleArray,
    /** The local day of the last applied play (or of creation): the forgetting counts from it. */
    var anchorDay: Long,
    /** Plays that moved the weights since the model was created or last reset to its prior. */
    var updates: Long = 0L,
    /** The id of the newest event applied; an event at or below it is never applied again. */
    var lastEventId: Long = Long.MIN_VALUE,
    /** Forgetting runs already made since [anchorDay]. */
    var forgetDone: Int = 0,
    /** How many times the model was reset to its prior because a value was not finite. */
    var resets: Int = 0,
) {
    /** Every value finite, every precision above 0, every mean inside its box. */
    fun isValid(): Boolean {
        for (i in 0 until Ranker.D) {
            if (!BrainMath.isFinite(m[i]) || !BrainMath.isFinite(q[i]) || !(q[i] > 0.0)) return false
            val lo = m0[i] - Ranker.BOX_SD * Ranker.sd0(i) - BOX_SLACK
            val hi = m0[i] + Ranker.BOX_SD * Ranker.sd0(i) + BOX_SLACK
            if (m[i] < lo || m[i] > hi) return false
        }
        return true
    }

    /** Back to `m = M0`, `q = 1 / SD0^2`; the watermark and the anchor are kept, [updates] starts again at 0, [resets] counts it. */
    fun resetToPrior() {
        val q0 = Ranker.priorPrecision()
        for (i in 0 until Ranker.D) {
            m[i] = m0[i]
            q[i] = q0[i]
        }
        updates = 0L
        forgetDone = 0
        resets++
    }

    /** True when the weights are the prior exactly (nothing learned yet, or reset). */
    fun isPrior(): Boolean {
        val q0 = Ranker.priorPrecision()
        for (i in 0 until Ranker.D) if (m[i] != m0[i] || q[i] != q0[i]) return false
        return true
    }

    fun copy(): RankerModel = RankerModel(m.copyOf(), q.copyOf(), m0.copyOf(), anchorDay, updates, lastEventId, forgetDone, resets)

    private companion object {
        /** Rounding of a bit pattern that went through text. */
        const val BOX_SLACK = 1e-9
    }
}
