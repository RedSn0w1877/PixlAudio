package com.theveloper.pixelplay.data.history

/**
 * The small read API over everything known about the listener's listening: plays in this app plus the history
 * imported from Spotify (weight 1.0) and YouTube Music (weight 0.35). Meant for the AI assistant's context, the
 * home greeting and insight surfaces. Every call is a suspend read on IO; nothing here touches the network.
 *
 * Scores are weighted play counts within the period (an imported YouTube Music play adds 0.35, a Spotify or
 * PixlAudio play 1.0). Early skips (< 30 s) never count.
 */
interface ListeningInsights {
    suspend fun topArtists(period: InsightPeriod, limit: Int = 20): List<ArtistInsight>
    suspend fun topTracks(period: InsightPeriod, limit: Int = 20): List<TrackInsight>
    /** Newest first, across all sources. */
    suspend fun recentPlays(n: Int = 50): List<RecentPlay>
    suspend fun listeningSummary(): ListeningSummary
}

enum class InsightPeriod(val days: Long?) {
    LAST_4_WEEKS(28),
    LAST_6_MONTHS(182),
    LAST_YEAR(365),
    ALL_TIME(null);

    fun sinceMs(nowMs: Long): Long = days?.let { nowMs - it * 86_400_000L } ?: 0L
}

/** Where a play happened. */
enum class PlayOrigin(val label: String) {
    PIXLAUDIO("PixlAudio"), SPOTIFY("Spotify"), YOUTUBE_MUSIC("YouTube Music");

    companion object {
        fun of(source: HistorySource?): PlayOrigin = when {
            source == null -> PIXLAUDIO
            source.isSpotify -> SPOTIFY
            else -> YOUTUBE_MUSIC
        }
    }
}

data class ArtistInsight(
    val name: String,
    val score: Double,
    val plays: Int,
    val listenedMs: Long,
)

data class TrackInsight(
    val title: String,
    val artist: String,
    val score: Double,
    val plays: Int,
    val listenedMs: Long,
    /** The library song, when this track is in the library (playable directly). */
    val songId: String? = null,
    val spotifyTrackId: String? = null,
    val videoId: String? = null,
)

data class RecentPlay(
    val title: String,
    val artist: String,
    val album: String?,
    val playedAtMs: Long,
    val origin: PlayOrigin,
    /** How long it was played, when known. */
    val msPlayed: Long? = null,
    val songId: String? = null,
)

data class ListeningSummary(
    /** Plays recorded by this app. */
    val appPlays: Int,
    val importedPlays: Int,
    val spotifyPlays: Int,
    val youTubeMusicPlays: Int,
    val importedArtists: Int,
    val importedTracks: Int,
    val firstPlayedMs: Long?,
    val lastPlayedMs: Long?,
    /** Listening time known from imported files (the Web API and YouTube Music do not report it). */
    val importedListeningMs: Long,
    val lastSyncedMs: Long,
    /** Top artists over the last six months, strongest first. */
    val topArtists: List<String>,
    /** From Spotify's top and followed artists. */
    val topGenres: List<String>,
    val followedArtists: Int,
) {
    /** A compact plain-text description, ready to drop into an AI context block. */
    fun describe(): String = buildString {
        append("Plays in PixlAudio: ").append(appPlays)
        append(". Imported plays: ").append(importedPlays)
        append(" (Spotify ").append(spotifyPlays).append(", YouTube Music ").append(youTubeMusicPlays).append(")")
        if (importedArtists > 0) append(" across ").append(importedArtists).append(" artists")
        append('.')
        if (topArtists.isNotEmpty()) append(" Top artists lately: ").append(topArtists.joinToString(", ")).append('.')
        if (topGenres.isNotEmpty()) append(" Favourite genres: ").append(topGenres.joinToString(", ")).append('.')
        if (followedArtists > 0) append(" Follows ").append(followedArtists).append(" artists on Spotify.")
        if (importedListeningMs > 0) append(" Imported listening time: ").append(importedListeningMs / 3_600_000L).append(" hours.")
    }
}
