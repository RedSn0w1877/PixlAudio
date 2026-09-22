package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.viewmodel.StemUi
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The stage: listener in the middle, one draggable puck per stem, seen from above.
 *
 * Coordinates handed out are −1..1 on both axes with the listener at the origin and −y meaning
 * "in front of you" — the same frame the ViewModel converts to metres. Pucks are clamped to the
 * unit circle rather than the square so the far corners, which would be the loudest and most
 * distant positions, simply don't exist.
 */
@Composable
fun SpatialStage(
    stems: List<StemUi>,
    peaks: FloatArray,
    listenerYaw: Float,
    onStemMoved: (index: Int, x: Float, y: Float) -> Unit,
    onStemTapped: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val sizePx = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val radiusPx = sizePx / 2f
        val puckSize = 62.dp
        val puckRadiusPx = with(density) { puckSize.toPx() } / 2f
        // Keep a dragged puck fully inside the ring rather than half over the edge.
        val travel = (radiusPx - puckRadiusPx).coerceAtLeast(1f)

        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ring = minOf(size.width, size.height) / 2f

            for (fraction in listOf(0.33f, 0.66f, 1f)) {
                drawCircle(
                    color = colors.outlineVariant.copy(alpha = if (fraction == 1f) 0.8f else 0.35f),
                    radius = ring * fraction,
                    center = centre,
                    style = Stroke(width = if (fraction == 1f) 2.5f else 1.5f),
                )
            }
            // Cross-hairs: without them "in front" and "behind" read as the same place.
            drawLine(
                color = colors.outlineVariant.copy(alpha = 0.25f),
                start = Offset(centre.x, centre.y - ring),
                end = Offset(centre.x, centre.y + ring),
                strokeWidth = 1.5f,
            )
            drawLine(
                color = colors.outlineVariant.copy(alpha = 0.25f),
                start = Offset(centre.x - ring, centre.y),
                end = Offset(centre.x + ring, centre.y),
                strokeWidth = 1.5f,
            )

            // Which way the listener is facing.
            val headingLength = ring * 0.42f
            val headingEnd = Offset(
                centre.x + kotlin.math.sin(listenerYaw) * headingLength,
                centre.y - kotlin.math.cos(listenerYaw) * headingLength,
            )
            drawLine(
                color = colors.primary.copy(alpha = 0.7f),
                start = centre,
                end = headingEnd,
                strokeWidth = 6f,
            )
            drawCircle(color = colors.primary, radius = 9f, center = centre)
            drawCircle(
                color = colors.primary.copy(alpha = 0.18f),
                radius = ring * 0.14f,
                center = centre,
            )
        }

        stems.forEach { stem ->
            StemPuck(
                stem = stem,
                peak = peaks.getOrElse(stem.index) { 0f },
                travelPx = travel,
                puckSize = puckSize,
                onMoved = onStemMoved,
                onTapped = onStemTapped,
            )
        }
    }
}

@Composable
private fun StemPuck(
    stem: StemUi,
    peak: Float,
    travelPx: Float,
    puckSize: androidx.compose.ui.unit.Dp,
    onMoved: (index: Int, x: Float, y: Float) -> Unit,
    onTapped: (index: Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    // Snap while a finger is on it — a spring here would lag the finger — and spring when
    // something else moves it, which is what makes an applied preset feel alive.
    val animatedX by animateFloatAsState(
        targetValue = stem.x,
        animationSpec = if (dragging) snap() else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "puckX",
    )
    val animatedY by animateFloatAsState(
        targetValue = stem.y,
        animationSpec = if (dragging) snap() else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "puckY",
    )
    val meterScale by animateFloatAsState(
        targetValue = 1f + (peak.coerceIn(0f, 1f) * 0.12f),
        label = "puckMeter",
    )

    val colors = MaterialTheme.colorScheme
    val container = when (stem.index % 4) {
        0 -> colors.primaryContainer
        1 -> colors.secondaryContainer
        2 -> colors.tertiaryContainer
        else -> colors.surfaceContainerHighest
    }
    val content = when (stem.index % 4) {
        0 -> colors.onPrimaryContainer
        1 -> colors.onSecondaryContainer
        2 -> colors.onTertiaryContainer
        else -> colors.onSurface
    }

    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (animatedX * travelPx).roundToInt(),
                    (animatedY * travelPx).roundToInt(),
                )
            }
            .size(puckSize)
            .scale(if (stem.muted) 0.88f else meterScale)
            .pointerInput(stem.index, travelPx) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    val nextX = stem.x + drag.x / travelPx
                    val nextY = stem.y + drag.y / travelPx
                    val (clampedX, clampedY) = clampToUnitCircle(nextX, nextY)
                    onMoved(stem.index, clampedX, clampedY)
                }
            }
            // Separate pointerInput so a drag can never be mistaken for a tap (and mute a stem).
            .pointerInput(stem.index) {
                detectTapGestures(onTap = { onTapped(stem.index) })
            },
        shape = CircleShape,
        color = if (stem.muted) colors.surfaceContainer else container,
        contentColor = content,
        tonalElevation = if (dragging) 8.dp else 3.dp,
        shadowElevation = if (dragging) 10.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stem.kind.shortLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (stem.muted) colors.onSurfaceVariant else content,
            )
        }
    }
}

private fun clampToUnitCircle(x: Float, y: Float): Pair<Float, Float> {
    val length = sqrt(x * x + y * y)
    if (length <= 1f) return x to y
    return (x / length) to (y / length)
}

private fun String.shortLabel(): String = when (this) {
    "vocals" -> "Voice"
    "drums" -> "Drums"
    "bass" -> "Bass"
    "other" -> "Music"
    "instrumental" -> "Inst"
    "center" -> "Centre"
    "sides" -> "Sides"
    else -> replaceFirstChar { it.uppercase() }
}

/** Heading in radians for a touch at [offset] relative to a stage of the given [radius]. */
internal fun headingFor(offset: Offset, radius: Float): Float =
    atan2(offset.x - radius, -(offset.y - radius))
