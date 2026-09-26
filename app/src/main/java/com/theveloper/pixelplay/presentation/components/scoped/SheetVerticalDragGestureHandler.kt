package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Encapsulates vertical drag gesture state and target resolution for the player sheet.
 * Behavior is kept identical to the previous inline implementation.
 */
internal class SheetVerticalDragGestureHandler(
    private val scope: CoroutineScope,
    private val velocityTracker: VelocityTracker,
    private val densityProvider: () -> Density,
    private val sheetMotionController: SheetMotionController,
    private val playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    private val currentSheetTranslationY: Animatable<Float, AnimationVector1D>,
    private val expandedYProvider: () -> Float,
    private val collapsedYProvider: () -> Float,
    private val miniHeightPxProvider: () -> Float,
    private val currentSheetStateProvider: () -> PlayerSheetState,
    private val visualOvershootScaleY: Animatable<Float, AnimationVector1D>,
    private val onDraggingChange: (Boolean) -> Unit,
    private val onDraggingPlayerAreaChange: (Boolean) -> Unit,
    private val onAnimateSheet: suspend (
        targetExpanded: Boolean,
        animationSpec: AnimationSpec<Float>?,
        initialVelocity: Float
    ) -> Unit,
    private val onExpandSheetState: () -> Unit,
    private val onCollapseSheetState: () -> Unit
) {
    private var initialFractionOnDragStart = 0f
    private var initialYOnDragStart = 0f
    private var accumulatedDragYSinceStart = 0f
    private var dragSnapJob: Job? = null

    // Drag deltas arrive at the touch rate (120-240 Hz). Each one only updates these fields; a
    // single loop per drag applies the latest target once per frame (the queue sheet's
    // pattern), instead of a coroutine + mutex + two Animatable snaps per touch event.
    // [dragTranslationY] tracks the target as the deltas accumulate, since the Animatable
    // itself only catches up once per frame.
    private var dragTranslationY = Float.NaN
    private var pendingTranslationY = 0f
    private var pendingExpansionFraction = 0f
    private var hasPendingSnap = false

    fun onDragStart() {
        dragSnapJob?.cancel()
        dragSnapJob = null
        hasPendingSnap = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sheetMotionController.stop()
        }
        onDraggingChange(true)
        onDraggingPlayerAreaChange(true)
        velocityTracker.resetTracking()
        initialFractionOnDragStart = playerContentExpansionFraction.value
        initialYOnDragStart = currentSheetTranslationY.value
        dragTranslationY = Float.NaN
        accumulatedDragYSinceStart = 0f
    }

    fun onVerticalDrag(
        uptimeMillis: Long,
        position: Offset,
        dragAmount: Float
    ) {
        accumulatedDragYSinceStart += dragAmount
        val expandedY = expandedYProvider()
        val collapsedY = collapsedYProvider()
        val baseTranslationY =
            if (dragTranslationY.isNaN()) currentSheetTranslationY.value else dragTranslationY
        val newY = sheetDragTranslationY(
            currentTranslationY = baseTranslationY,
            dragAmount = dragAmount,
            expandedY = expandedY,
            collapsedY = collapsedY,
            miniHeightPx = miniHeightPxProvider()
        )
        dragTranslationY = newY
        pendingTranslationY = newY
        pendingExpansionFraction = sheetDragExpansionFraction(
            newY = newY,
            expandedY = expandedY,
            collapsedY = collapsedY,
            initialFractionOnDragStart = initialFractionOnDragStart,
            initialYOnDragStart = initialYOnDragStart
        )
        hasPendingSnap = true
        launchDragSnapLoopIfNeeded()
        velocityTracker.addPosition(uptimeMillis, position)
    }

    private fun launchDragSnapLoopIfNeeded() {
        if (dragSnapJob?.isActive == true) return
        // UNDISPATCHED: the first delta of a drag lands on the very next frame.
        dragSnapJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (hasPendingSnap) {
                hasPendingSnap = false
                sheetMotionController.snapTo(
                    translationYValue = pendingTranslationY,
                    expansionFractionValue = pendingExpansionFraction
                )
                if (coroutineContext[MonotonicFrameClock] != null) {
                    withFrameNanos { }
                } else {
                    yield()
                }
            }
        }
    }

    fun onDragEnd() {
        dragSnapJob?.cancel()
        dragSnapJob = null
        if (hasPendingSnap) {
            // Land the last delta before deciding and animating from it.
            hasPendingSnap = false
            val lastY = pendingTranslationY
            val lastFraction = pendingExpansionFraction
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sheetMotionController.snapTo(
                    translationYValue = lastY,
                    expansionFractionValue = lastFraction
                )
            }
        }
        dragTranslationY = Float.NaN
        onDraggingChange(false)
        onDraggingPlayerAreaChange(false)

        val verticalVelocity = velocityTracker.calculateVelocity().y
        val currentFraction = playerContentExpansionFraction.value
        val minDragThresholdPx = with(densityProvider()) { 5.dp.toPx() }
        val velocityThreshold = 150f

        val targetState = resolveVerticalSheetTargetState(
            currentSheetContentState = currentSheetStateProvider(),
            accumulatedDragY = accumulatedDragYSinceStart,
            minDragThresholdPx = minDragThresholdPx,
            verticalVelocity = verticalVelocity,
            velocityThreshold = velocityThreshold,
            currentFraction = currentFraction
        )

        scope.launch {
            if (targetState == PlayerSheetState.EXPANDED) {
                launch {
                    onAnimateSheet(true, null, 0f)
                }
                onExpandSheetState()
            } else {
                val dynamicDamping = collapseSpringDampingForFraction(currentFraction)
                launch {
                    val initialSquash = collapseInitialSquashForFraction(currentFraction)
                    visualOvershootScaleY.snapTo(initialSquash)
                    visualOvershootScaleY.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessVeryLow
                        )
                    )
                }
                launch {
                    onAnimateSheet(
                        false,
                        spring(
                            dampingRatio = dynamicDamping,
                            stiffness = Spring.StiffnessLow
                        ),
                        verticalVelocity
                    )
                }
                onCollapseSheetState()
            }
        }

        accumulatedDragYSinceStart = 0f
    }

    fun onDragCancel() {
        onDragEnd()
    }
}

internal fun Modifier.playerSheetVerticalDragGesture(
    enabled: Boolean,
    handler: SheetVerticalDragGestureHandler
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled, handler) {
        detectVerticalDragGestures(
            onDragStart = { handler.onDragStart() },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                handler.onVerticalDrag(
                    uptimeMillis = change.uptimeMillis,
                    position = change.position,
                    dragAmount = dragAmount
                )
            },
            onDragEnd = { handler.onDragEnd() },
            onDragCancel = { handler.onDragCancel() }
        )
    }
}
