package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.model.LyricsDoc
import kotlinx.coroutines.flow.StateFlow

/** Reuses the player's Material 3 typography, motion, karaoke sweep and centered scrolling. */
@Composable
fun LyricsDocViewer(document: LyricsDoc, playbackMs: StateFlow<Long>, isPlaying: Boolean,
    modifier: Modifier = Modifier, onSeek: (Long) -> Unit = {}) {
    val lines = remember(document) { document.toLyrics().synced.orEmpty() }
    SyncedLyricsList(lines = lines, listState = rememberLazyListState(),
        playbackPositionFlow = playbackMs, lyricsSyncOffset = 0,
        accentColor = MaterialTheme.colorScheme.primary,
        textStyle = MaterialTheme.typography.headlineMedium,
        onLineClick = { onSeek(it.time.toLong()) }, highlightZoneFraction = 0.25f,
        highlightOffsetDp = 0.dp, autoscrollAnimationSpec = spring(),
        useAnimatedLyrics = true, isPlaying = isPlaying, modifier = modifier, contentPadding = PaddingValues(vertical = 24.dp))
}
