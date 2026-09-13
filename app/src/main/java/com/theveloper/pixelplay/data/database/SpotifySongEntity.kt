package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.model.Song

/** Estado del emparejamiento entre una pista de Spotify y un vídeo de YouTube Music. */
object SpotifyMatchState {
    /** Aún no se ha intentado emparejar. */
    const val PENDING = 0
    /** Emparejada: [SpotifySongEntity.matchedVideoId] es reproducible. */
    const val MATCHED = 1
    /** Se buscó y no hubo ningún candidato aceptable. */
    const val UNMATCHED = 2
    /** El usuario eligió el vídeo a mano; no volver a emparejar automáticamente. */
    const val MANUAL = 3
}

/**
 * Una pista importada de Spotify.
 *
 * Spotify solo entrega metadatos — nunca audio — así que estas filas no son reproducibles
 * por sí solas. Las tres últimas columnas guardan el resultado de buscar la pista en
 * YouTube Music, que es de donde sale el audio.
 */
@Entity(
    tableName = "spotify_songs",
    indices = [
        Index(value = ["spotify_id"], unique = false),
        Index(value = ["playlist_id"], unique = false),
        Index(value = ["isrc"], unique = false),
        Index(value = ["match_state"], unique = false)
    ]
)
data class SpotifySongEntity(
    /** `<playlistId>_<spotifyId>`: la misma pista puede estar en varias playlists. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "spotify_id") val spotifyId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
    /**
     * International Standard Recording Code. Es la clave de emparejamiento más fiable
     * que da Spotify — identifica la grabación concreta, no solo el título.
     */
    @ColumnInfo(name = "isrc") val isrc: String?,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "matched_video_id") val matchedVideoId: String? = null,
    @ColumnInfo(name = "match_score") val matchScore: Float? = null,
    @ColumnInfo(name = "match_state", defaultValue = "0")
    val matchState: Int = SpotifyMatchState.PENDING,
    /**
     * The primary artist's first listed genre, backfilled via a batch `/v1/artists` lookup —
     * Spotify's track object never carries genre itself, only the full artist object does.
     * Null when that lookup hasn't run yet or came back empty for every artist on the track.
     */
    @ColumnInfo(name = "genre")
    val genre: String? = null
)

fun SpotifySongEntity.toSong(): Song = Song(
    id = "spotify_$spotifyId",
    title = title,
    artist = artist,
    artistId = -1L,
    album = album,
    albumId = -1L,
    path = "",
    contentUriString = "spotify://$spotifyId",
    albumArtUriString = albumArtUrl,
    duration = durationMs,
    mimeType = null,
    bitrate = null,
    sampleRate = null,
    dateAdded = dateAdded,
    spotifyId = spotifyId
)
