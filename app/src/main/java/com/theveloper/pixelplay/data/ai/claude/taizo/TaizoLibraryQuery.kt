package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.text.FoldedText
import java.util.Random

enum class LibrarySort(val key: String) {
    RELEVANCE("relevance"), MOST_PLAYED("most_played"), RECENTLY_PLAYED("recently_played"),
    RECENTLY_ADDED("recently_added"), RANDOM("random"), TITLE("title");

    companion object {
        fun fromKey(key: String?): LibrarySort = entries.firstOrNull { it.key == key } ?: RELEVANCE
    }
}

/** What Ask Taizo may ask of the whole library at once. Every field but [query] narrows the result. */
data class LibraryFilter(
    val query: String = "",
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minPlays: Int? = null,
    val maxPlays: Int? = null,
    val likedOnly: Boolean = false,
    val neverPlayed: Boolean = false,
    val sort: LibrarySort = LibrarySort.RELEVANCE,
    val limit: Int = TaizoLibraryQuery.DEFAULT_LIMIT,
    val offset: Int = 0,
)

/** Plays of one song across this app and imported history. */
data class SongPlayStats(val plays: Int = 0, val lastPlayedMs: Long = 0L)

/** One page of a library search. [total] counts every match, not just this page. */
data class LibraryPage(val total: Int, val songs: List<Song>, val offset: Int) {
    val hasMore: Boolean get() = offset + songs.size < total
}

private class LibraryHit(val song: Song, val plays: SongPlayStats, val score: Int)

/**
 * Searches and pages the whole library in memory (TaizoLibraryQueryTest). The database search behind
 * the library screen stops at 100 rows; this never does, so Ask Taizo can see every song.
 */
object TaizoLibraryQuery {
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 200

    private val BY_PLAYS: Comparator<LibraryHit> =
        compareByDescending<LibraryHit> { it.plays.plays }.thenBy { it.song.title.lowercase() }.thenBy { it.song.id }

    /**
     * @param stats play counts by library song id (missing = never played)
     * @param liked ids of liked songs, on top of [Song.isFavorite]
     * @param seed makes `random` the same order on every page of one chat
     */
    fun run(
        library: List<Song>,
        stats: Map<String, SongPlayStats>,
        liked: Set<String>,
        filter: LibraryFilter,
        seed: Long = 0L,
    ): LibraryPage {
        val limit = filter.limit.coerceIn(1, MAX_LIMIT)
        val offset = filter.offset.coerceAtLeast(0)
        val tokens = FoldedText.fold(filter.query).split(' ').filter { it.isNotEmpty() }
        val artist = filter.artist?.let { FoldedText.fold(it) }?.takeIf { it.isNotEmpty() }
        val album = filter.album?.let { FoldedText.fold(it) }?.takeIf { it.isNotEmpty() }
        val genre = filter.genre?.let { FoldedText.fold(it) }?.takeIf { it.isNotEmpty() }
        val minPlays = filter.minPlays
        val maxPlays = filter.maxPlays
        val yearFrom = filter.yearFrom
        val yearTo = filter.yearTo

        val hits = ArrayList<LibraryHit>()
        for (song in library) {
            val played = stats[song.id] ?: SongPlayStats()
            if (filter.likedOnly && !(song.isFavorite || song.id in liked)) continue
            if (filter.neverPlayed && (played.plays > 0 || played.lastPlayedMs > 0L)) continue
            if (minPlays != null && played.plays < minPlays) continue
            if (maxPlays != null && played.plays > maxPlays) continue
            if (yearFrom != null && (song.year <= 0 || song.year < yearFrom)) continue
            if (yearTo != null && (song.year <= 0 || song.year > yearTo)) continue
            if (artist != null && !FoldedText.fold("${song.displayArtist}, ${song.artist}").contains(artist)) continue
            if (album != null && !FoldedText.fold(song.album).contains(album)) continue
            if (genre != null && !FoldedText.fold(song.genre).contains(genre)) continue
            val score = if (tokens.isEmpty()) 0 else relevance(song, tokens) ?: continue
            hits += LibraryHit(song, played, score)
        }

        val sorted: List<LibraryHit> = when (filter.sort) {
            LibrarySort.RELEVANCE ->
                if (tokens.isEmpty()) hits.sortedWith(BY_PLAYS)
                else hits.sortedWith(compareByDescending<LibraryHit> { it.score }.then(BY_PLAYS))
            LibrarySort.MOST_PLAYED -> hits.sortedWith(BY_PLAYS)
            LibrarySort.RECENTLY_PLAYED ->
                hits.sortedWith(compareByDescending<LibraryHit> { it.plays.lastPlayedMs }.thenBy { it.song.title.lowercase() }.thenBy { it.song.id })
            LibrarySort.RECENTLY_ADDED ->
                hits.sortedWith(compareByDescending<LibraryHit> { it.song.dateAdded }.thenBy { it.song.title.lowercase() }.thenBy { it.song.id })
            LibrarySort.TITLE ->
                hits.sortedWith(compareBy<LibraryHit> { FoldedText.fold(it.song.title) }.thenBy { it.song.id })
            LibrarySort.RANDOM -> hits.sortedWith(
                compareBy<LibraryHit> { Random(seed xor it.song.id.hashCode().toLong()).nextLong() }.thenBy { it.song.id }
            )
        }
        return LibraryPage(
            total = sorted.size,
            songs = sorted.drop(offset).take(limit).map { it.song },
            offset = offset,
        )
    }

    /** Null when a word is nowhere in the title, artists, album or genre; otherwise how well they fit. */
    private fun relevance(song: Song, tokens: List<String>): Int? {
        val title = FoldedText.fold(song.title)
        val artists = FoldedText.fold("${song.displayArtist} ${song.artist}")
        val album = FoldedText.fold(song.album)
        val genre = FoldedText.fold(song.genre)
        var score = 0
        for (token in tokens) {
            var hit = 0
            if (title.contains(token)) hit += if (title.startsWith(token) || title.contains(" $token")) 6 else 4
            if (artists.contains(token)) hit += 3
            if (album.contains(token)) hit += 2
            if (genre.contains(token)) hit += 1
            if (hit == 0) return null
            score += hit
        }
        if (title == tokens.joinToString(" ")) score += 10
        return score
    }
}
