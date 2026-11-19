package com.theveloper.pixelplay.brain

/**
 * Plans Daily Mix and Your Mix (docs/brain-spec.md, 6.2, 6.4, 8.2; stage 2a): the listener's own music
 * and music they do not have yet, in one list. It needs no atlas and no islands: the pool is the
 * library tracks the app shortlisted plus the cached radios of the listener's own tracks, and the
 * order is the brain scorer ([Ranker], weights `M0`) under the hard constraints of 6.4.
 *
 * What it adds to [StationPlanner] (which it runs, in [StationPlanner.MODE_MIX]):
 *  - the novelty share of 8.2 times the Familiar / Blend / Discover switch;
 *  - the repetition budget ([Repetition]): nothing another mix of the same day holds, a penalty for
 *    what was served often in the last 30 days;
 *  - a fresh session: a skip a minute ago does not reshuffle a mix that was built this morning.
 *
 * Pure: the same affinity tables, pool and request give the same list on both platforms.
 */
object MixPlanner {
    const val KIND_DAILY = 0
    const val KIND_YOUR = 1

    /** First Listen (6.2, stage 2b): weekly, music never played here. */
    const val KIND_FIRST_LISTEN = 2

    /** A Daily Mix of one island: every track must be at least this close to it (6.2). */
    const val ISLAND_FLOOR = 0.2

    /** The novelty share of a Daily Mix of an island: `clip(nuLong, 0.15, 0.40)` (6.2). */
    const val ISLAND_NU_MIN = 0.15
    const val ISLAND_NU_MAX = 0.40

    /** `lambda` of 6.5 across islands. */
    const val LAMBDA_ACROSS = 0.6

    /** A Daily Mix holds 30 tracks, ending on a familiar one; Your Mix starts with 60 and is endless. */
    const val DAILY_COUNT = 30
    const val YOUR_COUNT = 60

    /** The listener's novelty appetite before stage 3 learns one (5.10: 0.25 with no history). */
    const val NU0 = 0.25

    /** Your Mix gives new music less room than a Daily Mix does (6.2: `0.8 * nuLong`). */
    const val YOUR_FACTOR = 0.8

    /**
     * The width of the jitter every candidate gets in the value of one list: `JITTER * (u - 0.5)` with
     * `u` from `splitmix64(rid xor tieSeed)`. The spec's tie-break only separates exact ties, so a mix
     * built twice from the same state would be the same list and "Regenerate" (8.2) would do nothing.
     * A day's list is stable (the seed is the day's); the next day, and a regenerate, draw another.
     */
    const val JITTER = 1.0

    /** The most any switch position may push the share of new music to. */
    const val NU_MAX = 0.6

    /** An edge read back from the persisted graph (`graph_edge`): both ends are recording ids. */
    class Edge(val a: Long, val b: Long, val weight: Double)

    class Request(
        val kind: Int,
        /** The pool: library shortlist and radio rows, blocked and muted music already removed (5.11). */
        val candidates: List<Candidate>,
        /** The lists of the listener's own tracks the novel candidates came from, best seed first. */
        val seeds: List<Retrieval.SeedList>,
        val nowMs: Long,
        val tzOffsetMin: Int,
        /** The Familiar / Blend / Discover switch: 0.4 / 1.0 / 2.0 (5.10). */
        val nuMultiplier: Double = 1.0,
        /** The install's random salt: lists differ between listeners. */
        val salt: Long = 0L,
        /** 0 for the list of the day; a "Regenerate" draws a new number (8.2). */
        val regenerate: Int = 0,
        /** Held by another mix of the same day, or ignored three times: never picked. */
        val excludedRids: Set<Long> = emptySet(),
        /** From the served log: how often each recording was served in the last 30 days. */
        val servedTimes: Map<Long, Int> = emptyMap(),
        /** Persisted edges between the pool's recordings and the seeds' (3.6, `graph_edge`). */
        val edges: List<Edge> = emptyList(),
        /** 0 for the size of the kind. */
        val count: Int = 0,
        /** The width of the per-list jitter ([JITTER]); a different one only to measure what it costs. */
        val jitter: Double = JITTER,
        /** The served-log penalty at three serves or more ([Repetition.MAX_PENALTY]). */
        val servedMax: Double = Repetition.MAX_PENALTY,
        /** Stage 2b: the listener's islands. With them the island features (5, 6, 19) are live. */
        val islands: IslandContext? = null,
        /** A Daily Mix of one island: the index in [islands], or -1 (the one Daily Mix of stage 2a). */
        val islandIdx: Int = -1,
        /** Stage 2b: the graph embeddings (version of [FastRp]), for the diversity term. */
        val embeddings: Embeddings? = null,
        /** Stage 2b: tag vectors by rid, for the diversity term. */
        val tagsByRid: Map<Long, Tags.Vector> = emptyMap(),
    )

    fun countOf(kind: Int): Int = if (kind == KIND_YOUR) YOUR_COUNT else DAILY_COUNT

    /** The novelty share of one island's Daily Mix. */
    fun nuOfIsland(multiplier: Double): Double = BrainMath.clip(NU0 * multiplier, ISLAND_NU_MIN, ISLAND_NU_MAX)

    /** The share of the slots given to music the listener does not have (8.2, 5.10). */
    fun nuOf(kind: Int, multiplier: Double): Double {
        val base = if (kind == KIND_YOUR) NU0 * YOUR_FACTOR else NU0
        return BrainMath.clip(base * multiplier, 0.0, NU_MAX)
    }

    /** The surface a kind of mix is logged under. */
    fun surfaceOf(kind: Int): Int = if (kind == KIND_YOUR) Surface.YOUR_MIX else if (kind == KIND_FIRST_LISTEN) Surface.NAMED_MIX else Surface.DAILY_MIX

    /** What breaks ties: the same on the same local day, different after "Regenerate" and between installs. */
    fun tieSeedOf(request: Request): Long {
        val day = Repetition.mixDay(request.nowMs, request.tzOffsetMin)
        val island = if (request.islandIdx >= 0) (request.islandIdx + 1).toLong() * 65536L else 0L
        return BrainHash.splitmix64(request.salt xor BrainHash.splitmix64(day * 4L + request.kind.toLong() + request.regenerate.toLong() * 1024L + island))
    }

    fun plan(request: Request, affinity: Affinity.State): StationPlanner.Plan {
        val session = SessionState()
        val builder = Graph.Builder()
        for (seed in request.seeds) {
            builder.addList(seed.seedRid, seed.rids, seed.edgeFactor)
            builder.addTrack(seed.seedRid, seed.seedAid)
        }
        for (c in request.candidates) builder.addTrack(c.rid, c.aid)
        for (e in request.edges) builder.addEdge(e.a, e.b, e.weight)
        val graph = builder.build()
        val kind = request.kind
        val islands = request.islands
        var candidates = request.candidates
        var calibration: Calibration? = null
        var lambda = ListBuilder.LAMBDA
        var nu = nuOf(kind, request.nuMultiplier)
        if (islands != null && islands.islands.isNotEmpty()) {
            if (request.islandIdx >= 0 && request.islandIdx < islands.islands.size) {
                // A Daily Mix of one island: every track must be close to it (6.2).
                val island = islands.islands[request.islandIdx]
                val kept = ArrayList<Candidate>(candidates.size)
                for (c in candidates) {
                    val p = c.point ?: continue
                    if (Islands.similarity(island, p) >= ISLAND_FLOOR) kept.add(c)
                }
                candidates = kept
                nu = nuOfIsland(request.nuMultiplier)
            } else if (kind == KIND_YOUR && islands.islands.size > 1) {
                // Your Mix: every island, kept in the listener's proportions (6.5).
                val weights = DoubleArray(islands.islands.size) { islands.islands[it].weight }
                calibration = Calibration(weights)
                lambda = LAMBDA_ACROSS
                val placed = ArrayList<Candidate>(candidates.size)
                for (c in candidates) placed.add(c.withIsland(islands.best(c.point).index))
                candidates = placed
            }
        }
        val simSource: Similarity.Source? =
            if (islands != null && (request.embeddings != null || request.tagsByRid.isNotEmpty())) {
                SimilarityIndex(graph, request.embeddings, request.tagsByRid)
            } else null
        val first = kind == KIND_FIRST_LISTEN
        return StationPlanner.plan(
            StationPlanner.Request(
                mode = if (first) StationPlanner.MODE_FIRST_LISTEN else StationPlanner.MODE_MIX,
                count = if (request.count > 0) request.count else countOf(kind),
                candidates = candidates,
                seeds = request.seeds,
                nowMs = request.nowMs,
                tzOffsetMin = request.tzOffsetMin,
                nuMultiplier = request.nuMultiplier,
                tieSeed = tieSeedOf(request),
                excludedRids = request.excludedRids,
                finite = kind == KIND_DAILY,
                nu = if (first) 1.0 else nu,
                servedTimes = request.servedTimes,
                jitter = request.jitter,
                servedMax = request.servedMax,
                islands = islands,
                calibration = calibration,
                lambda = lambda,
                simSource = simSource,
                maxPerArtist = if (first) 2 else 0,
                minNewArtistShare = if (first) 0.4 else 0.0,
            ),
            affinity,
            session,
            graph,
        )
    }
}
