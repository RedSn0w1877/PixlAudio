package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import kotlin.math.roundToInt

/**
 * Universal Slider: a Liquid Glass slider when [isGlassEnabled], a standard Material 3 [Slider]
 * otherwise. Both honour every parameter — in particular [steps] and [onValueChangeFinished],
 * which settings rely on to actually save.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    if (!isGlassEnabled) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled
        )
        return
    }

    LiquidSlider(
        value = { value },
        onValueChange = onValueChange,
        valueRange = valueRange,
        visibilityThreshold = 0.001f,
        backdrop = LocalAppBackdrop.current,
        modifier = modifier,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled
    )
}

/** Snaps [value] to the nearest of `steps + 2` evenly spaced stops across [range]. */
internal fun snapToSteps(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val clamped = value.coerceIn(range)
    if (steps <= 0) return clamped
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val stepSize = span / (steps + 1)
    val index = ((clamped - range.start) / stepSize).roundToInt()
    return (range.start + index * stepSize).coerceIn(range)
}

/**
 * Port of the Backdrop catalog's `LiquidSlider` (Kyant0/AndroidLiquidGlass, Apache-2.0): a
 * centred track and a white capsule thumb that turns into clear glass while dragged, with the
 * damped squash-and-stretch physics.
 *
 * Drag anywhere on the track (no touch slop) or tap to jump. With [steps] the reported value is
 * always on a stop, and on release the thumb settles onto it before [onValueChangeFinished] fires.
 * Disabled: 38% alpha and no gestures.
 *
 * The thumb refracts only the track beneath it — never the page — so it needs no combined backdrop
 * (the page under a settings row is an empty backdrop anyway). [backdrop] is kept for callers.
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = glassIsDark()
    val accentColor = scheme.primary
    val trackColor = scheme.onSurface.copy(alpha = if (isDark) 0.20f else 0.12f)
    val thumbRecipe = resolveRecipe(GlassRole.Thumb)
    val reduceMotion = LocalGlassReduceMotion.current

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val trackBackdrop = rememberPageBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value(), valueRange, steps)
                if (!enabled) disabled() else setProgress { target ->
                    val snapped = snapToSteps(target, valueRange, steps)
                    currentOnValueChange(snapped)
                    currentOnValueChangeFinished?.invoke()
                    true
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // The raw finger position during a drag; the reported value is this snapped to a stop.
        var rawValue by remember { mutableFloatStateOf(value()) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }

        LaunchedEffect(value()) {
            val external = value()
            if (external != snapToSteps(rawValue, valueRange, steps)) {
                rawValue = external
                dampedDragAnimation.updateValue(external)
            }
        }

        fun valueAt(x: Float): Float {
            val fraction = (x / trackWidth).fastCoerceIn(0f, 1f)
            val span = valueRange.endInclusive - valueRange.start
            return if (isLtr) valueRange.start + fraction * span
            else valueRange.endInclusive - fraction * span
        }

        fun report(raw: Float) {
            rawValue = raw.coerceIn(valueRange)
            currentOnValueChange(snapToSteps(rawValue, valueRange, steps))
        }

        fun finish() {
            val snapped = snapToSteps(rawValue, valueRange, steps)
            rawValue = snapped
            dampedDragAnimation.updateValue(snapped)
            dampedDragAnimation.release()
            currentOnValueChange(snapped)
            currentOnValueChangeFinished?.invoke()
        }

        val gestures = if (!enabled) {
            Modifier
        } else {
            Modifier
                .pointerInput(animationScope, trackWidth, valueRange, steps, isLtr) {
                    detectTapGestures { position ->
                        val target = snapToSteps(valueAt(position.x), valueRange, steps)
                        rawValue = target
                        dampedDragAnimation.animateToValue(target)
                        currentOnValueChange(target)
                        currentOnValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(animationScope, trackWidth, valueRange, steps, isLtr) {
                    detectHorizontalDragGestures(
                        onDragStart = { position ->
                            dampedDragAnimation.press()
                            report(valueAt(position.x))
                            dampedDragAnimation.updateValue(rawValue)
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val span = valueRange.endInclusive - valueRange.start
                            val delta = span * (dragAmount / trackWidth)
                            report(rawValue + if (isLtr) delta else -delta)
                            dampedDragAnimation.updateValue(rawValue)
                        }
                    )
                }
        }

        val progress: () -> Float = {
            ((dampedDragAnimation.value - valueRange.start) /
                (valueRange.endInclusive - valueRange.start)).fastCoerceIn(0f, 1f)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .then(gestures),
            contentAlignment = Alignment.CenterStart
        ) {
            // Track, recorded so the thumb can refract it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .pageBackdrop(trackBackdrop)
            ) {
                Box(
                    Modifier
                        .clip(GlassShapes.Capsule)
                        .background(trackColor)
                        .height(6.dp)
                        .fillMaxWidth()
                )
                Box(
                    Modifier
                        .clip(GlassShapes.Capsule)
                        .background(accentColor)
                        .height(6.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (constraints.maxWidth * progress()).fastRoundToInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                )
            }

            // Thumb: white capsule at rest, clear refracting glass while pressed.
            val thumbBackdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                val p = dampedDragAnimation.pressProgress
                scale(lerp(2f / 3f, 1f, p), lerp(0f, 1f, p)) { drawBackdrop() }
            }
            val thumbLayer: GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation, reduceMotion) {
                { applyGlassSquash(dampedDragAnimation, velocityDivisor = 10f, reduceMotion = reduceMotion) }
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationX = (-size.width / 2f + trackWidth * progress())
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                            if (isLtr) 1f else -1f
                    }
                    .liquidGlass(
                        recipe = thumbRecipe,
                        shape = GlassShapes.Capsule,
                        materialize = { dampedDragAnimation.pressProgress },
                        backdrop = thumbBackdrop,
                        layerBlock = thumbLayer
                    )
                    .size(40.dp, 24.dp)
            )
        }
    }
}
