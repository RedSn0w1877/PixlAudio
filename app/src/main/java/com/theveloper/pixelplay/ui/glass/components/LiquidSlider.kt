package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.ui.platform.LocalViewConfiguration
import com.kyant.backdrop.backdrops.emptyBackdrop
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.derivedStateOf
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
 *
 * Material `Slider` parity, so settings can use it as a drop-in: [steps] snaps every reported value
 * (and the blob, once released) to `steps + 1` equal intervals, while the blob follows the finger
 * freely during a drag; [onValueChangeFinished] fires once after a drag or a tap; a disabled slider
 * ([enabled] false) ignores touches and draws at 38 % alpha. While a drag is running the caller's
 * echoed value is not fed back into the blob (a snapped echo would swallow sub-step drag deltas).
 *
 * At rest the thumb is opaque white, so it samples an empty backdrop then (identical pixels, no
 * offscreen backdrop draw); the real backdrop is swapped in while the thumb is held.
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
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val trackBackdrop = rememberLayerBackdrop()
    val receiver = rememberGlassLightReceiver()
    val currentAccent by rememberUpdatedState(accentColor)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val snap: (Float) -> Float = remember(valueRange, steps) {
        { raw -> snapSliderValue(raw, valueRange, steps) }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val currentTrackWidth by rememberUpdatedState(trackWidth)

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        val dragging = remember { booleanArrayOf(false) }
        val slop = remember { floatArrayOf(0f) }
        val startValue = remember { floatArrayOf(0f) }
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val lastReported = remember { floatArrayOf(Float.NaN) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.8f,
                onDragStarted = {
                    dragging[0] = true
                    slop[0] = 0f
                    startValue[0] = targetValue
                },
                onDragStopped = {
                    dragging[0] = false
                    if (didDrag && wasCancelled) {
                        // A scrolling page took the gesture mid-drag: put the value back.
                        animateToValue(startValue[0])
                        lastReported[0] = Float.NaN
                        currentOnValueChange(startValue[0])
                        didDrag = false
                    } else if (didDrag) {
                        val settled = snap(targetValue)
                        if (settled != targetValue) animateToValue(settled)
                        lastReported[0] = Float.NaN
                        currentOnValueChange(settled)
                        currentOnValueChangeFinished?.invoke()
                        didDrag = false
                    }
                },
                onDrag = drag@{ _, dragAmount ->
                    if (!didDrag) {
                        // Horizontal travel past the touch slop starts the drag; jitter while a
                        // list scrolls never changes the value.
                        slop[0] += dragAmount.x
                        if (kotlin.math.abs(slop[0]) <= touchSlop) return@drag
                        didDrag = true
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) *
                        (dragAmount.x / currentTrackWidth.coerceAtLeast(1))
                    val next = if (isLtr) (targetValue + delta).coerceIn(valueRange)
                    else (targetValue - delta).coerceIn(valueRange)
                    // Move the blob right away; the caller's value catches up through onValueChange.
                    updateValue(next)
                    val reported = snap(next)
                    if (reported != lastReported[0]) {
                        lastReported[0] = reported
                        currentOnValueChange(reported)
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { value ->
                    if (!dragging[0] && dampedDragAnimation.targetValue != value) {
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
                .then(
                    if (enabled) {
                        Modifier.pointerInput(animationScope) {
                            detectTapGestures { position ->
                                val delta = (valueRange.endInclusive - valueRange.start) *
                                    (position.x / currentTrackWidth.coerceAtLeast(1))
                                val targetValue = snap(
                                    (if (isLtr) valueRange.start + delta
                                    else valueRange.endInclusive - delta)
                                        .coerceIn(valueRange)
                                )
                                dampedDragAnimation.animateToValue(targetValue)
                                currentOnValueChange(targetValue)
                                currentOnValueChangeFinished?.invoke()
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .height(6f.dp)
                .fillMaxWidth()
        )

        val heldBackdrop = rememberCombinedBackdrop(
            backdrop,
            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                val progress = dampedDragAnimation.pressProgress
                val scaleX = lerp(2f / 3f, 1f, progress)
                val scaleY = lerp(0f, 1f, progress)
                scale(scaleX, scaleY) {
                    drawBackdrop()
                }
            }
        )
        val restBackdrop = remember { emptyBackdrop() }
        // Recomposes only when the thumb starts or stops being held, never per frame.
        val thumbHeld by remember(dampedDragAnimation) {
            derivedStateOf { dampedDragAnimation.pressProgress > 0f }
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .glassLightEmitter({ currentAccent })
                .drawBackdrop(
                    backdrop = if (thumbHeld) heldBackdrop else restBackdrop,
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

/**
 * Snaps [raw] to `steps + 1` equal intervals of [range] (Material `Slider` steps); no snapping when
 * [steps] is 0 or less.
 */
internal fun snapSliderValue(raw: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val clamped = raw.coerceIn(range)
    if (steps <= 0) return clamped
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val intervals = steps + 1
    val index = kotlin.math.round((clamped - range.start) / span * intervals)
    return (range.start + span * index / intervals).coerceIn(range)
}
