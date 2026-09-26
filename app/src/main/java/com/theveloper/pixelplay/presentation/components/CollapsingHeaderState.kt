package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

/**
 * A collapsing header's height in px. It replaces the `Animatable<Float>` the screens used to keep:
 * the nested-scroll connection writes it synchronously with [snapTo] from `onPreScroll` (no coroutine
 * launched per scroll event, and the header follows the consumed delta on the same frame instead of
 * one frame late), while the settle keeps `Animatable.animateTo`'s semantics: [animateTo] starts from
 * the running settle's velocity when it interrupts one, and [snapTo] or a newer [animateTo] cancels
 * the running settle's coroutine exactly as `Animatable`'s mutator did. Main thread only, like every
 * caller.
 */
@Stable
class CollapsingHeaderHeight(initialValue: Float) : FloatState {
    private val height = mutableFloatStateOf(initialValue)
    private var settleJob: Job? = null
    private var settleVelocity = 0f

    override val floatValue: Float
        get() = height.floatValue

    /** Current settle velocity in px/s (0 when no settle runs), as `Animatable.velocity`. */
    val velocity: Float
        get() = settleVelocity

    /** Sets the height now, cancelling any running settle. */
    fun snapTo(targetValue: Float) {
        cancelSettle()
        height.floatValue = targetValue
    }

    /**
     * Animates to [targetValue] with [animationSpec]; suspends until done. Throws
     * `CancellationException` if a [snapTo] or another [animateTo] interrupts it.
     */
    suspend fun animateTo(
        targetValue: Float,
        animationSpec: AnimationSpec<Float>,
        initialVelocity: Float = velocity
    ) {
        cancelSettle()
        coroutineScope {
            val job = coroutineContext[Job]
            settleJob = job
            try {
                animate(
                    initialValue = height.floatValue,
                    targetValue = targetValue,
                    initialVelocity = initialVelocity,
                    animationSpec = animationSpec
                ) { value, velocity ->
                    height.floatValue = value
                    settleVelocity = velocity
                }
            } finally {
                if (settleJob === job) {
                    settleJob = null
                    settleVelocity = 0f
                }
            }
        }
    }

    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
        settleVelocity = 0f
    }
}

/*
 * Helpers for the screens whose collapsing header height ([CollapsingHeaderHeight]) changes on
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
    height: FloatState,
    minHeightPx: Float,
    maxHeightPx: Float
): State<Float> = remember(height, minHeightPx, maxHeightPx) {
    derivedStateOf {
        1f - ((height.floatValue - minHeightPx) / (maxHeightPx - minHeightPx)).coerceIn(0f, 1f)
    }
}

/**
 * Reads the header's current height and collapse fraction in its own small restart scope and hands
 * them to [content] (the header). While the header collapses, only this call and the header
 * recompose — the screen scope around it, with its list, does not.
 */
@Composable
fun WithCollapsingHeader(
    height: FloatState,
    collapseFraction: State<Float>,
    content: @Composable (collapseFraction: Float, headerHeight: Dp) -> Unit
) {
    val density = LocalDensity.current
    content(collapseFraction.value, with(density) { height.floatValue.toDp() })
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
    height: FloatState,
    extraTop: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp
): CollapsingHeaderContentPadding {
    val density = LocalDensity.current
    return remember(height, density, extraTop, start, end, bottom) {
        CollapsingHeaderContentPadding(density, { height.floatValue }, extraTop, start, end, bottom)
    }
}
