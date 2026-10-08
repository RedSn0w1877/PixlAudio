package com.theveloper.pixelplay.data.tais.dj

import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.ai.local.TokenBudget
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facts from the listener's own library for Taizo's on-device answers, so a small model talks
 * about the songs the user actually has instead of guessing ("Radiohead: 34 songs, 5 albums
 * (OK Computer, Kid A, …), 210 plays"). A question about their habits ("what do I listen to
 * most?") gets the top artists and genres. At most [OnDevicePrompts.TAIZO_FACTS_TOKENS].
 */
@Singleton
class LibraryLookup @Inject constructor(
    private val musicRepository: MusicRepository,
    private val statsRepository: PlaybackStatsRepository,
) {
    suspend fun factsFor(question: String): String {
        val songs = runCatching { musicRepository.getAllSongsOnce() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        if (songs.isEmpty()) return ""
        val summary = runCatching { statsRepository.loadSummary(StatsTimeRange.ALL, songs) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val stats = LibraryLookupCore.Stats(
            artistPlays = summary?.topArtists.orEmpty().associate { it.artist to it.playCount },
            genrePlays = summary?.topGenres.orEmpty()
                .filter { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
                .associate { it.genre to it.playCount },
            totalPlays = summary?.totalPlayCount ?: 0,
        )
        return withContext(Dispatchers.Default) { LibraryLookupCore.facts(question, songs, stats) }
    }
}

/** The pure part of [LibraryLookup]. */
object LibraryLookupCore {
    data class Stats(
        val artistPlays: Map<String, Int> = emptyMap(),
        val genrePlays: Map<String, Int> = emptyMap(),
        val totalPlays: Int = 0,
    )

    private val HABIT_WORDS = listOf(
        "most", "top", "favorite", "favourite", "listen", "played", "taste", "stats", "often", "usually",
    )

    fun facts(question: String, songs: List<Song>, stats: Stats, budgetTokens: Int = OnDevicePrompts.TAIZO_FACTS_TOKENS): String {
        val q = normalize(question)
        val lines = mutableListOf<String>()

        val byArtist = songs.groupBy { it.primaryArtist.name.ifBlank { it.artist } }.filterKeys { it.isNotBlank() }
        val mentionedArtists = byArtist.keys
            .filter { name -> name.length >= 3 && containsPhrase(q, normalize(name)) }
            .sortedByDescending { it.length }
            .take(3)
        for (artist in mentionedArtists) {
            val artistSongs = byArtist[artist].orEmpty()
            val albums = artistSongs.map { it.album }.filter { it.isNotBlank() }.distinct()
            val albumList = albums.take(4).joinToString(", ") + if (albums.size > 4) ", …" else ""
            val plays = stats.artistPlays[artist]
            lines += buildString {
                append(artist).append(": ").append(artistSongs.size).append(if (artistSongs.size == 1) " song" else " songs")
                if (albums.isNotEmpty()) {
                    append(", ").append(albums.size).append(if (albums.size == 1) " album" else " albums")
                    append(" (").append(albumList).append(')')
                }
                if (plays != null) append(", ").append(plays).append(" plays")
            }
        }

        val byGenre = songs.mapNotNull { song -> song.genre?.trim()?.takeIf { it.isNotEmpty() }?.let { it to song } }
            .groupBy({ it.first }, { it.second })
        val mentionedGenres = byGenre.keys
            .filter { genre -> genre.length >= 3 && containsPhrase(q, normalize(genre)) }
            .take(3)
        for (genre in mentionedGenres) {
            val genreSongs = byGenre[genre].orEmpty()
            val topArtists = genreSongs.groupingBy { it.primaryArtist.name.ifBlank { it.artist } }.eachCount()
                .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
            lines += "$genre: ${genreSongs.size} songs" + if (topArtists.isNotEmpty()) "; mostly $topArtists" else ""
        }

        if (HABIT_WORDS.any { containsPhrase(q, it) } || (mentionedArtists.isEmpty() && mentionedGenres.isEmpty())) {
            val topArtists = stats.artistPlays.entries.sortedByDescending { it.value }.take(5)
            if (topArtists.isNotEmpty()) {
                lines += "Most played artists: " + topArtists.joinToString(", ") { "${it.key} (${it.value} plays)" }
            }
            val topGenres = stats.genrePlays.entries.sortedByDescending { it.value }.take(5)
            if (topGenres.isNotEmpty()) {
                lines += "Most played genres: " + topGenres.joinToString(", ") { "${it.key} (${it.value} plays)" }
            }
        }

        val albumCount = songs.map { it.album to it.primaryArtist.name }.distinct().size
        lines += "Library: ${songs.size} songs, ${byArtist.size} artists, $albumCount albums" +
            if (stats.totalPlays > 0) ", ${stats.totalPlays} plays logged" else ""

        val kept = TokenBudget.shrinkToFit(lines, budgetTokens) { it }
        return kept.joinToString("\n")
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}&]+"), " ").trim()

    private fun containsPhrase(normalizedText: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        return " $normalizedText ".contains(" $phrase ")
    }
}
