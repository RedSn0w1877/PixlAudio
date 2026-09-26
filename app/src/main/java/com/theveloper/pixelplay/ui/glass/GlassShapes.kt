package com.theveloper.pixelplay.ui.glass

import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule as KyantCapsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

/**
 * Shapes for glass surfaces.
 *
 * The continuous-curvature [KyantCapsule] and [RoundedRectangle] from `io.github.kyant0:shapes`
 * implement `RoundedRectangularShape`, which the backdrop library's `lens()` reads its corner
 * radii from natively — so there is no need for the old "draw a `RoundedCornerShape` stand-in
 * under a smooth-corner clip" workaround. They are also plain Compose [androidx.compose.ui.graphics.Shape]s,
 * so `clip`, `background` and `border` take them too.
 */
object GlassShapes {
    /** A full pill: radius = half the short side, continuous corners. */
    val Capsule: KyantCapsule = KyantCapsule()

    val Button: RoundedRectangle = RoundedRectangle(20.dp)
    val Card: RoundedRectangle = RoundedRectangle(24.dp)
    val Dialog: RoundedRectangle = RoundedRectangle(32.dp)

    /** Sheets keep a Compose [CornerBasedShape] because their public API takes one. */
    val Sheet: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** A continuous-corner rounded rectangle. Cheap to create, but hoist it when you can. */
    fun rounded(radius: Dp): RoundedRectangle = RoundedRectangle(radius)
}

/**
 * A [CornerSize] whose value is read from [provider] every time an outline is built, so one
 * `RoundedCornerShape(ProviderCornerSize { animatedRadius })` can be created once with `remember`
 * and still animate, without allocating a new shape per frame. It is still a [CornerBasedShape],
 * so `lens()` accepts it.
 *
 * **Caveat:** anything that caches an outline by shape *equality* will not see the radius change,
 * because the shape instance never changes. The backdrop library's own `ShapeProvider` does exactly
 * that, so for a shape handed to `drawBackdrop` use [QuantizedCornerShapeCache] instead. This is
 * the right tool for `graphicsLayer { shape = … }` and `Modifier.clip`, which re-query the outline
 * whenever the layer is invalidated.
 */
@Stable
class ProviderCornerSize(private val provider: () -> Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        with(density) { provider().toPx() }.coerceIn(0f, shapeSize.minDimension / 2f)

    override fun toString(): String = "ProviderCornerSize"
}

/**
 * Animated corner radii for `drawBackdrop`, which caches its outline by shape equality.
 *
 * The radius is rounded to 0.5dp steps and each step's shape is created once and reused, so an
 * animation costs at most one allocation per distinct step it passes through (and none once every
 * step has been seen) while the outline still updates whenever the step changes.
 */
class QuantizedCornerShapeCache(private val stepDp: Float = 0.5f) {
    // Primitive-keyed maps: a HashMap<Long, …> lookup boxes its key, and the mini player looks
    // up its shape several times per frame while the sheet is dragged.
    private val cache = MutableIntObjectMap<RoundedCornerShape>()

    private val unevenCache = MutableLongObjectMap<RoundedCornerShape>()

    fun get(radius: Dp): RoundedCornerShape {
        val key = step(radius)
        return cache.getOrPut(key) { RoundedCornerShape((key * stepDp).dp) }
    }

    /** Top corners at [top], bottom corners at [bottom] (the player card's shape). */
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
