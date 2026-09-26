package com.theveloper.pixelplay.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The only two facts a song row needs from the player: which song is current and whether it plays.
 *
 * [StablePlayerState] emits 4-6 times around every track change (buffering, duration, lyrics...).
 * Lists collect this narrow projection once, and each row derives its own booleans from it, so a
 * play/pause or a track change recomposes only the rows whose state actually changed.
 */
@Immutable
data class PlaybackRowState(
    val currentSongId: String? = null,
    val isPlaying: Boolean = false,
) {
    val hasCurrentSong: Boolean get() = currentSongId != null

    fun isCurrent(songId: String): Boolean = currentSongId == songId

    fun isPlaying(songId: String): Boolean = isPlaying && currentSongId == songId
}

internal fun StablePlayerState.toPlaybackRowState(): PlaybackRowState =
    PlaybackRowState(currentSongId = currentSong?.id, isPlaying = isPlaying)

/** Collects the narrow [PlaybackRowState] projection of [stablePlayerState] once, at list level. */
@Composable
fun rememberPlaybackRowState(stablePlayerState: StateFlow<StablePlayerState>): State<PlaybackRowState> {
    val flow = remember(stablePlayerState) {
        stablePlayerState.map { it.toPlaybackRowState() }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(
        initialValue = remember(stablePlayerState) { stablePlayerState.value.toPlaybackRowState() }
    )
}

/** Per-row "is this the current song" that only invalidates the row when its own answer flips. */
@Composable
fun rememberIsCurrentSong(playback: State<PlaybackRowState>, songId: String): State<Boolean> =
    remember(playback, songId) { derivedStateOf { playback.value.isCurrent(songId) } }

/** Per-row "is this song playing" that only invalidates the row when its own answer flips. */
@Composable
fun rememberIsSongPlaying(playback: State<PlaybackRowState>, songId: String): State<Boolean> =
    remember(playback, songId) { derivedStateOf { playback.value.isPlaying(songId) } }
