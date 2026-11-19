package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The learner of docs/brain-spec.md, 5.9 and 5.12, and what the review of stage 3 found in it. */
class LearnerTest {

    private fun x(vararg pairs: Pair<Int, Double>): DoubleArray {
        val v = DoubleArray(Ranker.D)
        v[0] = 1.0
        for ((i, value) in pairs) v[i] = value
        return v
    }

    private fun sameBits(a: DoubleArray, b: DoubleArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i].toRawBits(), b[i].toRawBits(), "index $i")
    }

    // ── The update ───────────────────────────────────────────────────────────

    @Test
    fun `an update moves the weights toward the outcome and never lowers a precision`() {
        val model = Ranker.newModel(100L)
        val q0 = model.q.copyOf()
        val v = x(1 to 0.8, 2 to 0.5)
        val before = Ranker.predict(model, v, 0.0)
        assertEquals(Ranker.UPDATE_APPLIED, Ranker.update(model, v, 1.0, 1.0, 0.0))
        assertTrue(Ranker.predict(model, v, 0.0) > before, "a finish raises the prediction")
        for (i in 0 until Ranker.D) assertTrue(model.q[i] >= q0[i], "precision $i only grows in an update")
        // Weights of features that were 0 did not move.
        assertEquals(Ranker.m0(5), model.m[5])
        assertEquals(1L, model.updates)
    }

    @Test
    fun `a skip lowers the prediction, a play with no weight changes nothing`() {
        val model = Ranker.newModel(100L)
        val v = x(1 to 0.8, 23 to 0.9)
        val p = Ranker.predict(model, v, 0.0)
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, v, 0.0, 0.0, 0.0))
        assertTrue(model.isPrior())
        assertEquals(Ranker.UPDATE_APPLIED, Ranker.update(model, v, 0.0, 1.0, 0.0))
        assertTrue(Ranker.predict(model, v, 0.0) < p)
    }

    @Test
    fun `the step is clipped and each mean stays inside its box`() {
        val model = Ranker.newModel(100L)
        val v = x(1 to 1.0)
        val q0 = Ranker.priorPrecision()
        // One huge-trust update cannot move a weight by more than the clip.
        Ranker.update(model, v, 1.0, 4.5, -3.0)
        assertTrue(model.m[1] - Ranker.m0(1) <= Ranker.STEP_CLIP + 1e-12)
        // A thousand updates in one direction stop at the edge of the box.
        for (k in 0 until 1000) Ranker.update(model, v, 1.0, 4.5, -3.0)
        for (i in 0 until Ranker.D) {
            assertTrue(model.m[i] <= Ranker.m0(i) + Ranker.BOX_SD * Ranker.sd0(i) + 1e-12, "upper box at $i")
            assertTrue(model.m[i] >= Ranker.m0(i) - Ranker.BOX_SD * Ranker.sd0(i) - 1e-12, "lower box at $i")
        }
        assertEquals(Ranker.m0(1) + Ranker.BOX_SD * Ranker.sd0(1), model.m[1], 1e-12)
        assertTrue(model.q[1] > q0[1])
        for (k in 0 until 1000) Ranker.update(model, v, 0.0, 4.5, 3.0)
        assertEquals(Ranker.m0(1) - Ranker.BOX_SD * Ranker.sd0(1), model.m[1], 1e-12)
    }

    @Test
    fun `a non-finite weight at an active feature resets the model, it is not clamped to the edge of its box`() {
        for (corrupt in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            // Index 0 has x = 1 always: the case the review found (the clip used to turn NaN into the lower edge).
            val model = Ranker.newModel(100L)
            model.m[0] = corrupt
            assertEquals(Ranker.UPDATE_RESET, Ranker.update(model, x(1 to 0.5), 1.0, 1.0, 0.0))
            assertTrue(model.isPrior(), "back to the prior, not to a box edge")
            assertEquals(1, model.resets)
            assertEquals(0L, model.updates)
        }
        val badQ = Ranker.newModel(100L)
        badQ.q[3] = -1.0
        assertEquals(Ranker.UPDATE_RESET, Ranker.update(badQ, x(3 to 1.0), 1.0, 1.0, 0.0))
        assertTrue(badQ.isPrior())
        val zeroQ = Ranker.newModel(100L)
        zeroQ.q[40] = 0.0
        assertEquals(Ranker.UPDATE_RESET, Ranker.update(zeroQ, x(1 to 1.0), 1.0, 1.0, 0.0))
        // A mean outside its box (a corrupt state) is invalid too.
        val outside = Ranker.newModel(100L)
        outside.m[2] = Ranker.m0(2) + 10.0
        assertFalse(outside.isValid())
        assertEquals(Ranker.UPDATE_RESET, Ranker.update(outside, x(1 to 1.0), 1.0, 1.0, 0.0))
        // A model that is a NaN at an index with x = 0 resets as well.
        val idle = Ranker.newModel(100L)
        idle.m[44] = Double.NaN
        assertEquals(Ranker.UPDATE_RESET, Ranker.update(idle, x(1 to 1.0), 1.0, 1.0, 0.0))
    }

    @Test
    fun `an input that is not finite or is huge is refused and changes nothing`() {
        val model = Ranker.newModel(100L)
        val before = model.copy()
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to Double.NaN), 1.0, 1.0, 0.0))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to 1.0e8), 1.0, 1.0, 0.0))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to -4.5), 1.0, 1.0, 0.0))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to 1.0), Double.NaN, 1.0, 0.0))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to 1.0), 1.0, Double.NaN, 0.0))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to 1.0), 1.0, 1.0, Double.POSITIVE_INFINITY))
        sameBits(before.m, model.m)
        sameBits(before.q, model.q)
        assertEquals(0, model.resets)
    }

    @Test
    fun `one huge but finite feature is skipped instead of wiping the model`() {
        val model = Ranker.newModel(100L)
        for (k in 0 until 20) Ranker.update(model, x(1 to 0.7, 2 to 0.3), 1.0, 1.0, 0.0)
        val learned = model.copy()
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.update(model, x(1 to 1.0e8), 1.0, 1.0, 0.0))
        sameBits(learned.m, model.m)
        assertFalse(model.isPrior())
    }

    // ── Batch refit ──────────────────────────────────────────────────────────

    /** Plays drawn from a known logistic model over 12 correlated features, as the methods report's synthetic check did. */
    private class Data(val xs: List<DoubleArray>, val ys: DoubleArray)

    private val FEATURES = intArrayOf(1, 2, 3, 8, 12, 13, 17, 18, 22, 23, 25, 26)

    private fun synth(n: Int, seed: Long, truth: DoubleArray): Data {
        val rng = BrainHash.Rng(seed)
        val xs = ArrayList<DoubleArray>(n)
        val ys = DoubleArray(n)
        for (k in 0 until n) {
            val v = DoubleArray(Ranker.D)
            v[0] = 1.0
            val common = (rng.uniform() - 0.5) * 2.0
            for ((j, i) in FEATURES.withIndex()) v[i] = BrainMath.clip(0.6 * common + 0.8 * (rng.uniform() - 0.5) * 2.0 * (if (j % 3 == 0) 1.0 else 0.8), -2.0, 2.0)
            var z = 0.0
            for (i in 0 until Ranker.D) z += truth[i] * v[i]
            val p = 1.0 / (1.0 + Math.exp(-z))
            ys[k] = if (rng.uniform() < p) 1.0 else 0.0
            xs.add(v)
        }
        return Data(xs, ys)
    }

    private fun logLoss(w: DoubleArray, d: Data): Double {
        var s = 0.0
        for (k in d.xs.indices) {
            var z = 0.0
            for (i in 0 until Ranker.D) z += w[i] * d.xs[k][i]
            val p = BrainMath.clip(1.0 / (1.0 + Math.exp(-z)), 1e-6, 1 - 1e-6)
            s += if (d.ys[k] > 0.5) -Math.log(p) else -Math.log(1 - p)
        }
        return s / d.xs.size
    }

    /** MAP with the prior N(M0, SD0^2) by Newton's method over the 13 active weights. */
    private fun batchMap(d: Data): DoubleArray {
        val idx = intArrayOf(0) + FEATURES
        val k = idx.size
        val w = DoubleArray(Ranker.D) { Ranker.m0(it) }
        w[0] = 0.0
        repeat(25) {
            val g = DoubleArray(k)
            val h = Array(k) { DoubleArray(k) }
            for (a in idx.indices) {
                val i = idx[a]
                g[a] += (w[i] - Ranker.m0(i)) / (Ranker.sd0(i) * Ranker.sd0(i))
                h[a][a] += 1.0 / (Ranker.sd0(i) * Ranker.sd0(i))
            }
            for (n in d.xs.indices) {
                var z = 0.0
                for (i in 0 until Ranker.D) z += w[i] * d.xs[n][i]
                val p = 1.0 / (1.0 + Math.exp(-z))
                for (a in 0 until k) {
                    g[a] += (p - d.ys[n]) * d.xs[n][idx[a]]
                    for (b in 0 until k) h[a][b] += p * (1 - p) * d.xs[n][idx[a]] * d.xs[n][idx[b]]
                }
            }
            val step = solve(h, g)
            for (a in 0 until k) w[idx[a]] -= step[a]
        }
        return w
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            val tv = v[c]; v[c] = v[piv]; v[piv] = tv
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (j in c until n) m[r][j] -= f * m[c][j]
                v[r] -= f * v[c]
            }
        }
        val out = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (j in r + 1 until n) s -= m[r][j] * out[j]
            out[r] = s / m[r][r]
        }
        return out
    }

    @Test
    fun `one online pass matches a batch refit on held-out log-loss`() {
        // A listener whose true weights differ from M0 by about one prior standard deviation on every active feature.
        val truth = DoubleArray(Ranker.D)
        val rng = BrainHash.Rng(77L)
        for (i in 0 until Ranker.D) truth[i] = Ranker.m0(i)
        truth[0] = 0.2
        for (i in FEATURES) truth[i] = Ranker.m0(i) + (if (rng.uniform() < 0.5) -1.0 else 1.0) * Ranker.sd0(i) * (0.6 + 0.8 * rng.uniform())
        for (n in intArrayOf(200, 1000, 4000)) {
            val train = synth(n, 1000L + n, truth)
            val test = synth(3000, 99L, truth)
            val model = Ranker.newModel(0L)
            for (k in train.xs.indices) Ranker.update(model, train.xs[k], train.ys[k], 1.0, 0.0)
            val online = logLoss(model.m, test)
            val batch = logLoss(batchMap(train), test)
            val prior = logLoss(Ranker.weights(0.5).also { it[0] = 0.0 }, test)
            val uninformed = Math.log(2.0)
            assertTrue(online < uninformed, "n=$n online $online below the uninformed $uninformed")
            assertTrue(online < prior, "n=$n online $online beats the prior $prior")
            assertTrue(online <= batch + 0.01, "n=$n online $online within 0.01 of the batch refit $batch")
        }
    }

    // ── Forgetting ───────────────────────────────────────────────────────────

    private fun trained(): RankerModel {
        val model = Ranker.newModel(1000L)
        for (k in 0 until 25) Ranker.applyEvent(model, x(1 to 0.9, 2 to 0.5, 22 to 0.6), if (k % 3 == 0) 0.0 else 1.0, 1.0, 0.0, 1000L, 10L + k)
        return model
    }

    @Test
    fun `forgetting does not depend on how often it is called`() {
        val once = trained()
        val twice = trained()
        val thrice = trained()
        Ranker.forget(once, 1060L)
        Ranker.forget(twice, 1030L)
        Ranker.forget(twice, 1060L)
        Ranker.forget(thrice, 1010L)
        Ranker.forget(thrice, 1045L)
        Ranker.forget(thrice, 1060L)
        sameBits(once.m, twice.m)
        sameBits(once.q, twice.q)
        sameBits(once.m, thrice.m)
        sameBits(once.q, thrice.q)
        // Sixty days away is thirty runs, not sixty.
        val thirty = trained()
        Ranker.forget(thirty, 1030L)
        sameBits(once.q, thirty.q)
        // And it pulls toward the prior: the precision fell, the mean moved toward M0.
        val fresh = trained()
        assertTrue(once.q[1] < fresh.q[1])
        assertTrue(Math.abs(once.m[1] - Ranker.m0(1)) < Math.abs(fresh.m[1] - Ranker.m0(1)))
    }

    @Test
    fun `a replayed play and a live play forget the same`() {
        // Live: the update path forgets up to the play's day, then updates. A worker call in between changes nothing.
        val live = trained()
        Ranker.forget(live, 1040L)
        Ranker.applyEvent(live, x(1 to 0.9), 1.0, 1.0, 0.0, 1050L, 500L)
        val replayed = trained()
        Ranker.applyEvent(replayed, x(1 to 0.9), 1.0, 1.0, 0.0, 1050L, 500L)
        sameBits(live.m, replayed.m)
        sameBits(live.q, replayed.q)
    }

    @Test
    fun `a clock set back makes no run and pulls the anchor back`() {
        val model = trained()
        val m = model.m.copyOf()
        val q = model.q.copyOf()
        assertEquals(0, Ranker.forget(model, 990L))
        sameBits(m, model.m)
        sameBits(q, model.q)
        assertEquals(990L, model.anchorDay)
        // From the corrected day the forgetting counts again.
        assertEquals(3, Ranker.forget(model, 993L))
        assertEquals(0, Ranker.forget(model, 993L))
    }

    @Test
    fun `a day written by a clock that was a year ahead does not stall the forgetting`() {
        val model = trained()
        Ranker.applyEvent(model, x(1 to 0.5), 1.0, 1.0, 0.0, 1000L + 365L, 900L)
        assertEquals(1365L, model.anchorDay)
        // The clock is corrected: the next play is on day 1005. The anchor comes back, and a week later the forgetting runs.
        Ranker.applyEvent(model, x(1 to 0.5), 1.0, 1.0, 0.0, 1005L, 901L)
        assertEquals(1005L, model.anchorDay)
        assertEquals(7, Ranker.forget(model, 1012L))
    }

    @Test
    fun `an event is applied once`() {
        val model = Ranker.newModel(10L)
        assertEquals(Ranker.UPDATE_APPLIED, Ranker.applyEvent(model, x(1 to 1.0), 1.0, 1.0, 0.0, 10L, 55L))
        val copy = model.copy()
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.applyEvent(model, x(1 to 1.0), 1.0, 1.0, 0.0, 10L, 55L))
        assertEquals(Ranker.UPDATE_SKIPPED, Ranker.applyEvent(model, x(1 to 1.0), 1.0, 1.0, 0.0, 10L, 54L))
        sameBits(copy.m, model.m)
        assertEquals(55L, model.lastEventId)
    }

    // ── Reset, weights, seeds ────────────────────────────────────────────────

    @Test
    fun `a reset returns to the prior and the count of plays starts again`() {
        val model = trained()
        assertTrue(model.updates > 0)
        val mark = model.lastEventId
        model.resetToPrior()
        assertTrue(model.isPrior())
        assertEquals(0L, model.updates)
        assertEquals(mark, model.lastEventId)
        // It then ranks exactly as the prior does, with the live bias.
        val w = Ranker.weightsFor(model, true, 0.7, null)
        sameBits(Ranker.weights(0.7), w)
    }

    @Test
    fun `the learned weights carry the live bias and the prior does not use them`() {
        val model = trained()
        val learned = Ranker.weightsFor(model, true, 0.75, null)
        assertEquals(model.m[0] + Ranker.biasOf(0.75), learned[0], 0.0)
        val prior = Ranker.weightsFor(model, false, 0.75, null)
        sameBits(Ranker.weights(0.75), prior)
        sameBits(Ranker.weights(0.75), Ranker.weightsFor(null, true, 0.75, null))
        // A model with a corrupt value never ranks: the prior does.
        val broken = trained()
        broken.m[5] = Double.NaN
        sameBits(Ranker.weights(0.75), Ranker.weightsFor(broken, true, 0.75, null))
        // A sample differs from the mean and is the same for the same seed.
        val a = Ranker.weightsFor(model, true, 0.75, 42L)
        val b = Ranker.weightsFor(model, true, 0.75, 42L)
        val c = Ranker.weightsFor(model, true, 0.75, 43L)
        sameBits(a, b)
        assertFalse(a.contentEquals(c))
        assertFalse(a.contentEquals(learned))
    }

    @Test
    fun `a model at its prior predicts bit for bit what the prior predicts`() {
        val model = Ranker.newModel(10L)
        for (p0 in doubleArrayOf(0.2, 0.5, 0.63, 0.8)) {
            val v = x(1 to 0.7, 2 to -0.3, 12 to 1.0, 22 to 0.45, 38 to 0.31)
            assertEquals(Ranker.predictPrior(v, p0).toRawBits(), Ranker.predict(model, v, Ranker.biasOf(p0)).toRawBits())
        }
    }

    @Test
    fun `a weight source is a snapshot and does not follow later changes`() {
        val model = trained()
        val source = Ranker.WeightSource(model, true, null)
        val before = source.weights(0.6)
        val precision = source.precision()!!
        Ranker.update(model, x(1 to 1.0, 2 to 1.0), 0.0, 4.0, 0.0)
        model.resetToPrior()
        sameBits(before, source.weights(0.6))
        sameBits(precision, source.precision()!!)
        assertTrue(source.usesLearned)
        val off = Ranker.WeightSource(model, false, null)
        assertFalse(off.usesLearned)
        assertNull(off.precision())
        sameBits(Ranker.weights(0.6), off.weights(0.6))
        // The prior precision handed out is a copy.
        val q = Ranker.priorPrecision()
        q[0] = 12345.0
        assertNotEquals(12345.0, Ranker.priorPrecision()[0])
        val pf = Ranker.precisionFor(null, true)
        pf[1] = -1.0
        assertNotEquals(-1.0, Ranker.precisionFor(null, true)[1])
    }

    @Test
    fun `a list seed cannot collide the way a separator could`() {
        // "a\u001f" + day bytes and "a" + a 0x1f-leading day used to be able to share bytes.
        val seeds = HashSet<Long>()
        val ids = listOf("", "a", "ab", "a\u001f", "a\u001fb", "daily:1", "daily:1\u001f")
        for (id in ids) for (day in longArrayOf(0L, 1L, 31L, 20_000L)) assertTrue(seeds.add(Ranker.listSeed(7L, id, day)), "$id $day")
        assertNotEquals(Ranker.listSeed(1L, "x", 5L), Ranker.listSeed(2L, "x", 5L))
        assertEquals(Ranker.listSeed(1L, "x", 5L), Ranker.listSeed(1L, "x", 5L))
    }

    @Test
    fun `one session in ten is a control session`() {
        var control = 0
        for (id in 1L..10_000L) if (Ranker.isControlSession(id)) control++
        assertTrue(control in 900..1100, "about a tenth: $control")
    }

    // ── The gate ─────────────────────────────────────────────────────────────

    private fun summary(plays: Int, learned: Double, prior: Double) = Gate.Summary(plays, plays, learned, prior, 0.6, 0.55, null, null)

    @Test
    fun `the gate is off until the learned weights win two weeks running, and off again at once`() {
        val week = 7L * 86_400_000L
        val t0 = 1_700_000_000_000L
        var state = Gate.State()
        assertFalse(state.useLearned)
        state = Gate.decide(state, summary(500, 0.50, 0.55), t0)
        assertFalse(state.useLearned, "one week of winning is not enough")
        assertEquals(1, state.winStreak)
        // A second evaluation inside the same week does not count again.
        val same = Gate.decide(state, summary(500, 0.50, 0.55), t0 + 86_400_000L)
        assertEquals(1, same.winStreak)
        assertFalse(same.useLearned)
        state = Gate.decide(state, summary(500, 0.50, 0.55), t0 + week)
        assertTrue(state.useLearned)
        state = Gate.decide(state, summary(500, 0.50, 0.55), t0 + 2 * week)
        assertTrue(state.useLearned)
        // Not lower: off at once, the streak starts again.
        state = Gate.decide(state, summary(500, 0.56, 0.55), t0 + 3 * week)
        assertFalse(state.useLearned)
        assertEquals(0, state.winStreak)
        // Equal is not lower either.
        assertFalse(Gate.decide(Gate.State(true, 3, t0), summary(500, 0.55, 0.55), t0 + week).useLearned)
    }

    @Test
    fun `too few graded plays keep the prior`() {
        val s = summary(Gate.MIN_PLAYS - 1, 0.1, 0.9)
        assertFalse(s.learnedBetter)
        assertFalse(Gate.decide(Gate.State(true, 5, 1L), s, 10_000_000_000L).useLearned)
    }

    @Test
    fun `log-loss and accuracy are the plain formulas`() {
        val p = doubleArrayOf(0.9, 0.2, 0.5)
        val y = booleanArrayOf(true, false, true)
        val expected = (-Math.log(0.9) - Math.log(0.8) - Math.log(0.5)) / 3.0
        assertEquals(expected, Gate.logLoss(p, y, 0, 3), 1e-12)
        assertEquals(1.0, Gate.accuracy(p, doubleArrayOf(0.5, 0.5, 0.5), y, 0, 3), 0.0)
        assertEquals(0.0, Gate.logLoss(p, y, 2, 2))
        // A prediction of exactly 0 or 1 stays finite.
        assertTrue(BrainMath.isFinite(Gate.logLoss(doubleArrayOf(0.0, 1.0), booleanArrayOf(true, false), 0, 2)))
    }

    // ── Saved state ──────────────────────────────────────────────────────────

    @Test
    fun `the saved state reads back bit for bit and a bad one is refused`() {
        val model = trained()
        val saved = LearnerCodec.Saved(model, Gate.State(true, 2, 1_700_000_000_000L), LearnReplay.VERSION, summary(900, 0.5, 0.55), 123L)
        val text = LearnerCodec.encode(saved)
        val back = LearnerCodec.decode(text)
        assertNotNull(back)
        sameBits(model.m, back!!.model.m)
        sameBits(model.q, back.model.q)
        assertEquals(model.anchorDay, back.model.anchorDay)
        assertEquals(model.lastEventId, back.model.lastEventId)
        assertEquals(model.updates, back.model.updates)
        assertTrue(back.gate.useLearned)
        assertEquals(text, LearnerCodec.encode(back))
        // Not the right format, a wrong length, a NaN, a precision of 0, a mean outside its box.
        assertNull(LearnerCodec.decode(text.replace("brain-learner-v1", "other")))
        assertNull(LearnerCodec.decode("{}"))
        assertNull(LearnerCodec.decode(""))
        val nan = trained()
        nan.m[7] = Double.NaN
        assertNull(LearnerCodec.decode(LearnerCodec.encode(LearnerCodec.Saved(nan, Gate.State(), 1, null, 0L))))
        val zero = trained()
        zero.q[9] = 0.0
        assertNull(LearnerCodec.decode(LearnerCodec.encode(LearnerCodec.Saved(zero, Gate.State(), 1, null, 0L))))
        val outside = trained()
        outside.m[9] = 50.0
        assertNull(LearnerCodec.decode(LearnerCodec.encode(LearnerCodec.Saved(outside, Gate.State(), 1, null, 0L))))
        // A fresh model with no summary round-trips as well.
        val fresh = LearnerCodec.Saved(Ranker.newModel(5L), Gate.State(), 1, null, 0L)
        assertNotNull(LearnerCodec.decode(LearnerCodec.encode(fresh)))
    }

    // ── Replay ───────────────────────────────────────────────────────────────

    @Test
    fun `the learned replay builds the same tables as the plain replay`() {
        for (listener in SyntheticListeners.all()) {
            val now = listener.nowMs
            val plain = Replay.rebuild(listener.events.iterator(), LibraryFacts.EMPTY, now)
            val learned = LearnReplay.run(listener.events.iterator(), LibraryFacts.EMPTY, now, emptySet())
            assertEquals(Snapshot.encode(plain), Snapshot.encode(learned.state), listener.name)
            assertEquals(listener.events.size, learned.events)
            assertEquals(listener.events.last().eventId, learned.lastEventId)
        }
    }

    @Test
    fun `a replay is deterministic and learns something from a listener with a taste`() {
        val listener = SyntheticListeners.loyal()
        val a = LearnReplay.run(listener.events.iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet())
        val b = LearnReplay.run(listener.events.iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet())
        sameBits(a.model.m, b.model.m)
        sameBits(a.model.q, b.model.q)
        assertTrue(a.model.updates > 1000)
        assertFalse(a.model.isPrior())
        assertTrue(a.model.isValid())
        // The first graded play is predicted by a model that is still the prior: the two predictions are the same bits.
        assertEquals(a.predictions.prior[0].toRawBits(), a.predictions.learned[0].toRawBits())
        assertTrue(a.predictions.size > 1000)
    }

    @Test
    fun `the learned weights beat the prior on a listener whose taste the prior does not know, and test 6 says so`() {
        val listener = SyntheticListeners.loyal()
        val run = LearnReplay.run(listener.events.iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet())
        val summary = Gate.summarize(run.predictions, listener.nowMs)
        assertTrue(summary.plays <= Gate.WINDOW)
        assertTrue(summary.learnedBetter, "learned ${summary.logLossLearned} against prior ${summary.logLossPrior}")
        val t6 = LearnReplay.test6(run.predictions, resamples = 100)
        assertTrue(t6.enoughPlays)
        assertTrue(t6.improvement > 0.0)
        assertTrue(t6.passes, "improvement ${t6.improvement}, interval ${t6.gainLow}..${t6.gainHigh}")
        // A gate that has seen it twice turns the learned weights on.
        val week = 7L * 86_400_000L
        var gate = Gate.decide(Gate.State(), summary, listener.nowMs)
        gate = Gate.decide(gate, summary, listener.nowMs + week)
        assertTrue(gate.useLearned)
    }

    @Test
    fun `test 6 keeps the prior below 2000 graded plays`() {
        val listener = SyntheticListeners.loyal()
        val run = LearnReplay.run(listener.events.take(600).iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet())
        val t6 = LearnReplay.test6(run.predictions, resamples = 50)
        assertFalse(t6.enoughPlays)
        assertFalse(t6.passes)
    }

    @Test
    fun `test 6 does not pass when the two predictors are the same`() {
        val n = 2500
        val rng = BrainHash.Rng(5L)
        val p = DoubleArray(n) { 0.3 + 0.4 * rng.uniform() }
        val pred = Gate.Predictions(
            LongArray(n) { it.toLong() }, LongArray(n) { (it / 12).toLong() }, BooleanArray(n) { rng.uniform() < 0.6 }, p, p.copyOf(), DoubleArray(n) { 0.5 },
        )
        val t6 = LearnReplay.test6(pred, resamples = 50)
        assertEquals(0.0, t6.improvement)
        assertFalse(t6.passes)
    }

    @Test
    fun `an imported play trains the origin feature but is predicted without it`() {
        val e = PlayEvent(
            eventId = 1L, origin = Origin.IMPORT_SPOTIFY, rid = 5L, primaryAid = 6L, startedAtMs = 1_700_000_000_000L,
            playedMs = 200_000, durationMs = 200_000, endReason = EndReason.TRACK_DONE,
        )
        val run = LearnReplay.run(listOf(e).iterator(), LibraryFacts.EMPTY, 1_700_000_100_000L, emptySet())
        assertTrue(run.model.m[LearnReplay.ORIGIN_BASE] != Ranker.m0(LearnReplay.ORIGIN_BASE), "the origin weight moved")
        assertEquals(1, run.predictions.size)
        assertEquals(run.predictions.prior[0].toRawBits(), run.predictions.learned[0].toRawBits())
        assertArrayEquals(doubleArrayOf(), DoubleArray(0))
    }

    @Test
    fun `the live path learns exactly what the replay learns`() {
        val listener = SyntheticListeners.loyal()
        val events = listener.events.take(700)
        val now = listener.nowMs
        val replayed = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        // The live path, play by play: begin the session, grade, learn from the state as it is, then apply the tables.
        val affinity = Affinity.State()
        Replay.applyFacts(affinity, LibraryFacts.EMPTY)
        val tracker = GradeTracker(affinity.completions)
        var session = SessionState()
        val live = Ranker.newModel(LearnReplay.dayOf(events[0], events[0].startedAtMs))
        for (e in events) {
            if (Session.isExpired(session, e.startedAtMs)) session = SessionState()
            Session.begin(session, e.startedAtMs, e.startReason)
            Brain.onEvent(affinity, tracker, session, e) { ev, g -> LearnReplay.applyLive(live, ev, g, false, affinity, session, now) }
        }
        Ranker.forget(live, BrainMath.localDay(now, 0))
        sameBits(replayed.model.m, live.m)
        sameBits(replayed.model.q, live.q)
        assertEquals(replayed.model.updates, live.updates)
        assertEquals(replayed.model.lastEventId, live.lastEventId)
    }

    @Test
    fun `plays at or below the reset mark build the tables but are not learned from`() {
        val listener = SyntheticListeners.loyal()
        val events = listener.events.take(600)
        val mark = events[399].eventId
        val run = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet(), 0, mark)
        val plain = Replay.rebuild(events.iterator(), LibraryFacts.EMPTY, listener.nowMs)
        assertEquals(Snapshot.encode(plain), Snapshot.encode(run.state), "the tables keep every play")
        // Only the 200 plays after the mark were learned from, so only they were predicted.
        assertTrue(run.predictions.size <= 200)
        assertTrue(run.model.updates <= 200)
        // A reset with nothing after it stays at the prior.
        val none = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, listener.nowMs, emptySet(), 0, events.last().eventId)
        assertTrue(none.model.isPrior())
        assertEquals(0, none.predictions.size)
    }
}
