package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A shared-element morph for one glass surface (the queue's ⋯ circle flowing into its "Save as
 * playlist" pill and back). No kit primitive does this, so the surface itself moves: this layout
 * modifier measures what follows it at the size of the rect lerped between [from] and [to] and
 * places it there, while the node reports the whole container as its own size.
 *
 * - [progress] is read in the LAYOUT phase only, so the morph re-lays out one node per frame and
 *   never recomposes anything.
 * - Put it on a direct child of a container whose constraints are bounded (a `Box` child): [from]
 *   and [to] get that container's size and work in its coordinates. [to] also gets the measurable
 *   that follows, so a pill can size itself to its content's intrinsic width.
 * - Keep the glass shape a capsule for the whole morph (a 64 × 64 capsule is the circle, a
 *   230 × 56 one the pill): the drawBackdrop chain is then never rebuilt, only its size changes.
 * - Pointer input after this modifier sits on the morphing rect only; the rest of the container
 *   stays touchable.
 * - HARD RULE: never put `layerBackdrop` on an ancestor of the morphing node.
 */
fun Modifier.glassMorphBounds(
    progress: () -> Float,
    from: MeasureScope.(container: IntSize, content: Measurable) -> Rect,
    to: MeasureScope.(container: IntSize, content: Measurable) -> Rect,
): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        // Not inside a bounded container: no geometry to morph in, behave like no modifier.
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val container = IntSize(constraints.maxWidth, constraints.maxHeight)
    val rect = lerpMorphRect(from(container, measurable), to(container, measurable), progress())
    val width = rect.width.roundToInt().coerceAtLeast(0)
    val height = rect.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(container.width, container.height) {
        placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
    }
}

/**
 * The rect between [from] and [to] at [progress]. Geometry clamps progress to 0..1: the open
 * spring's overshoot would otherwise push a pill past its target and shrink a circle below zero.
 */
internal fun lerpMorphRect(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        left = from.left + (to.left - from.left) * p,
        top = from.top + (to.top - from.top) * p,
        right = from.right + (to.right - from.right) * p,
        bottom = from.bottom + (to.bottom - from.bottom) * p,
    )
}

/** The leaving content's alpha: 1 at rest, gone by [end] (the circle's icon, the toolbar). */
internal fun morphFadeOut(progress: Float, end: Float = 0.4f): Float =
    (1f - progress / end).coerceIn(0f, 1f)

/** The arriving content's alpha: 0 until [start], then up to 1 at the end of the morph. */
internal fun morphFadeIn(progress: Float, start: Float = 0.5f): Float =
    ((progress - start) / (1f - start)).coerceIn(0f, 1f)
