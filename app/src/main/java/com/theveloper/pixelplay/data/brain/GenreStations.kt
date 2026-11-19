package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.model.Song
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** A genre or mood station as a card (docs/brain-spec.md, 8.1 "Stations for you", 8.3). */
data class StationCard(
    /** [KIND_GENRE] or [KIND_MOOD]. */
    val kind: Int,
    /** The atlas id of the genre, or the index of the mood in the atlas's mood list. */
    val atlasId: Int,
    /** A real atlas name, sentence case. Never invented: a node the atlas does not hold has no card. */
    val name: String,
    /** How many of the listener's songs sit under it. */
    val count: Int,
    /** Up to three album arts. */
    val covers: List<String>,
) {
    companion object {
        const val KIND_GENRE = 0
        const val KIND_MOOD = 1
    }
}

/**
 * Genre and mood stations (docs/brain-spec.md, 6.2, 8.3, stage 2b): the listener's own songs under an atlas node seed the
 * station and the radios of those seeds fill it, as for an album or a playlist station. Unknown genres are absent: a song
 * with no tags is under no genre, a name the atlas does not hold gives no card and no tile, and a node with none of the
 * listener's songs under it starts nothing.
 *
 * Reads the track DNA of the library once per library change, off the main thread; never at app start.
 */
@Singleton
class GenreStations @Inject constructor(
    private val libraryRids: LibraryRids,
    private val dnaDao: TrackDnaDao,
    private val atlasProvider: AtlasProvider,
    private val brain: BrainEngine,
    private val islandStore: IslandStore,
) {
    private class Index(
        val key: String,
        val byGenre: Map<Int, List<LibraryTrack>>,
        val byMood: Map<Int, List<LibraryTrack>>,
    )

    private val lock = Mutex()
    @Volatile private var cache: Index? = null

    /** The genres and moods of the library, by the DNA the knowledge lanes stored. A track with none is in none. */
    private suspend fun index(nowMs: Long): Index = lock.withLock {
        val all = libraryRids.all(nowMs)
        // The DNA changes while the library does not (the refresh job writes it): its row count and newest stamp are part of the key.
        val key = "${all.size}:${all.firstOrNull()?.rid}:${all.lastOrNull()?.rid}:${islandStore.embeddingCount()}:${dnaDao.count()}:${dnaDao.newestUpdate()}"
        cache?.let { if (it.key == key) return@withLock it }
        val byRid = HashMap<Long, LibraryTrack>(all.size * 2)
        for (t in all) byRid.putIfAbsent(t.rid, t)
        val byGenre = HashMap<Int, ArrayList<LibraryTrack>>()
        val byMood = HashMap<Int, ArrayList<LibraryTrack>>()
        try {
            for (chunk in byRid.keys.sorted().chunked(800)) {
                for (row in dnaDao.getAll(chunk)) {
                    val track = byRid[row.rid] ?: continue
                    val v = Tags.decode(row.tags)
                    // Parents carry weight too (3.4 spreads to them), so "pop" holds its sub-genres' songs.
                    for (t in v.tags) if (t.q >= MIN_Q) byGenre.getOrPut(t.atlasId) { ArrayList() }.add(track)
                    for (m in row.moods) {
                        val id = m.toInt() and 0xFF
                        byMood.getOrPut(id) { ArrayList() }.add(track)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading genres for the stations failed")
        }
        val built = Index(key, byGenre, byMood)
        cache = built
        built
    }

    /** The atlas id of a name ("Hip-Hop", "R&B"), or [GenreAtlas.NONE] when the atlas has no such genre. */
    suspend fun idForName(name: String): Int = atlasProvider.atlas()?.idForName(name) ?: GenreAtlas.NONE

    /** The names among [names] the atlas holds: the Search tiles of a genre the atlas lacks are not shown. */
    suspend fun knownNames(names: List<String>): Set<String>? {
        val atlas = atlasProvider.atlas() ?: return null
        return names.filterTo(HashSet()) { atlas.idForName(it) != GenreAtlas.NONE && AtlasNames.isShown(atlas.genre(atlas.idForName(it))?.name) }
    }

    /** The listener's songs under the node, most played first; empty when there are none (nothing is guessed). */
    suspend fun songsFor(kind: Int, atlasId: Int, nowMs: Long): List<Song> {
        val idx = index(nowMs)
        val tracks = (if (kind == StationCard.KIND_MOOD) idx.byMood[atlasId] else idx.byGenre[atlasId]).orEmpty()
        if (tracks.isEmpty()) return emptyList()
        val weights = brain.withState { st -> tracks.associate { it.rid to Affinity.weight(st.affinity, Family.TRACK, it.rid, nowMs) } }
        return tracks.distinctBy { it.rid }.sortedWith { a, b ->
            val c = BrainMath.orderKey(weights[b.rid] ?: 0.0).compareTo(BrainMath.orderKey(weights[a.rid] ?: 0.0))
            if (c != 0) c else a.rid.compareTo(b.rid)
        }.map { it.song }
    }

    /**
     * Up to six cards from the listener's biggest nodes (5 genres and 1 mood), plus one adjacent genre they hold songs of
     * (8.1 "Stations for you"). A genre must hold at least [MIN_SONGS] of their songs, and a genre related to one already
     * chosen (an ancestor or a descendant) is skipped, so the shelf is not "Pop", "Dance-pop", "Pop rock".
     */
    suspend fun cards(nowMs: Long, adjacent: IntArray): List<StationCard> {
        val atlas = atlasProvider.atlas() ?: return emptyList()
        val idx = index(nowMs)
        val out = ArrayList<StationCard>(7)
        val chosen = ArrayList<Int>()
        val candidates = idx.byGenre.entries
            .filter { (atlas.genre(it.key)?.depth ?: 0) >= 1 && AtlasNames.isShown(atlas.genre(it.key)?.name) && it.value.distinctBy { t -> t.rid }.size >= MIN_SONGS }
        // "Your top nodes": ranked by what the listener plays (decayed finished plays of the songs under each genre), then by how many
        // songs sit under it; a genre the listener owns a lot of but never plays does not lead the shelf.
        val played = playedWeight(candidates.flatMap { it.value }.map { it.rid }.toSet(), nowMs)
        val score = HashMap<Int, Long>(candidates.size * 2)
        for (e in candidates) score[e.key] = BrainMath.orderKey(e.value.distinctBy { it.rid }.sumOf { played[it.rid] ?: 0.0 })
        val genres = candidates.sortedWith { a, b ->
            val c = score[b.key]!!.compareTo(score[a.key]!!)
            if (c != 0) c else {
                val d = b.value.size.compareTo(a.value.size)
                if (d != 0) d else a.key.compareTo(b.key)
            }
        }
        for ((id, tracks) in genres) {
            if (out.size >= GENRE_CARDS) break
            if (chosen.any { related(atlas, it, id) }) continue
            val name = AtlasNames.genreName(atlas, id) ?: continue
            chosen.add(id)
            out.add(card(StationCard.KIND_GENRE, id, name, tracks))
        }
        val moods = idx.byMood.entries
            .filter { it.key in atlas.moods.indices && it.value.distinctBy { t -> t.rid }.size >= MIN_SONGS }
            .sortedWith { a, b ->
                val c = b.value.size.compareTo(a.value.size)
                if (c != 0) c else a.key.compareTo(b.key)
            }
        moods.firstOrNull()?.let { (id, tracks) -> out.add(card(StationCard.KIND_MOOD, id, atlas.moods[id], tracks)) }
        for (id in adjacent) {
            val tracks = idx.byGenre[id].orEmpty()
            if (tracks.size < MIN_ADJACENT_SONGS || out.any { it.kind == StationCard.KIND_GENRE && it.atlasId == id }) continue
            val name = AtlasNames.genreName(atlas, id) ?: continue
            out.add(card(StationCard.KIND_GENRE, id, name, tracks))
            break
        }
        return out
    }

    private suspend fun playedWeight(rids: Set<Long>, nowMs: Long): Map<Long, Double> =
        brain.withState { st ->
            val out = HashMap<Long, Double>(rids.size * 2)
            for (r in rids) {
                val w = Affinity.weight(st.affinity, Family.TRACK, r, nowMs)
                if (w > 0.0) out[r] = w
            }
            out
        }

    private fun card(kind: Int, id: Int, name: String, tracks: List<LibraryTrack>): StationCard {
        val distinct = tracks.distinctBy { it.rid }
        return StationCard(
            kind, id, name.replaceFirstChar { it.titlecase(Locale.getDefault()) }, distinct.size,
            distinct.mapNotNull { it.song.albumArtUriString }.distinct().take(3),
        )
    }

    private fun related(atlas: GenreAtlas, a: Int, b: Int): Boolean {
        for (x in atlas.ancestors(a)) if (x == b) return true
        for (x in atlas.ancestors(b)) if (x == a) return true
        return false
    }

    companion object {
        /** A tag below this weight (of 255) is a trace, not what the song is. */
        const val MIN_Q = 40
        const val MIN_SONGS = 8
        const val MIN_ADJACENT_SONGS = 3
        const val GENRE_CARDS = 5
        /** A genre or mood station is seeded by up to this many of the listener's songs (6.2: up to 5). */
        const val SEEDS = 5
    }
}
