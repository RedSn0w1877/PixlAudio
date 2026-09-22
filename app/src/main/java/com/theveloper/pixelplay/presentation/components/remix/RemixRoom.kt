package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.StemUi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room: a tilted floor you are standing in the middle of, seen over your own shoulder.
 *
 * The screen this replaces drew a flat grey circle with hairline strokes, and "in front of me"
 * and "behind me" were the same place on it. Here depth is carried by six stacked cues —
 * perspective convergence, vertical bow, scale, occlusion order, contact shadow and a horizon —
 * so the two halves of the floor are visibly different places.
 *
 * The part that actually teaches distance is not any of those. It is the **pressure rings**: a
 * stem's onsets fire ripples that cross the floor at a fixed speed and take real time to reach
 * your head. Put the drums behind you and you watch each kick travel forward and flash the back
 * of your head a beat later. Distance becomes something you see before you read a label.
 */

// ───────────────────────────────────────────────────────────── projection

/**
 * Stage coordinates are −1..1 with the listener at the origin and −y meaning "ahead of you".
 * These six numbers turn that into a tilted floor. Everything else in the file hangs off them.
 */
@Immutable
class RoomProjection(val width: Float, val height: Float) {
    val r = width * 0.44f
    val rv = r * FLOOR_SQUASH
    val cx = width / 2f
    val cy = height * 0.52f

    /** Near things are wider apart than far things — this is the convergence. */
    fun depth(sy: Float): Float = 1f + PERSPECTIVE * sy

    fun px(sx: Float, sy: Float): Float = cx + sx * r * depth(sy)

    fun py(sy: Float): Float = cy + sy * (1f + DEPTH_BOW * sy) * rv

    /**
     * Screen point back to stage coordinates. Two fixed-point passes for the lift term, because
     * a puck floats above its own floor point and the lift itself depends on depth.
     */
    fun unproject(fx: Float, fy: Float, liftPx: Float): Pair<Float, Float> {
        var d = 1f
        var sy = 0f
        repeat(2) {
            val t = (fy - liftPx * d - cy) / rv
            val disc = 1f + 4f * DEPTH_BOW * t
            sy = if (disc <= 0f) -1f else (-1f + sqrt(disc)) / (2f * DEPTH_BOW)
            sy = sy.coerceIn(-1f, 1f)
            d = depth(sy)
        }
        val sx = (fx - cx) / (r * d)
        return sx.coerceIn(-1f, 1f) to sy
    }

    companion object {
        const val FLOOR_SQUASH = 0.50f

        /**
         * Deliberately mild. Perspective compresses the far half, so placing something dead
         * ahead is fiddlier than placing it behind you — and dead ahead is what people reach for
         * first. If front placement tests badly, lower this before touching anything else; above
         * 0.22 the far rim collapses and the floor reads as a funnel.
         */
        const val DEPTH_BOW = 0.16f
        const val PERSPECTIVE = 0.22f
    }
}

/** Clamp to the unit circle so the far corners — loudest and most distant — simply don't exist. */
private fun clampToUnitCircle(x: Float, y: Float): Pair<Float, Float> {
    val length = hypot(x, y)
    if (length <= 1f) return x to y
    return (x / length) to (y / length)
}

// ────────────────────────────────────────────────────────────── backdrop

/**
 * Sits under the room in its own node, so its slow ambient drift never invalidates stage geometry.
 */
@Composable
fun RoomBackdrop(motion: RemixStageMotion, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val backdrop = Brush.verticalGradient(
                    listOf(
                        colors.surfaceContainerLowest,
                        colors.surface,
                        colors.surfaceContainerHigh,
                    ),
                )
                val vignette = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        colors.scrim.copy(alpha = if (isDark) 0.22f else 0.10f),
                    ),
                    center = Offset(size.width / 2f, size.height * 0.5f),
                    radius = hypot(size.width, size.height) * 0.62f,
                )
                val projection = RoomProjection(size.width, size.height)
                val horizonY = projection.py(-1f) - 24.dp.toPx()
                val parallaxPx = 20.dp.toPx()
                val overhangPx = 40.dp.toPx()
                val bandPx = 120.dp.toPx()
                val horizonBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        colors.primary.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    startY = horizonY,
                    endY = horizonY + 120.dp.toPx(),
                )
                val blobA = Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                )
                val blobB = Brush.radialGradient(
                    listOf(colors.tertiary.copy(alpha = 0.13f), Color.Transparent),
                )
                onDrawBehind {
                    motion.frame.intValue // draw-phase subscription; nothing above recomposes
                    drawRect(backdrop)

                    val t = motion.timeSec
                    val blobR = minOf(size.width, size.height) * 0.70f
                    val lift = motion.loudness.coerceIn(0f, 1f) * 0.10f
                    // Two slow Lissajous orbits: the room breathes with the music, and slows
                    // down when the tape does, because timeSec advances with the rate.
                    drawBlob(blobA, t, 46f, 61f, blobR, size, 0.30f + lift)
                    drawBlob(blobB, t, 61f, 46f, blobR, size, 0.26f + lift)

                    // The horizon slides against the floor when you turn — that parallax is what
                    // makes the tilt read as a space rather than a drawing.
                    val slide = sin(motion.yaw) * parallaxPx
                    drawRect(
                        horizonBrush,
                        topLeft = Offset(-overhangPx + slide, horizonY),
                        size = Size(size.width + overhangPx * 2, bandPx),
                    )
                    drawRect(vignette)
                }
            },
    ) {}
}

private fun DrawScope.drawBlob(
    brush: Brush,
    t: Float,
    periodX: Float,
    periodY: Float,
    radius: Float,
    canvas: Size,
    alpha: Float,
) {
    val x = canvas.width * (0.5f + 0.34f * sin(t / periodX * TAU))
    val y = canvas.height * (0.42f + 0.30f * cos(t / periodY * TAU))
    withTransform({ translate(x - radius, y - radius) }) {
        drawRect(brush, size = Size(radius * 2, radius * 2), alpha = alpha)
    }
}

private const val TAU = 6.2831855f

// ────────────────────────────────────────────────────────────────── room

@Composable
fun RemixRoom(
    stems: List<StemUi>,
    motion: RemixStageMotion,
    manualPose: Boolean,
    onStemMoved: (index: Int, x: Float, y: Float) -> Unit,
    onStemDragEnd: (index: Int, x: Float, y: Float) -> Unit,
    onStemTapped: (index: Int) -> Unit,
    onStemSolo: (index: Int) -> Unit,
    onHeadTapped: () -> Unit,
    onManualHeading: (Float) -> Unit,
    poseCaption: String,
    bottomInsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val headPath = remember { Path() }
    val conePath = remember { Path() }
    val silhouette = remember { Path() }
    val dash = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 9.dp.toPx())) }
    }

    // Every static label measured once. Measuring inside the draw lambda would allocate a layout
    // result per frame, per label, forever.
    val labelStyle = remember(colors) {
        TextStyle(
            color = colors.onSurfaceVariant.copy(alpha = 0.62f),
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    val softStyle = remember(colors) {
        TextStyle(color = colors.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 10.sp)
    }
    val cardinals: List<TextLayoutResult> = remember(measurer, labelStyle) {
        listOf("IN FRONT", "BEHIND YOU", "LEFT", "RIGHT", "YOU").map {
            measurer.measure(it, labelStyle)
        }
    }
    val ringWords = remember(measurer, softStyle) {
        listOf("close", "far").map { measurer.measure(it, softStyle) }
    }
    val caption = remember(measurer, softStyle, poseCaption) {
        measurer.measure(poseCaption, softStyle)
    }
    val pillStyle = remember(colors) {
        TextStyle(color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    val stemLabels = remember(measurer, pillStyle, stems) {
        stems.map { measurer.measure(it.kind.displayName() + if (it.muted) " · off" else "", pillStyle) }
    }

    val roomDescription = remember(stems) { describeRoom(stems) }

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = roomDescription }
            .pointerInput(stems.size, manualPose) {
                awaitEachGesture {
                    val projection = RoomProjection(size.width.toFloat(), size.height.toFloat())
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val liftPx = LIFT_DP.toPx()
                    val headR = HEAD_R_DP.toPx()

                    // Nearest first: a near puck occludes a far one, so it must also win the tap.
                    val hit = hitTest(down.position.x, down.position.y, stems, motion, projection, liftPx, this)
                    val onHead = hit == null &&
                        hypot(down.position.x - projection.cx, down.position.y - projection.cy) < headR + 12.dp.toPx()
                    if (hit == null && !onHead) return@awaitEachGesture

                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    down.consume()
                    motion.dragIndex = hit ?: -1

                    var travel = 0f
                    var lastX = 0f
                    var lastY = 0f
                    var lastHaptic = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        travel += change.positionChange().getDistance()
                        change.consume()
                        if (hit != null) {
                            val (ux, uy) = projection.unproject(change.position.x, change.position.y, liftPx)
                            val (cxs, cys) = clampToUnitCircle(ux, uy)
                            // The room rotates, so a finger position has to be un-rotated back
                            // into stage space before it means anything.
                            val (sx, sy) = rotate(cxs, cys, motion.yaw)
                            motion.targetStem(hit, sx, sy)
                            motion.curX[hit] = sx
                            motion.curY[hit] = sy
                            onStemMoved(hit, sx, sy)
                            lastX = sx; lastY = sy
                            val moved = hypot(sx - lastHaptic, 0f)
                            if (moved > 0.08f) {
                                lastHaptic = sx
                                performAppCompatHapticFeedback(
                                    view, haptics, HapticFeedbackConstantsCompat.SEGMENT_TICK,
                                )
                            }
                        } else if (manualPose) {
                            val dx = change.position.x - projection.cx
                            val dy = change.position.y - projection.cy
                            onManualHeading(atan2(dx, -dy))
                        }
                    }
                    motion.dragIndex = -1
                    if (travel < touchSlop) {
                        if (hit != null) onStemTapped(hit) else onHeadTapped()
                    } else if (hit != null) {
                        onStemDragEnd(hit, lastX, lastY)
                    }
                }
            }
            .drawWithCache {
                val projection = RoomProjection(size.width, size.height)
                val floorBrush = Brush.radialGradient(
                    0f to colors.surfaceContainer.copy(alpha = 0.55f),
                    0.70f to colors.surfaceContainerLow.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                    center = Offset(projection.cx, projection.cy),
                    radius = projection.r,
                )
                val poolBrushes = List(4) { i ->
                    Brush.radialGradient(
                        0f to stemAccent(colors, i).copy(alpha = 0.45f),
                        0.55f to stemAccent(colors, i).copy(alpha = 0.18f),
                        1f to Color.Transparent,
                        center = Offset(0.5f, 0.5f),
                        radius = 0.5f,
                    )
                }
                val ringOuter = colors.outlineVariant.copy(alpha = 0.55f)
                val ringInner = colors.outlineVariant.copy(alpha = 0.26f)
                val spoke = colors.onSurface.copy(alpha = 0.05f)
                val shadow = colors.onSurface.copy(alpha = 0.22f)

                onDrawBehind {
                    motion.frame.intValue // the single draw-phase subscription

                    drawFloor(projection, floorBrush, ringOuter, ringInner, spoke, ringWords)
                    drawCardinals(projection, cardinals)
                    drawRings(projection, motion, colors)
                    drawHead(projection, motion, colors, headPath, conePath, cardinals[4], caption)
                    drawPucks(
                        projection, motion, stems, colors, poolBrushes, silhouette,
                        stemLabels, dash, shadow,
                    )
                }
            },
    ) {}
}

// ─────────────────────────────────────────────────────────────── drawing

private fun DrawScope.drawFloor(
    p: RoomProjection,
    floorBrush: Brush,
    ringOuter: Color,
    ringInner: Color,
    spoke: Color,
    ringWords: List<TextLayoutResult>,
) {
    withTransform({
        scale(1f, RoomProjection.FLOOR_SQUASH, pivot = Offset(p.cx, p.cy))
    }) {
        drawCircle(floorBrush, radius = p.r, center = Offset(p.cx, p.cy))
    }

    // Ovals, not circles, so the bow applies. All strokes in dp — raw-pixel strokes are literally
    // why the old stage looked like a hairline wireframe on a 3× screen.
    for (f in floatArrayOf(0.36f, 0.68f, 1f)) {
        val top = p.py(-f)
        val bottom = p.py(f)
        drawOval(
            color = if (f == 1f) ringOuter else ringInner,
            topLeft = Offset(p.cx - p.r * f, top),
            size = Size(2 * p.r * f, bottom - top),
            style = Stroke(width = if (f == 1f) 2.dp.toPx() else 1.dp.toPx()),
        )
    }

    // Spokes fan and converge in perspective, which is what sells the tilt even with no pucks.
    for (i in 0 until 8) {
        val a = i * (TAU / 8f)
        val sx = sin(a)
        val sy = -cos(a)
        drawLine(
            color = spoke,
            start = Offset(p.px(sx * 0.12f, sy * 0.12f), p.py(sy * 0.12f)),
            end = Offset(p.px(sx, sy), p.py(sy)),
            strokeWidth = 1.dp.toPx(),
        )
    }

    drawText(ringWords[0], topLeft = Offset(p.cx + p.r * 0.36f + 6.dp.toPx(), p.cy - 7.dp.toPx()))
    drawText(ringWords[1], topLeft = Offset(p.cx + p.r + 6.dp.toPx(), p.cy - 7.dp.toPx()))
}

/** Body-relative, so these do NOT rotate: they name where *you* are facing, not where the room is. */
private fun DrawScope.drawCardinals(p: RoomProjection, labels: List<TextLayoutResult>) {
    drawText(
        labels[0],
        topLeft = Offset(p.cx - labels[0].size.width / 2f, p.py(-1f) - 22.dp.toPx()),
    )
    drawText(
        labels[1],
        topLeft = Offset(p.cx - labels[1].size.width / 2f, p.py(1f) + 8.dp.toPx()),
    )
    drawText(
        labels[2],
        topLeft = Offset(p.cx - p.r - labels[2].size.width - 6.dp.toPx(), p.cy - labels[2].size.height / 2f),
    )
    drawText(
        labels[3],
        topLeft = Offset(p.cx + p.r + 6.dp.toPx(), p.cy - labels[3].size.height / 2f),
    )
}

private fun DrawScope.drawRings(p: RoomProjection, motion: RemixStageMotion, colors: androidx.compose.material3.ColorScheme) {
    for (i in 0 until RemixStageMotion.RING_CAP) {
        if (!motion.ringAlive(i)) continue
        val age = motion.ringAge(i)
        val radius = motion.ringRadius[i]
        if (radius <= 0.001f) continue

        val (rx, ry) = rotate(motion.ringX[i], motion.ringY[i], -motion.yaw)
        val ox = p.px(rx, ry)
        val oy = p.py(ry)
        val fade = (1f - age)
        val alpha = fade * fade * motion.ringStrength[i] * 0.55f
        if (alpha < 0.004f) continue

        val rr = radius * p.r
        drawOval(
            color = stemAccent(colors, motion.ringStem[i]).copy(alpha = alpha),
            topLeft = Offset(ox - rr, oy - rr * RoomProjection.FLOOR_SQUASH),
            size = Size(2 * rr, 2 * rr * RoomProjection.FLOOR_SQUASH),
            style = Stroke(width = androidx.compose.ui.util.lerp(5.dp.toPx(), 1.2.dp.toPx(), age)),
        )
    }
}

private fun DrawScope.drawHead(
    p: RoomProjection,
    motion: RemixStageMotion,
    colors: androidx.compose.material3.ColorScheme,
    headPath: Path,
    conePath: Path,
    youLabel: TextLayoutResult,
    caption: TextLayoutResult,
) {
    val headR = HEAD_R_DP.toPx()
    val pulse = motion.headPulse.coerceIn(0f, 1.4f)

    // Facing cone, under the head. Always points up-screen: the room turns, you don't.
    conePath.rewind()
    val coneLen = p.r * 0.52f
    val half = 0.4537f // 26°
    conePath.moveTo(p.cx, p.cy)
    conePath.lineTo(p.cx + sin(-half) * coneLen, p.cy - cos(-half) * coneLen * RoomProjection.FLOOR_SQUASH * 2f)
    conePath.lineTo(p.cx + sin(half) * coneLen, p.cy - cos(half) * coneLen * RoomProjection.FLOOR_SQUASH * 2f)
    conePath.close()
    drawPath(conePath, colors.primary.copy(alpha = 0.14f + pulse * 0.10f))
    drawPath(conePath, colors.primary.copy(alpha = 0.34f), style = Stroke(1.5.dp.toPx()))

    // Three stacked ovals instead of a blur — cheap, shaderless, and reads as contact.
    for ((i, a) in floatArrayOf(0.14f, 0.09f, 0.05f).withIndex()) {
        val rr = headR * (1f + i * 0.27f)
        drawOval(
            color = colors.onSurface.copy(alpha = a),
            topLeft = Offset(p.cx - rr, p.cy - rr * RoomProjection.FLOOR_SQUASH + 4.dp.toPx()),
            size = Size(rr * 2, rr * 2 * RoomProjection.FLOOR_SQUASH),
        )
    }

    if (pulse > 0.001f) {
        drawCircle(
            color = colors.primary.copy(alpha = pulse * 0.35f),
            radius = headR * (1.4f + pulse * 1.2f),
            center = Offset(p.cx, p.cy),
        )
    }

    // Ears and a nose notch, so "which way am I facing" is answered by the shape itself.
    drawRoundRect(
        color = colors.primaryContainer,
        topLeft = Offset(p.cx - headR - 2.5.dp.toPx(), p.cy - 5.dp.toPx()),
        size = Size(5.dp.toPx(), 10.dp.toPx()),
        cornerRadius = CornerRadius(2.5.dp.toPx()),
    )
    drawRoundRect(
        color = colors.primaryContainer,
        topLeft = Offset(p.cx + headR - 2.5.dp.toPx(), p.cy - 5.dp.toPx()),
        size = Size(5.dp.toPx(), 10.dp.toPx()),
        cornerRadius = CornerRadius(2.5.dp.toPx()),
    )
    drawCircle(colors.primary, radius = headR, center = Offset(p.cx, p.cy))
    headPath.rewind()
    headPath.moveTo(p.cx, p.cy - headR - 5.dp.toPx())
    headPath.lineTo(p.cx - 4.dp.toPx(), p.cy - headR + 2.dp.toPx())
    headPath.lineTo(p.cx + 4.dp.toPx(), p.cy - headR + 2.dp.toPx())
    headPath.close()
    drawPath(headPath, colors.primary)
    drawCircle(
        color = colors.onPrimary.copy(alpha = 0.20f),
        radius = headR * 0.42f,
        center = Offset(p.cx, p.cy - headR * 0.22f),
    )

    drawText(youLabel, topLeft = Offset(p.cx - youLabel.size.width / 2f, p.cy + headR + 6.dp.toPx()))
    drawText(caption, topLeft = Offset(p.cx - caption.size.width / 2f, p.cy + headR + 22.dp.toPx()))
}

private fun DrawScope.drawPucks(
    p: RoomProjection,
    motion: RemixStageMotion,
    stems: List<StemUi>,
    colors: androidx.compose.material3.ColorScheme,
    poolBrushes: List<Brush>,
    silhouette: Path,
    labels: List<TextLayoutResult>,
    dash: PathEffect,
    shadow: Color,
) {
    if (stems.isEmpty()) return

    // Sorted far-to-near so near pucks occlude far ones. That ordering alone carries more depth
    // than any other single cue here.
    val order = stems.indices.sortedBy { i ->
        val idx = stems[i].index
        rotate(motion.curX[idx], motion.curY[idx], -motion.yaw).second
    }

    for (oi in order) {
        val stem = stems[oi]
        val i = stem.index
        if (i >= RemixStageMotion.MAX_STEMS) continue
        val (rx, ry) = rotate(motion.curX[i], motion.curY[i], -motion.yaw)
        val d = p.depth(ry)
        val fx = p.px(rx, ry)
        val fy = p.py(ry)
        val lift = LIFT_DP.toPx() * d
        val bodyY = fy - lift
        val puckR = PUCK_R_DP.toPx() * d
        val peak = motion.peaks[i].coerceIn(0f, 1f)
        val accent = if (stem.muted) colors.surfaceContainer else stemAccent(colors, i)

        // Light pool: one unit-radius brush, scaled. A varying radius must never rebuild a shader.
        val poolR = puckR * (1.6f + peak * 1.5f)
        withTransform({
            translate(fx - poolR, fy - poolR * RoomProjection.FLOOR_SQUASH)
            scale(poolR * 2f, poolR * 2f * RoomProjection.FLOOR_SQUASH, pivot = Offset.Zero)
        }) {
            drawRect(
                poolBrushes[i % poolBrushes.size],
                size = Size(1f, 1f),
                alpha = 0.10f + peak * 0.30f,
            )
        }

        // The tether is the only thing that makes distance-from-you readable mid-flight.
        drawLine(
            color = stemAccent(colors, i).copy(alpha = if (i == motion.dragIndex) 0.34f else 0.10f),
            start = Offset(p.cx, p.cy),
            end = Offset(fx, fy),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dash,
        )

        drawOval(
            color = shadow.copy(alpha = 0.22f * d),
            topLeft = Offset(fx - puckR * 0.9f, fy - puckR * 0.9f * RoomProjection.FLOOR_SQUASH),
            size = Size(puckR * 1.8f, puckR * 1.8f * RoomProjection.FLOOR_SQUASH),
        )

        // Silhouette carries identity, because four album-derived accents on a grey cover may not
        // be four distinguishable colours. Colour is the third channel here, never the only one.
        drawSilhouette(silhouette, stem.kind, fx, bodyY, puckR, accent, stem.muted, colors)

        if (peak > 0.01f) {
            drawCircle(
                color = stemAccent(colors, i).copy(alpha = peak * 0.8f),
                radius = puckR + 4.dp.toPx() + peak * 10.dp.toPx(),
                center = Offset(fx, bodyY),
                style = Stroke(2.dp.toPx() + peak * 3.dp.toPx()),
            )
        }

        if (stem.muted) {
            val s = puckR * 0.72f
            drawLine(
                color = colors.onSurfaceVariant,
                start = Offset(fx - s, bodyY + s),
                end = Offset(fx + s, bodyY - s),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Label below the body, never inside it — no more clipped names.
        labels.getOrNull(oi)?.let { label ->
            val padH = 8.dp.toPx()
            val padV = 3.dp.toPx()
            val w = label.size.width + padH * 2
            val h = label.size.height + padV * 2
            val lx = fx - w / 2f
            val ly = bodyY + puckR + 6.dp.toPx()
            drawRoundRect(
                color = colors.surface.copy(alpha = 0.78f),
                topLeft = Offset(lx, ly),
                size = Size(w, h),
                cornerRadius = CornerRadius(9.dp.toPx()),
            )
            drawText(label, topLeft = Offset(lx + padH, ly + padV))
        }
    }
}

private fun DrawScope.drawSilhouette(
    path: Path,
    kind: String,
    cx: Float,
    cy: Float,
    r: Float,
    fill: Color,
    muted: Boolean,
    colors: androidx.compose.material3.ColorScheme,
) {
    when (kind) {
        "drums" -> {
            drawRoundRect(
                color = fill,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                cornerRadius = CornerRadius(r * 0.45f),
            )
            if (muted) drawRoundRect(
                color = colors.outline,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                cornerRadius = CornerRadius(r * 0.45f),
                style = Stroke(2.dp.toPx()),
            )
        }
        "bass" -> {
            polygon(path, cx, cy, r, 6, 0f)
            drawPath(path, fill)
            if (muted) drawPath(path, colors.outline, style = Stroke(2.dp.toPx()))
        }
        "other", "sides" -> {
            polygon(path, cx, cy, r, 6, TAU / 12f)
            drawPath(path, fill)
            if (muted) drawPath(path, colors.outline, style = Stroke(2.dp.toPx()))
        }
        else -> {
            drawCircle(fill, radius = r, center = Offset(cx, cy))
            if (muted) drawCircle(colors.outline, radius = r, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
        }
    }
}

private fun polygon(path: Path, cx: Float, cy: Float, r: Float, sides: Int, rotation: Float) {
    path.rewind()
    for (i in 0 until sides) {
        val a = rotation + i * TAU / sides
        val x = cx + sin(a) * r
        val y = cy - cos(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

// ───────────────────────────────────────────────────────────────── helpers

/** Rotate stage coordinates. The room turns; the listener and the cardinal labels do not. */
private fun rotate(x: Float, y: Float, yaw: Float): Pair<Float, Float> {
    val c = cos(yaw)
    val s = sin(yaw)
    return (x * c - y * s) to (x * s + y * c)
}

private fun hitTest(
    fx: Float,
    fy: Float,
    stems: List<StemUi>,
    motion: RemixStageMotion,
    p: RoomProjection,
    liftPx: Float,
    density: androidx.compose.ui.unit.Density,
): Int? {
    var best: Int? = null
    var bestSy = -2f
    with(density) {
        val touchR = PUCK_R_DP.toPx() + 10.dp.toPx()
        for (stem in stems) {
            val i = stem.index
            if (i >= RemixStageMotion.MAX_STEMS) continue
            val (rx, ry) = rotate(motion.curX[i], motion.curY[i], -motion.yaw)
            val d = p.depth(ry)
            val bx = p.px(rx, ry)
            val by = p.py(ry) - liftPx * d
            if (hypot(fx - bx, fy - by) <= touchR * d && ry > bestSy) {
                best = i
                bestSy = ry
            }
        }
    }
    return best
}

private fun describeRoom(stems: List<StemUi>): String {
    if (stems.isEmpty()) return "The room is empty."
    return "Room view. " + stems.joinToString(" ") { stem ->
        val near = if (hypot(stem.x, stem.y) < 0.5f) "close" else "far away"
        val dir = compass(stem.x, stem.y)
        "${stem.kind.displayName()} is $dir and $near."
    }
}

private fun compass(x: Float, y: Float): String {
    val a = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).let { if (it < 0) it + 360 else it }
    return when {
        a < 22.5 || a >= 337.5 -> "ahead"
        a < 67.5 -> "ahead and to the right"
        a < 112.5 -> "to the right"
        a < 157.5 -> "behind and to the right"
        a < 202.5 -> "behind you"
        a < 247.5 -> "behind and to the left"
        a < 292.5 -> "to the left"
        else -> "ahead and to the left"
    }
}

/**
 * The `*Fixed` ladder is the app's "accent that survives light/dark inversion" family, and the one
 * palette designed for sibling accents that stay distinct in both themes. The fourth is neutral on
 * purpose: "the rest" is the residue, and its shape plus its label carry what colour gives the others.
 */
internal fun stemAccent(colors: androidx.compose.material3.ColorScheme, index: Int): Color =
    when (index % 4) {
        0 -> colors.primaryFixed
        1 -> colors.secondaryFixed
        2 -> colors.tertiaryFixed
        else -> colors.surfaceContainerHighest
    }

internal fun String.displayName(): String = when (this) {
    "vocals" -> "Voice"
    "drums" -> "Drums"
    "bass" -> "Bass"
    // "Music" was the worst possible name for one piece of a song, on a music screen.
    "other" -> "The rest"
    "instrumental" -> "Music only"
    "center" -> "Middle"
    "sides" -> "Sides"
    else -> replaceFirstChar { it.uppercase() }
}

private val LIFT_DP = 12.dp
private val PUCK_R_DP = 26.dp
private val HEAD_R_DP = 17.dp
