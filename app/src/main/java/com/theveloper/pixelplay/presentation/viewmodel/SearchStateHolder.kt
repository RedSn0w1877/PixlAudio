package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.model.CatalogTrack
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.YouTubeMusicTrack
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.worker.SpotifyMatchWorker
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val spotifyRepository: SpotifyRepository,
    private val spotifyDao: SpotifyDao,
    private val trackMatcher: TrackMatcher,
    private val innerTubeClient: InnerTubeClient,
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        // Suficiente para no lanzar una consulta por tecla, pero corto: ahora que las
        // consultas de álbum y artista van limitadas, esperar 300 ms era la mayor parte
        // del retardo que se notaba al escribir.
        const val SEARCH_DEBOUNCE_MS = 160L

        /**
         * Más largo que el de la biblioteca a propósito: cada disparo es una petición de
         * red contra Spotify, y no hace falta lanzar una por cada tecla mientras se sigue
         * escribiendo.
         */
        const val CATALOG_DEBOUNCE_MS = 420L
        const val MIN_CATALOG_QUERY_LENGTH = 2
        const val CATALOG_RESULT_LIMIT = 20

        /** Same cost class as the Spotify catalog search (one plain HTTP request); reuse its debounce. */
        const val YOUTUBE_MUSIC_DEBOUNCE_MS = 420L
        const val MIN_YOUTUBE_MUSIC_QUERY_LENGTH = 2
        const val YOUTUBE_MUSIC_RESULT_LIMIT = 20
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    /**
     * Resultados del catálogo de Spotify, aparte de los de la biblioteca.
     *
     * Van en su propio flujo y no mezclados en [_searchResults] porque llegan por red y
     * tardan bastante más: mezclarlos obligaría a esperar a Spotify para poder enseñar lo
     * que ya está en el dispositivo, y buscar en tu propia biblioteca dejaría de ser
     * instantáneo. Así la biblioteca aparece de inmediato y el catálogo se añade debajo
     * cuando llega.
     */
    private val _catalogResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val catalogResults = _catalogResults.asStateFlow()

    private val _isCatalogSearching = MutableStateFlow(false)
    val isCatalogSearching = _isCatalogSearching.asStateFlow()

    /**
     * Las pistas del catálogo tal y como las devolvió Spotify, por id.
     *
     * La UI sólo maneja [CatalogTrack]; para importar hace falta el DTO original, así que
     * se guarda aquí en vez de arrastrarlo por toda la capa de presentación.
     */
    private val catalogTracksById = ConcurrentHashMap<String, SpotifyTrack>()

    /**
     * Resultados de buscar directamente en YouTube Music, aparte de biblioteca y catálogo de
     * Spotify — mismo motivo que [_catalogResults]: es otra llamada de red, y no debe frenar
     * lo que ya se puede enseñar al instante.
     */
    private val _youtubeMusicResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val youtubeMusicResults = _youtubeMusicResults.asStateFlow()

    private val _isYoutubeMusicSearching = MutableStateFlow(false)
    val isYoutubeMusicSearching = _isYoutubeMusicSearching.asStateFlow()

    /** Los resultados de YouTube Music tal y como los devolvió InnerTube, por videoId. */
    private val youtubeMusicResultsByVideoId = ConcurrentHashMap<String, YouTubeSearchResult>()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private var catalogJob: Job? = null
    private var youtubeMusicJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
        observeCatalogRequests()
        observeYoutubeMusicRequests()
    }

    /**
     * Busca directamente en YouTube Music, aparte de biblioteca y catálogo de Spotify — un
     * tercer flujo paralelo con la misma estructura que [observeCatalogRequests], igual de
     * barato (una petición HTTP normal, sin PoToken ni WebView, ver
     * [InnerTubeClient.searchSongs]).
     */
    @OptIn(FlowPreview::class)
    private fun observeYoutubeMusicRequests() {
        youtubeMusicJob?.cancel()
        youtubeMusicJob = scope?.launch {
            searchRequests
                .debounce(YOUTUBE_MUSIC_DEBOUNCE_MS)
                .collectLatest { request ->
                    val query = request.query
                    if (query.length < MIN_YOUTUBE_MUSIC_QUERY_LENGTH) {
                        _isYoutubeMusicSearching.value = false
                        if (_youtubeMusicResults.value.isNotEmpty()) {
                            _youtubeMusicResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    _isYoutubeMusicSearching.value = true
                    try {
                        val results = withContext(Dispatchers.IO) {
                            innerTubeClient.searchMusic(query, limit = YOUTUBE_MUSIC_RESULT_LIMIT)
                        }
                        if (request.requestId != latestSearchRequestId.get()) return@collectLatest

                        youtubeMusicResultsByVideoId.clear()
                        results.forEach { youtubeMusicResultsByVideoId[it.videoId] = it }

                        _youtubeMusicResults.value = results
                            .map { SearchResultItem.YouTubeMusicItem(it.toYouTubeMusicTrack()) }
                            .toImmutableList()
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        Timber.w(e, "YouTube Music search failed for: $query")
                        if (request.requestId == latestSearchRequestId.get()) {
                            _youtubeMusicResults.value = persistentListOf()
                        }
                    } finally {
                        if (request.requestId == latestSearchRequestId.get()) {
                            _isYoutubeMusicSearching.value = false
                        }
                    }
                }
        }
    }

    /**
     * Trae una pista encontrada en YouTube Music a la biblioteca y la reproduce — no hace
     * falta emparejador aquí (a diferencia de [playCatalogTrack]): el `videoId` ya se sabe
     * desde la propia búsqueda.
     *
     * @return el [Song] ya reproducible, o null si no se pudo importar.
     */
    suspend fun playYouTubeMusicTrack(videoId: String): Song? {
        val result = youtubeMusicResultsByVideoId[videoId] ?: return null
        return withContext(Dispatchers.IO) {
            val song = runCatching {
                spotifyRepository.importYouTubeMusicTracks(listOf(result))
                val syntheticId = spotifyRepository.youTubeMusicSyntheticId(videoId)
                val unifiedId = SpotifyRepository.unifiedSongId(syntheticId)
                musicRepository.getSong(unifiedId.toString()).first()
            }.onFailure {
                Timber.w(it, "No se pudo reproducir de inmediato la pista de YouTube Music $videoId")
            }.getOrNull()

            _youtubeMusicResults.update { current ->
                current.filterNot {
                    it is SearchResultItem.YouTubeMusicItem && it.track.videoId == videoId
                }.toImmutableList()
            }

            song
        }
    }

    private fun YouTubeSearchResult.toYouTubeMusicTrack(): YouTubeMusicTrack = YouTubeMusicTrack(
        videoId = videoId,
        title = title,
        artist = artist.ifBlank { "Unknown Artist" },
        album = album,
        thumbnailUrl = thumbnailUrl,
        durationMs = (durationSeconds?.toLong() ?: 0L) * 1000L
    )

    /**
     * Busca en el catálogo de Spotify lo que la biblioteca no tiene.
     *
     * Se filtran las pistas ya importadas: la sección se llama "no está en tu biblioteca"
     * y enseñar ahí algo que el usuario ya tiene sólo genera duplicados al tocarlo.
     */
    @OptIn(FlowPreview::class)
    private fun observeCatalogRequests() {
        catalogJob?.cancel()
        catalogJob = scope?.launch {
            searchRequests
                .debounce(CATALOG_DEBOUNCE_MS)
                .collectLatest { request ->
                    val query = request.query
                    if (query.length < MIN_CATALOG_QUERY_LENGTH) {
                        _isCatalogSearching.value = false
                        if (_catalogResults.value.isNotEmpty()) {
                            _catalogResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    _isCatalogSearching.value = true
                    try {
                        val results = withContext(Dispatchers.IO) {
                            // Sólo pistas: esta sección lista canciones, y pedir un único
                            // tipo permite además traer más de ellas dentro del tope que
                            // Spotify impone al total de la respuesta.
                            spotifyRepository.searchCatalog(
                                query = query,
                                types = "track",
                                limit = CATALOG_RESULT_LIMIT
                            )
                        }
                        if (request.requestId != latestSearchRequestId.get()) return@collectLatest

                        val alreadyOwned = withContext(Dispatchers.IO) {
                            spotifyRepository.knownSpotifyIds()
                        }
                        val fresh = results.tracks
                            .asSequence()
                            .filter { !it.id.isNullOrBlank() && it.id !in alreadyOwned }
                            .take(CATALOG_RESULT_LIMIT)
                            .toList()

                        catalogTracksById.clear()
                        fresh.forEach { catalogTracksById[it.id!!] = it }

                        if (request.requestId != latestSearchRequestId.get()) return@collectLatest
                        _catalogResults.value = fresh
                            .map { SearchResultItem.CatalogItem(it.toCatalogTrack()) }
                            .toImmutableList()
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        Timber.w(e, "Catalogue search failed for: $query")
                        if (request.requestId == latestSearchRequestId.get()) {
                            _catalogResults.value = persistentListOf()
                        }
                    } finally {
                        if (request.requestId == latestSearchRequestId.get()) {
                            _isCatalogSearching.value = false
                        }
                    }
                }
        }
    }

    /**
     * Trae la pista a la biblioteca, la marca como favorita y arranca la búsqueda de su
     * audio — "que te guste" es lo único que hace falta para traerla: no hay un paso de
     * "añadir" aparte, y quitarla es tan simple como quitarle el "me gusta" (ver
     * [PlayerViewModel.toggleFavoriteSpecificSong], que borra de la biblioteca en vez de
     * sólo desmarcar cuando la pista sólo está aquí por haberla traído desde el catálogo).
     *
     * Sonar no es inmediato: Spotify sólo entrega metadatos, así que hasta que el
     * emparejador no encuentra el vídeo correspondiente no hay nada que reproducir.
     *
     * @return true si se pudo lanzar la importación.
     */
    fun likeCatalogTrack(spotifyId: String, onDone: (Boolean) -> Unit = {}) {
        val track = catalogTracksById[spotifyId]
        if (track == null) {
            onDone(false)
            return
        }
        scope?.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    spotifyRepository.importTracks(listOf(track))
                    val unifiedId = SpotifyRepository.unifiedSongId(spotifyId)
                    musicRepository.setFavoriteStatus(unifiedId.toString(), true)
                }
            }.isSuccess
            if (ok) SpotifyMatchWorker.enqueue(context, retryFailed = false)
            // Ya está en la biblioteca, así que deja de pertenecer a "lo que no tienes".
            _catalogResults.update { current ->
                current.filterNot {
                    it is SearchResultItem.CatalogItem && it.track.spotifyId == spotifyId
                }.toImmutableList()
            }
            onDone(ok)
        }
    }

    /**
     * Igual que [likeCatalogTrack] pero para "tócala y suena ya": importa la pista y la
     * empareja aquí mismo en vez de dejarlo para el lote de [SpotifyMatchWorker], porque el
     * usuario está esperando a que suene, no a que le llegue una notificación luego.
     *
     * @return el [Song] ya reproducible, o null si no se encontró vídeo emparejable.
     */
    suspend fun playCatalogTrack(spotifyId: String): Song? {
        val track = catalogTracksById[spotifyId] ?: return null
        return withContext(Dispatchers.IO) {
            val song = runCatching {
                spotifyRepository.importTracks(listOf(track))
                val unifiedId = SpotifyRepository.unifiedSongId(spotifyId)
                musicRepository.setFavoriteStatus(unifiedId.toString(), true)

                val entity = spotifyDao.getSongBySpotifyId(spotifyId)
                val match = entity?.let { trackMatcher.findMatch(it) }
                spotifyDao.updateMatch(
                    spotifyId = spotifyId,
                    videoId = match?.videoId,
                    score = match?.score,
                    state = if (match != null) SpotifyMatchState.MATCHED else SpotifyMatchState.UNMATCHED
                )

                if (match == null) null else musicRepository.getSong(unifiedId.toString()).first()
            }.onFailure {
                Timber.w(it, "No se pudo reproducir de inmediato la pista de catálogo $spotifyId")
            }.getOrNull()

            // Ya está en la biblioteca (con o sin match), así que deja de pertenecer a "lo
            // que no tienes" — mismo criterio que likeCatalogTrack.
            _catalogResults.update { current ->
                current.filterNot {
                    it is SearchResultItem.CatalogItem && it.track.spotifyId == spotifyId
                }.toImmutableList()
            }

            song
        }
    }

    private fun SpotifyTrack.toCatalogTrack(): CatalogTrack = CatalogTrack(
        spotifyId = id.orEmpty(),
        title = name?.ifBlank { null } ?: "Unknown title",
        artist = artists.orEmpty().mapNotNull { it.name?.ifBlank { null } }
            .joinToString(", ").ifBlank { "Unknown Artist" },
        album = album?.name?.ifBlank { null } ?: "Unknown Album",
        albumArtUrl = album?.images?.firstOrNull()?.url,
        durationMs = durationMs ?: 0L
    )

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value
                        musicRepository.searchAll(normalizedQuery, currentFilter).collect { resultsList ->
                            // Sort: prioritize Song/Album matches over Artist/Playlist matches
                            val sortedResults = resultsList.sortedWith(
                                compareBy { result ->
                                    when (result) {
                                        is SearchResultItem.SongItem -> 0
                                        is SearchResultItem.AlbumItem -> 1
                                        is SearchResultItem.ArtistItem -> 2
                                        is SearchResultItem.PlaylistItem -> 3
                                        // Nunca llega por aquí (el catálogo y YouTube Music
                                        // van en sus propios flujos), pero el `when` es
                                        // exhaustivo.
                                        is SearchResultItem.CatalogItem -> 4
                                        is SearchResultItem.YouTubeMusicItem -> 5
                                    }
                                }
                            )

                            if (request.requestId != latestSearchRequestId.get()) {
                                return@collect
                            }

                            val immutableResults = sortedResults.toImmutableList()
                            if (_searchResults.value != immutableResults) {
                                _searchResults.value = immutableResults
                            }
                        }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            _searchResults.value = persistentListOf()
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId))
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
