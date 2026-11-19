package com.theveloper.pixelplay.brain

/** Chooses the arm a novel slot goes to (5.10): given the arms that still have a candidate, the arm with the largest draw, or -1 for none. */
fun interface ArmPicker {
    fun pick(eligible: BooleanArray): Int
}

/**
 * The arm bandit of docs/brain-spec.md, 5.10: one discounted Beta posterior per arm and surface family, and a Thompson draw that decides
 * which arm a novel slot is filled from.
 *
 * ```
 * on a recommender play from arm a:  alpha += c * r ;  beta += c * (1 - r)
 * daily:                             alpha <- a0 + (alpha - a0) * 0.98 ;  same for beta
 * draw:                              mu = alpha / (alpha + beta) ;  sd = sqrt(alpha * beta / ((alpha + beta)^2 * (alpha + beta + 1)))
 *                                    theta = clip(mu + sd * gaussian12(rng), 0, 1)
 * ```
 *
 * Audit (5.12 point 3): beside the main posterior each arm has one that sees only EXPLORATION plays (the slots this draw filled, flagged
 * [EventFlags.EXPLORE_SLOT], which [Reward] weights 1.5). When the audit has enough evidence and its mean differs from the main mean
 * by more than [AUDIT_GAP], the audit is the one drawn from.
 *
 * Learned state: it is used only while the gate has the learned weights on (the caller passes no [ArmPicker] otherwise, so a listener
 * on the standard recipe keeps stage 2's choice of the best-valued novel candidate, byte for byte).
 */
object ArmBandit {
    const val HOME = 0
    const val MIX = 1
    const val STATION = 2
    const val FAMILIES = 3

    /** The arms that can be drawn: [Arm.FAV] to [Arm.ASK] (codes 0..8). */
    const val ARMS = 9

    const val DISCOUNT = 0.98
    const val MAX_FORGET_RUNS = 30

    /** The audit overrides the main estimate when the two differ by more than this. */
    const val AUDIT_GAP = 0.15

    /** The audit counts only with at least this many (weighted) exploration plays beyond its prior. */
    const val AUDIT_MIN_EVIDENCE = 5.0

    private val PRIOR_A = doubleArrayOf(7.0, 5.0, 6.0, 4.0, 3.0, 5.0, 5.0, 2.0, 4.0)
    private val PRIOR_B = doubleArrayOf(3.0, 5.0, 4.0, 6.0, 7.0, 5.0, 5.0, 8.0, 6.0)

    fun priorAlpha(arm: Int): Double = PRIOR_A[arm]
    fun priorBeta(arm: Int): Double = PRIOR_B[arm]

    /** The family a surface belongs to, or -1 for a surface the bandit does not learn from. */
    fun familyOf(surface: Int): Int = when (surface) {
        Surface.HOME_SHELF -> HOME
        Surface.DAILY_MIX, Surface.YOUR_MIX, Surface.NAMED_MIX -> MIX
        Surface.STATION, Surface.AUTOPLAY -> STATION
        else -> -1
    }

    /** The posteriors of one listener. Not thread safe: the engine's lock owns it. */
    class State(
        val alpha: DoubleArray,
        val beta: DoubleArray,
        val auditAlpha: DoubleArray,
        val auditBeta: DoubleArray,
        var anchorDay: Long,
        var forgetDone: Int = 0,
        /** Recommender plays counted since the state was created or reset. */
        var updates: Long = 0L,
    ) {
        fun copy(): State = State(alpha.copyOf(), beta.copyOf(), auditAlpha.copyOf(), auditBeta.copyOf(), anchorDay, forgetDone, updates)

        fun isValid(): Boolean {
            if (alpha.size != FAMILIES * ARMS || beta.size != alpha.size || auditAlpha.size != alpha.size || auditBeta.size != alpha.size) return false
            for (i in alpha.indices) {
                if (!BrainMath.isFinite(alpha[i]) || !(alpha[i] > 0.0) || !BrainMath.isFinite(beta[i]) || !(beta[i] > 0.0)) return false
                if (!BrainMath.isFinite(auditAlpha[i]) || !(auditAlpha[i] > 0.0) || !BrainMath.isFinite(auditBeta[i]) || !(auditBeta[i] > 0.0)) return false
            }
            return true
        }
    }

    private fun index(family: Int, arm: Int): Int = family * ARMS + arm

    /** Every arm at its prior; the daily discount counts from [today]. */
    fun newState(today: Long): State {
        val n = FAMILIES * ARMS
        val a = DoubleArray(n) { PRIOR_A[it % ARMS] }
        val b = DoubleArray(n) { PRIOR_B[it % ARMS] }
        return State(a, b, a.copyOf(), b.copyOf(), today)
    }

    /**
     * Discounts toward the priors up to local day [today]: a pure function of the days on which plays were applied and of [today] (the runs
     * made since the last applied play are `min(gap, 30)` in all), the same rule as [Ranker.forget]. A day before the anchor makes no run.
     */
    fun discount(state: State, today: Long): Int {
        if (today < state.anchorDay) {
            state.anchorDay = today
            state.forgetDone = 0
            return 0
        }
        val gap = today - state.anchorDay
        val total = if (gap > MAX_FORGET_RUNS.toLong()) MAX_FORGET_RUNS else gap.toInt()
        val runs = total - state.forgetDone
        if (runs <= 0) return 0
        for (k in 0 until runs) {
            for (i in state.alpha.indices) {
                val a0 = PRIOR_A[i % ARMS]
                val b0 = PRIOR_B[i % ARMS]
                state.alpha[i] = a0 + (state.alpha[i] - a0) * DISCOUNT
                state.beta[i] = b0 + (state.beta[i] - b0) * DISCOUNT
                state.auditAlpha[i] = a0 + (state.auditAlpha[i] - a0) * DISCOUNT
                state.auditBeta[i] = b0 + (state.auditBeta[i] - b0) * DISCOUNT
            }
        }
        state.forgetDone = total
        return runs
    }

    /**
     * One recommender play from [arm] in [family]: discount up to [day], then `alpha += c * r`, `beta += c * (1 - r)`. [explore] plays also
     * feed the audit posterior. A weight or reward that is not finite changes nothing. Returns true when the posteriors moved.
     */
    fun update(state: State, family: Int, arm: Int, r: Double, c: Double, explore: Boolean, day: Long): Boolean {
        discount(state, day)
        state.anchorDay = day
        state.forgetDone = 0
        if (family < 0 || family >= FAMILIES || arm < 0 || arm >= ARMS) return false
        if (!(c > 0.0) || !BrainMath.isFinite(c) || !BrainMath.isFinite(r)) return false
        val cc = if (c > Ranker.C_MAX * 1.5) Ranker.C_MAX * 1.5 else c
        val rr = BrainMath.clip(r, 0.0, 1.0)
        val i = index(family, arm)
        state.alpha[i] = state.alpha[i] + cc * rr
        state.beta[i] = state.beta[i] + cc * (1.0 - rr)
        if (explore) {
            state.auditAlpha[i] = state.auditAlpha[i] + cc * rr
            state.auditBeta[i] = state.auditBeta[i] + cc * (1.0 - rr)
        }
        state.updates++
        return true
    }

    /** The mean of the posterior the draw uses for ([family], [arm]): the audit's when it has evidence and disagrees with the main one by more than [AUDIT_GAP]. */
    fun mean(state: State, family: Int, arm: Int): Double {
        val i = index(family, arm)
        val main = state.alpha[i] / (state.alpha[i] + state.beta[i])
        val evidence = state.auditAlpha[i] + state.auditBeta[i] - PRIOR_A[arm] - PRIOR_B[arm]
        if (evidence >= AUDIT_MIN_EVIDENCE) {
            val audit = state.auditAlpha[i] / (state.auditAlpha[i] + state.auditBeta[i])
            if (BrainMath.abs(audit - main) > AUDIT_GAP) return audit
        }
        return main
    }

    /** True when the audit posterior, not the main one, is what the draw for ([family], [arm]) reads. */
    fun auditInUse(state: State, family: Int, arm: Int): Boolean {
        val i = index(family, arm)
        val evidence = state.auditAlpha[i] + state.auditBeta[i] - PRIOR_A[arm] - PRIOR_B[arm]
        if (evidence < AUDIT_MIN_EVIDENCE) return false
        val main = state.alpha[i] / (state.alpha[i] + state.beta[i])
        val audit = state.auditAlpha[i] / (state.auditAlpha[i] + state.auditBeta[i])
        return BrainMath.abs(audit - main) > AUDIT_GAP
    }

    /** One draw `theta = clip(mu + sd * gaussian12, 0, 1)` for ([family], [arm]); consumes twelve uniforms of [rng]. */
    fun draw(state: State, family: Int, arm: Int, rng: BrainHash.Rng): Double {
        val i = index(family, arm)
        val useAudit = auditInUse(state, family, arm)
        val a = if (useAudit) state.auditAlpha[i] else state.alpha[i]
        val b = if (useAudit) state.auditBeta[i] else state.beta[i]
        val n = a + b
        val mu = a / n
        val sd = BrainMath.sqrt(a * b / (n * n * (n + 1.0)))
        return BrainMath.clip(mu + sd * rng.gaussian12(), 0.0, 1.0)
    }

    /**
     * The arm with the largest draw among the arms that still have a candidate ([eligible], indexed by arm code), ties to the lower code;
     * -1 when none is eligible. Every eligible arm gets one draw, in ascending arm order, so the sequence of [rng] is fixed.
     */
    fun pickArm(state: State, family: Int, eligible: BooleanArray, rng: BrainHash.Rng): Int {
        var best = -1
        var bestTheta = 0.0
        for (arm in 0 until ARMS) {
            if (arm >= eligible.size || !eligible[arm]) continue
            val theta = draw(state, family, arm, rng)
            if (best < 0 || theta > bestTheta) {
                best = arm
                bestTheta = theta
            }
        }
        return best
    }

    /**
     * The picker one list uses: a private copy of the posteriors (so the list is ranked with one consistent set) and a generator seeded by
     * [seed] (see [Ranker.listSeed]), so a mix does not redraw when it is reopened.
     */
    class Sampler(state: State, private val family: Int, seed: Long) : ArmPicker {
        private val snapshot = state.copy()
        private val rng = BrainHash.Rng(seed)

        override fun pick(eligible: BooleanArray): Int = pickArm(snapshot, family, eligible, rng)
    }
}
