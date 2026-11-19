package com.theveloper.pixelplay.brain

/**
 * Taste islands (docs/brain-spec.md, 5.4, stage 2): weighted spherical k-means over the listener's top
 * 1,500 tracks by `w_t = sum over plays of c * r * 2^(-ageDays/180)` (computed by the caller).
 *
 * ```
 * point x_t = concat( e_t / 127 , 0.7 * P(g_t) ), L2-normalised;  P = a fixed hashed random projection of the tag vector to 32 dims
 * K0        = clamp( round( sqrt(n / 20) ), 3, 12 )
 * init      = first centre: highest w; each next: the point maximising sqrt(w) * (1 - max cos to the chosen centres)
 * iterate   = exactly 15 rounds, ties to the lower cluster index
 * merge     = centroid cosine > 0.85;  dissolve clusters under 8 tracks into the nearest
 * stability = match new clusters to old greedily by descending centroid cosine, so "Daily Mix 2" stays the same island
 * ```
 * Fewer than 60 engaged tracks: one island. A track with neither an embedding nor a tag has no point and
 * cannot be placed (unknown stays unknown); it is left out of the fit.
 */
object Islands {
    const val DIM = Embeddings.DIM + TagProjection.DIM
    const val MAX_TRACKS = 1500
    const val MIN_FOR_MANY = 60
    const val ROUNDS = 15
    const val MERGE_COS = 0.85
    const val MIN_SIZE = 8
    const val MIN_K = 3
    const val MAX_K = 12
    const val TAG_FACTOR = 0.7

    /** A matched new cluster must be at least this close to an old one to keep its id. */
    const val MATCH_FLOOR = 0.5

    /** One track that goes into the fit. */
    class Track(
        val rid: Long,
        val aid: Long,
        /** `w_t`. */
        val weight: Double,
        /** The part of [weight] from the last 21 days. */
        val recentWeight: Double,
        val emb: ByteArray?,
        val tags: Tags.Vector?,
    )

    class Island(
        val id: Int,
        /** Unit length, [DIM] numbers. */
        val centroid: DoubleArray,
        val medoidRid: Long,
        /** Up to 5 atlas ids by weighted share, best first. */
        val topTags: IntArray,
        /** Up to 5 atlas ids by lift over the listener's average, best first (the name of the island, 8.2). */
        val liftTags: IntArray,
        /** Up to 3 artists by weight, best first. */
        val topArtists: LongArray,
        /** `W_c`. */
        val weight: Double,
        /** The 21-day weight. */
        val recentWeight: Double,
        val size: Int,
    ) {
        fun withId(newId: Int): Island =
            Island(newId, centroid, medoidRid, topTags, liftTags, topArtists, weight, recentWeight, size)
    }

    class Fit(
        val islands: List<Island>,
        /** Placed tracks, by rid ascending, and the id of the island each belongs to. */
        val memberRids: LongArray,
        val memberIslandIds: IntArray,
        /** The largest island id ever used: pass it as `nextId - 1` to the next fit so ids are never reused. */
        val maxId: Int,
        /** Atlas ids the listener's tracks carry, and each one's share of their weighted tag mass; ascending id. */
        val tagIds: IntArray = IntArray(0),
        val tagShares: DoubleArray = DoubleArray(0),
    ) {
        fun tagShare(atlasId: Int): Double {
            val i = java.util.Arrays.binarySearch(tagIds, atlasId)
            return if (i >= 0) tagShares[i] else 0.0
        }

        fun islandOf(rid: Long): Int {
            var lo = 0
            var hi = memberRids.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = memberRids[mid]
                if (v == rid) return memberIslandIds[mid]
                if (v < rid) lo = mid + 1 else hi = mid - 1
            }
            return 0
        }
    }

    /** The point of a track, or null when it has neither an embedding nor a tag. */
    fun point(emb: ByteArray?, tags: Tags.Vector?): DoubleArray? {
        val x = DoubleArray(DIM)
        var any = false
        if (emb != null) {
            for (j in 0 until Embeddings.DIM) {
                x[j] = emb[j].toDouble() / 127.0
                if (emb[j].toInt() != 0) any = true
            }
        }
        if (tags != null && !tags.isEmpty) {
            val p = TagProjection.project(tags)
            for (j in 0 until TagProjection.DIM) x[Embeddings.DIM + j] = TAG_FACTOR * p[j]
            any = true
        }
        if (!any) return null
        var norm = 0.0
        for (v in x) norm += v * v
        if (norm <= 0.0) return null
        val inv = 1.0 / BrainMath.sqrt(norm)
        for (j in 0 until DIM) x[j] *= inv
        return x
    }

    /** Cosine of two unit points. */
    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (j in 0 until DIM) s += a[j] * b[j]
        return s
    }

    /** `sim(track, island)` for the island features: the cosine to the centroid, 0..1. */
    fun similarity(island: Island, point: DoubleArray): Double = BrainMath.clip(dot(island.centroid, point), 0.0, 1.0)

    fun kFor(n: Int): Int {
        val k = BrainMath.floorToLong(BrainMath.sqrt(n.toDouble() / 20.0) + 0.5).toInt()
        return if (k < MIN_K) MIN_K else if (k > MAX_K) MAX_K else k
    }

    fun fit(tracks: List<Track>, previous: List<Island> = emptyList(), lastId: Int = 0): Fit {
        val ordered = tracks.sortedWith { a, b ->
            val ka = BrainMath.orderKey(a.weight)
            val kb = BrainMath.orderKey(b.weight)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.rid.compareTo(b.rid)
        }.take(MAX_TRACKS)
        val used = ArrayList<Track>(ordered.size)
        val pts = ArrayList<DoubleArray>(ordered.size)
        for (t in ordered) {
            if (t.weight <= 0.0) continue
            val p = point(t.emb, t.tags) ?: continue
            used.add(t)
            pts.add(p)
        }
        val n = used.size
        if (n == 0) return Fit(emptyList(), LongArray(0), IntArray(0), lastId)
        val assign = IntArray(n)
        var centres: ArrayList<DoubleArray>
        if (n < MIN_FOR_MANY) {
            centres = arrayListOf(centroidOf(used, pts, IntArray(n) { it }, null))
        } else {
            centres = seedCentres(used, pts, kFor(n))
            for (round in 0 until ROUNDS) {
                assignAll(pts, centres, assign)
                for (c in centres.indices) {
                    val members = membersOf(assign, c)
                    if (members.isNotEmpty()) centres[c] = centroidOf(used, pts, members, centres[c])
                }
            }
            assignAll(pts, centres, assign)
            // Merge: the closest pair of centres above the threshold, the lower index absorbing the other.
            while (centres.size > 1) {
                var bi = -1
                var bj = -1
                var best = MERGE_COS
                for (i in 0 until centres.size) for (j in i + 1 until centres.size) {
                    val c = dot(centres[i], centres[j])
                    if (c > best) {
                        best = c
                        bi = i
                        bj = j
                    }
                }
                if (bi < 0) break
                for (x in 0 until n) {
                    if (assign[x] == bj) assign[x] = bi else if (assign[x] > bj) assign[x] = assign[x] - 1
                }
                centres.removeAt(bj)
                val members = membersOf(assign, bi)
                if (members.isNotEmpty()) centres[bi] = centroidOf(used, pts, members, centres[bi])
            }
            // Dissolve: the smallest cluster under MIN_SIZE goes to the nearest of the others.
            while (centres.size > 1) {
                var small = -1
                var smallN = MIN_SIZE
                for (c in centres.indices) {
                    val m = membersOf(assign, c).size
                    if (m < smallN) {
                        smallN = m
                        small = c
                    }
                }
                if (small < 0) break
                val gone = centres[small]
                centres.removeAt(small)
                for (x in 0 until n) if (assign[x] > small) assign[x] = assign[x] - 1 else if (assign[x] == small) assign[x] = -1
                for (x in 0 until n) if (assign[x] == -1) assign[x] = nearest(pts[x], centres)
                for (c in centres.indices) {
                    val members = membersOf(assign, c)
                    if (members.isNotEmpty()) centres[c] = centroidOf(used, pts, members, centres[c])
                }
                if (gone.isEmpty()) break
            }
        }
        if (centres.size == 1) for (x in 0 until n) assign[x] = 0
        else assignAll(pts, centres, assign)

        // Global tag mass, once, in the order of `used`.
        val globalMass = java.util.TreeMap<Int, Double>()
        var globalTotal = 0.0
        for (t in used) {
            val v = t.tags ?: continue
            for (g in v.tags) {
                val m = t.weight * (g.q.toDouble() / 255.0)
                globalMass[g.atlasId] = (globalMass[g.atlasId] ?: 0.0) + m
                globalTotal += m
            }
        }
        val raw = ArrayList<Island>(centres.size)
        for (c in centres.indices) {
            val members = membersOf(assign, c)
            if (members.isEmpty()) continue
            raw.add(describe(c, centres[c], used, pts, members, globalMass, globalTotal))
        }
        // Largest first; ties by the medoid, then the matching keeps old ids where it can.
        raw.sortWith { a, b ->
            val ka = BrainMath.orderKey(a.weight)
            val kb = BrainMath.orderKey(b.weight)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.medoidRid.compareTo(b.medoidRid)
        }
        val ids = matchIds(previous, raw, lastId)
        val islands = ArrayList<Island>(raw.size)
        var maxId = lastId
        for (i in raw.indices) {
            islands.add(raw[i].withId(ids[i]))
            if (ids[i] > maxId) maxId = ids[i]
        }
        // Membership by rid: each placed track and the final id of its island.
        val idByTempCluster = HashMap<Int, Int>()
        val centreOfRaw = HashMap<Int, Int>()
        for (i in raw.indices) centreOfRaw[raw[i].id] = i
        // raw[i].id still holds the temporary cluster number (see [describe]).
        for (i in raw.indices) idByTempCluster[raw[i].id] = ids[i]
        val order = (0 until n).sortedBy { used[it].rid }
        val rids = LongArray(n)
        val owner = IntArray(n)
        for (k in 0 until n) {
            rids[k] = used[order[k]].rid
            owner[k] = idByTempCluster[assign[order[k]]] ?: 0
        }
        val tagIds = globalMass.keys.toIntArray()
        val tagShares = DoubleArray(tagIds.size) { if (globalTotal > 0.0) globalMass[tagIds[it]]!! / globalTotal else 0.0 }
        return Fit(islands, rids, owner, maxId, tagIds, tagShares)
    }

    private fun nearest(p: DoubleArray, centres: List<DoubleArray>): Int {
        var best = 0
        var bestCos = dot(p, centres[0])
        for (c in 1 until centres.size) {
            val v = dot(p, centres[c])
            if (v > bestCos) {
                bestCos = v
                best = c
            }
        }
        return best
    }

    private fun assignAll(pts: List<DoubleArray>, centres: List<DoubleArray>, assign: IntArray) {
        for (x in pts.indices) assign[x] = nearest(pts[x], centres)
    }

    private fun membersOf(assign: IntArray, cluster: Int): IntArray {
        var n = 0
        for (a in assign) if (a == cluster) n++
        val out = IntArray(n)
        var k = 0
        for (x in assign.indices) if (assign[x] == cluster) out[k++] = x
        return out
    }

    /** The weighted mean of the members' points, normalised; [fallback] when it has no direction. */
    private fun centroidOf(used: List<Track>, pts: List<DoubleArray>, members: IntArray, fallback: DoubleArray?): DoubleArray {
        val sum = DoubleArray(DIM)
        for (x in members) {
            val w = used[x].weight
            val p = pts[x]
            for (j in 0 until DIM) sum[j] += w * p[j]
        }
        var norm = 0.0
        for (v in sum) norm += v * v
        if (norm <= 0.0) return fallback ?: DoubleArray(DIM)
        val inv = 1.0 / BrainMath.sqrt(norm)
        for (j in 0 until DIM) sum[j] *= inv
        return sum
    }

    private fun seedCentres(used: List<Track>, pts: List<DoubleArray>, k: Int): ArrayList<DoubleArray> {
        val n = used.size
        val chosen = ArrayList<Int>(k)
        chosen.add(0) // `used` is ordered by weight, so index 0 is the highest `w`.
        val maxCos = DoubleArray(n) { dot(pts[it], pts[0]) }
        while (chosen.size < k && chosen.size < n) {
            var best = -1
            var bestKey = 0L
            for (x in 0 until n) {
                if (chosen.contains(x)) continue
                val key = BrainMath.orderKey(BrainMath.sqrt(used[x].weight) * (1.0 - maxCos[x]))
                if (best < 0 || key > bestKey) {
                    best = x
                    bestKey = key
                }
            }
            if (best < 0) break
            chosen.add(best)
            for (x in 0 until n) {
                val c = dot(pts[x], pts[best])
                if (c > maxCos[x]) maxCos[x] = c
            }
        }
        val out = ArrayList<DoubleArray>(chosen.size)
        for (x in chosen) out.add(pts[x].copyOf())
        return out
    }

    private fun describe(
        cluster: Int,
        centre: DoubleArray,
        used: List<Track>,
        pts: List<DoubleArray>,
        members: IntArray,
        globalMass: java.util.TreeMap<Int, Double>,
        globalTotal: Double,
    ): Island {
        var weight = 0.0
        var recent = 0.0
        var medoid = members[0]
        var medoidKey = Long.MIN_VALUE
        val tagMass = java.util.TreeMap<Int, Double>()
        var tagTotal = 0.0
        val artistWeight = java.util.TreeMap<Long, Double>()
        for (x in members) {
            val t = used[x]
            weight += t.weight
            recent += t.recentWeight
            val key = BrainMath.orderKey(dot(pts[x], centre))
            if (key > medoidKey || (key == medoidKey && t.rid < used[medoid].rid)) {
                medoidKey = key
                medoid = x
            }
            val v = t.tags
            if (v != null) for (g in v.tags) {
                val m = t.weight * (g.q.toDouble() / 255.0)
                tagMass[g.atlasId] = (tagMass[g.atlasId] ?: 0.0) + m
                tagTotal += m
            }
            if (t.aid != 0L) artistWeight[t.aid] = (artistWeight[t.aid] ?: 0.0) + t.weight
        }
        val byShare = tagMass.keys.sortedWith { a, b ->
            val ka = BrainMath.orderKey(tagMass[a]!!)
            val kb = BrainMath.orderKey(tagMass[b]!!)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
        }.take(5)
        val lifts = HashMap<Int, Double>()
        for ((id, m) in tagMass) {
            val share = if (tagTotal > 0.0) m / tagTotal else 0.0
            val g = globalMass[id] ?: 0.0
            val globalShare = if (globalTotal > 0.0) g / globalTotal else 0.0
            if (share >= MIN_LIFT_SHARE && globalShare > 0.0) lifts[id] = share / globalShare
        }
        val byLift = lifts.keys.sortedWith { a, b ->
            val ka = BrainMath.orderKey(lifts[a]!!)
            val kb = BrainMath.orderKey(lifts[b]!!)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
        }.take(5)
        val artists = artistWeight.keys.sortedWith { a, b ->
            val ka = BrainMath.orderKey(artistWeight[a]!!)
            val kb = BrainMath.orderKey(artistWeight[b]!!)
            if (ka != kb) (if (ka > kb) -1 else 1) else a.compareTo(b)
        }.take(3)
        return Island(
            id = cluster, centroid = centre, medoidRid = used[medoid].rid,
            topTags = byShare.toIntArray(), liftTags = byLift.toIntArray(), topArtists = artists.toLongArray(),
            weight = weight, recentWeight = recent, size = members.size,
        )
    }

    /** A tag must make up at least this much of an island to count for its name (lift on a trace is noise). */
    const val MIN_LIFT_SHARE = 0.03

    /**
     * Greedy matching (5.4 "stability"): new clusters take the id of the old island they are closest to, by
     * descending centroid cosine, once the cosine reaches [MATCH_FLOOR]; the rest get fresh ids above [lastId].
     */
    fun matchIds(old: List<Island>, fresh: List<Island>, lastId: Int): IntArray {
        val ids = IntArray(fresh.size) { -1 }
        class Pair(val o: Int, val n: Int, val key: Long)
        val pairs = ArrayList<Pair>()
        for (o in old.indices) for (n in fresh.indices) {
            val c = dot(old[o].centroid, fresh[n].centroid)
            if (c >= MATCH_FLOOR) pairs.add(Pair(o, n, BrainMath.orderKey(c)))
        }
        pairs.sortWith { a, b ->
            if (a.key != b.key) (if (a.key > b.key) -1 else 1)
            else if (old[a.o].id != old[b.o].id) old[a.o].id.compareTo(old[b.o].id) else a.n.compareTo(b.n)
        }
        val takenOld = HashSet<Int>()
        for (p in pairs) {
            if (ids[p.n] >= 0 || p.o in takenOld) continue
            ids[p.n] = old[p.o].id
            takenOld.add(p.o)
        }
        var next = lastId
        for (island in old) if (island.id > next) next = island.id
        for (n in fresh.indices) if (ids[n] < 0) {
            next++
            ids[n] = next
        }
        return ids
    }
}

/** The hashed random projection of a tag vector to [DIM] numbers (5.4 `P`). Fixed: nothing here is fitted. */
object TagProjection {
    const val DIM = 32
    private val SCALE = 1.0 / BrainMath.sqrt(32.0)

    /** `R(atlasId, j)`: plus or minus one, from the lowest bit of a SplitMix64 hash. */
    fun sign(atlasId: Int, j: Int): Double {
        val h = BrainHash.splitmix64(BrainHash.splitmix64(atlasId.toLong()) xor (j + 1000).toLong())
        return if ((h and 1L) == 0L) 1.0 else -1.0
    }

    fun project(v: Tags.Vector): DoubleArray {
        val out = DoubleArray(DIM)
        for (t in v.tags) {
            val w = t.q.toDouble() / 255.0
            for (j in 0 until DIM) out[j] += w * sign(t.atlasId, j)
        }
        for (j in 0 until DIM) out[j] *= SCALE
        return out
    }
}

/**
 * The name of an island (docs/brain-spec.md, 8.2): its two tags with the highest lift over the listener's average,
 * by atlas name. A tag that is an ancestor or a descendant of the first one is skipped (so "pop" does not follow
 * "dance-pop"). Only real atlas genres are returned: an island with no tags has no name, and the surface shows
 * "Daily Mix N" and the artists alone.
 */
object IslandNames {
    fun pick(atlas: GenreAtlas, liftTags: IntArray, count: Int = 2): IntArray {
        val out = ArrayList<Int>(count)
        for (id in liftTags) {
            val g = atlas.genre(id) ?: continue
            if (g.name.isEmpty()) continue
            var related = false
            for (chosen in out) {
                if (atlas.ancestors(chosen).contains(id) || atlas.ancestors(id).contains(chosen)) related = true
            }
            if (related) continue
            out.add(id)
            if (out.size >= count) break
        }
        return out.toIntArray()
    }

    private fun IntArray.contains(v: Int): Boolean {
        for (x in this) if (x == v) return true
        return false
    }
}
