package com.theveloper.pixelplay.ui.glass.light

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Light spill" (ported from NexHome): the one shared light of a window. Holding an accent-coloured
 * glass element (an EMITTER) tints the rims of nearby glass (RECEIVERS) toward it.
 *
 * State: [position] in ROOT px, [color], [intensity] 0..1, [reachPx]. Only one emitter owns the
 * light at a time — the most recent touch wins; stale owners cannot move or end it.
 *
 * NexHome's surface spill glow and wallpaper caster were turned into no-ops there (they forced every
 * lens, and the sheet blur, to re-render on every frame of a hold) and are not ported at all here:
 * spill is only the rim tint ([GlassLightReceiver.highlight]), kept subtle ("glow, not blind").
 */
@Stable
class GlassLight internal constructor(
    private val scope: CoroutineScope,
    reachPx: Float,
) {

    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val colorAnimation = Animatable(Color.White)

    /** 0..1 light intensity (blooms on hold, fades after release). */
    val intensity: Animatable<Float, AnimationVector1D> = Animatable(0f, 0.001f)

    /** How far (px, from the element's nearest edge) the light reaches. */
    var reachPx: Float by mutableFloatStateOf(reachPx)

    /** Light position in root coordinates (px). */
    val position: Offset get() = positionAnimation.value

    /** Current light color (opaque). */
    val color: Color get() = colorAnimation.value

    private var owner: Any? = null

    private var moveTarget = Offset.Zero
    private var moveLaunchPending = false

    /** Starts (or takes over) the light for [owner] at [rootPosition]; intensity blooms to [peak]. */
    fun begin(owner: Any, rootPosition: Offset, color: Color, peak: Float = 1f) {
        val wasDark = intensity.value <= 0.02f
        this.owner = owner
        moveTarget = rootPosition
        val opaque = color.copy(alpha = 1f)
        // Launched directly (not nested) so a following move() is dispatched after these.
        if (wasDark) {
            scope.launch { positionAnimation.snapTo(rootPosition) }
            scope.launch { colorAnimation.snapTo(opaque) }
        } else {
            scope.launch { positionAnimation.animateTo(rootPosition, LiquidMotion.GlideSpring) }
            scope.launch { colorAnimation.animateTo(opaque, ColorGlideSpring) }
        }
        scope.launch { intensity.animateTo(peak.fastCoerceIn(0f, 1f), LiquidMotion.LightBloomSpring) }
    }

    /**
     * Glides the light after [owner]'s finger (ignored if another emitter took over). Several moves
     * inside one frame share one launch (PixlAudio change; NexHome launched per pointer event).
     */
    fun move(owner: Any, rootPosition: Offset) {
        if (this.owner !== owner) return
        moveTarget = rootPosition
        if (moveLaunchPending) return
        moveLaunchPending = true
        scope.launch {
            moveLaunchPending = false
            positionAnimation.animateTo(moveTarget, LiquidMotion.GlideSpring)
        }
    }

    /** Animates [owner]'s light toward [peak]. */
    fun setPeak(owner: Any, peak: Float, spec: AnimationSpec<Float> = LiquidMotion.LightBloomSpring) {
        if (this.owner !== owner) return
        scope.launch { intensity.animateTo(peak.fastCoerceIn(0f, 1f), spec) }
    }

    /** Changes [owner]'s light color smoothly. */
    fun setColor(owner: Any, color: Color) {
        if (this.owner !== owner) return
        scope.launch { colorAnimation.animateTo(color.copy(alpha = 1f), ColorGlideSpring) }
    }

    /** Fades [owner]'s light out gracefully (no-op if another emitter owns the light). */
    fun end(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        scope.launch { intensity.animateTo(0f, LiquidMotion.LightFadeSpring) }
    }
}

private val ColorGlideSpring = spring<Color>(dampingRatio = 1f, stiffness = 260f)

/** The window's light; `null` when not provided (receivers/emitters then do nothing). */
val LocalGlassLight = staticCompositionLocalOf<GlassLight?> { null }

/** Remembers a [GlassLight] whose reach is [reach]. */
@Composable
fun rememberGlassLight(reach: Dp = 240.dp): GlassLight {
    val scope = rememberCoroutineScope()
    val reachPx = with(LocalDensity.current) { reach.toPx() }
    val light = remember(scope) { GlassLight(scope, reachPx) }
    SideEffect { light.reachPx = reachPx }
    return light
}

/** Provides [light] as [LocalGlassLight]. Wrap the glass root. */
@Composable
fun ProvideGlassLight(light: GlassLight = rememberGlassLight(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassLight provides light, content = content)
}

/**
 * Makes this element an EMITTER: while touched, the shared light follows the finger in [color].
 * Pointers are only inspected (never consumed) so scroll/click keep working. Multiple touches:
 * the last pointer wins. Leaving composition mid-press fades the light out.
 *
 * PixlAudio change: a `Modifier.Node` (NexHome used `Modifier.composed`, which allocates a
 * composition per use and can't be skipped).
 *
 * @param releaseOnScroll fade out as soon as an ancestor (e.g. a scroll container) consumes the drag.
 */
fun Modifier.glassLightEmitter(
    color: () -> Color,
    enabled: Boolean = true,
    releaseOnScroll: Boolean = false,
): Modifier = if (!enabled) this else this then GlassLightEmitterElement(color, releaseOnScroll)

private class GlassLightEmitterElement(
    val color: () -> Color,
    val releaseOnScroll: Boolean,
) : ModifierNodeElement<GlassLightEmitterNode>() {

    override fun create(): GlassLightEmitterNode = GlassLightEmitterNode(color, releaseOnScroll)

    override fun update(node: GlassLightEmitterNode) {
        node.update(color, releaseOnScroll)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassLightEmitter"
        properties["releaseOnScroll"] = releaseOnScroll
    }

    override fun equals(other: Any?): Boolean =
        other is GlassLightEmitterElement && other.color === color && other.releaseOnScroll == releaseOnScroll

    override fun hashCode(): Int = 31 * System.identityHashCode(color) + releaseOnScroll.hashCode()
}

private class GlassLightEmitterNode(
    private var color: () -> Color,
    private var releaseOnScroll: Boolean,
) : DelegatingNode(),
    PointerInputModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var coordinates: LayoutCoordinates? = null
    private var activeLight: GlassLight? = null

    private val pointerNode = delegate(SuspendingPointerInputModifierNode { trackPointers() })

    fun update(color: () -> Color, releaseOnScroll: Boolean) {
        this.color = color
        if (this.releaseOnScroll != releaseOnScroll) {
            this.releaseOnScroll = releaseOnScroll
            pointerNode.resetPointerInputHandler()
        }
    }

    private fun toRoot(local: Offset): Offset {
        val c = coordinates ?: return local
        return if (c.isAttached) c.localToRoot(local) else local
    }

    private suspend fun PointerInputScope.trackPointers() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val light = currentValueOf(LocalGlassLight) ?: return@awaitEachGesture
            activeLight = light
            val emitter = this@GlassLightEmitterNode
            var activeId = down.id
            light.begin(emitter, toRoot(down.position), color())
            try {
                while (true) {
                    val event: PointerEvent = awaitPointerEvent(PointerEventPass.Final)
                    val newest = event.changes.lastOrNull { it.changedToDownIgnoreConsumed() }
                    if (newest != null && newest.id != activeId) {
                        activeId = newest.id
                        light.begin(emitter, toRoot(newest.position), color())
                        continue
                    }
                    var active = event.changes.firstOrNull { it.id == activeId }
                    if (active == null || !active.pressed) {
                        active = event.changes.lastOrNull { it.pressed } ?: break
                        activeId = active.id
                    }
                    if (releaseOnScroll && active.isConsumed &&
                        active.positionChangeIgnoreConsumed() != Offset.Zero
                    ) {
                        break
                    }
                    light.move(emitter, toRoot(active.position))
                }
            } finally {
                light.end(emitter)
            }
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) {
        pointerNode.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerNode.onCancelPointerInput()
    }

    override fun onDetach() {
        activeLight?.end(this)
        activeLight = null
        coordinates = null
    }
}

/**
 * RECEIVER helper for any `drawBackdrop` surface. Attach [modifier] right AFTER `drawBackdrop(...)`
 * in the chain and wrap the highlight with [highlight]. When the light is off every call costs one
 * state read and returns immediately.
 */
@Stable
class GlassLightReceiver(val light: GlassLight?) {

    private var coordinates: LayoutCoordinates? = null
    private var lastLocal: Offset = Offset.Zero
    private var lastDistance: Float = 0f

    /** Captures this surface's live layout coordinates. */
    val modifier: Modifier = Modifier.onPlaced { coordinates = it }

    /**
     * Current spill strength 0..1: `intensity * (1 - clamp(distanceToNearestEdge / reach))^2`.
     * Reads snapshot state, so call it inside draw / highlight / layer lambdas.
     */
    fun strength(): Float {
        val light = light ?: return 0f
        val intensity = light.intensity.value
        if (intensity <= 0.001f) return 0f
        val c = coordinates ?: return 0f
        if (!c.isAttached) return 0f
        val local = c.localPositionOf(c.findRootCoordinates(), light.position)
        val w = c.size.width.toFloat()
        val h = c.size.height.toFloat()
        val dx = max(max(-local.x, 0f), local.x - w)
        val dy = max(max(-local.y, 0f), local.y - h)
        val distance = sqrt(dx * dx + dy * dy)
        val reach = light.reachPx
        if (reach <= 0f || distance >= reach) return 0f
        lastLocal = local
        lastDistance = distance
        val falloff = 1f - distance / reach
        return intensity * falloff * falloff
    }

    /**
     * Returns [base] with its rim lit in the light's color, turned toward the light (the Default
     * highlight shader lights the rim along `(cos angle, sin angle)` in y-down coordinates, and
     * symmetrically on the opposite rim). Returns [base] untouched when the light is off.
     */
    fun highlight(base: Highlight?): Highlight? {
        val s = strength()
        if (s <= 0.002f) return base
        val light = light ?: return base
        val c = coordinates ?: return base
        val w = c.size.width.toFloat()
        val h = c.size.height.toFloat()
        val towardLight = atan2(lastLocal.y - h / 2f, lastLocal.x - w / 2f) * (180f / PI.toFloat())

        val b = base ?: DarkHighlight
        val style = b.style
        val baseAngle: Float
        val baseFalloff: Float
        if (style is HighlightStyle.Default) {
            baseAngle = style.angle
            baseFalloff = style.falloff
        } else {
            baseAngle = towardLight
            baseFalloff = 1f
        }
        // Kept subtle: a tinted rim turned toward the held element, not a glare.
        val k = s * SPILL_STRENGTH
        return Highlight(
            width = lerp(b.width, 1.25.dp, k),
            blurRadius = lerp(b.blurRadius, 2.5.dp, k),
            alpha = lerp(b.alpha, 0.8f, k),
            style = HighlightStyle.Default(
                color = lerp(
                    if (base == null) light.color.copy(alpha = 0f) else style.color,
                    light.color.copy(alpha = 0.55f),
                    k,
                ),
                angle = lerpAxisAngle(baseAngle, towardLight, s),
                falloff = lerp(baseFalloff, 1.2f, k),
            ),
        )
    }
}

/** 0..1 scale on how strongly spilled light tints neighboring rims (NexHome's shipped value). */
private const val SPILL_STRENGTH = 0.75f

private val DarkHighlight = Highlight(width = 0.dp, blurRadius = 0.dp, alpha = 0f)

/** Lerps between two highlight axis angles (degrees) along the shortest path; axes repeat every 180°. */
internal fun lerpAxisAngle(from: Float, to: Float, fraction: Float): Float {
    var delta = (to - from) % 180f
    if (delta > 90f) delta -= 180f
    if (delta < -90f) delta += 180f
    return from + delta * fraction
}

/** Remembers a [GlassLightReceiver] bound to [LocalGlassLight]. */
@Composable
fun rememberGlassLightReceiver(): GlassLightReceiver {
    val light = LocalGlassLight.current
    return remember(light) { GlassLightReceiver(light) }
}
