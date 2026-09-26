package com.theveloper.pixelplay.ui.glass.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.motion.applyVelocitySquash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Ported from the Backdrop library's own catalog app (utils/DampedDragAnimation.kt, Apache-2.0) via
 * NexHome — the physics behind every "draggable blob" (toggle thumb, slider thumb, bottom-tab
 * indicator, segmented blob): a value with a springy drag-follow, a press-scale, and
 * velocity-reactive squash. Tuned with [LiquidMotion]: the value overshoots when it snaps, the press
 * scale wobbles, and [pressedScale] is boosted by [LiquidMotion.BlobPressedScaleBoost].
 *
 * PixlAudio change: drag updates are coalesced. The catalog launched one coroutine per pointer
 * event for the value and another per animation frame for the velocity; here a burst of events
 * inside one frame only retargets the pending launch, so at most one value launch and one velocity
 * launch run per frame.
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
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    /** The scale actually reached while pressed (the requested delta boosted for more drama). */
    val effectivePressedScale: Float =
        initialScale + (pressedScale - initialScale) * LiquidMotion.BlobPressedScaleBoost

    private val valueAnimationSpec =
        spring(LiquidMotion.BlobValueDamping, LiquidMotion.BlobValueStiffness, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = LiquidMotion.BlobPressSpring
    private val scaleXAnimationSpec = LiquidMotion.BlobScaleXSpring
    private val scaleYAnimationSpec = LiquidMotion.BlobScaleYSpring

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    // Coalescing state (main thread only). pendingValueTarget is always the latest request.
    private var pendingValueTarget = initialValue.coerceIn(valueRange)
    private var valueLaunchPending = false
    private var pendingVelocityTarget = 0f
    private var velocityLaunchPending = false

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    /** The latest requested value (what the blob is heading to, even before its launch has run). */
    val targetValue: Float get() = pendingValueTarget
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /**
     * True while [onDragStopped] runs for a gesture that was cancelled (another handler — a
     * scrolling list — consumed it) rather than released. Controls inside scrolling pages use it to
     * restore their value instead of committing a tap or a partial drag.
     */
    var wasCancelled: Boolean = false
        private set

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                wasCancelled = false
                onDragStopped()
                release()
            },
            onDragCancel = {
                wasCancelled = true
                onDragStopped()
                wasCancelled = false
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
            launch { scaleXAnimation.animateTo(effectivePressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(effectivePressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
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
        pendingValueTarget = value.coerceIn(valueRange)
        if (valueLaunchPending) return
        valueLaunchPending = true
        animationScope.launch {
            valueLaunchPending = false
            valueAnimation.animateTo(pendingValueTarget, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                valueLaunchPending = false
                pendingValueTarget = targetValue
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        pendingVelocityTarget =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        if (velocityLaunchPending) return
        velocityLaunchPending = true
        animationScope.launch {
            velocityLaunchPending = false
            velocityAnimation.animateTo(pendingVelocityTarget, velocityAnimationSpec)
        }
    }
}

/**
 * Sets this layer's scale from [animation]'s press wobble plus the kit's velocity squash.
 * [velocityDivisor] normalizes velocity (catalog: 10 for slider/tabs, 50 for toggle).
 */
fun GraphicsLayerScope.applyBlobTransform(animation: DampedDragAnimation, velocityDivisor: Float = 10f) {
    scaleX = animation.scaleX
    scaleY = animation.scaleY
    applyVelocitySquash(animation.velocity / velocityDivisor)
}
