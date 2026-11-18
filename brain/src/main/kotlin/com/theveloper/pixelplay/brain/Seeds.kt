package com.theveloper.pixelplay.brain

/**
 * Which tracks the background worker asks YouTube Music for a radio of (docs/brain-spec.md, 3.6,
 * "Stage 2 before islands exist"; 5.12 rule 10). Pure: the caller hands over the four groups, already
 * ranked, and gets the seeds back.
 *
 * At most 24 seeds a day, in four groups, with at most 2 seeds per primary artist (two seeds by one
 * artist share 28 to 31 of their 50 rows [L]):
 *  - [RECENT]: the 8 newest distinct tracks the listener chose and finished in the last 14 days;
 *  - [TOP]: the 8 top tracks by decayed engagement;
 *  - [SAVED]: 4 tracks liked or saved in the last 30 days;
 *  - [RANDOM]: 4 pseudo-random library tracks with non-negative affinity.
 *
 * A track the engine picked is never a seed unless the listener liked, saved or replayed it: the
 * callers only put tracks they know the listener chose into the groups, and [pseudoRandomOrder] is
 * the only thing here that is not already the listener's own ranking.
 */
object Seeds {
    const val RECENT = 0
    const val TOP = 1
    const val SAVED = 2
    const val RANDOM = 3

    const val RECENT_QUOTA = 8
    const val TOP_QUOTA = 8
    const val SAVED_QUOTA = 4
    const val RANDOM_QUOTA = 4

    /** A day's seeds, whatever the quotas add up to. */
    const val MAX_PER_DAY = 24
    const val MAX_PER_ARTIST = 2

    /** How far back "finished" and "liked or saved" look. */
    const val RECENT_WINDOW_MS = 14L * 86_400_000L
    const val SAVED_WINDOW_MS = 30L * 86_400_000L

    /** One track that may be a seed. [aid] 0 is "unknown artist": it has no per-artist cap. */
    class Seed(val rid: Long, val aid: Long, val group: Int)

    private fun quotaOf(group: Int): Int = when (group) {
        RECENT -> RECENT_QUOTA
        TOP -> TOP_QUOTA
        SAVED -> SAVED_QUOTA
        else -> RANDOM_QUOTA
    }

    /**
     * Walks the groups in order, each best first, and takes from each up to its quota, skipping a
     * recording that is already a seed and an artist that already has [MAX_PER_ARTIST]. [skip] holds
     * recordings that may not be seeds at all (blocked, muted, "not for me").
     */
    fun select(groups: List<List<Seed>>, skip: Set<Long> = emptySet(), perArtist: Int = MAX_PER_ARTIST): List<Seed> {
        val out = ArrayList<Seed>()
        val taken = HashSet<Long>()
        val perArtistCount = HashMap<Long, Int>()
        for (g in groups.indices) {
            var got = 0
            val quota = quotaOf(g)
            for (s in groups[g]) {
                if (got >= quota || out.size >= MAX_PER_DAY) break
                if (s.rid == 0L || s.rid in skip || s.rid in taken) continue
                if (s.aid != 0L) {
                    val n = perArtistCount[s.aid] ?: 0
                    if (n >= perArtist) continue
                    perArtistCount[s.aid] = n + 1
                }
                taken.add(s.rid)
                out.add(Seed(s.rid, s.aid, g))
                got++
            }
        }
        return out
    }

    /**
     * The order of the [RANDOM] group: recordings by `splitmix64(rid xor salt xor day)`, unsigned,
     * smallest first (ties by rid). The same day gives the same order; the next day another.
     */
    fun pseudoRandomOrder(rids: LongArray, salt: Long, day: Long): LongArray {
        val key = LongArray(rids.size) { BrainHash.splitmix64(rids[it] xor salt xor BrainHash.splitmix64(day)) }
        val order = (0 until rids.size).sortedWith { a, b ->
            val c = BrainMath.compareUnsigned(key[a], key[b])
            if (c != 0) c else if (rids[a] < rids[b]) -1 else if (rids[a] > rids[b]) 1 else 0
        }
        return LongArray(rids.size) { rids[order[it]] }
    }
}
