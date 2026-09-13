package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/** Small, explainable on-device learner. No network or model inference is needed to rank. */
object MusicRecommendationEngine {
    data class Signal(
        val sessions: Int = 0,
        val completions: Int = 0,
        val earlySkips: Int = 0,
        val voluntaryPlays: Int = 0,
        val lastPlayedMs: Long = 0,
        val listenedMs: Long = 0
    )

    data class History(val plays: Int = 0, val listenedMs: Long = 0, val lastPlayedMs: Long = 0)
    data class Pick(val song: Song, val score: Double, val reason: String, val unheard: Boolean)

    /**
     * Marca los motivos que explican por qué una canción aparece MENOS. Quien los muestre debe
     * filtrarlos: enseñar "se salta mucho" en la propia tarjeta que la está recomendando se
     * contradice a sí mismo.
     */
    const val REASON_PREFIX_DEMOTED = "Less often"

    fun record(
        previous: Signal, listenedMs: Long, durationMs: Long, voluntary: Boolean,
        changedTrack: Boolean, nowMs: Long
    ): Signal {
        if (listenedMs < 5_000) return previous // Ignore loading failures and accidental taps.
        val completed = durationMs > 0 && listenedMs.toDouble() / durationMs >= 0.8
        val skipped = changedTrack && durationMs > 0 && listenedMs < minOf(30_000L, durationMs / 3)
        return previous.copy(
            sessions = (previous.sessions + 1).coerceAtMost(1_000_000),
            completions = previous.completions + if (completed) 1 else 0,
            earlySkips = previous.earlySkips + if (skipped) 1 else 0,
            voluntaryPlays = previous.voluntaryPlays + if (voluntary) 1 else 0,
            lastPlayedMs = maxOf(previous.lastPlayedMs, nowMs),
            listenedMs = previous.listenedMs + listenedMs.coerceAtLeast(0)
        )
    }

    fun rank(
        songs: List<Song>, favorites: Set<String>, signals: Map<String, Signal>,
        history: Map<String, History>, nowMs: Long, seed: Long
    ): List<Pick> {
        val candidates = songs.filter { it.title.isNotBlank() }.distinctBy { it.id }
            .groupBy(::recordingKey).values.map { versions ->
                RecordingCandidate(
                    song = versions.first(),
                    favorite = versions.any { it.id in favorites || it.isFavorite },
                    signal = mergeSignals(versions.mapNotNull { signals[it.id] }.distinctInstances()),
                    history = mergeHistory(versions.mapNotNull { history[it.id] }.distinctInstances())
                )
            }
        val artistWeights = mutableMapOf<String, Double>()
        val genreWeights = mutableMapOf<String, Double>()
        for (candidate in candidates) {
            val song = candidate.song
            val signal = candidate.signal
            val past = candidate.history
            val lastEngaged = maxOf(signal.lastPlayedMs, past.lastPlayedMs).takeIf { it > 0 } ?: nowMs
            val ageDays = ((nowMs - lastEngaged).coerceAtLeast(0) / DAY.toDouble())
            val decay = 1.0 / (1.0 + ageDays / 45.0)
            val weight = ((if (candidate.favorite) 2.0 else 0.0) +
                ln(1.0 + past.plays.coerceAtLeast(0)) * 0.3 +
                signal.completions * 0.8 + signal.voluntaryPlays * 0.2 -
                signal.earlySkips * 1.1).coerceIn(-4.0, 6.0) * decay
            artistWeights.merge(artistKey(song), weight, Double::plus)
            genreKey(song)?.let { genreWeights.merge(it, weight, Double::plus) }
        }
        val artistScale = artistWeights.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val genreScale = genreWeights.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val playScale = ln(1.0 + (candidates.maxOfOrNull { it.history.plays } ?: 1).coerceAtLeast(1))
        return candidates.map { candidate ->
            val song = candidate.song
            val signal = candidate.signal
            val past = candidate.history
            val favorite = candidate.favorite
            val artist = (artistWeights[artistKey(song)] ?: 0.0) / artistScale
            val genre = (genreWeights[genreKey(song)] ?: 0.0) / genreScale
            val taste = artist * 0.75 + genre * 0.25
            val satisfaction = (signal.completions + 2.0) / (signal.sessions + 4.0)
            val skipRate = signal.earlySkips.toDouble() / (signal.sessions + 2.0)
            val familiarity = ln(1.0 + past.plays.coerceAtLeast(0)) / playScale
            val lastPlayed = maxOf(signal.lastPlayedMs, past.lastPlayedMs)
            val hoursAgo = if (lastPlayed == 0L) 1_000.0 else (nowMs - lastPlayed).coerceAtLeast(0) / 3_600_000.0
            val fatigue = (1.0 - hoursAgo / 24.0).coerceIn(0.0, 1.0)
            val unheard = past.plays == 0 && signal.sessions == 0
            val exploration = 1.0 / sqrt(1.0 + past.plays.coerceAtLeast(0) + signal.sessions)
            val jitter = java.util.Random(seed xor recordingKey(song).hashCode().toLong()).nextDouble() * 0.025
            val score = taste * 0.32 + satisfaction * 0.2 + familiarity * 0.12 +
                (if (favorite) 0.18 else 0.0) + exploration * 0.12 - skipRate * 0.55 - fatigue * 0.2 + jitter
            val reason = when {
                skipRate > 0.4 -> "$REASON_PREFIX_DEMOTED: frequently skipped"
                favorite -> "One of your favorites"
                signal.completions >= 2 -> "You often finish this song"
                unheard && artist > 0.1 -> "Discover more from an artist you enjoy"
                unheard && genre > 0.1 -> "Explore a genre you enjoy"
                unheard -> "Something you haven't played yet"
                fatigue > 0.8 -> "Played recently"
                else -> "Based on your listening history"
            }
            Pick(song, score, reason, unheard)
        }.sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.song.id })
    }

    private data class RecordingCandidate(val song: Song, val favorite: Boolean, val signal: Signal, val history: History)

    /**
     * DailyMixManager's ID normalization may place the same stored feedback object under
     * both numeric and spotify_ aliases. Count that object once, while retaining genuinely
     * separate session histories even when their counter values happen to be identical.
     */
    private fun <T : Any> List<T>.distinctInstances(): List<T> {
        if (size <= 1) return this
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
        return filter(seen::add)
    }

    private fun mergeSignals(values: List<Signal>) = values.singleOrNull() ?: Signal(
        sessions = sumCounts(values.map { it.sessions }),
        completions = sumCounts(values.map { it.completions }),
        earlySkips = sumCounts(values.map { it.earlySkips }),
        voluntaryPlays = sumCounts(values.map { it.voluntaryPlays }),
        lastPlayedMs = values.maxOfOrNull { it.lastPlayedMs } ?: 0L,
        listenedMs = values.sumOf { it.listenedMs.coerceAtLeast(0L) }
    )

    private fun mergeHistory(values: List<History>) = values.singleOrNull() ?: History(
        plays = sumCounts(values.map { it.plays }),
        listenedMs = values.sumOf { it.listenedMs.coerceAtLeast(0L) },
        lastPlayedMs = values.maxOfOrNull { it.lastPlayedMs } ?: 0L
    )

    private fun sumCounts(values: List<Int>): Int =
        values.sumOf { it.toLong().coerceAtLeast(0L) }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Interleave exploration and reduce consecutive artist/album repeats, including unknown artist IDs. */
    fun select(ranked: List<Pick>, limit: Int, explorationFraction: Float = 0.25f): List<Pick> {
        if (limit <= 0) return emptyList()
        val remaining = ranked.distinctBy { recordingKey(it.song) }.toMutableList()
        val selected = mutableListOf<Pick>()
        val counts = mutableMapOf<String, Int>()
        val target = minOf(limit, remaining.size)
        val discoveryTarget = (target * explorationFraction.coerceIn(0f, 0.6f)).toInt()
        var discoveries = 0
        repeat(target) { slot ->
            val needsDiscovery = discoveries < discoveryTarget &&
                (slot % 3 == 2 || target - slot <= discoveryTarget - discoveries)
            val pool = if (needsDiscovery) remaining.filter { it.unheard }.ifEmpty { remaining } else remaining
            val previous = selected.lastOrNull()?.song
            val next = pool.maxByOrNull { pick ->
                val artist = artistKey(pick.song)
                pick.score - (counts[artist] ?: 0) * 0.17 -
                    (if (previous != null && artist == artistKey(previous)) 0.3 else 0.0) -
                    (if (previous != null && pick.song.album.isNotBlank() &&
                        normalize(pick.song.album) == normalize(previous.album) && artist == artistKey(previous)) 0.1 else 0.0)
            } ?: return@repeat
            selected += next
            remaining.remove(next)
            counts.merge(artistKey(next.song), 1, Int::plus)
            if (next.unheard) discoveries++
        }
        return selected
    }

    fun artistKey(song: Song): String = normalize(song.artist).ifBlank { "unknown:${song.id}" }
    private fun genreKey(song: Song): String? = song.genre?.let(::normalize)?.takeUnless { it.isBlank() || it == "unknown" }
    fun recordingKey(song: Song): String = "${artistKey(song)}|${normalize(song.title)}"
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private const val DAY = 86_400_000L
}
