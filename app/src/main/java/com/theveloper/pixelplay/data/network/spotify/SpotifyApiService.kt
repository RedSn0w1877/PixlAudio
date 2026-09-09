package com.theveloper.pixelplay.data.network.spotify

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoints de `accounts.spotify.com`. Bajo PKCE no hay client secret, así que estas
 * llamadas son seguras desde la app.
 */
interface SpotifyAuthApiService {

    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeCode(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("client_id") clientId: String,
        @Field("code_verifier") codeVerifier: String
    ): Response<SpotifyTokenResponse>

    @FormUrlEncoded
    @POST("api/token")
    suspend fun refreshToken(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String
    ): Response<SpotifyTokenResponse>
}

/**
 * Endpoints de `api.spotify.com`.
 *
 * El token va explícito en cada llamada en vez de por interceptor para que el
 * repositorio pueda refrescarlo justo antes y reintentar una sola vez ante un 401.
 */
interface SpotifyApiService {

    @GET("v1/me")
    suspend fun getProfile(
        @Header("Authorization") authorization: String
    ): Response<SpotifyUserProfile>

    /** Canciones "Me gusta". El máximo por página es 50. */
    @GET("v1/me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyTracksPage>

    @GET("v1/me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyPlaylistsPage>

    /**
     * `fields` recorta la respuesta a lo que se guarda; sin él Spotify manda cientos de
     * kilobytes de mercados disponibles y enlaces que no se usan.
     *
     * Se puede pasar null para pedir la respuesta entera: Retrofit omite los `@Query`
     * nulos. Es la vía de escape cuando el filtro es lo que está haciendo fallar la
     * llamada — traerse de más es infinitamente mejor que no traerse nada.
     *
     * `/items`, no `/tracks`: Spotify's Feb/Mar 2026 Web API migration retired
     * `GET /playlists/{id}/tracks` — it now returns 403 for every Development Mode app
     * regardless of scope. `/items` is the direct replacement with an identical response
     * shape (same `items`/`next`/`total` fields, same nested `track` object per row).
     */
    @GET("v1/playlists/{playlistId}/items")
    suspend fun getPlaylistTracks(
        @Header("Authorization") authorization: String,
        @Path("playlistId") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String? = PLAYLIST_TRACK_FIELDS
    ): Response<SpotifyTracksPage>

    // ─── Catálogo ──────────────────────────────────────────────────────

    /**
     * @param type lista separada por comas: `track`, `artist`, `album`.
     * @param limit resultados **por cada tipo**. Spotify acota el total de la respuesta, de
     *   modo que el límite válido depende de cuántos tipos se pidan: 20 con tres tipos son
     *   60 resultados y devuelve `400 Invalid limit`. Quien llame debe ajustarlo al número
     *   de tipos (ver `SpotifyRepository.searchCatalog`).
     */
    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "track,artist,album",
        // Nulos: Retrofit omite el parámetro por completo, que es como se prueba una
        // petición mínima cuando Spotify rechaza la completa.
        @Query("limit") limit: Int? = null,
        @Query("market") market: String? = null,
        @Query("offset") offset: Int? = null
    ): Response<SpotifySearchResponse>

    @GET("v1/artists/{artistId}")
    suspend fun getArtist(
        @Header("Authorization") authorization: String,
        @Path("artistId") artistId: String
    ): Response<SpotifyArtistFull>

    /** Batch lookup, up to 50 comma-separated ids — backfills genre for imported tracks, whose own object never carries one. */
    @GET("v1/artists")
    suspend fun getArtists(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String
    ): Response<SpotifyArtistsBatchResponse>

    /**
     * Las canciones más escuchadas del artista.
     *
     * `market` es obligatorio; `from_token` usa el país de la cuenta que ha iniciado sesión.
     */
    @GET("v1/artists/{artistId}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") authorization: String,
        @Path("artistId") artistId: String,
        @Query("market") market: String = "from_token"
    ): Response<SpotifyTopTracksResponse>

    @GET("v1/artists/{artistId}/albums")
    suspend fun getArtistAlbums(
        @Header("Authorization") authorization: String,
        @Path("artistId") artistId: String,
        // Sin esto llegan también recopilatorios y discos donde solo aparece invitado.
        @Query("include_groups") includeGroups: String = "album,single",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyAlbumsPage>

    /** Ojo: estas pistas vienen sin `album`; hay que rellenarlo desde el álbum pedido. */
    @GET("v1/albums/{albumId}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") authorization: String,
        @Path("albumId") albumId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyTracksListPage>

    @GET("v1/albums/{albumId}")
    suspend fun getAlbum(
        @Header("Authorization") authorization: String,
        @Path("albumId") albumId: String
    ): Response<SpotifyAlbumFull>

    // ─── Lo más escuchado por el usuario ───────────────────────────────

    /** @param timeRange `short_term` (~4 semanas), `medium_term` (~6 meses), `long_term`. */
    @GET("v1/me/top/tracks")
    suspend fun getMyTopTracks(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyTracksListPage>

    @GET("v1/me/top/artists")
    suspend fun getMyTopArtists(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyArtistsPage>

    companion object {
        // `track` was the field name on the old, now-removed /tracks endpoint. /items
        // renamed it to `item` (a oneOf TrackObject/EpisodeObject) and kept `track` only as
        // a deprecated alias that Spotify's `fields` filter no longer reliably honors —
        // asking for `track(...)` here silently came back null for every row.
        const val PLAYLIST_TRACK_FIELDS =
            "total,next,items(added_at,item(id,name,duration_ms,is_local,type," +
                "artists(id,name),album(id,name,images(url,width,height)),external_ids(isrc)))"
    }
}
