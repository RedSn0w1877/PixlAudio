package com.theveloper.pixelplay.presentation.components

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure geometry for the split-pill navigation bar (no Android, no Compose: unit-tested).
 *
 * The bar is two pills side by side. On Home / Library the left one is big (the tabs) and the
 * right one small (Search); on Search they flip: the left one shrinks to a round Home button and
 * the right one grows into the search field. One morph value drives that: 0 = tabs state,
 * 1 = search state. Everything here is called from layout or draw with primitives only, so the
 * morph and the tab selection animate without allocating.
 */
internal object SplitPillMath {

    /** Extra weight the selected tab gets over the others (mockup: flex 1.4 vs 1). */
    const val SELECTED_TAB_EXTRA_WEIGHT = 0.4f

    /** How far a spring may squeeze a pill below its small size before it is held. */
    private const val MIN_SMALL_FRACTION = 0.8f

    /** Width of the big pill: everything the small pill and the gap leave. */
    fun bigPillWidth(totalPx: Int, gapPx: Int, smallPx: Int): Int =
        (totalPx - gapPx - smallPx).coerceAtLeast(smallPx)

    /**
     * Width of the LEFT pill at [progress] (0 = big, 1 = small). The right pill takes the rest.
     * Spring overshoot is allowed (it is what makes the flip feel alive) but neither pill is ever
     * squeezed below [MIN_SMALL_FRACTION] of its small size.
     */
    fun leftPillWidth(totalPx: Int, gapPx: Int, smallPx: Int, progress: Float): Int {
        val big = bigPillWidth(totalPx, gapPx, smallPx)
        val raw = big + (smallPx - big) * progress
        val min = smallPx * MIN_SMALL_FRACTION
        val max = (totalPx - gapPx - min).coerceAtLeast(min)
        return raw.coerceIn(min, max).roundToInt()
    }

    /** Width of the RIGHT pill for a given left pill width. */
    fun rightPillWidth(totalPx: Int, gapPx: Int, leftPx: Int): Int =
        (totalPx - gapPx - leftPx).coerceAtLeast(0)

    /** 1 for the selected tab, 0 for the others, in between while the selection moves. */
    fun tabEmphasis(index: Int, selection: Float): Float =
        (1f - abs(selection - index)).coerceIn(0f, 1f)

    private fun tabWeight(index: Int, count: Int, selection: Float): Float {
        val clamped = selection.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
        return 1f + SELECTED_TAB_EXTRA_WEIGHT * tabEmphasis(index, clamped)
    }

    private fun totalWeight(count: Int, selection: Float): Float {
        var sum = 0f
        for (i in 0 until count) sum += tabWeight(i, count, selection)
        return sum
    }

    /** Width of tab [index] when [availablePx] (pill width minus padding and gaps) is shared out. */
    fun tabSlotWidth(index: Int, count: Int, availablePx: Float, selection: Float): Float {
        if (count <= 0) return 0f
        return availablePx.coerceAtLeast(0f) * tabWeight(index, count, selection) / totalWeight(count, selection)
    }

    /** Start of tab [index], relative to the first tab's start. */
    fun tabSlotStart(index: Int, count: Int, availablePx: Float, gapPx: Float, selection: Float): Float {
        var x = 0f
        for (i in 0 until index.coerceIn(0, count)) {
            x += tabSlotWidth(i, count, availablePx, selection) + gapPx
        }
        return x
    }

    /**
     * Start of the selected-tab capsule. It glides between the two slots the selection sits
     * between and is allowed to overshoot a little with the spring (the slot widths are not).
     */
    fun indicatorStart(count: Int, availablePx: Float, gapPx: Float, selection: Float): Float {
        if (count <= 1) return 0f
        val i0 = segmentStart(count, selection)
        val f = selection - i0
        val a = tabSlotStart(i0, count, availablePx, gapPx, selection)
        val b = tabSlotStart(i0 + 1, count, availablePx, gapPx, selection)
        return a + (b - a) * f
    }

    /** Width of the selected-tab capsule, interpolated like [indicatorStart]. */
    fun indicatorWidth(count: Int, availablePx: Float, selection: Float): Float {
        if (count <= 0) return 0f
        if (count == 1) return availablePx.coerceAtLeast(0f)
        val i0 = segmentStart(count, selection)
        val f = selection - i0
        val a = tabSlotWidth(i0, count, availablePx, selection)
        val b = tabSlotWidth(i0 + 1, count, availablePx, selection)
        return (a + (b - a) * f).coerceAtLeast(0f)
    }

    /** The slot pair the selection sits between; outside 0..count-1 it keeps the end pair. */
    private fun segmentStart(count: Int, selection: Float): Int =
        floor(selection).toInt().coerceIn(0, count - 2)

    /** Alpha of the content that leaves as the morph goes 0 → 1 (gone by [end]). */
    fun fadeOut(progress: Float, end: Float = 0.45f): Float =
        (1f - progress / end).coerceIn(0f, 1f)

    /** Alpha of the content that arrives as the morph goes 0 → 1 (starts at [start]). */
    fun fadeIn(progress: Float, start: Float = 0.35f): Float =
        ((progress - start) / (1f - start)).coerceIn(0f, 1f)

    /**
     * How far the bar must rise so the search pill sits [gapPx] above the keyboard.
     * [restingBottomPx] is the pill's distance from the window bottom without a keyboard.
     */
    fun imeLift(imeBottomPx: Float, restingBottomPx: Float, gapPx: Float): Float {
        if (imeBottomPx <= 0f) return 0f
        return (imeBottomPx + gapPx - restingBottomPx).coerceAtLeast(0f)
    }

    /** The user's nav corner radius, capped at a full pill for [pillHeight]. */
    fun pillCornerRadius(requested: Float, pillHeight: Float): Float =
        requested.coerceIn(0f, (pillHeight / 2f).coerceAtLeast(0f))
}
