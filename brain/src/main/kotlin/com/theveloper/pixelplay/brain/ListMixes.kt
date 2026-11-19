package com.theveloper.pixelplay.brain

/**
 * The simple made-for-you lists of docs/brain-spec.md, 6.2 and 8.2 (stage 2b): On Repeat, Rediscover and
 * Just Dropped. Each ranks a short list of candidates by one number and fills 30 slots greedily under the
 * hard constraints of 6.4 (no recording twice, nothing another mix of the day holds, artists and albums
 * spaced). First Listen, Daily Mix and Your Mix go through [StationPlanner] instead.
 *
 * Pure: the same candidates and request give the same list on both platforms.
 */
object ListMixes {
    const val SIZE = 30

    /** On Repeat looks at the last 30 days. */
    const val ON_REPEAT_WINDOW_MS = 30L * 86_400_000L

    /** A track needs this much recent positive mass (about one finished play) to be "on repeat" at all. */
    const val ON_REPEAT_MIN_MASS = 1.0

    /** Rediscover: loved (`aff > 0.5`) and not played for 90 days. */
    const val REDISCOVER_AFF = 0.5
    const val REDISCOVER_AGE_MS = 90L * 86_400_000L

    /** One candidate with the one number its list is ranked by. */
    class Item(
        val rid: Long,
        val aid: Long,
        val albumKey: Long,
        val score: Double,
        val arm: Int,
        /** True for music the listener does not have (counts as novel in the list's health). */
        val novel: Boolean = false,
        /** Free for the caller to carry (an index into its own table). */
        val tag: Int = -1,
    )

    /**
     * Fills up to [count] slots: at each slot the best remaining item (by the quantised score, then the
     * jittered tie-break of 10.3) that breaks no spacing rule. [excluded] are recordings another mix of the
     * day holds or that the listener ruled out; they are never picked.
     */
    fun fill(items: List<Item>, count: Int, excluded: Set<Long>, tieSeed: Long, maxPerArtist: Int = 0): List<Item> {
        val out = ArrayList<Item>(count)
        if (count <= 0 || items.isEmpty()) return out
        val used = HashSet<Long>()
        val list = ArrayList<ListBuilder.Prior>(count)
        while (out.size < count) {
            var best: Item? = null
            var bestKey = 0L
            for (x in items) {
                if (x.rid == 0L || x.rid in used || x.rid in excluded) continue
                val c = Candidate(x.rid, x.aid, x.albumKey)
                if (!ListBuilder.fits(list, c, 0L)) continue
                if (maxPerArtist > 0 && x.aid != 0L) {
                    var n = 0
                    for (p in list) if (p.aid == x.aid) n++
                    if (n >= maxPerArtist) continue
                }
                val key = BrainMath.orderKey(x.score)
                if (best == null || key > bestKey || (key == bestKey && tieBreak(x.rid, best.rid, tieSeed))) {
                    best = x
                    bestKey = key
                }
            }
            val pick = best ?: break
            used.add(pick.rid)
            out.add(pick)
            list.add(ListBuilder.Prior(pick.rid, pick.aid, pick.albumKey, pick.novel))
        }
        return out
    }

    private fun tieBreak(rid: Long, otherRid: Long, tieSeed: Long): Boolean {
        val cmp = BrainMath.compareUnsigned(BrainHash.splitmix64(rid xor tieSeed), BrainHash.splitmix64(otherRid xor tieSeed))
        if (cmp != 0) return cmp < 0
        return rid < otherRid
    }

    /**
     * On Repeat (6.2, nu 0): the tracks with the most decayed plays in the last 30 days. The score is the
     * track's 21-day positive mass (finished plays, each decayed by age), and the track must have been played
     * inside the window and carry at least [ON_REPEAT_MIN_MASS].
     */
    fun onRepeat(affinity: Affinity.State, rids: LongArray, aids: LongArray, albumKeys: LongArray, nowMs: Long): List<Item> {
        val out = ArrayList<Item>()
        for (i in rids.indices) {
            val k = affinity.counter(Family.TRACK, rids[i]) ?: continue
            if (k.lastMs <= 0L || nowMs - k.lastMs > ON_REPEAT_WINDOW_MS) continue
            val mass = Affinity.posRecent(k, nowMs)
            if (mass < ON_REPEAT_MIN_MASS) continue
            out.add(Item(rids[i], aids[i], albumKeys[i], mass, Arm.FAV, tag = i))
        }
        return out
    }

    /**
     * Rediscover (6.2, nu 0): loved tracks (`aff(track) > 0.5`) not played for 90 days. Ranked by affinity less the
     * 30-day served penalty, so what the last weeks' lists showed steps aside and the list rotates.
     */
    fun rediscover(
        affinity: Affinity.State,
        rids: LongArray,
        aids: LongArray,
        albumKeys: LongArray,
        servedTimes: Map<Long, Int>,
        nowMs: Long,
    ): List<Item> {
        val out = ArrayList<Item>()
        val p0 = Affinity.p0(affinity, nowMs)
        for (i in rids.indices) {
            val k = affinity.counter(Family.TRACK, rids[i]) ?: continue
            if (k.lastMs <= 0L || nowMs - k.lastMs < REDISCOVER_AGE_MS) continue
            val aff = Affinity.aff(affinity, Family.TRACK, rids[i], nowMs, p0)
            if (aff <= REDISCOVER_AFF) continue
            val score = aff - Repetition.servedPenalty(servedTimes[rids[i]] ?: 0)
            out.add(Item(rids[i], aids[i], albumKeys[i], score, Arm.FAV, tag = i))
        }
        return out
    }

    /**
     * Just Dropped (6.2, nu 1): new releases, the artists the listener loves first. [artistAff] is the affinity
     * of each item's artist; an artist the listener has never played scores below every loved one, and
     * within one level the more recent card comes first ([recency] is bigger for newer cards).
     */
    fun justDropped(
        rids: LongArray,
        aids: LongArray,
        albumKeys: LongArray,
        artistAff: DoubleArray,
        artistHeard: BooleanArray,
        recency: DoubleArray,
    ): List<Item> {
        val out = ArrayList<Item>(rids.size)
        for (i in rids.indices) {
            val loved = if (artistHeard[i]) 3.0 + artistAff[i] else 0.0
            out.add(Item(rids[i], aids[i], albumKeys[i], loved + 0.001 * recency[i], Arm.NEW, novel = true, tag = i))
        }
        return out
    }
}
