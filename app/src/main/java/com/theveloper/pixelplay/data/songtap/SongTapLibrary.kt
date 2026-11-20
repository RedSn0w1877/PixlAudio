package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a beamed song needs from the library, as small calls the receiver's rules can be tested against.
 * [SpotifySongTapLibrary] is the real one: the same importer the Search screen's Save uses.
 */
interface SongTapLibrary {
    /** A song with this video is already in the library, under whatever row or id it came in. */
    suspend fun hasVideo(videoId: String): Boolean

    /** A library row with this Spotify id exists (synced from the listener's own Spotify, or imported). */
    suspend fun hasSpotifyId(spotifyId: String): Boolean

    /** The video for a song that came without one: the app's own title, artist and duration search and scoring. Null when nothing matches. */
    suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?): String?

    /** Puts the song in the library; true when it was not there before. */
    suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?): Boolean

    /** Takes back what [import] added; false when it cannot be taken back (the song has since joined a playlist). */
    suspend fun remove(videoId: String): Boolean

    /** The library's id for the song with this video, so the card can play it. */
    suspend fun songId(videoId: String): String

    /** The library's id for the song with this Spotify id. */
    suspend fun songIdForSpotify(spotifyId: String): String
}

@Singleton
class SpotifySongTapLibrary @Inject constructor(
    private val spotifyRepository: SpotifyRepository,
    private val spotifyDao: SpotifyDao,
    private val trackMatcher: TrackMatcher,
) : SongTapLibrary {

    override suspend fun hasVideo(videoId: String): Boolean =
        spotifyDao.getSongBySpotifyId(spotifyRepository.youTubeMusicSyntheticId(videoId)) != null ||
            spotifyDao.getSpotifyIdForVideo(videoId) != null

    override suspend fun hasSpotifyId(spotifyId: String): Boolean = spotifyDao.getSongBySpotifyId(spotifyId) != null

    override suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?): String? {
        val probe = SpotifySongEntity(
            id = "songtap_probe",
            spotifyId = spotifyId ?: "songtap_probe",
            playlistId = "songtap_probe",
            title = title,
            artist = artist,
            album = "",
            albumId = null,
            durationMs = durationMs,
            albumArtUrl = null,
            isrc = isrc,
            dateAdded = 0L,
            matchState = SpotifyMatchState.PENDING,
        )
        return trackMatcher.findMatchFanOut(probe)?.videoId
    }

    override suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?): Boolean {
        val added = spotifyRepository.importYouTubeMusicTracks(
            listOf(
                YouTubeSearchResult(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = null,
                    durationSeconds = (durationMs / 1000L).toInt().takeIf { it > 0 },
                    thumbnailUrl = artUrl,
                )
            )
        )
        return added > 0
    }

    override suspend fun remove(videoId: String): Boolean =
        spotifyRepository.removeFromExploredCatalog(spotifyRepository.youTubeMusicSyntheticId(videoId))

    override suspend fun songId(videoId: String): String {
        // A song already synced from the listener's Spotify sits under its real id; one imported from a video under the made-up one.
        val key = spotifyDao.getSpotifyIdForVideo(videoId) ?: spotifyRepository.youTubeMusicSyntheticId(videoId)
        return SpotifyRepository.unifiedSongId(key).toString()
    }

    override suspend fun songIdForSpotify(spotifyId: String): String = SpotifyRepository.unifiedSongId(spotifyId).toString()
}
