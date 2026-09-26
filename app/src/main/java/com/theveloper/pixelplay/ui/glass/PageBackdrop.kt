package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toIntSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A drop-in replacement for the library's `rememberLayerBackdrop` + `Modifier.layerBackdrop` that
 * records the content's display list **once per frame instead of twice**.
 *
 * ## Why this exists
 *
 * The library's `LayerBackdropNode.draw()` is:
 *
 * ```
 * drawContent()                              // (1) record the subtree into the parent's list
 * recordLayer(layer) { drawContent() }        // (2) record the SAME subtree again, into the layer
 * ```
 *
 * Step (2) is what glass panels sample. The cost it adds is CPU-side: every frame the recorded
 * subtree redraws, its draw ops are walked and recorded into a display list a second time — and
 * in this app that subtree is `AppNavigation`, i.e. the entire screen, including whatever
 * `LazyColumn` the user is flinging.
 *
 * This version instead does:
 *
 * ```
 * layer.record { drawContent() }   // record the subtree ONCE, into the layer
 * drawLayer(layer)                 // reference that layer from the parent's list
 * ```
 *
 * Same pixels, same layer available for sampling, one display-list *record* instead of two. It
 * does not save any GPU rasterisation: the RenderThread still draws the layer once for the screen
 * and once per glass surface that samples it.
 *
 * ## Why it is vendored rather than configured
 *
 * `LayerBackdrop.layerCoordinates` is `internal` to the library, and `drawBackdrop` bails out
 * early when it is null, so the position bookkeeping cannot be driven from outside the library's
 * own modifier — a custom node has to own the whole type. [Backdrop] itself is public, so this
 * class plugs into the library's `Modifier.drawBackdrop` unchanged. Ported from
 * Kyant0/AndroidLiquidGlass (Apache-2.0), whose positioning and inverse-transform logic is
 * reproduced faithfully — only the double record is gone.
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
     * How many window-backed glass surfaces (sheets, dialogs) currently want a snapshot of the
     * window. Sheets register through [RegisterGlassSnapshot]; MainActivity captures only while
     * this is above zero and drops the bitmap when it falls back to zero. Nothing runs on a timer.
     */
    var snapshotRequests: Int by mutableIntStateOf(0)
        internal set

    /**
     * A half-size rasterised copy of the whole window (page, nav bar and mini player), for the one
     * case the live layer cannot cover: a `ModalBottomSheet` (or any Dialog/Popup-backed surface)
     * renders into its OWN Android window, and a [GraphicsLayer] recorded by one window's renderer
     * is not something another window can reliably draw. A plain [ImageBitmap] has no window
     * affinity, so it can.
     *
     * Captured on demand by MainActivity (see [captureWindowSnapshot]): once, a frame after the
     * first sheet registers, once more after the sheet's enter animation has settled, and cleared
     * when the last sheet goes away. About 4 MB instead of the 16 MB a full-size copy costs.
     */
    internal var snapshotBitmap: ImageBitmap? by mutableStateOf(null)

    /** The full (pre-downscale) size of [snapshotBitmap], i.e. the size it is drawn back at. */
    internal var snapshotSize: IntSize = IntSize.Zero

    /** `SystemClock.uptimeMillis()` of the last capture (0 = none), to reuse it for back-to-back sheets. */
    internal var snapshotTakenAtMs: Long = 0L

    /** A snapshot taken within [maxAgeMs] is still on hand (the previous sheet just closed). */
    fun hasFreshWindowSnapshot(maxAgeMs: Long): Boolean =
        snapshotBitmap != null && android.os.SystemClock.uptimeMillis() - snapshotTakenAtMs <= maxAgeMs

    /**
     * Screen position of the snapshot's top-left corner. Screen, not window, coordinates: an
     * AlertDialog's window is centred and wrap-sized, so window positions from inside it do not
     * line up with the main window's.
     */
    internal var snapshotOrigin: Offset = Offset.Zero

    /**
     * A backdrop that only ever draws [snapshotBitmap]. Window-backed surfaces sample this rather
     * than the page itself, so they never try to draw a layer owned by another window.
     */
    val snapshotBackdrop: Backdrop = object : Backdrop {
        override val isCoordinatesDependent: Boolean = true

        override fun DrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?
        ) {
            drawSnapshot(this, density, coordinates ?: return, layerBlock)
        }
    }

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
        drawSnapshot(this, density, coordinates, layerBlock)
    }

    private fun drawSnapshot(
        scope: DrawScope,
        density: Density,
        coordinates: LayoutCoordinates,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val bitmap = snapshotBitmap ?: return
        val fullSize = snapshotSize
        if (fullSize.width <= 0 || fullSize.height <= 0) return
        // Screen coordinates on both sides: a sheet's window is fullscreen, but a dialog's is centred
        // and wrap-sized, so only screen positions line up across windows.
        val offset = coordinates.positionOnScreen() - snapshotOrigin
        scope.withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            translate(-offset.x, -offset.y)
        }) {
            drawImage(
                image = bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = fullSize,
                filterQuality = FilterQuality.Low
            )
        }
    }

    private fun obtainInverseLayerScope(): InverseGlassLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseGlassLayerScope().also { inverseLayerScope = it }
    }
}

/**
 * Registers the calling window-backed glass surface (a sheet or dialog) as wanting a window
 * snapshot for as long as it is in the composition. See [PageBackdrop.snapshotRequests].
 */
@Composable
fun RegisterGlassSnapshot(backdrop: Backdrop = LocalPageBackdrop.current) {
    val page = backdrop as? PageBackdrop ?: return
    DisposableEffect(page) {
        page.snapshotRequests++
        onDispose { page.snapshotRequests = (page.snapshotRequests - 1).coerceAtLeast(0) }
    }
}

/**
 * Marks the element this is applied to as the source of [PageBackdrop.snapshotBitmap]: while a
 * sheet has asked for a snapshot ([PageBackdrop.snapshotRequests] > 0), its content (the page plus
 * the app chrome) is recorded into [windowLayer] and drawn from it, so [captureWindowSnapshot] has
 * something to rasterise; otherwise it just draws its content. Nothing samples [windowLayer] live,
 * so this cannot form a cycle.
 *
 * The request count is read in the draw phase, so a sheet opening or closing only redraws this
 * element instead of recomposing the screen that applies it. Apply it only in glass mode.
 */
fun Modifier.glassSnapshotSource(
    backdrop: PageBackdrop,
    windowLayer: GraphicsLayer
): Modifier = this then GlassSnapshotSourceElement(backdrop, windowLayer)

private class GlassSnapshotSourceElement(
    val backdrop: PageBackdrop,
    val windowLayer: GraphicsLayer
) : ModifierNodeElement<GlassSnapshotSourceNode>() {

    override fun create(): GlassSnapshotSourceNode = GlassSnapshotSourceNode(backdrop, windowLayer)

    override fun update(node: GlassSnapshotSourceNode) {
        node.backdrop = backdrop
        node.windowLayer = windowLayer
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassSnapshotSource"
        properties["backdrop"] = backdrop
        properties["windowLayer"] = windowLayer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassSnapshotSourceElement) return false
        return backdrop == other.backdrop && windowLayer == other.windowLayer
    }

    override fun hashCode(): Int = 31 * backdrop.hashCode() + windowLayer.hashCode()
}

private class GlassSnapshotSourceNode(
    var backdrop: PageBackdrop,
    var windowLayer: GraphicsLayer
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val drawSize = size
        if (backdrop.snapshotRequests <= 0 || drawSize.width < 1f || drawSize.height < 1f) {
            drawContent()
            return
        }
        windowLayer.record(
            density = requireDensity(),
            layoutDirection = requireLayoutDirection(),
            size = drawSize.toIntSize()
        ) {
            this@draw.drawContent()
        }
        drawLayer(windowLayer)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) backdrop.snapshotOrigin = coordinates.positionOnScreen()
    }
}

/**
 * Rasterises [windowLayer] at half size into [PageBackdrop.snapshotBitmap], using [scratchLayer] to
 * hold the downscaled recording. Must be called from the composition/coroutine of the window that
 * owns [windowLayer] — an earlier attempt captured from inside the sheet's own window and silently
 * failed every time.
 *
 * @return false if the window layer has not been recorded yet (try again next frame).
 */
suspend fun PageBackdrop.captureWindowSnapshot(
    windowLayer: GraphicsLayer,
    scratchLayer: GraphicsLayer,
    density: Density,
    layoutDirection: LayoutDirection
): Boolean {
    val full = windowLayer.size
    if (full.width < 2 || full.height < 2) return false
    val half = IntSize(full.width / 2, full.height / 2)
    scratchLayer.record(density, layoutDirection, half) {
        scale(0.5f, 0.5f, pivot = Offset.Zero) { drawLayer(windowLayer) }
    }
    val bitmap = scratchLayer.toImageBitmap()
    snapshotSize = full
    snapshotBitmap = bitmap
    snapshotTakenAtMs = android.os.SystemClock.uptimeMillis()
    return true
}

/** Drops the window snapshot (called when the last sheet unregisters). */
fun PageBackdrop.clearWindowSnapshot() {
    snapshotBitmap = null
    snapshotSize = IntSize.Zero
    snapshotTakenAtMs = 0L
}

/**
 * The backdrops whose recording the current composition position is *inside of*. A glass surface
 * that samples one of these would make the recording depend on itself — the RenderThread
 * `prepareTreeImpl` stack overflow this app hit before. [liquidGlass] checks it.
 */
val LocalRecordingBackdrops = staticCompositionLocalOf<Set<Backdrop>> { emptySet() }

/**
 * Records [content] into [backdrop] so chrome drawn as a *sibling* above it (a top bar, a floating
 * toolbar) can refract it. Everything inside is the content layer: [LocalGlassLayer] becomes
 * [GlassLayer.Content], [LocalAppBackdrop] becomes empty, and [backdrop] joins
 * [LocalRecordingBackdrops] so nothing inside can sample its own recording.
 *
 * @param enabled false skips the recording (Material 3 mode) but keeps the same layout.
 */
@Composable
fun RecordedContent(
    backdrop: PageBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val recording = LocalRecordingBackdrops.current
    Box(modifier = if (enabled) modifier.pageBackdrop(backdrop) else modifier) {
        CompositionLocalProvider(
            LocalRecordingBackdrops provides recording + backdrop,
            LocalGlassLayer provides GlassLayer.Content,
            LocalAppBackdrop provides emptyBackdrop()
        ) {
            content()
        }
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
