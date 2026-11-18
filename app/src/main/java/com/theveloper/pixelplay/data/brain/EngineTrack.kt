package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.PlayableEntity
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow

/**
 * A track from outside the library, in the form the player needs (docs/brain-spec.md, 7.2).
 *
 * It has no row in the main database. Its ids are the ones the normal importer would give it:
 * [synthId] is `SpotifyRepository.youTubeMusicSyntheticId(videoId)` and [songId] is
 * `SpotifyRepository.unifiedSongId(synthId)`, so when the listener saves the track, the queue item,
 * the history entry and the library row all already agree.
 */
data class EngineTrack(
    val synthId: String,
    val songId: Long,
    val rid: Long,
    val videoId: String,
    val title: String,
    /** Credited artists, primary first; never a joined display string. */
    val artists: List<RadioArtistRef>,
    val album: String?,
    val durationMs: Long,
    val artUrl: String?,
) {
    val artistDisplay: String get() = artists.joinToString(", ") { it.name }.ifBlank { UNKNOWN_ARTIST }

    val contentUri: String get() = CONTENT_URI_PREFIX + synthId

    fun toPlayable(nowMs: Long): PlayableEntity = PlayableEntity(
        synthId = synthId,
        songId = songId,
        rid = rid,
        videoId = videoId,
        title = title,
        artistDisplay = artistDisplay,
        album = album,
        durationMs = durationMs,
        artUrl = artUrl,
        lastUsedAt = nowMs,
    )

    /** The in-memory [Song] the queue, the player screen and the notification show. */
    fun toSong(): Song {
        val refs = artists.mapIndexed { index, artist ->
            ArtistRef(id = SpotifyRepository.unifiedArtistId(artist.name), name = artist.name, isPrimary = index == 0)
        }
        val display = artistDisplay
        val albumName = album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
        return Song(
            id = songId.toString(),
            title = title,
            artist = display,
            artistId = refs.firstOrNull()?.id ?: -1L,
            artists = refs,
            album = albumName,
            albumId = SpotifyRepository.unifiedAlbumIdByName(albumName, display),
            path = "",
            contentUriString = contentUri,
            albumArtUriString = artUrl,
            duration = durationMs,
            mimeType = null,
            bitrate = null,
            sampleRate = null,
            spotifyId = synthId,
        )
    }

    companion object {
        const val CONTENT_URI_PREFIX = "spotify://"
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"

        /** The synthetic id of a YouTube video: the id the normal importer gives the same track. */
        fun synthIdOf(videoId: String): String = SpotifyRepository.syntheticIdForVideo(videoId)

        fun from(row: RadioRow, rid: Long): EngineTrack {
            val synthId = synthIdOf(row.videoId)
            return EngineTrack(
                synthId = synthId,
                songId = SpotifyRepository.unifiedSongId(synthId),
                rid = rid,
                videoId = row.videoId,
                title = row.title,
                artists = row.artists,
                album = row.album,
                durationMs = row.durationSec * 1000L,
                artUrl = row.thumbnailUrl,
            )
        }

        /**
         * A [Song] rebuilt from a `playable` row, for Recently Played. The row keeps the artists as one
         * display string, and that string is never split again, so the song has no artist list.
         */
        fun songOf(row: PlayableEntity): Song = Song(
            id = row.songId.toString(),
            title = row.title,
            artist = row.artistDisplay,
            artistId = -1L,
            album = row.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
            albumId = -1L,
            path = "",
            contentUriString = CONTENT_URI_PREFIX + row.synthId,
            albumArtUriString = row.artUrl,
            duration = row.durationMs,
            mimeType = null,
            bitrate = null,
            sampleRate = null,
            spotifyId = row.synthId,
        )
    }
}
