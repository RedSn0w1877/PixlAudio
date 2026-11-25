package com.theveloper.pixelplay.brain

import java.io.File

/**
 * The golden vectors of stage 5 (docs/brain-spec.md, 3.3, 6.5, 10.4): the on-device estimator on synthetic PCM
 * (`audiofeatures.jsonl`) and flow ordering (`flow.jsonl`). New files only: no earlier golden file changes, because a request without
 * [ListBuilder.Request.flow] builds exactly what it built before.
 */
object Stage5Golden {

    fun generate(project: File): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["audiofeatures.jsonl"] = audio()
        files["flow.jsonl"] = flow(GenreAtlas.parse(Stage2bGolden.atlasText(project)))
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

    // ── audiofeatures.jsonl ──────────────────────────────────────────────────

    private fun audio(): String {
        val sb = StringBuilder()
        for (spec in AudioFixtures.specs()) {
            val pcm = AudioFixtures.render(spec)
            val r = AudioFixtures.estimate(spec, pcm)
            sb.append(line("estimate", {
                string("kind", spec.kind); int("sampleRate", spec.sampleRate); int("channels", spec.channels); int("seconds", spec.seconds)
                int("param", spec.param); int("levelDbX10", spec.levelDbX10); long("seed", spec.seed); int("block", 4096)
            }, {
                long("pcmHash", AudioFixtures.hash(pcm)); int("samples", pcm.size)
                int("bpmX10", r.bpmX10); int("tempoConf", r.tempoConf); int("lufsX10", r.lufsX10); int("analysedMs", r.analysedMs)
                int("onsetX1000", r.onsetX1000); int("version", r.version)
            }))
        }
        for (x in doubleArrayOf(0.0, 1e-6, 0.0027, 0.05, 0.1198, 0.13, 0.2, 0.5)) {
            sb.append(line("tanSmall", { double("x", x) }, { double("y", AudioFeatures.tanSmall(x)) }))
        }
        for (d in longArrayOf(0L, 20_000L, 40_000L, 50_000L, 61_000L, 180_000L, 600_000L, -5L)) {
            sb.append(line("windowStartMs", { long("durationMs", d) }, { long("startMs", AudioFeatures.windowStartMs(d)) }))
        }
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:choose"))
        for (k in 0 until 40) {
            val deezer = if (rng.int(3) == 0) 0 else 600 + rng.int(1400)
            val measured = if (rng.int(4) == 0) null else AudioFeatures.Result(rng.int(3) * (600 + rng.int(1400)) / 2, rng.int(1001),
                if (rng.int(3) == 0) AudioFeatures.LOUDNESS_UNKNOWN else -200 + rng.int(190), 40_000)
            val dGain = if (rng.int(2) == 0) TrackDna.GAIN_UNKNOWN else -150 + rng.int(120)
            val t = AudioFeatures.chooseTempo(deezer, measured)
            val g = AudioFeatures.chooseGain(dGain, measured)
            sb.append(line("choose", {
                int("deezerBpmX10", deezer); int("deezerGainX10", dGain)
                if (measured == null) nullValue("measured") else {
                    beginObject("measured"); int("bpmX10", measured.bpmX10); int("tempoConf", measured.tempoConf); int("lufsX10", measured.lufsX10); endObject()
                }
            }, { int("bpmX10", t[0]); int("tempoSource", t[1]); int("gainDbX10", g[0]); int("gainMeasured", g[1]) }))
        }
        return sb.toString()
    }

    // ── flow.jsonl ───────────────────────────────────────────────────────────

    private fun facts(rng: BrainHash.Rng): Flow.Facts = Flow.Facts(
        tempoBpmX10 = if (rng.int(5) == 0) 0 else 600 + rng.int(1200),
        gainDbX10 = if (rng.int(6) == 0) TrackDna.GAIN_UNKNOWN else -160 + rng.int(130),
        energy = if (rng.int(6) == 0) -1 else rng.int(256),
    )

    private fun BrainJson.Writer.facts(name: String, f: Flow.Facts) {
        beginArray(name).intItem(f.tempoBpmX10).intItem(f.gainDbX10).intItem(f.energy).endArray()
    }

    private fun flow(atlas: GenreAtlas): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:flow"))
        for (k in 0 until 200) {
            val a = if (k < 6) intArrayOf(0, 1200, 1200, 600, 1200, 1740)[k] else 400 + rng.int(1800)
            val b = if (k < 6) intArrayOf(1200, 0, 1200, 1200, 1800, 870)[k] else 400 + rng.int(1800)
            sb.append(line("tempoDist", { int("a", a); int("b", b) }, { double("d", Flow.tempoDist(a, b)) }))
        }
        for (k in 0 until 200) {
            val p = facts(rng)
            val n = facts(rng)
            sb.append(line("step", { facts("prev", p); facts("next", n) }, { double("cost", Flow.step(p, n)) }))
        }
        for (count in intArrayOf(1, 2, 5, 12, 30)) {
            for (mid in intArrayOf(-1, 0, 90, 128, 240)) {
                for (slot in 0 until count step maxOf(1, count / 6)) {
                    val e = rng.int(256)
                    sb.append(line("arc", { int("mid", mid); int("slot", slot); int("count", count); int("energy", e) }, {
                        int("target", Flow.target(mid, slot, count)); double("cost", Flow.arc(mid, slot, count, e))
                    }))
                }
            }
        }
        // Energy with the tempo nudge (3.3, stage 5) on vectors of described genres.
        val described = atlas.allIds.filter { atlas.descriptor(it) != null }
        for (k in 0 until 80) {
            val n = 1 + rng.int(3)
            val tags = ArrayList<Tags.Tag>(n)
            val used = HashSet<Int>()
            for (j in 0 until n) {
                val id = described[rng.int(described.size)]
                if (used.add(id)) tags.add(Tags.Tag(id, 255 - j * 60))
            }
            val v = Tags.Vector(tags)
            val gain = if (rng.int(3) == 0) TrackDna.GAIN_UNKNOWN else -150 + rng.int(120)
            val tempo = if (rng.int(4) == 0) 0 else 600 + rng.int(1200)
            val f = TrackDna.feel(atlas, v, gain, tempo)
            sb.append(line("feelTempo", {
                beginArray("tags"); for (t in v.tags) stringItem("${t.atlasId}:${t.q}"); endArray()
                int("gainDbX10", gain); int("tempoBpmX10", tempo)
            }, { int("energy", f.energy); int("valence", f.valence) }))
        }
        // Whole lists: the same pool built with and without flow, finite (arc) and endless.
        for (c in 0 until 24) {
            val n = 30 + rng.int(40)
            val scored = ArrayList<ListBuilder.Scored>(n)
            val factMap = HashMap<Long, Flow.Facts>()
            for (i in 0 until n) {
                val rid = 1000L + i * 7L + c.toLong() * 100_000L
                val aid = 50L + rng.int(n / 2 + 2).toLong()
                val novel = rng.int(3) == 0
                val z = rng.uniform() * 2.0 - 1.0
                val p = 0.2 + rng.uniform() * 0.7
                scored.add(ListBuilder.Scored(Candidate(rid, aid, albumKey = 0L, inLibrary = !novel), Arm.RADIO, z, p, novel, 0, -1, 0, 0L, 500))
                factMap[rid] = facts(rng)
            }
            val finite = c % 2 == 0
            val count = if (finite) 12 + rng.int(10) else 5
            val lead = facts(rng)
            val graph = Graph.Builder().build()
            for (withFlow in booleanArrayOf(false, true)) {
                val ctx = if (withFlow) Flow.Context(factMap, lead = if (finite) Flow.Facts.UNKNOWN else lead, arc = finite) else null
                val req = ListBuilder.Request(
                    count = count, prior = emptyList(), nu = 0.25, tieSeed = BrainHash.fnv1a64("flow list $c"), nowMs = 1_763_000_000_000L,
                    tzOffsetMin = 60, finite = finite, flow = ctx,
                )
                val picks = ListBuilder.build(req, scored, graph)
                val order = picks.map { factMap[it.scored.c.rid]!! }
                sb.append(line("build", {
                    int("case", c); bool("flow", withFlow); bool("finite", finite); int("count", count); double("nu", 0.25)
                    long("tieSeed", req.tieSeed); long("nowMs", req.nowMs); int("tzOffsetMin", req.tzOffsetMin)
                    if (ctx != null) { facts("lead", ctx.lead); int("mid", ctx.mid) }
                    beginArray("pool")
                    for (s in scored) {
                        val f = factMap[s.c.rid]!!
                        rawItem(BrainJson.Writer().beginObject().long("rid", s.c.rid).long("aid", s.c.aid).bool("novel", s.novel)
                            .double("z", s.z).double("p", s.p).int("tempo", f.tempoBpmX10).int("gain", f.gainDbX10).int("energy", f.energy)
                            .endObject().toString())
                    }
                    endArray()
                }, {
                    beginArray("picks")
                    for (pk in picks) rawItem(BrainJson.Writer().beginObject().long("rid", pk.scored.c.rid).int("kind", pk.kind).long("key", pk.key).endObject().toString())
                    endArray()
                    double("roughness", Flow.roughness(order))
                }))
            }
        }
        return sb.toString()
    }

    val README_NOTES = """
        ## Stage 5 (`audiofeatures.jsonl`, `flow.jsonl`)

        `estimate`: render the fixture with `AudioFixtures.render` (test source; port it operation by operation: SplitMix64 noise, Taylor
          `sinT` / `cosT` to x^21, the two-term sine recurrence, `x * 32767` rounded half up and clipped), check `pcmHash` (FNV-1a 64 of
          the samples as 16-bit little-endian bytes) and `samples`, then feed `AudioFeatures.Estimator(sampleRate, channels)` in blocks of
          `block` frames and compare every field of the result. `tanSmall`, `windowStartMs`, `choose` (`chooseTempo` + `chooseGain`) are
          the small helpers. `flow.jsonl`: `tempoDist`, `step`, `arc` (`Flow.target` and `Flow.arc`), `feelTempo` (`TrackDna.feel` with
          the tempo nudge, on the bundled atlas), and `build`: a `ListBuilder.build` of the pool (`Candidate(rid, aid, inLibrary = !novel)`,
          arm RADIO, `bestRank 0`, `bestSeed -1`, `newestSeedRank 0`, `lastMs 0`, `retrQ 500`) on an empty graph, without and then with a
          `Flow.Context(facts, lead, arc = finite)`; `roughness` is `Flow.roughness` of the picked order.
    """
}
