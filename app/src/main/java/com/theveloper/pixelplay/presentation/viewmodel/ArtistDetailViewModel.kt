package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.YouTubeMusicTrack
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Holds the full UI state for ArtistDetailScreen.
 *
 * [effectiveImageUrl] is the resolved image to display (custom takes priority over Deezer).
 * It is updated after artist data loads and again whenever the user changes the custom image.
 */
data class ArtistDetailUiState(
    val artist: Artist? = null,
    val songs: List<Song> = emptyList(),
    val albumSections: List<ArtistAlbumSection> = emptyList(),
    /**
     * Las canciones de este artista que más se han escuchado, de más a menos.
     *
     * Vacía hasta que haya algo que contar: una sección "lo más escuchado" que en realidad
     * enseña las primeras canciones por orden alfabético miente, así que si no hay
     * reproducciones registradas no se muestra.
     */
    val topSongs: List<Song> = emptyList(),
    val effectiveImageUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * Álbumes de este artista que no están en la biblioteca, agrupados a partir de una
     * búsqueda en YouTube Music. No es una discografía oficial (InnerTube no expone la
     * página de artista, sólo búsqueda) — es una aproximación por relevancia de búsqueda,
     * agrupando resultados por el álbum que YouTube Music les asigna en el propio texto de
     * resultado.
     */
    val moreFromArtist: List<ArtistYouTubeMusicAlbumSection> = emptyList(),
    val isLoadingMoreFromArtist: Boolean = false
)

@Immutable
data class ArtistYouTubeMusicAlbumSection(
    val title: String,
    val tracks: List<YouTubeMusicTrack>
)

@Immutable
data class ArtistAlbumSection(
    val albumId: Long,
    val title: String,
    val year: Int?,
    val albumArtUriString: String?,
    val songs: List<Song>
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val artistImageRepository: ArtistImageRepository,
    private val engagementDao: com.theveloper.pixelplay.data.database.EngagementDao,
    private val innerTubeClient: InnerTubeClient,
    private val spotifyRepository: SpotifyRepository,
    val themeStateHolder: ThemeStateHolder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Los resultados de YouTube Music tal y como los devolvió InnerTube, por videoId. */
    private val youtubeMusicResultsByVideoId = ConcurrentHashMap<String, YouTubeSearchResult>()

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    /**
     * Pre-warmed color scheme for the current artist image.
     * This is populated synchronously (from the processor's LRU/DB cache) before [uiState]
     * marks [ArtistDetailUiState.isLoading] = false, so the screen has the correct palette
     * on its very first composition — no flash from system colors.
     *
     * Consumers should read this directly instead of calling [ThemeStateHolder.getAlbumColorSchemeFlow]
     * in order to avoid the initial-null-emission that causes the flash.
     */
    private val _artistColorScheme = MutableStateFlow<ColorSchemePair?>(null)
    val artistColorScheme: StateFlow<ColorSchemePair?> = _artistColorScheme.asStateFlow()

    init {
        savedStateHandle.getStateFlow<String?>("artistId", null)
            .onEach { idString ->
                if (idString != null) {
                    val artistId = idString.toLongOrNull()
                    if (artistId != null) {
                        loadArtistData(artistId)
                    } else {
                        _uiState.update { it.copy(error = context.getString(R.string.artist_detail_invalid_id), isLoading = false) }
                    }
                } else {
                    _uiState.update { it.copy(error = context.getString(R.string.artist_detail_id_not_found), isLoading = false) }
                }
            }
            .launchIn(viewModelScope)
    }

    private var currentLoadJob: Job? = null

    private fun loadArtistData(id: Long) {
        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            Log.d("ArtistDebug", "loadArtistData: id=$id")
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val artistDetailsFlow = musicRepository.getArtistById(id)
                val artistSongsFlow = musicRepository.getSongsForArtist(id)

                combine(artistDetailsFlow, artistSongsFlow) { artist, songs ->
                    Log.d("ArtistDebug", "loadArtistData: id=$id found=${artist != null} songs=${songs.size}")
                    artist to songs
                }
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                error = context.getString(R.string.artist_error_loading_artist, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        }
                    }
                    .collect { (artist, songs) ->
                        if (artist == null) {
                            _uiState.update {
                                it.copy(error = context.getString(R.string.artist_detail_not_found), isLoading = false)
                            }
                            return@collect
                        }

                        val albumSections = buildAlbumSections(songs)
                        val orderedSongs = albumSections.flatMap { it.songs }
                        val topSongs = loadTopSongs(orderedSongs)

                        // 1) Resolve effective image URL (custom > Deezer, may fetch from API)
                        val effectiveUrl = try {
                            artistImageRepository.getEffectiveArtistImageUrl(
                                artistId = artist.id,
                                artistName = artist.name
                            )
                        } catch (e: Exception) {
                            Log.w("ArtistDebug", "Failed to resolve effective artist image: ${e.message}")
                            artist.effectiveImageUrl
                        }

                        // 2) Pre-warm the color scheme BEFORE emitting isLoading = false.
                        //    getOrGenerateColorScheme checks the in-memory LRU first (≈0 ms if cached),
                        //    then the DB cache (fast), and only generates from scratch ~on first visit.
                        //    Either way, the scheme is ready before the screen first renders.
                        val newScheme = if (!effectiveUrl.isNullOrBlank()) {
                            try {
                                themeStateHolder.getOrGenerateColorScheme(effectiveUrl)
                            } catch (e: Exception) {
                                Log.w("ArtistDebug", "Color scheme pre-warm failed: ${e.message}")
                                null
                            }
                        } else null

                        // 3) Atomically publish state + pre-warmed color scheme.
                        //    Both flows update before the Compose frame runs, so no intermediate null frame.
                        _artistColorScheme.value = newScheme
                        _uiState.value = ArtistDetailUiState(
                            artist = artist.copy(
                                imageUrl = if (artist.customImageUri.isNullOrBlank()) effectiveUrl else artist.imageUrl
                            ),
                            songs = orderedSongs,
                            albumSections = albumSections,
                            topSongs = topSongs,
                            effectiveImageUrl = effectiveUrl,
                            isLoading = false
                        )
                        loadMoreFromArtist(artist.name, orderedSongs)
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.artist_error_loading_artist, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Las canciones del artista ordenadas por número de reproducciones.
     *
     * La consulta se trocea porque SQLite limita cuántos valores admite un `IN (...)`; un
     * artista con cientos de canciones haría fallar la consulta entera, y perder la
     * sección por eso sería absurdo. Si algo falla se devuelve vacío: es una sección
     * opcional, no puede tumbar la pantalla.
     */
    private companion object {
        const val TOP_SONGS_LIMIT = 5

        /** Por debajo del límite histórico de 999 parámetros por consulta de SQLite. */
        const val SQLITE_VARIABLE_CHUNK = 900

        const val MORE_FROM_ARTIST_LIMIT = 25
    }

    private suspend fun loadTopSongs(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()
        return runCatching {
            val byId = songs.associateBy { it.id }
            songs.map { it.id }
                .chunked(SQLITE_VARIABLE_CHUNK)
                .flatMap { chunk -> engagementDao.getTopPlayedAmong(chunk, TOP_SONGS_LIMIT) }
                .sortedByDescending { it.playCount }
                .take(TOP_SONGS_LIMIT)
                .mapNotNull { byId[it.songId] }
        }.getOrElse {
            Log.w("ArtistDebug", "Could not load play counts: ${it.message}")
            emptyList()
        }
    }

    /**
     * Trae más canciones de este artista buscando en YouTube Music, para la sección "More
     * from [artist]" — cubre lo que no está en la biblioteca local. Se filtran los títulos
     * que ya aparecen entre las canciones locales (comparación simple por título, sin
     * normalizar demasiado: prefiere pecar de dejar algún duplicado suelto a esconder una
     * canción de verdad distinta con un título parecido).
     */
    private fun loadMoreFromArtist(artistName: String, localSongs: List<Song>) {
        if (artistName.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreFromArtist = true) }
            try {
                val localTitles = localSongs.map { it.title.trim().lowercase() }.toSet()
                val results = withContext(Dispatchers.IO) {
                    innerTubeClient.searchSongs(artistName, limit = MORE_FROM_ARTIST_LIMIT)
                }
                val fresh = results.filter { it.title.trim().lowercase() !in localTitles }

                youtubeMusicResultsByVideoId.clear()
                fresh.forEach { youtubeMusicResultsByVideoId[it.videoId] = it }

                val sections = fresh
                    .groupBy { it.album?.ifBlank { null } ?: "Singles" }
                    .map { (album, tracks) ->
                        ArtistYouTubeMusicAlbumSection(
                            title = album,
                            tracks = tracks.map { it.toYouTubeMusicTrack() }
                        )
                    }

                _uiState.update { it.copy(moreFromArtist = sections, isLoadingMoreFromArtist = false) }
            } catch (e: Exception) {
                Timber.w(e, "Couldn't load more from artist '%s' via YouTube Music", artistName)
                _uiState.update { it.copy(isLoadingMoreFromArtist = false) }
            }
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
     * Trae una pista de la sección "More from [artist]" a la biblioteca y la reproduce —
     * mismo mecanismo que [SearchStateHolder.playYouTubeMusicTrack]: el `videoId` ya se
     * conoce, así que no hace falta emparejador.
     *
     * @return el [Song] ya reproducible, o null si no se pudo importar.
     */
    suspend fun playYouTubeMusicTrack(videoId: String): Song? {
        val result = youtubeMusicResultsByVideoId[videoId] ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                spotifyRepository.importYouTubeMusicTracks(listOf(result))
                val syntheticId = spotifyRepository.youTubeMusicSyntheticId(videoId)
                val unifiedId = SpotifyRepository.unifiedSongId(syntheticId)
                musicRepository.getSong(unifiedId.toString()).first()
            }.onFailure {
                Timber.w(it, "No se pudo reproducir de inmediato la pista de YouTube Music $videoId")
            }.getOrNull()
        }
    }

    /**
     * Called from the UI when the user selects a custom image from the system photo picker.
     * Copies the image to internal storage, persists the path to DB, and triggers palette regeneration.
     */
    fun setCustomImage(sourceUri: Uri) {
        val artistId = _uiState.value.artist?.id ?: return
        viewModelScope.launch {
            try {
                val internalPath = artistImageRepository.setCustomArtistImage(context, artistId, sourceUri)
                if (!internalPath.isNullOrBlank()) {
                    val oldEffectiveUrl = _uiState.value.effectiveImageUrl

                    // Regenerate palette from the new image url — invalidate old and warm-up new
                    if (!oldEffectiveUrl.isNullOrBlank() && oldEffectiveUrl != internalPath) {
                        themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                    }
                    val newScheme = try {
                        themeStateHolder.forceRegenerateColorScheme(internalPath)
                        themeStateHolder.getOrGenerateColorScheme(internalPath)
                    } catch (e: Exception) {
                        Log.w("ArtistDebug", "Failed to regenerate color scheme for custom image: ${e.message}")
                        null
                    }

                    _artistColorScheme.value = newScheme
                    _uiState.update { state ->
                        // Cache-busting: add timestamp to internalPath to force Coil to reload
                        val effectiveUrlWithBust = "$internalPath?t=${System.currentTimeMillis()}"
                        state.copy(
                            effectiveImageUrl = effectiveUrlWithBust,
                            artist = state.artist?.copy(customImageUri = internalPath)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ArtistDebug", "Failed to set custom image: ${e.message}")
            }
        }
    }

    /**
     * Called when the user wants to revert to the Deezer-sourced image.
     */
    fun clearCustomImage() {
        val artist = _uiState.value.artist ?: return
        viewModelScope.launch {
            try {
                val oldEffectiveUrl = _uiState.value.effectiveImageUrl
                artistImageRepository.clearCustomArtistImage(context, artist.id)

                // Fall back to Deezer URL
                val deezerUrl = artistImageRepository.getArtistImageUrl(artist.name, artist.id)
                val newEffectiveUrl = deezerUrl.takeIf { !it.isNullOrBlank() }

                // Invalidate old custom image palette
                if (!oldEffectiveUrl.isNullOrBlank()) {
                    themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                }

                val newScheme = if (!newEffectiveUrl.isNullOrBlank()) {
                    try {
                        themeStateHolder.getOrGenerateColorScheme(newEffectiveUrl)
                    } catch (e: Exception) {
                        Log.w("ArtistDebug", "Failed to regenerate palette after clear: ${e.message}")
                        null
                    }
                } else null

                _artistColorScheme.value = newScheme
                _uiState.update { state ->
                    state.copy(
                        effectiveImageUrl = newEffectiveUrl,
                        artist = state.artist?.copy(customImageUri = null, imageUrl = deezerUrl)
                    )
                }

            } catch (e: Exception) {
                Log.e("ArtistDebug", "Failed to clear custom image: ${e.message}")
            }
        }
    }

    fun removeSongFromAlbumSection(songId: String) {
        _uiState.update { currentState ->
            val updatedAlbumSections = currentState.albumSections.map { section ->
                val updatedSongs = section.songs.filterNot { it.id == songId }
                section.copy(songs = updatedSongs)
            }.filter { it.songs.isNotEmpty() }

            currentState.copy(
                albumSections = updatedAlbumSections,
                songs = currentState.songs.filterNot { it.id == songId }
            )
        }
    }
}

private val songDisplayComparator = compareBy<Song> { it.discNumber ?: 1 }
    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
    .thenBy { it.title.lowercase() }

private fun buildAlbumSections(songs: List<Song>): List<ArtistAlbumSection> {
    if (songs.isEmpty()) return emptyList()

    val sections = songs
        .groupBy { it.albumId to it.album }
        .map { (key, albumSongs) ->
            val sortedSongs = albumSongs.sortedWith(songDisplayComparator)
            val albumYear = albumSongs.mapNotNull { song -> song.year.takeIf { it > 0 } }.maxOrNull()
            val albumArtUri = albumSongs.firstNotNullOfOrNull { it.albumArtUriString }
            ArtistAlbumSection(
                albumId = key.first,
                title = (key.second.takeIf { it.isNotBlank() } ?: "Unknown Album"),
                year = albumYear,
                albumArtUriString = albumArtUri,
                songs = sortedSongs
            )
        }

    val (withYear, withoutYear) = sections.partition { it.year != null }
    val withYearSorted = withYear.sortedWith(
        compareByDescending<ArtistAlbumSection> { it.year ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase() }
    )
    val withoutYearSorted = withoutYear.sortedBy { it.title.lowercase() }

    return withYearSorted + withoutYearSorted
}
