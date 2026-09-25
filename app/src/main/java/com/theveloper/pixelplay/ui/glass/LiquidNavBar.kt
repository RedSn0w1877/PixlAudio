package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** The bar's own height. MainActivity's layout math (occupied height, capsule sizing) needs it. */
val LiquidNavBarHeight = 68.dp

/** One destination in [LiquidGlassNavBar]. */
data class LiquidNavItem(
    val label: String,
    val iconRes: Int,
    val selectedIconRes: Int
)

/**
 * The floating glass tab bar, after the Backdrop catalog's `LiquidBottomTabs`
 * (Kyant0/AndroidLiquidGlass, Apache-2.0).
 *
 * Four layers, bottom to top:
 * 1. **The bar**: one capsule of glass sampling the page ([LocalAppBackdrop]). It also exports its
 *    own finished glass (without the icons) into `barExport`.
 * 2. **The pill**: sits under the selected tab. At rest it is a faint `primary` wash with no lens;
 *    pressed or dragged it lifts into clear glass (lens, chromatic edge, highlight, shadows all
 *    materialise with the press) and squashes and stretches as it slides. It refracts `barExport`
 *    — the bar beneath it — not the page, so it never needs a combined backdrop.
 * 3. **The tabs**: icon + label for each destination. A tab's colour follows the pill's live
 *    position in the draw phase, so the accent slides across as the pill does, with no
 *    recomposition per frame.
 * 4. **Gestures**: tap a tab, or drag anywhere along the bar to slide the pill.
 *
 * Colours come from the scheme: container `surface` at the recipe's alpha, accent `primary`.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true
) {
    if (items.isEmpty()) return

    val barRecipe = resolveRecipe(GlassRole.NavBar)
    val pillRecipe = resolveRecipe(GlassRole.NavPill)
    val accentColor = MaterialTheme.colorScheme.primary
    val restingContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
    val reduceMotion = LocalGlassReduceMotion.current
    val currentOnSelected by rememberUpdatedState(onSelected)

    val barExport = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val totalWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val tabWidth = (totalWidth - with(density) { 8.dp.toPx() }) / items.size.toFloat()
        val lastIndex = (items.size - 1).toFloat()

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, totalWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / totalWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }

        val dampedDragAnimation = remember(animationScope, items.size) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..lastIndex,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 84f / 54f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }

        LaunchedEffect(selectedIndex) {
            if (selectedIndex != currentIndex) currentIndex = selectedIndex
            if (dampedDragAnimation.targetValue != selectedIndex.toFloat()) {
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }

        fun select(index: Int) {
            currentIndex = index
            dampedDragAnimation.animateToValue(index.toFloat())
            currentOnSelected(index)
        }

        // Layer 1: the bar.
        val barLayer: GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation) {
            {
                translationX = panelOffset
                val progress = dampedDragAnimation.pressProgress
                val scale = lerp(1f, 1f + 12.dp.toPx() / size.width.coerceAtLeast(1f), progress)
                scaleX = scale
                scaleY = scale
            }
        }
        Box(
            Modifier
                .liquidGlass(
                    recipe = barRecipe,
                    shape = GlassShapes.Capsule,
                    layerBlock = barLayer,
                    exportedBackdrop = barExport,
                    // MainActivity's Surface clips to the bar's own capsule and casts the elevation
                    // shadow itself; a drawBackdrop shadow outside the shape would be cut away.
                    shadowEnabled = false
                )
                .height(LiquidNavBarHeight)
                .fillMaxWidth()
        )

        // Layer 2: the pill, under the selected tab.
        val pillLayer: GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation, tabWidth, isLtr, reduceMotion) {
            {
                translationX = if (isLtr) {
                    dampedDragAnimation.value * tabWidth + panelOffset
                } else {
                    size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                applyGlassSquash(dampedDragAnimation, velocityDivisor = 10f, reduceMotion = reduceMotion)
            }
        }
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .liquidGlass(
                    recipe = pillRecipe,
                    shape = GlassShapes.Capsule,
                    materialize = { dampedDragAnimation.pressProgress },
                    backdrop = barExport,
                    layerBlock = pillLayer
                )
                .height(58.dp)
                .fillMaxWidth(1f / items.size)
        )

        // Layer 3: the tabs. Each tab's accent follows the pill's live position (draw phase).
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .height(LiquidNavBarHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val closeness: () -> Float = {
                    (1f - abs(dampedDragAnimation.value - index)).fastCoerceIn(0f, 1f)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clearAndSetSemantics {
                            role = Role.Tab
                            selected = index == currentIndex
                            contentDescription = item.label
                            onClick {
                                select(index)
                                true
                            }
                        }
                ) {
                    NavTabContent(
                        item = item,
                        selected = false,
                        showLabel = showLabels,
                        color = restingContentColor,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - closeness() }
                    )
                    NavTabContent(
                        item = item,
                        selected = true,
                        showLabel = showLabels,
                        color = accentColor,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = closeness() }
                    )
                }
            }
        }

        // Layer 4: tap or drag anywhere along the bar.
        Box(
            Modifier
                .fillMaxWidth()
                .height(LiquidNavBarHeight)
                .pointerInput(items.size, isLtr) {
                    detectTapGestures { position ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val raw = (position.x / (width / items.size)).toInt()
                            .coerceIn(0, items.size - 1)
                        select(if (isLtr) raw else items.size - 1 - raw)
                    }
                }
                .pointerInput(items.size, isLtr) {
                    detectHorizontalDragGestures(
                        onDragStart = { dampedDragAnimation.press() },
                        onDragEnd = {
                            val target = dampedDragAnimation.targetValue.fastRoundToInt()
                                .coerceIn(0, items.size - 1)
                            dampedDragAnimation.release()
                            select(target)
                            animationScope.launch {
                                offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                            }
                        },
                        onDragCancel = {
                            dampedDragAnimation.release()
                            dampedDragAnimation.animateToValue(currentIndex.toFloat())
                            animationScope.launch {
                                offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val deltaIndex = (dragAmount / (width / items.size)) * (if (isLtr) 1f else -1f)
                            dampedDragAnimation.updateValue(
                                (dampedDragAnimation.targetValue + deltaIndex).fastCoerceIn(0f, lastIndex)
                            )
                            animationScope.launch {
                                offsetAnimation.snapTo(offsetAnimation.value + dragAmount)
                            }
                        }
                    )
                }
        )
    }
}

@Composable
private fun NavTabContent(
    item: LiquidNavItem,
    selected: Boolean,
    showLabel: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(if (selected) item.selectedIconRes else item.iconRes),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        if (showLabel) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
