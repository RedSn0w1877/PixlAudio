package com.theveloper.pixelplay.brain

/**
 * The golden vectors of stage 4 (docs/brain-spec.md, 10.4), milestone 1: the served feature vector's quantisation and the replay that
 * trains on it (`servedvec.jsonl`), and the gate's 1% margin with the test 6 interval (`gate2.jsonl`). New files only: no earlier golden
 * file changes. The tool request rules of `AssistantRules` have their own file (`assistant.jsonl`), added in the same stage.
 */
object Stage4Golden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["servedvec.jsonl"] = servedVec()
        files["gate2.jsonl"] = gate2()
        files["assistant.jsonl"] = assistant()
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

    private fun BrainJson.Writer.doubles(name: String, v: DoubleArray) {
        beginArray(name)
        for (d in v) doubleItem(d)
        endArray()
    }

    /** Features that are non-zero in a served vector. */
    private val ACTIVE = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 23, 24, 25, 26, 36, 37, 38, 39)

    private fun randomVector(rng: BrainHash.Rng): DoubleArray {
        val x = DoubleArray(Ranker.D)
        x[0] = 1.0
        val count = 4 + rng.int(12)
        for (k in 0 until count) {
            val i = ACTIVE[rng.int(ACTIVE.size)]
            x[i] = (rng.int(49) - 24).toDouble() / 12.0 + rng.uniform() / 5000.0
        }
        return x
    }

    // ── servedvec.jsonl ──────────────────────────────────────────────────────

    private fun servedVec(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:servedvec"))
        // Quantisation: a vector in, the hex out, and the vector the hex reads back as.
        for (k in 0 until 24) {
            val x = if (k == 0) DoubleArray(Ranker.D) else if (k == 1) DoubleArray(Ranker.D) { 9.0 - 0.5 * (it % 40) } else randomVector(rng)
            val bytes = ServedVector.quantize(x)
            val back = ServedVector.dequantize(bytes)!!
            sb.append(line("quantize", { doubles("x", x) }, { string("hex", ServedVector.toHex(bytes)); doubles("back", back) }))
        }
        // What does not read: a wrong length, a character that is not lower-case hex.
        val good = ServedVector.toHex(ServedVector.quantize(randomVector(rng)))
        for (bad in listOf("", good.substring(2), good + "00", good.uppercase().replaceFirst('0', 'g'), good.replaceRange(4, 5, "Z"))) {
            sb.append(line("hexBad", { string("hex", bad) }, { bool("accepted", ServedVector.fromHex(bad) != null) }))
        }
        // A replay over events that carry a served vector and events that do not: the model and the predictions, which must be the same
        // whatever the platform, and which differ from the replay of the same plays without the vectors.
        for (c in 0 until 3) {
            val r = BrainHash.Rng(BrainHash.fnv1a64("golden:servedreplay:$c"))
            val n = 120 + 40 * c
            val events = ArrayList<PlayEvent>(n)
            val day0 = 20_000L
            for (i in 0 until n) {
                val finished = r.uniform() < 0.6
                val started = (day0 + i / 12) * BrainMath.DAY_MS + (i % 12) * 600_000L
                val served = if (i % 5 == 4) null else ServedVector.quantize(randomVector(r))
                events.add(
                    PlayEvent(
                        eventId = (started shl 16) or i.toLong(), origin = Origin.LIVE, rid = 1000L + r.int(40), primaryAid = 50L + r.int(12),
                        startedAtMs = started, tzOffsetMin = 0, playedMs = if (finished) 200_000 else 3_000, durationMs = 200_000,
                        startReason = StartReason.AUTOPLAY, endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT,
                        surface = Surface.AUTOPLAY, arm = Arm.RADIO, sessionId = (started / 3_600_000L), servedX = served,
                    )
                )
            }
            val now = (day0 + n / 12 + 2) * BrainMath.DAY_MS
            val withVec = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet(), 0)
            val without = LearnReplay.run(events.map { stripped(it) }.iterator(), LibraryFacts.EMPTY, now, emptySet(), 0)
            sb.append(
                line("servedReplay", {
                    beginArray("events"); for (e in events) rawItem(e.encode()); endArray()
                    long("nowMs", now)
                }, {
                    doubles("m", withVec.model.m); doubles("q", withVec.model.q); long("updates", withVec.model.updates)
                    int("graded", withVec.predictions.size)
                    long("learnedHash", hashDoubles(withVec.predictions.learned)); long("priorHash", hashDoubles(withVec.predictions.prior))
                    long("learnedHashWithout", hashDoubles(without.predictions.learned))
                })
            )
        }
        return sb.toString()
    }

    private fun stripped(e: PlayEvent): PlayEvent = PlayEvent(
        eventId = e.eventId, origin = e.origin, rid = e.rid, primaryAid = e.primaryAid, startedAtMs = e.startedAtMs,
        tzOffsetMin = e.tzOffsetMin, playedMs = e.playedMs, durationMs = e.durationMs, startReason = e.startReason,
        endReason = e.endReason, surface = e.surface, arm = e.arm, sessionId = e.sessionId,
    )

    // ── gate2.jsonl ──────────────────────────────────────────────────────────

    private fun gate2(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:gate2"))
        for (k in 0 until 60) {
            val plays = if (k % 6 == 0) 100 + rng.int(150) else 300 + rng.int(2200)
            val total = plays + rng.int(2500)
            val llP = 0.55 + rng.int(30).toDouble() / 100.0
            // Gains around the 1% line: from a loss of 1% to a gain of 4%.
            val llL = llP * (1.0 - (rng.int(51) - 10).toDouble() / 1000.0)
            val prev = Gate.State(rng.uniform() < 0.4, rng.int(3), if (rng.uniform() < 0.2) 0L else 1_700_000_000_000L + rng.int(30) * 86_400_000L)
            val now = 1_700_000_000_000L + rng.int(60) * 86_400_000L
            val above = rng.uniform() < 0.5
            val summary = Gate.Summary(plays, total, llL, llP, 0.6, 0.55, null, null)
            val next = Gate.decide(prev, summary, now, above)
            sb.append(
                line("gate2", {
                    bool("useLearned", prev.useLearned); int("winStreak", prev.winStreak); long("atMs", prev.atMs)
                    int("plays", plays); int("playsTotal", total); double("llLearned", llL); double("llPrior", llP); long("nowMs", now)
                    bool("intervalAboveZero", above)
                }, {
                    bool("useLearned", next.useLearned); int("winStreak", next.winStreak); long("atMs", next.atMs)
                    bool("clearlyBetter", summary.learnedClearlyBetter)
                })
            )
        }
        return sb.toString()
    }

    // ── assistant.jsonl ──────────────────────────────────────────────────────

    private fun assistant(): String = AssistantGolden.generate()

    const val README_NOTES = """
        - `servedvec.jsonl`, `gate2.jsonl`, `assistant.jsonl` (stage 4, milestone 1; new files, nothing before them changed).
        - `servedvec.jsonl` `quantize`: `ServedVector.quantize(x)` is `hex` (51 little-endian signed 16-bit integers, each `floor(x * 4096 + 0.5)`
          clipped to +-32767, as lower-case hex: 204 characters); `back` is `ServedVector.dequantize` of it (`q / 4096`). `hexBad`: `fromHex` of
          the text must not be accepted (wrong length, upper case, a character that is not hex). `servedReplay`: `LearnReplay.run(events,
          LibraryFacts.EMPTY, nowMs, no favourites, tz 0)` over events whose `servedX` is the hex in the line (an event without it uses the
          reduced vector); `m`, `q`, `updates`, the number of graded plays and the hashes of the two prediction arrays; `learnedHashWithout`
          is the learned prediction hash of the same events with `servedX` removed (it must differ from `learnedHash`).
        - `gate2.jsonl` `gate2`: `Gate.decide(State(useLearned, winStreak, atMs), Summary(plays, playsTotal, llLearned, llPrior, ...), nowMs,
          intervalAboveZero)`; `clearlyBetter` is `Summary.learnedClearlyBetter` (better by at least 1%). A week that is better but not by 1%, or
          from 2,000 graded plays without an interval above zero, is not a win.
    """
}
