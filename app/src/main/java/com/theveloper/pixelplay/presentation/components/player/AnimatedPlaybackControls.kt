package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.components.LocalMaterialTheme
import kotlinx.coroutines.delay
import androidx.compose.runtime.State
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt
import kotlin.math.sign
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private enum class PlaybackButtonType { NONE, PREVIOUS, PLAY_PAUSE, NEXT }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnimatedPlaybackControls(
    isPlayingProvider: () -> Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    baseWeight: Float = 1f,
    expansionWeight: Float = 1.1f,
    compressionWeight: Float = 0.65f,
    pressAnimationSpec: AnimationSpec<Float>,
    releaseDelay: Long = 220L,
    playPauseCornerPlaying: Dp = 60.dp,
    playPauseCornerPaused: Dp = 26.dp,
    colorOtherButtons: Color = LocalMaterialTheme.current.secondaryContainer,
    colorPlayPause: Color = LocalMaterialTheme.current.primary,
    tintPlayPauseIcon: Color = LocalMaterialTheme.current.onPrimary,
    tintOtherIcons: Color = LocalMaterialTheme.current.onSecondaryContainer,
    colorPreviousButton: Color = colorOtherButtons,
    colorNextButton: Color = colorOtherButtons,
    tintPreviousIcon: Color = tintOtherIcons,
    tintNextIcon: Color = tintOtherIcons,
    playPauseIconSize: Dp = 36.dp,
    iconSize: Dp = 32.dp,
) {
    val isPlaying = isPlayingProvider()
    var lastClicked by remember { mutableStateOf<PlaybackButtonType?>(null) }
    var clickTrigger by remember { mutableStateOf(0) }
    val latestIsPlayingProvider by rememberUpdatedState(newValue = isPlayingProvider)
    val latestLastClicked by rememberUpdatedState(newValue = lastClicked)
    val isPlayPauseLocked =
        lastClicked == PlaybackButtonType.NEXT || lastClicked == PlaybackButtonType.PREVIOUS
    var playPauseVisualState by remember { mutableStateOf(isPlaying) }
    var pendingPlayPauseState by remember { mutableStateOf<Boolean?>(null) }
    val hapticFeedback = LocalHapticFeedback.current

    val motionScheme = remember { MotionScheme.expressive() }
    val defaultSpatialDpSpec = remember { motionScheme.defaultSpatialSpec<Dp>() }

    LaunchedEffect(lastClicked, clickTrigger) {
        if (lastClicked != null) {
            val delayTime = when (lastClicked) {
                PlaybackButtonType.NEXT, PlaybackButtonType.PREVIOUS -> 600L
                else -> releaseDelay
            }
            delay(delayTime)
            lastClicked = null
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            pendingPlayPauseState = true
            return@LaunchedEffect
        }

        val shouldDelay = latestLastClicked != PlaybackButtonType.PLAY_PAUSE
        if (shouldDelay) {
            delay(releaseDelay)
        }
        if (!latestIsPlayingProvider()) {
            pendingPlayPauseState = false
        }
    }

    LaunchedEffect(isPlayPauseLocked, pendingPlayPauseState) {
        if (!isPlayPauseLocked) {
            pendingPlayPauseState?.let {
                playPauseVisualState = it
                pendingPlayPauseState = null
            }
        }
    }

    fun weightFor(button: PlaybackButtonType): Float = when (lastClicked) {
        button -> expansionWeight
        null -> baseWeight
        else -> compressionWeight
    }

    // The press weights animate for up to ~0.8 s per press. They are read only in the measure
    // policy below, so a press relays out the row each frame instead of recomposing it.
    val prevWeight = animateFloatAsState(
        targetValue = weightFor(PlaybackButtonType.PREVIOUS),
        animationSpec = pressAnimationSpec,
        label = "prevWeight"
    )
    val playWeight = animateFloatAsState(
        targetValue = weightFor(PlaybackButtonType.PLAY_PAUSE),
        animationSpec = pressAnimationSpec,
        label = "playWeight"
    )
    val nextWeight = animateFloatAsState(
        targetValue = weightFor(PlaybackButtonType.NEXT),
        animationSpec = pressAnimationSpec,
        label = "nextWeight"
    )
    val rowMeasurePolicy = remember(prevWeight, playWeight, nextWeight) {
        weightedControlsMeasurePolicy(prevWeight, playWeight, nextWeight)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            measurePolicy = rowMeasurePolicy,
            content = {
                // The command goes out on the tap; the press-weight animation (driven by
                // lastClicked, read in the measure policy) runs alongside it.
                val onPreviousClick = {
                    lastClicked = PlaybackButtonType.PREVIOUS
                    clickTrigger++
                    onPrevious()
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colorPreviousButton)
                        .clickable(onClick = onPreviousClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = tintPreviousIcon,
                        modifier = Modifier.size(iconSize)
                    )
                }

                val playCornerState = animateDpAsState(
                    targetValue = if (!playPauseVisualState) playPauseCornerPlaying else playPauseCornerPaused,
                    animationSpec = defaultSpatialDpSpec,
                    label = "playCorner"
                )
                val onPlayPauseClick = {
                    lastClicked = PlaybackButtonType.PLAY_PAUSE
                    clickTrigger++
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                }
                // The corner morph is read in the layer blocks below, never in composition.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .graphicsLayer {
                            val playCorner = playCornerState.value
                            clip = true
                            shape = AbsoluteSmoothCornerShape(
                                cornerRadiusTL = playCorner,
                                smoothnessAsPercentTR = 60,
                                cornerRadiusBL = playCorner,
                                smoothnessAsPercentTL = 60,
                                cornerRadiusTR = playCorner,
                                smoothnessAsPercentBL = 60,
                                cornerRadiusBR = playCorner,
                                smoothnessAsPercentBR = 60
                            )
                        }
                        .graphicsLayer {
                            clip = true
                            shape = RoundedCornerShape(playCornerState.value)
                        }
                        .background(colorPlayPause)
                        .clickable(onClick = onPlayPauseClick),
                    contentAlignment = Alignment.Center
                ) {
                    MorphingPlayPauseIcon(
                        isPlaying = playPauseVisualState,
                        tint = tintPlayPauseIcon,
                        size = playPauseIconSize,
                        motionScheme = motionScheme
                    )
                }

                val onNextClick = {
                    lastClicked = PlaybackButtonType.NEXT
                    clickTrigger++
                    onNext()
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colorNextButton)
                        .clickable(onClick = onNextClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = tintNextIcon,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        )
    }
}

/**
 * `Row(horizontalArrangement = spacedBy(6.dp))` over three `weight(fill = true)` children,
 * measured exactly as Row does it (same rounding and remainder distribution, same spacedBy
 * placement in both layout directions), but with the weights read from their animation states
 * here in the measure pass.
 */
private fun weightedControlsMeasurePolicy(
    prevWeight: State<Float>,
    playWeight: State<Float>,
    nextWeight: State<Float>
): MeasurePolicy = MeasurePolicy { measurables, constraints ->
    val spacingPx = ControlsSpacing.roundToPx()
    val w0 = prevWeight.value
    val w1 = playWeight.value
    val w2 = nextWeight.value
    val totalWeight = w0 + w1 + w2
    val targetSpace = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val arrangementSpacingTotal = spacingPx * (measurables.size - 1)
    val remaining = (targetSpace - arrangementSpacingTotal).coerceAtLeast(0)
    val unit = if (totalWeight > 0f) remaining / totalWeight else 0f
    var remainder = remaining -
        (unit * w0).roundToInt() - (unit * w1).roundToInt() - (unit * w2).roundToInt()

    var weightedSpace = 0
    var crossAxisSize = 0
    val placeables = arrayOfNulls<Placeable>(measurables.size)
    for (i in measurables.indices) {
        val weight = when (i) {
            0 -> w0
            1 -> w1
            else -> w2
        }
        val sign = remainder.sign
        remainder -= sign
        val size = ((unit * weight).roundToInt() + sign).coerceAtLeast(0)
        val placeable = measurables[i].measure(
            Constraints(
                minWidth = size,
                maxWidth = size,
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
        )
        placeables[i] = placeable
        weightedSpace += placeable.width
        crossAxisSize = maxOf(crossAxisSize, placeable.height)
    }

    val width = constraints.constrainWidth(
        maxOf(weightedSpace + arrangementSpacingTotal, constraints.minWidth)
    )
    val height = constraints.constrainHeight(maxOf(crossAxisSize, constraints.minHeight))
    val ltr = layoutDirection == LayoutDirection.Ltr
    layout(width, height) {
        // Arrangement.spacedBy: in RTL it walks the children from the last one.
        var occupied = 0
        for (k in placeables.indices) {
            val i = if (ltr) k else placeables.lastIndex - k
            val placeable = placeables[i]!!
            val x = minOf(occupied, width - placeable.width)
            val lastSpace = minOf(spacingPx, width - x - placeable.width)
            occupied = x + placeable.width + lastSpace
            // Alignment.CenterVertically
            placeable.place(x, ((height - placeable.height) / 2f).roundToInt())
        }
    }
}

private val ControlsSpacing = 6.dp

@Composable
private fun MorphingPlayPauseIcon(
    isPlaying: Boolean,
    tint: Color,
    size: Dp,
    motionScheme: MotionScheme
) {
    Crossfade(
        targetState = isPlaying,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "playPauseCrossfade"
    ) { playing ->
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pausar" else "Reproducir",
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}
