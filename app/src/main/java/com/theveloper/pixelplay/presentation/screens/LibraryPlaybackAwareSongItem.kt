package com.theveloper.pixelplay.presentation.screens

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.PlaybackRowState
import com.theveloper.pixelplay.presentation.components.rememberIsCurrentSong
import com.theveloper.pixelplay.presentation.components.rememberIsSongPlaying
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Immutable
internal data class LibrarySongPlaybackUiState(
    val isCurrentSong: Boolean = false,
    val isPlaying: Boolean = false
)

@OptIn(UnstableApi::class)
@Composable
internal fun LibraryPlaybackAwareSongItem(
    song: Song,
    playback: State<PlaybackRowState>,
    albumArtSize: Dp = 50.dp,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    // The list collects the player state once; each row only derives its own two booleans, so a
    // play/pause or track change recomposes just the rows whose answer flipped.
    val isCurrentSong by rememberIsCurrentSong(playback, song.id)
    val isPlaying by rememberIsSongPlaying(playback, song.id)

    EnhancedSongListItem(
        song = song,
        isPlaying = isPlaying,
        isCurrentSong = isCurrentSong,
        isLoading = false,
        albumArtSize = albumArtSize,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onMoreOptionsClick = onMoreOptionsClick,
        onClick = onClick
    )
}
