package com.theveloper.pixelplay.presentation.components

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.preferences.FullPlayerLoadingTweaks
import com.theveloper.pixelplay.presentation.components.player.FullPlayerContent
import com.theveloper.pixelplay.presentation.components.scoped.FullPlayerVisualState
import com.theveloper.pixelplay.presentation.components.scoped.PlayerSheetFieldStates
import com.theveloper.pixelplay.presentation.components.scoped.rememberFullPlayerRuntimePolicy
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled

@OptIn(UnstableApi::class)
@Composable
internal fun BoxScope.UnifiedPlayerMiniAndFullLayers(
    fieldStates: PlayerSheetFieldStates,
    albumSchemeState: State<ColorScheme>,
    targetSchemeState: State<ColorScheme>,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    bottomSheetOpenFractionState: State<Float>,
    fullPlayerVisualState: FullPlayerVisualState,
    containerHeight: Dp,
    currentQueueSourceName: String,
    currentSheetContentState: PlayerSheetState,
    carouselStyle: String,
    fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isFavorite: Boolean,
    shouldRenderFullPlayer: Boolean = true,
    currentHorizontalPaddingStartPxProvider: () -> Float,
    currentHorizontalPaddingEndPxProvider: () -> Float,
    onShowQueueClicked: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    onShowCastClicked: () -> Unit,
    /**
     * Glass mode: true while an opaque sheet scrim covers the whole player (the queue fully open).
     * Read in the full player's layer only; the player then stops drawing its glass underneath.
     */
    isFullPlayerCoveredProvider: () -> Boolean = { false }
) {
    val currentSongNonNull = fieldStates.currentSong.value ?: return
    val glassMode = LocalGlassModeEnabled.current

    // A hidden player gets the target scheme instead of the animated one: it isn't on
    // screen, so nothing visible changes, and it doesn't recompose for every frame of the
    // colour fade. It switches to the animated scheme before it becomes visible (the mini
    // player is fully transparent from 0.5, the full player until 0.25).
    val isMiniPlayerOnScreen = remember {
        derivedStateOf { playerContentExpansionFraction.value < 0.5f }
    }
    val miniPlayerScheme = remember(albumSchemeState, targetSchemeState) {
        { if (isMiniPlayerOnScreen.value) albumSchemeState.value else targetSchemeState.value }
    }
    ProvidePlayerScheme(scheme = miniPlayerScheme) {
        val miniPlayerZIndex by remember {
            derivedStateOf {
                if (playerContentExpansionFraction.value < 0.5f) 1f else 0f
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .graphicsLayer {
                    // Compute miniAlpha in the draw phase from the Animatable,
                    // avoiding per-frame recomposition during gestures.
                    alpha = (1f - playerContentExpansionFraction.value * 2f)
                        .coerceIn(0f, 1f)
                }
                .layout { measurable, constraints ->
                    val fraction = playerContentExpansionFraction.value
                    val startPaddingPx = currentHorizontalPaddingStartPxProvider().toInt().coerceAtLeast(0)
                    val endPaddingPx = currentHorizontalPaddingEndPxProvider().toInt().coerceAtLeast(0)

                    val targetWidth = if (fraction > 0f) {
                        (constraints.maxWidth - startPaddingPx - endPaddingPx).coerceAtLeast(0)
                    } else {
                        constraints.maxWidth
                    }
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = targetWidth,
                            maxWidth = targetWidth
                        )
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val xOffset = if (fraction > 0f) startPaddingPx else 0
                        placeable.placeRelative(xOffset, 0)
                    }
                }
                .zIndex(miniPlayerZIndex)
        ) {
            val isMiniPlayerVisible by remember {
                derivedStateOf { playerContentExpansionFraction.value < 0.01f }
            }
            val isPlaying = fieldStates.isPlaying.value
            val connectDeviceName by playerViewModel.spotifyConnect.playingOnName.collectAsStateWithLifecycle()
            if (glassMode) {
                GlassMiniPlayerContent(
                    song = currentSongNonNull,
                    isPlaying = isPlaying,
                    isCastConnecting = isCastConnecting,
                    isPreparingPlayback = isPreparingPlayback,
                    onPlayPause = { playerViewModel.playPause() },
                    onNext = { playerViewModel.nextSong() },
                    positionProvider = currentPositionProvider,
                    durationProvider = fieldStates.totalDurationProvider,
                    progressActive = isMiniPlayerVisible,
                    // The mini player is fully transparent from 0.5: its orbs stop sampling the
                    // ambient then (invisible, so no visible change), instead of re-rendering two
                    // moving lenses nobody sees.
                    glassLive = isMiniPlayerOnScreen.value,
                    remoteDeviceName = connectDeviceName,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MiniPlayerContentInternal(
                    song = currentSongNonNull,
                    isPlaying = isPlaying,
                    isCastConnecting = isCastConnecting,
                    isPreparingPlayback = isPreparingPlayback,
                    onPlayPause = { playerViewModel.playPause() },
                    onPrevious = { playerViewModel.previousSong() },
                    onNext = { playerViewModel.nextSong() },
                    canScroll = isMiniPlayerVisible && isPlaying,
                    remoteDeviceName = connectDeviceName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (shouldRenderFullPlayer) {
        val isFullPlayerOnScreen = remember {
            derivedStateOf { playerContentExpansionFraction.value > 0.01f }
        }
        val fullPlayerScheme = remember(albumSchemeState, targetSchemeState) {
            { if (isFullPlayerOnScreen.value) albumSchemeState.value else targetSchemeState.value }
        }
        ProvidePlayerScheme(scheme = fullPlayerScheme) {
            val fullPlayerZIndex by remember {
                derivedStateOf {
                    if (playerContentExpansionFraction.value >= 0.5f) 1f else 0f
                }
            }
            val fullPlayerOffset by remember {
                derivedStateOf {
                    if (playerContentExpansionFraction.value <= 0.01f) IntOffset(0, 10000)
                    else IntOffset.Zero
                }
            }
            val fullPlayerRuntimePolicy = rememberFullPlayerRuntimePolicy(
                currentSheetState = currentSheetContentState,
                expansionFraction = playerContentExpansionFraction,
                bottomSheetOpenFractionState = bottomSheetOpenFractionState
            )

            // Scoped queue collection: only the FullPlayer subtree observes
            // the queue. Sibling MiniPlayer composable and the whole
            // UnifiedPlayerSheetV2 caller are insulated from queue churn.
            val currentPlaybackQueue by playerViewModel.queueFlow
                .collectAsStateWithLifecycle()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(containerHeight)
                    .graphicsLayer {
                        // Read from FullPlayerVisualState lazy getters in the draw phase;
                        // these read Animatable.value internally → re-draw only, no recomposition.
                        alpha = if (isFullPlayerCoveredProvider()) 0f else fullPlayerVisualState.contentAlpha
                        translationY = fullPlayerVisualState.translationY
                        // Depth effect while the queue/cast sheet is open, read at draw time.
                        val fullPlayerScale = lerp(1f, 0.972f, bottomSheetOpenFractionState.value)
                        scaleX = fullPlayerScale
                        scaleY = fullPlayerScale
                    }
                    .zIndex(fullPlayerZIndex)
                    .offset { fullPlayerOffset }
            ) {
                val latestIsFavorite = rememberUpdatedState(isFavorite)
                val expansionFractionProvider = remember(playerContentExpansionFraction) {
                    { playerContentExpansionFraction.value }
                }
                val isFavoriteProvider = remember {
                    { latestIsFavorite.value }
                }
                val onPlayPause = remember(playerViewModel) { playerViewModel::playPause }
                val onSeek = remember(playerViewModel) { playerViewModel::seekTo }
                val onNext = remember(playerViewModel) { playerViewModel::nextSong }
                val onPrevious = remember(playerViewModel) { playerViewModel::previousSong }
                val onCollapse = remember(playerViewModel) {
                    { playerViewModel.collapsePlayerSheet() }
                }
                val onShuffleToggle = remember(playerViewModel) {
                    { playerViewModel.toggleShuffle() }
                }
                val onRepeatToggle = remember(playerViewModel) { playerViewModel::cycleRepeatMode }
                val onFavoriteToggle = remember(playerViewModel) { playerViewModel::toggleFavorite }

                // Glass mode: while the full player is fully transparent (below 25 % expansion, or
                // covered by the queue's opaque scrim) its kit surfaces sample an empty backdrop,
                // so a dozen invisible lenses don't re-render on every frame of the sheet motion.
                // Invisible either way: nothing on screen changes.
                val fullPlayerGlassLive by remember(fullPlayerVisualState, isFullPlayerCoveredProvider) {
                    derivedStateOf { fullPlayerVisualState.contentAlpha > 0f && !isFullPlayerCoveredProvider() }
                }
                val liveGlassBackdrop = LocalGlassBackdrop.current
                CompositionLocalProvider(
                    LocalGlassBackdrop provides if (!glassMode || fullPlayerGlassLive) liveGlassBackdrop else emptyBackdrop()
                ) {
                FullPlayerContent(
                    currentSong = currentSongNonNull,
                    currentPlaybackQueue = currentPlaybackQueue,
                    currentQueueSourceName = currentQueueSourceName,
                    currentMediaItemIndex = fieldStates.currentMediaItemIndex.value,
                    isShuffleEnabled = fieldStates.isShuffleEnabled.value,
                    shuffleTransitionInProgress = fieldStates.isShuffleTransitionInProgress.value,
                    repeatMode = fieldStates.repeatMode.value,
                    allowRealtimeUpdates = fullPlayerRuntimePolicy.allowRealtimeUpdates,
                    expansionFractionProvider = expansionFractionProvider,
                    currentSheetState = currentSheetContentState,
                    carouselStyle = carouselStyle,
                    loadingTweaks = fullPlayerLoadingTweaks,
                    isSheetDragGestureActive = isSheetDragGestureActive,
                    playerViewModel = playerViewModel,
                    currentPositionProvider = currentPositionProvider,
                    isPlayingProvider = fieldStates.isPlayingProvider,
                    playWhenReadyProvider = fieldStates.playWhenReadyProvider,
                    repeatModeProvider = fieldStates.repeatModeProvider,
                    isShuffleEnabledProvider = fieldStates.isShuffleEnabledProvider,
                    totalDurationProvider = fieldStates.totalDurationProvider,
                    lyricsProvider = fieldStates.lyricsProvider,
                    isCastConnecting = isCastConnecting,
                    isFavoriteProvider = isFavoriteProvider,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onCollapse = onCollapse,
                    onShowQueueClicked = onShowQueueClicked,
                    onQueueDragStart = onQueueDragStart,
                    onQueueDrag = onQueueDrag,
                    onQueueRelease = onQueueRelease,
                    onShowCastClicked = onShowCastClicked,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle,
                    onFavoriteToggle = onFavoriteToggle
                )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerPrewarmLayer(
    prewarmFullPlayer: Boolean,
    fieldStates: PlayerSheetFieldStates,
    containerHeight: Dp,
    targetSchemeState: State<ColorScheme>,
    currentQueueSourceName: String,
    carouselStyle: String,
    fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isCastConnecting: Boolean,
    isFavorite: Boolean,
    onShowQueueClicked: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit
) {
    if (!prewarmFullPlayer) return
    val currentSong = fieldStates.currentSong.value ?: return
    // Scoped queue collection: the prewarmed FullPlayer owns its own
    // subscription, keeping the queue out of the outer sheet's state.
    val currentPlaybackQueue by playerViewModel.queueFlow
        .collectAsStateWithLifecycle()
    // Invisible (alpha 0): always the target scheme, never the per-frame fade.
    ProvidePlayerScheme(scheme = { targetSchemeState.value }) {
        Box(
            modifier = Modifier
                .height(containerHeight)
                .fillMaxWidth()
                .alpha(0f)
                .clipToBounds()
        ) {
            // Memoize closures the same way the main layer does to avoid creating
            // new lambda instances on every recomposition.
            val latestIsFavorite = rememberUpdatedState(isFavorite)
            val isFavoriteProvider = remember { { latestIsFavorite.value } }
            val onPlayPause = remember(playerViewModel) { playerViewModel::playPause }
            val onSeek = remember(playerViewModel) { playerViewModel::seekTo }
            val onNext = remember(playerViewModel) { playerViewModel::nextSong }
            val onPrevious = remember(playerViewModel) { playerViewModel::previousSong }
            val onShuffleToggle = remember(playerViewModel) { { playerViewModel.toggleShuffle() } }
            val onRepeatToggle = remember(playerViewModel) { playerViewModel::cycleRepeatMode }
            val onFavoriteToggle = remember(playerViewModel) { playerViewModel::toggleFavorite }

            FullPlayerContent(
                currentSong = currentSong,
                currentPlaybackQueue = currentPlaybackQueue,
                currentQueueSourceName = currentQueueSourceName,
                currentMediaItemIndex = fieldStates.currentMediaItemIndex.value,
                isShuffleEnabled = fieldStates.isShuffleEnabled.value,
                shuffleTransitionInProgress = fieldStates.isShuffleTransitionInProgress.value,
                repeatMode = fieldStates.repeatMode.value,
                allowRealtimeUpdates = false,
                expansionFractionProvider = { 1f },
                currentSheetState = PlayerSheetState.EXPANDED,
                carouselStyle = carouselStyle,
                loadingTweaks = fullPlayerLoadingTweaks,
                playerViewModel = playerViewModel,
                currentPositionProvider = currentPositionProvider,
                isPlayingProvider = fieldStates.isPlayingProvider,
                playWhenReadyProvider = fieldStates.playWhenReadyProvider,
                repeatModeProvider = fieldStates.repeatModeProvider,
                isShuffleEnabledProvider = fieldStates.isShuffleEnabledProvider,
                totalDurationProvider = fieldStates.totalDurationProvider,
                lyricsProvider = fieldStates.lyricsProvider,
                isCastConnecting = isCastConnecting,
                isFavoriteProvider = isFavoriteProvider,
                onShowQueueClicked = onShowQueueClicked,
                onQueueDragStart = onQueueDragStart,
                onQueueDrag = onQueueDrag,
                onQueueRelease = onQueueRelease,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onNext = onNext,
                onPrevious = onPrevious,
                onCollapse = {},
                onShowCastClicked = {},
                onShuffleToggle = onShuffleToggle,
                onRepeatToggle = onRepeatToggle,
                onFavoriteToggle = onFavoriteToggle
            )
        }
    }
}
