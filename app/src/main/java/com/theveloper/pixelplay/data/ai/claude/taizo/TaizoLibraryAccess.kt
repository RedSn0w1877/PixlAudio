package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoFactsGatherer
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.history.ImportedTasteMerge
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.text.FoldedText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Ask Taizo's view of the whole library: `search_library` with filters and paging, and
 * `get_library_overview`. Reads every song once per call on IO ([TaizoLibraryQuery] does the matching),
 * so nothing stops at the 100 rows the library screen's search shows.
 */
@Singleton
class TaizoLibraryAccess @Inject constructor(
    private val musicRepository: MusicRepository,
    private val engagementDao: EngagementDao,
    private val taste: MusicTasteRepository,
    private val adjustments: TasteAdjustmentRepository,
    private val playlists: PlaylistPreferencesRepository,
) {
    /** One shuffle order for the whole process, so paging through `random` results never repeats a song. */
    private val randomSeed = System.nanoTime()

    suspend fun search(filter: LibraryFilter): JsonObject = withContext(Dispatchers.IO) {
        val adj = adjustments.snapshot()
        val all = musicRepository.getAllSongsOnce()
        val library = if (adj.isEmpty) all else all.filterNot { adj.blocks(it) }
        val stats = playStats(library)
        val liked = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val page = TaizoLibraryQuery.run(library, stats, liked, filter, randomSeed)
        buildJsonObject {
            if (filter.query.isNotBlank()) put("query", filter.query.trim())
            put("total", page.total)
            put("offset", page.offset)
            put("returned", page.songs.size)
            put("has_more", page.hasMore)
            if (page.hasMore) put("next_offset", page.offset + page.songs.size)
            putJsonArray("results") {
                page.songs.forEach { song ->
                    add(buildJsonObject {
                        put("id", song.id)
                        put("title", song.title)
                        put("artist", song.displayArtist)
                        if (song.album.isNotBlank()) put("album", song.album)
                        if (song.year > 0) put("year", song.year)
                        put("plays", stats[song.id]?.plays ?: 0)
                        if (song.isFavorite || song.id in liked) put("liked", true)
                        if (!adj.isEmpty && adj.excludes(song)) put("muted", true)
                    })
                }
            }
            val hidden = all.size - library.size
            if (hidden > 0) put("hidden_blocked_songs", hidden)
        }
    }

    suspend fun overview(): JsonObject = withContext(Dispatchers.IO) {
        val library = musicRepository.getAllSongsOnce()
        val stats = playStats(library)
        val liked = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val adj = adjustments.snapshot()
        val genreCounts = HashMap<String, Int>()
        val genreNames = HashMap<String, String>()
        var unknownGenre = 0
        for (song in library) {
            val parts = song.genre.orEmpty().split(',', ';', '/').map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("unknown", true) && !it.equals("unknown genre", true) }
            if (parts.isEmpty()) unknownGenre++
            for (part in parts.distinctBy { FoldedText.fold(it) }) {
                val key = FoldedText.fold(part)
                genreCounts.merge(key, 1, Int::plus)
                genreNames.putIfAbsent(key, part)
            }
        }
        val decades = library.filter { it.year > 0 }.groupingBy { "${it.year / 10 * 10}s" }.eachCount()
        val years = library.map { it.year }.filter { it > 0 }
        val artistNames = HashSet<String>()
        val albumNames = HashSet<String>()
        for (song in library) {
            MusicRecommendationEngine.normalizeKey(song.primaryArtist.name.ifBlank { song.artist }).takeIf { it.isNotEmpty() }?.let(artistNames::add)
            FoldedText.fold(song.album).takeIf { it.isNotEmpty() && it != "unknown album" }?.let(albumNames::add)
        }
        buildJsonObject {
            put("songs", library.size)
            put("artists", artistNames.size)
            put("albums", albumNames.size)
            put("playlists", runCatching { playlists.userPlaylistsFlow.first().size }.getOrDefault(0))
            put("liked_songs", library.count { it.isFavorite || it.id in liked })
            put("never_played", library.count { (stats[it.id]?.plays ?: 0) == 0 && (stats[it.id]?.lastPlayedMs ?: 0L) == 0L })
            put("unknown_genre_songs", unknownGenre)
            putJsonArray("top_genres") {
                genreCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(10).forEach { (key, count) ->
                    add(buildJsonObject {
                        put("genre", genreNames[key] ?: key)
                        put("songs", count)
                    })
                }
            }
            putJsonArray("decades") {
                decades.entries.sortedBy { it.key }.forEach { (decade, count) ->
                    add(buildJsonObject {
                        put("decade", decade)
                        put("songs", count)
                    })
                }
            }
            if (years.isNotEmpty()) {
                putJsonObject("year_range") {
                    put("oldest", years.min())
                    put("newest", years.max())
                }
            }
            if (!adj.isEmpty) {
                putJsonObject("taste_changes") {
                    put("blocked_songs", library.count { adj.blocks(it) })
                    put("muted_or_blocked_songs", library.count { adj.excludes(it) })
                }
            }
            put("paging", "search_library returns up to ${TaizoLibraryQuery.MAX_LIMIT} songs per call; use offset to page through the rest.")
        }
    }

    /** Plays and last play per library song id: this app's own engagement plus imported history. */
    suspend fun playStats(songs: List<Song>): Map<String, SongPlayStats> {
        val engagement = runCatching { engagementDao.getAllEngagements() }.getOrDefault(emptyList()).associateBy { it.songId }
        val imported = runCatching { taste.importedHistory() }.getOrDefault(emptyMap())
        val out = HashMap<String, SongPlayStats>(songs.size * 2)
        for (song in songs) {
            val own = engagement[song.id] ?: song.spotifyId?.let { engagement["spotify_$it"] }
            val local = own?.let { MusicRecommendationEngine.History(it.playCount, it.totalPlayDurationMs, CloudAutoFactsGatherer.normalizeEpochMs(it.lastPlayedTimestamp)) }
            val merged = ImportedTasteMerge.history(local, imported[song.id])
            if (merged.plays > 0 || merged.lastPlayedMs > 0L) {
                out[song.id] = SongPlayStats(merged.plays, merged.lastPlayedMs)
            }
        }
        return out
    }
}
