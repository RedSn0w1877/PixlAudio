package com.theveloper.pixelplay.presentation.spotify.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.service.PlaybackErrorInfo
import com.theveloper.pixelplay.data.service.PlaybackErrorReporter
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.worker.SpotifyMatchWorker
import com.theveloper.pixelplay.data.worker.SpotifySyncWorker
import com.theveloper.pixelplay.data.youtube.PlaybackDiagnostics
import com.theveloper.pixelplay.data.youtube.PlaybackDiagnosticsReport
import com.theveloper.pixelplay.data.youtube.StreamDebugProbe
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotifyDashboardUiState(
    val isLoggedIn: Boolean = false,
    val accountName: String? = null,
    val accountEmail: String? = null,
    val playlists: List<SpotifyPlaylistEntity> = emptyList(),
    val totalSongs: Int = 0,
    val isSyncing: Boolean = false,
    val syncStatus: String? = null,
    val matchedCount: Int = 0,
    val pendingMatchCount: Int = 0,
    val unmatchedCount: Int = 0,
    val isDiagnosing: Boolean = false,
    val diagnosticsReport: PlaybackDiagnosticsReport? = null,
    val lastPlaybackError: PlaybackErrorInfo? = null,
    val isDeepProbing: Boolean = false,
    val deepProbeReport: String? = null,
    val isYouTubeSignedIn: Boolean = false,
    /** Mientras el usuario completa el login en google.com/device. */
    val youTubeSignInPrompt: YouTubeSignInPrompt? = null,
    val youTubeSignInError: String? = null,
    /**
     * Spotify rechaza por permisos el contenido de las playlists: el token es de antes de
     * que la app pidiera ese permiso. Se arregla volviendo a enlazar la cuenta.
     */
    val playlistAccessDenied: Boolean = false
)

data class YouTubeSignInPrompt(
    val userCode: String,
    val verificationUrl: String
)

@HiltViewModel
class SpotifyDashboardViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: SpotifyRepository,
    private val authManager: SpotifyAuthManager,
    private val spotifyDao: SpotifyDao,
    private val playbackDiagnostics: PlaybackDiagnostics,
    private val streamDebugProbe: StreamDebugProbe,
    private val youTubeAuthManager: com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager
) : ViewModel() {

    private val matchCounts = MutableStateFlow(Triple(0, 0, 0))
    private val diagnostics = MutableStateFlow<Pair<Boolean, PlaybackDiagnosticsReport?>>(false to null)
    private val deepProbe = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val youTubePrompt = MutableStateFlow<YouTubeSignInPrompt?>(null)
    private val youTubeError = MutableStateFlow<String?>(null)

    private val syncState: StateFlow<Pair<Boolean, String?>> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(SpotifySyncWorker.WORK_NAME)
            .map { infos ->
                val info = infos.firstOrNull()
                val running = info?.state == WorkInfo.State.RUNNING ||
                    info?.state == WorkInfo.State.ENQUEUED
                val label = info?.progress?.getString(SpotifySyncWorker.PROGRESS_NAME)
                running to label
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to null)

    val uiState: StateFlow<SpotifyDashboardUiState> = combine(
        repository.isLoggedIn,
        repository.playlistsFlow(),
        repository.songsFlow(),
        syncState,
        matchCounts,
        diagnostics,
        PlaybackErrorReporter.lastError,
        deepProbe,
        youTubeAuthManager.isSignedIn,
        youTubePrompt,
        youTubeError,
        repository.playlistAccessDenied
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val loggedIn = values[0] as Boolean
        val playlists = values[1] as List<SpotifyPlaylistEntity>
        val songs = values[2] as List<com.theveloper.pixelplay.data.database.SpotifySongEntity>
        val sync = values[3] as Pair<Boolean, String?>
        val counts = values[4] as Triple<Int, Int, Int>
        val diag = values[5] as Pair<Boolean, PlaybackDiagnosticsReport?>
        val playbackError = values[6] as PlaybackErrorInfo?
        val probe = values[7] as Pair<Boolean, String?>
        val ytSignedIn = values[8] as Boolean
        val ytPrompt = values[9] as YouTubeSignInPrompt?
        val ytError = values[10] as String?
        val playlistDenied = values[11] as Boolean

        SpotifyDashboardUiState(
            isLoggedIn = loggedIn,
            accountName = authManager.accountName(),
            accountEmail = authManager.accountEmail(),
            playlists = playlists,
            totalSongs = songs.distinctBy { it.spotifyId }.size,
            isSyncing = sync.first,
            syncStatus = sync.second,
            matchedCount = counts.first,
            pendingMatchCount = counts.second,
            unmatchedCount = counts.third,
            isDiagnosing = diag.first,
            diagnosticsReport = diag.second,
            lastPlaybackError = playbackError,
            isDeepProbing = probe.first,
            deepProbeReport = probe.second,
            isYouTubeSignedIn = ytSignedIn,
            youTubeSignInPrompt = ytPrompt,
            youTubeSignInError = ytError,
            playlistAccessDenied = playlistDenied
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotifyDashboardUiState())

    init {
        refreshMatchCounts()
    }

    fun refreshMatchCounts() {
        viewModelScope.launch {
            runCatching {
                Triple(
                    spotifyDao.countByMatchState(SpotifyMatchState.MATCHED) +
                        spotifyDao.countByMatchState(SpotifyMatchState.MANUAL),
                    spotifyDao.countByMatchState(SpotifyMatchState.PENDING),
                    spotifyDao.countByMatchState(SpotifyMatchState.UNMATCHED)
                )
            }.onSuccess { matchCounts.value = it }
        }
    }

    fun signIn() = SpotifyLoginActivity.start(context)

    fun syncNow() {
        SpotifySyncWorker.enqueue(context)
    }

    /**
     * Relanza la búsqueda en YouTube Music. Incluye las pistas que ya se dieron por
     * perdidas: si el fallo fue de red o por un cambio en la API, ahora pueden encontrarse.
     */
    fun matchNow() {
        SpotifyMatchWorker.enqueue(context, retryFailed = true)
    }

    /** Prueba la cadena completa con una pista y guarda el informe. */
    fun runDiagnostics() {
        if (diagnostics.value.first) return
        viewModelScope.launch {
            diagnostics.value = true to null
            val report = runCatching { playbackDiagnostics.run() }.getOrNull()
            diagnostics.value = false to report
            refreshMatchCounts()
        }
    }

    fun dismissDiagnostics() {
        diagnostics.value = false to null
    }

    /** Depuración: dispara todas las variantes de petición y guarda el volcado de texto. */
    fun runDeepProbe() {
        if (deepProbe.value.first) return
        viewModelScope.launch {
            deepProbe.value = true to null
            val report = runCatching { streamDebugProbe.run() }
                .getOrElse { "Deep probe falló: ${it.message}" }
            deepProbe.value = false to report
        }
    }

    fun dismissDeepProbe() {
        deepProbe.value = false to null
    }

    // ─── Sign in to YouTube (device flow) ──────────────────────────────

    private var youTubeSignInJob: kotlinx.coroutines.Job? = null

    /**
     * Arranca el login de YouTube: pide el código, lo muestra, y sondea en segundo plano
     * hasta que el usuario termina en google.com/device.
     */
    fun startYouTubeSignIn() {
        if (youTubeSignInJob?.isActive == true) return
        youTubeError.value = null
        youTubeSignInJob = viewModelScope.launch {
            val device = youTubeAuthManager.requestDeviceCode()
            if (device == null) {
                youTubeError.value = "Couldn't reach Google to start sign-in. Check your connection and try again."
                return@launch
            }
            youTubePrompt.value = YouTubeSignInPrompt(device.userCode, device.verification)
            val result = youTubeAuthManager.pollForToken(device)
            youTubePrompt.value = null
            if (result is com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager.SignInStep.Failed) {
                youTubeError.value = result.reason
            }
        }
    }

    fun cancelYouTubeSignIn() {
        youTubeSignInJob?.cancel()
        youTubeSignInJob = null
        youTubePrompt.value = null
    }

    fun dismissYouTubeError() {
        youTubeError.value = null
    }

    fun signOutYouTube() {
        youTubeAuthManager.signOut()
    }

    fun clearPlaybackError() {
        PlaybackErrorReporter.clear()
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            matchCounts.value = Triple(0, 0, 0)
        }
    }

    /**
     * Vuelve a pedir permiso a Spotify conservando lo ya importado.
     *
     * Es lo que arregla el 403 al leer playlists sin castigar al usuario: [logout] borraría
     * también los emparejamientos con YouTube, que son la parte cara de reconstruir.
     */
    fun reconnect() {
        viewModelScope.launch {
            repository.reauthorize()
            SpotifyLoginActivity.start(context)
        }
    }
}
