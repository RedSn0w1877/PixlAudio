package com.theveloper.pixelplay.ui.theme

import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Animated corner radii without a new shape per frame.
 *
 * The radius is rounded to 0.5dp steps and each step's shape is created once and reused, so an
 * animation costs at most one allocation per distinct step it passes through (and none once every
 * step has been seen) while the outline still updates whenever the step changes. Anything that
 * caches an outline by shape equality sees the change, because each step is its own instance.
 */
class QuantizedCornerShapeCache(private val stepDp: Float = 0.5f) {
    // Primitive-keyed maps: a HashMap<Long, …> lookup boxes its key, and callers look up their
    // shape on every frame of an animation.
    private val cache = MutableIntObjectMap<RoundedCornerShape>()

    private val unevenCache = MutableLongObjectMap<RoundedCornerShape>()

    fun get(radius: Dp): RoundedCornerShape {
        val key = step(radius)
        return cache.getOrPut(key) { RoundedCornerShape((key * stepDp).dp) }
    }

    /** Top corners at [top], bottom corners at [bottom]. */
    fun get(top: Dp, bottom: Dp): RoundedCornerShape {
        val topKey = step(top)
        val bottomKey = step(bottom)
        if (topKey == bottomKey) return get(top)
        val key = (topKey.toLong() shl 32) or (bottomKey.toLong() and 0xFFFFFFFFL)
        return unevenCache.getOrPut(key) {
            RoundedCornerShape(
                topStart = (topKey * stepDp).dp,
                topEnd = (topKey * stepDp).dp,
                bottomEnd = (bottomKey * stepDp).dp,
                bottomStart = (bottomKey * stepDp).dp
            )
        }
    }

    private fun step(radius: Dp): Int {
        val value = radius.value
        if (!value.isFinite()) return 0
        return (value.coerceAtLeast(0f) / stepDp).roundToInt()
    }
}
