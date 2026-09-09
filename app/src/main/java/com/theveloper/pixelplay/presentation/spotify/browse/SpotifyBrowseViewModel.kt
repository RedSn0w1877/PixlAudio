package com.theveloper.pixelplay.presentation.spotify.browse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.network.spotify.SpotifyAlbumFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyArtistFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.worker.SpotifyMatchWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Dónde está el usuario dentro de la pantalla de exploración. */
sealed interface BrowseLevel {
    data object Home : BrowseLevel
    data object Results : BrowseLevel
    data class Artist(val artist: SpotifyArtistFull) : BrowseLevel
    data class Album(val album: SpotifyAlbumFull) : BrowseLevel
}

data class SpotifyBrowseUiState(
    val query: String = "",
    val level: BrowseLevel = BrowseLevel.Home,
    val isLoading: Boolean = false,
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtistFull> = emptyList(),
    val albums: List<SpotifyAlbumFull> = emptyList(),
    /** Discos del artista abierto (distinto de [albums], que son los de la búsqueda). */
    val artistAlbums: List<SpotifyAlbumFull> = emptyList(),
    val topArtists: List<SpotifyArtistFull> = emptyList(),
    val message: String? = null,
    val topReadDenied: Boolean = false
)

/**
 * Explorador del catálogo de Spotify.
 *
 * La navegación (búsqueda → artista → álbum) es estado interno y no rutas: son tres vistas
 * del mismo flujo y separarlas obligaría a re-pedir por red al volver atrás.
 */
@HiltViewModel
class SpotifyBrowseViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: SpotifyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotifyBrowseUiState())
    val uiState: StateFlow<SpotifyBrowseUiState> = _uiState.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedIn

    private var searchJob: Job? = null

    init {
        loadHome()
    }

    // ─── Inicio: lo más escuchado ──────────────────────────────────────

    fun loadHome() {
        viewModelScope.launch {
            if (!repository.isLoggedIn.value) return@launch
            _uiState.update { it.copy(isLoading = true) }

            val topTracks = runCatching { repository.getMyTopTracks() }.getOrElse { emptyList() }
            val topArtists = runCatching { repository.getMyTopArtists() }.getOrElse { emptyList() }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    tracks = if (it.level == BrowseLevel.Home) topTracks else it.tracks,
                    topArtists = topArtists,
                    // Spotify deniega estos endpoints si la sesión se creó sin el permiso
                    // `user-top-read`, que se añadió después. Reconectar la cuenta lo arregla.
                    topReadDenied = topTracks.isEmpty() && topArtists.isEmpty()
                )
            }
        }
    }

    // ─── Búsqueda ──────────────────────────────────────────────────────

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update { it.copy(level = BrowseLevel.Home) }
            loadHome()
            return
        }

        searchJob = viewModelScope.launch {
            // Sin esta pausa se dispararía una petición por cada tecla pulsada.
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update { it.copy(isLoading = true, level = BrowseLevel.Results) }
            val results = runCatching { repository.searchCatalog(query) }
                .getOrElse { SpotifyRepository.CatalogSearchResults() }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tracks = results.tracks,
                    artists = results.artists,
                    albums = results.albums,
                    message = if (results.isEmpty) "Nothing found for \"$query\"." else null
                )
            }
        }
    }

    // ─── Navegación interna ────────────────────────────────────────────

    fun openArtist(artist: SpotifyArtistFull) {
        val artistId = artist.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, level = BrowseLevel.Artist(artist)) }
            val top = runCatching { repository.getArtistTopTracks(artistId) }.getOrElse { emptyList() }
            val albums = runCatching { repository.getArtistAlbums(artistId) }.getOrElse { emptyList() }
            _uiState.update {
                it.copy(isLoading = false, tracks = top, artistAlbums = albums)
            }
        }
    }

    fun openAlbum(album: SpotifyAlbumFull) {
        val albumId = album.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, level = BrowseLevel.Album(album)) }
            val tracks = runCatching { repository.getAlbumTracks(albumId) }.getOrElse { emptyList() }
            _uiState.update { it.copy(isLoading = false, tracks = tracks) }
        }
    }

    /** @return true si se consumió el gesto de volver atrás. */
    fun goBack(): Boolean = when (val level = _uiState.value.level) {
        is BrowseLevel.Album -> {
            // Desde un álbum se vuelve al artista si se llegó por ahí; si no, a la búsqueda.
            val artist = _uiState.value.artists.firstOrNull { artistOwns(it, level.album) }
            if (artist != null) openArtist(artist) else restoreSearch()
            true
        }

        is BrowseLevel.Artist -> {
            restoreSearch()
            true
        }

        BrowseLevel.Results -> {
            _uiState.update { it.copy(query = "", level = BrowseLevel.Home) }
            loadHome()
            true
        }

        BrowseLevel.Home -> false
    }

    private fun artistOwns(artist: SpotifyArtistFull, album: SpotifyAlbumFull): Boolean =
        album.artists.orEmpty().any { it.id != null && it.id == artist.id }

    private fun restoreSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) {
            _uiState.update { it.copy(level = BrowseLevel.Home) }
            loadHome()
        } else {
            onQueryChange(query)
        }
    }

    // ─── Añadir a la biblioteca ────────────────────────────────────────

    /**
     * Importa las pistas y lanza la búsqueda de audio. Reproducir no es inmediato: hasta que
     * el emparejador no encuentra el vídeo correspondiente no hay nada que sonar.
     */
    fun addToLibrary(tracks: List<SpotifyTrack>, label: String) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val added = runCatching { repository.importTracks(tracks) }.getOrElse { 0 }
            SpotifyMatchWorker.enqueue(context, retryFailed = false)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    message = when {
                        added == 0 -> "$label was already in your library — finding audio now."
                        tracks.size == 1 -> "Added \"$label\". Finding audio for it now."
                        else -> "Added $added tracks from $label. Finding audio now."
                    }
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
