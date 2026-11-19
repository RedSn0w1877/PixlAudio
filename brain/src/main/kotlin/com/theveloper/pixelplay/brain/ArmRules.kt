package com.theveloper.pixelplay.brain

/**
 * The rules of the arms stage 2b completes (docs/brain-spec.md, 6.1): SIM, BACK and EDGE. They decide
 * which candidates carry the flag the planner reads; the data they need (a similar artist's top songs,
 * the imported plays, the atlas) comes from the caller as plain numbers.
 */
object ArmRules {
    /** BACK: imported-history tracks with at least this many completions ... */
    const val BACK_MIN_COMPLETIONS = 5

    /** ... last played over a year ago. */
    const val BACK_AGE_MS = 365L * 86_400_000L

    /** SIM and EDGE start from artists and tags with an affinity above this. */
    const val HIGH_AFFINITY = 0.5

    /** EDGE: a tag the listener plays less than this is "not yet theirs" (`share < 0.005`). */
    const val EDGE_MAX_SHARE = 0.005

    /** BACK: "forgotten favourites", not in the library. */
    fun isBack(completions: Int, lastPlayedMs: Long, nowMs: Long, inLibrary: Boolean): Boolean =
        !inLibrary && completions >= BACK_MIN_COMPLETIONS && lastPlayedMs > 0L && nowMs - lastPlayedMs > BACK_AGE_MS

    /**
     * SIM: the artists to take top tracks from. [aff] and [weight] are the listener's artist affinity and decayed
     * play weight for each of [aids]; the result is the artists with `aff > 0.5` and enough plays (the ARTIST
     * arm's own bar), best first, ties by id. The similar artists of those are what the caller then fetches.
     */
    fun simSources(aids: LongArray, aff: DoubleArray, weight: DoubleArray, limit: Int): LongArray {
        val idx = aids.indices.filter { aff[it] > HIGH_AFFINITY && weight[it] >= StationPlanner.ARTIST_ARM_WEIGHT }
            .sortedWith { a, b ->
                val ka = BrainMath.orderKey(aff[a])
                val kb = BrainMath.orderKey(aff[b])
                if (ka != kb) (if (ka > kb) -1 else 1) else aids[a].compareTo(aids[b])
            }
        val n = if (idx.size < limit) idx.size else limit
        return LongArray(n) { aids[idx[it]] }
    }

    /**
     * EDGE: the genres next to the ones the listener loves. [loved] are atlas ids with `aff > 0.5`, best first;
     * [share] says how much of the listener's listening a tag already is. A genre is adjacent to a loved one when
     * it is a sibling (a child of one of its parents), one of its nearest related genres, or a genre it is a
     * fusion with; not the loved genre itself, not its ancestors or descendants, not a family root, and not one
     * the listener already plays (`share >= 0.005`). Best loved genre first, then by id. Unknown genres are
     * absent: an atlas id the atlas does not hold is skipped.
     */
    fun adjacentGenres(atlas: GenreAtlas, loved: IntArray, share: (Int) -> Double, limit: Int): IntArray {
        val rank = java.util.TreeMap<Int, Int>()
        val all = atlas.allIds
        for (r in loved.indices) {
            val g = atlas.genre(loved[r]) ?: continue
            val ancestors = atlas.ancestors(g.id)
            val candidates = java.util.TreeSet<Int>()
            for (rel in g.related) candidates.add(rel)
            for (fus in g.fusionOf) candidates.add(fus)
            for (parent in g.parents) {
                for (id in all) {
                    val other = atlas.genre(id) ?: continue
                    for (p in other.parents) if (p == parent) candidates.add(id)
                }
            }
            for (c in candidates) {
                val cg = atlas.genre(c) ?: continue
                if (c == g.id || cg.depth < 1) continue
                if (ancestors.contains(c)) continue
                if (atlas.ancestors(c).contains(g.id)) continue
                if (share(c) >= EDGE_MAX_SHARE) continue
                if (!rank.containsKey(c) || rank[c]!! > r) rank[c] = r
            }
        }
        val ordered = rank.keys.sortedWith { a, b ->
            val ra = rank[a]!!
            val rb = rank[b]!!
            if (ra != rb) ra.compareTo(rb) else a.compareTo(b)
        }
        val n = if (ordered.size < limit) ordered.size else limit
        return IntArray(n) { ordered[it] }
    }

    private fun IntArray.contains(v: Int): Boolean {
        for (x in this) if (x == v) return true
        return false
    }
}
