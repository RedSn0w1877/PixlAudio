package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest

/**
 * Universal Slider that renders a Liquid Glass slider when [isGlassEnabled] is true,
 * and a standard Material 3 Slider when in Material 3 theme mode.
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

    val backdrop = LocalAppBackdrop.current
    LiquidSlider(
        value = { value },
        onValueChange = onValueChange,
        valueRange = valueRange,
        visibilityThreshold = 0.001f,
        backdrop = backdrop,
        modifier = modifier
    )
}

/**
 * Port of LiquidSlider with perfectly centered track & thumb, damped drag physics, popping thumb, and refraction.
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberPageBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentVal by remember { mutableFloatStateOf(value()) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.65f,
                onDragStarted = {},
                onDragStopped = {
                    onValueChange(currentVal)
                },
                onDrag = { _, dragAmount ->
                    val rangeLength = valueRange.endInclusive - valueRange.start
                    val delta = rangeLength * (dragAmount.x / trackWidth)
                    currentVal = (currentVal + if (isLtr) delta else -delta).coerceIn(valueRange)
                    updateValue(currentVal)
                    onValueChange(currentVal)
                }
            )
        }

        LaunchedEffect(value()) {
            val externalVal = value()
            if (externalVal != currentVal) {
                currentVal = externalVal
                dampedDragAnimation.updateValue(externalVal)
            }
        }

        val sliderProgress = ((dampedDragAnimation.value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .fastCoerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(animationScope, trackWidth, valueRange) {
                    detectTapGestures { position ->
                        val rangeLength = valueRange.endInclusive - valueRange.start
                        val fraction = (position.x / trackWidth).fastCoerceIn(0f, 1f)
                        val targetVal = (if (isLtr) valueRange.start + fraction * rangeLength
                        else valueRange.endInclusive - fraction * rangeLength).coerceIn(valueRange)
                        currentVal = targetVal
                        dampedDragAnimation.animateToValue(targetVal)
                        onValueChange(targetVal)
                    }
                }
                .pointerInput(animationScope, trackWidth, valueRange) {
                    detectDragGestures(
                        onDragStart = { position ->
                            dampedDragAnimation.press()
                            val rangeLength = valueRange.endInclusive - valueRange.start
                            val fraction = (position.x / trackWidth).fastCoerceIn(0f, 1f)
                            val targetVal = (if (isLtr) valueRange.start + fraction * rangeLength
                            else valueRange.endInclusive - fraction * rangeLength).coerceIn(valueRange)
                            currentVal = targetVal
                            dampedDragAnimation.updateValue(targetVal)
                            onValueChange(targetVal)
                        },
                        onDragEnd = {
                            dampedDragAnimation.release()
                            onValueChange(currentVal)
                        },
                        onDragCancel = {
                            dampedDragAnimation.release()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val rangeLength = valueRange.endInclusive - valueRange.start
                            val delta = rangeLength * (dragAmount.x / trackWidth)
                            currentVal = (currentVal + if (isLtr) delta else -delta).coerceIn(valueRange)
                            dampedDragAnimation.updateValue(currentVal)
                            onValueChange(currentVal)
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Track (8dp height) centered vertically
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .align(Alignment.CenterStart)
                    .pageBackdrop(trackBackdrop)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(trackColor)
                        .height(8.dp)
                        .fillMaxWidth()
                )

                Box(
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accentColor)
                        .height(8.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (constraints.maxWidth * sliderProgress).fastRoundToInt()
                            layout(width, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                )
            }

            // Liquid glass thumb (44dp x 28dp) centered vertically
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationX = (-size.width / 2f + trackWidth * sliderProgress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                    }
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
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(12f.dp.toPx() * (1f - progress))
                            lens(
                                20f.dp.toPx() * (1f + progress * 0.6f),
                                28f.dp.toPx() * (1f + progress * 0.6f),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.2f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.2f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.25f))
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(radius = 6.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        }
                    )
                    .size(44.dp, 28.dp)
            )
        }
    }
}
