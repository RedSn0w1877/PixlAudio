package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.media.session.MediaSession
import com.theveloper.pixelplay.data.service.MediaSessionTokenHolder
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectController
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectDevice
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectUiState
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectVolume
import com.theveloper.pixelplay.data.spotify.connect.SpotifyDeviceKind
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Equalizer's volume card while Spotify Connect plays: the device's volume instead of the phone's. */
@Immutable
data class SpotifyConnectDeviceVolume(val name: String, val percent: Int?, val supportsVolume: Boolean)

/** The full player's output pill during Spotify Connect: the device's name and kind. */
@Immutable
data class SpotifyConnectChip(val name: String, val kind: SpotifyDeviceKind, val connecting: Boolean)

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
    private val spotifyRepository: SpotifyRepository,
    private val mediaSessionTokenHolder: MediaSessionTokenHolder
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

    /**
     * The full player's output pill: the device playing, or the one being connected to; else
     * null. A narrow slice: the whole [uiState] re-emits on every volume and play-state poll.
     */
    val topBarDevice: StateFlow<SpotifyConnectChip?> = controller.uiState
        .map { state -> topBarChipOf(state) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The MediaSession token MainActivity hands to `Activity.setMediaController` while a Connect
     * device that takes the volume keys plays (supports_volume, not a phone or tablet); else null.
     * With it the focused window's volume keys go straight to the session, which the platform
     * otherwise does only while the session is playing (so the keys also work with the speaker
     * paused while PixlAudio is open).
     */
    val volumeKeySessionToken: StateFlow<MediaSession.Token?> = combine(
        mediaSessionTokenHolder.platformToken,
        controller.uiState
            .map { ui -> ui.active?.let { SpotifyConnectVolume.keysControlDevice(it.type, it.supportsVolume) } == true }
            .distinctUntilChanged()
    ) { token, eligible -> token.takeIf { eligible } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Connect device's volume for the Equalizer card, or null while this phone plays. */
    val deviceVolume: StateFlow<SpotifyConnectDeviceVolume?> = controller.uiState
        .map { ui -> ui.active?.let { SpotifyConnectDeviceVolume(it.name, it.volumePercent, it.supportsVolume) } }
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

/** The Connect device the full player's output pill names, or null while this phone plays. */
internal fun topBarChipOf(state: SpotifyConnectUiState): SpotifyConnectChip? {
    state.active?.let { return SpotifyConnectChip(it.name, it.kind, connecting = false) }
    val id = state.connectingDeviceId ?: return null
    val device = state.devices.firstOrNull { it.deviceId == id } ?: return null
    return SpotifyConnectChip(device.name, device.kind, connecting = true)
}
