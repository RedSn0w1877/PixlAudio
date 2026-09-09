package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable

/**
 * Una pista del catálogo de Spotify que **no está en la biblioteca**.
 *
 * Es un modelo propio y no el DTO de red, para que la UI no dependa de la forma de la
 * respuesta de Spotify. No lleva id de biblioteca porque todavía no existe allí: lo que
 * ocurre al tocarla es que se importa, y el id aparece entonces.
 */
@Immutable
data class CatalogTrack(
    val spotifyId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val durationMs: Long
)

/**
 * Una canción encontrada buscando directamente en YouTube Music, no en la biblioteca ni en
 * el catálogo de Spotify. Ya trae su propio `videoId` — a diferencia de [CatalogTrack], que
 * necesita que el emparejador le busque uno, esta ya es reproducible tal cual.
 */
@Immutable
data class YouTubeMusicTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationMs: Long
)

@Immutable
sealed interface SearchResultItem {
    data class SongItem(val song: Song) : SearchResultItem
    data class AlbumItem(val album: Album) : SearchResultItem
    data class ArtistItem(val artist: Artist) : SearchResultItem
    data class PlaylistItem(val playlist: Playlist) : SearchResultItem

    /** Resultado del catálogo, no de la biblioteca: se importa al tocarlo. */
    data class CatalogItem(val track: CatalogTrack) : SearchResultItem

    /** Resultado de buscar directamente en YouTube Music: se importa y suena al tocarlo. */
    data class YouTubeMusicItem(val track: YouTubeMusicTrack) : SearchResultItem
}
