package com.theveloper.pixelplay.brain

/**
 * The golden vectors of stage 3 (docs/brain-spec.md, 10.4): the learner's update and forgetting (`ekf.jsonl`), the seed of a list's
 * posterior sample and the control-session rule (`listseed.jsonl`), the replay that trains the ranker and the gate's figures
 * (`replay.jsonl`), and the learner's saved state (`learner.jsonl`). New files only: no earlier golden file changes.
 */
object Stage3Golden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["ekf.jsonl"] = ekf()
        files["listseed.jsonl"] = listSeeds()
        files["replay.jsonl"] = replay()
        files["learner.jsonl"] = learner()
        return files
    }

    private fun line(fn: String, input: BrainJson.Writer.() -> Unit, output: BrainJson.Writer.() -> Unit): String {
        val w = BrainJson.Writer().beginObject()
        w.string("fn", fn)
        w.beginObject("in"); w.input(); w.endObject()
        w.beginObject("out"); w.output(); w.endObject()
        return w.endObject().toString() + "\n"
    }

    private fun BrainHash.Rng.int(n: Int): Int = BrainMath.floorToLong(uniform() * n.toDouble()).toInt().coerceIn(0, n - 1)

    private fun hashDoubles(v: DoubleArray): Long {
        val b = ByteArray(v.size * 8)
        for (i in v.indices) {
            val bits = v[i].toRawBits()
            for (k in 0 until 8) b[i * 8 + k] = ((bits ushr (8 * k)) and 0xFF).toByte()
        }
        return BrainHash.fnv1a64(b)
    }

    /** Features the stages up to 3 can make non-zero in a replay; the test vectors draw from these. */
    private val ACTIVE = intArrayOf(1, 2, 3, 8, 12, 13, 14, 15, 16, 17, 18, 22, 23, 25, 26, 36, 37, 38, 20, 19)

    private fun BrainJson.Writer.model(m: RankerModel) {
        beginArray("m")
        for (i in 0 until Ranker.D) doubleItem(m.m[i])
        endArray()
        beginArray("q")
        for (i in 0 until Ranker.D) doubleItem(m.q[i])
        endArray()
        long("anchorDay", m.anchorDay); int("forgetDone", m.forgetDone); long("updates", m.updates)
        long("lastEventId", m.lastEventId); int("resets", m.resets)
    }

    // ── ekf.jsonl ────────────────────────────────────────────────────────────

    /**
     * Cases of one fresh model each (`Ranker.newModel(day0)`): `steps` of `[eventId, day, bias, r, c, [index, value]...]` applied with
     * `Ranker.applyEvent`; `out` lists the status of each step and the final model. Some steps carry values that must be refused
     * (a feature out of range, a NaN is not expressible in the file, so a repeated event id stands for that case).
     */
    private fun ekf(): String {
        val sb = StringBuilder()
        for (c in 0 until 6) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:ekf:$c"))
            val day0 = 20_000L + rng.int(100)
            val steps = 40 + 30 * c
            val model = Ranker.newModel(day0)
            val statuses = IntArray(steps)
            val w = BrainJson.Writer()
            w.beginArray()
            var day = day0
            var id = 1_000L
            val x = DoubleArray(Ranker.D)
            for (s in 0 until steps) {
                java.util.Arrays.fill(x, 0.0)
                x[0] = 1.0
                val count = 3 + rng.int(6)
                val idx = ArrayList<Int>()
                for (k in 0 until count) {
                    val i = ACTIVE[rng.int(ACTIVE.size)]
                    if (i !in idx) idx.add(i)
                }
                idx.sort()
                val vals = DoubleArray(idx.size)
                for ((k, i) in idx.withIndex()) {
                    // 1/8 steps of -2..2 with an occasional value past the refused bound.
                    vals[k] = if (s % 17 == 16 && k == 0) 5.0 else (rng.int(33) - 16).toDouble() / 8.0
                    x[i] = vals[k]
                }
                if (rng.uniform() < 0.3) day += 1L + rng.int(40) else if (rng.uniform() < 0.05) day -= 1L + rng.int(3)
                if (s % 23 == 22) id -= 5L else id += 1L + rng.int(1000)
                val r = if (rng.uniform() < 0.5) 1.0 else if (rng.uniform() < 0.6) 0.0 else (rng.int(9)).toDouble() / 8.0
                val cc = if (rng.uniform() < 0.1) 0.0 else 0.25 + (rng.int(18)).toDouble() / 4.0
                val bias = (rng.int(21) - 10).toDouble() / 10.0
                statuses[s] = Ranker.applyEvent(model, x, r, cc, bias, day, id)
                w.beginArray().longItem(id).longItem(day).doubleItem(bias).doubleItem(r).doubleItem(cc)
                w.beginArray()
                for ((k, i) in idx.withIndex()) w.beginArray().intItem(i).doubleItem(vals[k]).endArray()
                w.endArray().endArray()
            }
            w.endArray()
            val stepsJson = w.toString()
            sb.append(
                line("ekf", {
                    long("day0", day0); raw("steps", stepsJson)
                }, {
                    ints("status", statuses); model(model); long("mHash", hashDoubles(model.m)); long("qHash", hashDoubles(model.q))
                })
            )
        }
        // Forgetting alone, a pure function of the days and not of how often it is called.
        for (c in 0 until 4) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:forget:$c"))
            val base = Ranker.newModel(1_000L)
            val x = DoubleArray(Ranker.D)
            x[0] = 1.0; x[1] = 1.0; x[2] = 0.5; x[22] = 0.7
            for (k in 0 until 20) Ranker.applyEvent(base, x, if (k % 3 == 0) 0.0 else 1.0, 1.0, 0.0, 1_000L, 10L + k)
            val calls = LongArray(1 + rng.int(5))
            var d = 1_000L
            for (k in calls.indices) {
                d += (rng.int(50) - 5).toLong()
                calls[k] = d
            }
            val many = base.copy()
            val runs = IntArray(calls.size)
            for (k in calls.indices) runs[k] = Ranker.forget(many, calls[k])
            sb.append(
                line("forget", {
                    model(base); longs("days", calls)
                }, {
                    ints("runs", runs); model(many)
                })
            )
        }
        return sb.toString()
    }

    // ── listseed.jsonl ───────────────────────────────────────────────────────

    private fun listSeeds(): String {
        val sb = StringBuilder()
        val ids = listOf("", "daily:1", "daily:1\u001f", "your", "station:ünï\u00e7ode", "mix\uD83C\uDFB5", "a", "ab", "a\u001fb")
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:listseed"))
        for (id in ids) {
            for (k in 0 until 3) {
                val salt = rng.nextLong()
                val day = 19_000L + rng.int(2000)
                val seed = Ranker.listSeed(salt, id, day)
                val gauss = BrainHash.Rng(seed).gaussian12()
                sb.append(line("listSeed", { long("salt", salt); string("listId", id); long("localDay", day) }, { long("seed", seed); double("firstGaussian", gauss) }))
            }
        }
        for (k in 0 until 64) {
            val sessionId = if (k < 8) k.toLong() else rng.nextLong()
            sb.append(line("control", { long("sessionId", sessionId) }, { bool("control", Ranker.isControlSession(sessionId)) }))
        }
        // The posterior sample of one list, from a model that has seen a few plays.
        val model = Ranker.newModel(10L)
        val x = DoubleArray(Ranker.D)
        x[0] = 1.0; x[1] = 0.8; x[2] = 0.4; x[18] = 1.0
        for (k in 0 until 12) Ranker.applyEvent(model, x, if (k % 2 == 0) 1.0 else 0.0, 1.0, 0.2, 10L + k / 4, 100L + k)
        for (seed in longArrayOf(1L, 99L, Ranker.listSeed(7L, "your", 20_000L))) {
            val w = Ranker.sampleWeights(model, BrainHash.Rng(seed))
            sb.append(line("sample", { model(model); long("seed", seed) }, { long("hash", hashDoubles(w)); double("w0", w[0]); double("w1", w[1]); double("w2", w[2]) }))
        }
        return sb.toString()
    }

    // ── replay.jsonl ─────────────────────────────────────────────────────────

    private fun replay(): String {
        val sb = StringBuilder()
        for (c in 0 until 3) {
            val world = GoldenGenerator.World(BrainHash.fnv1a64("golden:learnreplay:$c"), historySize = 160 + c * 60, withFacts = false)
            val liked = world.tracks.filter { it.liked }.map { it.rid }.toSet()
            val result = LearnReplay.run(world.history.iterator(), LibraryFacts.EMPTY, world.nowMs, liked, world.tz)
            val s = Gate.summarize(result.predictions, world.nowMs)
            sb.append(
                line("learnReplay", {
                    beginArray("events"); for (e in world.history) rawItem(e.encode()); endArray()
                    longs("liked", liked.toLongArray().also { it.sort() }); long("nowMs", world.nowMs); int("tzNowMin", world.tz)
                }, {
                    model(result.model); int("events", result.events); long("lastEventId", result.lastEventId)
                    long("snapshotHash", BrainHash.fnv1a64(Snapshot.encode(result.state)))
                    int("graded", result.predictions.size)
                    long("learnedHash", hashDoubles(result.predictions.learned)); long("priorHash", hashDoubles(result.predictions.prior))
                    summary(s)
                })
            )
        }
        return sb.toString()
    }

    private fun BrainJson.Writer.summary(s: Gate.Summary) {
        int("plays", s.plays); int("playsTotal", s.playsTotal)
        double("llLearned", s.logLossLearned); double("llPrior", s.logLossPrior)
        double("accLearned", s.accLearned); double("accPrior", s.accPrior)
        if (s.accLearnedBefore == null) nullValue("accLearnedBefore") else double("accLearnedBefore", s.accLearnedBefore)
        if (s.accPriorBefore == null) nullValue("accPriorBefore") else double("accPriorBefore", s.accPriorBefore)
    }

    // ── learner.jsonl ────────────────────────────────────────────────────────

    private fun learner(): String {
        val sb = StringBuilder()
        // The gate: a state, a summary, the time, the next state.
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:gate"))
        for (k in 0 until 40) {
            val plays = if (k % 5 == 0) 100 + rng.int(150) else 300 + rng.int(1800)
            val llP = 0.55 + rng.int(30).toDouble() / 100.0
            val llL = llP + (rng.int(21) - 12).toDouble() / 100.0
            val prev = Gate.State(rng.uniform() < 0.4, rng.int(3), if (rng.uniform() < 0.2) 0L else 1_700_000_000_000L + rng.int(30) * 86_400_000L)
            val now = 1_700_000_000_000L + rng.int(60) * 86_400_000L
            val summary = Gate.Summary(plays, plays + rng.int(500), llL, llP, 0.6, 0.55, null, null)
            val next = Gate.decide(prev, summary, now)
            sb.append(
                line("gate", {
                    bool("useLearned", prev.useLearned); int("winStreak", prev.winStreak); long("atMs", prev.atMs)
                    int("plays", plays); double("llLearned", llL); double("llPrior", llP); long("nowMs", now)
                }, { bool("useLearned", next.useLearned); int("winStreak", next.winStreak); long("atMs", next.atMs) })
            )
        }
        // log-loss and accuracy over arrays.
        for (k in 0 until 6) {
            val n = 30 + rng.int(60)
            val p = DoubleArray(n) { (1 + rng.int(98)).toDouble() / 100.0 }
            val thr = DoubleArray(n) { 0.5 }
            val y = BooleanArray(n) { rng.uniform() < 0.6 }
            sb.append(
                line("logLoss", {
                    beginArray("p"); for (v in p) doubleItem(v); endArray()
                    beginArray("y"); for (v in y) intItem(if (v) 1 else 0); endArray()
                }, { double("logLoss", Gate.logLoss(p, y, 0, n)); double("accuracy", Gate.accuracy(p, thr, y, 0, n)) })
            )
        }
        // The saved state: the canonical text, and that it reads back.
        for (c in 0 until 2) {
            val model = Ranker.newModel(21_000L)
            val x = DoubleArray(Ranker.D)
            x[0] = 1.0; x[1] = 0.6; x[2] = 0.9; x[17] = 1.0; x[22] = 0.4
            for (k in 0 until 8 + 10 * c) Ranker.applyEvent(model, x, if (k % 3 == 0) 0.0 else 1.0, 1.0, 0.1, 21_000L + k / 3, 5_000L + k)
            val summary = if (c == 0) null else Gate.Summary(900, 1200, 0.51, 0.55, 0.7, 0.66, 0.68, 0.65)
            val saved = LearnerCodec.Saved(model, Gate.State(c == 1, c + 1, 1_700_000_000_000L), LearnReplay.VERSION, summary, 1_700_000_100_000L)
            val text = LearnerCodec.encode(saved)
            val back = LearnerCodec.decode(text)
            sb.append(
                line("codec", { model(model); bool("useLearned", c == 1) }, {
                    string("text", text); bool("readsBack", back != null && LearnerCodec.encode(back) == text)
                })
            )
        }
        for (bad in listOf("", "{}", "{\"format\":\"brain-learner-v1\"}", "not json")) {
            sb.append(line("codecBad", { string("text", bad) }, { bool("accepted", LearnerCodec.decode(bad) != null) }))
        }
        return sb.toString()
    }

    const val README_NOTES = """
        - `ekf.jsonl`, `listseed.jsonl`, `replay.jsonl`, `learner.jsonl` (stage 3; new files, nothing before them changed).
        - `ekf.jsonl` `ekf`: start from `Ranker.newModel(day0)`; for each step `[eventId, day, bias, r, c, [[index, value]...]]` build `x`
          (index 0 is 1, the listed indexes take their values, the rest are 0) and call `Ranker.applyEvent(model, x, r, c, bias, day,
          eventId)`; `status` is each call's result (0 applied, 1 skipped, 2 reset), then the model: `m`, `q`, `anchorDay`, `forgetDone`,
          `updates`, `lastEventId`, `resets` (`mHash`, `qHash`: FNV-1a 64 over the 8 little-endian bytes of each double). A value past 4
          or an event id at or below the watermark is skipped. `forget`: start from the model in `in`, call `Ranker.forget(model, day)`
          for each of `days` in order; `runs` are the results.
        - `listseed.jsonl`: `listSeed` is `Ranker.listSeed(salt, listId, localDay)` (8 little-endian bytes of the salt, 4 little-endian
          bytes of the UTF-8 length of the id, its UTF-8 bytes, 8 little-endian bytes of the day; FNV-1a 64), `firstGaussian` the first
          `Rng(seed).gaussian12()`. `control` is `Ranker.isControlSession`. `sample` is `Ranker.sampleWeights(model, Rng(seed))` for the
          model in `in`.
        - `replay.jsonl` `learnReplay`: `LearnReplay.run(events, LibraryFacts.EMPTY, nowMs, liked, tzNowMin)`; the model, the event count,
          the watermark, the hash of `Snapshot.encode(state)`, the number of graded plays, the hashes of the two prediction arrays, and
          `Gate.summarize(predictions, nowMs)` (`plays`, `playsTotal`, `llLearned`, `llPrior`, `accLearned`, `accPrior`, the two `Before`).
        - `learner.jsonl`: `gate` is `Gate.decide(State(useLearned, winStreak, atMs), Summary(plays, ..., llLearned, llPrior), nowMs)`;
          `logLoss` is `Gate.logLoss` and `Gate.accuracy` (threshold 0.5) over the arrays; `codec` is `LearnerCodec.encode` of the model in
          `in` (with the gate and summary the case gives) and that it decodes to the same text; `codecBad` texts must not be accepted.
    """
}
