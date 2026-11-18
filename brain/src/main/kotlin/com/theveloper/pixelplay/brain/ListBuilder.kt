package com.theveloper.pixelplay.brain

/**
 * Builds a list slot by slot (docs/brain-spec.md, 6.4, 6.5). Greedy: each slot has a kind (familiar,
 * novel, or a special first slot), and within the kind the candidate with the best value that breaks
 * no hard rule takes it.
 *
 * Stage 1 has the hard constraints of 6.4, the slot plan and `lambda`; no calibration, flow cost or
 * served penalty yet. Ordering is by a quantised key with a total tie-break, so sort stability and
 * floating-point noise below 2^-20 cannot change a list.
 */
object ListBuilder {

    const val LAMBDA = 0.75

    /** The same primary artist not within this many slots. */
    const val ARTIST_GAP = 4

    /** A station's seed artist after one skip: one track in six. */
    const val THIN_ARTIST_GAP = 6

    /** The same album not within this many slots. */
    const val ALBUM_GAP = 8

    /** Diversity looks back this many slots. */
    const val MMR_WINDOW = 10

    const val SLOT_OPENER = 0
    const val SLOT_FAMILIAR = 1
    const val SLOT_NOVEL = 2
    /** A familiar track by a given artist, or failing that the best row of the newest anchor's radio. */
    const val SLOT_BRIDGE = 3
    /** The track scheduled because the listener liked something. */
    const val SLOT_LIKED = 4
    const val SLOT_CLOSER = 5

    /** A candidate with its score. */
    class Scored(
        val c: Candidate,
        val arm: Int,
        val z: Double,
        val p: Double,
        /** True when the listener does not have it: it competes for the novel slots. */
        val novel: Boolean,
        val bestRank: Int,
        val bestSeed: Int,
        /** Its rank in the newest anchor's list; 0 when it is not there. */
        val newestSeedRank: Int,
        /** When it was last played; 0 for never. */
        val lastMs: Long,
        /** Retrieval percentile x 1000. */
        val retrQ: Int,
    )

    /** A slot already in the list (the engine's own tail of the queue), oldest first. */
    class Prior(val rid: Long, val aid: Long, val albumKey: Long, val novel: Boolean)

    class Request(
        val count: Int,
        val prior: List<Prior>,
        /** Novelty share: the part of the slots that goes to music the listener does not have. */
        val nu: Double,
        /** Seeds the tie-break, so two lists do not break ties the same way. */
        val tieSeed: Long,
        val nowMs: Long,
        val tzOffsetMin: Int,
        /** Non-null: the first slot is a [SLOT_BRIDGE] for these artists. */
        val firstSlotAids: LongArray? = null,
        /** Non-zero: one slot goes to a track by this artist, or the nearest neighbour of [likedRid]. */
        val likedAid: Long = 0L,
        val likedRid: Long = 0L,
        /** A finite list ends on a familiar track. */
        val finite: Boolean = false,
        /** This artist is spaced [THIN_ARTIST_GAP] apart instead of [ARTIST_GAP]. */
        val thinAid: Long = 0L,
        val lambda: Double = LAMBDA,
    )

    class Pick(
        val scored: Scored,
        val kind: Int,
        /** The quantised key it won its slot with. */
        val key: Long,
    )

    fun build(request: Request, scored: List<Scored>, graph: Graph): List<Pick> {
        val picks = ArrayList<Pick>(request.count)
        if (request.count <= 0 || scored.isEmpty()) return picks
        val list = ArrayList<Prior>(request.prior.size + request.count)
        list.addAll(request.prior)
        val used = HashSet<Long>(list.size * 2 + request.count * 2)
        var novelSoFar = 0
        for (p in list) {
            used.add(p.rid)
            if (p.novel) novelSoFar++
        }
        val today = BrainMath.localDay(request.nowMs, request.tzOffsetMin)
        var likedPending = request.likedAid != 0L || request.likedRid != 0L

        for (i in 0 until request.count) {
            val slotNo = list.size + 1
            var kind = when {
                i == 0 && request.firstSlotAids != null -> SLOT_BRIDGE
                likedPending -> SLOT_LIKED
                slotNo == 1 -> SLOT_OPENER
                slotNo == 2 -> SLOT_FAMILIAR
                request.finite && i == request.count - 1 -> SLOT_CLOSER
                // The first two slots are the familiar openers; the novelty share counts from the third.
                novelSoFar.toDouble() < request.nu * (slotNo - 2).toDouble() && !list[list.size - 1].novel -> SLOT_NOVEL
                else -> SLOT_FAMILIAR
            }
            var chosen: Scored? = null
            var chosenKey = 0L

            if (kind == SLOT_BRIDGE) {
                val aids = request.firstSlotAids!!
                var best: Scored? = null
                var bestKey = 0L
                for (s in scored) {
                    if (s.novel || s.c.rid in used || !contains(aids, s.c.aid) || !fits(list, s.c, request.thinAid)) continue
                    val key = BrainMath.orderKey(s.p)
                    if (best == null || better(key, s.c.rid, bestKey, best.c.rid, request.tieSeed)) {
                        best = s
                        bestKey = key
                    }
                }
                if (best == null) {
                    // The best radio row of the newest anchor.
                    for (s in scored) {
                        if (s.newestSeedRank <= 0 || s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                        val key = -s.newestSeedRank.toLong()
                        if (best == null || better(key, s.c.rid, bestKey, best.c.rid, request.tieSeed)) {
                            best = s
                            bestKey = key
                        }
                    }
                }
                chosen = best
                chosenKey = bestKey
                if (chosen == null) kind = if (slotNo <= 2) SLOT_FAMILIAR else SLOT_NOVEL
            }

            if (kind == SLOT_LIKED) {
                likedPending = false
                var best: Scored? = null
                var bestKey = 0L
                if (request.likedAid != 0L) {
                    for (s in scored) {
                        if (s.c.aid != request.likedAid || s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                        val key = BrainMath.orderKey(value(s, list, graph, request.lambda))
                        if (best == null || better(key, s.c.rid, bestKey, best.c.rid, request.tieSeed)) {
                            best = s
                            bestKey = key
                        }
                    }
                }
                if (best == null && request.likedRid != 0L) {
                    for (s in scored) {
                        if (s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                        val sim = Similarity.sim(graph, s.c.rid, s.c.aid, request.likedRid, request.likedAid)
                        if (sim <= 0.0) continue
                        val key = BrainMath.orderKey(sim)
                        if (best == null || better(key, s.c.rid, bestKey, best.c.rid, request.tieSeed)) {
                            best = s
                            bestKey = key
                        }
                    }
                }
                chosen = best
                chosenKey = bestKey
                if (chosen == null) {
                    kind = if (slotNo <= 2) SLOT_FAMILIAR
                    else if (novelSoFar.toDouble() < request.nu * (slotNo - 2).toDouble() && !list[list.size - 1].novel) SLOT_NOVEL
                    else SLOT_FAMILIAR
                }
            }

            if (chosen == null && kind == SLOT_OPENER) {
                // The trust opener: familiar, highest p, not played today.
                for (s in scored) {
                    if (s.novel || s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                    if (s.lastMs != 0L && BrainMath.localDay(s.lastMs, request.tzOffsetMin) == today) continue
                    val key = BrainMath.orderKey(s.p)
                    if (chosen == null || better(key, s.c.rid, chosenKey, chosen.c.rid, request.tieSeed)) {
                        chosen = s
                        chosenKey = key
                    }
                }
                if (chosen == null) kind = SLOT_FAMILIAR
            }

            if (chosen == null && kind == SLOT_CLOSER) {
                for (s in scored) {
                    if (s.novel || s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                    val key = BrainMath.orderKey(s.p)
                    if (chosen == null || better(key, s.c.rid, chosenKey, chosen.c.rid, request.tieSeed)) {
                        chosen = s
                        chosenKey = key
                    }
                }
                if (chosen == null) kind = SLOT_NOVEL
            }

            if (chosen == null) {
                // A familiar or a novel slot; when its own kind has nobody, the other kind takes it
                // (staying on topic beats the quota).
                var wantNovel = kind == SLOT_NOVEL
                var pass = 0
                while (pass < 2 && chosen == null) {
                    for (s in scored) {
                        if (s.novel != wantNovel || s.c.rid in used || !fits(list, s.c, request.thinAid)) continue
                        val key = BrainMath.orderKey(value(s, list, graph, request.lambda))
                        if (chosen == null || better(key, s.c.rid, chosenKey, chosen.c.rid, request.tieSeed)) {
                            chosen = s
                            chosenKey = key
                        }
                    }
                    if (chosen == null) wantNovel = !wantNovel
                    pass++
                }
                if (chosen != null) kind = if (chosen.novel) SLOT_NOVEL else SLOT_FAMILIAR
            }

            val pick = chosen ?: break
            picks.add(Pick(pick, kind, chosenKey))
            used.add(pick.c.rid)
            if (pick.novel) novelSoFar++
            list.add(Prior(pick.c.rid, pick.c.aid, pick.c.albumKey, pick.novel))
        }
        return picks
    }

    /** `lambda * z - (1 - lambda) * max over the last slots j of sim(i, j)`. */
    private fun value(s: Scored, list: List<Prior>, graph: Graph, lambda: Double): Double {
        var maxSim = 0.0
        var back = 1
        while (back <= MMR_WINDOW && list.size - back >= 0) {
            val p = list[list.size - back]
            val sim = Similarity.sim(graph, s.c.rid, s.c.aid, p.rid, p.aid)
            if (sim > maxSim) maxSim = sim
            back++
        }
        return lambda * s.z - (1.0 - lambda) * maxSim
    }

    /** Order: the key descending, then `splitmix64(rid xor seed)` ascending (unsigned), then rid ascending. */
    private fun better(key: Long, rid: Long, otherKey: Long, otherRid: Long, tieSeed: Long): Boolean {
        if (key != otherKey) return key > otherKey
        val cmp = BrainMath.compareUnsigned(BrainHash.splitmix64(rid xor tieSeed), BrainHash.splitmix64(otherRid xor tieSeed))
        if (cmp != 0) return cmp < 0
        return rid < otherRid
    }

    private fun contains(ids: LongArray, id: Long): Boolean {
        if (id == 0L) return false
        for (v in ids) if (v == id) return true
        return false
    }

    /**
     * The spacing rules of 6.4 for the slot after [list]: the same primary artist not within
     * [ARTIST_GAP] slots and at most 2 per 20 and 3 per 50; the same album not within [ALBUM_GAP].
     */
    fun fits(list: List<Prior>, c: Candidate, thinAid: Long): Boolean {
        val size = list.size
        if (c.aid != 0L) {
            val gap = if (c.aid == thinAid) THIN_ARTIST_GAP else ARTIST_GAP
            var within20 = 0
            var within50 = 0
            var back = 1
            while (back <= 49 && size - back >= 0) {
                if (list[size - back].aid == c.aid) {
                    if (back <= gap) return false
                    if (back <= 19) within20++
                    within50++
                }
                back++
            }
            if (within20 >= 2 || within50 >= 3) return false
        }
        if (c.albumKey != 0L) {
            var back = 1
            while (back <= ALBUM_GAP && size - back >= 0) {
                if (list[size - back].albumKey == c.albumKey) return false
                back++
            }
        }
        return true
    }
}
