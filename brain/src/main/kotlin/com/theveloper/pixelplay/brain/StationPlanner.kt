package com.theveloper.pixelplay.brain

/**
 * Plans what the engine plays next (docs/brain-spec.md, 6.2, 7.1, 7.3, 8.1): an autoplay batch, a
 * station batch, or the "Because you listened to" Home row. One path for all three: retrieve
 * ([Retrieval.cooc]), score ([Features], [Ranker]), build ([ListBuilder]), explain ([WhyLine]).
 *
 * Pure: the same affinity tables, session, graph and request give the same picks on both platforms.
 */
object StationPlanner {
    const val MODE_AUTOPLAY = 0
    const val MODE_STATION = 1
    const val MODE_HOME_ROW = 2

    /**
     * Daily Mix and Your Mix (6.2, stage 2a): the pool is the library plus the cached radios of the
     * listener's own tracks, no library track has to be close to a seed, and nothing the session did
     * applies (a mix is built from a fresh session). [MixPlanner] is the way in.
     */
    const val MODE_MIX = 3

    /**
     * First Listen (6.2, stage 2b): unheard music only, a mix-like list built from a fresh session, with the
     * features that need a history zeroed (5.12 rule 6) and novelty 1.
     */
    const val MODE_FIRST_LISTEN = 4

    const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000

    /** Autoplay: a library track must be this close to an anchor (same artist, or the first 8 radio rows). */
    const val FAMILIAR_FLOOR = 0.3

    /** A station without embeddings: every track must be at least this close to the seed. */
    const val STATION_FLOOR = 0.15

    /** ARTIST arm: the artist's affinity and decayed play weight must both be above these. */
    const val ARTIST_ARM_AFF = 0.5
    const val ARTIST_ARM_WEIGHT = 5.0

    class Request(
        val mode: Int,
        val count: Int,
        /** Everything that could be picked. Not yet in the queue; blocked and muted music already removed (5.11). */
        val candidates: List<Candidate>,
        /** The seeds' lists, newest anchor first. For a station or a Home row the seed is `seeds[0]`. */
        val seeds: List<Retrieval.SeedList>,
        /** The engine's slots already in the list, oldest first. */
        val prior: List<ListBuilder.Prior> = emptyList(),
        val nowMs: Long,
        val tzOffsetMin: Int = 0,
        /** The surface factor times the listener's Familiar / Blend / Discover switch (0.4 / 1 / 2). */
        val nuMultiplier: Double = 1.0,
        val tieSeed: Long = 0L,
        /** Recordings that may not be picked (already queued, ignored three times). */
        val excludedRids: Set<Long> = emptySet(),
        /** The first slot is the bridge of 7.1 (the first track autoplay appends to a queue). */
        val bridge: Boolean = false,
        val finite: Boolean = false,
        /** At least 0: the novelty share to use as it is (a mix has its own, 8.2); negative: the session's. */
        val nu: Double = -1.0,
        /** How often each recording was served in a generated list in the last 30 days (6.5 `servedPen`). */
        val servedTimes: Map<Long, Int> = emptyMap(),
        /** The width of the per-list jitter of a mix (see [MixPlanner.JITTER]); 0 for none. */
        val jitter: Double = 0.0,
        /** The penalty at three serves or more (see [Repetition.MAX_PENALTY]). */
        val servedMax: Double = Repetition.MAX_PENALTY,
        /**
         * Station mode only. False (stage 1): a track must be close to the first seed. True (an album or playlist
         * station has up to five, 6.2): close to the nearest of them. Off by default so no earlier plan changes.
         */
        val closestOfSeeds: Boolean = false,
        /** Stage 2b: the listener's islands (features 5, 6 and 19, 5.8); null leaves those three at 0. */
        val islands: IslandContext? = null,
        /** Stage 2b: the island mix a list across islands keeps (6.5); mutated as picks are made. */
        val calibration: Calibration? = null,
        /** 0.75 inside one island, 0.6 across islands (6.5). */
        val lambda: Double = ListBuilder.LAMBDA,
        /** Stage 2b: similarity with embeddings and tags for the diversity term; null for the `adj` branch alone. */
        val simSource: Similarity.Source? = null,
        /** First Listen: at most two tracks by one artist, at least 40% from artists never played. */
        val maxPerArtist: Int = 0,
        val minNewArtistShare: Double = 0.0,
        /**
         * Stage 3: which weights rank this list (5.10, 5.12 point 8). Null is the prior `M0`, exactly as in stages 1 and 2.
         * Build it with [Ranker.WeightSource]; it falls back to the prior by itself when the gate has the prior on.
         */
        val ranking: Ranker.WeightSource? = null,
        /** Stage 3: the arm bandit of 5.10 (null keeps stage 2's choice of the novel candidate, byte for byte). */
        val armPicker: ArmPicker? = null,
    )

    class Pick(
        val rid: Long,
        val arm: Int,
        /** Position in the engine's list: `prior.size + index`. */
        val slot: Int,
        val slotKind: Int,
        val whyKey: Int,
        val whyArg: Long,
        /** Retrieval percentile x 1000 (the event's `retrQ`). */
        val retrQ: Int,
        /** Best radio rank, -1 for none (the event's `radioRank`). */
        val radioRank: Int,
        /** The quantised key the pick won its slot with. */
        val key: Long,
        /** `p` x 10,000. */
        val pQ: Int,
        val novel: Boolean,
        /** The slot was an exploration slot: the arm was drawn by the bandit (5.10), and the play is logged with [EventFlags.EXPLORE_SLOT]. */
        val explore: Boolean = false,
        /**
         * The feature vector the pick was scored with, quantised ([ServedVector]), kept with the play so the learner trains on what the ranker saw
         * (stage 4). Null when the plan was built without it; it never changes which picks are made.
         */
        val servedX: ByteArray? = null,
    )

    class Plan(
        val picks: List<Pick>,
        /** The like-scheduled slot was filled: the caller clears `likedWithin`. */
        val usedLiked: Boolean,
        /** The "second skip in a row" slot was planned: the caller clears `familiarNext`. */
        val usedFamiliarNext: Boolean,
        /** How many candidates survived the filters. */
        val poolSize: Int,
    )

    fun plan(request: Request, affinity: Affinity.State, session: SessionState, graph: Graph): Plan {
        val nowMs = request.nowMs
        val p0 = Affinity.p0(affinity, nowMs)
        val weights = request.ranking?.weights(p0) ?: Ranker.weights(p0)
        val precision = request.ranking?.precision()
        val cooc = Retrieval.cooc(request.seeds, affinity, nowMs, p0)
        val homeRow = request.mode == MODE_HOME_ROW
        val station = request.mode == MODE_STATION
        val firstListen = request.mode == MODE_FIRST_LISTEN
        val mix = request.mode == MODE_MIX || firstListen
        val stationSeed = if ((station || homeRow) && request.seeds.isNotEmpty()) request.seeds[0] else null
        val newest = if (request.seeds.isNotEmpty()) request.seeds[0] else null
        val newestRank = HashMap<Long, Int>()
        if (newest != null) {
            for (i in newest.rids.indices) if (!newestRank.containsKey(newest.rids[i])) newestRank[newest.rids[i]] = i + 1
        }

        // Filter, and decide each candidate's arm.
        val kept = ArrayList<Candidate>(request.candidates.size)
        val arms = ArrayList<Int>(request.candidates.size)
        val seen = HashSet<Long>(request.candidates.size * 2)
        // A seed is what the listener is hearing: it is never its own recommendation.
        // (A mix is the listener's own music: its seeds are tracks they chose, and they may be in it.)
        if (!mix) for (seed in request.seeds) seen.add(seed.seedRid)
        for (c in request.candidates) {
            if (c.rid == 0L || c.rid in request.excludedRids || !seen.add(c.rid)) continue
            if (session.isBlocked(c.aid)) continue
            if (session.seedLimit == 2 && c.aid != 0L && c.aid == session.seedAid) continue
            val last = Affinity.lastMs(affinity, Family.TRACK, c.rid)
            if (last != 0L && nowMs - last < FOUR_HOURS_MS) continue
            if (homeRow && c.inLibrary) continue
            // First Listen is unheard music only (6.2).
            if (firstListen && Affinity.isHeard(affinity, Family.TRACK, c.rid)) continue
            if (stationSeed != null && station) {
                val s = if (request.closestOfSeeds) closestSeed(graph, c, request.seeds)
                else Similarity.sim(graph, c.rid, c.aid, stationSeed.seedRid, stationSeed.seedAid)
                if (s < (if (c.inLibrary) FAMILIAR_FLOOR else STATION_FLOOR)) continue
            } else if (c.inLibrary && !mix) {
                var close = 0.0
                for (seed in request.seeds) {
                    val s = Similarity.sim(graph, c.rid, c.aid, seed.seedRid, seed.seedAid)
                    if (s > close) close = s
                }
                if (close < FAMILIAR_FLOOR) continue
            }
            val arm = when {
                // The arms of stage 2b (6.1): a flag set by the pool builder names the arm.
                c.fresh -> Arm.NEW
                c.back -> Arm.BACK
                c.edge -> Arm.EDGE
                c.inLibrary -> if ((affinity.completions[c.rid] ?: 0) >= 1) Arm.FAV else Arm.DEEP
                c.fromSimilarArtist -> Arm.SIM
                c.fromArtistRadio -> Arm.ARTIST
                c.aid != 0L && Affinity.aff(affinity, Family.ARTIST, c.aid, nowMs, p0) > ARTIST_ARM_AFF &&
                    Affinity.weight(affinity, Family.ARTIST, c.aid, nowMs) >= ARTIST_ARM_WEIGHT -> Arm.ARTIST
                else -> Arm.RADIO
            }
            kept.add(c)
            arms.add(arm)
        }

        val n = kept.size
        val armCodes = IntArray(n) { arms[it] }
        val scores = DoubleArray(n) { cooc[kept[it].rid]?.cooc ?: 0.0 }
        val pct = Retrieval.percentiles(armCodes, scores)
        val x = DoubleArray(Features.D)
        val scored = ArrayList<ListBuilder.Scored>(n)
        for (i in 0 until n) {
            val c = kept[i]
            val entry = cooc[c.rid]
            val bestRank = entry?.bestRank ?: 0
            Features.compute(c, armCodes[i], pct[i], bestRank, affinity, session, graph, nowMs, p0, homeRow || firstListen, x, request.islands)
            var z = Ranker.z(weights, x)
            if (station && stationSeed != null) {
                val s = if (request.closestOfSeeds) closestSeed(graph, c, request.seeds)
                else Similarity.sim(graph, c.rid, c.aid, stationSeed.seedRid, stationSeed.seedAid)
                z += 1.2 * (0.6 * s + 0.4 * x[Features.SESS_POS_SIM])
            }
            // The listener's boost / reduce is applied after scoring, outside the model (5.11).
            z += c.boostLn
            scored.add(
                ListBuilder.Scored(
                    c = c,
                    arm = armCodes[i],
                    z = z,
                    p = if (precision != null) Ranker.p(z, x, precision) else Ranker.p(z, x),
                    novel = !c.inLibrary,
                    bestRank = bestRank,
                    bestSeed = entry?.bestSeed ?: -1,
                    newestSeedRank = newestRank[c.rid] ?: 0,
                    lastMs = Affinity.lastMs(affinity, Family.TRACK, c.rid),
                    retrQ = BrainMath.floorToLong(pct[i] * 1000.0 + 0.5).toInt(),
                    servedPen = if (mix) Repetition.servedPenalty(request.servedTimes[c.rid] ?: 0, request.servedMax) else 0.0,
                    noise = if (mix && request.jitter > 0.0) {
                        request.jitter * (BrainHash.unit(BrainHash.splitmix64(c.rid xor request.tieSeed)) - 0.5)
                    } else 0.0,
                    idx = i,
                )
            )
        }

        // The special first slot: after two skips in a row a familiar track by the artist last finished;
        // on the first batch, the bridge from what was just heard.
        var firstSlotAids: LongArray? = null
        val familiarNext = session.familiarNext && !homeRow
        if (familiarNext) {
            firstSlotAids = if (session.lastCompletedAid != 0L) longArrayOf(session.lastCompletedAid) else LongArray(0)
        } else if (request.bridge && !homeRow) {
            val aids = ArrayList<Long>(3)
            var j = 0
            while (j < session.posCount && j < 3) {
                val aid = session.recentPosAid[j]
                if (aid != 0L && aid !in aids) aids.add(aid)
                j++
            }
            firstSlotAids = aids.toLongArray()
        }
        val liked = session.likedWithin > 0 && !homeRow
        val nu = if (homeRow || firstListen) 1.0
        else if (request.nu >= 0.0) BrainMath.clip(request.nu, 0.0, 1.0)
        else BrainMath.clip(session.nuSession * request.nuMultiplier, 0.0, 1.0)
        val built = ListBuilder.build(
            ListBuilder.Request(
                count = request.count,
                prior = request.prior,
                nu = nu,
                tieSeed = request.tieSeed,
                nowMs = nowMs,
                tzOffsetMin = request.tzOffsetMin,
                firstSlotAids = firstSlotAids,
                likedAid = if (liked) session.likedAid else 0L,
                likedRid = if (liked) session.likedRid else 0L,
                finite = request.finite,
                thinAid = if (session.seedLimit == 1) session.seedAid else 0L,
                lambda = request.lambda,
                calibration = request.calibration,
                simSource = request.simSource,
                maxPerArtist = request.maxPerArtist,
                minNewArtistShare = request.minNewArtistShare,
                armPicker = request.armPicker,
            ),
            scored,
            graph,
        )

        var usedLiked = false
        val picks = ArrayList<Pick>(built.size)
        for (i in built.indices) {
            val b = built[i]
            val s = b.scored
            if (b.kind == ListBuilder.SLOT_LIKED) usedLiked = true
            // The vector this pick was scored with, read again from the same state (a plan changes neither the tables nor the session).
            var servedX: ByteArray? = null
            if (s.idx >= 0 && s.idx < n) {
                Features.compute(kept[s.idx], armCodes[s.idx], pct[s.idx], s.bestRank, affinity, session, graph, nowMs, p0, homeRow || firstListen, x, request.islands)
                servedX = ServedVector.quantize(x)
            }
            val seedAid = if (s.bestSeed >= 0 && s.bestSeed < request.seeds.size) request.seeds[s.bestSeed].seedAid else 0L
            val why = WhyLine.pick(
                arm = s.arm,
                slotKind = b.kind,
                homeRow = homeRow,
                candidateAid = s.c.aid,
                seedAid = seedAid,
                bestRank = s.bestRank,
                likedRid = session.likedRid,
                homeSeedRid = stationSeed?.seedRid ?: 0L,
                trackAffRecent = Affinity.affRecent(affinity, Family.TRACK, s.c.rid, nowMs, p0),
                trackPosRecent = affinity.counter(Family.TRACK, s.c.rid)?.let { Affinity.posRecent(it, nowMs) } ?: 0.0,
                mix = mix,
            )
            picks.add(
                Pick(
                    rid = s.c.rid,
                    arm = s.arm,
                    slot = request.prior.size + i,
                    slotKind = b.kind,
                    whyKey = why.key,
                    whyArg = why.arg,
                    retrQ = s.retrQ,
                    radioRank = if (s.bestRank > 0) s.bestRank else -1,
                    key = b.key,
                    pQ = BrainMath.floorToLong(s.p * 10000.0 + 0.5).toInt(),
                    novel = s.novel,
                    explore = b.explore,
                    servedX = servedX,
                )
            )
        }
        return Plan(picks, usedLiked, familiarNext && picks.isNotEmpty(), n)
    }

    /**
     * How close [c] is to the station: the closest of its seeds. A song or artist station has one seed
     * (so this is that seed's similarity, as before); an album or playlist station has up to five (6.2),
     * and a track is wanted when it is near any of them.
     */
    private fun closestSeed(graph: Graph, c: Candidate, seeds: List<Retrieval.SeedList>): Double {
        var best = 0.0
        var first = true
        for (seed in seeds) {
            val v = Similarity.sim(graph, c.rid, c.aid, seed.seedRid, seed.seedAid)
            if (first || v > best) best = v
            first = false
        }
        return best
    }

    /**
     * The in-memory graph of 3.6 for one plan: every seed's list, plus one edge from each track to its
     * primary artist for the seeds, the candidates and the tracks the session remembers.
     */
    fun buildGraph(seeds: List<Retrieval.SeedList>, candidates: List<Candidate>, session: SessionState): Graph {
        val b = Graph.Builder()
        for (seed in seeds) {
            b.addList(seed.seedRid, seed.rids, seed.edgeFactor)
            b.addTrack(seed.seedRid, seed.seedAid)
        }
        for (c in candidates) b.addTrack(c.rid, c.aid)
        for (i in 0 until session.posCount) b.addTrack(session.recentPos[i], session.recentPosAid[i])
        for (i in 0 until session.negCount) b.addTrack(session.recentNeg[i], session.recentNegAid[i])
        for (i in 0 until session.anchorCount) b.addTrack(session.anchors[i], session.anchorsAid[i])
        return b.build()
    }

    /** What the caller does to the session once a plan's picks are in the queue. */
    fun consume(session: SessionState, plan: Plan) {
        if (plan.usedLiked) session.likedWithin = 0
        if (plan.usedFamiliarNext) session.familiarNext = false
    }
}
