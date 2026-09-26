package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.DampedDragAnimation
import com.theveloper.pixelplay.ui.glass.utils.InteractiveHighlight
import com.theveloper.pixelplay.ui.glass.utils.applyBlobTransform
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** One option of a [LiquidSegmented] selector. */
@Immutable
data class SegmentOption(val label: String, val icon: ImageVector? = null)

/**
 * Draggable glass blob selector with LiquidBottomTabs physics (ported from NexHome): tap an option
 * or drag the blob across; it snaps to the nearest option with overshoot, squashes with velocity,
 * swells while held (lens + chromatic aberration) and ticks a haptic per option crossed. Under the
 * blob the options render in [accent] (a hidden accent-tinted copy captured with `layerBackdrop` and
 * refracted by the blob). The blob emits [accent] as light while held. Best for 2–5 options.
 *
 * PixlAudio changes: palette colours; the bar nudge follows the finger in a plain float state (no
 * coroutine per pointer event); frosted-tier blur on API 31–32.
 */
@Composable
fun LiquidSegmented(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LocalGlassPalette.current.accent,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val count = options.size.coerceAtLeast(1)
    val hasIcons = options.any { it.icon != null }
    val barHeight = if (hasIcons) 64.dp else 48.dp
    val blobHeight = barHeight - 8.dp
    val optionsBackdrop = rememberLayerBackdrop()
    val barReceiver = rememberGlassLightReceiver()
    val blobReceiver = rememberGlassLightReceiver()
    val palette = LocalGlassPalette.current
    val capability = LocalGlassCapability.current
    val blobRest = if (palette.isDark) Color.White.copy(alpha = 0.14f) else palette.blobRest
    val currentAccent by rememberUpdatedState(accent)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val haptic = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val tabWidth = with(density) { (maxWidthPx - 8.dp.toPx()) / count }
        val currentTabWidth by rememberUpdatedState(tabWidth)

        var fingerOffset by remember { mutableFloatStateOf(0f) }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, maxWidthPx) {
            derivedStateOf {
                val raw = fingerOffset + offsetAnimation.value
                val fraction = (raw / maxWidthPx.coerceAtLeast(1f)).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val scope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, count - 1)) }
        val tickHolder = remember { intArrayOf(currentIndex) }
        val blob = remember(scope, count) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.32f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    val released = fingerOffset
                    scope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + released)
                        fingerOffset = 0f
                        offsetAnimation.animateTo(0f, spring(0.45f, 260f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    val next = (targetValue + dragAmount.x / currentTabWidth * if (isLtr) 1f else -1f)
                        .fastCoerceIn(0f, (count - 1).toFloat())
                    updateValue(next)
                    val tick = next.fastRoundToInt()
                    if (tick != tickHolder[0]) {
                        tickHolder[0] = tick
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    fingerOffset += dragAmount.x
                },
            )
        }
        LaunchedEffect(blob) {
            snapshotFlow { currentSelected }.collectLatest { index ->
                currentIndex = index.coerceIn(0, count - 1)
            }
        }
        LaunchedEffect(blob) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    tickHolder[0] = index
                    blob.animateToValue(index.toFloat())
                    if (index != currentSelected) currentOnSelect(index)
                }
        }

        val highlight = remember(scope, blob) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    val inset = with(density) { 4.dp.toPx() }
                    Offset(
                        if (isLtr) inset + (blob.value + 0.5f) * currentTabWidth + panelOffset
                        else size.width - inset - (blob.value + 0.5f) * currentTabWidth + panelOffset,
                        size.height / 2f,
                    )
                },
                color = { kitGlowColor(currentAccent) },
            )
        }

        val select: (Int) -> Unit = { index ->
            if (index != currentIndex) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentIndex = index
        }

        // Visible bar with the options in the secondary content colour.
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        if (capability.hasLens) lens(14.dp.toPx(), 28.dp.toPx()) else blur(FrostedBlur.toPx())
                    },
                    highlight = { barReceiver.highlight(Highlight.Plain) },
                    shadow = null,
                    layerBlock = {
                        val w = size.width
                        if (w > 0f) {
                            val scale = lerp(1f, 1f + 10.dp.toPx() / w, blob.pressProgress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = {
                        drawRect(palette.tintStrong)
                    },
                )
                .then(barReceiver.modifier)
                .then(highlight.modifier)
                .height(barHeight)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                SegmentItem(option, tint = palette.secondary, scale = { 1f }, onClick = { select(index) })
            }
        }

        // Hidden accent-tinted copy, captured for the blob to refract.
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(optionsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        vibrancy()
                        lens(14.dp.toPx() * p, 28.dp.toPx() * p)
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(palette.tintStrong) },
                )
                .then(highlight.modifier)
                .height(blobHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                SegmentItem(
                    option,
                    tint = Color.White,
                    scale = { lerp(1f, 1.12f, blob.pressProgress) },
                    onClick = { select(index) },
                )
            }
        }

        // The draggable glass blob.
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) blob.value * currentTabWidth + panelOffset
                        else size.width - (blob.value + 1f) * currentTabWidth + panelOffset
                }
                .then(highlight.gestureModifier)
                .then(blob.modifier)
                .glassLightEmitter({ currentAccent })
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, optionsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        lens(
                            lerp(4.dp.toPx(), 10.dp.toPx(), p),
                            lerp(6.dp.toPx(), 16.dp.toPx(), p),
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        blobReceiver.highlight(Highlight.Default.copy(alpha = lerp(0.4f, 1f, p)))
                    },
                    shadow = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.12f), alpha = lerp(0.4f, 1f, p))
                    },
                    innerShadow = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        InnerShadow(radius = 8.dp * p, alpha = p)
                    },
                    layerBlock = { applyBlobTransform(blob, velocityDivisor = 10f) },
                    onDrawSurface = {
                        val p = blob.pressProgress.fastCoerceIn(0f, 1f)
                        drawRect(blobRest, alpha = 1f - p)
                        drawRect(currentAccent.copy(alpha = 0.14f))
                    },
                )
                .then(blobReceiver.modifier)
                .height(blobHeight)
                .fillMaxWidth(1f / count)
        )
    }
}

@Composable
private fun RowScope.SegmentItem(
    option: SegmentOption,
    tint: Color,
    scale: () -> Float,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(Capsule())
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (option.icon != null) GlassIcon(option.icon, tint = tint, size = 20.dp)
        GlassText(option.label, style = GlassType.Label, color = tint, maxLines = 1)
    }
}
