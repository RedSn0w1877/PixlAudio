package com.theveloper.pixelplay.presentation.spotify

import android.content.Context
import androidx.lifecycle.ViewModel
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Lo mínimo para pintar un botón de "iniciar sesión con Spotify" en cualquier pantalla.
 * El dashboard usa [SpotifyDashboardViewModel], que además maneja la importación.
 */
@HiltViewModel
class SpotifyAccountViewModel @Inject constructor(
    repository: SpotifyRepository,
    private val authManager: SpotifyAuthManager
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedIn

    fun hasClientId(): Boolean = authManager.hasClientId()

    fun signIn(context: Context) {
        SpotifyLoginActivity.start(context)
    }
}
