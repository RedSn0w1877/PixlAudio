package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.theveloper.pixelplay.ui.glass.GlassPlacement
import com.theveloper.pixelplay.ui.glass.GlassRole
import com.theveloper.pixelplay.ui.glass.GlassShapes
import com.theveloper.pixelplay.ui.glass.InteractiveHighlight
import com.theveloper.pixelplay.ui.glass.LocalGlassReduceMotion
import com.theveloper.pixelplay.ui.glass.QuantizedCornerShapeCache
import com.theveloper.pixelplay.ui.glass.applyGlassPress
import com.theveloper.pixelplay.ui.glass.glassIsDark
import com.theveloper.pixelplay.ui.glass.glassPanel
import com.theveloper.pixelplay.ui.glass.glassPlacement
import com.theveloper.pixelplay.ui.glass.liquidGlass
import com.theveloper.pixelplay.ui.glass.resolveRecipe
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    glassGlyphTint: Color = LocalMaterialTheme.current.onPrimaryContainer,
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
    val coroutineScope = rememberCoroutineScope()

    val motionScheme = remember { MotionScheme.expressive() }
    val defaultSpatialDpSpec = remember { motionScheme.defaultSpatialSpec<Dp>() }
    // Real glass only where there is something to refract (the full player provides its static
    // background backdrop). Material 3 and the tonal/fill fallbacks keep the original buttons.
    val glass = glassPlacement() == GlassPlacement.Glass

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            fun weightFor(button: PlaybackButtonType): Float = when (lastClicked) {
                button -> expansionWeight
                null -> baseWeight
                else -> compressionWeight
            }

            val prevWeight by animateFloatAsState(
                targetValue = weightFor(PlaybackButtonType.PREVIOUS),
                animationSpec = pressAnimationSpec,
                label = "prevWeight"
            )
            val onPreviousClick = {
                lastClicked = PlaybackButtonType.PREVIOUS
                clickTrigger++
                coroutineScope.launch {
                    delay(180)
                    onPrevious()
                }
                Unit
            }
            if (glass) {
                TransientGlassButton(
                    onClick = onPreviousClick,
                    modifier = Modifier
                        .weight(prevWeight)
                        .fillMaxHeight()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = glassGlyphTint,
                        modifier = Modifier.size(iconSize)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(prevWeight)
                        .fillMaxHeight()
                        .glassPanel(CircleShape, colorPreviousButton, effectScale = 0.35f)
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
            }

            val playWeight by animateFloatAsState(
                targetValue = weightFor(PlaybackButtonType.PLAY_PAUSE),
                animationSpec = pressAnimationSpec,
                label = "playWeight"
            )
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
            if (glass) {
                // Persistent, prominent glass. Its corner morph is read in the draw phase through
                // playCornerState, so the morph never recomposes this row.
                PlayPauseGlassButton(
                    onClick = onPlayPauseClick,
                    cornerRadius = { playCornerState.value },
                    accent = colorPlayPause,
                    modifier = Modifier
                        .weight(playWeight)
                        .fillMaxHeight()
                ) {
                    MorphingPlayPauseIcon(
                        isPlaying = playPauseVisualState,
                        tint = tintPlayPauseIcon,
                        size = playPauseIconSize,
                        motionScheme = motionScheme
                    )
                }
            } else {
                val playCorner = playCornerState.value
                Box(
                    modifier = Modifier
                        .weight(playWeight)
                        .fillMaxHeight()
                        .graphicsLayer {
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
                        // In Material 3 this is exactly `clip + background` inside the squircle
                        // clip above; the glass path is PlayPauseGlassButton.
                        .glassPanel(
                            shape = RoundedCornerShape(playCorner),
                            color = colorPlayPause,
                            effectScale = 0.4f,
                            tintAlpha = 0.55f
                        )
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
            }

            val nextWeight by animateFloatAsState(
                targetValue = weightFor(PlaybackButtonType.NEXT),
                animationSpec = pressAnimationSpec,
                label = "nextWeight"
            )
            val onNextClick = {
                lastClicked = PlaybackButtonType.NEXT
                clickTrigger++
                coroutineScope.launch {
                    delay(180)
                    onNext()
                }
                Unit
            }
            if (glass) {
                TransientGlassButton(
                    onClick = onNextClick,
                    modifier = Modifier
                        .weight(nextWeight)
                        .fillMaxHeight()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = glassGlyphTint,
                        modifier = Modifier.size(iconSize)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(nextWeight)
                        .fillMaxHeight()
                        .glassPanel(CircleShape, colorNextButton, effectScale = 0.35f)
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
        }
    }
}

/**
 * Prev/next in glass mode (spec §2, "Prev/next"): only the glyph at rest; the glass *materialises*
 * under the finger — lens, highlight and a faint white body grow in with the press, and the
 * drawBackdrop node is attached only while the press is in progress. Apple's rule: glass appears
 * by adding lensing, not by fading a panel in.
 */
@Composable
private fun TransientGlassButton(
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val reduceMotion = LocalGlassReduceMotion.current
    val recipe = resolveRecipe(GlassRole.TransientControl)
    val pressProgress = remember(highlight) { { highlight.pressProgress } }
    // Composition only hears about the press starting and ending, never its progress.
    val materialized by remember(highlight) { derivedStateOf { highlight.pressProgress > 0f } }
    val layerBlock: GraphicsLayerScope.() -> Unit = remember(highlight, reduceMotion) {
        { applyGlassPress(highlight, reduceMotion) }
    }
    Box(
        modifier = modifier
            // Gesture + click first, so attaching the glass below never disturbs a live press.
            .then(highlight.gestureModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (materialized) {
                    Modifier.liquidGlass(
                        recipe = recipe,
                        shape = GlassShapes.Capsule,
                        materialize = pressProgress,
                        layerBlock = layerBlock
                    )
                } else {
                    Modifier
                }
            )
            .then(highlight.modifier),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Play/pause in glass mode: persistent glass with the prominent recipe in the album accent, the
 * press light and squash, and the pause-to-play corner morph. The morph goes through
 * [QuantizedCornerShapeCache] because drawBackdrop only re-derives its outline when the shape it
 * is given stops being equal; a plain RoundedCornerShape (not a kyant shape) keeps the highlight
 * shader's corner radii right, since it only reads a CornerBasedShape's radii.
 *
 * Over a bright album background it adds Apple's "Clear" dimming under the tint.
 */
@Composable
private fun PlayPauseGlassButton(
    onClick: () -> Unit,
    cornerRadius: () -> Dp,
    accent: Color,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val reduceMotion = LocalGlassReduceMotion.current
    val recipe = resolveRecipe(GlassRole.PlayPause)
    val shapes = remember { QuantizedCornerShapeCache() }
    val latestCornerRadius by rememberUpdatedState(cornerRadius)
    val shape: () -> Shape = remember(shapes) { { shapes.get(latestCornerRadius()) } }
    val layerBlock: GraphicsLayerScope.() -> Unit = remember(highlight, reduceMotion) {
        { applyGlassPress(highlight, reduceMotion) }
    }
    val dim = if (!glassIsDark()) 0.35f else 0f
    Box(
        modifier = modifier
            .liquidGlass(
                recipe = recipe,
                shape = GlassShapes.Capsule,
                dynamicShape = shape,
                prominent = true,
                accent = accent,
                layerBlock = layerBlock,
                backdropDim = dim
            )
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

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
