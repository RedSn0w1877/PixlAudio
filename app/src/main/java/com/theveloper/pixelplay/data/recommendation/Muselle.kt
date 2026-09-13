package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import kotlin.math.abs

/**
 * The named recommendation tiers used throughout PixelPlayer.
 *
 * Muselle is the excellent, private, on-device baseline. Muselle 2 adds a
 * second explainable pass for Plus: it uses the same learned listening signals
 * and adds context, novelty, duration, and diversity balancing. Both tiers are
 * deterministic for a given seed, so Home never flickers while it is open.
 */
enum class MuselleVariant { BASIC, PLUS }

object Muselle {
    fun rank(
        variant: MuselleVariant,
        songs: List<Song>,
        favorites: Set<String>,
        signals: Map<String, MusicRecommendationEngine.Signal>,
        history: Map<String, MusicRecommendationEngine.History>,
        nowMs: Long,
        seed: Long
    ): List<MusicRecommendationEngine.Pick> = when (variant) {
        MuselleVariant.BASIC -> MusicRecommendationEngine.rank(songs, favorites, signals, history, nowMs, seed)
        MuselleVariant.PLUS -> Muselle2.rank(songs, favorites, signals, history, nowMs, seed)
    }
}

/** A stronger local reranker. It does not upload listening history or require a model file. */
object Muselle2 {
    fun rank(
        songs: List<Song>,
        favorites: Set<String>,
        signals: Map<String, MusicRecommendationEngine.Signal>,
        history: Map<String, MusicRecommendationEngine.History>,
        nowMs: Long,
        seed: Long
    ): List<MusicRecommendationEngine.Pick> {
        val baseline = MusicRecommendationEngine.rank(songs, favorites, signals, history, nowMs, seed)
        if (baseline.size < 2) return baseline
        val artistCounts = baseline.groupingBy { MusicRecommendationEngine.artistKey(it.song) }.eachCount()
        val genreCounts = baseline.groupingBy { it.song.genre?.trim()?.lowercase().orEmpty() }.eachCount()
        val medianDuration = baseline.map { it.song.duration }.filter { it > 0 }.sorted()
            .let { values -> values.getOrNull(values.size / 2) ?: 0L }
        val maxYear = baseline.maxOfOrNull { it.song.year }?.takeIf { it > 0 } ?: 0
        return baseline.map { pick ->
            val song = pick.song
            val artistPenalty = ((artistCounts[MusicRecommendationEngine.artistKey(song)] ?: 1) - 1) * 0.035
            val genre = song.genre?.trim()?.lowercase().orEmpty()
            val genrePenalty = if (genre.isBlank()) 0.0 else ((genreCounts[genre] ?: 1) - 1) * 0.012
            val durationFit = if (medianDuration > 0 && song.duration > 0) {
                (1.0 - abs(song.duration - medianDuration).toDouble() / medianDuration).coerceIn(-1.0, 1.0) * 0.06
            } else 0.0
            val eraBoost = if (maxYear > 0 && song.year > 0) {
                (1.0 - abs(song.year - maxYear).toDouble() / 40.0).coerceIn(-1.0, 1.0) * 0.025
            } else 0.0
            val noveltyBoost = if (pick.unheard) 0.045 else 0.0
            val score = pick.score + durationFit + eraBoost + noveltyBoost - artistPenalty - genrePenalty
            val reason = when {
                pick.unheard && noveltyBoost > 0 -> "A fresh pick shaped by your listening patterns"
                durationFit > 0.035 -> "Fits the length of your usual listening sessions"
                artistPenalty > 0.07 -> "Balanced into the queue for more artist variety"
                else -> pick.reason
            }
            pick.copy(score = score, reason = reason)
        }.sortedWith(compareByDescending<MusicRecommendationEngine.Pick> { it.score }.thenBy { it.song.id })
    }

    fun select(
        songs: List<Song>, favorites: Set<String>, signals: Map<String, MusicRecommendationEngine.Signal>,
        history: Map<String, MusicRecommendationEngine.History>, nowMs: Long, seed: Long,
        limit: Int, explorationFraction: Float = 0.3f
    ): List<Song> = MusicRecommendationEngine.select(
        rank(songs, favorites, signals, history, nowMs, seed), limit, explorationFraction
    ).map { it.song }
}
