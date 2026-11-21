package com.theveloper.pixelplay.brain

/**
 * The seeds of Endless Listening (the endless-listening handoff, "the station"): up to five tracks, one for each of the listener's taste
 * islands, biggest island first. A station started from them plays what the radios of those tracks say, so it starts from the listener's
 * own taste spread over its sides rather than from one song.
 *
 * With fewer than two usable islands (a thin history, or a library the islands could not split) the seeds are the listener's top artists
 * instead, one track each. Pure: the app reads the islands, the plays and the library and hands the numbers over; the same numbers give the
 * same seeds on both platforms (golden `endless.jsonl`).
 */
object EndlessSeeds {
    const val MAX_SEEDS = 5

    /** A library track that may be a seed. [weight] is the listener's decayed play weight of it (0 for a track never played). */
    class Track(val rid: Long, val aid: Long, val weight: Double)

    /** One taste island with the library tracks the fit placed in it. [weight] is the island's own weight (`W_c`). */
    class IslandIn(val id: Int, val weight: Double, val tracks: List<Track>)

    /** One artist with its library tracks, for the fallback. [weight] is the artist's decayed play weight. */
    class ArtistIn(val aid: Long, val weight: Double, val tracks: List<Track>)

    /** A seed: [islandId] is the island it stands for, or -1 for a seed of the fallback. */
    class Seed(val rid: Long, val aid: Long, val islandId: Int)

    /** The seeds, best first; empty when the listener has nothing to seed from. */
    fun pick(islands: List<IslandIn>, artists: List<ArtistIn>): List<Seed> {
        val byIsland = fromIslands(islands)
        if (byIsland.size >= 2) return byIsland
        return fromArtists(artists)
    }

    private fun fromIslands(islands: List<IslandIn>): List<Seed> {
        val ordered = islands.sortedWith(Comparator { a, b ->
            val c = b.weight.compareTo(a.weight)
            if (c != 0) c else a.id.compareTo(b.id)
        })
        val out = ArrayList<Seed>(MAX_SEEDS)
        val usedAids = HashSet<Long>()
        val usedRids = HashSet<Long>()
        for (island in ordered) {
            if (out.size >= MAX_SEEDS) break
            val best = bestOf(island.tracks, usedAids, usedRids) ?: continue
            out.add(Seed(best.rid, best.aid, island.id))
            usedRids.add(best.rid)
            if (best.aid != 0L) usedAids.add(best.aid)
        }
        return out
    }

    private fun fromArtists(artists: List<ArtistIn>): List<Seed> {
        val ordered = artists.sortedWith(Comparator { a, b ->
            val c = b.weight.compareTo(a.weight)
            if (c != 0) c else a.aid.compareTo(b.aid)
        })
        val out = ArrayList<Seed>(MAX_SEEDS)
        val usedAids = HashSet<Long>()
        val usedRids = HashSet<Long>()
        for (artist in ordered) {
            if (out.size >= MAX_SEEDS) break
            if (artist.aid != 0L && artist.aid in usedAids) continue
            val best = bestOf(artist.tracks, emptySet(), usedRids) ?: continue
            out.add(Seed(best.rid, best.aid.takeIf { it != 0L } ?: artist.aid, -1))
            usedRids.add(best.rid)
            if (artist.aid != 0L) usedAids.add(artist.aid)
        }
        return out
    }

    /** The most played track not yet used whose artist is not yet a seed; ties by the lower recording id. */
    private fun bestOf(tracks: List<Track>, usedAids: Set<Long>, usedRids: Set<Long>): Track? {
        var best: Track? = null
        for (t in tracks) {
            if (t.rid == 0L || t.rid in usedRids) continue
            if (t.aid != 0L && t.aid in usedAids) continue
            val b = best
            if (b == null || t.weight > b.weight || (t.weight == b.weight && t.rid < b.rid)) best = t
        }
        return best
    }
}
