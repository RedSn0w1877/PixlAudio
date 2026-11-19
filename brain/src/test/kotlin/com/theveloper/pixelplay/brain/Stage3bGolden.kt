package com.theveloper.pixelplay.brain

/**
 * The golden vectors of what stage 3's completion added (docs/brain-spec.md, 5.10, 10.4): the arm bandit (`bandit.jsonl`), the novelty
 * appetite (`novelty.jsonl`), context lift (`context.jsonl`), the shelf bandit (`shelfbandit.jsonl`) and the posteriors a replay builds
 * (`posteriors.jsonl`). New files only: no earlier golden file changes.
 */
object Stage3bGolden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["bandit.jsonl"] = bandit()
        files["novelty.jsonl"] = novelty()
        files["context.jsonl"] = context()
        files["shelfbandit.jsonl"] = shelfBandit()
        files["posteriors.jsonl"] = posteriors()
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

    // ── bandit.jsonl ─────────────────────────────────────────────────────────

    private fun bandit(): String {
        val sb = StringBuilder()
        for (c in 0 until 5) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:armbandit:$c"))
            val day0 = 20_000L + rng.int(100)
            val state = ArmBandit.newState(day0)
            val steps = 60 + 40 * c
            val w = BrainJson.Writer().beginArray()
            var day = day0
            val results = BooleanArray(steps)
            for (s in 0 until steps) {
                val family = rng.int(ArmBandit.FAMILIES)
                val arm = rng.int(ArmBandit.ARMS)
                val r = (rng.int(5)).toDouble() / 4.0
                val cc = if (rng.uniform() < 0.1) 0.0 else 0.25 + (rng.int(14)).toDouble() / 4.0
                val explore = rng.uniform() < 0.3
                if (rng.uniform() < 0.3) day += 1L + rng.int(20) else if (rng.uniform() < 0.05) day -= 1L + rng.int(3)
                results[s] = ArmBandit.update(state, family, arm, r, cc, explore, day)
                w.beginArray().intItem(family).intItem(arm).doubleItem(r).doubleItem(cc).intItem(if (explore) 1 else 0).longItem(day).endArray()
            }
            w.endArray()
            val stepsJson = w.toString()
            // Draws from the final state: one pick over a mask, by three seeds.
            val picks = IntArray(6)
            for (k in picks.indices) {
                val eligible = BooleanArray(ArmBandit.ARMS) { (it + k + c) % 3 != 0 }
                picks[k] = ArmBandit.pickArm(state, k % ArmBandit.FAMILIES, eligible, BrainHash.Rng(BrainHash.fnv1a64("golden:armdraw:$c:$k")))
            }
            val draws = DoubleArray(ArmBandit.ARMS) { ArmBandit.draw(state, ArmBandit.MIX, it, BrainHash.Rng(BrainHash.fnv1a64("golden:armtheta:$c:$it"))) }
            sb.append(
                line("armBandit", { long("day0", day0); raw("steps", stepsJson) }, {
                    beginArray("moved"); for (b in results) intItem(if (b) 1 else 0); endArray()
                    long("alphaHash", hashDoubles(state.alpha)); long("betaHash", hashDoubles(state.beta))
                    long("auditAlphaHash", hashDoubles(state.auditAlpha)); long("auditBetaHash", hashDoubles(state.auditBeta))
                    long("anchorDay", state.anchorDay); long("updates", state.updates)
                    beginArray("picks"); for (p in picks) intItem(p); endArray()
                    beginArray("theta"); for (d in draws) doubleItem(d); endArray()
                    beginArray("mean"); for (arm in 0 until ArmBandit.ARMS) doubleItem(ArmBandit.mean(state, ArmBandit.MIX, arm)); endArray()
                })
            )
        }
        // The discount alone, a pure function of the days.
        for (c in 0 until 3) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:armdiscount:$c"))
            val base = ArmBandit.newState(1_000L)
            for (k in 0 until 20) ArmBandit.update(base, rng.int(3), rng.int(ArmBandit.ARMS), (rng.int(2)).toDouble(), 1.0 + rng.int(3), k % 4 == 0, 1_000L)
            val calls = LongArray(1 + rng.int(5))
            var d = 1_000L
            for (k in calls.indices) {
                d += (rng.int(50) - 5).toLong()
                calls[k] = d
            }
            val many = base.copy()
            val runs = IntArray(calls.size)
            for (k in calls.indices) runs[k] = ArmBandit.discount(many, calls[k])
            sb.append(
                line("armDiscount", { long("alphaHash", hashDoubles(base.alpha)); long("anchorDay", base.anchorDay); longs("days", calls) }, {
                    ints("runs", runs); long("alphaHash", hashDoubles(many.alpha)); long("betaHash", hashDoubles(many.beta)); long("anchorDay", many.anchorDay)
                })
            )
        }
        return sb.toString()
    }

    // ── novelty.jsonl ────────────────────────────────────────────────────────

    private fun event(id: Long, startedAtMs: Long, finished: Boolean, flags: Int): PlayEvent = PlayEvent(
        eventId = id, origin = Origin.LIVE, rid = id, primaryAid = 7L, startedAtMs = startedAtMs, tzOffsetMin = 0,
        playedMs = if (finished) 200_000 else 3_000, durationMs = 200_000, startReason = StartReason.AUTOPLAY,
        endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT, flags = flags, surface = Surface.AUTOPLAY, arm = Arm.RADIO,
    )

    private fun novelty(): String {
        val sb = StringBuilder()
        for (c in 0 until 4) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:novelty:$c"))
            val day0 = 20_000L + rng.int(50)
            val state = NoveltyAppetite.newState(day0)
            val w = BrainJson.Writer().beginArray()
            var day = day0
            val n = 80 + 60 * c
            for (i in 0 until n) {
                if (rng.uniform() < 0.25) day += 1L + rng.int(9)
                val t = day * BrainMath.DAY_MS + rng.int(86_000) * 1000L
                val finished = rng.uniform() < 0.6
                val novel = rng.uniform() < 0.35
                val recommender = rng.uniform() < 0.7
                val flags = if (rng.uniform() < 0.08) EventFlags.LIKED_DURING else 0
                val e = event(1_000L + i, t, finished, flags)
                NoveltyAppetite.onPlay(state, e, Reward.grade(e, Reward.Context()), novel, recommender, day)
                w.beginArray().longItem(t).longItem(day).intItem(if (finished) 1 else 0).intItem(if (novel) 1 else 0).intItem(if (recommender) 1 else 0).intItem(flags).endArray()
            }
            w.endArray()
            val stepsJson = w.toString()
            val nowMs = (day + 1) * BrainMath.DAY_MS
            NoveltyAppetite.advance(state, day + 1)
            sb.append(
                line("novelty", { long("day0", day0); raw("plays", stepsJson); long("nowMs", nowMs) }, {
                    double("fAlpha", state.fAlpha); double("fBeta", state.fBeta); double("nAlpha", state.nAlpha); double("nBeta", state.nBeta)
                    double("nuLong", state.nuLong); double("nu0", NoveltyAppetite.nu0(state, day + 1)); double("target", NoveltyAppetite.target(state, day + 1))
                    double("boost", NoveltyAppetite.boost(state, nowMs)); double("nu", NoveltyAppetite.nu(state, nowMs))
                    long("updates", state.updates); int("boosts", state.boosts.size); int("dayRows", state.days.size)
                })
            )
        }
        return sb.toString()
    }

    // ── context.jsonl ────────────────────────────────────────────────────────

    private fun context(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:context"))
        // Buckets: a spread of times and offsets.
        for (k in 0 until 40) {
            val t = 1_700_000_000_000L + rng.int(400) * 86_400_000L + rng.int(86_400) * 1000L
            val tz = (rng.int(49) - 24) * 30
            sb.append(line("bucket", { long("startedAtMs", t); int("tzOffsetMin", tz) }, { int("daypart", ContextLift.daypart(t, tz)); int("bucket", ContextLift.bucket(t, tz)) }))
        }
        for (c in 0 until 3) {
            val now = 1_750_000_000_000L + c * 3_600_000L
            val b = ContextLift.Builder(now)
            val w = BrainJson.Writer().beginArray()
            val n = 150 + 150 * c
            for (i in 0 until n) {
                val island = rng.int(4) - 1
                val t = now - rng.int(400) * 86_400_000L - rng.int(86_400) * 1000L
                val tz = if (i % 7 == 0) 120 else 0
                val cc = if (rng.uniform() < 0.1) 0.0 else 0.5 + rng.int(6).toDouble() / 4.0
                val r = rng.int(5).toDouble() / 4.0
                b.add(island, t, tz, cc, r)
                w.beginArray().intItem(island).longItem(t).intItem(tz).doubleItem(cc).doubleItem(r).endArray()
            }
            w.endArray()
            val table = b.build()
            val plays = table.plays
            sb.append(
                line("context", { long("nowMs", now); raw("plays", w.toString()) }, {
                    ints("plays", plays)
                    beginArray("lift")
                    for (id in 0 until 3) for (bk in 0 until ContextLift.BUCKETS) doubleItem(table.lift(id, bk))
                    endArray()
                    beginArray("leader")
                    for (bk in 0 until ContextLift.BUCKETS) intItem(table.leader(bk, listOf(0 to 1.0, 1 to 2.0, 2 to 0.5)))
                    endArray()
                })
            )
        }
        return sb.toString()
    }

    // ── shelfbandit.jsonl ────────────────────────────────────────────────────

    private fun shelfBandit(): String {
        val sb = StringBuilder()
        for (c in 0 until 3) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:shelf:$c"))
            val state = ShelfBandit.newState()
            val w = BrainJson.Writer().beginArray()
            var day = 20_000L
            val visits = 20 + 15 * c
            val rows = ArrayList<ShelfBandit.VisitRow>()
            for (v in 0 until visits) {
                if (rng.uniform() < 0.4) day += 1L + rng.int(3)
                val daypart = rng.int(4)
                val n = 3 + rng.int(5)
                val shown = IntArray(n) { rng.int(ShelfBandit.TYPES) }
                val lastSeen = rng.int(n + 1) - 1
                val used = if (rng.uniform() < 0.5) rng.int(n) else -1
                val moved = ShelfBandit.visit(state, daypart, shown, lastSeen, used, day)
                for (p in shown.indices) rows.add(ShelfBandit.VisitRow(v.toLong(), shown[p], daypart, p, p <= lastSeen, p == used, day))
                w.beginArray().intItem(daypart).intItem(lastSeen).intItem(used).longItem(day).intItem(moved)
                w.beginArray(); for (t in shown) w.intItem(t); w.endArray(); w.endArray()
            }
            w.endArray()
            val types = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            val order = ShelfBandit.order(state, 700L + c, c % 4, types, 123L + c, day + 20)
            val rebuilt = ShelfBandit.rebuild(rows)
            sb.append(
                line("shelfBandit", { raw("visits", w.toString()) }, {
                    long("alphaHash", hashDoubles(state.alpha)); long("betaHash", hashDoubles(state.beta)); long("updates", state.updates)
                    beginArray("lastSeenDay"); for (d in state.lastSeenDay) longItem(d); endArray()
                    if (order == null) nullValue("order") else { beginArray("order"); for (t in order) intItem(t); endArray() }
                    bool("rebuiltEqual", java.util.Arrays.equals(rebuilt.alpha, state.alpha) && java.util.Arrays.equals(rebuilt.beta, state.beta))
                    string("text", ShelfBanditCodec.encode(state))
                })
            )
        }
        return sb.toString()
    }

    // ── posteriors.jsonl ─────────────────────────────────────────────────────

    private fun posteriors(): String {
        val sb = StringBuilder()
        for (c in 0 until 3) {
            val world = GoldenGenerator.World(BrainHash.fnv1a64("golden:learnreplay:$c"), historySize = 160 + c * 60, withFacts = false)
            val liked = world.tracks.filter { it.liked }.map { it.rid }.toSet()
            val result = LearnReplay.run(world.history.iterator(), LibraryFacts.EMPTY, world.nowMs, liked, world.tz)
            val text = PosteriorCodec.encode(result.posteriors)
            sb.append(
                line("posteriors", {
                    beginArray("events"); for (e in world.history) rawItem(e.encode()); endArray()
                    longs("liked", liked.toLongArray().also { it.sort() }); long("nowMs", world.nowMs); int("tzNowMin", world.tz)
                }, {
                    string("text", text); bool("readsBack", PosteriorCodec.decode(text)?.let { PosteriorCodec.encode(it) == text } == true)
                    long("armUpdates", result.posteriors.arms.updates); long("novelUpdates", result.posteriors.appetite.updates)
                })
            )
        }
        for (bad in listOf("", "{}", "{\"format\":\"brain-posteriors-v1\"}", "not json")) {
            sb.append(line("posteriorsBad", { string("text", bad) }, { bool("accepted", PosteriorCodec.decode(bad) != null) }))
        }
        return sb.toString()
    }

    const val README_NOTES = """
        - `bandit.jsonl`, `novelty.jsonl`, `context.jsonl`, `shelfbandit.jsonl`, `posteriors.jsonl` (the completion of stage 3; new files, nothing before them changed).
        - `bandit.jsonl` `armBandit`: start from `ArmBandit.newState(day0)`; each step `[family, arm, r, c, explore, day]` is `ArmBandit.update`;
          `moved` is its result. Then the hashes (FNV-1a 64 over the 8 little-endian bytes of each double) of `alpha`, `beta`, `auditAlpha`,
          `auditBeta`, the anchor day and update count; `picks[k]` is `ArmBandit.pickArm(state, k mod 3, eligible, Rng(fnv1a64("golden:armdraw:<c>:<k>")))`
          with `eligible[a] = (a + k + c) mod 3 != 0`; `theta[a]` is `ArmBandit.draw(state, MIX, a, Rng(fnv1a64("golden:armtheta:<c>:<a>")))`; `mean[a]` is
          `ArmBandit.mean(state, MIX, a)`. `armDiscount`: start from a state with the given `alphaHash` and `anchorDay` (the file does not
          hold the state: a port builds the same one from its own `update`), call `ArmBandit.discount(state, day)` for each of `days`.
        - `novelty.jsonl` `novelty`: start from `NoveltyAppetite.newState(day0)`; each play `[startedAtMs, day, finished, novel, recommender, flags]` becomes
          the event of `Stage3bGolden.event` (a live autoplay play of arm RADIO, 200 s long, finished or skipped after 3 s) and is given to
          `NoveltyAppetite.onPlay`; after the last, `advance(state, lastDay + 1)`; the figures are read at `nowMs`.
        - `context.jsonl` `bucket`: `ContextLift.daypart` and `bucket` of a time and an offset. `context`: add every play
          `[island, startedAtMs, tzOffsetMin, c, r]` to `ContextLift.Builder(nowMs)`; `plays` per bucket, `lift[island * 8 + bucket]` for islands 0..2,
          and `leader[bucket]` of `table.leader(bucket, [(0, 1.0), (1, 2.0), (2, 0.5)])`.
        - `shelfbandit.jsonl` `shelfBandit`: from `ShelfBandit.newState()` each visit `[daypart, lastSeen, used, day, moved, [types]]` is
          `ShelfBandit.visit`; then `order(state, 700 + c, c mod 4, [0..9], 123 + c, lastDay + 20)`; `rebuiltEqual` says `ShelfBandit.rebuild` of
          the impression rows reproduces the posteriors; `text` is `ShelfBanditCodec.encode`.
        - `posteriors.jsonl` `posteriors`: `LearnReplay.run` of the events (as in `replay.jsonl`), `PosteriorCodec.encode` of its `posteriors`;
          `posteriorsBad` texts must not be accepted.
    """
}
