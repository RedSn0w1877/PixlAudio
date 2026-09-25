package com.theveloper.pixelplay.ui.glass

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
    private val cache = HashMap<Int, RoundedCornerShape>()

    fun get(radius: Dp): RoundedCornerShape {
        val key = (radius.value.coerceAtLeast(0f) / stepDp).roundToInt()
        return cache.getOrPut(key) { RoundedCornerShape((key * stepDp).dp) }
    }
}
