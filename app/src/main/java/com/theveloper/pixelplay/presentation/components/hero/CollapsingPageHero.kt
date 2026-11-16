package com.theveloper.pixelplay.presentation.components.hero

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.scoped.lerpColorScheme
import com.theveloper.pixelplay.ui.theme.FlexFitCollapsingTitle
import com.theveloper.pixelplay.ui.theme.FlexMath
import com.theveloper.pixelplay.ui.theme.FlexPresets
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import com.theveloper.pixelplay.ui.theme.collapseScale
import kotlin.math.roundToInt

/*
 * The collapsing full-bleed header shared by the new UI's detail pages (artist, playlist): the
 * picture fills the top edge to edge with the progressive blur ([ProgressiveBlurPageHero]), a big
 * fit-by-width title sits over the blurred part above the page's own row (counts, buttons), and as
 * the page scrolls the header collapses into a solid bar with the title beside the back button.
 *
 * Moved here from the artist page (stage 4) so the playlist page (stage 5) shares the exact same
 * layout and performance contract instead of a copy.
 */

/** Sizes and defaults of the page heroes. */
internal object PageHeroDefaults {
    /** Expanded title size (artist page; the playlist mockup uses 44). */
    val TitleExpandedSize: TextUnit = 48.sp

    /** Title size once collapsed into the bar. */
    val TitleCollapsedSize: TextUnit = 20.sp

    /** Cached font steps between the expanded and the collapsed cut. */
    const val TitleSteps = 8

    /** Height of the bar row (under the status bar) holding back / actions. */
    val BarHeight: Dp = 64.dp

    /** Hero title at [size]: tight leading for the two-line case, a touch of negative tracking. */
    fun titleStyle(size: TextUnit = TitleExpandedSize): TextStyle =
        TextStyle(fontSize = size, lineHeight = 1.04.em, letterSpacing = (-0.5).sp)

    /** Round buttons over the hero: the page surface, translucent, so the picture reads through. */
    @Composable
    fun iconButtonColors(): IconButtonColors {
        val colors = MaterialTheme.colorScheme
        return IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.surface.copy(alpha = 0.62f),
            contentColor = colors.onSurface
        )
    }

    /**
     * The expanded header height: the full width and almost as much again (390 × 360-400 in the
     * mockups), at least the status bar + 300 dp so the buttons, two lines of title and the action
     * row fit, and capped on landscape screens so it doesn't eat the whole view.
     */
    fun maxHeight(screenWidthDp: Int, screenHeightDp: Int, statusBar: Dp): Dp {
        val byWidth = (screenWidthDp * 0.92f).dp
        val cap = (screenHeightDp * 0.62f).dp
        return maxOf(minOf(byWidth, cap), statusBar + 300.dp)
    }
}

/**
 * The page hero. One custom layout, measured with fixed constraints for everything except its own
 * reported height and the title's per-step width, so a collapsing frame costs a re-measure of this
 * node (children skip), a re-placement, and layer / draw updates:
 *
 *  - the picture + progressive blur + scrim ([ProgressiveBlurPageHero]) is laid out once at the
 *    full height and slides up at half speed in its layer (parallax);
 *  - the bar backdrop (solid surface fading in, the status-bar shade, a soft bottom edge) is drawn
 *    from the collapse fraction in draw only;
 *  - [bottomContent] (counts, Play / Shuffle...) rides the header's bottom edge and fades out;
 *  - the [title] ([FlexFitCollapsingTitle]) travels from above [bottomContent] to beside
 *    [navigation], its axes stepping in cached instances and its size scaling in its layer, and
 *    narrows to the room left between [navigation] and [actions];
 *  - [navigation] and [actions] stay put at the top.
 *
 * [height] and [collapseFraction] are read only in measure / placement / layer / draw blocks, so
 * scrolling never recomposes the header (nor the screen around it).
 */
@Composable
internal fun CollapsingPageHeroHeader(
    title: String,
    imageUrl: String?,
    imageContentDescription: String?,
    imageRequestSize: Size,
    height: FloatState,
    collapseFraction: State<Float>,
    minHeightPx: Float,
    maxHeightPx: Float,
    placeholder: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleExpandedSize: TextUnit = PageHeroDefaults.TitleExpandedSize,
) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    val isDark = LocalPixelPlayDarkTheme.current
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()

    // Los iconos de la barra de estado van sobre la superficie (velo arriba, barra sólida al
    // colapsar), así que basta con fijarlos una vez por esquema y no en cada frame.
    PixelPlayStatusBarStyle(color = surface)

    val blur = rememberHeroBlur(imageUrl)

    // GRAD for the title over the blurred picture: light text on a bright picture gets heavier
    // strokes (no reflow). Changes once, when the blur arrives or the palette settles.
    val surfaceLuma = remember(surface) { surface.luminance() }
    val grade by remember(isDark, surfaceLuma) {
        derivedStateOf {
            val cover = blur.value?.coverLuma ?: return@derivedStateOf 0f
            FlexMath.gradeForBackdrop(HeroBlurMath.pageBackdropLuma(cover, surfaceLuma, 0.55f), lightText = isDark)
        }
    }
    val expandedSpec = remember(grade) { FlexPresets.HeroDisplay.copy(grade = grade) }
    val titleStyle = remember(titleExpandedSize) { PageHeroDefaults.titleStyle(titleExpandedSize) }
    val collapsedSize = PageHeroDefaults.TitleCollapsedSize
    val steps = PageHeroDefaults.TitleSteps

    val onSurfaceState = rememberUpdatedState(colors.onSurface)
    val titleColor = remember { ColorProducer { onSurfaceState.value } }
    val surfaceState = rememberUpdatedState(surface)
    val statusShade = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.35f)

    val titleStep = remember(collapseFraction) {
        derivedStateOf { FlexMath.stepIndex(collapseFraction.value, steps) }
    }
    val fraction: () -> Float = remember(collapseFraction) { { collapseFraction.value } }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        content = {
            // 0 — picture, blur and scrim.
            ProgressiveBlurPageHero(
                imageUrl = imageUrl,
                contentDescription = imageContentDescription,
                blur = blur,
                backgroundColor = surface,
                imageRequestSize = imageRequestSize,
                modifier = Modifier.fillMaxSize(),
                placeholder = placeholder
            )
            // 1 — bar backdrop, drawn from the fraction and height only.
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val shadeHeight = 80.dp.toPx()
                        val edgeHeight = 40.dp.toPx()
                        val shade = Brush.verticalGradient(
                            listOf(statusShade, Color.Transparent),
                            startY = 0f,
                            endY = shadeHeight
                        )
                        // Read here, not per frame: the edge brush is rebuilt only when the
                        // palette changes (its first-visit fade), never while collapsing.
                        val edgeColor = surfaceState.value
                        val edge = Brush.verticalGradient(
                            listOf(edgeColor.copy(alpha = 0f), edgeColor),
                            startY = 0f,
                            endY = edgeHeight
                        )
                        val shadeSize = GeometrySize(size.width, shadeHeight)
                        val edgeSize = GeometrySize(size.width, edgeHeight)
                        onDrawBehind {
                            val f = collapseFraction.value
                            val h = height.floatValue.coerceIn(minHeightPx, maxHeightPx)
                            val bg = surfaceState.value
                            drawRect(shade, size = shadeSize)
                            // A soft edge where the list meets the header mid-collapse.
                            val edgeAlpha = (f * 3f).coerceAtMost(1f)
                            if (edgeAlpha > 0f) {
                                translate(top = h - edgeHeight) {
                                    drawRect(brush = edge, size = edgeSize, alpha = edgeAlpha)
                                }
                            }
                            val barAlpha = ((f - 0.4f) / 0.45f).coerceIn(0f, 1f)
                            if (barAlpha > 0f) {
                                drawRect(bg, size = GeometrySize(size.width, h), alpha = barAlpha)
                            }
                        }
                    }
            )
            // 2 — the page's own row (counts, buttons).
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                bottomContent()
            }
            // 3 — the title.
            FlexFitCollapsingTitle(
                text = title,
                collapseFraction = fraction,
                expandedStyle = titleStyle,
                collapsedFontSize = collapsedSize,
                color = titleColor,
                expanded = expandedSpec,
                collapsed = FlexPresets.HeroCollapsed,
                steps = steps,
                modifier = Modifier.semantics { heading() }
            )
            // 4 — navigation (back).
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(PageHeroDefaults.BarHeight)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                navigation()
            }
            // 5 — actions.
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(PageHeroDefaults.BarHeight)
                    .padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val fullHeight = maxHeightPx.roundToInt()
        // The only per-frame read in measure: the height this node reports.
        val currentHeight = height.floatValue.coerceIn(minHeightPx, maxHeightPx).roundToInt()
        val full = Constraints.fixed(width, fullHeight)
        val image = measurables[0].measure(full)
        val backdrop = measurables[1].measure(full)
        val bottom = measurables[2].measure(Constraints(maxWidth = width))
        val nav = measurables[4].measure(Constraints(maxWidth = width))
        val actionRow = measurables[5].measure(Constraints(maxWidth = width))
        // The title narrows step by step toward the bar's room (between navigation and actions).
        val sidePx = 16.dp.roundToPx()
        val gapPx = 8.dp.roundToPx()
        val barStartPx = nav.width + gapPx
        val barEndPx = actionRow.width + gapPx
        val stepT = FlexMath.stepFraction(titleStep.value, steps)
        val expandedTitleWidth = (width - 2 * sidePx).coerceAtLeast(0)
        val collapsedTitleWidth = (width - barStartPx - barEndPx).coerceAtLeast(0)
        val titleWidth = lerp(expandedTitleWidth.toFloat(), collapsedTitleWidth.toFloat(), stepT).roundToInt()
        val titlePlaceable = measurables[3].measure(Constraints(maxWidth = titleWidth))
        val bottomGapPx = 16.dp.toPx()
        val titleGapPx = 4.dp.toPx()
        val barCenterPx = statusBarTopPx + PageHeroDefaults.BarHeight.toPx() / 2f

        layout(width, currentHeight) {
            image.placeWithLayer(0, 0) {
                val h = height.floatValue.coerceIn(minHeightPx, maxHeightPx)
                translationY = -(maxHeightPx - h) * 0.5f
                // Fully under the solid bar at the end: skip drawing it.
                alpha = if (collapseFraction.value >= 0.999f) 0f else 1f
            }
            backdrop.place(0, 0)
            val f = collapseFraction.value
            val h = height.floatValue.coerceIn(minHeightPx, maxHeightPx)
            val bottomY = h - bottomGapPx - bottom.height
            bottom.placeWithLayer(0, bottomY.roundToInt()) {
                alpha = (1f - collapseFraction.value * 2.2f).coerceIn(0f, 1f)
            }
            // Title: its visual box is the layout box scaled about its start-centre.
            val s = collapseScale(f, titleExpandedSize, collapsedSize, steps)
            val visualHeight = titlePlaceable.height * s
            val expandedCenter = bottomY - titleGapPx - visualHeight / 2f
            val center = lerp(expandedCenter, barCenterPx, f)
            val x = lerp(sidePx.toFloat(), barStartPx.toFloat(), (f * 1.6f).coerceAtMost(1f))
            titlePlaceable.place(x.roundToInt(), (center - titlePlaceable.height / 2f).roundToInt())
            nav.place(0, 0)
            actionRow.place(width - actionRow.width, 0)
        }
    }
}

/** Plain holder (not state): whether the page content was on screen in the previous frame. */
internal class PageContentShownFlag {
    var shown: Boolean = false
    var darkTheme: Boolean? = null
}

/**
 * Returns [target] as is, except when [animateChange] is set for this target: then it crossfades
 * from the colours on screen to [target] over 450 ms with one Animatable. Only consumers of the
 * scheme recompose during the fade, and only on a first visit (a palette that arrives after the
 * page is already showing).
 */
@Composable
internal fun rememberCrossfadedPageColorScheme(target: ColorScheme, animateChange: Boolean): ColorScheme {
    val progress = remember { Animatable(1f) }
    var fromScheme by remember { mutableStateOf(target) }
    var toScheme by remember { mutableStateOf(target) }
    val animate by rememberUpdatedState(animateChange)

    LaunchedEffect(target) {
        if (toScheme == target) return@LaunchedEffect
        if (!animate) {
            fromScheme = target
            toScheme = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        fromScheme = lerpColorScheme(fromScheme, toScheme, progress.value)
        toScheme = target
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing))
    }

    val interpolated by remember {
        derivedStateOf { lerpColorScheme(fromScheme, toScheme, progress.value) }
    }
    return if (animateChange || progress.isRunning) interpolated else target
}

/**
 * Play + Shuffle as an M3 Expressive connected button group: a pill split in two with tight inner
 * corners that morph rounder on press (the group's own press shapes). Play is the primary,
 * labelled half; Shuffle the tonal, icon-only half.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PagePlayShuffleGroup(
    enabled: Boolean,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    shuffleContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val leading = ButtonShapes(
        ButtonGroupDefaults.connectedLeadingButtonShape,
        ButtonGroupDefaults.connectedLeadingButtonPressShape
    )
    val trailing = ButtonShapes(
        ButtonGroupDefaults.connectedTrailingButtonShape,
        ButtonGroupDefaults.connectedTrailingButtonPressShape
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        Button(
            onClick = onPlayClick,
            shapes = leading,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            ),
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp),
            modifier = Modifier.height(56.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.common_play), style = MaterialTheme.typography.titleMedium)
        }
        Button(
            onClick = onShuffleClick,
            shapes = trailing,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(width = 64.dp, height = 56.dp)
        ) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = shuffleContentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
