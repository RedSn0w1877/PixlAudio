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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val importStore: SpotifyImportStore
) {

    // Vive todo el proceso, igual que SpotifyMatchStateCache: rellena el indicador por
    // canción en cuanto este repositorio se construye, sin que la pantalla de Spotify tenga
    // que estar abierta.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every Spotify call goes through this one executor: a single shared rate limiter, the
     * 401 refresh-once rule, `Retry-After` handling and bounded retries. Replaceable in tests.
     */
    internal var executor: SpotifyApiExecutor = SpotifyApiExecutor(authManager)

    @Volatile
    private var lastMatcherKickMs = 0L

    @OptIn(FlowPreview::class)
    private fun observeMatchStates() {
        repositoryScope.launch {
            // Sampled: every imported page and every matched song rewrites this table, and
            // rebuilding the whole per-song map on each write made a big import quadratic.
            spotifyDao.getAllMatchStatesFlow()
                .sample(MATCH_STATE_SAMPLE_MS)
                .collect { rows ->
                    SpotifyMatchStateCache.update(rows.associate { it.spotifyId to it.matchState })
                    val now = System.currentTimeMillis()
                    if (rows.any { it.matchState == SpotifyMatchState.PENDING } &&
                        now - lastMatcherKickMs >= MATCHER_KICK_INTERVAL_MS
                    ) {
                        lastMatcherKickMs = now
                        // Matching starts as soon as the first imported page lands, while the
                        // import is still running (KEEP: a no-op when it already is).
                        com.theveloper.pixelplay.data.worker.SpotifyMatchWorker.enqueue(context)
                    }
                }
        }
    }

    init {
        observeMatchStates()
    }

    // A getter, so building this repository doesn't open the encrypted session prefs.
    val isLoggedIn: StateFlow<Boolean> get() = authManager.isLoggedIn

    /**
     * The login itself is the problem and only signing in again fixes it: Spotify rejected
     * the refreshed token (401), or the token verifiably lacks a scope the import reads with
     * (`user-library-read`, `playlist-read-private`, `playlist-read-collaborative`).
     *
     * A plain 403 on one playlist does NOT raise this: that is Spotify refusing that
     * playlist (it only hands over playlists you own or collaborate on), reported per
     * playlist in [skippedPlaylists]. Mixing the two up is what used to put a false
     * "reconnect" banner on every account that follows someone else's playlist.
     */
    private val _reconnectRequired = MutableStateFlow(false)
    val reconnectRequired: StateFlow<Boolean> = _reconnectRequired.asStateFlow()

    /** Playlists the import left out, with the reason each one was left out. */
    val skippedPlaylists: StateFlow<List<SkippedPlaylist>> get() = importStore.skipped

    fun playlistsFlow(): Flow<List<SpotifyPlaylistEntity>> = spotifyDao.getAllPlaylists()

    fun songsFlow(): Flow<List<SpotifySongEntity>> = spotifyDao.getAllSpotifySongs()

    fun songsOfPlaylistFlow(playlistId: String): Flow<List<SpotifySongEntity>> =
        spotifyDao.getSongsByPlaylist(playlistId)

    fun accountName(): String? = authManager.accountName()

    fun accountEmail(): String? = authManager.accountEmail()

    /**
     * Compatibility shim for the catalogue calls, which only care whether they got a body.
     * The import itself uses [executor] directly because it must tell failures apart.
     */
    private suspend fun <T> apiCall(
        label: String = "spotify",
        block: suspend (authorization: String) -> Response<T>
    ): T? = executor.execute(label, block).orNull()

    // ─── Perfil ────────────────────────────────────────────────────────

    /** The Spotify user id: a playlist is readable only when its owner is this id (or it is collaborative). */
    @Volatile
    private var currentUserId: String? = null

    suspend fun refreshProfile(): Boolean = withContext(Dispatchers.IO) {
        when (val result = executor.execute("profile") { auth -> api.getProfile(auth) }) {
            is SpotifyResult.Ok -> {
                currentUserId = result.body.id?.takeIf { it.isNotBlank() }
                authManager.cacheAccount(result.body.displayName, result.body.email)
                true
            }
            is SpotifyResult.Unauthorized -> {
                if (authManager.isLoggedIn.value) _reconnectRequired.value = true
                false
            }
            else -> false
        }
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

    /** Only a complete, advancing page sequence may remove songs from a saved library snapshot. */
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

    /** Turns a failed call into the exception the importer reacts to; returns the body when it worked. */
    private fun <T> SpotifyResult<T>.bodyOrThrow(what: String): T = when (this) {
        is SpotifyResult.Ok -> body
        is SpotifyResult.Unauthorized -> {
            _reconnectRequired.value = true
            throw ImportAborted(null, true, "Spotify login expired; reconnect needed")
        }
        is SpotifyResult.RateLimited ->
            throw ImportAborted(retryAfterSeconds, false, "Spotify is rate limiting requests; retry in ${retryAfterSeconds}s")
        is SpotifyResult.Forbidden -> {
            noteForbidden()
            throw PlaylistSkipped(SkipReason.FORBIDDEN, "$what: Spotify refused access (HTTP 403)")
        }
        is SpotifyResult.NotFound -> throw PlaylistSkipped(SkipReason.NOT_FOUND, "$what: not found (HTTP 404)")
        is SpotifyResult.BadRequest -> throw IOException("$what: Spotify rejected the request (${message ?: "HTTP 400"})")
        is SpotifyResult.ServerError -> throw IOException("$what: Spotify error HTTP $code")
        is SpotifyResult.NetworkError -> throw IOException("$what: network error", cause)
    }

    /**
     * A 403 only means "reconnect" when the token provably lacks a scope the import needs.
     * With every scope granted it is Spotify refusing that one playlist, nothing to reconnect.
     */
    private fun noteForbidden() {
        val missing = authManager.missingScopes(SpotifyAuthManager.IMPORT_SCOPES)
        if (!missing.isNullOrEmpty()) {
            Timber.w("Spotify 403 and the token is missing scopes $missing: reconnect needed")
            _reconnectRequired.value = true
        }
    }

    /**
     * "Me gusta" de Spotify. Se guarda como una playlist con id sintético.
     *
     * Each page is written the moment it arrives (and handed to [onPage]) so the user sees
     * their likes appear straight away, newest first, instead of after the last page. Rows of
     * songs that are no longer liked are only removed once EVERY page has arrived, so a
     * failure half-way never shrinks the library.
     *
     * When the total and the newest track match the previous pass, the remaining pages are
     * skipped: an unchanged library costs one request.
     */
    suspend fun syncLikedSongs(
        onPage: (suspend (List<SpotifySongEntity>) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val keepIds = HashSet<String>()
        var offset = 0
        var signature: String? = null
        var cover: String? = null

        while (true) {
            val currentOffset = offset
            val result = executor.execute("liked@$currentOffset") { auth ->
                api.getSavedTracks(auth, limit = LIKED_PAGE_SIZE, offset = currentOffset)
            }
            if (result is SpotifyResult.Forbidden) {
                // /me/tracks answers 403 only for a missing `user-library-read`.
                _reconnectRequired.value = true
                throw IOException("Spotify refused Liked Songs (HTTP 403): reconnect Spotify to grant library access")
            }
            val page: SpotifyTracksPage = result.bodyOrThrow("Liked Songs")
            val items = page.items ?: throw IOException("Liked Songs returned an incomplete page")

            if (currentOffset == 0) {
                signature = "${page.total}|${items.firstOrNull()?.resolvedTrack?.id}"
                val previous = spotifyDao.getAllPlaylistsList()
                    .firstOrNull { it.id == SpotifyPlaylistEntity.LIKED_SONGS_ID }
                if (previous != null && previous.lastSyncTime > 0L && importStore.likedSignature == signature) {
                    Timber.d("Liked Songs unchanged ($signature); nothing to import")
                    return@withContext previous.songCount
                }
            }

            val entities = items.mapNotNull { item ->
                toSpotifySongEntity(
                    track = item.resolvedTrack,
                    playlistId = SpotifyPlaylistEntity.LIKED_SONGS_ID,
                    addedAt = item.addedAt
                )
            }
            spotifyDao.upsertImportedSongs(entities)
            entities.mapTo(keepIds) { it.id }
            if (currentOffset == 0) {
                cover = entities.firstOrNull()?.albumArtUrl
                // The playlist row exists from the first page on, so the dashboard shows it growing.
                spotifyDao.insertPlaylist(
                    SpotifyPlaylistEntity(
                        id = SpotifyPlaylistEntity.LIKED_SONGS_ID,
                        name = "Liked Songs",
                        coverUrl = cover,
                        songCount = page.total ?: entities.size,
                        lastSyncTime = 0L
                    )
                )
            }
            onPage?.invoke(entities)

            offset = nextSnapshotOffset(currentOffset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        spotifyDao.removeSongsNotIn(SpotifyPlaylistEntity.LIKED_SONGS_ID, keepIds)
        spotifyDao.insertPlaylist(
            SpotifyPlaylistEntity(
                id = SpotifyPlaylistEntity.LIKED_SONGS_ID,
                name = "Liked Songs",
                coverUrl = cover,
                songCount = keepIds.size,
                lastSyncTime = System.currentTimeMillis()
            )
        )
        importStore.likedSignature = signature
        keepIds.size
    }

    /** A playlist as the user's library lists it, with what decides whether this app may read it. */
    internal data class RemotePlaylist(
        val entity: SpotifyPlaylistEntity,
        val snapshotId: String?,
        /** False for playlists owned by someone else: Spotify only hands over the songs of playlists you own or collaborate on. */
        val readable: Boolean,
        val renamed: Boolean
    )

    /** Lista las playlists del usuario y guarda la ficha de las que se pueden leer (sin canciones todavía). */
    suspend fun syncUserPlaylists(): List<SpotifyPlaylistEntity> =
        syncRemotePlaylists().filter { it.readable }.map { it.entity }

    internal suspend fun syncRemotePlaylists(): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val remote = mutableListOf<SpotifyPlaylist>()
        var offset = 0

        while (true) {
            val currentOffset = offset
            val result = executor.execute("playlists@$currentOffset") { auth ->
                api.getUserPlaylists(auth, limit = PLAYLIST_PAGE_SIZE, offset = currentOffset)
            }
            if (result is SpotifyResult.Forbidden) {
                _reconnectRequired.value = true
                throw ImportAborted(null, true, "Spotify refused the playlist list (HTTP 403): reconnect Spotify")
            }
            val page = result.bodyOrThrow("Spotify playlists")
            val items = page.items ?: throw IOException("Spotify playlists returned an incomplete page")
            remote += items.filter { !it.id.isNullOrBlank() }
            offset = nextSnapshotOffset(currentOffset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        // `lastSyncTime` se conserva del registro anterior en vez de sellarse con "ahora":
        // solo se actualiza cuando de verdad se importan sus pistas.
        val previousById = spotifyDao.getAllPlaylistsList().associateBy { it.id }
        val me = currentUserId
        val playlists = remote.map { playlist ->
            val id = playlist.id!!
            val previous = previousById[id]
            val name = playlist.name?.ifBlank { null } ?: "Untitled playlist"
            val ownerId = playlist.owner?.id
            val readable = playlist.collaborative == true || ownerId == null || me == null || ownerId == me
            RemotePlaylist(
                entity = SpotifyPlaylistEntity(
                    id = id,
                    name = name,
                    coverUrl = playlist.images?.firstOrNull()?.url,
                    songCount = (playlist.items?.total ?: playlist.tracks?.total) ?: previous?.songCount ?: 0,
                    lastSyncTime = previous?.lastSyncTime ?: 0L
                ),
                snapshotId = playlist.snapshotId,
                readable = readable,
                renamed = previous != null && previous.name != name
            )
        }
        playlists.filter { it.readable }.forEach { spotifyDao.insertPlaylist(it.entity) }

        // Playlists borradas en Spotify, o que esta app no puede leer (de otro usuario; un
        // import anterior las dejó vacías): fuera de la caché local y de sus canciones.
        val keepIds = playlists.filter { it.readable }.mapTo(HashSet()) { it.entity.id }
        previousById.values
            .filter {
                it.id != SpotifyPlaylistEntity.LIKED_SONGS_ID &&
                    it.id != SpotifyPlaylistEntity.BROWSE_ID && it.id !in keepIds
            }
            .forEach { stale ->
                spotifyDao.deleteSongsByPlaylist(stale.id)
                spotifyDao.deletePlaylist(stale.id)
                deleteAppPlaylistForSpotifyPlaylist(stale.id)
                importStore.forget(stale.id)
            }

        playlists
    }

    /**
     * Songs of one playlist, page by page at the largest page size Spotify allows (50).
     *
     * Each page is upserted as it arrives and handed to [onPage]; rows of songs the playlist
     * no longer contains are removed only after the last page. Failures are thrown as
     * [PlaylistSkipped] (this playlist cannot be read: skip it, keep going), [ImportAborted]
     * (stop the whole pass: login/rate limit) or a plain [IOException] (try again later).
     */
    suspend fun syncPlaylistSongs(
        playlistId: String,
        onPage: (suspend (List<SpotifySongEntity>) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (playlistId == SpotifyPlaylistEntity.LIKED_SONGS_ID) {
            return@withContext syncLikedSongs(onPage)
        }

        val keepIds = HashSet<String>()
        var offset = 0
        // Se apaga en cuanto el filtro `fields` demuestre ser el problema, y ya no se
        // vuelve a usar para el resto de páginas de esta playlist.
        var useFieldFilter = true
        var pagesFetched = 0
        var rejected = 0

        while (true) {
            val currentOffset = offset
            val pageSize = itemsPageSize
            val fields = if (useFieldFilter) SpotifyApiService.PLAYLIST_TRACK_FIELDS else null
            val result = executor.execute("playlist:$playlistId@$currentOffset") { auth ->
                api.getPlaylistTracks(auth, playlistId, limit = pageSize, offset = currentOffset, fields = fields)
            }

            if (result is SpotifyResult.BadRequest || result is SpotifyResult.ServerError) {
                // `fields` only saves bandwidth; if it is what breaks the call, take the whole
                // response rather than importing an empty playlist.
                if (useFieldFilter) {
                    Timber.w("Playlist $playlistId failed with `fields`; retrying without the filter")
                    useFieldFilter = false
                    continue
                }
                // Spotify's documented maximum has changed over time; step down once and remember.
                if (result is SpotifyResult.BadRequest && pageSize > FALLBACK_PAGE_SIZE) {
                    Timber.w("Playlist $playlistId rejected limit=$pageSize; falling back to $FALLBACK_PAGE_SIZE")
                    itemsPageSize = FALLBACK_PAGE_SIZE
                    continue
                }
            }

            val page = result.bodyOrThrow("Playlist $playlistId")
            pagesFetched++
            val items = page.items ?: throw IOException("Spotify playlist returned an incomplete page")

            val entities = ArrayList<SpotifySongEntity>(items.size)
            items.forEach { item ->
                val entity = toSpotifySongEntity(item.resolvedTrack, playlistId, item.addedAt)
                if (entity != null) entities += entity else rejected++
            }
            spotifyDao.upsertImportedSongs(entities)
            entities.mapTo(keepIds) { it.id }
            onPage?.invoke(entities)

            offset = nextSnapshotOffset(currentOffset, page.offset, page.limit, items.size, page.next, page.total)
                ?: break
        }

        // Una playlist que responde bien y aun así no aporta ni una pista es exactamente
        // el fallo que se está persiguiendo; conviene que se note en el log.
        if (keepIds.isEmpty()) {
            Timber.w(
                "Playlist $playlistId importada vacía " +
                    "(páginas=$pagesFetched, descartadas=$rejected, filtro=$useFieldFilter)"
            )
        }

        spotifyDao.removeSongsNotIn(playlistId, keepIds)
        keepIds.size
    }

    @Volatile
    private var itemsPageSize = TRACK_PAGE_SIZE

    // ─── Publicación progresiva a la biblioteca ─────────────────────────

    private val publishMutex = Mutex()
    private val pendingPublishIds = HashSet<String>()
    private val pendingMirrors = LinkedHashSet<String>()
    private var lastPublishAtMs = 0L

    /**
     * Makes freshly imported songs visible in the unified library without waiting for the
     * whole import. Throttled (the unified sync rebuilds its entity lists), but never loses
     * anything: skipped calls just accumulate their ids for the next flush or the final one.
     */
    private suspend fun publish(spotifyIds: Collection<String>, mirrorPlaylistId: String?, force: Boolean = false) {
        publishMutex.withLock {
            pendingPublishIds += spotifyIds
            if (mirrorPlaylistId != null) pendingMirrors += mirrorPlaylistId
            val now = System.currentTimeMillis()
            if (!force && now - lastPublishAtMs < PUBLISH_MIN_INTERVAL_MS) return@withLock
            flushPendingLocked()
        }
    }

    private suspend fun flushPendingLocked() {
        val ids = pendingPublishIds.toSet()
        val mirrors = pendingMirrors.toList()
        pendingPublishIds.clear()
        pendingMirrors.clear()
        lastPublishAtMs = System.currentTimeMillis()
        runCatching {
            if (ids.isNotEmpty()) syncUnifiedLibrarySongsFromSpotify(onlySpotifyIds = ids)
            mirrors.forEach { mirrorPlaylistsIntoApp(onlyPlaylistId = it) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.w(error, "No se pudo volcar el progreso parcial a la biblioteca")
        }
    }

    /** Progress of a running import, for the Active jobs list and the dashboard. */
    data class ImportProgress(
        val playlistsDone: Int,
        val playlistsTotal: Int,
        val currentName: String,
        val songsImported: Int,
        val skipped: Int
    )

    private val importMutex = Mutex()

    /**
     * Importación completa: perfil, "Me gusta" (primero), luego las playlists legibles, con
     * hasta [PLAYLIST_PARALLELISM] en vuelo a la vez bajo el mismo limitador de peticiones.
     *
     * - Lo importado se publica página a página y playlist a playlist.
     * - Una playlist que Spotify no entrega (403/404, o ajena) se **salta y se recuerda**; la
     *   pasada sigue. Nunca se aborta la importación por una playlist.
     * - Solo paran toda la pasada: 429 con espera larga ([BulkSyncResult.retryAfterSeconds]),
     *   un login muerto ([BulkSyncResult.needsReconnect]) o el presupuesto de tiempo
     *   ([shouldContinue]); lo ya importado se conserva y la siguiente pasada continúa.
     * - Es incremental y reanudable: una playlist cuyo `snapshot_id` no ha cambiado desde su
     *   último import completo no cuesta ninguna petición.
     */
    suspend fun syncAllPlaylistsAndSongs(
        shouldContinue: () -> Boolean = { true },
        onProgress: ((ImportProgress) -> Unit)? = null
    ): BulkSyncResult = withContext(Dispatchers.IO) {
        importMutex.withLock {
            // Se parte de cero cada pasada: si el usuario ya volvió a enlazar la cuenta, el
            // aviso tiene que desaparecer solo en cuanto la importación funcione.
            _reconnectRequired.value = false

            refreshProfile()
            if (_reconnectRequired.value) {
                return@withLock BulkSyncResult(0, 0, 0, isComplete = false, needsReconnect = true)
            }
            authManager.missingScopes(SpotifyAuthManager.IMPORT_SCOPES)
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    Timber.w("El token de Spotify no tiene los permisos $it; hay que reconectar")
                    _reconnectRequired.value = true
                }

            val synced = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val done = AtomicInteger(0)
            val skipped = ConcurrentLinkedQueue<SkippedPlaylist>()
            val processedIds = ConcurrentHashMap.newKeySet<String>()
            val abortRef = AtomicReference<ImportAborted?>(null)
            val complete = AtomicBoolean(true)
            var playlistsTotal = 1

            fun report(name: String) {
                onProgress?.invoke(
                    ImportProgress(done.get(), playlistsTotal, name, synced.get(), skipped.size)
                )
            }

            // 1) Me gusta, primero y publicado página a página.
            report("Liked Songs")
            try {
                var firstLikedPage = true
                val liked = syncLikedSongs { rows ->
                    publish(rows.map { it.spotifyId }, if (firstLikedPage) SpotifyPlaylistEntity.LIKED_SONGS_ID else null)
                    firstLikedPage = false
                    report("Liked Songs")
                }
                synced.addAndGet(liked)
                publish(emptyList(), SpotifyPlaylistEntity.LIKED_SONGS_ID, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportAborted) {
                abortRef.set(e)
            } catch (e: Exception) {
                Timber.w(e, "Fallo al importar Liked Songs")
                failed.incrementAndGet()
            }
            done.incrementAndGet()

            // 2) Lista de playlists.
            var remotes: List<RemotePlaylist> = emptyList()
            if (abortRef.get() == null) {
                try {
                    remotes = syncRemotePlaylists()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportAborted) {
                    abortRef.set(e)
                }
            }
            playlistsTotal = remotes.size + 1
            val libraryIds = remotes.mapTo(HashSet()) { it.entity.id }

            // Las ajenas no se piden: Spotify solo da el contenido de las propias o colaborativas.
            remotes.filter { !it.readable }.forEach {
                skipped += SkippedPlaylist(it.entity.id, it.entity.name, SkipReason.NOT_OWNED)
                processedIds += it.entity.id
                done.incrementAndGet()
            }

            // 3) Playlists legibles, con paralelismo acotado.
            val readable = remotes.filter { it.readable }
            if (abortRef.get() == null) {
                coroutineScope {
                    val gate = Semaphore(PLAYLIST_PARALLELISM)
                    readable.map { remote ->
                        async {
                            gate.withPermit {
                                if (abortRef.get() != null || !shouldContinue()) {
                                    complete.set(false)
                                    return@withPermit
                                }
                                val playlist = remote.entity
                                report(playlist.name)
                                try {
                                    synced.addAndGet(importOnePlaylist(remote))
                                    processedIds += playlist.id
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: ImportAborted) {
                                    abortRef.compareAndSet(null, e)
                                } catch (e: PlaylistSkipped) {
                                    Timber.w("Playlist ${playlist.name} omitida: ${e.message}")
                                    skipped += SkippedPlaylist(playlist.id, playlist.name, e.reason)
                                    processedIds += playlist.id
                                } catch (e: Exception) {
                                    Timber.w(e, "Fallo al importar la playlist ${playlist.name}")
                                    failed.incrementAndGet()
                                    skipped += SkippedPlaylist(playlist.id, playlist.name, SkipReason.FAILED)
                                    processedIds += playlist.id
                                    // Una racha de fallos es red o Spotify caídos: parar en vez de insistir.
                                    if (failed.get() >= MAX_FAILURES_BEFORE_STOP) {
                                        abortRef.compareAndSet(
                                            null,
                                            ImportAborted(null, false, "Too many playlists failed in a row")
                                        )
                                    }
                                }
                                done.incrementAndGet()
                                report(playlist.name)
                            }
                        }
                    }.awaitAll()
                }
            }

            val aborted = abortRef.get()
            if (aborted != null) complete.set(false)

            // 4) Cierre: volcado completo (quita lo borrado) y memoria de lo saltado.
            publishMutex.withLock { flushPendingLocked() }
            runCatching { syncUnifiedLibrarySongsFromSpotify() }
                .onFailure { if (it is CancellationException) throw it else Timber.w(it, "Volcado final fallido") }
            if (libraryIds.isNotEmpty() || aborted == null) {
                importStore.updateSkipped(processedIds, skipped.toList(), libraryIds)
            }

            BulkSyncResult(
                playlistCount = remotes.size + 1, // +1 por "Me gusta"
                syncedSongCount = synced.get(),
                failedPlaylistCount = failed.get(),
                isComplete = complete.get(),
                retryAfterSeconds = aborted?.retryAfterSeconds,
                needsReconnect = aborted?.needsReconnect == true,
                skippedPlaylistCount = skipped.size
            )
        }
    }

    /** @return songs now in the playlist (0 when untouched because its snapshot did not change). */
    private suspend fun importOnePlaylist(remote: RemotePlaylist): Int {
        val playlist = remote.entity
        val unchanged = remote.snapshotId != null &&
            playlist.lastSyncTime > 0L &&
            importStore.snapshotOf(playlist.id) == remote.snapshotId
        if (unchanged) {
            if (remote.renamed) publish(emptyList(), playlist.id)
            return 0
        }

        var firstPage = true
        val count = syncPlaylistSongs(playlist.id) { rows ->
            if (firstPage) {
                firstPage = false
                spotifyDao.insertPlaylist(playlist)
            }
            publish(rows.map { it.spotifyId }, null)
        }
        spotifyDao.insertPlaylist(playlist.copy(songCount = count, lastSyncTime = System.currentTimeMillis()))
        importStore.markSynced(playlist.id, remote.snapshotId)
        publish(emptyList(), playlist.id)
        return count
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
        // Spotify removed the batch `GET /artists` for development-mode apps (Feb 2026 migration);
        // once it has refused, stop asking instead of burning a request per 50 artists.
        if (artistIds.isEmpty() || batchArtistLookupUnavailable) return emptyMap()
        val result = HashMap<String, String>(artistIds.size)
        for (batch in artistIds.chunked(50)) {
            val response = executor.execute("artists") { auth -> api.getArtists(auth, batch.joinToString(",")) }
            when (response) {
                is SpotifyResult.Ok -> response.body.artists.orEmpty().forEach { artist ->
                    val id = artist?.id ?: return@forEach
                    val primaryGenre = artist.genres?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                    result[id] = primaryGenre
                }
                is SpotifyResult.Forbidden, is SpotifyResult.NotFound, is SpotifyResult.BadRequest -> {
                    Timber.w("Batch artist lookup is not available to this app; genres will stay empty")
                    batchArtistLookupUnavailable = true
                    break
                }
                else -> Unit
            }
        }
        return result
    }

    @Volatile
    private var batchArtistLookupUnavailable = false

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

    // ─── Listening history (data.history) ──────────────────────────────
    // These return the full SpotifyResult so the history sync can tell a missing scope (403) from
    // a network failure; all of them go through the shared executor and its rate limiter.

    suspend fun fetchRecentlyPlayed(afterMs: Long? = null): SpotifyResult<com.theveloper.pixelplay.data.network.spotify.SpotifyRecentlyPlayedPage> =
        withContext(Dispatchers.IO) {
            executor.execute("recently-played") { auth -> api.getRecentlyPlayed(auth, after = afterMs) }
        }

    suspend fun fetchTopTracks(timeRange: String): SpotifyResult<com.theveloper.pixelplay.data.network.spotify.SpotifyTracksListPage> =
        withContext(Dispatchers.IO) {
            executor.execute("top-tracks") { auth -> api.getMyTopTracks(auth, timeRange) }
        }

    suspend fun fetchTopArtists(timeRange: String): SpotifyResult<com.theveloper.pixelplay.data.network.spotify.SpotifyArtistsPage> =
        withContext(Dispatchers.IO) {
            executor.execute("top-artists") { auth -> api.getMyTopArtists(auth, timeRange) }
        }

    suspend fun fetchFollowedArtists(after: String? = null): SpotifyResult<com.theveloper.pixelplay.data.network.spotify.SpotifyFollowedArtistsResponse> =
        withContext(Dispatchers.IO) {
            executor.execute("followed-artists") { auth -> api.getFollowedArtists(auth, after = after) }
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

        // Importing adds rows to the browse playlist only: write just these songs (and the albums /
        // artists they touch, with counts over the whole mirror) and mirror just that playlist,
        // instead of rewriting every mirrored song and playlist on a single tap.
        syncUnifiedLibrarySongsFromSpotify(onlySpotifyIds = entities.mapTo(HashSet()) { it.spotifyId })
        mirrorPlaylistsIntoApp(onlyPlaylistId = SpotifyPlaylistEntity.BROWSE_ID)

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
    suspend fun syncUnifiedLibrarySongsFromSpotify(
        onlySpotifyIds: Set<String>? = null
    ) = withContext(Dispatchers.IO) {
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

        if (onlySpotifyIds != null) {
            // Partial write: nothing is deleted here (removals use the full path).
            val touchedSongIds = onlySpotifyIds.mapTo(HashSet()) { unifiedId(SONG_ID_OFFSET, it) }
            val touchedSongs = songs.filter { it.id in touchedSongIds }
            val touchedRefs = crossRefs.filter { it.songId in touchedSongIds }
            val touchedAlbumIds = touchedSongs.mapTo(HashSet()) { it.albumId }
            val touchedArtistIds = touchedRefs.mapTo(HashSet()) { it.artistId }
            musicDao.incrementalSyncMusicData(
                songs = touchedSongs,
                albums = albumsWithCounts.filter { it.id in touchedAlbumIds },
                artists = artistsWithCounts.filter { it.id in touchedArtistIds },
                crossRefs = touchedRefs,
                deletedSongIds = emptyList()
            )
            return@withContext
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
    private suspend fun mirrorPlaylistsIntoApp(onlyPlaylistId: String? = null) {
        // Una sola lectura; para una única playlist, solo sus filas en vez de toda la tabla.
        val songsByPlaylist = if (onlyPlaylistId != null) {
            mapOf(onlyPlaylistId to spotifyDao.getSongsByPlaylistList(onlyPlaylistId))
        } else {
            spotifyDao.getAllSpotifySongsList().groupBy { it.playlistId }
        }

        spotifyDao.getAllPlaylistsList()
            .filter { onlyPlaylistId == null || it.id == onlyPlaylistId }
            .forEach { playlist ->
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
        _reconnectRequired.value = false
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        spotifyDao.getAllPlaylistsList().forEach { deleteAppPlaylistForSpotifyPlaylist(it.id) }
        spotifyDao.clearAllSongs()
        spotifyDao.clearAllPlaylists()
        musicDao.clearAllSpotifySongs()
        importStore.clear()
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

        /** Spotify's documented maximum for `/me/tracks`, `/me/playlists` and playlist items. */
        private const val LIKED_PAGE_SIZE = 50
        private const val PLAYLIST_PAGE_SIZE = 50
        private const val TRACK_PAGE_SIZE = 50
        private const val FALLBACK_PAGE_SIZE = 20

        /** Playlists downloaded at the same time (all under the one shared rate limiter). */
        private const val PLAYLIST_PARALLELISM = 3
        private const val MAX_FAILURES_BEFORE_STOP = 8

        /** The unified-library flush rebuilds entity lists, so at most one per this interval. */
        private const val PUBLISH_MIN_INTERVAL_MS = 1_500L
        private const val MATCH_STATE_SAMPLE_MS = 1_000L
        private const val MATCHER_KICK_INTERVAL_MS = 30_000L

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

/** This playlist cannot be imported (403/404): skip it, remember why, keep importing the rest. */
internal class PlaylistSkipped(val reason: SkipReason, message: String) : IOException(message)

/** The whole pass must stop: dead login, or Spotify rate limiting for longer than is worth waiting. */
internal class ImportAborted(
    val retryAfterSeconds: Long?,
    val needsReconnect: Boolean,
    message: String
) : IOException(message)
