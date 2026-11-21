package com.theveloper.pixelplay.presentation.songtap

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.songtap.ring.RingCodeSlots
import com.theveloper.pixelplay.songtap.SongTapTimeline
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.RingCodeTimeline
import com.theveloper.pixelplay.songtap.ring.RingFrameBuffer
import com.theveloper.pixelplay.songtap.ring.image.RingCodeStyle
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/*
 * The Pixl ring code, drawn.
 *
 * One pre-built geometry per size (the 200 dot centres, the big and the small dots as two flat point arrays), drawn in the draw
 * phase only: the idle sway, the approach and the beam are `RingCodeTimeline.frameInto` read inside the draw block and the
 * art's graphicsLayer block, so a frame is a few integer operations, two `drawRawPoints` calls and a handful of shapes, with
 * no recomposition, no allocation and no blur. While the beam plays each dot is drawn on its own (200 circles, 2 seconds).
 * The idle clock ticks at about 30 frames a second (the sway is slow) and not at all with animations off.
 */

/**
 * The device colour: the Material You primary for the app's light or dark setting (API 31 and up), the app's own primary on
 * API 30. ARGB. The ring code's dots are this colour, darkened only as far as the 7:1 contrast rule needs.
 */
@Composable
fun rememberDeviceColor(dark: Boolean = LocalPixelPlayDarkTheme.current): Int {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.primary
    return remember(context, dark, fallback) {
        val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary }.getOrNull()
        } else {
            null
        }
        (dynamic ?: fallback).toArgb()
    }
}

@Composable
fun rememberRingPalette(dark: Boolean = LocalPixelPlayDarkTheme.current): RingCodeStyle.Palette {
    val device = rememberDeviceColor(dark)
    return remember(device) { RingCodeStyle.palette(device) }
}

/** True when the phone's animations are off (the animator scale is 0): the ring code then cross-fades and does not sway. */
@Composable
fun rememberReducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

/** Where every slot sits in the ring and in its ring, so the beam can ask the timeline about a dot without any search. */
private object SlotTables {
    val ring = IntArray(RingCodeLayout.SLOTS) { RingCodeLayout.ringOf(it) }
    val index = IntArray(RingCodeLayout.SLOTS) { RingCodeLayout.indexInRing(it) }
    val angle = FloatArray(RingCodeLayout.SLOTS) { slot ->
        (index[slot].toDouble() / RingCodeLayout.RING_SLOTS[ring[slot]] * 2.0 * Math.PI).toFloat()
    }
}

/** The geometry of one code at one size, built once: dot centres relative to the plate's centre, in pixels. */
private class RingGeometry(half: Float, val bits: ByteArray) {
    val unit = half / 1000f
    val slotX = FloatArray(RingCodeLayout.SLOTS)
    val slotY = FloatArray(RingCodeLayout.SLOTS)
    val slotR = FloatArray(RingCodeLayout.SLOTS)
    val big: FloatArray
    val small: FloatArray

    init {
        var bigCount = 0
        for (slot in 0 until RingCodeLayout.SLOTS) {
            val r = RingCodeLayout.RING_RADIUS[SlotTables.ring[slot]] * unit
            slotR[slot] = r
            slotX[slot] = r * sin(SlotTables.angle[slot])
            slotY[slot] = -r * cos(SlotTables.angle[slot])
            if (bits[slot].toInt() != 0) bigCount++
        }
        big = FloatArray(bigCount * 2)
        small = FloatArray((RingCodeLayout.SLOTS - bigCount) * 2)
        var b = 0
        var s = 0
        for (slot in 0 until RingCodeLayout.SLOTS) {
            if (bits[slot].toInt() != 0) {
                big[b++] = slotX[slot]; big[b++] = slotY[slot]
            } else {
                small[s++] = slotX[slot]; small[s++] = slotY[slot]
            }
        }
    }
}

private const val IDLE_FRAME_MS = 30L
private const val NEAR_ENTER = 170
private const val NEAR_EXIT = 90
private const val SPIRAL_TURNS = 0.32f
private const val TWO_PI = 6.2831855f

/**
 * One ring code: the plate, the outline, the 200 dots, the orange start dot and, in the middle, the artwork ([art], clipped to a
 * circle). The code sways while idle, tightens and glows when the other phone is near ([approach], the 0..500 level the
 * Bluetooth side publishes; null for none) and plays the beam from [beamStartMs] (wall clock, 0 for none): the dots peel off and spiral
 * into the art, which pulses, lifts and leaves through the top ([rootHeight] is the height it flies across), and [pillText] fades in.
 * With animations off ([reduced]) nothing moves and the beam is a 150 ms cross-fade.
 */
@Composable
fun RingCodeView(
    slots: RingCodeSlots,
    palette: RingCodeStyle.Palette,
    modifier: Modifier = Modifier,
    approach: State<Int>? = null,
    beamStartMs: Long = 0L,
    rootHeight: IntState? = null,
    pillText: String? = null,
    reduced: Boolean = rememberReducedMotion(),
    art: @Composable BoxScope.() -> Unit,
) {
    val shownAt = remember { System.currentTimeMillis() }
    val clock = remember { mutableLongStateOf(0L) }
    val approachStart = remember { mutableLongStateOf(RingCodeTimeline.NONE) }
    val approachEnd = remember { mutableLongStateOf(RingCodeTimeline.NONE) }
    val beaming = beamStartMs != 0L

    // The idle clock: ~30 frames a second, none with animations off unless a beam is playing.
    LaunchedEffect(reduced, beaming) {
        if (reduced && !beaming) {
            clock.longValue = System.currentTimeMillis() - shownAt
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameMillis {
                val now = System.currentTimeMillis()
                if (beaming || now - last >= IDLE_FRAME_MS) {
                    last = now
                    clock.longValue = now - shownAt
                }
            }
        }
    }
    // "The other phone is near", with hysteresis, as the two moments the timeline wants.
    if (approach != null) {
        LaunchedEffect(approach) {
            var near = false
            snapshotFlow { approach.value }.collectLatest { level ->
                val t = System.currentTimeMillis() - shownAt
                if (!near && level >= NEAR_ENTER) { near = true; approachStart.longValue = t; approachEnd.longValue = RingCodeTimeline.NONE }
                else if (near && level <= NEAR_EXIT) { near = false; approachEnd.longValue = t }
            }
        }
    }
    val beamAt = if (beaming) beamStartMs - shownAt else RingCodeTimeline.NONE

    val dot = Color(palette.dot)
    val plate = Color(palette.plate)
    val marker = Color(palette.marker)
    val drawer = remember(slots, palette, reduced, beamAt) {
        val frame = RingFrameBuffer()
        Modifier.drawWithCache {
            val half = size.minDimension / 2f
            val geometry = RingGeometry(half, slots.bits)
            val u = geometry.unit
            val centre = Offset(size.width / 2f, size.height / 2f)
            val bigPaint = Paint().apply {
                isAntiAlias = true; color = dot; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round
                strokeWidth = 2f * RingCodeLayout.BIG_RADIUS * u
            }
            val smallPaint = Paint().apply {
                isAntiAlias = true; color = dot; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round
                strokeWidth = 2f * RingCodeLayout.SMALL_RADIUS * u
            }
            val outline = Stroke(width = (RingCodeLayout.BEZEL_OUTER - RingCodeLayout.BEZEL_INNER) * u)
            val outlineRadius = (RingCodeLayout.BEZEL_OUTER + RingCodeLayout.BEZEL_INNER) / 2f * u
            val glowBrush = Brush.radialGradient(
                colors = listOf(dot.copy(alpha = 0.55f), dot.copy(alpha = 0f)),
                center = centre,
                radius = half * 1.3f,
            )
            val corner = CornerRadius(RingCodeLayout.PLATE_CORNER * u)
            val markerCentre = Offset(0f, -RingCodeLayout.MARKER_RADIUS_AT * u)
            val bigR = RingCodeLayout.BIG_RADIUS * u
            val smallR = RingCodeLayout.SMALL_RADIUS * u
            val markerR = RingCodeLayout.MARKER_RADIUS * u
            val artEdge = RingCodeLayout.ART_RADIUS * u * 0.8f
            onDrawBehind {
                RingCodeTimeline.frameInto(clock.longValue, approachStart.longValue, approachEnd.longValue, beamAt, reduced, frame)
                if (frame.glow > 0) drawCircle(glowBrush, radius = half * 1.3f, center = centre, alpha = frame.glow / 1000f)
                if (frame.plateAlpha > 0) {
                    drawRoundRect(plate, size = Size(size.width, size.height), cornerRadius = corner, alpha = frame.plateAlpha / 1000f)
                }
                val alpha = frame.codeAlpha / 1000f
                if (alpha <= 0f) return@onDrawBehind
                withTransform({
                    translate(centre.x, centre.y)
                    rotate(frame.codeAngle / 1_000_000f * (180f / Math.PI.toFloat()), Offset.Zero)
                    scale(frame.codeScale / 1000f, frame.codeScale / 1000f, Offset.Zero)
                }) {
                    if (frame.plateAlpha > 0) {
                        drawCircle(dot, radius = outlineRadius, center = Offset.Zero, alpha = alpha * frame.plateAlpha / 1000f, style = outline)
                    }
                    if (frame.beamMs < 0) {
                        // At rest: all big dots in one call, all small dots in another.
                        val canvas = drawContext.canvas
                        bigPaint.alpha = alpha
                        smallPaint.alpha = alpha
                        canvas.drawRawPoints(PointMode.Points, geometry.big, bigPaint)
                        canvas.drawRawPoints(PointMode.Points, geometry.small, smallPaint)
                        drawCircle(marker, radius = markerR, center = markerCentre, alpha = alpha)
                    } else {
                        for (slot in 0 until RingCodeLayout.SLOTS) {
                            val p = RingCodeTimeline.slotProgress(SlotTables.ring[slot], SlotTables.index[slot], frame.beamMs)
                            if (p >= 1000) continue
                            val isBig = slots.bits[slot].toInt() != 0
                            val r = if (isBig) bigR else smallR
                            if (p == 0) {
                                drawCircle(dot, radius = r, center = Offset(geometry.slotX[slot], geometry.slotY[slot]), alpha = alpha)
                                continue
                            }
                            val e = SongTapTimeline.smooth(p) / 1000f
                            val radius = geometry.slotR[slot] + (artEdge - geometry.slotR[slot]) * e
                            val angle = SlotTables.angle[slot] + SPIRAL_TURNS * TWO_PI * e
                            val fade = if (p < 650) 1f else 1f - (p - 650) / 350f
                            drawCircle(
                                dot,
                                radius = r * (1f - 0.35f * e),
                                center = Offset(radius * sin(angle), -radius * cos(angle)),
                                alpha = alpha * fade,
                            )
                        }
                        val mp = RingCodeTimeline.markerProgress(frame.beamMs)
                        if (mp < 1000) {
                            val e = SongTapTimeline.smooth(mp) / 1000f
                            val radius = -markerCentre.y + (artEdge - (-markerCentre.y)) * e
                            val angle = SPIRAL_TURNS * TWO_PI * e
                            drawCircle(
                                marker,
                                radius = markerR * (1f - 0.35f * e),
                                center = Offset(radius * sin(angle), -radius * cos(angle)),
                                alpha = alpha * (if (mp < 650) 1f else 1f - (mp - 650) / 350f),
                            )
                        }
                    }
                }
            }
        }
    }

    Box(modifier.aspectRatio(1f)) {
        Box(Modifier.fillMaxSize().then(drawer))
        val artFrame = remember { RingFrameBuffer() }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(RingCodeLayout.ART_RADIUS * 2f / 1000f)
                .graphicsLayer {
                    RingCodeTimeline.frameInto(clock.longValue, approachStart.longValue, approachEnd.longValue, beamAt, reduced, artFrame)
                    val s = artFrame.artScale / 1000f
                    scaleX = s
                    scaleY = s
                    translationY = artFrame.artTranslateY / 1000f * (rootHeight?.intValue ?: size.height.toInt() * 4)
                    alpha = artFrame.artAlpha / 1000f
                    shape = CircleShape
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            content = art,
        )
        if (pillText != null) {
            val pillFrame = remember { RingFrameBuffer() }
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        RingCodeTimeline.frameInto(clock.longValue, approachStart.longValue, approachEnd.longValue, beamAt, reduced, pillFrame)
                        alpha = pillFrame.pillAlpha / 1000f
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(pillText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

// ── Previews: the code at rest and at moments of the beam ────────────────────────────────

private fun previewSlots(): RingCodeSlots {
    val bits = com.theveloper.pixelplay.songtap.ring.RingCodePayloads.encodeSlots("dQw4w9WgXcQ", 12, 77)!!
    return RingCodeSlots(bits, 1)
}

@Preview(name = "Ring code, at rest", widthDp = 320, heightDp = 320)
@Composable
private fun RingCodeAtRest() {
    MaterialTheme {
        RingCodeView(
            slots = remember { previewSlots() },
            palette = remember { RingCodeStyle.palette(0xFF6750A4.toInt()) },
            reduced = true,
            modifier = Modifier.padding(8.dp),
            art = { Box(Modifier.fillMaxSize().background(Color(0xFF8C6D3F))) },
        )
    }
}

@Preview(name = "Ring code, beam half way", widthDp = 320, heightDp = 320)
@Composable
private fun RingCodeBeam() {
    MaterialTheme {
        RingCodeView(
            slots = remember { previewSlots() },
            palette = remember { RingCodeStyle.palette(0xFF006A60.toInt()) },
            reduced = false,
            beamStartMs = System.currentTimeMillis() - 500,
            modifier = Modifier.padding(8.dp),
            art = { Box(Modifier.fillMaxSize().background(Color(0xFF8C6D3F))) },
        )
    }
}
