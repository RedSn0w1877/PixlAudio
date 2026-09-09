package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ExternalServiceAccount {
    SPOTIFY
}

data class ExternalAccountUiModel(
    val service: ExternalServiceAccount,
    val title: String,
    val accountLabel: String,
    val syncedContentLabel: String,
    val isLoggingOut: Boolean
)

data class AccountsUiState(
    val connectedAccounts: List<ExternalAccountUiModel> = emptyList(),
    val disconnectedServices: List<ExternalServiceAccount> = emptyList()
)

/**
 * Cuentas externas enlazadas. Spotify es la única fuente remota que queda.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val spotifyRepository: SpotifyRepository,
    private val spotifyAuthManager: SpotifyAuthManager
) : ViewModel() {

    private val loggingOutServices = MutableStateFlow<Set<ExternalServiceAccount>>(emptySet())

    val uiState: StateFlow<AccountsUiState> = combine(
        spotifyRepository.isLoggedIn,
        spotifyRepository.playlistsFlow(),
        spotifyRepository.songsFlow(),
        loggingOutServices
    ) { loggedIn, playlists, songs, loggingOut ->
        if (!loggedIn) {
            AccountsUiState(disconnectedServices = listOf(ExternalServiceAccount.SPOTIFY))
        } else {
            val trackCount = songs.distinctBy { it.spotifyId }.size
            AccountsUiState(
                connectedAccounts = listOf(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.SPOTIFY,
                        title = "Spotify",
                        accountLabel = spotifyAuthManager.accountName()
                            ?: spotifyAuthManager.accountEmail()
                            ?: "Linked account",
                        syncedContentLabel = "$trackCount tracks · ${playlists.size} playlists",
                        isLoggingOut = ExternalServiceAccount.SPOTIFY in loggingOut
                    )
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun logout(service: ExternalServiceAccount) {
        if (service != ExternalServiceAccount.SPOTIFY) return
        viewModelScope.launch {
            loggingOutServices.value = loggingOutServices.value + service
            try {
                spotifyRepository.logout()
            } finally {
                loggingOutServices.value = loggingOutServices.value - service
            }
        }
    }
}
