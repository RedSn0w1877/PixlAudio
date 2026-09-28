package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The part of [StablePlayerState] a song list actually renders from.
 *
 * Screens used to collect the whole [StablePlayerState], so any change to buffering, repeat,
 * duration or lyrics recomposed the entire screen — and every row got a changed `isPlaying`
 * argument, so every visible row recomposed too, not just the current one.
 */
@Immutable
data class PlayerListSlice(
    val currentSongId: String? = null,
    val isPlaying: Boolean = false,
    val isShuffleEnabled: Boolean = false
) {
    val hasCurrentSong: Boolean get() = currentSongId != null

    /** Row-level `isPlaying`: only the current row ever sees `true`. */
    fun isPlayingRow(songId: String): Boolean = isPlaying && currentSongId == songId
}

@Composable
fun rememberPlayerListSlice(playerViewModel: PlayerViewModel): State<PlayerListSlice> {
    val flow = remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.toListSlice() }
            .distinctUntilChanged()
    }
    // Seeded from the current value so the first frame doesn't flash "nothing playing".
    val initial = remember(playerViewModel) { playerViewModel.stablePlayerState.value.toListSlice() }
    return flow.collectAsStateWithLifecycle(initialValue = initial)
}

private fun StablePlayerState.toListSlice() =
    PlayerListSlice(currentSong?.id, isPlaying, isShuffleEnabled)
