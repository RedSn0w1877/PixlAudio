package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * Helpers for the screens whose collapsing header height lives in an `Animatable` that changes on
 * every scroll frame. They keep that per-frame value out of the screen's root composition: the
 * collapse fraction is derived (only the scope that reads it — the top bar — recomposes), and the
 * list's top padding is read by the lazy list's measure pass (a relayout, never a recomposition).
 */

/**
 * `1 - (height - min) / (max - min)`, clamped to 0..1: 0 fully expanded, 1 fully collapsed.
 * Read it only where it is consumed (the top bar), never in the screen's root body.
 */
@Composable
fun rememberCollapseFraction(
    height: Animatable<Float, AnimationVector1D>,
    minHeightPx: Float,
    maxHeightPx: Float
): State<Float> = remember(height, minHeightPx, maxHeightPx) {
    derivedStateOf {
        1f - ((height.value - minHeightPx) / (maxHeightPx - minHeightPx)).coerceIn(0f, 1f)
    }
}

/**
 * Reads the header's current height and collapse fraction in its own small restart scope and hands
 * them to [content] (the header). While the header collapses, only this call and the header
 * recompose — the screen scope around it, with its list, does not.
 */
@Composable
fun WithCollapsingHeader(
    height: Animatable<Float, AnimationVector1D>,
    collapseFraction: State<Float>,
    content: @Composable (collapseFraction: Float, headerHeight: Dp) -> Unit
) {
    val density = LocalDensity.current
    content(collapseFraction.value, with(density) { height.value.toDp() })
}

/**
 * Content padding whose top is the header's current height (in px, converted with [density]) plus
 * [extraTop]; the other sides are fixed. Lazy lists call `calculateTopPadding()` from their measure
 * pass, so the header height is read at layout time and the list's measure policy stays remembered.
 */
@Stable
class CollapsingHeaderContentPadding(
    private val density: Density,
    private val headerHeightPx: () -> Float,
    private val extraTop: Dp = 0.dp,
    private val start: Dp = 0.dp,
    private val end: Dp = 0.dp,
    private val bottom: Dp = 0.dp
) : PaddingValues {
    /** The header's current height plus [extraTop]; a state read wherever it is called. */
    fun currentTop(): Dp = with(density) { headerHeightPx().toDp() } + extraTop

    override fun calculateTopPadding(): Dp = currentTop()

    override fun calculateBottomPadding(): Dp = bottom

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start
}

/** [CollapsingHeaderContentPadding] following [height], remembered across recompositions. */
@Composable
fun rememberCollapsingHeaderContentPadding(
    height: Animatable<Float, AnimationVector1D>,
    extraTop: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp
): CollapsingHeaderContentPadding {
    val density = LocalDensity.current
    return remember(height, density, extraTop, start, end, bottom) {
        CollapsingHeaderContentPadding(density, { height.value }, extraTop, start, end, bottom)
    }
}
