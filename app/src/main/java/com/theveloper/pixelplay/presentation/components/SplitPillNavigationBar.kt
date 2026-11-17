package com.theveloper.pixelplay.presentation.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.BottomNavItem
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.NavBarStyle
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateToTopLevelSafely
import com.theveloper.pixelplay.ui.theme.FlexEmphasisText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber
import kotlin.math.roundToInt

// Geometry. NavBarContentHeight (86) = pill 68 + 12 below + 6 above; compact 64 = 52 + 8 + 4.
// Everything that pads for the bar goes through resolveNavBarContentHeight, so these stay in step.
internal val NavPillHeight = 68.dp
internal val NavPillCompactHeight = 52.dp
internal val NavPillGap = 10.dp
internal val NavPillSideMargin = 12.dp
private val NavPillBottomMargin = 12.dp
private val NavPillCompactBottomMargin = 8.dp
private val NavPillElevation = 6.dp
private val NavPillImeGap = 8.dp
private val NavTabsPadding = 8.dp
private val NavTabsGap = 4.dp
private val NavTabHeight = 52.dp
private val NavTabCompactHeight = 40.dp

internal fun resolveNavPillHeight(compactMode: Boolean): Dp =
    if (compactMode) NavPillCompactHeight else NavPillHeight

internal fun resolveNavPillBottomMargin(compactMode: Boolean): Dp =
    if (compactMode) NavPillCompactBottomMargin else NavPillBottomMargin

private const val SEARCH_DOUBLE_TAP_WINDOW_MS = 350L

/**
 * The two target widths of the split layout, written by its measure pass right before it
 * measures the pills, so the pill contents can be laid out at their *final* width and simply be
 * clipped while the pill around them morphs. Plain fields, not state: they only change with the
 * window width, and the pills are always re-measured right after they are written.
 */
private class SplitPillMetrics {
    var bigPx: Int = 0
    var smallPx: Int = 0
}

private class SearchTapTracker {
    var lastTapAt: Long = 0L
}

/**
 * Measures the content at a fixed width read from [widthPx] and places it inside whatever width
 * the parent gives (start-aligned at [bias] 0, centred at 0.5). The content's constraints don't
 * change while the pill morphs, so it is never re-measured per frame; the pill clips it.
 */
private fun Modifier.laidOutAtWidth(widthPx: () -> Int, bias: Float): Modifier =
    layout { measurable, constraints ->
        val w = widthPx().coerceAtLeast(0)
        val h = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(w, h))
        val outerW = constraints.maxWidth
        layout(outerW, h) {
            placeable.place(((outerW - w) * bias).roundToInt(), 0)
        }
    }

/**
 * The floating pill surface: clip + shadow on one layer, tonal fill under the content. [shape] is
 * read in the layer block, so swapping it (see [SplitPillShapes]) never recomposes.
 */
private fun Modifier.navPill(shape: () -> Shape, color: Color, elevationPx: Float): Modifier =
    graphicsLayer {
        this.shape = shape()
        clip = true
        shadowElevation = elevationPx
    }.background(color)

/**
 * The pills' resting shape and the one used while they flip. During the Home/Library <-> Search
 * morph both pills change width every frame, and a smooth-corner shape builds a new cubic Path per
 * size (two per frame, plus the shadow outline); a plain rounded rect is a cheap outline and at
 * 68 dp the difference is invisible mid-motion. The smooth shape comes back once the morph settles.
 */
private class SplitPillShapes(val resting: Shape, val moving: Shape) {
    fun at(morph: Float): Shape = if (morph <= 0.001f || morph >= 0.999f) resting else moving
}

private fun navPillMovingShape(radius: Dp, pillHeight: Dp): Shape =
    RoundedCornerShape(SplitPillMath.pillCornerRadius(radius.value, pillHeight.value).dp)

private fun navPillShape(radius: Dp, pillHeight: Dp, smooth: Boolean): Shape {
    val r = SplitPillMath.pillCornerRadius(radius.value, pillHeight.value).dp
    val isFullPill = r.value >= pillHeight.value / 2f - 0.5f
    return if (!smooth || isFullPill) {
        RoundedCornerShape(r)
    } else {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = r, smoothnessAsPercentTL = 60,
            cornerRadiusTR = r, smoothnessAsPercentTR = 60,
            cornerRadiusBL = r, smoothnessAsPercentBL = 60,
            cornerRadiusBR = r, smoothnessAsPercentBR = 60
        )
    }
}

private fun navBandShape(radius: Dp, smooth: Boolean): Shape =
    if (smooth) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = radius, smoothnessAsPercentTL = 60,
            cornerRadiusTR = radius, smoothnessAsPercentTR = 60,
            cornerRadiusBL = 0.dp, smoothnessAsPercentBL = 60,
            cornerRadiusBR = 0.dp, smoothnessAsPercentBR = 60
        )
    } else {
        RoundedCornerShape(topStart = radius, topEnd = radius)
    }

/**
 * The split-pill navigation bar (Material 3 Expressive).
 *
 * Home / Library: a big tonal pill with the tabs (the selected one in a secondaryContainer
 * capsule with icon + label, the label going heavier and wider through Google Sans Flex) and a
 * small primaryContainer pill for Search. On the Search route the pills flip in one spatial
 * spring: the left pill shrinks into a round Home button and the right one grows into the search
 * field, bound to the same query the search screen reads.
 *
 * Every animated value (the morph, the tab selection, the keyboard lift) is read in layout or
 * draw only; the bar recomposes when the route changes, never per frame.
 *
 * NavBarStyle.FULL_WIDTH keeps its meaning: the pills sit on a full-width tonal band that runs
 * under the system navigation bar, with the user's corner radius on the band's top corners. In
 * the default floating style that radius rounds the pills themselves (capped at a full pill).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SplitPillNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    navBarStyle: String,
    compactMode: Boolean,
    cornerRadius: Dp,
    useSmoothCorners: Boolean,
    systemNavBarInset: Dp,
    searchQuery: () -> String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSearch = currentRoute == Screen.Search.route
    val navigationEnabled = currentRoute != null

    val tabItems = remember(navItems) {
        navItems.filter { it.screen.route != Screen.Search.route }.toImmutableList()
    }
    val homeRoute = remember(navItems) {
        navItems.firstOrNull { it.screen.route == Screen.Home.route }?.screen?.route ?: Screen.Home.route
    }

    // Which tab the capsule sits on. Off the tab routes (Search) it keeps the last one, so coming
    // back does not slide it across.
    var lastTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val routeTabIndex = tabItems.indexOfFirst { it.screen.route == currentRoute }
    val selectedTab = if (routeTabIndex >= 0) routeTabIndex else lastTabIndex
    LaunchedEffect(routeTabIndex) { if (routeTabIndex >= 0) lastTabIndex = routeTabIndex }

    val motionScheme = remember { MotionScheme.expressive() }
    val morph = remember { Animatable(if (isSearch) 1f else 0f) }
    LaunchedEffect(isSearch) {
        morph.animateTo(if (isSearch) 1f else 0f, motionScheme.defaultSpatialSpec())
    }
    val tabSelection = remember { Animatable(selectedTab.toFloat()) }
    LaunchedEffect(selectedTab) {
        // While the tabs are hidden behind the search state there is nothing to animate.
        if (morph.value > 0.5f) {
            tabSelection.snapTo(selectedTab.toFloat())
        } else {
            tabSelection.animateTo(selectedTab.toFloat(), motionScheme.fastSpatialSpec())
        }
    }

    // Composition gates: each flips once per route change, the per-frame values stay in layout.
    val isSearchState = rememberUpdatedState(isSearch)
    val showSearchState by remember {
        derivedStateOf { isSearchState.value || morph.value > 0.001f }
    }
    val showTabsState by remember {
        derivedStateOf { !isSearchState.value || morph.value < 0.999f }
    }

    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    var pendingFieldFocus by remember { mutableStateOf(false) }
    val searchTaps = remember { SearchTapTracker() }

    // Leaving Search (Home pill, back gesture, anything) takes the keyboard with it.
    LaunchedEffect(isSearch) {
        if (!isSearch) {
            pendingFieldFocus = false
            if (fieldFocused) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
    }

    val onTabClick: (Int) -> Unit = { index ->
        val route = tabItems.getOrNull(index)?.screen?.route
        if (navigationEnabled && route != null && route != currentRoute) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            navController.navigateToTopLevelSafely(route)
        }
    }
    val onSearchPillClick: () -> Unit = click@{
        if (!navigationEnabled) {
            searchTaps.lastTapAt = 0L
            return@click
        }
        val now = SystemClock.elapsedRealtime()
        val isDoubleTap = now - searchTaps.lastTapAt <= SEARCH_DOUBLE_TAP_WINDOW_MS
        searchTaps.lastTapAt = now
        if (!isSearch) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            if (!navController.navigateToTopLevelSafely(Screen.Search.route)) {
                searchTaps.lastTapAt = 0L
                return@click
            }
        }
        // Double tap: straight into typing, as the old search icon did.
        if (isDoubleTap) {
            searchTaps.lastTapAt = 0L
            pendingFieldFocus = true
        }
    }
    val onHomePillClick: () -> Unit = {
        if (navigationEnabled) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            if (fieldFocused) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
            navController.navigateToTopLevelSafely(homeRoute)
        }
    }

    val density = LocalDensity.current
    val pillHeight = resolveNavPillHeight(compactMode)
    val bottomMargin = resolveNavPillBottomMargin(compactMode)
    val isFullWidth = navBarStyle == NavBarStyle.FULL_WIDTH
    val colors = MaterialTheme.colorScheme
    val leftColor = if (isFullWidth) colors.surfaceContainerHighest else colors.surfaceContainerHigh
    val rightColor = colors.primaryContainer
    val pillShapes = remember(cornerRadius, pillHeight, useSmoothCorners, isFullWidth) {
        val radius = if (isFullWidth) pillHeight else cornerRadius
        val resting = navPillShape(radius, pillHeight, useSmoothCorners)
        SplitPillShapes(
            resting = resting,
            moving = if (resting is RoundedCornerShape) resting else navPillMovingShape(radius, pillHeight)
        )
    }
    val pillShape: () -> Shape = remember(pillShapes) { { pillShapes.at(morph.value) } }
    val bandShape = remember(cornerRadius, useSmoothCorners) { navBandShape(cornerRadius, useSmoothCorners) }
    val elevationPx = if (isFullWidth) 0f else with(density) { NavPillElevation.toPx() }
    val gapPx = with(density) { NavPillGap.roundToPx() }
    val restingBottomPx = with(density) { (systemNavBarInset + bottomMargin).toPx() }
    val imeGapPx = with(density) { NavPillImeGap.toPx() }
    val imeInsets = WindowInsets.ime
    val metrics = remember { SplitPillMetrics() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(resolveNavBarContentHeight(compactMode) + systemNavBarInset)
            .graphicsLayer {
                // The search field lives in the bar, so on Search the bar rides up with the
                // keyboard. Insets and morph are snapshot state read here, in draw only.
                val p = morph.value.coerceIn(0f, 1f)
                translationY = if (p > 0f) {
                    -SplitPillMath.imeLift(imeInsets.getBottom(this).toFloat(), restingBottomPx, imeGapPx) * p
                } else {
                    0f
                }
            }
    ) {
        if (isFullWidth) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.surfaceContainer, bandShape)
            )
        }

        Layout(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = NavPillSideMargin,
                    end = NavPillSideMargin,
                    bottom = systemNavBarInset + bottomMargin
                )
                .fillMaxWidth()
                .height(pillHeight),
            content = {
                // Left pill: the tabs, or the round Home button on Search.
                Box(Modifier.navPill(pillShape, leftColor, elevationPx)) {
                    if (showTabsState) {
                        NavTabs(
                            items = tabItems,
                            selectedIndex = selectedTab,
                            selection = { tabSelection.value },
                            compactMode = compactMode,
                            enabled = navigationEnabled && !isSearch,
                            onTabClick = onTabClick,
                            modifier = Modifier
                                .laidOutAtWidth({ metrics.bigPx }, bias = 0f)
                                .graphicsLayer { alpha = SplitPillMath.fadeOut(morph.value) }
                        )
                    }
                    if (showSearchState) {
                        PillIconButton(
                            iconRes = R.drawable.rounded_home_24,
                            contentDescription = stringResource(R.string.nav_bar_home),
                            tint = colors.onSurface,
                            iconSize = if (compactMode) 24.dp else 26.dp,
                            enabled = isSearch,
                            onClick = onHomePillClick,
                            modifier = Modifier
                                .laidOutAtWidth({ metrics.smallPx }, bias = 0.5f)
                                .graphicsLayer { alpha = SplitPillMath.fadeIn(morph.value) }
                        )
                    }
                }
                // Right pill: the Search button, or the search field itself on Search.
                Box(Modifier.navPill(pillShape, rightColor, elevationPx)) {
                    if (showTabsState) {
                        PillIconButton(
                            iconRes = R.drawable.rounded_search_24,
                            contentDescription = stringResource(R.string.nav_bar_search),
                            tint = colors.onPrimaryContainer,
                            iconSize = if (compactMode) 24.dp else 26.dp,
                            enabled = !isSearch,
                            onClick = onSearchPillClick,
                            modifier = Modifier
                                .laidOutAtWidth({ metrics.smallPx }, bias = 0.5f)
                                .graphicsLayer { alpha = SplitPillMath.fadeOut(morph.value) }
                        )
                    }
                    if (showSearchState) {
                        NavSearchField(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onSubmit = onSearchSubmit,
                            enabled = isSearch,
                            compactMode = compactMode,
                            pendingFocus = pendingFieldFocus,
                            onPendingFocusConsumed = { pendingFieldFocus = false },
                            onFocusChanged = { fieldFocused = it },
                            modifier = Modifier
                                .laidOutAtWidth({ metrics.bigPx }, bias = 0f)
                                .graphicsLayer { alpha = SplitPillMath.fadeIn(morph.value) }
                        )
                    }
                }
            }
        ) { measurables, constraints ->
            val totalPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val smallPx = heightPx
            metrics.smallPx = smallPx
            metrics.bigPx = SplitPillMath.bigPillWidth(totalPx, gapPx, smallPx)
            // The morph is read here, in measure: the flip re-lays out the two pills and nothing
            // else (their contents keep fixed constraints through laidOutAtWidth).
            val leftPx = SplitPillMath.leftPillWidth(totalPx, gapPx, smallPx, morph.value)
            val rightPx = SplitPillMath.rightPillWidth(totalPx, gapPx, leftPx)
            val left = measurables[0].measure(Constraints.fixed(leftPx, heightPx))
            val right = measurables[1].measure(Constraints.fixed(rightPx, heightPx))
            layout(totalPx, heightPx) {
                left.place(0, 0)
                right.place(leftPx + gapPx, 0)
            }
        }
    }
}

/**
 * The tabs inside the big pill. Slots share the width by weight (the selected one 1.4×), and a
 * secondaryContainer capsule glides between them; both follow [selection] in layout / draw.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavTabs(
    items: ImmutableList<BottomNavItem>,
    selectedIndex: Int,
    selection: () -> Float,
    compactMode: Boolean,
    enabled: Boolean,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = items.size
    val density = LocalDensity.current
    val padPx = with(density) { NavTabsPadding.toPx() }
    val gapPx = with(density) { NavTabsGap.toPx() }
    val tabHeightPx = with(density) { (if (compactMode) NavTabCompactHeight else NavTabHeight).toPx() }
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
    // Reused across measures: the selection animation re-measures every frame.
    val placeables = remember(count) { arrayOfNulls<Placeable>(count) }

    Layout(
        modifier = modifier
            .selectableGroup()
            .drawBehind {
                if (count == 0) return@drawBehind
                val s = selection()
                val available = size.width - 2f * padPx - gapPx * (count - 1)
                val left = padPx + SplitPillMath.indicatorStart(count, available, gapPx, s)
                val width = SplitPillMath.indicatorWidth(count, available, s)
                val h = tabHeightPx.coerceAtMost(size.height)
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(left, (size.height - h) / 2f),
                    size = Size(width, h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f)
                )
            },
        content = {
            items.forEachIndexed { index, item ->
                NavTab(
                    item = item,
                    index = index,
                    selected = index == selectedIndex,
                    selection = selection,
                    compactMode = compactMode,
                    enabled = enabled,
                    onClick = { onTabClick(index) }
                )
            }
        }
    ) { measurables, constraints ->
        val s = selection()
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val available = w - 2f * padPx - gapPx * (count - 1)
        val tabH = tabHeightPx.roundToInt().coerceAtMost(h)
        for (i in 0 until count) {
            val slotW = SplitPillMath.tabSlotWidth(i, count, available, s).roundToInt().coerceAtLeast(0)
            placeables[i] = measurables[i].measure(Constraints.fixed(slotW, tabH))
        }
        layout(w, h) {
            val y = (h - tabH) / 2
            for (i in 0 until count) {
                val x = padPx + SplitPillMath.tabSlotStart(i, count, available, gapPx, s)
                placeables[i]?.place(x.roundToInt(), y)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavTab(
    item: BottomNavItem,
    index: Int,
    selected: Boolean,
    selection: () -> Float,
    compactMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(item.labelResId)
    val colors = MaterialTheme.colorScheme
    val selectedColor = colors.onSecondaryContainer
    val unselectedColor = colors.onSurfaceVariant
    val effects = remember { MotionScheme.expressive().fastEffectsSpec<Color>() }
    val labelColorState = animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        animationSpec = effects,
        label = "NavTabLabelColor"
    )
    val labelColor = remember(labelColorState) { ColorProducer { labelColorState.value } }
    val labelStyle = MaterialTheme.typography.labelLarge
    val tabLabelStyle = remember(labelStyle) { labelStyle.copy(fontSize = 15.sp, lineHeight = 20.sp) }
    val selectedIconRes = item.selectedIconResId?.takeIf { it != 0 } ?: item.iconResId
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            )
            .then(if (compactMode) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            // Unbounded: the slot width moves every frame of a selection change, the row's
            // constraints don't, so the label is never re-measured for it.
            modifier = Modifier.wrapContentWidth(unbounded = true),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Outline and filled icon cross-fade in the layer: no recomposition, no tint change.
            Box(Modifier.size(24.dp)) {
                Icon(
                    painter = painterResource(item.iconResId),
                    contentDescription = null,
                    tint = unselectedColor,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - SplitPillMath.tabEmphasis(index, selection())
                    }
                )
                Icon(
                    painter = painterResource(selectedIconRes),
                    contentDescription = null,
                    tint = selectedColor,
                    modifier = Modifier.graphicsLayer {
                        alpha = SplitPillMath.tabEmphasis(index, selection())
                    }
                )
            }
            if (!compactMode) {
                FlexEmphasisText(
                    text = label,
                    emphasized = selected,
                    style = tabLabelStyle,
                    color = labelColor
                )
            }
        }
    }
}

@Composable
private fun PillIconButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Speech recognizer presence, resolved once per process off the main thread. */
private object VoiceSearchAvailability {
    @Volatile
    var cached: Boolean? = null

    fun intent(prompt: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply { if (prompt != null) putExtra(RecognizerIntent.EXTRA_PROMPT, prompt) }

    suspend fun resolve(context: Context): Boolean {
        cached?.let { return it }
        val available = withContext(Dispatchers.IO) {
            runCatching { intent(null).resolveActivity(context.packageManager) != null }.getOrDefault(false)
        }
        cached = available
        return available
    }
}

private enum class SearchTrailing { None, Clear, Voice }

/**
 * The search field in the big pill: same query as the search screen (the view model's), search
 * IME action, clear, and voice input when a recognizer is installed.
 */
@Composable
private fun NavSearchField(
    query: () -> String,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    enabled: Boolean,
    compactMode: Boolean,
    pendingFocus: Boolean,
    onPendingFocusConsumed: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val contentColor = colors.onPrimaryContainer
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val text = query()
    val placeholder = stringResource(R.string.nav_search_placeholder)
    val searchLabel = stringResource(R.string.nav_bar_search)
    val voiceLabel = stringResource(R.string.nav_search_cd_voice)
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val textStyle = remember(bodyStyle, contentColor) {
        bodyStyle.copy(fontSize = 15.sp, lineHeight = 20.sp, color = contentColor)
    }
    val cursorBrush = remember(contentColor) { SolidColor(contentColor) }

    val voiceAvailable by produceState(initialValue = VoiceSearchAvailability.cached ?: false, context) {
        value = VoiceSearchAvailability.resolve(context.applicationContext)
    }
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)
    val latestOnSubmit by rememberUpdatedState(onSubmit)
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!spoken.isNullOrEmpty()) {
            latestOnQueryChange(spoken)
            latestOnSubmit(spoken)
        }
    }

    LaunchedEffect(pendingFocus, enabled) {
        if (pendingFocus && enabled) {
            // One frame for the field to attach after the route change.
            delay(40L)
            runCatching { focusRequester.requestFocus() }
                .onFailure { Timber.w(it, "Nav search field could not take focus") }
            keyboard?.show()
            onPendingFocusConsumed()
        }
    }

    val trailing = when {
        text.isNotEmpty() -> SearchTrailing.Clear
        voiceAvailable -> SearchTrailing.Voice
        else -> SearchTrailing.None
    }
    val trailingSize = if (compactMode) 40.dp else 46.dp

    Row(
        modifier = modifier.padding(start = 16.dp, end = if (compactMode) 6.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_search_24),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        BasicTextField(
            value = text,
            onValueChange = onQueryChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    val current = query()
                    if (current.isNotBlank()) onSubmit(current)
                    keyboard?.hide()
                }
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .semantics { contentDescription = searchLabel },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = contentColor.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
        Crossfade(
            targetState = trailing,
            label = "NavSearchTrailing",
            modifier = Modifier.size(trailingSize)
        ) { kind ->
            when (kind) {
                SearchTrailing.Clear -> FilledIconButton(
                    onClick = { onQueryChange("") },
                    enabled = enabled,
                    modifier = Modifier.size(trailingSize),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = contentColor.copy(alpha = 0.14f),
                        contentColor = contentColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.search_cd_clear_search_query)
                    )
                }
                SearchTrailing.Voice -> FilledIconButton(
                    onClick = {
                        try {
                            voiceLauncher.launch(VoiceSearchAvailability.intent(placeholder))
                        } catch (e: ActivityNotFoundException) {
                            Timber.w(e, "No speech recognizer to handle voice search")
                            VoiceSearchAvailability.cached = false
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.size(trailingSize),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = contentColor,
                        contentColor = colors.primaryContainer
                    )
                ) {
                    Icon(imageVector = Icons.Rounded.Mic, contentDescription = voiceLabel)
                }
                SearchTrailing.None -> Box(Modifier.size(trailingSize))
            }
        }
    }
}
