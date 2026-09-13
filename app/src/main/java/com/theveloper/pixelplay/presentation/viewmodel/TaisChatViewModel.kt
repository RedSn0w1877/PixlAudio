package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.data.tais.dj.TaisDjEngine
import com.theveloper.pixelplay.data.tais.dj.TaizoTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** One turn of the TAIS DJ chat: the user's prompt, then either a result or an in-flight state. */
sealed interface TaisChatMessage {
    /** Stable per-instance identity for the chat LazyColumn's `key` — these carry no natural ID otherwise. */
    val id: Long
    data class User(val text: String, override val id: Long = System.nanoTime()) : TaisChatMessage
    data class Thinking(val prompt: String, override val id: Long = System.nanoTime()) : TaisChatMessage
    data class DjReply(
        val prompt: String,
        val result: DjRouteResult,
        val aiIntro: String? = null,
        override val id: Long = System.nanoTime()
    ) : TaisChatMessage
    /** A freeform conversational answer from Taizo (music trivia, recommendations, etc.) rather than a media result. */
    data class TextReply(val text: String, val isError: Boolean = false, override val id: Long = System.nanoTime()) : TaisChatMessage
}

data class TaisChatUiState(
    val messages: List<TaisChatMessage> = emptyList(),
    val inputText: String = ""
)

@HiltViewModel
class TaisChatViewModel @Inject constructor(
    private val djEngine: TaisDjEngine,
    private val spotifyRepository: SpotifyRepository,
    private val musicRepository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaisChatUiState())
    val uiState: StateFlow<TaisChatUiState> = _uiState.asStateFlow()

    private val _isResolvingOnlineTracks = MutableStateFlow(false)
    val isResolvingOnlineTracks: StateFlow<Boolean> = _isResolvingOnlineTracks.asStateFlow()

    /**
     * Imports Spotify search results into the library through the same pipeline the Spotify
     * browse tab uses, then hands the resolved [Song]s to [onReady] — this is what lets a
     * Taizo "Online" (Spotify catalog) result actually play/queue directly from the chat
     * instead of sending the user to manually import from the browse tab first.
     */
    fun resolveOnlineTracks(tracks: List<SpotifyTrack>, onReady: (List<Song>) -> Unit) {
        if (tracks.isEmpty() || _isResolvingOnlineTracks.value) return
        viewModelScope.launch {
            _isResolvingOnlineTracks.value = true
            try {
                spotifyRepository.importTracks(tracks)
                val ids = spotifyRepository.unifiedIdsFor(tracks).map { it.toString() }
                val songs = musicRepository.getSongsByIds(ids).first()
                if (songs.isNotEmpty()) onReady(songs)
            } catch (e: Exception) {
                Timber.tag("TaisChatViewModel").w(e, "Failed to resolve online tracks for playback")
            } finally {
                _isResolvingOnlineTracks.value = false
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendPrompt() {
        val prompt = _uiState.value.inputText.trim()
        if (prompt.isBlank()) return

        _uiState.update {
            it.copy(
                messages = it.messages + TaisChatMessage.User(prompt) + TaisChatMessage.Thinking(prompt),
                inputText = ""
            )
        }

        viewModelScope.launch {
            val turn = djEngine.respond(prompt)
            val reply = when (turn) {
                is TaizoTurn.Media -> TaisChatMessage.DjReply(prompt, turn.result, turn.aiIntro)
                is TaizoTurn.Conversation -> TaisChatMessage.TextReply(turn.text)
                is TaizoTurn.Error -> TaisChatMessage.TextReply(turn.message, isError = true)
            }
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.dropLast(1) + reply // drop the Thinking placeholder
                )
            }
        }
    }
}
