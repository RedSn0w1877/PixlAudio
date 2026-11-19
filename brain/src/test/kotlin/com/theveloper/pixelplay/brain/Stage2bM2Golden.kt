package com.theveloper.pixelplay.brain

/**
 * The golden vectors of stage 2b, milestone 2 (docs/brain-spec.md, 10.4): FastRP embeddings, personalised PageRank, taste
 * islands (fit and matching), the arms' rules, the simple mixes, calibration, the embedding-aware similarity, the island features
 * and the island-aware mix planner, and the Home page rules. New files only: no earlier golden file changes.
 */
object Stage2bM2Golden {

    fun generate(project: java.io.File): LinkedHashMap<String, String> {
        val atlas = GenreAtlas.parse(Stage2bGolden.atlasText(project))
        val files = LinkedHashMap<String, String>()
        files["fastrp.jsonl"] = fastRp()
        files["ppr.jsonl"] = ppr()
        files["islands.jsonl"] = islands(atlas)
        files["arms.jsonl"] = arms(atlas)
        files["listmixes.jsonl"] = listMixes()
        files["islandfeatures.jsonl"] = islandFeatures(atlas)
        files["islandmixes.jsonl"] = islandMixes(atlas)
        files["homeplan.jsonl"] = homePlan()
        return files
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun line(fn: String, input: BrainJson.Writer.() -> Unit, output: BrainJson.Writer.() -> Unit): String {
        val w = BrainJson.Writer().beginObject()
        w.string("fn", fn)
        w.beginObject("in"); w.input(); w.endObject()
        w.beginObject("out"); w.output(); w.endObject()
        return w.endObject().toString() + "\n"
    }

    private fun BrainHash.Rng.int(n: Int): Int {
        val v = (uniform() * n).toInt()
        return if (v >= n) n - 1 else v
    }

    private fun BrainHash.Rng.chance(p: Double): Boolean = uniform() < p

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
        return sb.toString()
    }

    private fun hashDoubles(v: DoubleArray): Long {
        val b = ByteArray(v.size * 8)
        for (i in v.indices) {
            val bits = v[i].toRawBits()
            for (k in 0 until 8) b[i * 8 + k] = ((bits ushr (8 * k)) and 0xFF).toByte()
        }
        return BrainHash.fnv1a64(b)
    }

    private fun BrainJson.Writer.vector(name: String, v: Tags.Vector?) {
        beginArray(name)
        if (v != null) for (t in v.tags) stringItem("${t.atlasId}:${t.q}")
        endArray()
    }

    private class Edge(val a: Long, val b: Long, val w: Double)

    private fun randomEdges(rng: BrainHash.Rng, nodes: LongArray, count: Int): List<Edge> {
        val out = ArrayList<Edge>(count)
        for (k in 0 until count) {
            val a = nodes[rng.int(nodes.size)]
            val b = nodes[rng.int(nodes.size)]
            if (a != b) out.add(Edge(a, b, 0.1 + 0.05 * rng.int(18)))
        }
        return out
    }

    private fun buildGraph(edges: List<Edge>): Graph {
        val b = Graph.Builder()
        for (e in edges) b.addEdge(e.a, e.b, e.w)
        return b.build()
    }

    private fun BrainJson.Writer.edgeList(edges: List<Edge>) {
        beginArray("edges")
        for (e in edges) beginArray().longItem(e.a).longItem(e.b).doubleItem(e.w).endArray()
        endArray()
    }

    // ── fastrp.jsonl ─────────────────────────────────────────────────────────

    private fun fastRp(): String {
        val sb = StringBuilder()
        for (c in 0 until 10) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:fastrp:$c"))
            val n = 12 + c * 8
            val nodes = LongArray(n) { BrainHash.fnv1a64("n:node $c $it") }
            val edges = randomEdges(rng, nodes, n * 3)
            val graph = buildGraph(edges)
            val emb = FastRp.embed(graph)
            val pairs = ArrayList<LongArray>()
            for (k in 0 until 8) pairs.add(longArrayOf(nodes[rng.int(n)], nodes[rng.int(n)]))
            sb.append(
                line("embed", { edgeList(edges) }, {
                    int("version", emb.version); int("nodes", emb.size)
                    beginArray("vectors")
                    for (i in 0 until emb.size) beginArray().longItem(emb.idAt(i)).stringItem(hex(emb.vector(emb.idAt(i)) ?: ByteArray(0))).endArray()
                    endArray()
                    beginArray("cos")
                    for (p in pairs) beginArray().longItem(p[0]).longItem(p[1]).doubleItem(emb.cos(p[0], p[1])).endArray()
                    endArray()
                })
            )
        }
        // The hash of the projection itself, for a handful of nodes.
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:fastrp:projection"))
        for (k in 0 until 20) {
            val id = rng.nextLong()
            sb.append(line("projection", { long("id", id) }, {
                long("hash", FastRp.nodeHash(id))
                beginArray("row"); for (j in 0 until Embeddings.DIM) doubleItem(FastRp.projection(FastRp.nodeHash(id), j)); endArray()
            }))
        }
        return sb.toString()
    }

    // ── ppr.jsonl ────────────────────────────────────────────────────────────

    private fun ppr(): String {
        val sb = StringBuilder()
        for (c in 0 until 12) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:ppr:$c"))
            val n = 20 + c * 12
            val nodes = LongArray(n) { BrainHash.fnv1a64("n:ppr $c $it") }
            val edges = randomEdges(rng, nodes, n * 3)
            val graph = buildGraph(edges)
            val seedCount = 3 + rng.int(40)
            val seeds = LongArray(seedCount) { nodes[rng.int(n)] }
            val weights = DoubleArray(seedCount) { 0.5 + rng.int(20) * 0.25 }
            val top = if (c % 3 == 0) 15 else 300
            val r = Ppr.run(graph, seeds, weights, top)
            sb.append(
                line("ppr", {
                    edgeList(edges); longs("seeds", seeds)
                    beginArray("weights"); for (w in weights) doubleItem(w); endArray()
                    int("top", top)
                }, {
                    beginArray("result"); for (i in 0 until r.size) beginArray().longItem(r.ids[i]).doubleItem(r.scores[i]).endArray(); endArray()
                })
            )
        }
        return sb.toString()
    }

    // ── islands.jsonl ────────────────────────────────────────────────────────

    /** Tracks drawn around planted centres: each cluster has its own embedding direction and two atlas genres. */
    class Planted(val tracks: List<Islands.Track>, val cluster: IntArray)

    fun planted(atlas: GenreAtlas, seed: String, clusters: Int, perCluster: Int, noise: Int): Planted {
        val rng = BrainHash.Rng(BrainHash.fnv1a64(seed))
        val centres = Array(clusters) { ByteArray(Embeddings.DIM) { (rng.int(255) - 127).toByte() } }
        val ids = atlas.allIds
        val genres = Array(clusters) { intArrayOf(ids[rng.int(ids.size)], ids[rng.int(ids.size)]) }
        val tracks = ArrayList<Islands.Track>()
        val owner = ArrayList<Int>()
        for (c in 0 until clusters) for (k in 0 until perCluster) {
            val emb = ByteArray(Embeddings.DIM) {
                var v = centres[c][it].toInt() + rng.int(2 * noise + 1) - noise
                if (v > 127) v = 127
                if (v < -127) v = -127
                v.toByte()
            }
            val g0 = genres[c][0]
            val g1 = genres[c][1]
            val vec = Tags.Vector(listOf(Tags.Tag(minOf(g0, g1), 200 + rng.int(40)), Tags.Tag(maxOf(g0, g1), 60 + rng.int(60))).distinctBy { it.atlasId })
            val rid = BrainHash.fnv1a64("n:planted $seed $c $k")
            val w = 1.0 + rng.int(40) * 0.25
            tracks.add(Islands.Track(rid, BrainHash.fnv1a64("n:artist $seed $c ${k % 14}"), w, if (rng.chance(0.4)) w * 0.5 else 0.0, emb, vec))
            owner.add(c)
        }
        return Planted(tracks, owner.toIntArray())
    }

    private fun BrainJson.Writer.islandTracks(name: String, tracks: List<Islands.Track>) {
        beginArray(name)
        for (t in tracks) {
            beginObject(); long("rid", t.rid); long("aid", t.aid); double("w", t.weight); double("recent", t.recentWeight)
            string("emb", if (t.emb == null) "" else hex(t.emb)); vector("tags", t.tags); endObject()
        }
        endArray()
    }

    private fun BrainJson.Writer.fitOut(fit: Islands.Fit) {
        int("maxId", fit.maxId)
        beginArray("islands")
        for (i in fit.islands) {
            beginObject(); int("id", i.id); long("medoid", i.medoidRid); int("size", i.size)
            double("weight", i.weight); double("recent", i.recentWeight)
            ints("topTags", i.topTags); ints("liftTags", i.liftTags); longs("topArtists", i.topArtists)
            long("centroidHash", hashDoubles(i.centroid))
            beginArray("head"); for (j in 0 until 6) doubleItem(i.centroid[j]); endArray()
            endObject()
        }
        endArray()
        beginArray("members")
        for (k in fit.memberRids.indices) beginArray().longItem(fit.memberRids[k]).intItem(fit.memberIslandIds[k]).endArray()
        endArray()
    }

    private fun islands(atlas: GenreAtlas): String {
        val sb = StringBuilder()
        // Fits from scratch: planted clusters of several sizes, and the cases around the thresholds (under 60 tracks, tiny clusters).
        val shapes = listOf(
            intArrayOf(3, 30, 6), intArrayOf(4, 40, 8), intArrayOf(5, 50, 10), intArrayOf(6, 25, 6), intArrayOf(2, 20, 5),
            intArrayOf(8, 30, 12), intArrayOf(1, 40, 5), intArrayOf(3, 15, 6), intArrayOf(4, 8, 4), intArrayOf(12, 40, 8),
        )
        for ((c, s) in shapes.withIndex()) {
            val p = planted(atlas, "golden:islands:$c", s[0], s[1], s[2])
            val fit = Islands.fit(p.tracks)
            sb.append(line("fit", { islandTracks("tracks", p.tracks) }, { fitOut(fit) }))
        }
        // Matching: a second fit after the listener's taste moved a little keeps the ids it can.
        for (c in 0 until 6) {
            val first = planted(atlas, "golden:islands:match:$c", 4 + c % 3, 30, 8)
            val before = Islands.fit(first.tracks)
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:islands:match:drift:$c"))
            val drifted = ArrayList<Islands.Track>()
            for (t in first.tracks) {
                if (rng.chance(0.15)) continue
                drifted.add(Islands.Track(t.rid, t.aid, t.weight * (0.7 + 0.1 * rng.int(7)), t.recentWeight, t.emb, t.tags))
            }
            val extra = planted(atlas, "golden:islands:match:extra:$c", 1 + c % 2, 30, 8)
            drifted.addAll(extra.tracks)
            val after = Islands.fit(drifted, before.islands, before.maxId)
            sb.append(
                line("match", { islandTracks("first", first.tracks); islandTracks("second", drifted) }, {
                    int("firstMaxId", before.maxId)
                    fitOut(after)
                })
            )
        }
        // The point of a track.
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:islands:point"))
        val ids = atlas.allIds
        for (k in 0 until 30) {
            val emb = if (k % 5 == 4) null else ByteArray(Embeddings.DIM) { (rng.int(255) - 127).toByte() }
            val tags = if (k % 4 == 3) null else Tags.Vector(listOf(Tags.Tag(ids[rng.int(ids.size)], 100 + rng.int(150))))
            val p = Islands.point(emb, tags)
            sb.append(line("point", { string("emb", if (emb == null) "" else hex(emb)); vector("tags", tags) }, {
                if (p == null) nullValue("point") else long("hash", hashDoubles(p))
                if (p != null) { beginArray("head"); for (j in 0 until 4) doubleItem(p[j]); endArray() }
            }))
        }
        return sb.toString()
    }

    // ── arms.jsonl ───────────────────────────────────────────────────────────

    private fun arms(atlas: GenreAtlas): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:arms"))
        val now = 1_760_000_000_000L
        for (k in 0 until 40) {
            val completions = rng.int(9)
            val last = if (rng.chance(0.1)) 0L else now - rng.int(900) * 86_400_000L
            val inLib = rng.chance(0.3)
            sb.append(line("back", { int("completions", completions); long("lastMs", last); long("nowMs", now); bool("inLibrary", inLib) }, {
                bool("back", ArmRules.isBack(completions, last, now, inLib))
            }))
        }
        for (k in 0 until 12) {
            val n = 5 + rng.int(25)
            val aids = LongArray(n) { BrainHash.fnv1a64("n:simaid $k $it") }
            val aff = DoubleArray(n) { (rng.int(60) - 30) / 10.0 }
            val weight = DoubleArray(n) { rng.int(120) / 10.0 }
            val limit = 3 + rng.int(10)
            val out = ArmRules.simSources(aids, aff, weight, limit)
            sb.append(line("simSources", {
                longs("aids", aids); beginArray("aff"); for (a in aff) doubleItem(a); endArray()
                beginArray("weight"); for (w in weight) doubleItem(w); endArray(); int("limit", limit)
            }, { longs("sources", out) }))
        }
        val all = atlas.allIds
        for (k in 0 until 25) {
            val loved = IntArray(1 + rng.int(6)) { all[rng.int(all.size)] }.distinct().toIntArray()
            val played = HashMap<Int, Double>()
            for (i in 0 until rng.int(30)) played[all[rng.int(all.size)]] = rng.int(100) / 1000.0
            val limit = 3 + rng.int(8)
            val out = ArmRules.adjacentGenres(atlas, loved, { played[it] ?: 0.0 }, limit)
            sb.append(line("adjacent", {
                ints("loved", loved)
                beginArray("shares"); for (id in played.keys.sorted()) beginArray().intItem(id).doubleItem(played[id]!!).endArray(); endArray()
                int("limit", limit)
            }, { ints("adjacent", out) }))
        }
        return sb.toString()
    }

    // ── listmixes.jsonl ──────────────────────────────────────────────────────

    private fun BrainJson.Writer.items(name: String, list: List<ListMixes.Item>) {
        beginArray(name)
        for (i in list) beginArray().longItem(i.rid).longItem(i.aid).doubleItem(i.score).intItem(i.arm).endArray()
        endArray()
    }

    private fun listMixes(): String {
        val sb = StringBuilder()
        val now = 1_760_000_000_000L
        for (c in 0 until 10) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:listmixes:$c"))
            val n = 40 + rng.int(60)
            val rids = LongArray(n) { BrainHash.fnv1a64("n:lm $c $it") }
            val aids = LongArray(n) { BrainHash.fnv1a64("n:lmaid $c ${rng.int(14)}") }
            val albums = LongArray(n) { BrainHash.fnv1a64("n:lmalb $c ${it / 3}") }
            val state = Affinity.State()
            val steps = ArrayList<LongArray>()
            for (k in 0 until n * 3) {
                val i = rng.int(n)
                val t = now - rng.int(200 * 24) * 3_600_000L
                steps.add(longArrayOf(rids[i], t, rng.int(11).toLong(), rng.int(6).toLong()))
            }
            steps.sortBy { it[1] }
            for (s in steps) Affinity.apply(state, Family.TRACK, s[0], s[2] / 10.0, 0.5 + s[3] / 2.0, 1.0, s[1])
            val served = HashMap<Long, Int>()
            for (r in rids) if (rng.chance(0.2)) served[r] = 1 + rng.int(4)
            val excluded = HashSet<Long>()
            for (r in rids) if (rng.chance(0.05)) excluded.add(r)
            val tie = BrainHash.fnv1a64("tie $c")
            val onRepeat = ListMixes.fill(ListMixes.onRepeat(state, rids, aids, albums, now), ListMixes.SIZE, excluded, tie)
            val rediscover = ListMixes.fill(ListMixes.rediscover(state, rids, aids, albums, served, now), ListMixes.SIZE, excluded, tie)
            val artistAff = DoubleArray(n) { (rng.int(60) - 30) / 10.0 }
            val heard = BooleanArray(n) { rng.chance(0.6) }
            val recency = DoubleArray(n) { rng.int(100).toDouble() }
            val dropped = ListMixes.fill(ListMixes.justDropped(rids, aids, albums, artistAff, heard, recency), ListMixes.SIZE, excluded, tie, 2)
            sb.append(
                line("lists", {
                    long("nowMs", now); longs("rids", rids); longs("aids", aids); longs("albums", albums)
                    beginArray("steps"); for (s in steps) beginArray().longItem(s[0]).longItem(s[1]).intItem(s[2].toInt()).intItem(s[3].toInt()).endArray(); endArray()
                    beginArray("served"); for (k in served.keys.sorted()) beginArray().longItem(k).intItem(served[k]!!).endArray(); endArray()
                    longs("excluded", excluded.toLongArray().also { it.sort() }); long("tieSeed", tie)
                    beginArray("artistAff"); for (a in artistAff) doubleItem(a); endArray()
                    beginArray("artistHeard"); for (h in heard) intItem(if (h) 1 else 0); endArray()
                    beginArray("recency"); for (r in recency) doubleItem(r); endArray()
                }, {
                    items("onRepeat", onRepeat); items("rediscover", rediscover); items("justDropped", dropped)
                })
            )
        }
        return sb.toString()
    }

    // ── islandfeatures.jsonl: calibration and similarity with embeddings and tags ──

    private fun islandFeatures(atlas: GenreAtlas): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:islandfeatures"))
        // Calibration: a run of picks and the gain of each next island.
        for (c in 0 until 12) {
            val k = 2 + rng.int(6)
            val weights = DoubleArray(k) { 1.0 + rng.int(200) / 4.0 }
            val picks = IntArray(rng.int(30)) { rng.int(k) }
            val cal = Calibration(weights)
            for (p in picks) cal.add(p)
            sb.append(line("calibration", {
                beginArray("weights"); for (w in weights) doubleItem(w); endArray(); ints("picks", picks)
            }, {
                beginArray("gains"); for (i in 0 until k) doubleItem(cal.gain(i)); endArray()
                double("divergence", cal.divergence())
            }))
        }
        // The similarity of two tracks with embeddings and tags.
        val ids = atlas.allIds
        for (c in 0 until 10) {
            val n = 16
            val rids = LongArray(n) { BrainHash.fnv1a64("n:sim $c $it") }.also { it.sort() }
            val emb = ByteArray(n * Embeddings.DIM) { if (rng.chance(0.1)) 0 else (rng.int(255) - 127).toByte() }
            val embs = Embeddings.of(1, rids, emb)
            val tags = HashMap<Long, Tags.Vector>()
            for (r in rids) if (rng.chance(0.6)) {
                tags[r] = Tags.Vector(
                    listOf(Tags.Tag(ids[rng.int(ids.size)], 120 + rng.int(130)), Tags.Tag(ids[rng.int(ids.size)], 40 + rng.int(60)))
                        .distinctBy { it.atlasId }.sortedBy { it.atlasId }
                )
            }
            val g = Graph.Builder()
            for (k in 0 until 20) g.addEdge(rids[rng.int(n)], rids[rng.int(n)], 0.2 + 0.1 * rng.int(8))
            val graph = g.build()
            val withEmb = c % 3 != 0
            val index = SimilarityIndex(graph, if (withEmb) embs else null, tags)
            val pairs = ArrayList<LongArray>()
            for (k in 0 until 10) pairs.add(longArrayOf(rids[rng.int(n)], rids[rng.int(n)]))
            sb.append(line("sim", {
                longs("rids", rids); string("emb", hex(emb)); bool("withEmbeddings", withEmb)
                beginArray("tagList")
                for (r in rids) { val v = tags[r]; beginArray(); if (v != null) for (t in v.tags) stringItem("${t.atlasId}:${t.q}"); endArray() }
                endArray()
                beginArray("edges")
                for (i in rids.indices) {
                    val nb = graph.neighboursOf(rids[i])
                    val w = graph.weightsOf(rids[i])
                    for (k in nb.indices) if (rids[i] < nb[k]) beginArray().longItem(rids[i]).longItem(nb[k]).doubleItem(w[k]).endArray()
                }
                endArray()
            }, {
                beginArray("sims")
                for (p in pairs) beginArray().longItem(p[0]).longItem(p[1]).doubleItem(index.sim(p[0], 0L, p[1], 0L)).endArray()
                endArray()
            }))
        }
        return sb.toString()
    }

    // ── islandmixes.jsonl: the planner with islands, Your Mix calibration and First Listen ───────

    private fun BrainJson.Writer.cands(list: List<Candidate>) {
        beginArray("candidates")
        for (c in list) {
            beginObject(); long("rid", c.rid); long("aid", c.aid); long("albumKey", c.albumKey); bool("inLibrary", c.inLibrary)
            bool("liked", c.liked); bool("fromArtistRadio", c.fromArtistRadio); bool("fromSimilarArtist", c.fromSimilarArtist)
            bool("fresh", c.fresh); bool("back", c.back); bool("edge", c.edge); bool("artistNew", c.artistNew); int("pop", c.popPermille)
            string("emb", if (c.emb == null) "" else hex(c.emb)); vector("tags", c.tags); endObject()
        }
        endArray()
    }

    private fun islandMixes(atlas: GenreAtlas): String {
        val sb = StringBuilder()
        val now = 1_760_000_000_000L
        for (c in 0 until 12) {
            val p = planted(atlas, "golden:islandmixes:$c", 3 + c % 3, 30, 8)
            val fit = Islands.fit(p.tracks)
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:islandmixes:req:$c"))
            val cands = ArrayList<Candidate>()
            for ((k, t) in p.tracks.withIndex()) {
                val inLib = k % 3 != 0
                cands.add(
                    Candidate(
                        rid = t.rid, aid = t.aid, albumKey = BrainHash.fnv1a64("alb ${t.rid / 7}"), inLibrary = inLib,
                        liked = rng.chance(0.1), emb = t.emb, tags = t.tags, popPermille = if (rng.chance(0.7)) rng.int(1001) else -1,
                        fresh = !inLib && rng.chance(0.1), artistNew = !inLib && rng.chance(0.5),
                    )
                )
            }
            val context = IslandContext(fit.islands)
            val tagsByRid = HashMap<Long, Tags.Vector>()
            for (t in p.tracks) if (t.tags != null) tagsByRid[t.rid] = t.tags
            val kind = when (c % 3) { 0 -> MixPlanner.KIND_DAILY; 1 -> MixPlanner.KIND_YOUR; else -> MixPlanner.KIND_FIRST_LISTEN }
            val islandIdx = if (kind == MixPlanner.KIND_DAILY && fit.islands.isNotEmpty()) c % fit.islands.size else -1
            val request = MixPlanner.Request(
                kind = kind, candidates = cands, seeds = emptyList(), nowMs = now, tzOffsetMin = 0, nuMultiplier = 1.0,
                salt = BrainHash.fnv1a64("salt $c"), islands = context, islandIdx = islandIdx, tagsByRid = tagsByRid,
            )
            val plan = MixPlanner.plan(request, Affinity.State())
            sb.append(
                line("islandMix", {
                    long("nowMs", now); int("kind", kind); int("islandIdx", islandIdx); cands(cands)
                    islandTracks("fitTracks", p.tracks)
                }, {
                    beginArray("picks")
                    for (pick in plan.picks) {
                        beginObject().long("rid", pick.rid).int("arm", pick.arm).int("slot", pick.slot).int("slotKind", pick.slotKind)
                            .long("key", pick.key).int("pQ", pick.pQ).bool("novel", pick.novel).endObject()
                    }
                    endArray()
                    int("poolSize", plan.poolSize)
                })
            )
        }
        return sb.toString()
    }

    // ── homeplan.jsonl ───────────────────────────────────────────────────────

    private fun BrainJson.Writer.shelfItems(items: List<HomePlanner.Item>) {
        beginArray("items")
        for (i in items) beginArray().longItem(i.rid).longItem(i.aid).intItem(i.key).endArray()
        endArray()
    }

    private fun homePlan(): String {
        val sb = StringBuilder()
        for (c in 0 until 24) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:homeplan:$c"))
            val types = intArrayOf(
                HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.BECAUSE_LISTENED, HomePlanner.NEW_FROM_LOVED,
                HomePlanner.ON_REPEAT, HomePlanner.REDISCOVER, HomePlanner.STATIONS, HomePlanner.STEP_SIDEWAYS,
                HomePlanner.MORE_LIKE_ALBUM, HomePlanner.POPULAR,
            )
            val shelves = ArrayList<HomePlanner.ShelfIn>()
            val pool = LongArray(60) { BrainHash.fnv1a64("n:hp $c $it") }
            val artists = LongArray(12) { BrainHash.fnv1a64("n:hpa $c $it") }
            for ((s, type) in types.withIndex()) {
                if (rng.chance(0.15)) continue
                val count = if (HomePlanner.isCardShelf(type)) rng.int(8) else rng.int(20)
                val items = ArrayList<HomePlanner.Item>(count)
                for (k in 0 until count) {
                    items.add(HomePlanner.Item(if (HomePlanner.isCardShelf(type)) 0L else pool[rng.int(pool.size)], artists[rng.int(artists.size)], k))
                }
                shelves.add(HomePlanner.ShelfIn(type, "s$s", items))
            }
            val out = HomePlanner.plan(shelves)
            sb.append(
                line("plan", {
                    beginArray("shelves")
                    for (s in shelves) { beginObject().int("type", s.type).string("id", s.id); shelfItems(s.items); endObject() }
                    endArray()
                }, {
                    beginArray("shown")
                    for (s in out) { beginObject().int("type", s.type).string("id", s.id); shelfItems(s.items); endObject() }
                    endArray()
                })
            )
        }
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:homeplan:hero"))
        for (k in 0 until 60) {
            val now = 1_760_000_000_000L + rng.int(24 * 14) * 3_600_000L
            val tz = (rng.int(49) - 24) * 30
            val first = rng.chance(0.5)
            val release = rng.chance(0.5)
            val daily = rng.int(7)
            val salt = rng.nextLong()
            val hero = HomePlanner.hero(first, release, daily, now, tz, salt)
            sb.append(line("hero", {
                long("nowMs", now); int("tzOffsetMin", tz); bool("firstListen", first); bool("release", release); int("daily", daily); long("salt", salt)
            }, {
                int("kind", hero.kind); int("index", hero.index); int("daypart", HomePlanner.daypart(now, tz))
                int("weekday", HomePlanner.weekday(now, tz)); long("planKey", HomePlanner.planKey(now, tz))
            }))
        }
        return sb.toString()
    }

    const val README_NOTES = """
        - `fastrp.jsonl`, `ppr.jsonl`, `islands.jsonl`, `arms.jsonl`, `listmixes.jsonl`, `islandfeatures.jsonl`, `islandmixes.jsonl`,
          `homeplan.jsonl` (stage 2b, milestone 2; new files, nothing before them changed). Doubles are bit patterns; Int64 are strings.
        - `fastrp.jsonl`: `embed` builds a graph with `Graph.Builder.addEdge(a, b, w)` for each edge in order, then `FastRp.embed(graph)`;
          `vectors` are `[nodeId, hexBytes]` in ascending node id (an empty hex means the zero vector); `cos` is `Embeddings.cos`.
          `projection` is `FastRp.nodeHash(id)` and the row `FastRp.projection(hash, j)` for j in 0..63.
        - `ppr.jsonl`: `Ppr.run(graph, seeds, weights, top)`; `result` is `[id, score]`, best first.
        - `islands.jsonl`: `fit` is `Islands.fit(tracks)` (`emb` is hex, empty for none; `tags` are `"atlasId:q"`; `w` is the weight);
          `match` fits `first`, then fits `second` with the first fit's islands and its `maxId` (`firstMaxId`); the output is the second
          fit. `centroidHash` is FNV-1a 64 over the 8 little-endian bytes of each centroid double, `head` its first numbers. `point` is
          `Islands.point(emb, tags)`.
        - `arms.jsonl`: `ArmRules.isBack`, `simSources`, `adjacentGenres` (needs the atlas; `shares` are the tags the listener plays).
        - `listmixes.jsonl`: `lists` rebuilds one `Affinity.State` by `Affinity.apply(TRACK, rid, r = s[2] / 10, c = 0.5 + s[3] / 2, part 1,
          t)` for each step, then `ListMixes.onRepeat`/`rediscover`/`justDropped` followed by `ListMixes.fill(items, 30, excluded, tieSeed
          [, maxPerArtist 2 for justDropped])`; items are `[rid, aid, score, arm]`.
        - `islandfeatures.jsonl`: `calibration` is `Calibration(weights)` with `add(pick)` for each pick, `gain(i)` per island and
          `divergence()`; `sim` is `SimilarityIndex(graph, embeddings or none, tags).sim(a, 0, b, 0)` over the `edges` (`Graph.Builder.addEdge`),
          `emb` (hex, 64 bytes a track in ascending rid, `Embeddings.of(1, ...)`) and `tagList` (parallel to `rids`).
        - `islandmixes.jsonl`: `Islands.fit(fitTracks)`, then `MixPlanner.plan` on `candidates` with `islands = IslandContext(fit.islands)`,
          `islandIdx`, `tagsByRid` (from the candidates' tags), an empty affinity state, no seeds, `salt` of the case, `nuMultiplier` 1.
        - `homeplan.jsonl`: `plan` is `HomePlanner.plan(shelves)`; `hero` is `HomePlanner.hero(...)` plus `daypart`, `weekday`, `planKey`.
    """
}
