package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.YourSound
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a genre page (docs/brain-spec.md, 8.7) says beyond the atlas's own facts, all of it from the listener's own data and read when the page
 * opens, off the main thread: the artists the brain knows with the most of this genre in them, the artists of the listener's library under
 * it, and "You and this genre". Nothing is guessed: an artist with no known genre is absent, and the "you" part does not exist for a genre
 * the listener never played.
 */
@Singleton
class GenrePageRepository @Inject constructor(
    private val artistDna: ArtistDnaDao,
    private val trackDna: TrackDnaDao,
    private val store: BrainStore,
    private val genreStations: GenreStations,
    private val atlasProvider: AtlasProvider,
    private val clock: BrainClock,
    private val switch: BrainSwitch,
) {
    /** "You and this genre": when it was first played, how much of the listening it is, the five tracks played most. */
    class You(val firstPlayedMs: Long, val permille: Int, val plays: Int, val topSongs: List<String>)

    class Extras(val keyArtists: List<String>, val libraryArtists: List<String>, val you: You?)

    suspend fun extras(atlasId: Int): Extras = withContext(Dispatchers.IO) {
        val atlas = atlasProvider.atlas() ?: return@withContext Extras(emptyList(), emptyList(), null)
        Extras(if (tagVotesAllowed()) keyArtists(atlas, atlasId) else emptyList(), libraryArtists(atlasId), you(atlas, atlasId))
    }

    /**
     * Key artists come from the artist tags the brain holds, which the open-data sources (Deezer, MusicBrainz, ListenBrainz; non-commercial
     * terms while the app has a paid tier) filled in. The rows do not say which source a tag came from, so the list is shown only while all
     * three are switched on: turn one off and the list goes with it. Library artists and "You and this genre" are the listener's own data.
     */
    private suspend fun tagVotesAllowed(): Boolean =
        switch.sourceAllowed(com.theveloper.pixelplay.data.brain.source.KnowledgeHost.DEEZER) &&
            switch.sourceAllowed(com.theveloper.pixelplay.data.brain.source.KnowledgeHost.MUSICBRAINZ) &&
            switch.sourceAllowed(com.theveloper.pixelplay.data.brain.source.KnowledgeHost.LISTENBRAINZ)

    /** Whether a tag is the genre or one of its descendants. */
    private fun under(atlas: GenreAtlas, tag: Int, id: Int): Boolean = tag == id || atlas.ancestors(tag).any { it == id }

    /** The artists the brain holds a genre vector for, the largest weight under [id] first (ties: the smaller id), at most [KEY_ARTISTS]. */
    private suspend fun keyArtists(atlas: GenreAtlas, id: Int): List<String> {
        val scored = ArrayList<Pair<Long, Int>>()
        val names = HashMap<Long, String>()
        for (row in artistDna.withTags()) {
            val v = Tags.decode(row.tags)
            var best = 0
            for (t in v.tags) if (t.q > best && under(atlas, t.atlasId, id)) best = t.q
            if (best <= 0 || !AtlasNames.isShown(row.name)) continue
            scored.add(row.aid to best)
            names[row.aid] = row.name
        }
        scored.sortWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else a.first.compareTo(b.first) }
        return scored.take(KEY_ARTISTS).mapNotNull { names[it.first] }
    }

    /** The artists of the listener's own songs under the genre, the one with the most of them first. */
    private suspend fun libraryArtists(id: Int): List<String> {
        val songs = genreStations.songsFor(StationCard.KIND_GENRE, id, clock.nowMs())
        val counts = LinkedHashMap<String, Int>()
        for (song in songs) {
            val name = BrainFeedback.artistOf(song)
            if (name.isNotBlank() && AtlasNames.isShown(name)) counts.merge(name, 1, Int::plus)
        }
        return counts.entries.sortedByDescending { it.value }.take(KEY_ARTISTS).map { it.key }
    }

    private suspend fun you(atlas: GenreAtlas, id: Int): You? {
        val rids = ArrayList<Long>()
        val aids = ArrayList<Long>()
        val starts = ArrayList<Long>()
        val tzs = ArrayList<Int>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = store.eventsAfter(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().eventId
            for (e in page) {
                if (e.has(EventFlags.LEARN_EXCLUDED) || e.has(EventFlags.NEVER_AUDIBLE)) continue
                val listened = e.endReason == EndReason.TRACK_DONE || e.playedMs < 0 || e.playedMs >= LISTEN_MS
                if (!listened) continue
                rids.add(e.rid); aids.add(e.primaryAid); starts.add(e.startedAtMs)
                tzs.add(if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else e.tzOffsetMin)
            }
            if (page.size < PAGE) break
        }
        if (rids.isEmpty()) return null
        val belongs = HashSet<Long>()
        val title = HashMap<Long, String>()
        for (chunk in rids.toHashSet().toList().chunked(DNA_CHUNK)) {
            for (row in trackDna.getAll(chunk)) {
                val v = Tags.decode(row.tags)
                if (v.tags.any { under(atlas, it.atlasId, id) }) {
                    belongs.add(row.rid)
                    if (row.title.isNotBlank()) title[row.rid] = if (row.artistDisplay.isBlank()) row.title else "${row.title} · ${row.artistDisplay}"
                }
            }
        }
        val plays = YourSound.withFirsts(rids.toLongArray(), aids.toLongArray(), starts.toLongArray(), tzs.toIntArray())
        val h = YourSound.genreHistory(plays, 5) { it in belongs } ?: return null
        return You(h.firstPlayedMs, h.permille, h.plays, h.topRids.toList().mapNotNull { title[it] })
    }

    private companion object {
        const val KEY_ARTISTS = 8
        const val PAGE = 2_000
        const val DNA_CHUNK = 800
        const val LISTEN_MS = 30_000
    }
}
