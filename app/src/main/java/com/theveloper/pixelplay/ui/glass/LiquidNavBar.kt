package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Matches this file's own hardcoded `.height(68.dp)` below — named for MainActivity.kt's layout
 * math (nav bar occupied height, corner-radius capsule sizing), which needs it outside this file. */
val LiquidNavBarHeight = 68.dp

private val PillShape = RoundedCornerShape(percent = 50)
private val PillShapeProvider: () -> Shape = { PillShape }
private val PillPressedFill = Color.Black.copy(alpha = 0.03f)

/** One destination in [LiquidGlassNavBar]. */
data class LiquidNavItem(
    val label: String,
    val iconRes: Int,
    val selectedIconRes: Int
)

/**
 * Taller, high-contrast liquid glass nav bar with full-width tap & drag navigation routing,
 * real-time glass intensity scaling, and pop-out bubble physics.
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

    val palette = LocalGlassPalette.current
    val tier = LocalGlassTier.current
    val light = LocalGlassLight.current
    val backdrop = LocalAppBackdrop.current
    val accentColor = palette.accent
    val activeContentColor = palette.content
    val inactiveContentColor = palette.contentSecondary
    // Neutral palette glass, not theme-hued — the bar is chrome, it shouldn't change colour with
    // every song. The accent only shows through the selected tab.
    val containerColor = resolveGlassFill(
        tint = null,
        palette = palette,
        material = GlassMaterial.Chrome,
        intensity = LocalGlassIntensity.current,
        tier = tier
    )
    val refracts = tier.refracts
    val samples = tier.samplesBackdrop
    val chrome = GlassMaterial.Chrome
    val pillRestFill = if (palette.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    val tabsBackdrop = rememberPageBackdrop()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val totalWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val tabWidth = (totalWidth - with(density) { 8.dp.toPx() }) / items.size.toFloat()

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

        val dampedDragAnimation = remember(animationScope, items.size, tabWidth) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(items.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 84f / 54f, // Dynamic vertical pop-out (~1.55x)
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    onSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (items.size - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        val barPressScale: GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation) {
            {
                val progress = dampedDragAnimation.pressProgress
                val scale = lerp(1f, 1f + 12.dp.toPx() / size.width.coerceAtLeast(1f), progress)
                scaleX = scale
                scaleY = scale
            }
        }

        LaunchedEffect(selectedIndex) {
            if (selectedIndex != currentIndex) {
                currentIndex = selectedIndex
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }

        // Layer 1: Base frosted glass container bar (68dp height)
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .glass(
                    shape = PillShape,
                    material = chrome,
                    backdrop = backdrop,
                    // MainActivity's Surface casts the bar's shadow outside its clip.
                    shadow = false,
                    layerBlock = barPressScale
                )
                .height(68.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                NavTabContent(
                    item = item,
                    selected = index == currentIndex,
                    showLabel = showLabels,
                    color = if (index == currentIndex) activeContentColor else inactiveContentColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // Layer 2: Recorded accent tab layer (58dp, alpha 0) for sampling through liquid bubble
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .alpha(0f)
                .then(if (samples) Modifier.pageBackdrop(tabsBackdrop) else Modifier)
                .then(
                    if (samples) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = PillShapeProvider,
                            effects = {
                                // Zero lens at rest, growing only while the pill is pressed or
                                // dragged. Layer 1 is the only resting bend.
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(chrome.blur.toPx())
                                if (refracts) {
                                    lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                                }
                            },
                            highlight = null,
                            shadow = null,
                            onDrawSurface = { drawRect(containerColor) }
                        )
                    } else {
                        Modifier
                    }
                )
                .height(58.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                NavTabContent(
                    item = item,
                    selected = true,
                    showLabel = showLabels,
                    color = accentColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // Layer 3: Pop-out liquid glass bubble pill (58dp height)
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dampedDragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(if (samples) Modifier.drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = PillShapeProvider,
                    // Matches the reference's LiquidBottomTabs pop-out pill exactly — no
                    // depthEffect, no extra multiplier on top of progress. Two earlier passes each
                    // added their own extra strength on top of this (a *1.4 multiplier, then a
                    // pinned-high fixed T), which is what made the pill "refract too much" — the
                    // reference pill is essentially flat at rest and only bends while pressed.
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        if (refracts) {
                            lens(
                                10.dp.toPx() * progress,
                                14.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress > 0.01f) light.highlight(palette, alpha = progress) else null
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress > 0.01f) Shadow(alpha = progress, color = palette.shadow) else null
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress > 0.01f) InnerShadow(radius = 8.dp * progress, alpha = progress) else null
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
                        drawRect(pillRestFill, alpha = 1f - progress)
                        drawRect(PillPressedFill, alpha = progress)
                    }
                ) else Modifier.drawBehind {
                    // Solid tier: a plain pill, no sampling.
                    drawRoundRect(
                        color = pillRestFill,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    )
                })
                .height(58.dp)
                .fillMaxWidth(1f / items.size)
        )

        // Layer 4: Transparent full-width gesture overlay spanning the entire nav bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .pointerInput(items.size, isLtr) {
                    detectTapGestures { position ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val wTab = width / items.size
                        val rawIndex = (position.x / wTab).fastCoerceIn(0f, (items.size - 1).toFloat())
                        val targetIndex = if (isLtr) rawIndex.fastRoundToInt() else (items.size - 1) - rawIndex.fastRoundToInt()
                        currentIndex = targetIndex
                        dampedDragAnimation.animateToValue(targetIndex.toFloat())
                        onSelected(targetIndex)
                    }
                }
                .pointerInput(items.size, isLtr) {
                    detectDragGestures(
                        onDragStart = {
                            dampedDragAnimation.press()
                        },
                        onDragEnd = {
                            dampedDragAnimation.release()
                            val targetIndex = dampedDragAnimation.targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                            currentIndex = targetIndex
                            dampedDragAnimation.animateToValue(targetIndex.toFloat())
                            onSelected(targetIndex)
                        },
                        onDragCancel = {
                            dampedDragAnimation.release()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val wTab = width / items.size
                            val deltaIndex = (dragAmount.x / wTab) * (if (isLtr) 1f else -1f)
                            dampedDragAnimation.updateValue((dampedDragAnimation.targetValue + deltaIndex).fastCoerceIn(0f, (items.size - 1).toFloat()))
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
            painter = painterResource(
                if (selected) item.selectedIconRes else item.iconRes
            ),
            contentDescription = item.label,
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
