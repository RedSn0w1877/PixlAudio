package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toIntSize
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A drop-in replacement for the library's `rememberLayerBackdrop` + `Modifier.layerBackdrop` that
 * draws the recorded content **once per frame instead of twice**.
 *
 * ## Why this exists
 *
 * The library's `LayerBackdropNode.draw()` is:
 *
 * ```
 * drawContent()                              // (1) rasterize subtree straight to the screen
 * recordLayer(layer) { drawContent() }        // (2) rasterize the SAME subtree into the layer
 * ```
 *
 * Step (2) is what glass panels sample. But it means the whole recorded subtree is walked and
 * rasterized twice every frame it redraws — and in this app that subtree is `AppNavigation`, i.e.
 * the entire screen, including whatever `LazyColumn` the user is currently flinging. So turning
 * glass on roughly doubled the app's per-frame render cost, which is exactly the wrong tradeoff
 * on a 120Hz panel where the budget is 8.3ms.
 *
 * This version instead does:
 *
 * ```
 * layer.record { drawContent() }   // rasterize the subtree ONCE, into the layer
 * drawLayer(layer)                 // blit that layer to the screen (one textured quad)
 * ```
 *
 * Same pixels on screen, same layer available for sampling, one traversal instead of two. The
 * added cost is a single full-screen texture blit, which is trivially cheap next to re-recording
 * and re-rasterizing an entire scrolling list.
 *
 * ## Why it is vendored rather than configured
 *
 * `LayerBackdrop.layerCoordinates` is `internal` to the library, and `drawBackdrop` bails out
 * early when it is null, so the position bookkeeping cannot be driven from outside the library's
 * own modifier — a custom node has to own the whole type. [Backdrop] itself is public, so this
 * class plugs into the library's `Modifier.drawBackdrop` unchanged; every existing glass call site
 * works against it without modification. Ported from Kyant0/AndroidLiquidGlass (Apache-2.0), whose
 * positioning and inverse-transform logic is reproduced faithfully — only the double draw is gone.
 */
@Composable
fun rememberPageBackdrop(): PageBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { PageBackdrop(layer) }
}

@Stable
class PageBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * A rasterized copy of [graphicsLayer], for the ONE case the live layer can't cover: a
     * `ModalBottomSheet` (or any Dialog/Popup-backed surface) renders into its OWN Android
     * Window, and a [GraphicsLayer] recorded in one window's hardware renderer cannot be drawn
     * from another window's — that's why [layerCoordinates] stays null there and this backdrop
     * would otherwise silently no-op, drawing only the glass tint with zero refraction. A plain
     * [ImageBitmap] has no window affinity, so it can.
     *
     * Populated by a throttled loop in MainActivity (≈3/sec, not every frame — a sheet's backdrop
     * is static almost the entire time it's open, so slightly-stale reads as live), called from
     * MainActivity's OWN composition/coroutine since it's the window that actually owns
     * [graphicsLayer]. An earlier attempt triggered the capture from inside the sheet's own
     * composition instead — cross-window, touching a layer it doesn't own — and silently failed
     * every single time.
     */
    internal var snapshotBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)

    private var inverseLayerScope: InverseGlassLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val liveLayerCoordinates = layerCoordinates
        if (liveLayerCoordinates != null) {
            withTransform({
                if (layerBlock != null) {
                    with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
                }
                val offset =
                    try {
                        liveLayerCoordinates.localPositionOf(coordinates)
                    } catch (_: Exception) {
                        coordinates.positionInWindow() - liveLayerCoordinates.positionInWindow()
                    }
                translate(-offset.x, -offset.y)
            }) {
                drawLayer(graphicsLayer)
            }
            return
        }

        // Different window: fall back to the rasterized snapshot. Both windows are fullscreen
        // (the sheet's Dialog uses usePlatformDefaultWidth = false), so positionInWindow() lines
        // up between them without needing a second window's origin to subtract.
        val bitmap = snapshotBitmap ?: return
        val offset = coordinates.positionInWindow()
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            translate(-offset.x, -offset.y)
        }) {
            drawImage(bitmap)
        }
    }

    private fun obtainInverseLayerScope(): InverseGlassLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseGlassLayerScope().also { inverseLayerScope = it }
    }
}

/**
 * Records this subtree into [backdrop] and draws it from that recording, so glass panels
 * elsewhere in the tree can refract it. See [rememberPageBackdrop] for why this is not the
 * library's own `Modifier.layerBackdrop`.
 */
fun Modifier.pageBackdrop(backdrop: PageBackdrop): Modifier =
    this then PageBackdropElement(backdrop)

private class PageBackdropElement(
    val backdrop: PageBackdrop
) : ModifierNodeElement<PageBackdropNode>() {

    override fun create(): PageBackdropNode = PageBackdropNode(backdrop)

    override fun update(node: PageBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pageBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PageBackdropElement) return false
        return backdrop == other.backdrop
    }

    override fun hashCode(): Int = backdrop.hashCode()
}

private class PageBackdropNode(
    var backdrop: PageBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val layer = backdrop.graphicsLayer
        val drawSize = size
        // Zero-area layers are illegal to record and happen transiently during layout.
        if (drawSize.width < 1f || drawSize.height < 1f) {
            drawContent()
            return
        }
        layer.record(
            density = requireDensity(),
            layoutDirection = requireLayoutDirection(),
            size = drawSize.toIntSize()
        ) {
            this@draw.drawContent()
        }
        drawLayer(layer)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}

/**
 * A [GraphicsLayerScope] that captures a `layerBlock`'s transform so it can be undone.
 *
 * When a glass panel animates itself (the nav bar pill squashing and stretching as it slides), the
 * backdrop it samples must NOT inherit that animation — otherwise the refracted image stretches
 * along with the glass and the illusion collapses. So the panel's own `layerBlock` is replayed
 * here purely to read `scaleX`/`scaleY`/`rotationZ` back out, and the inverse is applied to the
 * backdrop draw. Every other property is captured and ignored.
 */
internal class InverseGlassLayerScope : GraphicsLayerScope {

    override var size: Size = Size.Unspecified
    override var density: Float = 1f
    override var fontScale: Float = 1f

    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 0f
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

    private var matrix: Matrix? = null

    fun DrawTransform.inverseTransform(
        density: Density,
        layerBlock: GraphicsLayerScope.() -> Unit
    ) {
        this@InverseGlassLayerScope.size = size
        this@InverseGlassLayerScope.density = density.density
        fontScale = density.fontScale

        layerBlock()

        inverseTransformAtTopLeft(
            rotationZ = rotationZ,
            scaleX = scaleX,
            scaleY = scaleY
        )
    }

    fun reset() {
        size = Size.Unspecified
        density = 1f
        fontScale = 1f

        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        shadowElevation = 0f
        ambientShadowColor = DefaultShadowColor
        spotShadowColor = DefaultShadowColor
        rotationX = 0f
        rotationY = 0f
        rotationZ = 0f
        cameraDistance = DefaultCameraDistance
        transformOrigin = TransformOrigin.Center
        shape = RectangleShape
        clip = false
        renderEffect = null
        blendMode = BlendMode.SrcOver
        colorFilter = null
        compositingStrategy = CompositingStrategy.Auto

        matrix = null
    }

    private fun DrawTransform.inverseTransformAtTopLeft(
        rotationZ: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        if (rotationZ == 0f) {
            if (scaleX != 0f && scaleY != 0f) {
                scale(1f / scaleX, 1f / scaleY, Offset.Zero)
            }
            return
        }

        val matrix = matrix ?: Matrix().also { matrix = it }
        if (matrix.values.size < 16) return

        val rz = rotationZ * (PI / 180.0)
        val rsz = sin(rz).toFloat()
        val rcz = cos(rz).toFloat()

        val a00 = rcz * scaleX
        val a01 = rsz * scaleY
        val a10 = -rsz * scaleX
        val a11 = rcz * scaleY

        val det = a00 * a11 - a01 * a10
        if (det == 0f) return
        val invDet = 1f / det
        matrix[0, 0] = a11 * invDet
        matrix[0, 1] = -a01 * invDet
        matrix[1, 0] = -a10 * invDet
        matrix[1, 1] = a00 * invDet

        transform(matrix)
    }
}
