package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.history.ListeningHistoryRepository
import com.theveloper.pixelplay.data.history.ListeningHistoryStatus
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings › Library & storage › Listening history, the screen behind it, and the one-time Home card. */
@HiltViewModel
class ListeningHistoryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ListeningHistoryRepository,
    private val spotifyRepository: dagger.Lazy<SpotifyRepository>,
) : ViewModel() {

    val status: StateFlow<ListeningHistoryStatus> = repository.status

    fun setSpotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSpotifyEnabled(enabled) }
    }

    fun setYouTubeEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setYouTubeEnabled(enabled) }
    }

    /** Runs in the repository's own scope: leaving the screen does not stop an import. */
    fun importFiles(uris: List<Uri>) = repository.importFiles(uris)

    fun clearImportResult() = repository.clearImportResult()

    fun syncNow() {
        viewModelScope.launch { repository.sync(force = true) }
    }

    fun clearImported(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.clearImported()
            onDone()
        }
    }

    fun dismissHomeCard() {
        viewModelScope.launch { repository.dismissHomeCard() }
    }

    /** Drops only the token (imports and matches stay), then signs in again with the history scopes. */
    fun reconnectSpotify() {
        viewModelScope.launch {
            spotifyRepository.get().reauthorize()
            SpotifyLoginActivity.start(context)
        }
    }
}
