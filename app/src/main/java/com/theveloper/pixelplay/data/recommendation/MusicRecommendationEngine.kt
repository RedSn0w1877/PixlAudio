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

    /**
     * @param artistPriors normalised artist name → affinity in -1..1 learned from listening history imported
     *   from Spotify / YouTube Music (see `data.history`). It covers artists with no song in the library too,
     *   which is what lets discoveries from an artist the listener plays elsewhere rank up. Empty = no effect.
     */
    fun rank(
        songs: List<Song>, favorites: Set<String>, signals: Map<String, Signal>,
        history: Map<String, History>, nowMs: Long, seed: Long,
        artistPriors: Map<String, Double> = emptyMap()
    ): List<Pick> {
        val candidates = songs.filter { it.title.isNotBlank() }.distinctBy { it.id }
            .groupBy(::recordingKey).entries.map { (recordingKey, versions) ->
                val first = versions.first()
                RecordingCandidate(
                    song = first,
                    favorite = versions.any { it.id in favorites || it.isFavorite },
                    signal = mergeSignals(versions.mapNotNull { signals[it.id] }.distinctInstances()),
                    history = mergeHistory(versions.mapNotNull { history[it.id] }.distinctInstances()),
                    // The group key IS recordingKey(first): every version shares it. Kept so the
                    // artist/genre/recording strings are normalised once per song, not 4-5 times.
                    recordingKey = recordingKey,
                    artistKey = artistKey(first),
                    genreKey = genreKey(first)
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
            artistWeights.merge(candidate.artistKey, weight, Double::plus)
            candidate.genreKey?.let { genreWeights.merge(it, weight, Double::plus) }
        }
        if (artistPriors.isNotEmpty()) {
            // Once per artist (not per song), so a big discography doesn't multiply the imported affinity.
            val seenArtists = HashSet<String>()
            for (candidate in candidates) {
                if (!seenArtists.add(candidate.artistKey)) continue
                val prior = artistPrior(candidate.song, artistPriors)
                if (prior != 0.0) artistWeights.merge(candidate.artistKey, prior * ARTIST_PRIOR_SCALE, Double::plus)
            }
        }
        val artistScale = artistWeights.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val genreScale = genreWeights.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val playScale = ln(1.0 + (candidates.maxOfOrNull { it.history.plays } ?: 1).coerceAtLeast(1))
        return candidates.map { candidate ->
            val song = candidate.song
            val signal = candidate.signal
            val past = candidate.history
            val favorite = candidate.favorite
            val artist = (artistWeights[candidate.artistKey] ?: 0.0) / artistScale
            val genre = (genreWeights[candidate.genreKey] ?: 0.0) / genreScale
            val taste = artist * 0.75 + genre * 0.25
            val satisfaction = (signal.completions + 2.0) / (signal.sessions + 4.0)
            val skipRate = signal.earlySkips.toDouble() / (signal.sessions + 2.0)
            val familiarity = ln(1.0 + past.plays.coerceAtLeast(0)) / playScale
            val lastPlayed = maxOf(signal.lastPlayedMs, past.lastPlayedMs)
            val hoursAgo = if (lastPlayed == 0L) 1_000.0 else (nowMs - lastPlayed).coerceAtLeast(0) / 3_600_000.0
            val fatigue = (1.0 - hoursAgo / 24.0).coerceIn(0.0, 1.0)
            val unheard = past.plays == 0 && signal.sessions == 0
            val exploration = 1.0 / sqrt(1.0 + past.plays.coerceAtLeast(0) + signal.sessions)
            val jitter = java.util.Random(seed xor candidate.recordingKey.hashCode().toLong()).nextDouble() * 0.025
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

    private data class RecordingCandidate(
        val song: Song, val favorite: Boolean, val signal: Signal, val history: History,
        val recordingKey: String, val artistKey: String, val genreKey: String?
    )

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

    /** A ranked pick with the strings [select] compares, normalised once instead of once per slot. */
    private class SelectCandidate(val pick: Pick, val artist: String, val albumKey: String?)

    /** Interleave exploration and reduce consecutive artist/album repeats, including unknown artist IDs. */
    fun select(ranked: List<Pick>, limit: Int, explorationFraction: Float = 0.25f): List<Pick> {
        if (limit <= 0) return emptyList()
        // distinctBy(recordingKey), keeping the first of each recording, with the keys kept for the loop below.
        val remaining = ArrayList<SelectCandidate>(ranked.size)
        val seenRecordings = HashSet<String>(ranked.size * 2)
        for (pick in ranked) {
            val song = pick.song
            val artist = artistKey(song)
            if (!seenRecordings.add("$artist|${normalize(song.title)}")) continue
            remaining += SelectCandidate(pick, artist, if (song.album.isNotBlank()) normalize(song.album) else null)
        }
        val selected = ArrayList<SelectCandidate>()
        val counts = mutableMapOf<String, Int>()
        val target = minOf(limit, remaining.size)
        val discoveryTarget = (target * explorationFraction.coerceIn(0f, 0.6f)).toInt()
        var discoveries = 0
        repeat(target) { slot ->
            val needsDiscovery = discoveries < discoveryTarget &&
                (slot % 3 == 2 || target - slot <= discoveryTarget - discoveries)
            val pool = if (needsDiscovery) remaining.filter { it.pick.unheard }.ifEmpty { remaining } else remaining
            val previous = selected.lastOrNull()
            val previousArtist = previous?.artist
            val previousAlbum = previous?.let { normalize(it.pick.song.album) }
            val next = pool.maxByOrNull { candidate ->
                val artist = candidate.artist
                candidate.pick.score - (counts[artist] ?: 0) * 0.17 -
                    (if (previous != null && artist == previousArtist) 0.3 else 0.0) -
                    (if (previous != null && candidate.albumKey != null &&
                        candidate.albumKey == previousAlbum && artist == previousArtist) 0.1 else 0.0)
            } ?: return@repeat
            selected += next
            // Recordings are unique here, so the first match is `next` itself (identity equality).
            remaining.remove(next)
            counts.merge(next.artist, 1, Int::plus)
            if (next.pick.unheard) discoveries++
        }
        return selected.map { it.pick }
    }

    /**
     * The imported affinity of a song's artists. Library rows join Spotify's credits with ", ": the strongest
     * positive credit wins; with only negative ones, the most negative.
     */
    private fun artistPrior(song: Song, priors: Map<String, Double>): Double {
        var best = 0.0
        var worst = 0.0
        for (part in song.artist.split(',')) {
            val value = priors[normalize(part.trim().removeSuffix(" - Topic"))] ?: continue
            if (value > best) best = value
            if (value < worst) worst = value
        }
        return if (best > 0.0) best else worst
    }

    /** An imported artist affinity of 1.0 weighs like a favorite plus a few finished plays (see the -4..6 clamp). */
    private const val ARTIST_PRIOR_SCALE = 3.0

    fun artistKey(song: Song): String = normalize(song.artist).ifBlank { "unknown:${song.id}" }
    private fun genreKey(song: Song): String? = song.genre?.let(::normalize)?.takeUnless { it.isBlank() || it == "unknown" }
    fun recordingKey(song: Song): String = "${artistKey(song)}|${normalize(song.title)}"
    private val WHITESPACE_RUN = Regex("\\s+")

    private fun normalize(value: String): String {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        // Replacing `\s+` with " " does nothing unless a whitespace run is not already a single
        // plain space, so most names skip the regex entirely (it used to be compiled again on
        // every single call).
        val collapsed = if (needsWhitespaceCollapse(folded)) folded.replace(WHITESPACE_RUN, " ") else folded
        return collapsed.trim()
    }

    /** True if [value] has a whitespace run (ASCII `\s` only, like the regex) that is not one plain space. */
    private fun needsWhitespaceCollapse(value: String): Boolean {
        var previousWasWhitespace = false
        for (c in value) {
            val whitespace = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
            if (whitespace && (c != ' ' || previousWasWhitespace)) return true
            previousWasWhitespace = whitespace
        }
        return false
    }
    private const val DAY = 86_400_000L
}
