package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchRow
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.database.serializeArtistRefs
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.network.spotify.SpotifyAlbumFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyAlbumRef
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyArtistFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylist
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistTrackItem
import com.theveloper.pixelplay.data.network.spotify.SpotifySearchResponse
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.network.spotify.SpotifyTracksPage
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.stream.BulkSyncResult
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Biblioteca de Spotify del usuario.
 *
 * Spotify solo entrega **metadatos**: la Web API no da audio, y las URLs de preview de 30s
 * dejaron de emitirse para apps nuevas a finales de 2024. Así que este repositorio importa
 * títulos, artistas, álbumes y portadas; el audio lo resuelve después el emparejador de
 * YouTube Music ([com.theveloper.pixelplay.data.youtube]).
 */
@Singleton
class SpotifyRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val api: SpotifyApiService,
    private val authManager: SpotifyAuthManager,
    private val spotifyDao: SpotifyDao,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) {

    // Vive todo el proceso, igual que SpotifyMatchStateCache: rellena el indicador por
    // canción en cuanto este repositorio se construye, sin que la pantalla de Spotify tenga
    // que estar abierta.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repositoryScope.launch {
            spotifyDao.getAllMatchStatesFlow().collect { rows ->
                SpotifyMatchStateCache.update(rows.associate { it.spotifyId to it.matchState })
                if (rows.any { it.matchState == SpotifyMatchState.PENDING }) {
                    com.theveloper.pixelplay.data.worker.SpotifyMatchWorker.enqueue(context)
                }
            }
        }
    }

    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn

    /**
     * Spotify está rechazando por permisos la lectura del contenido de las playlists.
     *
     * El token guardado sólo lleva los permisos que se concedieron el día que se enlazó la
     * cuenta. Si desde entonces la app pide alguno más — como pasó con `user-top-read` —
     * las cuentas antiguas siguen con el juego viejo y Spotify responde 403 a lo que falta:
     * la lista de playlists se lee, su contenido no. Es indistinguible de "playlists
     * vacías" salvo que alguien lo diga, así que se dice.
     *
     * Sólo se arregla desconectando y volviendo a conectar la cuenta.
     */
    private val _playlistAccessDenied = MutableStateFlow(false)
    val playlistAccessDenied: StateFlow<Boolean> = _playlistAccessDenied.asStateFlow()

    fun playlistsFlow(): Flow<List<SpotifyPlaylistEntity>> = spotifyDao.getAllPlaylists()

    fun songsFlow(): Flow<List<SpotifySongEntity>> = spotifyDao.getAllSpotifySongs()

    fun songsOfPlaylistFlow(playlistId: String): Flow<List<SpotifySongEntity>> =
        spotifyDao.getSongsByPlaylist(playlistId)

    fun accountName(): String? = authManager.accountName()

    fun accountEmail(): String? = authManager.accountEmail()

    // ─── Limitador de peticiones ───────────────────────────────────────

    private val requestMutex = Mutex()

    @Volatile
    private var nextRequestAllowedAtMs = 0L

    /**
     * Ejecuta una llamada respetando un intervalo mínimo entre peticiones, reintentando
     * una vez tras refrescar el token si vuelve 401 y esperando lo que pida la cabecera
     * `Retry-After` cuando Spotify responde 429.
     */
    private suspend fun <T> apiCall(
        label: String = "spotify",
        onForbidden: (() -> Unit)? = null,
        block: suspend (authorization: String) -> Response<T>
    ): T? {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++

            requestMutex.withLock {
                val waitMs = nextRequestAllowedAtMs - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)
                nextRequestAllowedAtMs = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MS
            }

            val authorization = authManager.authorizationHeader() ?: return null
            val response = try {
                block(authorization)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "[$label] petición a Spotify fallida (intento $attempt)")
                if (attempt >= MAX_ATTEMPTS) return null
                delay(RETRY_BACKOFF_MS * attempt)
                continue
            }

            when {
                response.isSuccessful -> return response.body()

                response.code() == 401 -> {
                    // Token caducado antes de tiempo (p. ej. revocado desde la web).
                    authManager.forceRefresh()
                }

                response.code() == 429 -> {
                    val retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull() ?: 5L
                    val waitMs = (retryAfterSeconds.coerceIn(1L, 60L)) * 1000L
                    Timber.w("[$label] Spotify pidió esperar ${retryAfterSeconds}s (429)")
                    requestMutex.withLock {
                        nextRequestAllowedAtMs = System.currentTimeMillis() + waitMs
                    }
                }

                else -> {
                    // 403 con un token válido significa "te falta un permiso", no "ha
                    // caducado" (eso sería 401). Reintentar no sirve de nada.
                    if (response.code() == 403) onForbidden?.invoke()

                    // Con el cuerpo del error, no sólo el código: Spotify explica ahí qué
                    // parámetro no le gusta, y sin eso un 400 y un 404 se investigan a
                    // ciegas. Es la diferencia entre leer el fallo y adivinarlo.
                    val body = runCatching { response.errorBody()?.string() }.getOrNull()
                    Timber.w(
                        "[$label] Spotify respondió HTTP ${response.code()} ${response.message()}" +
                            (body?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                    )
                    return null
                }
            }
        }
        return null
    }

    // ─── Perfil ────────────────────────────────────────────────────────

    suspend fun refreshProfile(): Boolean = withContext(Dispatchers.IO) {
        val profile = apiCall { auth -> api.getProfile(auth) } ?: return@withContext false
        authManager.cacheAccount(profile.displayName, profile.email)
        true
    }

    // ─── Importación ───────────────────────────────────────────────────

    /**
     * Lo ya emparejado, indexado por pista.
     *
     * Reimportar sustituye las filas de una playlist, así que hay que devolverle a cada
     * pista el vídeo que ya se le había encontrado. Se lee una vez por pasada completa y
     * se reutiliza en todas las playlists.
     */
    private suspend fun knownMatches(): Map<String, SpotifyMatchRow> =
        spotifyDao.getKnownMatches().associateBy { it.spotifyId }

    /** Reaplica a las filas recién construidas el emparejamiento que ya se conocía. */
    private fun List<SpotifySongEntity>.carryingMatches(
        known: Map<String, SpotifyMatchRow>
    ): List<SpotifySongEntity> {
        if (known.isEmpty()) return this
        return map { song ->
            val previous = known[song.spotifyId] ?: return@map song
            song.copy(
                matchedVideoId = previous.matchedVideoId,
                matchScore = previous.matchScore,
                matchState = previous.matchState
            )
        }
    }

    private fun List<SpotifyPlaylistTrackItem>.artistIdsOf(): Set<String> =
        flatMapTo(HashSet()) { it.resolvedTrack?.artists.orEmpty().mapNotNull { ref -> ref.id } }

    /** Only a complete, advancing page sequence may replace a saved library snapshot. */
    private fun nextSnapshotOffset(
        current: Int, reportedOffset: Int?, reportedLimit: Int?, count: Int, next: String?, total: Int?
    ): Int? {
        if (reportedOffset != null && reportedOffset != current) {
            throw IOException("Spotify repeated or skipped a page; existing songs were kept")
        }
        if (next.isNullOrBlank()) {
            if (total != null && current.toLong() + count < total.toLong()) {
                throw IOException("Spotify ended pagination before all entries arrived; existing songs were kept")
            }
            return null
        }
        if (count == 0) throw IOException("Spotify returned an empty continuation page")
        // The API may return fewer entries than requested while still providing `next`.
        // Follow its cursor instead of mistaking that short page for a complete library.
        val fromNext = Regex("""[?&]offset=(\d+)""").find(next)?.groupValues?.get(1)?.toIntOrNull()
        val following = fromNext ?: (current.toLong() + (reportedLimit?.takeIf { it > 0 } ?: count))
            .takeIf { it <= Int.MAX_VALUE }?.toInt()
        if (following == null || following <= current) throw IOException("Spotify pagination did not advance")
        return following
    }

    /** "Me gusta" de Spotify. Se guarda como una playlist con id sintético. */
    suspend fun syncLikedSongs(
        known: Map<String, SpotifyMatchRow>? = null
    ): Int = withContext(Dispatchers.IO) {
        val matches = known ?: knownMatches()
        val pendingItems = mutableListOf<SpotifyPlaylistTrackItem>()
        var offset = 0

        while (true) {
            val page: SpotifyTracksPage = apiCall { auth ->
                api.getSavedTracks(auth, limit = LIKED_PAGE_SIZE, offset = offset)
            } ?: throw IOException("Liked Songs could not be fully loaded; existing songs were kept")

            val items = page.items ?: throw IOException("Liked Songs returned an incomplete page")
            pendingItems += items
            offset = nextSnapshotOffset(offset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        // Genre lookup needs every track's artist ids up front, so it runs once after
        // pagination finishes rather than per page — one batch of calls instead of many.
        val genreByArtistId = fetchArtistGenres(pendingItems.artistIdsOf())
        val collected = pendingItems.mapNotNull { item ->
            toSpotifySongEntity(
                track = item.resolvedTrack,
                playlistId = SpotifyPlaylistEntity.LIKED_SONGS_ID,
                addedAt = item.addedAt,
                genreByArtistId = genreByArtistId
            )
        }

        spotifyDao.replaceSongsForPlaylist(SpotifyPlaylistEntity.LIKED_SONGS_ID, collected.carryingMatches(matches))
        spotifyDao.insertPlaylist(
            SpotifyPlaylistEntity(
                id = SpotifyPlaylistEntity.LIKED_SONGS_ID,
                name = "Liked Songs",
                coverUrl = collected.firstOrNull()?.albumArtUrl,
                songCount = collected.size,
                lastSyncTime = System.currentTimeMillis()
            )
        )
        collected.size
    }

    /** Lista las playlists del usuario y guarda su ficha (sin canciones todavía). */
    suspend fun syncUserPlaylists(): List<SpotifyPlaylistEntity> = withContext(Dispatchers.IO) {
        val remote = mutableListOf<SpotifyPlaylist>()
        var offset = 0

        while (true) {
            val page = apiCall { auth ->
                api.getUserPlaylists(auth, limit = PLAYLIST_PAGE_SIZE, offset = offset)
            } ?: throw IOException("Spotify playlists could not be fully loaded; existing playlists were kept")

            val items = page.items ?: throw IOException("Spotify playlists returned an incomplete page")
            remote += items.filter { !it.id.isNullOrBlank() }
            offset = nextSnapshotOffset(offset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        // `lastSyncTime` se conserva del registro anterior en vez de sellarse con "ahora":
        // es lo que permite a una pasada interrumpida saber qué playlists ya se bajaron y
        // retomar por donde iba. Solo se actualiza cuando de verdad se importan sus pistas.
        val previousSyncTimes = spotifyDao.getAllPlaylistsList().associate { it.id to it.lastSyncTime }
        val entities = remote.map { playlist ->
            val id = playlist.id!!
            SpotifyPlaylistEntity(
                id = id,
                name = playlist.name?.ifBlank { null } ?: "Untitled playlist",
                coverUrl = playlist.images?.firstOrNull()?.url,
                songCount = playlist.tracks?.total ?: 0,
                lastSyncTime = previousSyncTimes[id] ?: 0L
            )
        }
        entities.forEach { spotifyDao.insertPlaylist(it) }

        // Playlists borradas en Spotify: fuera de la caché local y de sus canciones.
        val remoteIds = entities.map { it.id }.toSet()
        spotifyDao.getAllPlaylistsList()
            .filter {
                it.id != SpotifyPlaylistEntity.LIKED_SONGS_ID &&
                    it.id != SpotifyPlaylistEntity.BROWSE_ID && it.id !in remoteIds
            }
            .forEach { stale ->
                spotifyDao.deleteSongsByPlaylist(stale.id)
                spotifyDao.deletePlaylist(stale.id)
                deleteAppPlaylistForSpotifyPlaylist(stale.id)
            }

        entities
    }

    suspend fun syncPlaylistSongs(
        playlistId: String,
        known: Map<String, SpotifyMatchRow>? = null
    ): Int = withContext(Dispatchers.IO) {
        val matches = known ?: knownMatches()
        if (playlistId == SpotifyPlaylistEntity.LIKED_SONGS_ID) {
            return@withContext syncLikedSongs(matches)
        }

        val pendingItems = mutableListOf<SpotifyPlaylistTrackItem>()
        var offset = 0
        // Se apaga en cuanto el filtro `fields` demuestre ser el problema, y ya no se
        // vuelve a usar para el resto de páginas de esta playlist.
        var useFieldFilter = true
        var pagesFetched = 0
        var rejected = 0

        var forbidden = false

        while (true) {
            var page = apiCall(
                label = "playlist:$playlistId",
                onForbidden = { forbidden = true }
            ) { auth ->
                api.getPlaylistTracks(
                    auth, playlistId,
                    limit = TRACK_PAGE_SIZE,
                    offset = offset,
                    fields = if (useFieldFilter) SpotifyApiService.PLAYLIST_TRACK_FIELDS else null
                )
            }

            // Un 403 aquí (endpoint /items, no el /tracks retirado) significa que el usuario
            // no es dueño ni colaborador de esta playlist — Spotify solo entrega contenido de
            // playlists propias o donde se colabora. Repetir la llamada no lo arregla.
            if (forbidden) {
                _playlistAccessDenied.value = true
                Timber.w(
                    "Playlist $playlistId: 403. El usuario no es dueño ni colaborador de esta " +
                        "playlist, Spotify no entrega su contenido vía API."
                )
                throw IOException("Spotify denied access to this playlist; existing songs were kept")
            }

            // Reintento sin el recorte de campos. `fields` sólo existe para ahorrar ancho
            // de banda; si es lo que rompe la llamada, traerse la respuesta entera es
            // preferible a importar la playlist vacía.
            if (page == null && useFieldFilter) {
                Timber.w("Playlist $playlistId sin respuesta con `fields`; reintento sin filtro")
                useFieldFilter = false
                page = apiCall(
                    label = "playlist-nofields:$playlistId",
                    onForbidden = { forbidden = true }
                ) { auth ->
                    api.getPlaylistTracks(
                        auth, playlistId,
                        limit = TRACK_PAGE_SIZE,
                        offset = offset,
                        fields = null
                    )
                }
                if (forbidden) {
                    _playlistAccessDenied.value = true
                    throw IOException("Spotify denied access to this playlist; existing songs were kept")
                }
            }

            if (page == null) throw IOException("Playlist could not be fully loaded; existing songs were kept")
            pagesFetched++

            val items = page.items ?: throw IOException("Spotify playlist returned an incomplete page")
            pendingItems += items
            offset = nextSnapshotOffset(offset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        val genreByArtistId = fetchArtistGenres(pendingItems.artistIdsOf())
        val collected = mutableListOf<SpotifySongEntity>()
        pendingItems.forEach { item ->
            val entity = toSpotifySongEntity(item.resolvedTrack, playlistId, item.addedAt, genreByArtistId)
            if (entity != null) collected += entity else rejected++
        }

        // Una playlist que responde bien y aun así no aporta ni una pista es exactamente
        // el fallo que se está persiguiendo; conviene que se note en el log.
        if (collected.isEmpty()) {
            Timber.w(
                "Playlist $playlistId importada vacía " +
                    "(páginas=$pagesFetched, descartadas=$rejected, filtro=$useFieldFilter)"
            )
        }

        spotifyDao.replaceSongsForPlaylist(playlistId, collected.carryingMatches(matches))
        collected.size
    }

    /**
     * Importación completa: perfil, "Me gusta", todas las playlists y sus canciones, y
     * el volcado a la biblioteca unificada.
     *
     * La pasada es **reanudable y va volcando sobre la marcha**. Antes volcaba una sola vez
     * al final, así que una biblioteca grande que superara el límite de ejecución de
     * WorkManager se cortaba justo antes de ese volcado: las canciones de las playlists se
     * habían descargado pero nunca llegaban a la biblioteca, y al reintentar se empezaba de
     * cero. De ahí que solo apareciesen los "Me gusta", que se sincronizan primero.
     *
     * @param shouldContinue se consulta entre playlists; al devolver false la pasada
     *   termina limpia con [BulkSyncResult.isComplete] a false y quien llame la reencola.
     * @param resumeInterrupted sólo para el tramo siguiente de una pasada cortada: salta
     *   lo que ya se bajó hace poco. **Falso cuando lo pide el usuario** — pulsar
     *   "sincronizar" y que no se vuelva a pedir nada porque "ya estaba fresco" es
     *   indistinguible de que el botón no funcione.
     */
    suspend fun syncAllPlaylistsAndSongs(
        shouldContinue: () -> Boolean = { true },
        resumeInterrupted: Boolean = false,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): BulkSyncResult = withContext(Dispatchers.IO) {
        refreshProfile()

        // Se parte de cero cada pasada: si el usuario ya volvió a enlazar la cuenta, el
        // aviso tiene que desaparecer solo en cuanto la importación funcione.
        _playlistAccessDenied.value = false

        val known = knownMatches()
        val passStartedAt = System.currentTimeMillis()
        fun alreadyDone(lastSyncTime: Long?) =
            resumeInterrupted && isFreshlySynced(lastSyncTime, passStartedAt)
        var syncedSongs = 0
        var failed = 0
        var complete = true

        val likedEntry = spotifyDao.getAllPlaylistsList()
            .firstOrNull { it.id == SpotifyPlaylistEntity.LIKED_SONGS_ID }
        if (!alreadyDone(likedEntry?.lastSyncTime)) {
            syncedSongs += syncLikedSongs(known)
            flushToLibrary()
        }

        val playlists = syncUserPlaylists()
        var sinceLastFlush = 0

        for ((index, playlist) in playlists.withIndex()) {
            if (!shouldContinue()) {
                Timber.i("Importación de Spotify pausada en ${index + 1}/${playlists.size}; se reanudará")
                complete = false
                break
            }
            // Ya bajada en esta misma pasada (p. ej. la ejecución anterior que se quedó sin
            // tiempo): no se vuelve a pedir por red.
            if (alreadyDone(playlist.lastSyncTime)) continue

            onProgress?.invoke(index + 1, playlists.size, playlist.name)
            val count = runCatching { syncPlaylistSongs(playlist.id, known) }.getOrElse { error ->
                Timber.w(error, "Fallo al importar la playlist ${playlist.name}")
                failed++
                0
            }
            syncedSongs += count
            spotifyDao.insertPlaylist(
                playlist.copy(songCount = count, lastSyncTime = System.currentTimeMillis())
            )

            if (++sinceLastFlush >= FLUSH_EVERY_N_PLAYLISTS) {
                sinceLastFlush = 0
                flushToLibrary()
            }
        }

        flushToLibrary()

        BulkSyncResult(
            playlistCount = playlists.size + 1, // +1 por "Me gusta"
            syncedSongCount = syncedSongs,
            failedPlaylistCount = failed,
            isComplete = complete
        )
    }

    /**
     * Vuelca a la biblioteca lo importado hasta ahora. Se llama varias veces por pasada
     * para que un corte a mitad no tire el trabajo ya hecho.
     */
    private suspend fun flushToLibrary() {
        runCatching {
            syncUnifiedLibrarySongsFromSpotify()
            mirrorPlaylistsIntoApp()
        }.onFailure { Timber.w(it, "No se pudo volcar el progreso parcial a la biblioteca") }
    }

    /** ¿Se bajó ya dentro de esta misma pasada (incluyendo una ejecución previa cortada)? */
    private fun isFreshlySynced(lastSyncTime: Long?, passStartedAt: Long): Boolean {
        if (lastSyncTime == null || lastSyncTime <= 0L) return false
        return passStartedAt - lastSyncTime < RESUME_WINDOW_MS
    }

    // ─── Explorar el catálogo ──────────────────────────────────────────

    data class CatalogSearchResults(
        val tracks: List<SpotifyTrack> = emptyList(),
        val artists: List<SpotifyArtistFull> = emptyList(),
        val albums: List<SpotifyAlbumFull> = emptyList()
    ) {
        val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty()
    }

    /**
     * @param types tipos a buscar, separados por comas.
     * @param limit techo aproximado de resultados por tipo. Spotify no expone un `limit`
     *   utilizable — ver la nota en [fetchSearchPage] — así que esto se consigue pidiendo
     *   varias páginas con `offset` hasta acercarse a este número o quedarse sin más.
     */
    suspend fun searchCatalog(
        query: String,
        types: String = "track,artist,album",
        limit: Int = 20
    ): CatalogSearchResults = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext CatalogSearchResults()

        val tracks = mutableListOf<SpotifyTrack>()
        val artists = mutableListOf<SpotifyArtistFull>()
        val albums = mutableListOf<SpotifyAlbumFull>()

        var offset = 0
        var page = 0
        while (page < MAX_SEARCH_PAGES) {
            val response = fetchSearchPage(query, types, offset) ?: break

            val pageTracks = response.tracks?.items.orEmpty().filter { !it.id.isNullOrBlank() }
            val pageArtists = response.artists?.items.orEmpty().filter { !it.id.isNullOrBlank() }
            val pageAlbums = response.albums?.items.orEmpty().filter { !it.id.isNullOrBlank() }
            tracks += pageTracks
            artists += pageArtists
            albums += pageAlbums

            // Ninguna sección tiene más ("next" a null) o ya se sobrepasó lo pedido: no
            // tiene sentido gastar otra página de red.
            val anyHasMore = response.tracks?.next != null ||
                response.artists?.next != null ||
                response.albums?.next != null
            val pageWasEmpty = pageTracks.isEmpty() && pageArtists.isEmpty() && pageAlbums.isEmpty()
            if (!anyHasMore || pageWasEmpty) break
            if (tracks.size >= limit && artists.size >= limit && albums.size >= limit) break

            offset += SEARCH_PAGE_SIZE_GUESS
            page++
        }

        CatalogSearchResults(
            tracks = tracks.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            albums = albums.distinctBy { it.id }
        )
    }

    /**
     * Una página de `/v1/search`.
     *
     * Spotify rechaza con `400 Invalid limit` cualquier petición que incluya `limit`, sin
     * importar el valor — hasta el propio valor por defecto de su documentación falla. Sólo
     * la petición mínima (sin `limit`) responde 200, y de ahí sale una página de tamaño fijo
     * bastante pequeño. Como no hay forma de pedir páginas más grandes, conseguir más
     * resultados es cuestión de pedir más páginas con `offset`, no un `limit` mayor.
     */
    private suspend fun fetchSearchPage(
        query: String,
        types: String,
        offset: Int
    ): SpotifySearchResponse? {
        var response = apiCall("search:plain@$offset") { auth ->
            api.search(auth, query, types, offset = offset.takeIf { it > 0 })
        }
        if (response == null) {
            response = apiCall("search:market@$offset") { auth ->
                api.search(auth, query, types, market = "US", offset = offset.takeIf { it > 0 })
            }
        }
        return response
    }

    /**
     * Los ids de Spotify que ya están en la biblioteca.
     *
     * Lo usa la búsqueda para no ofrecer en "más en Spotify" algo que el usuario ya tiene:
     * ahí sólo debe salir lo que le falta.
     */
    suspend fun knownSpotifyIds(): Set<String> = withContext(Dispatchers.IO) {
        spotifyDao.getAllSpotifyIds().toHashSet()
    }

    suspend fun getArtist(artistId: String): SpotifyArtistFull? = withContext(Dispatchers.IO) {
        apiCall { auth -> api.getArtist(auth, artistId) }
    }

    /**
     * Primary genre per artist id, for backfilling [SpotifySongEntity.genre] — a track's own
     * object never carries genre, only the full artist object does, so this is the only way
     * imported tracks end up searchable/filterable by genre at all.
     *
     * Chunks into batches of 50 (Spotify's `/v1/artists` cap) and takes each artist's first
     * listed genre; artists with an empty genre list are simply absent from the result map
     * rather than mapped to null, so callers can `getOrDefault`/`get` without extra null checks.
     */
    private suspend fun fetchArtistGenres(artistIds: Set<String>): Map<String, String> {
        if (artistIds.isEmpty()) return emptyMap()
        val result = HashMap<String, String>(artistIds.size)
        artistIds.chunked(50).forEach { batch ->
            val response = apiCall { auth -> api.getArtists(auth, batch.joinToString(",")) }
            response?.artists.orEmpty().forEach { artist ->
                val id = artist?.id ?: return@forEach
                val primaryGenre = artist.genres?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                result[id] = primaryGenre
            }
        }
        return result
    }

    suspend fun getArtistTopTracks(artistId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        apiCall { auth -> api.getArtistTopTracks(auth, artistId) }
            ?.tracks.orEmpty()
            .filter { !it.id.isNullOrBlank() }
    }

    suspend fun getArtistAlbums(artistId: String): List<SpotifyAlbumFull> = withContext(Dispatchers.IO) {
        apiCall { auth -> api.getArtistAlbums(auth, artistId) }
            ?.items.orEmpty()
            .filter { !it.id.isNullOrBlank() }
            // Spotify repite el mismo disco por región; sin esto la ficha sale con
            // cuatro copias del mismo álbum.
            .distinctBy { "${it.name?.lowercase()}|${it.totalTracks}" }
            .sortedByDescending { it.releaseDate.orEmpty() }
    }

    /**
     * Pistas de un álbum. El endpoint de pistas no incluye el álbum en cada una, así que se
     * pide la ficha del álbum y se rellena — de lo contrario se importarían sin portada.
     */
    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val album = apiCall { auth -> api.getAlbum(auth, albumId) }
        val albumRef = SpotifyAlbumRef(
            id = album?.id ?: albumId,
            name = album?.name,
            images = album?.images
        )
        apiCall { auth -> api.getAlbumTracks(auth, albumId) }
            ?.items.orEmpty()
            .filter { !it.id.isNullOrBlank() }
            .map { track -> track.copy(album = track.album ?: albumRef) }
    }

    /** Lo más escuchado por el usuario. Requiere el permiso `user-top-read`. */
    suspend fun getMyTopTracks(timeRange: String = "medium_term"): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            apiCall { auth -> api.getMyTopTracks(auth, timeRange) }
                ?.items.orEmpty()
                .filter { !it.id.isNullOrBlank() }
        }

    suspend fun getMyTopArtists(timeRange: String = "medium_term"): List<SpotifyArtistFull> =
        withContext(Dispatchers.IO) {
            apiCall { auth -> api.getMyTopArtists(auth, timeRange) }
                ?.items.orEmpty()
                .filter { !it.id.isNullOrBlank() }
        }

    /**
     * Mete en la biblioteca las pistas que el usuario ha encontrado explorando, para que
     * pasen por el mismo emparejador que el resto y se puedan reproducir.
     *
     * @return cuántas pistas nuevas se añadieron.
     */
    suspend fun importTracks(tracks: List<SpotifyTrack>): Int = withContext(Dispatchers.IO) {
        val artistIds = tracks.flatMapTo(HashSet()) { it.artists.orEmpty().mapNotNull { ref -> ref.id } }
        val genreByArtistId = fetchArtistGenres(artistIds)
        val entities = tracks.mapNotNull { track ->
            toSpotifySongEntity(track, SpotifyPlaylistEntity.BROWSE_ID, addedAt = null, genreByArtistId)
        }
        if (entities.isEmpty()) return@withContext 0

        val alreadyKnown = spotifyDao.getAllSpotifySongsList().map { it.spotifyId }.toSet()
        spotifyDao.insertSongs(entities.carryingMatches(knownMatches()))

        val existingBrowse = spotifyDao.getAllPlaylistsList()
            .firstOrNull { it.id == SpotifyPlaylistEntity.BROWSE_ID }
        val browseCount = spotifyDao.getAllSpotifySongsList()
            .count { it.playlistId == SpotifyPlaylistEntity.BROWSE_ID }
        spotifyDao.insertPlaylist(
            SpotifyPlaylistEntity(
                id = SpotifyPlaylistEntity.BROWSE_ID,
                name = "Saved from Spotify",
                coverUrl = existingBrowse?.coverUrl ?: entities.firstOrNull()?.albumArtUrl,
                songCount = browseCount,
                lastSyncTime = System.currentTimeMillis()
            )
        )

        syncUnifiedLibrarySongsFromSpotify()
        mirrorPlaylistsIntoApp()

        entities.count { it.spotifyId !in alreadyKnown }
    }

    /** Los ids de biblioteca que corresponden a estas pistas, ya importadas. */
    fun unifiedIdsFor(tracks: List<SpotifyTrack>): List<Long> =
        tracks.mapNotNull { it.id }.map { unifiedSongId(it) }

    /**
     * Mete en la biblioteca canciones encontradas buscando directamente en YouTube Music.
     *
     * Reutiliza toda la tabla `spotify_songs` y el resto del pipeline de
     * [importTracks]/[syncUnifiedLibrarySongsFromSpotify] en vez de crear una tabla propia: la
     * única diferencia real es que aquí no hace falta el emparejador ([TrackMatcher]) porque el
     * `videoId` ya se sabe desde la propia búsqueda, así que la fila se guarda ya
     * [SpotifyMatchState.MATCHED]. El "spotifyId" de estas filas es sintético
     * ([youTubeMusicSyntheticId]), nunca un id real de Spotify — nada en el resto del pipeline
     * distingue entre los dos, así que funciona igual de bien como clave de hash para
     * [unifiedSongId].
     *
     * @return cuántas pistas nuevas se añadieron.
     */
    suspend fun importYouTubeMusicTracks(
        results: List<com.theveloper.pixelplay.data.youtube.YouTubeSearchResult>
    ): Int = withContext(Dispatchers.IO) {
        val entities = results.map { toYouTubeMusicSongEntity(it) }
        if (entities.isEmpty()) return@withContext 0

        val alreadyKnown = spotifyDao.getAllSpotifySongsList().map { it.spotifyId }.toSet()
        spotifyDao.insertSongs(entities)

        val existingBrowse = spotifyDao.getAllPlaylistsList()
            .firstOrNull { it.id == SpotifyPlaylistEntity.BROWSE_ID }
        val browseCount = spotifyDao.getAllSpotifySongsList()
            .count { it.playlistId == SpotifyPlaylistEntity.BROWSE_ID }
        spotifyDao.insertPlaylist(
            SpotifyPlaylistEntity(
                id = SpotifyPlaylistEntity.BROWSE_ID,
                name = existingBrowse?.name ?: "Saved from Spotify",
                coverUrl = existingBrowse?.coverUrl ?: entities.firstOrNull()?.albumArtUrl,
                songCount = browseCount,
                lastSyncTime = System.currentTimeMillis()
            )
        )

        syncUnifiedLibrarySongsFromSpotify()
        mirrorPlaylistsIntoApp()

        entities.count { it.spotifyId !in alreadyKnown }
    }

    /**
     * El "spotifyId" sintético bajo el que se guarda una pista encontrada en YouTube Music.
     *
     * [SpotifyStreamProxy.validateId] exige el formato real de un id de Spotify —
     * `^[A-Za-z0-9]{22}$` ([CloudStreamSecurity.validateSpotifyTrackId]) — así que no vale
     * usar el `videoId` tal cual (11 caracteres, puede llevar `-`/`_`): un id sintético que no
     * cumpla el patrón hace que el proxy lo rechace en silencio y la reproducción falle con
     * "unknown protocol: spotify" (la URI nunca llega a resolverse a algo reproducible).
     * SHA-256 del videoId, primeros 22 caracteres hex, cumple el patrón siempre y es estable
     * por videoId — el resto del pipeline (unifiedSongId, spotifyDao.getMatchedVideoId) no
     * necesita poder deshacer este hash, solo usarlo como clave consistente.
     */
    fun youTubeMusicSyntheticId(videoId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(22)
    }

    private fun toYouTubeMusicSongEntity(
        result: com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
    ): SpotifySongEntity {
        val syntheticId = youTubeMusicSyntheticId(result.videoId)
        return SpotifySongEntity(
            id = "${SpotifyPlaylistEntity.BROWSE_ID}_$syntheticId",
            spotifyId = syntheticId,
            playlistId = SpotifyPlaylistEntity.BROWSE_ID,
            title = result.title,
            artist = result.artist.ifBlank { "Unknown Artist" },
            album = result.album?.ifBlank { null } ?: "Unknown Album",
            albumId = null,
            durationMs = (result.durationSeconds?.toLong() ?: 0L) * 1000L,
            albumArtUrl = result.thumbnailUrl,
            isrc = null,
            dateAdded = System.currentTimeMillis(),
            matchedVideoId = result.videoId,
            matchScore = 1f,
            matchState = SpotifyMatchState.MATCHED
        )
    }

    /**
     * Saca del catálogo explorado una pista que sólo está ahí porque el usuario la trajo
     * desde "más en Spotify", sin pasar por quitarla de una playlist a mano.
     *
     * Una pista de una playlist de verdad (sincronizada) o de "Me gusta" NO se toca aquí:
     * borrarla de esas requeriría deshacer la sincronización, y el usuario esperaría verla
     * volver en cuanto se sincronizase otra vez. Sólo tiene sentido para lo que el propio
     * usuario trajo desde el catálogo — que es exactamente lo único que este método borra.
     *
     * @return true si la pista se quitó de la biblioteca; false si pertenece a algo más
     *   (una playlist real, Me gusta) y por tanto no se tocó — el llamador debe entonces
     *   limitarse a des-favoritearla como con cualquier otra canción.
     */
    suspend fun removeFromExploredCatalog(spotifyId: String): Boolean = withContext(Dispatchers.IO) {
        val playlists = spotifyDao.getPlaylistIdsForSong(spotifyId)
        if (playlists != listOf(SpotifyPlaylistEntity.BROWSE_ID)) return@withContext false

        spotifyDao.deleteSongFromPlaylist(spotifyId, SpotifyPlaylistEntity.BROWSE_ID)

        val remainingBrowseCount = spotifyDao.getAllSpotifySongsList()
            .count { it.playlistId == SpotifyPlaylistEntity.BROWSE_ID }
        spotifyDao.getAllPlaylistsList()
            .firstOrNull { it.id == SpotifyPlaylistEntity.BROWSE_ID }
            ?.let { browse ->
                spotifyDao.insertPlaylist(browse.copy(songCount = remainingBrowseCount))
            }

        syncUnifiedLibrarySongsFromSpotify()
        mirrorPlaylistsIntoApp()
        true
    }

    // ─── Biblioteca unificada ──────────────────────────────────────────

    /**
     * Vuelca las pistas de Spotify a las tablas `songs`/`albums`/`artists` para que
     * aparezcan junto a la música local. Los ids son negativos y con un desplazamiento
     * propio, de modo que nunca chocan con los de MediaStore.
     */
    suspend fun syncUnifiedLibrarySongsFromSpotify() = withContext(Dispatchers.IO) {
        val sourceSongs = spotifyDao.getDistinctSpotifySongsList()
        val existingUnifiedIds = musicDao.getAllSpotifySongIds()

        if (sourceSongs.isEmpty()) {
            if (existingUnifiedIds.isNotEmpty()) musicDao.clearAllSpotifySongs()
            return@withContext
        }

        val songs = ArrayList<SongEntity>(sourceSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        sourceSongs.forEach { source ->
            val songId = unifiedId(SONG_ID_OFFSET, source.spotifyId)
            val artistNames = CloudMusicUtils.parseArtistNames(source.artist)
            val artistRefs = artistNames.mapIndexed { index, name ->
                val artistId = unifiedId(ARTIST_ID_OFFSET, name.lowercase())
                artists.putIfAbsent(
                    artistId,
                    ArtistEntity(id = artistId, name = name, trackCount = 0)
                )
                crossRefs += SongArtistCrossRef(
                    songId = songId,
                    artistId = artistId,
                    isPrimary = index == 0
                )
                ArtistRef(id = artistId, name = name, isPrimary = index == 0)
            }

            val primaryArtist = artistRefs.firstOrNull()
            val albumKey = source.albumId ?: "${source.album}|${source.artist}"
            val albumId = unifiedId(ALBUM_ID_OFFSET, albumKey)
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = source.album,
                    artistName = primaryArtist?.name ?: source.artist,
                    artistId = primaryArtist?.id ?: 0L,
                    albumArtUriString = source.albumArtUrl,
                    songCount = 0,
                    dateAdded = source.dateAdded,
                    year = 0
                )
            )

            songs += SongEntity(
                id = songId,
                title = source.title,
                artistName = source.artist,
                artistId = primaryArtist?.id ?: 0L,
                albumName = source.album,
                albumId = albumId,
                contentUriString = "spotify://${source.spotifyId}",
                albumArtUriString = source.albumArtUrl,
                duration = source.durationMs,
                genre = source.genre,
                filePath = "",
                parentDirectoryPath = "",
                dateAdded = source.dateAdded,
                artistsJson = serializeArtistRefs(artistRefs),
                sourceType = SourceType.SPOTIFY
            )
        }

        val songsPerAlbum = songs.groupingBy { it.albumId }.eachCount()
        val albumsWithCounts = albums.values.map { album ->
            album.copy(songCount = songsPerAlbum[album.id] ?: 0)
        }
        val tracksPerArtist = crossRefs.groupingBy { it.artistId }.eachCount()
        val artistsWithCounts = artists.values.map { artist ->
            artist.copy(trackCount = tracksPerArtist[artist.id] ?: 0)
        }

        val currentIds = songs.map { it.id }.toSet()
        val deletedUnifiedSongIds = existingUnifiedIds.filter { it !in currentIds }

        musicDao.incrementalSyncMusicData(
            songs = songs,
            albums = albumsWithCounts,
            artists = artistsWithCounts,
            crossRefs = crossRefs,
            deletedSongIds = deletedUnifiedSongIds
        )
    }

    /** Refleja cada playlist de Spotify como una playlist normal de la app. */
    private suspend fun mirrorPlaylistsIntoApp() {
        // Una sola lectura de la tabla; agrupar en memoria evita una consulta por lista.
        val songsByPlaylist = spotifyDao.getAllSpotifySongsList().groupBy { it.playlistId }

        spotifyDao.getAllPlaylistsList().forEach { playlist ->
            val songIds = songsByPlaylist[playlist.id]
                ?.map { unifiedId(SONG_ID_OFFSET, it.spotifyId).toString() }
                .orEmpty()

            runCatching {
                playlistPreferencesRepository.createPlaylist(
                    name = playlist.name,
                    songIds = songIds,
                    coverImageUri = playlist.coverUrl,
                    // El id fijo hace que una re-importación actualice la lista en vez de
                    // crear un duplicado cada vez.
                    customId = appPlaylistId(playlist.id),
                    source = SPOTIFY_PLAYLIST_SOURCE
                )
            }.onFailure { Timber.w(it, "No se pudo reflejar la playlist ${playlist.name}") }
        }
    }

    private suspend fun deleteAppPlaylistForSpotifyPlaylist(spotifyPlaylistId: String) {
        runCatching { playlistPreferencesRepository.deletePlaylist(appPlaylistId(spotifyPlaylistId)) }
    }

    // ─── Cierre de sesión ──────────────────────────────────────────────

    /**
     * Cierra la sesión **sin tocar la biblioteca**, para volver a entrar y obtener un token
     * con los permisos actuales.
     *
     * [logout] borra además todo lo importado, incluidos los emparejamientos con YouTube:
     * usarlo para arreglar un problema de permisos costaría horas de búsqueda de audio ya
     * hechas. Aquí sólo se tira el token; al reconectar, la importación rellena las
     * playlists y las canciones ya emparejadas siguen estándolo.
     */
    suspend fun reauthorize() = withContext(Dispatchers.IO) {
        authManager.clearSession()
        _playlistAccessDenied.value = false
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        spotifyDao.getAllPlaylistsList().forEach { deleteAppPlaylistForSpotifyPlaylist(it.id) }
        spotifyDao.clearAllSongs()
        spotifyDao.clearAllPlaylists()
        musicDao.clearAllSpotifySongs()
        authManager.clearSession()
    }

    // ─── Utilidades ────────────────────────────────────────────────────

    private fun toSpotifySongEntity(
        track: SpotifyTrack?,
        playlistId: String,
        addedAt: String?,
        genreByArtistId: Map<String, String> = emptyMap()
    ): SpotifySongEntity? {
        val id = track?.id ?: return null
        if (track.isLocal == true) return null // Ficheros locales del usuario: no hay nada que emparejar.
        if (track.type != null && track.type != "track") return null // Episodios de podcast.

        val artistNames = track.artists.orEmpty().mapNotNull { it.name?.ifBlank { null } }
        // First artist with a known genre wins — usually the primary artist, but a featured
        // artist's genre is a reasonable fallback over leaving the track ungenred entirely.
        val genre = track.artists.orEmpty().firstNotNullOfOrNull { it.id?.let(genreByArtistId::get) }
        return SpotifySongEntity(
            id = "${playlistId}_$id",
            spotifyId = id,
            playlistId = playlistId,
            title = track.name?.ifBlank { null } ?: "Unknown title",
            artist = artistNames.joinToString(", ").ifBlank { "Unknown Artist" },
            album = track.album?.name?.ifBlank { null } ?: "Unknown Album",
            albumId = track.album?.id,
            durationMs = track.durationMs ?: 0L,
            albumArtUrl = track.album?.images?.firstOrNull()?.url,
            isrc = track.externalIds?.isrc,
            dateAdded = parseAddedAt(addedAt),
            genre = genre
        )
    }

    private fun parseAddedAt(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }

    companion object {
        const val SPOTIFY_PLAYLIST_SOURCE = "SPOTIFY"

        // Bandas de ids negativas reservadas a Spotify. Cada una es disjunta de las
        // demás porque el hash se acota a menos de un billón.
        private const val SONG_ID_OFFSET = 3_000_000_000_000L
        private const val ALBUM_ID_OFFSET = 4_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 5_000_000_000_000L
        private const val ID_BAND_SIZE = 1_000_000_000_000L

        private const val LIKED_PAGE_SIZE = 50
        private const val PLAYLIST_PAGE_SIZE = 50
        private const val TRACK_PAGE_SIZE = 100

        /**
         * Páginas de `/v1/search` que se piden como mucho por consulta. Es un tope de
         * peticiones de red, no de resultados: cada página ronda [SEARCH_PAGE_SIZE_GUESS]
         * pistas, así que esto acota cuánto se gasta buscando antes de conformarse con lo
         * que haya.
         */
        private const val MAX_SEARCH_PAGES = 4

        /**
         * Tamaño de página que Spotify usa cuando no se manda `limit` (que siempre falla).
         * Es una estimación a partir de lo observado, no algo que Spotify documente; si
         * cambia, el peor caso es pedir alguna página de más o de menos, no un fallo.
         */
        private const val SEARCH_PAGE_SIZE_GUESS = 5

        private const val MAX_ATTEMPTS = 3
        private const val MIN_REQUEST_INTERVAL_MS = 120L
        private const val RETRY_BACKOFF_MS = 800L

        /** Cada cuántas playlists se vuelca lo importado a la biblioteca. */
        private const val FLUSH_EVERY_N_PLAYLISTS = 4

        /**
         * Cuánto sigue contando una playlist como "ya bajada en esta pasada". Cubre el
         * hueco entre que WorkManager corta una ejecución y arranca la siguiente; pasado
         * ese plazo, sincronizar vuelve a pedirlo todo.
         */
        private const val RESUME_WINDOW_MS = 30 * 60 * 1000L

        fun appPlaylistId(spotifyPlaylistId: String): String = "spotify_playlist:$spotifyPlaylistId"

        /**
         * Id negativo estable a partir de una clave de texto. FNV-1a de 64 bits en vez de
         * [String.hashCode] (32 bits) porque con miles de pistas las colisiones de 32 bits
         * dejan de ser teóricas.
         */
        internal fun unifiedId(offset: Long, key: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
            for (char in key) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
            val bounded = (hash and Long.MAX_VALUE) % ID_BAND_SIZE
            return -(offset + bounded)
        }

        fun unifiedSongId(spotifyId: String): Long = unifiedId(SONG_ID_OFFSET, spotifyId)
    }
}
