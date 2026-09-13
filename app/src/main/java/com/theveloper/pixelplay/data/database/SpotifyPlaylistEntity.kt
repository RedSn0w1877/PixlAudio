package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una playlist de Spotify del usuario.
 *
 * El id de "Me gusta" no viene de la API — Spotify la expone en un endpoint aparte
 * (`/v1/me/tracks`), así que la guardamos con el id sintético [LIKED_SONGS_ID].
 */
@Entity(tableName = "spotify_playlists")
data class SpotifyPlaylistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "song_count") val songCount: Int,
    @ColumnInfo(name = "last_sync_time") val lastSyncTime: Long
) {
    companion object {
        const val LIKED_SONGS_ID = "spotify_liked_songs"

        /** Cajón donde caen las pistas que el usuario añade explorando el catálogo. */
        const val BROWSE_ID = "spotify_browse"
    }
}
