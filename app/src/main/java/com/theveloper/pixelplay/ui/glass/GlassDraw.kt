@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.drawBackdrop as libraryDrawBackdrop

/**
 * The app's single entry point to Backdrop's `drawBackdrop` (same signature; every call site imports
 * this one instead of the library's). It keeps the library's look but rebuilds its modifier chain for
 * the render thread:
 *
 * 1. **The lens lives in its own RenderNode.** The library chains inner shadow → shadow → highlight →
 *    backdrop in ONE layer, so any change to a rim highlight (light spill, press, gravity) re-recorded
 *    the refraction layer too, and HWUI threw away and re-rendered that lens's offscreen texture. During
 *    a touch that was ~70 lenses per frame. Here the rim, inner shadow and drop shadow sit in an outer
 *    layer. The lens sits behind a plain `graphicsLayer()` and is only re-rendered when its own effects change.
 * 2. **`layerBlock` (press swell / jelly) transforms the outer layer.** The inverse that keeps the refracted
 *    wallpaper anchored while the glass moves is re-applied inside `onDrawBackdrop`, mirroring the
 *    library's own `InverseLayerScope` (rotate + scale about the top-left; translation is already
 *    part of the coordinates the backdrop reads).
 * 3. **No CPU blur masks.** The library draws rims and drop shadows with a `BlurMaskFilter`, which on
 *    the GPU renderer rasterizes on the CPU and uploads a texture on every draw. Rims keep their width,
 *    color and shader but drop the mask blur; drop shadows become a soft gradient glow.
 * 4. **Rims only redraw when they change.** Each highlight is read through `derivedStateOf`, so a
 *    surface far from the spilled light isn't invalidated at all while the light moves.
 *
 * Ported from NexHome's `glass/GlassDraw.kt` (the owner's own code). [InverseLayer] mirrors the
 * library's internal `InverseLayerScope` (Kyant Backdrop, Apache-2.0). It reaches the library's
 * internal `HighlightElement` / `InnerShadowElement` / `ShapeProvider`, so the Backdrop version is
 * pinned (2.0.0); PixlAudio change: the round drop-shadow gradient is cached per shadow and size
 * instead of being rebuilt on every draw.
 */
fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    val rim = highlight?.let { h ->
        val state = derivedStateOf { h()?.let { if (it.blurRadius.value > 0f) it.copy(blurRadius = 0.dp) else it } }
        val read: () -> Highlight? = { state.value }
        read
    }
    val inverse = layerBlock?.let { InverseLayer(it) }
    val shadowCache = if (shadow != null) GradientShadowCache() else null

    return this
        .then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
        .then(if (innerShadow != null) InnerShadowElement(shapeProvider, innerShadow) else Modifier)
        .then(
            if (shadow != null && shadowCache != null) {
                Modifier.drawBehind { shadow()?.let { drawGradientShadow(it, shadowCache) } }
            } else {
                Modifier
            }
        )
        .then(if (rim != null) HighlightElement(shapeProvider, rim) else Modifier)
        // Isolation boundary: everything above can redraw without touching the lens below.
        .graphicsLayer()
        .libraryDrawBackdrop(
            backdrop = backdrop,
            shape = shape,
            effects = effects,
            highlight = null,
            shadow = null,
            innerShadow = null,
            layerBlock = null,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = if (inverse == null) {
                onDrawBackdrop
            } else {
                { drawBackdrop -> onDrawBackdrop { inverse.draw(this, drawBackdrop) } }
            },
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
        )
}

private val DefaultHighlight: () -> Highlight? = { Highlight.Default }
private val DefaultShadow: () -> Shadow? = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

/**
 * Evaluates a [layerBlock] off-layer and draws the backdrop through its inverse, like the library's
 * internal `InverseLayerScope.inverseTransform`: size = the recording size, then undo rotationZ and
 * scale about the top-left.
 */
private class InverseLayer(private val block: GraphicsLayerScope.() -> Unit) : GraphicsLayerScope {
    override var size: Size = Size.Unspecified
    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = DefaultCameraDistance
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var renderEffect: RenderEffect? = null
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    fun draw(scope: DrawScope, drawBackdrop: DrawScope.() -> Unit) {
        scaleX = 1f; scaleY = 1f; rotationZ = 0f
        size = scope.size
        density = scope.density
        fontScale = scope.fontScale
        block()
        val sx = scaleX
        val sy = scaleY
        val rz = rotationZ
        if (sx == 1f && sy == 1f && rz == 0f || sx == 0f || sy == 0f) {
            scope.drawBackdrop()
            return
        }
        scope.withTransform({
            if (rz != 0f) rotate(-rz, Offset.Zero)
            scale(1f / sx, 1f / sy, Offset.Zero)
        }) { drawBackdrop() }
    }
}

/**
 * A GPU-only stand-in for the library's blurred drop shadow (offset like the original). Round-ish
 * shapes get a radial falloff; wide ones (capsules, rows) get a few stacked soft rounded rects.
 */
private fun DrawScope.drawGradientShadow(shadow: Shadow, cache: GradientShadowCache) {
    val alpha = shadow.alpha * shadow.color.alpha
    if (alpha <= 0.002f) return
    val radius = shadow.radius.toPx()
    if (radius <= 0f || size.minDimension <= 0f) return
    val dx = shadow.offset.x.toPx()
    val dy = shadow.offset.y.toPx()
    val base = shadow.color.copy(alpha = alpha)

    if (size.maxDimension - size.minDimension <= size.maxDimension * 0.15f) {
        val center = Offset(size.width / 2f + dx, size.height / 2f + dy)
        val reach = size.maxDimension / 2f + radius
        // Solid until just inside the shape's edge, then fading out over the blur radius.
        val solidStop = ((size.minDimension / 2f - radius * 0.25f) / reach).coerceIn(0f, 0.95f)
        // The gradient carries the shadow colour; its animated alpha is applied at draw time, so
        // a fading shadow reuses the cached brush (same premultiplied result as baking it in).
        drawCircle(
            brush = cache.radial(shadow.color, solidStop, center, reach),
            radius = reach,
            center = center,
            alpha = shadow.alpha.coerceIn(0f, 1f),
            blendMode = shadow.blendMode,
        )
    } else {
        val steps = 4
        val corner = size.minDimension / 2f
        for (i in 1..steps) {
            val grow = radius * i / steps
            drawRoundRect(
                color = base.copy(alpha = base.alpha * (1f - (i - 1f) / steps) / steps * 1.6f),
                topLeft = Offset(dx - grow, dy - grow),
                size = androidx.compose.ui.geometry.Size(size.width + grow * 2f, size.height + grow * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner + grow),
                blendMode = shadow.blendMode,
            )
        }
    }
}

/**
 * Holds the last round-shadow gradient so a shadow whose colour, size and offset are unchanged
 * (the usual case: a pressed blob's shadow only fades its alpha through [Shadow.alpha]) doesn't
 * allocate a new [Brush] on every draw.
 */
private class GradientShadowCache {
    private var brush: Brush? = null
    private var color: Color = Color.Unspecified
    private var solidStop = -1f
    private var center = Offset.Unspecified
    private var reach = -1f

    fun radial(base: Color, solidStop: Float, center: Offset, reach: Float): Brush {
        val cached = brush
        if (cached != null && base == color && solidStop == this.solidStop && center == this.center &&
            reach == this.reach
        ) {
            return cached
        }
        return Brush.radialGradient(
            0f to base,
            solidStop to base,
            1f to Color.Transparent,
            center = center,
            radius = reach,
        ).also {
            brush = it
            color = base
            this.solidStop = solidStop
            this.center = center
            this.reach = reach
        }
    }
}
