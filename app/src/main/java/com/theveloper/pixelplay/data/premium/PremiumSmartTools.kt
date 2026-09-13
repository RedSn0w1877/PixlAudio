package com.theveloper.pixelplay.data.premium

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import com.theveloper.pixelplay.data.recommendation.Muselle2
import kotlin.math.roundToInt

/**
 * Local-only Plus tools. They intentionally operate on the library already loaded by the app;
 * no network or cloud entitlement is implied. Existing Daily Mix behaviour remains unchanged.
 */
enum class SmartPlaylistPreset(val displayName: String) {
    FAVORITES("Favorites, refreshed"),
    DISCOVER("Deep discovery"),
    RECENTLY_ADDED("Recently added"),
    SHORT_LISTEN("Short listens"),
    LONG_LISTEN("Long-form listening"),
    ARTIST_RADIO("Artist radio")
}

data class SmartPlaylistResult(
    val preset: SmartPlaylistPreset,
    val songs: List<Song>,
    val explanation: String
)

/** Builds deterministic, explainable playlists from local playback evidence. */
object PremiumSmartPlaylistEngine {
    fun build(
        preset: SmartPlaylistPreset,
        songs: List<Song>,
        favorites: Set<String> = emptySet(),
        history: Map<String, MusicRecommendationEngine.History> = emptyMap(),
        signals: Map<String, MusicRecommendationEngine.Signal> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
        seed: Long = nowMs,
        limit: Int = 30
    ): SmartPlaylistResult {
        val clean = songs.filter { it.title.isNotBlank() }.distinctBy(MusicRecommendationEngine::recordingKey)
        if (limit <= 0 || clean.isEmpty()) return SmartPlaylistResult(preset, emptyList(), explanation(preset))
        val selected = when (preset) {
            SmartPlaylistPreset.FAVORITES -> clean
                .filter { it.isFavorite || it.id in favorites }
                .sortedWith(compareByDescending<Song> { history[it.id]?.plays ?: 0 }.thenBy { it.title })
            SmartPlaylistPreset.RECENTLY_ADDED -> clean.sortedWith(
                compareByDescending<Song> { it.dateAdded }.thenByDescending { it.dateModified }.thenBy { it.title }
            )
            SmartPlaylistPreset.SHORT_LISTEN -> clean.sortedBy { it.duration.takeIf { duration -> duration > 0 } ?: Long.MAX_VALUE }
            SmartPlaylistPreset.LONG_LISTEN -> clean.sortedByDescending { it.duration }
            SmartPlaylistPreset.ARTIST_RADIO -> rankAndSelect(clean, favorites, history, signals, nowMs, seed, limit, 0.2f)
            SmartPlaylistPreset.DISCOVER -> rankAndSelect(clean, favorites, history, signals, nowMs, seed, limit, 0.5f)
        }
        return SmartPlaylistResult(preset, selected.take(limit), explanation(preset))
    }

    private fun rankAndSelect(
        songs: List<Song>, favorites: Set<String>, history: Map<String, MusicRecommendationEngine.History>,
        signals: Map<String, MusicRecommendationEngine.Signal>, nowMs: Long, seed: Long,
        limit: Int, exploration: Float
    ): List<Song> {
        val ranked = Muselle2.rank(songs, favorites, signals, history, nowMs, seed)
        return MusicRecommendationEngine.select(ranked, limit, exploration).map { it.song }
    }

    private fun explanation(preset: SmartPlaylistPreset): String = when (preset) {
        SmartPlaylistPreset.FAVORITES -> "Your favorites, balanced with the tracks you return to most."
        SmartPlaylistPreset.DISCOVER -> "A wider, artist-diverse mix with room for songs you have not played."
        SmartPlaylistPreset.RECENTLY_ADDED -> "The newest music in your library, in arrival order."
        SmartPlaylistPreset.SHORT_LISTEN -> "Quick tracks for a focused listening session."
        SmartPlaylistPreset.LONG_LISTEN -> "Longer tracks for an uninterrupted listening session."
        SmartPlaylistPreset.ARTIST_RADIO -> "A local radio-style mix shaped by your listening history."
    }
}

data class ListeningInsights(
    val songCount: Int,
    val playedSongCount: Int,
    val totalListeningMs: Long,
    val completionRatePercent: Int,
    val topArtists: List<Pair<String, Int>>,
    val topGenres: List<Pair<String, Int>>,
    val discoveryRatePercent: Int
)

/** Formats a duration for a compact Insight Lab card without exposing raw telemetry. */
fun ListeningInsights.totalListeningLabel(): String {
    val minutes = (totalListeningMs / 60_000L).coerceAtLeast(0L)
    return when {
        minutes >= 60L -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

/** A compact, privacy-preserving local summary for the Plus Insight Lab screen. */
object PremiumInsightEngine {
    fun summarize(
        songs: List<Song>,
        history: Map<String, MusicRecommendationEngine.History> = emptyMap(),
        signals: Map<String, MusicRecommendationEngine.Signal> = emptyMap()
    ): ListeningInsights {
        val unique = songs.filter { it.title.isNotBlank() }.distinctBy(MusicRecommendationEngine::recordingKey)
        val played = unique.filter { (history[it.id]?.plays ?: 0) > 0 || (signals[it.id]?.sessions ?: 0) > 0 }
        val totalMs = unique.sumOf {
            maxOf(history[it.id]?.listenedMs ?: 0L, signals[it.id]?.listenedMs ?: 0L)
        }.coerceAtLeast(0L)
        val sessions = unique.sumOf { signals[it.id]?.sessions ?: 0 }.coerceAtLeast(0)
        val completions = unique.sumOf { signals[it.id]?.completions ?: 0 }.coerceAtLeast(0)
        val unheard = unique.count { (history[it.id]?.plays ?: 0) == 0 && (signals[it.id]?.sessions ?: 0) == 0 }
        val artists = unique.groupBy { it.displayArtist.ifBlank { "Unknown artist" } }
            .mapValues { (_, tracks) -> tracks.sumOf { maxOf(history[it.id]?.plays ?: 0, signals[it.id]?.sessions ?: 0) } }
            .filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val genres = unique.mapNotNull { it.genre?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return ListeningInsights(
            songCount = unique.size,
            playedSongCount = played.size,
            totalListeningMs = totalMs,
            completionRatePercent = if (sessions == 0) 0 else (completions.toDouble() / sessions * 100).roundToInt().coerceIn(0, 100),
            topArtists = artists,
            topGenres = genres,
            discoveryRatePercent = if (unique.isEmpty()) 0 else ((unheard.toDouble() / unique.size) * 100).roundToInt().coerceIn(0, 100)
        )
    }
}
