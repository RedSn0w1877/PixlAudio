package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.DampedDragAnimation
import com.theveloper.pixelplay.ui.glass.utils.InteractiveHighlight
import com.theveloper.pixelplay.ui.glass.utils.applyBlobTransform
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Ported from the Backdrop library's own catalog app (LiquidBottomTabs.kt, Apache-2.0) via NexHome —
 * the "draggable blob" nav bar: the selection pill can be dragged between tabs and snaps (with
 * overshoot) to the nearest one. The bar and blob receive light spill; the blob emits [accentColor]
 * while held. Three `drawBackdrop` nodes + one `layerBackdrop` (the hidden accent-tinted copy of the
 * row that the blob refracts, which is what paints the selected icon in the accent).
 *
 * PixlAudio changes: colours default to the glass palette (accent = album colour, bar and blob wash
 * per light/dark); [backdrop] defaults to [LocalGlassBackdrop]; [selectedTabIndex] and
 * [onTabSelected] go through `rememberUpdatedState` (NexHome keyed the index state on the lambda, so
 * an un-remembered lambda reset it on every recomposition); the 4 dp bar nudge follows the finger in
 * a plain float state instead of launching a `snapTo` coroutine per pointer event.
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    accentColor: Color = LocalGlassPalette.current.accent,
    containerColor: Color = LocalGlassPalette.current.bar,
    blobRestColor: Color = LocalGlassPalette.current.blobRest,
    content: @Composable RowScope.() -> Unit
) {
    val tabsBackdrop = rememberLayerBackdrop()
    val containerReceiver = rememberGlassLightReceiver()
    val blobReceiver = rememberGlassLightReceiver()
    val currentAccent by rememberUpdatedState(accentColor)
    val currentSelectedTabIndex by rememberUpdatedState(selectedTabIndex)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val tabWidth = with(density) {
            (maxWidthPx - 8f.dp.toPx()) / tabsCount
        }
        val currentTabWidth by rememberUpdatedState(tabWidth)

        // Bar nudge toward the drag: the finger's part lives in a plain float state (no coroutine
        // per pointer event); the release springs [offsetAnimation] back to zero.
        var fingerOffset by remember { mutableFloatStateOf(0f) }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, maxWidthPx) {
            derivedStateOf {
                val raw = fingerOffset + offsetAnimation.value
                val fraction = (raw / maxWidthPx.coerceAtLeast(1f)).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember {
            mutableIntStateOf(selectedTabIndex().fastCoerceIn(0, (tabsCount - 1).coerceAtLeast(0)))
        }
        val dampedDragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).coerceAtLeast(0).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 90f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    val released = fingerOffset
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + released)
                        fingerOffset = 0f
                        offsetAnimation.animateTo(
                            0f,
                            spring(0.45f, 260f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / currentTabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    fingerOffset += dragAmount.x
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentSelectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    currentOnTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope, dampedDragAnimation) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * currentTabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * currentTabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = { containerReceiver.highlight(Highlight.Default) },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val width = size.width
                        if (width > 0f) {
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                .then(containerReceiver.modifier)
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * currentTabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * currentTabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .glassLightEmitter({ currentAccent })
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        blobReceiver.highlight(Highlight.Default.copy(alpha = progress))
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress.fastCoerceIn(0f, 1f))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress.fastCoerceIn(0f, 1f)
                        )
                    },
                    layerBlock = {
                        applyBlobTransform(dampedDragAnimation, velocityDivisor = 10f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            blobRestColor,
                            alpha = (1f - progress).fastCoerceIn(0f, 1f)
                        )
                        drawRect(Color.Black.copy(alpha = (0.03f * progress).fastCoerceIn(0f, 1f)))
                    }
                )
                .then(blobReceiver.modifier)
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
