package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState

/**
 * Per-field views of [StablePlayerState] for the player sheet.
 *
 * `StablePlayerState` emits 3–5 times per skip (transition, hydration, lyrics, duration,
 * buffering) and 1–2 times per play/pause. A scope that reads the whole object re-runs on
 * every one of those emissions, whatever changed. Each field here is a `derivedStateOf`, so a
 * scope that reads, say, [isPlaying] is only invalidated when `isPlaying` itself flips.
 *
 * The matching `...Provider` lambdas are stable for the lifetime of the holder and are what
 * the full player's `() -> T` parameters receive.
 */
@Stable
internal class PlayerSheetFieldStates(source: State<StablePlayerState>) {
    val currentSong: State<Song?> = derivedStateOf { source.value.currentSong }
    val currentMediaItemIndex: State<Int> = derivedStateOf { source.value.currentMediaItemIndex }
    val isPlaying: State<Boolean> = derivedStateOf { source.value.isPlaying }
    val playWhenReady: State<Boolean> = derivedStateOf { source.value.playWhenReady }
    val totalDuration: State<Long> = derivedStateOf { source.value.totalDuration }
    val isShuffleEnabled: State<Boolean> = derivedStateOf { source.value.isShuffleEnabled }
    val isShuffleTransitionInProgress: State<Boolean> =
        derivedStateOf { source.value.isShuffleTransitionInProgress }
    val repeatMode: State<Int> = derivedStateOf { source.value.repeatMode }
    val lyrics: State<Lyrics?> = derivedStateOf { source.value.lyrics }

    val isPlayingProvider: () -> Boolean = { isPlaying.value }
    val playWhenReadyProvider: () -> Boolean = { playWhenReady.value }
    val totalDurationProvider: () -> Long = { totalDuration.value }
    val isShuffleEnabledProvider: () -> Boolean = { isShuffleEnabled.value }
    val repeatModeProvider: () -> Int = { repeatMode.value }
    val lyricsProvider: () -> Lyrics? = { lyrics.value }
}

@Composable
internal fun rememberPlayerSheetFieldStates(
    source: State<StablePlayerState>
): PlayerSheetFieldStates = remember(source) { PlayerSheetFieldStates(source) }
