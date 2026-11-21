package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.EndlessSeeds
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.data.model.Song

/** One seed of Endless Listening: a library song, the ids the brain knows it by, and the island it stands for (-1: a top artist). */
class EndlessSeedPick(val song: Song, val rid: Long, val aid: Long, val islandId: Int)

/**
 * The seeds of Endless Listening, drawn from the library tracks of the Your Mix pool (the shortlist of about 500 that Your Mix is planned from):
 * one for each taste island, biggest first, at most five; the listener's top artists with fewer than two islands ([EndlessSeeds]).
 *
 * Reads the island fit the weekly build left, the shortlist (cached for the day) and the in-memory affinity tables; never the network, and
 * it does not open anything that is not already open. Empty when there is nothing to seed from.
 */
internal suspend fun BrainMixes.endlessSeeds(now: Long): List<EndlessSeedPick> {
    val taste = constraints.snapshot(now)
    val library = shortlist(now, BrainMixes.tzOffsetMin(now), salt(), taste)
        .filter { BrainText.fold(LibraryIndex.primaryArtistName(it.song)) !in BrainMixes.NO_ARTIST_NAMES }
    if (library.isEmpty()) return emptyList()
    val set = islandStore.current()
    val memberOf = set?.memberOf ?: emptyMap()
    val inputs = brain.withState { st ->
        val weights = HashMap<Long, Double>(library.size * 2)
        for (t in library) weights[t.rid] = Affinity.weight(st.affinity, Family.TRACK, t.rid, now)
        fun track(t: LibraryTrack) = EndlessSeeds.Track(t.rid, t.aid, weights[t.rid] ?: 0.0)
        val islands = ArrayList<EndlessSeeds.IslandIn>()
        if (set != null) {
            val byIsland = HashMap<Int, ArrayList<EndlessSeeds.Track>>()
            for (t in library) {
                val id = memberOf[t.rid] ?: continue
                byIsland.getOrPut(id) { ArrayList() }.add(track(t))
            }
            for (island in set.islands) {
                val tracks = byIsland[island.id] ?: continue
                islands.add(EndlessSeeds.IslandIn(island.id, island.weight, tracks))
            }
        }
        val byArtist = LinkedHashMap<Long, ArrayList<EndlessSeeds.Track>>()
        for (t in library) if (t.aid != 0L) byArtist.getOrPut(t.aid) { ArrayList() }.add(track(t))
        val artists = byArtist.map { (aid, tracks) -> EndlessSeeds.ArtistIn(aid, Affinity.weight(st.affinity, Family.ARTIST, aid, now), tracks) }
        islands to artists
    }
    val seeds = EndlessSeeds.pick(inputs.first, inputs.second)
    if (seeds.isEmpty()) return emptyList()
    val byRid = HashMap<Long, LibraryTrack>(library.size * 2)
    for (t in library) byRid.putIfAbsent(t.rid, t)
    return seeds.mapNotNull { s -> byRid[s.rid]?.let { EndlessSeedPick(it.song, s.rid, s.aid, s.islandId) } }
}

/**
 * The names of the islands the seeds stand for, as the Daily Mixes name them ("Soft pop & R&B"), in seed order; an island that is not named
 * (too few of its tracks carry a genre) is left out. Real names only: the card says nothing when there are none.
 */
internal suspend fun BrainMixes.endlessIslandNames(seeds: List<EndlessSeedPick>): List<String> {
    val set = islandStore.current() ?: return emptyList()
    val out = ArrayList<String>(seeds.size)
    for (s in seeds) {
        if (s.islandId < 0) continue
        val island = set.islands.firstOrNull { it.id == s.islandId } ?: continue
        val name = islandGenreName(island)
        if (name.isNotBlank() && name !in out) out.add(name)
    }
    return out
}
