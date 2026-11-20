package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import javax.inject.Inject
import javax.inject.Singleton

/** The ids the sender's library knows for a song, behind a small interface so the rules can be tested. */
interface SongTapSongIds {
    suspend fun videoIdFor(spotifyId: String): String?
    suspend fun isrcFor(spotifyId: String): String?
}

@Singleton
class LibrarySongTapSongIds @Inject constructor(
    private val spotifyDao: SpotifyDao,
    private val playableStore: PlayableStore,
) : SongTapSongIds {
    override suspend fun videoIdFor(spotifyId: String): String? =
        spotifyDao.getMatchedVideoId(spotifyId)?.takeIf { it.isNotBlank() } ?: playableStore.videoIdFor(spotifyId)

    override suspend fun isrcFor(spotifyId: String): String? =
        spotifyDao.getIsrcs(listOf(spotifyId)).firstOrNull()?.isrc?.takeIf { it.isNotBlank() }
}

/**
 * Builds the payload for the song that is playing. The video id is the one the song plays from; the Spotify
 * id goes along only when it is a real one (a song saved from YouTube has a made-up id that means nothing
 * to anyone else). No account, no device and no listener name is ever read here.
 */
@Singleton
class SongTapSender @Inject constructor(
    private val ids: SongTapSongIds,
) {
    suspend fun payloadFor(song: Song, nowMs: Long): SongTapPayload? {
        val own = song.spotifyId ?: return null
        val video = ids.videoIdFor(own)
        val real = own.takeIf { video == null || SpotifyRepository.syntheticIdForVideo(video) != it }
        return SongTapPayloads.make(
            nowMs = nowMs,
            ytVideoId = video,
            spotifyId = real,
            isrc = if (real != null) ids.isrcFor(real) else null,
            title = song.title,
            artist = song.artist,
            durationMs = song.duration,
            artUrl = song.albumArtUriString,
        )
    }
}
