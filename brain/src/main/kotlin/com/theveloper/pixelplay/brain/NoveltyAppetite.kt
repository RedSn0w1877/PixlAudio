package com.theveloper.pixelplay.brain

/**
 * The listener's novelty appetite (docs/brain-spec.md, 5.10): the share of slots given to music they have never played.
 *
 * ```
 * nu0      = clip( share of qualified plays in the trailing 365 days that were the track's first play, 0.08, 0.50 ) ; 0.25 with no history
 * F, N     = two discounted Beta posteriors on recommender surfaces: familiar plays and novel plays
 * nuLong   = clip( nu0 * (mean(N) / mean(F))^1.5, 0.08, 0.60 ), moving at most 0.02 per day
 * boost    = +0.01 per novel track liked or saved, fading to nothing over 30 days, at most +0.10
 * nu       = clip(nuLong + boost, 0, 0.60)
 * surface  = nu * k    (k = 1.0 mixes, 1.2 a station from a seed, 0.8 autoplay after an album or playlist, 0 On Repeat and Rediscover)
 * switch   = Familiar / Blend / Discover multiplies it by 0.4 / 1.0 / 2.0
 * ```
 *
 * Choices the spec leaves open (deviations, see the stage 3 handoff): F and N start at Beta(4, 4) each (no prior is given; equal priors make
 * the ratio 1, so `nuLong = nu0` with no evidence), the daily discount is 0.98 like the arm bandit's, the boost fades linearly, and a
 * "qualified" play is one with `c > 0` that is not excluded from learning.
 *
 * Learned state: it replaces the stage 2 constant only while the gate has the learned weights on.
 */
object NoveltyAppetite {
    const val NU0_DEFAULT = 0.25
    const val NU0_MIN = 0.08
    const val NU0_MAX = 0.50
    const val NU_LONG_MIN = 0.08
    const val NU_LONG_MAX = 0.60

    /** The most `nuLong` moves toward its target in one day. */
    const val MAX_STEP_PER_DAY = 0.02

    const val BOOST_EACH = 0.01
    const val BOOST_CAP = 0.10
    const val BOOST_DAYS = 30

    const val WINDOW_DAYS = 365

    const val PRIOR = 4.0
    const val DISCOUNT = 0.98
    const val MAX_FORGET_RUNS = 30

    /** At most this many boosting likes are kept (the cap is reached at ten whole ones). */
    const val MAX_BOOSTS = 64

    class State(
        var fAlpha: Double = PRIOR,
        var fBeta: Double = PRIOR,
        var nAlpha: Double = PRIOR,
        var nBeta: Double = PRIOR,
        /** The slow appetite; NaN until the first day it is advanced. */
        var nuLong: Double = Double.NaN,
        var anchorDay: Long = 0L,
        var forgetDone: Int = 0,
        /** The day `nuLong` was last advanced to. */
        var nuDay: Long = Long.MIN_VALUE,
        /** Local day -> (qualified plays, first plays). Days older than the window are dropped. */
        val days: java.util.TreeMap<Long, IntArray> = java.util.TreeMap(),
        /** Start times of the novel tracks liked or saved in the last [BOOST_DAYS] days, oldest first. */
        val boosts: ArrayList<Long> = ArrayList(),
        /** Recommender plays counted into F and N since the state was created or reset. */
        var updates: Long = 0L,
    ) {
        fun copy(): State {
            val d = java.util.TreeMap<Long, IntArray>()
            for ((k, v) in days) d[k] = v.copyOf()
            return State(fAlpha, fBeta, nAlpha, nBeta, nuLong, anchorDay, forgetDone, nuDay, d, ArrayList(boosts), updates)
        }

        fun isValid(): Boolean =
            BrainMath.isFinite(fAlpha) && fAlpha > 0.0 && BrainMath.isFinite(fBeta) && fBeta > 0.0 &&
                BrainMath.isFinite(nAlpha) && nAlpha > 0.0 && BrainMath.isFinite(nBeta) && nBeta > 0.0 &&
                (nuLong != nuLong || (nuLong >= NU_LONG_MIN - 1e-9 && nuLong <= NU_LONG_MAX + 1e-9))
    }

    fun newState(today: Long): State = State(anchorDay = today)

    /** Discounts F and N toward their priors up to local day [today], the same pure-of-the-days rule as [ArmBandit.discount]. */
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
            state.fAlpha = PRIOR + (state.fAlpha - PRIOR) * DISCOUNT
            state.fBeta = PRIOR + (state.fBeta - PRIOR) * DISCOUNT
            state.nAlpha = PRIOR + (state.nAlpha - PRIOR) * DISCOUNT
            state.nBeta = PRIOR + (state.nBeta - PRIOR) * DISCOUNT
        }
        state.forgetDone = total
        return runs
    }

    /** The share of qualified plays in the trailing [WINDOW_DAYS] days (up to [today]) that were a first play, clipped; [NU0_DEFAULT] with none. */
    fun nu0(state: State, today: Long): Double {
        var plays = 0
        var first = 0
        for ((day, v) in state.days.subMap(today - WINDOW_DAYS.toLong(), true, today, true)) {
            plays += v[0]
            first += v[1]
        }
        if (plays == 0) return NU0_DEFAULT
        return BrainMath.clip(first.toDouble() / plays.toDouble(), NU0_MIN, NU0_MAX)
    }

    /** `nu0 * (mean(N) / mean(F))^1.5`, clipped: where the slow appetite is heading. `x^1.5 = x * sqrt(x)`. */
    fun target(state: State, today: Long): Double {
        val meanF = state.fAlpha / (state.fAlpha + state.fBeta)
        val meanN = state.nAlpha / (state.nAlpha + state.nBeta)
        val ratio = meanN / meanF
        return BrainMath.clip(nu0(state, today) * ratio * BrainMath.sqrt(ratio), NU_LONG_MIN, NU_LONG_MAX)
    }

    /**
     * Moves the slow appetite toward its target by at most [MAX_STEP_PER_DAY] for each day since it was last advanced (and at most 60 days'
     * worth at once). The first time it takes the target at once, so a listener with no evidence starts at `nu0`. Days only go forward.
     */
    fun advance(state: State, today: Long) {
        discount(state, today)
        val target = target(state, today)
        if (state.nuLong != state.nuLong || state.nuDay == Long.MIN_VALUE) {
            state.nuLong = target
            state.nuDay = today
            return
        }
        if (today <= state.nuDay) return
        val gap = today - state.nuDay
        val days = if (gap > 60L) 60L else gap
        val step = MAX_STEP_PER_DAY * days.toDouble()
        state.nuLong = BrainMath.clip(state.nuLong + BrainMath.clip(target - state.nuLong, -step, step), NU_LONG_MIN, NU_LONG_MAX)
        state.nuDay = today
    }

    /** The boost of the novel tracks liked or saved in the last [BOOST_DAYS] days, fading linearly, at most [BOOST_CAP]. */
    fun boost(state: State, nowMs: Long): Double {
        val span = BOOST_DAYS.toDouble() * BrainMath.DAY_MS.toDouble()
        var sum = 0.0
        for (t in state.boosts) {
            val age = (nowMs - t).toDouble()
            if (age < 0.0 || age >= span) continue
            sum += 1.0 - age / span
        }
        return BrainMath.min(BOOST_CAP, BOOST_EACH * sum)
    }

    /** The share of slots for music the listener has never played, before the surface factor and the switch. */
    fun nu(state: State, nowMs: Long): Double {
        val base = if (state.nuLong != state.nuLong) nu0(state, BrainMath.localDay(nowMs, 0)) else state.nuLong
        return BrainMath.clip(base + boost(state, nowMs), 0.0, NU_LONG_MAX)
    }

    /**
     * One play, as both the live path and the replay take it. [novel] is true when it was the first play of the track; [recommender] when it
     * came from a recommender surface (an engine arm on an engine surface); [day] is its local day. Returns true when the state moved.
     */
    fun onPlay(state: State, e: PlayEvent, g: Reward.Graded, novel: Boolean, recommender: Boolean, day: Long): Boolean {
        advance(state, day)
        // The discount counts from the last day a play was applied, as [ArmBandit.update] does. Without moving the anchor, a gap of 30 days
        // or more spends all 30 runs once and F and N are never discounted again.
        state.anchorDay = day
        state.forgetDone = 0
        if (!(g.c > 0.0) || e.has(EventFlags.LEARN_EXCLUDED) || e.has(EventFlags.NEVER_AUDIBLE)) return false
        var counts = state.days[day]
        if (counts == null) {
            counts = IntArray(2)
            state.days[day] = counts
        }
        counts[0]++
        if (novel) counts[1]++
        // Keep the window: older days never count again.
        val oldest = day - WINDOW_DAYS.toLong() - 1L
        while (state.days.isNotEmpty() && state.days.firstKey() < oldest) state.days.pollFirstEntry()
        if (recommender) {
            val cc = if (g.c > Ranker.C_MAX * 1.5) Ranker.C_MAX * 1.5 else g.c
            val r = BrainMath.clip(g.r, 0.0, 1.0)
            if (novel) {
                state.nAlpha += cc * r
                state.nBeta += cc * (1.0 - r)
            } else {
                state.fAlpha += cc * r
                state.fBeta += cc * (1.0 - r)
            }
            state.updates++
        }
        val liked = e.flags and (EventFlags.LIKED_DURING or EventFlags.SAVED_DURING) != 0 && !e.has(EventFlags.NOT_FOR_ME)
        if (liked && novel) {
            state.boosts.add(e.startedAtMs)
            while (state.boosts.size > MAX_BOOSTS) state.boosts.removeAt(0)
        }
        return true
    }

    /** Drops the boosts that have faded out by [nowMs]. */
    fun prune(state: State, nowMs: Long) {
        val span = BOOST_DAYS.toLong() * BrainMath.DAY_MS
        state.boosts.removeAll { nowMs - it >= span }
    }
}
