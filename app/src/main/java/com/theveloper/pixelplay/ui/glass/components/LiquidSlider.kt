package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.DampedDragAnimation
import com.theveloper.pixelplay.ui.glass.utils.applyBlobTransform
import kotlinx.coroutines.flow.collectLatest

/**
 * Ported from the Backdrop library's own catalog app (LiquidSlider.kt, Apache-2.0) via NexHome,
 * tuned bouncier: the thumb blob wobbles and squashes with velocity, receives light spill and emits
 * [accentColor] while held. Tap the track to jump.
 *
 * PixlAudio changes: the accent fill is drawn in the track's draw pass (a rounded rect of the
 * current width) instead of re-measuring a `layout {}` box on every frame (NexHome defect); colours
 * default to the glass palette; [backdrop] defaults to [LocalGlassBackdrop]; the value-sync effect
 * reads [value] / [onValueChange] through `rememberUpdatedState`.
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    accentColor: Color = LocalGlassPalette.current.accent,
    trackColor: Color = LocalGlassPalette.current.track,
    thumbColor: Color = LocalGlassPalette.current.thumb,
) {
    val trackBackdrop = rememberLayerBackdrop()
    val receiver = rememberGlassLightReceiver()
    val currentAccent by rememberUpdatedState(accentColor)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val currentTrackWidth by rememberUpdatedState(trackWidth)

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.8f,
                onDragStarted = {},
                onDragStopped = {
                    if (didDrag) {
                        currentOnValueChange(targetValue)
                        didDrag = false
                    }
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) *
                        (dragAmount.x / currentTrackWidth.coerceAtLeast(1))
                    val next = if (isLtr) (targetValue + delta).coerceIn(valueRange)
                    else (targetValue - delta).coerceIn(valueRange)
                    // Move the blob right away; the caller's value catches up through onValueChange.
                    updateValue(next)
                    currentOnValueChange(next)
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { value ->
                    if (dampedDragAnimation.targetValue != value) {
                        dampedDragAnimation.updateValue(value)
                    }
                }
        }

        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    drawRect(trackColor)
                    // Accent fill: the same pixels as the catalog's capsule-clipped fill box, drawn
                    // here so a moving value only redraws, never re-measures.
                    val progress = dampedDragAnimation.progress.fastCoerceIn(0f, 1f)
                    val fillWidth = (size.width * progress).fastRoundToInt().toFloat()
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = if (isLtr) Offset.Zero else Offset(size.width - fillWidth, 0f),
                            size = Size(fillWidth, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    }
                }
                .pointerInput(animationScope) {
                    detectTapGestures { position ->
                        val delta = (valueRange.endInclusive - valueRange.start) *
                            (position.x / currentTrackWidth.coerceAtLeast(1))
                        val targetValue =
                            (if (isLtr) valueRange.start + delta
                            else valueRange.endInclusive - delta)
                                .coerceIn(valueRange)
                        dampedDragAnimation.animateToValue(targetValue)
                        currentOnValueChange(targetValue)
                    }
                }
                .height(6f.dp)
                .fillMaxWidth()
        )

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(dampedDragAnimation.modifier)
                .glassLightEmitter({ currentAccent })
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // At rest the thumb is opaque white, so the blur would be invisible work.
                        if (progress > 0f) blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        receiver.highlight(
                            if (progress > 0f) {
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = progress
                                )
                            } else {
                                null
                            }
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress > 0f) InnerShadow(radius = 4f.dp * progress, alpha = progress) else null
                    },
                    layerBlock = {
                        applyBlobTransform(dampedDragAnimation, velocityDivisor = 10f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(thumbColor.copy(alpha = (1f - progress).fastCoerceIn(0f, 1f)))
                    }
                )
                .then(receiver.modifier)
                .size(40f.dp, 24f.dp)
        )
    }
}
