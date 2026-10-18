package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.blocking.BlockedArtist
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.haptics.MusicHapticsEngine
import com.theveloper.pixelplay.data.preferences.MusicHapticsEngineChoice
import com.theveloper.pixelplay.data.preferences.MusicHapticsFollows
import com.theveloper.pixelplay.data.preferences.MusicHapticsSettings
import com.theveloper.pixelplay.data.preferences.PlayerGesture
import com.theveloper.pixelplay.data.preferences.PlayerGestureSettings
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.data.text.FoldedText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings > Playback rows for gestures, music haptics and the blocked-artists entry. */
@HiltViewModel
class PlayerExtrasSettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    blockedArtists: BlockedArtistsRepository,
    private val hapticsEngine: MusicHapticsEngine,
) : ViewModel() {

    val gestures: StateFlow<PlayerGestureSettings> = preferences.playerGesturesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerGestureSettings())

    val musicHaptics: StateFlow<MusicHapticsSettings> = preferences.musicHapticsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicHapticsSettings())

    val blockedCount: StateFlow<Int> = blockedArtists.index
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The phone has a vibration motor. Without one the haptics rows are greyed out. */
    val hapticsSupported: Boolean = hapticsEngine.isSupported
    val systemGeneratorAvailable: Boolean = hapticsEngine.isSystemGeneratorAvailable

    fun setGesture(gesture: PlayerGesture, enabled: Boolean) {
        viewModelScope.launch { preferences.setPlayerGesture(gesture, enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setMusicHapticsEnabled(enabled)
            DiagnosticsLog.event("MusicHaptics", if (enabled) "switched on in settings" else "switched off in settings")
        }
    }

    fun setHapticsStrength(percent: Int) {
        viewModelScope.launch { preferences.setMusicHapticsStrength(percent) }
    }

    fun setHapticsFollows(follows: MusicHapticsFollows) {
        viewModelScope.launch { preferences.setMusicHapticsFollows(follows) }
    }

    fun setHapticsEngine(engine: MusicHapticsEngineChoice) {
        viewModelScope.launch { preferences.setMusicHapticsEngine(engine) }
    }
}

/** The Blocked artists screen: list, search, unblock one, unblock all. */
@HiltViewModel
class BlockedArtistsViewModel @Inject constructor(
    private val blockedArtists: BlockedArtistsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    val queryText: StateFlow<String> = query

    val totalCount: StateFlow<Int> = blockedArtists.index
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The blocked artists whose folded name contains the folded query, in name order. */
    val visible: StateFlow<List<BlockedArtist>> = combine(blockedArtists.index, query) { index: BlockedArtistIndex, q ->
        val folded = FoldedText.fold(q)
        if (folded.isEmpty()) index.artists
        else index.artists.filter { FoldedText.fold(it.name).contains(folded) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { query.value = value }

    fun unblock(artist: BlockedArtist) {
        viewModelScope.launch { blockedArtists.unblock(artist.id, artist.name) }
    }

    fun unblockAll() {
        viewModelScope.launch { blockedArtists.unblockAll() }
    }
}
