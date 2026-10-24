package com.theveloper.pixelplay.data.history

import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A library song as the matcher needs it. */
data class LibraryTrack(val songId: String, val spotifyId: String?, val title: String, val artist: String)

/**
 * Finds the library song an imported play belongs to: by Spotify track id first, then by YouTube video id
 * (a matched video, or a song saved straight from YouTube Music), then by primary artist + cleaned title.
 */
class LibraryIndex private constructor(
    private val bySpotifyId: Map<String, String>,
    private val byVideoId: Map<String, String>,
    private val byRecording: Map<String, String>,
) {
    val isEmpty: Boolean get() = bySpotifyId.isEmpty() && byRecording.isEmpty()

    fun songIdFor(spotifyTrackId: String?, videoId: String?, artistName: String, trackName: String): String? {
        if (!spotifyTrackId.isNullOrBlank()) bySpotifyId[spotifyTrackId]?.let { return it }
        if (!videoId.isNullOrBlank()) {
            byVideoId[videoId]?.let { return it }
            bySpotifyId[youTubeMusicSyntheticId(videoId)]?.let { return it }
        }
        return byRecording[HistoryText.recordingKey(artistName, trackName)]
    }

    companion object {
        val EMPTY = LibraryIndex(emptyMap(), emptyMap(), emptyMap())

        /** [videoToSpotifyId]: the YouTube video each Spotify track was matched to. */
        fun build(tracks: List<LibraryTrack>, videoToSpotifyId: Map<String, String> = emptyMap()): LibraryIndex {
            val bySpotifyId = HashMap<String, String>(tracks.size * 2)
            val byRecording = HashMap<String, String>(tracks.size * 2)
            for (track in tracks) {
                track.spotifyId?.takeIf { it.isNotBlank() }?.let { bySpotifyId.putIfAbsent(it, track.songId) }
                val title = HistoryText.cleanTitle(track.title)
                if (title.isEmpty()) continue
                for (artist in HistoryText.splitArtists(track.artist)) {
                    byRecording.putIfAbsent("$artist|$title", track.songId)
                }
            }
            val byVideoId = HashMap<String, String>(videoToSpotifyId.size * 2)
            for ((videoId, spotifyId) in videoToSpotifyId) {
                bySpotifyId[spotifyId]?.let { byVideoId[videoId] = it }
            }
            return LibraryIndex(bySpotifyId, byVideoId, byRecording)
        }

        /** Same as `SpotifyRepository.youTubeMusicSyntheticId`: songs saved from YouTube Music live under this id. */
        fun youTubeMusicSyntheticId(videoId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(22)
        }
    }
}

/** One stored play, as the taste builder reads it. */
data class HistoryRow(
    val trackKey: String,
    val artistKey: String,
    val trackName: String,
    val artistName: String,
    val spotifyTrackId: String?,
    val videoId: String?,
    val playedAtMs: Long,
    val msPlayed: Long?,
    val outcome: PlayOutcome,
    /** The source weight ([ListeningHistoryWeights.SPOTIFY] or [ListeningHistoryWeights.YOUTUBE_MUSIC]). */
    val weight: Double,
    val qualified: Boolean,
    val voluntary: Boolean,
)

enum class SeedKind(val key: String) {
    TOP_ARTIST("top_artist"), TOP_TRACK("top_track"), FOLLOWED_ARTIST("followed_artist");

    companion object {
        fun fromKey(key: String): SeedKind? = entries.firstOrNull { it.key == key }
    }
}

/** Spotify's own view of the listener: top artists/tracks per time range and followed artists. */
data class TasteSeed(
    val kind: SeedKind,
    /** `short_term`, `medium_term`, `long_term`, or "" for followed artists. */
    val timeRange: String,
    /** 0-based rank in its list. */
    val position: Int,
    val listSize: Int,
    val artistName: String,
    val trackName: String? = null,
    val spotifyId: String? = null,
)

/** What imported history adds to the recommendation inputs, keyed by library song id. */
data class ImportedTaste(
    val signals: Map<String, MusicRecommendationEngine.Signal> = emptyMap(),
    val history: Map<String, MusicRecommendationEngine.History> = emptyMap(),
    /** Normalised artist name → affinity in -1..1 (the strongest imported artist is 1.0). Includes unmapped plays. */
    val artistPriors: Map<String, Double> = emptyMap(),
    val mappedPlays: Int = 0,
    val unmappedPlays: Int = 0,
)

/**
 * Folds imported plays into the learner's inputs.
 *
 * Every play is weighted by its source (Spotify 1.0, YouTube Music 0.35) and by its age (half-life
 * [ListeningHistoryWeights.HALF_LIFE_DAYS]). Plays that map to a library song add sessions, completions, early
 * skips and play counts to that song; every play — mapped or not — moves its artist's affinity, so music the
 * listener plays elsewhere but has not saved still shapes discovery.
 */
class ImportedTasteBuilder(private val index: LibraryIndex, private val nowMs: Long) {

    private class SongAccumulator {
        var sessions = 0.0
        var completions = 0.0
        var skips = 0.0
        var voluntary = 0.0
        var plays = 0.0
        var listenedMs = 0.0
        var lastPlayedMs = 0L
    }

    private val songs = HashMap<String, SongAccumulator>()
    private val artists = HashMap<String, Double>()
    private val songIdByTrack = HashMap<String, String?>()
    private var mapped = 0
    private var unmapped = 0

    fun add(row: HistoryRow) {
        val w = row.weight * ListeningHistoryWeights.decay(row.playedAtMs, nowMs)
        if (w <= 0.0) return
        if (row.artistKey.isNotEmpty()) {
            val delta = when (row.outcome) {
                PlayOutcome.SKIPPED -> -SKIP_PENALTY * w
                PlayOutcome.COMPLETED -> COMPLETION_BONUS * w
                else -> if (row.qualified) w else 0.0
            }
            if (delta != 0.0) artists.merge(row.artistKey, delta, Double::plus)
        }
        val songId = songIdFor(row.trackKey) { index.songIdFor(row.spotifyTrackId, row.videoId, row.artistName, row.trackName) }
        if (songId == null) {
            unmapped++
            return
        }
        mapped++
        val acc = songs.getOrPut(songId) { SongAccumulator() }
        acc.sessions += w
        if (row.outcome == PlayOutcome.COMPLETED) acc.completions += w
        if (row.outcome == PlayOutcome.SKIPPED) acc.skips += w
        if (row.voluntary) acc.voluntary += w
        if (row.qualified) acc.plays += w
        acc.listenedMs += (row.msPlayed ?: 0L).coerceAtLeast(0L) * w
        if (row.playedAtMs > acc.lastPlayedMs) acc.lastPlayedMs = row.playedAtMs
    }

    fun addSeed(seed: TasteSeed) {
        val rankFactor = 1.0 - 0.7 * (seed.position.toDouble() / seed.listSize.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val rangeWeight = when (seed.timeRange) {
            "short_term" -> 1.0
            "medium_term" -> 0.8
            "long_term" -> 0.6
            else -> 0.7
        }
        val artistKey = HistoryText.artistKey(seed.artistName)
        when (seed.kind) {
            SeedKind.TOP_ARTIST -> if (artistKey.isNotEmpty()) {
                artists.merge(artistKey, TOP_ARTIST_PLAYS * rangeWeight * rankFactor, Double::plus)
            }
            SeedKind.FOLLOWED_ARTIST -> if (artistKey.isNotEmpty()) {
                artists.merge(artistKey, FOLLOWED_ARTIST_PLAYS, Double::plus)
            }
            SeedKind.TOP_TRACK -> {
                if (artistKey.isNotEmpty()) artists.merge(artistKey, TOP_TRACK_ARTIST_PLAYS * rangeWeight * rankFactor, Double::plus)
                val title = seed.trackName ?: return
                val songId = index.songIdFor(seed.spotifyId, null, seed.artistName, title) ?: return
                songs.getOrPut(songId) { SongAccumulator() }.plays += TOP_TRACK_PLAYS * rangeWeight * rankFactor
            }
        }
    }

    private inline fun songIdFor(trackKey: String, lookup: () -> String?): String? {
        if (songIdByTrack.containsKey(trackKey)) return songIdByTrack[trackKey]
        val found = lookup()
        songIdByTrack[trackKey] = found
        return found
    }

    fun build(): ImportedTaste {
        val signals = HashMap<String, MusicRecommendationEngine.Signal>()
        val history = HashMap<String, MusicRecommendationEngine.History>()
        for ((songId, acc) in songs) {
            val sessions = acc.sessions.roundToInt()
            if (sessions > 0) {
                signals[songId] = MusicRecommendationEngine.Signal(
                    sessions = sessions,
                    completions = acc.completions.roundToInt().coerceIn(0, sessions),
                    earlySkips = acc.skips.roundToInt().coerceIn(0, sessions),
                    voluntaryPlays = acc.voluntary.roundToInt().coerceIn(0, sessions),
                    lastPlayedMs = acc.lastPlayedMs,
                    listenedMs = acc.listenedMs.roundToLong(),
                )
            }
            val plays = acc.plays.roundToInt()
            if (plays > 0 || acc.lastPlayedMs > 0L) {
                history[songId] = MusicRecommendationEngine.History(
                    plays = plays.coerceAtLeast(0),
                    listenedMs = acc.listenedMs.roundToLong(),
                    lastPlayedMs = acc.lastPlayedMs,
                )
            }
        }
        val maxAbs = artists.values.maxOfOrNull { abs(it) } ?: 0.0
        val priors = if (maxAbs <= 0.0) emptyMap() else {
            artists.entries
                .sortedByDescending { abs(it.value) }
                .take(MAX_ARTIST_PRIORS)
                .map { it.key to it.value / maxAbs }
                .filter { abs(it.second) >= MIN_PRIOR }
                .toMap()
        }
        return ImportedTaste(signals, history, priors, mapped, unmapped)
    }

    companion object {
        /** A completed play says a bit more about the artist than a partial one. */
        const val COMPLETION_BONUS = 1.2
        /** An early skip counts against the artist, by a quarter of a play. */
        const val SKIP_PENALTY = 0.25
        /** Spotify's top artists, in plays: a #1 short-term artist is worth ~30 recent plays. */
        const val TOP_ARTIST_PLAYS = 30.0
        const val FOLLOWED_ARTIST_PLAYS = 8.0
        const val TOP_TRACK_ARTIST_PLAYS = 6.0
        const val TOP_TRACK_PLAYS = 4.0
        const val MAX_ARTIST_PRIORS = 2_000
        const val MIN_PRIOR = 0.01
    }
}
