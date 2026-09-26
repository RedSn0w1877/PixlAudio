package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import android.os.Build
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The squash-and-stretch drag physics behind the sliding glass selector, ported from the
 * Backdrop catalog's `DampedDragAnimation` (Kyant0/AndroidLiquidGlass, Apache-2.0).
 *
 * Ported rather than depended on because it lives in that project's demo app, not in the
 * published `backdrop` artifact. Two Android-specific substitutions: `withFrameNanos` in place of
 * the multiplatform `awaitFrame` expect/actual, and `System.nanoTime` in place of
 * `kotlin.time.Clock` so this needs no kotlinx-datetime dependency.
 *
 * Five springs run at once, which is the whole point — the value, its velocity, the press
 * progress and the two axis scales all settle independently, so the pill leads with a stretch,
 * overshoots, and relaxes instead of sliding linearly.
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit
) {

    private val valueAnimationSpec = spring(0.85f, 350f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            // Hold the pressed state until the value is nearly settled, so the pill doesn't
            // shrink back to rest while it is still visibly flying toward the target.
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.nanoTime() / 1_000_000L, Offset(value, 0f))
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/**
 * Drag detection without a slop threshold — the selector has to start moving on the very first
 * pixel, or the pill visibly lags the finger at the start of every drag.
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent = drag(
            pointerId = drag.id,
            onDrag = { onDrag(it, it.positionChange()) }
        )
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) return dragEvent else pointer = otherDown.id
        } else {
            if (dragEvent.previousPosition != dragEvent.position) return dragEvent
        }
    }
}

/**
 * The press light on a glass control: a faint additive wash plus a soft radial spot that follows
 * the finger. Ported from the Backdrop catalog's `InteractiveHighlight` (Kyant0/AndroidLiquidGlass,
 * Apache-2.0).
 *
 * - [modifier] draws the light. Put it *after* `drawBackdrop` so it lands inside the glass's
 *   clipped layer. It reads the animation in the draw phase only.
 * - [gestureModifier] tracks the finger (no slop, doesn't consume, so a `clickable` alongside it
 *   still gets the tap).
 *
 * On API 33+ the spot is an AGSL radial falloff (`smoothstep(r, r/2, d)`, `r = 1.5·minDimension`)
 * at `0.15·p`, over a `0.08·p` wash, both with [BlendMode.Plus]. Below 33 there are no runtime
 * shaders, so it falls back to a flat `0.25·p` wash. The shader is created lazily on the first
 * press, so a screen full of buttons that are never touched compiles nothing.
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    // While the finger is down the light follows it through these plain states, written straight
    // from the pointer handler (no coroutine or animation per move event). The Animatable only
    // runs the settle back to the start on release.
    private var trackingFinger by mutableStateOf(false)
    private var fingerX by mutableFloatStateOf(0f)
    private var fingerY by mutableFloatStateOf(0f)
    private var settleJob: Job? = null

    private val lightPosition: Offset
        get() = if (trackingFinger) Offset(fingerX, fingerY) else positionAnimation.value

    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = lightPosition - startPosition

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) drawLight(progress)
        drawContent()
    }

    private fun DrawScope.drawLight(progress: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
            val spot = SpotLight.obtain()
            if (spot != null) {
                val shader = spot.shader
                val brush = spot.brush
                val p = position(size, lightPosition)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setColorUniform(
                    "color",
                    android.graphics.Color.argb(0.15f * progress, 1f, 1f, 1f)
                )
                shader.setFloatUniform("radius", size.minDimension * 1.5f)
                shader.setFloatUniform(
                    "position",
                    p.x.fastCoerceIn(0f, size.width),
                    p.y.fastCoerceIn(0f, size.height)
                )
                drawRect(brush, blendMode = BlendMode.Plus)
            }
        } else {
            drawRect(Color.White.copy(alpha = 0.25f * progress), blendMode = BlendMode.Plus)
        }
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                settleJob?.cancel()
                settleJob = null
                startPosition = down.position
                fingerX = down.position.x
                fingerY = down.position.y
                trackingFinger = true
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                }
            },
            onDragEnd = { settle() },
            onDragCancel = { settle() }
        ) { change, _ ->
            fingerX = change.position.x
            fingerY = change.position.y
        }
    }

    private fun settle() {
        settleJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch {
                // Hand the finger's last position to the Animatable, then spring home from it.
                positionAnimation.snapTo(Offset(fingerX, fingerY))
                trackingFinger = false
                positionAnimation.animateTo(startPosition, positionAnimationSpec)
            }
        }
    }
}

/**
 * The press-light spot shader, compiled once per process on the first press of any glass control
 * rather than once per control. Sharing one instance is safe: every draw sets all four uniforms
 * right before drawing, and a recorded draw keeps the shader state it was recorded with. Main
 * thread only, like the draws that use it.
 */
private class SpotLight private constructor(
    val shader: android.graphics.RuntimeShader,
    val brush: ShaderBrush
) {
    companion object {
        private var instance: SpotLight? = null
        private var failed = false

        fun obtain(): SpotLight? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            instance?.let { return it }
            if (failed) return null
            return runCatching {
                val shader = android.graphics.RuntimeShader(SpotShaderSource)
                SpotLight(shader, ShaderBrush(shader)).also { instance = it }
            }.onFailure { failed = true }.getOrNull()
        }

        const val SpotShaderSource = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
    }
}

/**
 * The catalog's press/drag transform for a glass control, as a `layerBlock`: grow by 4dp on the
 * short side while pressed, lean toward the finger along a `tanh` curve, and stretch a little
 * along the drag. Under Reduce Motion it only grows by 2dp — no lean, no stretch.
 */
fun GraphicsLayerScope.applyGlassPress(
    highlight: InteractiveHighlight,
    reduceMotion: Boolean
) {
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    val progress = highlight.pressProgress
    if (reduceMotion) {
        val scale = lerp(1f, 1f + 2.dp.toPx() / h, progress)
        scaleX = scale
        scaleY = scale
        return
    }
    val scale = lerp(1f, 1f + 4.dp.toPx() / h, progress)
    val maxOffset = size.minDimension.coerceAtLeast(1f)
    val offset = highlight.offset
    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
    val maxDragScale = 4.dp.toPx() / h
    val offsetAngle = atan2(offset.y, offset.x)
    val maxDimension = size.maxDimension.coerceAtLeast(1f)
    scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / maxDimension) *
        (w / h).fastCoerceAtMost(1f)
    scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / maxDimension) *
        (h / w).fastCoerceAtMost(1f)
}

/**
 * The squash-and-stretch the catalog applies to a dragged thumb/pill: [DampedDragAnimation.scaleX]
 * and [scaleY][DampedDragAnimation.scaleY], narrowed along the direction of travel by velocity.
 * Under Reduce Motion the scale springs stay but the velocity squash is dropped.
 */
fun GraphicsLayerScope.applyGlassSquash(
    animation: DampedDragAnimation,
    velocityDivisor: Float,
    reduceMotion: Boolean
) {
    scaleX = animation.scaleX
    scaleY = animation.scaleY
    if (reduceMotion) return
    val velocity = animation.velocity / velocityDivisor
    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
}
