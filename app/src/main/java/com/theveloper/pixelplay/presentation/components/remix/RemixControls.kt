package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.ui.theme.ShapeCache
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * The control vocabulary for Remix Studio.
 *
 * One rule runs through the whole file: **a control that cannot be unlabelled.** [RoomRail]
 * requires a name and a spoken value in its signature, so there is no way to write the bug the
 * old screen shipped — two identical bare slider tracks stacked on top of each other, one of
 * which muffled the sound and the other of which made it scream, with nothing on screen saying
 * which was which.
 *
 * Nothing here is a stock Material control dressed up. Everything is drawn, because the screen
 * has to look like itself, and everything reports its value, because it has to be usable.
 */

// ─────────────────────────────────────────────────────────────── the rail

/**
 * The replacement for every `Slider` on the screen.
 *
 * [fraction] is a lambda, not a value: the track reads it inside the draw lambda, so dragging
 * repaints one node instead of recomposing the sheet.
 *
 * @param detent optional position the knob snaps to — Speed uses it so 1.00× is reachable by hand.
 */
@Composable
fun RoomRail(
    label: String,
    valueText: String,
    fraction: () -> Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detent: Float? = null,
    onFractionCommitted: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    var fine by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(
        targetValue = if (fine) 18.dp else 12.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "railTrack",
    )
    val alpha = if (enabled) 1f else 0.45f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeCache.smooth20)
            .background(colors.surfaceContainerHigh)
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                if (enabled) setProgress { target -> onFractionChange(target); true }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (fine) "$label · fine" else label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface.copy(alpha = alpha),
            )
            Box(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary.copy(alpha = alpha),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(
                    if (!enabled) Modifier else Modifier.pointerInput(detent) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // A horizontal control inside a vertically draggable sheet has to
                            // claim the gesture or the sheet steals it after a few pixels.
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            down.consume()

                            val padPx = 16.dp.toPx()
                            val usable = (size.width - 2 * padPx).coerceAtLeast(1f)
                            var value = fraction()
                            var lastHapticStep = (value * HAPTIC_STEPS).roundToInt()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break

                                // Straying vertically gears the rail down instead of cancelling
                                // it, which is what makes a 0.3 s reverb tail settable at all.
                                val strayed = abs(change.position.y - down.position.y) > 44.dp.toPx()
                                if (strayed != fine) fine = strayed
                                val gear = if (strayed) 0.22f else 1f

                                val delta = change.positionChange().x / usable * gear
                                value = (value + delta).coerceIn(0f, 1f)
                                var applied = value
                                if (detent != null && abs(applied - detent) < DETENT_GRAB) {
                                    applied = detent
                                }
                                change.consume()
                                onFractionChange(applied)

                                val step = (applied * HAPTIC_STEPS).roundToInt()
                                if (step != lastHapticStep) {
                                    lastHapticStep = step
                                    performAppCompatHapticFeedback(
                                        view, haptics,
                                        HapticFeedbackConstantsCompat.SEGMENT_TICK,
                                    )
                                }
                            }
                            fine = false
                            onFractionCommitted?.invoke()
                        }
                    }
                )
                .drawWithCache {
                    val padPx = 16.dp.toPx()
                    val knobW = 6.dp.toPx()
                    val fillBrush = Brush.horizontalGradient(
                        listOf(colors.primary, colors.tertiary),
                    )
                    val base = colors.onSurface.copy(alpha = 0.10f * alpha)
                    val knobColor = colors.surfaceContainerHigh
                    val detentColor = colors.onSurface.copy(alpha = 0.32f * alpha)
                    onDrawBehind {
                        val trackH = trackHeight.toPx()
                        val top = size.height / 2f - trackH / 2f
                        val full = (size.width - 2 * padPx).coerceAtLeast(1f)
                        val w = full * fraction().coerceIn(0f, 1f)

                        drawRoundRect(
                            color = base,
                            topLeft = Offset(padPx, top),
                            size = Size(full, trackH),
                            cornerRadius = CornerRadius(trackH / 2f),
                        )
                        drawRoundRect(
                            brush = fillBrush,
                            topLeft = Offset(padPx, top),
                            size = Size(w.coerceAtLeast(trackH), trackH),
                            cornerRadius = CornerRadius(trackH / 2f),
                            alpha = alpha,
                        )
                        if (detent != null) {
                            val dx = padPx + full * detent
                            drawLine(
                                color = detentColor,
                                start = Offset(dx, top - 3.dp.toPx()),
                                end = Offset(dx, top + trackH + 3.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                        // The knob is the card's own colour, so it reads as a notch cut out of
                        // the fill rather than a thumb bolted on top of it.
                        drawRoundRect(
                            color = knobColor,
                            topLeft = Offset(padPx + w - knobW / 2f, top - 5.dp.toPx()),
                            size = Size(knobW, trackH + 10.dp.toPx()),
                            cornerRadius = CornerRadius(knobW / 2f),
                        )
                    }
                },
        )
    }
}

// ─────────────────────────────────────────────────────────────── the pad

/**
 * Replaces the two identical unlabelled sliders with one control that shows what it does.
 *
 * X is brightness, Y is edge, and the filter's actual response curve is drawn inside it — so the
 * control is its own explanation and nobody has to know the word "resonance". The four edges are
 * captioned Dull / Bright / Smooth / Sharp.
 */
@Composable
fun TonePad(
    cutoffHz: Float,
    resonance: Float,
    mode: String,
    onChange: (cutoffHz: Float, resonance: Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val curve = remember { Path() }
    val alpha = if (enabled) 1f else 0.45f

    val x = cutoffToFraction(cutoffHz)
    val y = ((resonance - MIN_Q) / (MAX_Q - MIN_Q)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(ShapeCache.smooth20)
            .background(colors.surfaceContainerHigh)
            .semantics {
                contentDescription = "Tone"
                stateDescription = toneDescription(cutoffHz, resonance)
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(mode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        down.consume()
                        var lastX = -1
                        var lastY = -1

                        fun apply(px: Float, py: Float) {
                            val fx = (px / size.width).coerceIn(0f, 1f)
                            // Screen y grows downward; "sharp" is up.
                            val fy = (1f - py / size.height).coerceIn(0f, 1f)
                            onChange(fractionToCutoff(fx), MIN_Q + fy * (MAX_Q - MIN_Q))
                            val sx = (fx * 24).roundToInt()
                            val sy = (fy * 24).roundToInt()
                            if (sx != lastX || sy != lastY) {
                                lastX = sx; lastY = sy
                                performAppCompatHapticFeedback(
                                    view, haptics, HapticFeedbackConstantsCompat.SEGMENT_TICK,
                                )
                            }
                        }
                        // Absolute, not relative: tapping anywhere places the marker there.
                        apply(down.position.x, down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            apply(change.position.x, change.position.y)
                        }
                    }
                }
            )
            .drawWithCache {
                val grid = colors.onSurface.copy(alpha = 0.06f * alpha)
                val curveColor = colors.primary.copy(alpha = alpha)
                val fill = Brush.verticalGradient(
                    listOf(colors.primary.copy(alpha = 0.26f * alpha), Color.Transparent),
                )
                val marker = colors.tertiary.copy(alpha = alpha)
                val markerRing = colors.onTertiary.copy(alpha = 0.6f * alpha)
                val hair = 1.dp.toPx()

                onDrawBehind {
                    for (i in 1..3) {
                        val gx = size.width * i / 4f
                        drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), hair)
                    }
                    for (i in 1..2) {
                        val gy = size.height * i / 3f
                        drawLine(grid, Offset(0f, gy), Offset(size.width, gy), hair)
                    }

                    // The response curve, sampled across the width. Rewound, never rebuilt.
                    curve.rewind()
                    val q = resonance.coerceAtLeast(0.05f)
                    var first = true
                    for (i in 0..CURVE_SAMPLES) {
                        val f = i.toFloat() / CURVE_SAMPLES
                        val freq = fractionToCutoff(f)
                        val w = freq / cutoffHz.coerceAtLeast(20f)
                        val den = sqrt((1f - w * w).pow(2) + (w / q).pow(2)).coerceAtLeast(1e-5f)
                        val mag = when (mode) {
                            "hp" -> w * w / den
                            "bp" -> (w / q) / den
                            else -> 1f / den
                        }
                        val db = (20f * log10(mag.coerceAtLeast(1e-5f))).coerceIn(-24f, 18f)
                        val py = size.height * (1f - (db + 24f) / 42f)
                        val px = size.width * f
                        if (first) { curve.moveTo(px, py); first = false } else curve.lineTo(px, py)
                    }
                    drawPath(curve, curveColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    // Close a copy under the curve for the fill.
                    curve.lineTo(size.width, size.height)
                    curve.lineTo(0f, size.height)
                    curve.close()
                    drawPath(curve, fill)

                    val mx = size.width * x
                    val my = size.height * (1f - y)
                    drawLine(marker.copy(alpha = 0.25f * alpha), Offset(0f, my), Offset(size.width, my), hair)
                    drawLine(marker.copy(alpha = 0.25f * alpha), Offset(mx, 0f), Offset(mx, size.height), hair)
                    drawCircle(marker, radius = 8.dp.toPx(), center = Offset(mx, my))
                    drawCircle(
                        markerRing, radius = 8.dp.toPx(), center = Offset(mx, my),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            },
    ) {
        val edge = MaterialTheme.typography.labelSmall
        val edgeColor = colors.onSurfaceVariant.copy(alpha = 0.7f * alpha)
        Text("Dull", style = edge, color = edgeColor, modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        Text("Bright", style = edge, color = edgeColor, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
        Text("Sharp", style = edge, color = edgeColor, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Text("Smooth", style = edge, color = edgeColor, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

// ─────────────────────────────────────────────────────────── the segments

/**
 * Replaces radio-group `FilterChip`s.
 *
 * Selection is carried by three channels at once — fill, corner radius and width — so it never
 * depends on colour alone, and the selected segment physically shoves its neighbours aside.
 *
 * Actions never appear here. "Face forward", "Split it" and "Save key" are buttons; a chip-shaped
 * thing sitting inside a row of radio options was half the screen's confusion.
 */
@Composable
fun RoomSegments(
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(ShapeCache.smoothPill)
            .background(colors.onSurface.copy(alpha = 0.07f * alpha))
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEach { (id, label) ->
            val selected = id == selectedId
            val weight by animateFloatAsState(
                targetValue = if (selected) 1.25f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "segWeight",
            )
            val corner by animateDpAsState(
                targetValue = if (selected) 50.dp else 14.dp,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "segCorner",
            )
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxSize()
                    .clip(AbsoluteSmoothCornerShape(corner, 60))
                    .then(
                        if (selected) {
                            Modifier.background(
                                Brush.horizontalGradient(listOf(colors.primary, colors.tertiary)),
                            )
                        } else Modifier
                    )
                    .then(
                        if (!enabled) Modifier else Modifier.pointerInput(id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = awaitPointerEvent().changes.firstOrNull()
                                if (up != null && !selected) {
                                    performAppCompatHapticFeedback(
                                        view, haptics,
                                        HapticFeedbackConstantsCompat.CONFIRM,
                                    )
                                    onSelected(id)
                                }
                            }
                        }
                    )
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = label
                        stateDescription = if (selected) "Selected" else "Not selected"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        colors.onPrimary.copy(alpha = alpha)
                    } else {
                        colors.onSurfaceVariant.copy(alpha = alpha)
                    },
                )
            }
        }
    }
}

/** An independent on/off, distinct from a radio segment by shape as well as colour. */
@Composable
fun RoomToggle(
    label: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val corner by animateDpAsState(
        targetValue = if (checked) 22.dp else 12.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "toggleCorner",
    )
    val alpha = if (enabled) 1f else 0.45f

    Column(
        modifier = modifier
            .clip(AbsoluteSmoothCornerShape(corner, 60))
            .background(
                if (checked) colors.tertiaryContainer.copy(alpha = alpha)
                else colors.onSurface.copy(alpha = 0.07f * alpha)
            )
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(checked) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = awaitPointerEvent().changes.firstOrNull()
                        if (up != null) {
                            performAppCompatHapticFeedback(
                                view, haptics, HapticFeedbackConstantsCompat.TOGGLE_ON,
                            )
                            onCheckedChange(!checked)
                        }
                    }
                }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics {
                role = Role.Switch
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (checked) {
                colors.onTertiaryContainer.copy(alpha = alpha)
            } else {
                colors.onSurfaceVariant.copy(alpha = alpha)
            },
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) {
                colors.onTertiaryContainer.copy(alpha = 0.75f * alpha)
            } else {
                colors.onSurfaceVariant.copy(alpha = 0.7f * alpha)
            },
        )
    }
}

// ───────────────────────────────────────────────────────────── conversions

private const val MIN_CUTOFF = 200f
private const val MAX_CUTOFF = 18_000f
private const val MIN_Q = 0.3f
private const val MAX_Q = 12f
private const val CURVE_SAMPLES = 48
private const val HAPTIC_STEPS = 40
private const val DETENT_GRAB = 0.022f

fun cutoffToFraction(hz: Float): Float =
    (kotlin.math.ln(hz.coerceIn(MIN_CUTOFF, MAX_CUTOFF) / MIN_CUTOFF) /
        kotlin.math.ln(MAX_CUTOFF / MIN_CUTOFF)).coerceIn(0f, 1f)

fun fractionToCutoff(fraction: Float): Float =
    MIN_CUTOFF * (MAX_CUTOFF / MIN_CUTOFF).pow(fraction.coerceIn(0f, 1f))

private fun toneDescription(cutoffHz: Float, resonance: Float): String {
    val brightness = when {
        cutoffHz > 14_000f -> "bright"
        cutoffHz > 6_000f -> "fairly bright"
        cutoffHz > 1_500f -> "muffled"
        else -> "very muffled"
    }
    val edge = when {
        resonance > 8f -> "very sharp"
        resonance > 3f -> "sharp"
        resonance > 1f -> "a little sharp"
        else -> "smooth"
    }
    return "$brightness, $edge"
}
