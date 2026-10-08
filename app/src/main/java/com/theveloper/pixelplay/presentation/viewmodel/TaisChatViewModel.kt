package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.data.tais.dj.TaisDjEngine
import com.theveloper.pixelplay.data.tais.dj.TaizoMemory
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

    /** True from a send until its reply lands; the sheet disables Send meanwhile. */
    private val _isResponding = MutableStateFlow(false)
    val isResponding: StateFlow<Boolean> = _isResponding.asStateFlow()

    /** What Taizo remembers of this chat (owner decision: for the sheet's lifetime only). */
    private val memory = TaizoMemory()

    /** Loads the on-device model as the sheet opens, so the first answer doesn't wait for it. */
    fun prewarm() {
        viewModelScope.launch { djEngine.prewarm() }
    }

    fun sendPrompt() {
        val prompt = _uiState.value.inputText.trim()
        if (prompt.isBlank()) return
        // One reply at a time: a second send used to drop the wrong "Thinking" bubble (the reply
        // replaced messages.dropLast(1)), leaving the first one spinning forever.
        if (_isResponding.value) return
        _isResponding.value = true

        val thinking = TaisChatMessage.Thinking(prompt)
        _uiState.update {
            it.copy(
                messages = it.messages + TaisChatMessage.User(prompt) + thinking,
                inputText = ""
            )
        }

        viewModelScope.launch {
            try {
                val turn = djEngine.respond(prompt, memory)
                val reply = when (turn) {
                    is TaizoTurn.Media -> TaisChatMessage.DjReply(prompt, turn.result, turn.aiIntro)
                    is TaizoTurn.Conversation -> TaisChatMessage.TextReply(turn.text)
                    is TaizoTurn.Error -> TaisChatMessage.TextReply(turn.message, isError = true)
                }
                replaceMessage(thinking.id, reply)
                when (turn) {
                    is TaizoTurn.Conversation -> memory.add(prompt, turn.text)
                    is TaizoTurn.Media -> {
                        memory.add(prompt, mediaMemoryNote(turn.result))
                        // The card is up; its intro line follows when the model has written it.
                        if (turn.aiIntro == null && reply is TaisChatMessage.DjReply) {
                            launch {
                                val intro = djEngine.introFor(prompt, turn.result) ?: return@launch
                                _uiState.update { state ->
                                    state.copy(messages = state.messages.map { message ->
                                        if (message.id == reply.id && message is TaisChatMessage.DjReply) {
                                            message.copy(aiIntro = intro)
                                        } else {
                                            message
                                        }
                                    })
                                }
                            }
                        }
                    }
                    is TaizoTurn.Error -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("TaisChatViewModel").w(e, "Taizo reply failed")
                replaceMessage(thinking.id, TaisChatMessage.TextReply(FALLBACK_ERROR, isError = true))
            } finally {
                // Still there if respond() threw: never leave a spinner behind.
                if (_uiState.value.messages.any { it.id == thinking.id }) {
                    replaceMessage(
                        thinking.id,
                        TaisChatMessage.TextReply(FALLBACK_ERROR, isError = true)
                    )
                }
                _isResponding.value = false
            }
        }
    }

    private fun replaceMessage(id: Long, replacement: TaisChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) replacement else it })
        }
    }

    private fun mediaMemoryNote(result: DjRouteResult): String = when (result) {
        is DjRouteResult.Offline -> "Found ${result.songs.size} songs from the library: " +
            result.songs.take(3).joinToString(", ") { "${it.title} by ${it.displayArtist}" }
        is DjRouteResult.Online -> "Found ${result.tracks.size} songs on Spotify."
        DjRouteResult.NoResults -> "Found no matching songs."
    }

    private companion object {
        const val FALLBACK_ERROR = "Something went wrong. Please try again."
    }
}
