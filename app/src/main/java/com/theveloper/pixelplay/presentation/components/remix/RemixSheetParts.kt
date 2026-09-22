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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.StemUi
import com.theveloper.pixelplay.ui.theme.ShapeCache
import kotlin.math.abs
import kotlin.math.roundToInt
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * The loop window, on the whole song, and it is actually draggable.
 *
 * What this replaces looked exactly like a scrubber — a waveform with a playhead sweeping across
 * it — and had no `pointerInput` at all. Every music app has trained people that a waveform can
 * be touched; this one silently did nothing, and the loop could only be moved with two nudge
 * chips and resized with four length chips that overflowed off the right edge of a 360dp phone.
 *
 * Drag inside the window to move it, drag a handle to resize it, tap outside to jump there.
 *
 * All of it writes to local state read only in the draw lambda; [onLoopChanged] fires exactly
 * once, on pointer up. That is not a nicety — the ViewModel re-decodes the region from disk on
 * every call, so a per-pixel callback would thrash the audio engine.
 */
@Composable
fun LoopStrip(
    peaks: List<Float>,
    motion: RemixStageMotion,
    loopStartMs: Int,
    loopLengthMs: Int,
    songDurationMs: Int,
    onLoopChanged: (startMs: Int, lengthMs: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current

    val duration = songDurationMs.coerceAtLeast(1)
    // Pending values are what the strip draws while a finger is down.
    val pendingStart = remember { mutableFloatStateOf(loopStartMs.toFloat()) }
    val pendingLength = remember { mutableFloatStateOf(loopLengthMs.toFloat()) }
    val dragging = remember { mutableFloatStateOf(0f) }

    // Recomputed only when the peaks change — the old code scanned all 240 every single frame.
    val loudest = remember(peaks) { (peaks.maxOrNull() ?: 1f).coerceAtLeast(0.0001f) }

    if (dragging.floatValue == 0f) {
        pendingStart.floatValue = loopStartMs.toFloat()
        pendingLength.floatValue = loopLengthMs.toFloat()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Looping ${formatTime(pendingStart.floatValue.toInt())} – " +
                    formatTime((pendingStart.floatValue + pendingLength.floatValue).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
            )
            Box(Modifier.weight(1f))
            Text(
                text = "of ${formatTime(songDurationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .then(
                    if (!enabled) Modifier else Modifier.pointerInput(duration, loopStartMs, loopLengthMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            down.consume()

                            val w = size.width.toFloat()
                            val handleR = 22.dp.toPx()
                            fun xOf(ms: Float) = ms / duration * w
                            val startX = xOf(pendingStart.floatValue)
                            val endX = xOf(pendingStart.floatValue + pendingLength.floatValue)

                            val mode = when {
                                abs(down.position.x - startX) < handleR -> MODE_START
                                abs(down.position.x - endX) < handleR -> MODE_END
                                down.position.x in startX..endX -> MODE_MOVE
                                else -> MODE_JUMP
                            }
                            dragging.floatValue = 1f

                            if (mode == MODE_JUMP) {
                                pendingStart.floatValue =
                                    (down.position.x / w * duration)
                                        .coerceIn(0f, (duration - pendingLength.floatValue).coerceAtLeast(0f))
                            }

                            var lastSnap = pendingLength.floatValue
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                val dxMs = change.positionChange().x / w * duration
                                when (mode) {
                                    MODE_MOVE, MODE_JUMP -> {
                                        pendingStart.floatValue =
                                            (pendingStart.floatValue + dxMs)
                                                .coerceIn(0f, (duration - pendingLength.floatValue).coerceAtLeast(0f))
                                    }
                                    MODE_START -> {
                                        val next = (pendingStart.floatValue + dxMs).coerceAtLeast(0f)
                                        val delta = next - pendingStart.floatValue
                                        val len = (pendingLength.floatValue - delta)
                                        if (len in MIN_LEN..MAX_LEN) {
                                            pendingStart.floatValue = next
                                            pendingLength.floatValue = len
                                        }
                                    }
                                    MODE_END -> {
                                        pendingLength.floatValue =
                                            (pendingLength.floatValue + dxMs).coerceIn(MIN_LEN, MAX_LEN)
                                    }
                                }
                                if (mode == MODE_START || mode == MODE_END) {
                                    val snapped = snapLength(pendingLength.floatValue)
                                    if (snapped != lastSnap) {
                                        lastSnap = snapped
                                        performAppCompatHapticFeedback(
                                            view, haptics,
                                            HapticFeedbackConstantsCompat.SEGMENT_TICK,
                                        )
                                    }
                                }
                            }
                            dragging.floatValue = 0f
                            val finalLength = snapLength(pendingLength.floatValue)
                            onLoopChanged(pendingStart.floatValue.roundToInt(), finalLength.roundToInt())
                        }
                    }
                )
                .drawWithCache {
                    val base = colors.onSurface.copy(alpha = 0.08f)
                    val fill = Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))
                    val peakColor = colors.onPrimary.copy(alpha = 0.85f)
                    val handleFill = colors.surfaceContainerHighest
                    val handleStroke = colors.outline
                    val bead = colors.onPrimary

                    onDrawBehind {
                        motion.frame.intValue

                        val barH = 30.dp.toPx()
                        val top = size.height / 2f - barH / 2f
                        drawRoundRect(
                            color = base,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, barH),
                            cornerRadius = CornerRadius(barH / 2f),
                        )

                        val x = pendingStart.floatValue / duration * size.width
                        val w = (pendingLength.floatValue / duration * size.width)
                            .coerceAtLeast(28.dp.toPx())
                        drawRoundRect(
                            brush = fill,
                            topLeft = Offset(x, top),
                            size = Size(w, barH),
                            cornerRadius = CornerRadius(barH / 2f),
                            alpha = 0.9f,
                        )

                        // Peaks live inside the window, strided so at most one bar per 2dp is
                        // drawn no matter how many samples the array holds.
                        if (peaks.isNotEmpty()) {
                            clipRect(x, top, x + w, top + barH) {
                                val maxBars = (w / 2.dp.toPx()).toInt().coerceIn(1, peaks.size)
                                val stride = (peaks.size / maxBars).coerceAtLeast(1)
                                var i = 0
                                var bar = 0
                                while (i < peaks.size) {
                                    val h = (peaks[i] / loudest).coerceIn(0f, 1f) * barH * 0.8f
                                    val bx = x + (bar.toFloat() / maxBars) * w
                                    drawLine(
                                        color = peakColor,
                                        start = Offset(bx, top + barH / 2f - h / 2f),
                                        end = Offset(bx, top + barH / 2f + h / 2f),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                    i += stride
                                    bar++
                                }
                            }
                        }

                        val px = x + motion.playhead.coerceIn(0f, 1f) * w
                        drawLine(bead, Offset(px, top), Offset(px, top + barH), 2.dp.toPx())
                        drawCircle(bead, radius = 4.5.dp.toPx(), center = Offset(px, top + barH / 2f))

                        for (hx in floatArrayOf(x, x + w)) {
                            drawRoundRect(
                                color = handleFill,
                                topLeft = Offset(hx - 5.dp.toPx(), top - 3.dp.toPx()),
                                size = Size(10.dp.toPx(), barH + 6.dp.toPx()),
                                cornerRadius = CornerRadius(5.dp.toPx()),
                            )
                            drawRoundRect(
                                color = handleStroke,
                                topLeft = Offset(hx - 5.dp.toPx(), top - 3.dp.toPx()),
                                size = Size(10.dp.toPx(), barH + 6.dp.toPx()),
                                cornerRadius = CornerRadius(5.dp.toPx()),
                                style = Stroke(1.5.dp.toPx()),
                            )
                        }
                    }
                },
        )
        Text(
            text = "Drag the bar to move it, or its ends to make it longer.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

private const val MODE_MOVE = 0
private const val MODE_START = 1
private const val MODE_END = 2
private const val MODE_JUMP = 3
private const val MIN_LEN = 1_000f
private const val MAX_LEN = 30_000f

private fun snapLength(ms: Float): Float {
    val options = floatArrayOf(2_000f, 4_000f, 8_000f, 16_000f, 30_000f)
    return options.minByOrNull { abs(it - ms) } ?: ms
}

internal fun formatTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

// ────────────────────────────────────────────────────────────── transport

/**
 * Always on screen, thumb-sized, in a fixed place.
 *
 * Play/Stop used to be a small chip fifth in a scrolling list — and because playback auto-started,
 * the first time you found it, it already read "Stop".
 *
 * The widths breathe on press: the button you are touching grows and shoves its neighbours, and
 * the action fires after a short delay so the squeeze is seen. That is the app's own idiom.
 */
@Composable
fun TransportBar(
    playing: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onJumpBack: () -> Unit,
    onJumpForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val playCorner by animateDpAsState(
        targetValue = if (playing) 26.dp else 60.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "playCorner",
    )
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(ShapeCache.smoothPill)
            .background(colors.surfaceContainerLowest.copy(alpha = 0.70f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TransportButton(
            modifier = Modifier.weight(0.22f),
            container = colors.secondaryFixedDim.copy(alpha = alpha),
            content = colors.onSecondaryFixed.copy(alpha = alpha),
            corner = 26.dp,
            enabled = enabled,
            onClick = onJumpBack,
            description = "Jump back one loop",
        ) {
            Icon(Icons.Rounded.FastRewind, contentDescription = null, modifier = Modifier.size(26.dp))
        }
        TransportButton(
            modifier = Modifier.weight(0.56f),
            container = colors.tertiaryFixedDim.copy(alpha = alpha),
            content = colors.onTertiaryFixed.copy(alpha = alpha),
            corner = playCorner,
            enabled = enabled,
            onClick = onPlayPause,
            description = if (playing) "Pause the loop" else "Play the loop",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = if (playing) "Pause" else "Play",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        TransportButton(
            modifier = Modifier.weight(0.22f),
            container = colors.secondaryFixedDim.copy(alpha = alpha),
            content = colors.onSecondaryFixed.copy(alpha = alpha),
            corner = 26.dp,
            enabled = enabled,
            onClick = onJumpForward,
            description = "Jump forward one loop",
        ) {
            Icon(Icons.Rounded.FastForward, contentDescription = null, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun TransportButton(
    modifier: Modifier,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    corner: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
    inner: @Composable () -> Unit,
) {
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(AbsoluteSmoothCornerShape(corner, 60))
            .background(container)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(onClick) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = awaitPointerEvent().changes.firstOrNull()
                        if (up != null) {
                            performAppCompatHapticFeedback(
                                view, haptics, HapticFeedbackConstantsCompat.CONFIRM,
                            )
                            onClick()
                        }
                    }
                }
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides content,
        ) { inner() }
    }
}

// ───────────────────────────────────────────────────────────── part chips

/**
 * A flat, fully reachable twin of the stage.
 *
 * The room is a canvas; a screen reader cannot drag a puck around it. This row is the real
 * accessible control surface — every stem is a switch with four nudge actions — and it happens to
 * be the fastest way to mute something with a thumb, too.
 */
@Composable
fun PartChipRow(
    stems: List<StemUi>,
    motion: RemixStageMotion,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onNudge: (index: Int, dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stems.forEach { stem ->
            PartChip(
                stem = stem,
                motion = motion,
                onMute = { onMute(stem.index) },
                onSolo = { onSolo(stem.index) },
                onNudge = onNudge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PartChip(
    stem: StemUi,
    motion: RemixStageMotion,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onNudge: (index: Int, dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val corner by animateDpAsState(
        targetValue = if (stem.muted) 14.dp else 26.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chipCorner",
    )
    val accent = if (stem.muted) colors.surfaceContainer else stemAccent(colors, stem.index)

    Column(
        modifier = modifier
            .height(56.dp)
            .clip(AbsoluteSmoothCornerShape(corner, 60))
            .background(accent)
            .pointerInput(stem.index, stem.muted) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.uptimeMillis
                    var long = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (!long && change.uptimeMillis - start > 400L) {
                            long = true
                            performAppCompatHapticFeedback(
                                view, haptics, HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE,
                            )
                            onSolo()
                        }
                    }
                    if (!long) {
                        performAppCompatHapticFeedback(
                            view, haptics, HapticFeedbackConstantsCompat.TOGGLE_ON,
                        )
                        onMute()
                    }
                }
            }
            .semantics {
                role = Role.Switch
                contentDescription = stem.kind.displayName()
                stateDescription = if (stem.muted) "Muted" else "Playing"
                customActions = listOf(
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Move ahead") {
                        onNudge(stem.index, 0f, -0.25f); true
                    },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Move behind") {
                        onNudge(stem.index, 0f, 0.25f); true
                    },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Move left") {
                        onNudge(stem.index, -0.25f, 0f); true
                    },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Move right") {
                        onNudge(stem.index, 0.25f, 0f); true
                    },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(
                        if (stem.muted) "Unmute" else "Mute"
                    ) { onMute(); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Play only this") {
                        onSolo(); true
                    },
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val content = if (stem.muted) colors.onSurfaceVariant else stemContent(colors, stem.index)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .drawWithCache {
                    onDrawBehind {
                        motion.frame.intValue
                        val peak = if (stem.muted) 0f else motion.peaks[stem.index].coerceIn(0f, 1f)
                        val barW = 3.dp.toPx()
                        val gap = 3.dp.toPx()
                        val total = barW * 3 + gap * 2
                        var x = (size.width - total) / 2f
                        for (b in 0 until 3) {
                            val scale = when (b) { 1 -> 1f; else -> 0.66f }
                            val h = (size.height * (0.22f + peak * scale * 0.78f))
                            drawRoundRect(
                                color = content,
                                topLeft = Offset(x, size.height - h),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(barW / 2f),
                            )
                            x += barW + gap
                        }
                    }
                },
        )
        Text(
            text = stem.kind.displayName(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

private fun stemContent(colors: androidx.compose.material3.ColorScheme, index: Int) =
    when (index % 4) {
        0 -> colors.onPrimaryFixed
        1 -> colors.onSecondaryFixed
        2 -> colors.onTertiaryFixed
        else -> colors.onSurface
    }
