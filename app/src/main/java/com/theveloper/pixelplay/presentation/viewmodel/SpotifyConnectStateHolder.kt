package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectController
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectDevice
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectUiState
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The devices sheet's and the player's view of Spotify Connect output. A sibling of
 * [CastStateHolder]: the session itself lives in [SpotifyConnectController] (data layer, shared with
 * MusicService); this holder adds the UI-only rules — one remote output at a time, and the
 * "Reconnect Spotify to use Connect" flow, done the same way as the dashboard's reconnect for
 * `user-top-read` (drop only the token, then sign in again).
 */
@Singleton
class SpotifyConnectStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: SpotifyConnectController,
    private val castStateHolder: CastStateHolder,
    private val spotifyRepository: SpotifyRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val uiState: StateFlow<SpotifyConnectUiState> = controller.uiState

    /** Connect's toasts; the ViewModel forwards them to the app's toast channel. */
    val messages: SharedFlow<String> = controller.messages

    /** "Playing on <device>" chips: the device name while a session runs, else null. */
    val playingOnName: StateFlow<String?> = controller.uiState
        .map { it.active?.name }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The sheet opened or its refresh action ran. */
    fun refreshDevices() = controller.refreshDevices()

    fun selectDevice(device: SpotifyConnectDevice, onMessage: (String) -> Unit) {
        if (castStateHolder.isRemotePlaybackActive.value || castStateHolder.isCastConnecting.value) {
            onMessage("Stop casting first, then choose a Spotify device")
            return
        }
        controller.connect(device)
    }

    /** "Stop playing on <device>": carry on on this phone from where the device was. */
    fun stopPlaying() = controller.disconnect()

    fun setVolume(percent: Int) = controller.setVolume(percent)

    /** "Reconnect Spotify to use Connect": drops only the token, then signs in with the new scopes. */
    fun reconnect() {
        scope.launch {
            spotifyRepository.reauthorize()
            SpotifyLoginActivity.start(context)
        }
    }
}
