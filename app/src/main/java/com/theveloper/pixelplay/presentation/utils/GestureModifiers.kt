package com.theveloper.pixelplay.presentation.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Decides whether a touch is a sideways swipe. Kept pure so the rule that keeps gestures from
 * fighting scrolling is unit-tested: a gesture only counts as horizontal when the sideways travel
 * clearly dominates, and as soon as it is clearly vertical it is left to the scroll container.
 */
object SwipeIntent {
    fun isHorizontal(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dx) > slop && abs(dx) > 2f * abs(dy)

    fun isVertical(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dy) > slop && abs(dy) > abs(dx)

    /** +1 for a swipe to the right past [threshold], -1 to the left, 0 when it was too short. */
    fun commitDirection(dx: Float, threshold: Float): Int = when {
        dx >= threshold -> 1
        dx <= -threshold -> -1
        else -> 0
    }
}

/**
 * Horizontal swipe with intent detection. Observes in the normal pass and only starts consuming
 * once the movement is clearly sideways, so vertical scrolling, taps, long-presses and the sheet's
 * vertical drag keep working untouched. [onDrag] reports the running sideways distance.
 */
fun Modifier.horizontalIntentSwipe(
    enabled: Boolean = true,
    threshold: Dp = 64.dp,
    onDrag: (dx: Float) -> Unit = {},
    onCancel: () -> Unit = {},
    onCommit: (direction: Int) -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(threshold) {
        val slop = viewConfiguration.touchSlop
        val thresholdPx = threshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var active = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.isConsumed && !active) return@awaitEachGesture
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!active) {
                    if (SwipeIntent.isVertical(dx, dy, slop)) return@awaitEachGesture
                    if (SwipeIntent.isHorizontal(dx, dy, slop)) {
                        active = true
                    } else if (abs(dx) + abs(dy) > slop * 6f) {
                        return@awaitEachGesture
                    }
                }
                if (active) {
                    change.consume()
                    onDrag(dx)
                }
            }
            if (active) {
                val direction = SwipeIntent.commitDirection(dx, thresholdPx)
                if (direction != 0) onCommit(direction) else onCancel()
            }
        }
    }
}

/**
 * Swallows sideways drags before the child sees them (used to turn the artwork carousel's swipe
 * off). Taps, long-presses and vertical drags are untouched.
 */
fun Modifier.blockHorizontalDrags(enabled: Boolean): Modifier {
    if (!enabled) return this
    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var blocking = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!blocking && SwipeIntent.isHorizontal(dx, dy, slop)) blocking = true
                if (blocking) change.consume()
            }
        }
    }
}

/** A heart that pops over the artwork each time [trigger] changes (double-tap like). */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val p = progress.value
                    // Quick pop up to 1.15x, then settle and fade; invisible at rest.
                    val scale = if (p < 0.3f) 0.4f + (p / 0.3f) * 0.75f else 1.15f - (p - 0.3f) * 0.2f
                    scaleX = scale
                    scaleY = scale
                    alpha = if (p >= 1f || p <= 0f) 0f else if (p < 0.6f) 1f else 1f - (p - 0.6f) / 0.4f
                }
        )
    }
}
