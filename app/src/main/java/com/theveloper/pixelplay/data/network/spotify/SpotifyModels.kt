package com.theveloper.pixelplay.data.network.spotify

import com.google.gson.annotations.SerializedName

/**
 * DTOs de la Web API de Spotify.
 *
 * Solo se modela lo que la app usa. Todo lo opcional es nullable a propósito: la API
 * omite campos según el tipo de pista (los ficheros locales de un usuario no traen `id`
 * ni `external_ids`, y una pista retirada del catálogo llega como `track: null`).
 */

// ─── Autenticación ─────────────────────────────────────────────────────

data class SpotifyTokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    /**
     * Bajo PKCE Spotify devuelve un refresh token **nuevo** en cada refresco.
     * Hay que guardarlo siempre; si se ignora, la cuenta deja de funcionar tras la
     * primera rotación.
     */
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("scope") val scope: String?
)

// ─── Perfil ────────────────────────────────────────────────────────────

data class SpotifyUserProfile(
    @SerializedName("id") val id: String?,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("product") val product: String?,
    @SerializedName("images") val images: List<SpotifyImage>? = null
)

data class SpotifyImage(
    @SerializedName("url") val url: String?,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null
)

// ─── Pistas ────────────────────────────────────────────────────────────

data class SpotifyArtistRef(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?
)

data class SpotifyAlbumRef(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("images") val images: List<SpotifyImage>? = null,
    @SerializedName("release_date") val releaseDate: String? = null
)

data class SpotifyExternalIds(
    @SerializedName("isrc") val isrc: String? = null
)

data class SpotifyTrack(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("duration_ms") val durationMs: Long? = null,
    @SerializedName("artists") val artists: List<SpotifyArtistRef>? = null,
    @SerializedName("album") val album: SpotifyAlbumRef? = null,
    @SerializedName("external_ids") val externalIds: SpotifyExternalIds? = null,
    @SerializedName("is_local") val isLocal: Boolean? = null,
    @SerializedName("type") val type: String? = null
)

/**
 * Un elemento de `/v1/me/tracks` o `/v1/playlists/{id}/items`.
 *
 * `/v1/playlists/{id}/items` (the replacement for the removed `/tracks` endpoint) renamed
 * this field to `item`; `track` is kept as a fallback since `/v1/me/tracks` still uses it.
 */
data class SpotifyPlaylistTrackItem(
    @SerializedName("added_at") val addedAt: String? = null,
    @SerializedName("item") val item: SpotifyTrack? = null,
    @SerializedName("track") val track: SpotifyTrack? = null
) {
    val resolvedTrack: SpotifyTrack? get() = item ?: track
}

data class SpotifyTracksPage(
    @SerializedName("items") val items: List<SpotifyPlaylistTrackItem>? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("limit") val limit: Int? = null,
    @SerializedName("offset") val offset: Int? = null,
    @SerializedName("next") val next: String? = null
)

// ─── Playlists ─────────────────────────────────────────────────────────

data class SpotifyPlaylistTracksRef(
    @SerializedName("total") val total: Int? = null
)

data class SpotifyPlaylistOwner(
    @SerializedName("id") val id: String? = null,
    @SerializedName("display_name") val displayName: String? = null
)

data class SpotifyPlaylist(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("images") val images: List<SpotifyImage>? = null,
    @SerializedName("tracks") val tracks: SpotifyPlaylistTracksRef? = null,
    @SerializedName("owner") val owner: SpotifyPlaylistOwner? = null,
    @SerializedName("public") val isPublic: Boolean? = null,
    @SerializedName("collaborative") val collaborative: Boolean? = null
)

data class SpotifyPlaylistsPage(
    @SerializedName("items") val items: List<SpotifyPlaylist>? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("limit") val limit: Int? = null,
    @SerializedName("offset") val offset: Int? = null,
    @SerializedName("next") val next: String? = null
)

// ─── Catálogo: artistas y álbumes ──────────────────────────────────────

/** Artista completo (el de `/v1/artists`, con imagen y géneros — el de una pista no los trae). */
data class SpotifyArtistFull(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("images") val images: List<SpotifyImage>? = null,
    @SerializedName("genres") val genres: List<String>? = null,
    @SerializedName("popularity") val popularity: Int? = null,
    @SerializedName("followers") val followers: SpotifyFollowers? = null
)

data class SpotifyFollowers(
    @SerializedName("total") val total: Long? = null
)

/** Response of `GET /v1/artists?ids=...` (batch, up to 50). Entries for unknown/removed ids come back null. */
data class SpotifyArtistsBatchResponse(
    @SerializedName("artists") val artists: List<SpotifyArtistFull?>? = null
)

/**
 * Álbum de catálogo. `album_group` distingue disco propio de recopilatorio o colaboración,
 * que es lo que evita llenar la ficha del artista de discos que en realidad no son suyos.
 */
data class SpotifyAlbumFull(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("images") val images: List<SpotifyImage>? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("total_tracks") val totalTracks: Int? = null,
    @SerializedName("album_type") val albumType: String? = null,
    @SerializedName("album_group") val albumGroup: String? = null,
    @SerializedName("artists") val artists: List<SpotifyArtistRef>? = null
)

data class SpotifyArtistsPage(
    @SerializedName("items") val items: List<SpotifyArtistFull>? = null,
    @SerializedName("next") val next: String? = null
)

data class SpotifyAlbumsPage(
    @SerializedName("items") val items: List<SpotifyAlbumFull>? = null,
    @SerializedName("next") val next: String? = null
)

data class SpotifyTracksListPage(
    @SerializedName("items") val items: List<SpotifyTrack>? = null,
    @SerializedName("next") val next: String? = null
)

data class SpotifyTopTracksResponse(
    @SerializedName("tracks") val tracks: List<SpotifyTrack>? = null
)

/** Respuesta de `/v1/search`; solo llegan las secciones que se hayan pedido en `type`. */
data class SpotifySearchResponse(
    @SerializedName("tracks") val tracks: SpotifyTracksListPage? = null,
    @SerializedName("artists") val artists: SpotifyArtistsPage? = null,
    @SerializedName("albums") val albums: SpotifyAlbumsPage? = null
)
