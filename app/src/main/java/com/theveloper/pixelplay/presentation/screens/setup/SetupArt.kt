package com.theveloper.pixelplay.presentation.screens.setup

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.subcomps.MaterialYouVectorDrawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val TWO_PI = 6.2831855f

/**
 * Shape vocabulary for the setup flow. Every polygon has radius 1 and is centred on the origin so
 * they can be swapped, morphed and scaled freely.
 */
internal object SetupShapes {
    val polygons: List<RoundedPolygon> by lazy {
        listOf(
            // 0: soft nine-petal cookie
            RoundedPolygon.star(
                numVerticesPerRadius = 9,
                innerRadius = 0.84f,
                rounding = CornerRounding(0.3f, 0.6f)
            ),
            // 1: squircle
            RoundedPolygon(numVertices = 4, rounding = CornerRounding(0.4f)),
            // 2: chunky six-point star
            RoundedPolygon.star(
                numVerticesPerRadius = 6,
                innerRadius = 0.7f,
                rounding = CornerRounding(0.25f, 0.5f)
            ),
            // 3: circle
            RoundedPolygon.circle(numVertices = 8),
            // 4: rounded triangle
            RoundedPolygon(numVertices = 3, rounding = CornerRounding(0.35f)),
            // 5: twelve-petal flower
            RoundedPolygon.star(
                numVerticesPerRadius = 12,
                innerRadius = 0.86f,
                rounding = CornerRounding(0.2f, 0.7f)
            ),
            // 6: rounded pentagon
            RoundedPolygon(numVertices = 5, rounding = CornerRounding(0.3f))
        )
    }

    /** One morph per page transition: shape i becomes shape i + 1 (wrapping around). */
    fun buildMorphs(): List<Morph> {
        val shapes = polygons
        return shapes.indices.map { i -> Morph(shapes[i], shapes[(i + 1) % shapes.size]) }
    }
}

/** Fills [path] with [cubics] scaled by [radius] around ([cx], [cy]). */
private fun fillPath(
    path: Path,
    cubics: List<androidx.graphics.shapes.Cubic>,
    cx: Float,
    cy: Float,
    radius: Float
) {
    path.rewind()
    if (cubics.isEmpty()) return
    path.moveTo(cx + cubics.first().anchor0X * radius, cy + cubics.first().anchor0Y * radius)
    for (c in cubics) {
        path.cubicTo(
            cx + c.control0X * radius, cy + c.control0Y * radius,
            cx + c.control1X * radius, cy + c.control1Y * radius,
            cx + c.anchor1X * radius, cy + c.anchor1Y * radius
        )
    }
    path.close()
}

/** Paints a [polygon] as the background of an element, cached per size. */
internal fun Modifier.polygonBackground(polygon: RoundedPolygon, color: Color): Modifier =
    drawWithCache {
        val path = Path()
        fillPath(path, polygon.cubics, size.width / 2f, size.height / 2f, min(size.width, size.height) / 2f)
        onDrawBehind { drawPath(path, color) }
    }

/** A 0..1 loop. Frozen at 0 when the system asks for reduced motion. Read the value in draw lambdas only. */
@Composable
internal fun rememberSetupClock(durationMillis: Int): State<Float> {
    return if (LocalSetupReduceMotion.current) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "SetupClock").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
            label = "SetupClockValue"
        )
    }
}

/**
 * Full-screen backdrop: a soft tonal gradient, two slow colour glows and one big shape that morphs
 * from page to page as the pager moves. Everything is drawn in a single draw pass; [position] and the
 * drift clock are only read inside it, so nothing recomposes while it moves.
 *
 * @param position the pager position as a float (page index + fraction).
 */
@Composable
internal fun SetupBackground(
    position: () -> Float,
    heroCenterY: Dp,
    blobRadius: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surface
    val top = scheme.surfaceContainerLow
    val glowA = scheme.primary
    val glowB = scheme.tertiary
    val blobFill = scheme.primaryContainer
    val blobLine = scheme.primary
    val morphs = remember { SetupShapes.buildMorphs() }
    val path = remember { Path() }
    val baseBrush = remember(base, top) { Brush.verticalGradient(listOf(top, base)) }
    val drift = rememberSetupClock(durationMillis = 40_000)

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val t = drift.value * TWO_PI
                val pos = position().coerceAtLeast(0f)

                drawRect(brush = baseBrush)

                // Glows drift a little and lean towards where the flow is heading.
                val lean = pos * 0.035f
                val aCenter = Offset(w * (0.08f + lean + 0.05f * sin(t)), h * (0.06f + 0.02f * cos(t)))
                val aRadius = w * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowA.copy(alpha = 0.18f), Color.Transparent),
                        center = aCenter,
                        radius = aRadius
                    ),
                    radius = aRadius,
                    center = aCenter
                )
                val bCenter = Offset(w * (0.95f - lean + 0.04f * cos(t)), h * (0.94f + 0.02f * sin(t)))
                val bRadius = w * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowB.copy(alpha = 0.16f), Color.Transparent),
                        center = bCenter,
                        radius = bRadius
                    ),
                    radius = bRadius,
                    center = bCenter
                )

                // The morphing hero shape.
                val toHero = pos.coerceIn(0f, 1f)
                val index = pos.toInt().coerceAtLeast(0)
                val frac = (pos - index).coerceIn(0f, 1f)
                val morph = morphs[index % morphs.size]
                val cx = w / 2f
                val cy = lerp(h * 0.5f, heroCenterY.toPx(), toHero)
                val radius = lerp(blobRadius.toPx() * 1.5f, blobRadius.toPx(), toHero)
                val spin = drift.value * 360f
                val cubics = morph.asCubics(frac)

                rotate(degrees = spin, pivot = Offset(cx, cy)) {
                    fillPath(path, cubics, cx, cy, radius)
                    drawPath(path, color = blobFill, alpha = 0.6f)
                }
                rotate(degrees = -spin * 0.7f, pivot = Offset(cx, cy)) {
                    fillPath(path, cubics, cx, cy, radius * 1.22f)
                    drawPath(
                        path,
                        color = blobLine,
                        alpha = 0.28f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
    )
}

/** Entrance progress for the i-th element of a hero (index 0 is first). Overshoot is kept for bounce. */
private fun staggered(progress: Float, index: Int): Float {
    val delay = (index * 0.1f).coerceAtMost(0.6f)
    return ((progress - delay) / (1f - delay)).coerceAtLeast(0f)
}

/** A polygon-shaped plate with an icon in the middle. */
@Composable
internal fun ShapeBadge(
    icon: Painter,
    polygon: RoundedPolygon,
    container: Color,
    content: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .polygonBackground(polygon, container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

private class SatelliteStyle(
    val dx: Float,
    val dy: Float,
    val container: Color,
    val content: Color,
    val polygon: RoundedPolygon,
    val phase: Float,
    val tilt: Float
)

/**
 * The standard page illustration: one big shaped plate with an icon, and up to four smaller plates
 * floating around it. They spring in one after another and then bob gently.
 */
@Composable
internal fun SetupHeroCluster(
    centerIcon: Painter,
    satelliteIcons: List<Painter>,
    entrance: State<Float>,
    height: Dp,
    modifier: Modifier = Modifier,
    centerContainer: Color = MaterialTheme.colorScheme.primary,
    centerContent: Color = MaterialTheme.colorScheme.onPrimary,
    centerShape: Int = 0
) {
    val scheme = MaterialTheme.colorScheme
    val polygons = SetupShapes.polygons
    val clock = rememberSetupClock(durationMillis = 6_000)
    val styles = listOf(
        SatelliteStyle(-1.30f, -0.62f, scheme.secondaryContainer, scheme.onSecondaryContainer, polygons[3], 0.00f, -8f),
        SatelliteStyle(1.32f, -0.40f, scheme.tertiaryContainer, scheme.onTertiaryContainer, polygons[1], 0.33f, 10f),
        SatelliteStyle(0.98f, 0.72f, scheme.surfaceContainerHighest, scheme.primary, polygons[5], 0.66f, -6f),
        SatelliteStyle(-1.02f, 0.70f, scheme.primaryContainer, scheme.onPrimaryContainer, polygons[2], 0.50f, 12f)
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        // The widest satellite sits 1.32 plates from the middle, so the plate has to leave room.
        val plate = minOf(height * 0.52f, maxWidth / 3.2f).coerceIn(72.dp, 132.dp)
        val satellite = plate * 0.46f

        satelliteIcons.take(styles.size).forEachIndexed { i, icon ->
            val style = styles[i]
            ShapeBadge(
                icon = icon,
                polygon = style.polygon,
                container = style.container,
                content = style.content,
                size = satellite,
                modifier = Modifier.graphicsLayer {
                    val e = staggered(entrance.value, i + 1)
                    val bob = sin((clock.value + style.phase) * TWO_PI) * 5.dp.toPx()
                    translationX = style.dx * plate.toPx()
                    translationY = style.dy * plate.toPx() + bob
                    scaleX = e
                    scaleY = e
                    alpha = e.coerceIn(0f, 1f)
                    rotationZ = style.tilt
                }
            )
        }

        Box(
            modifier = Modifier
                .size(plate)
                .graphicsLayer {
                    val e = staggered(entrance.value, 0)
                    val s = 0.55f + 0.45f * e
                    scaleX = s
                    scaleY = s
                    alpha = e.coerceIn(0f, 1f)
                    translationY = sin((clock.value + 0.2f) * TWO_PI) * 4.dp.toPx()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = sin(clock.value * TWO_PI) * 6f }
                    .polygonBackground(polygons[centerShape % polygons.size], centerContainer)
            )
            Icon(
                painter = centerIcon,
                contentDescription = null,
                tint = centerContent,
                modifier = Modifier.size(plate * 0.46f)
            )
        }
    }
}

/** A tiny fake media notification, so the "turn on notifications" step shows what you are turning on. */
@Composable
internal fun NotificationHero(
    entrance: State<Float>,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clock = rememberSetupClock(durationMillis = 5_000)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp)
                .graphicsLayer {
                    val e = staggered(entrance.value, 0)
                    alpha = e.coerceIn(0f, 1f)
                    translationY = (1f - e) * 40.dp.toPx() + sin(clock.value * TWO_PI) * 4.dp.toPx()
                }
        ) {
            // A second card peeking out underneath, for depth.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .graphicsLayer {
                        translationY = 22.dp.toPx()
                        scaleX = 0.92f
                        alpha = 0.7f
                    }
            ) {}
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_music_note_24),
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SkeletonBar(widthFraction = 0.7f, height = 12.dp, color = scheme.onSurface.copy(alpha = 0.75f))
                        SkeletonBar(widthFraction = 0.45f, height = 9.dp, color = scheme.onSurfaceVariant.copy(alpha = 0.45f))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_skip_previous_24),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Icon(
                                painter = painterResource(R.drawable.rounded_pause_24),
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Icon(
                                painter = painterResource(R.drawable.rounded_skip_next_24),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            // The bell badge that says "this is what notifications are".
            ShapeBadge(
                icon = painterResource(R.drawable.rounded_notifications_active_24),
                polygon = SetupShapes.polygons[0],
                container = scheme.primary,
                content = scheme.onPrimary,
                size = 52.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-22).dp)
                    .graphicsLayer {
                        val e = staggered(entrance.value, 2)
                        scaleX = e
                        scaleY = e
                        alpha = e.coerceIn(0f, 1f)
                        rotationZ = sin(clock.value * TWO_PI) * 8f
                    }
            )
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(CircleShape)
            .background(color)
    )
}

/** A battery that fills up. Drawn in one Canvas, the level is only read while drawing. */
@Composable
internal fun BatteryHero(
    entrance: State<Float>,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clock = rememberSetupClock(durationMillis = 3_200)
    val outline = scheme.outline
    val fill = scheme.primary

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        val batteryWidth = minOf(height * 1.7f, maxWidth * 0.62f)
        val batteryHeight = batteryWidth * 0.5f

        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(batteryWidth, batteryHeight)
                    .graphicsLayer {
                        val e = staggered(entrance.value, 0)
                        val s = 0.7f + 0.3f * e
                        scaleX = s
                        scaleY = s
                        alpha = e.coerceIn(0f, 1f)
                    }
            ) {
                val stroke = 6.dp.toPx()
                val nubWidth = 8.dp.toPx()
                val bodyWidth = size.width - nubWidth - 3.dp.toPx()
                val corner = CornerRadius(size.height * 0.28f)

                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(bodyWidth - stroke, size.height - stroke),
                    cornerRadius = corner,
                    style = Stroke(width = stroke)
                )
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(size.width - nubWidth, size.height * 0.32f),
                    size = Size(nubWidth, size.height * 0.36f),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )

                val inset = stroke + 5.dp.toPx()
                val maxFill = bodyWidth - inset * 2f
                val level = (0.2f + 0.72f * entrance.value.coerceIn(0f, 1f) +
                    0.03f * sin(clock.value * TWO_PI)).coerceIn(0.1f, 0.95f)
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(inset, inset),
                    size = Size(maxFill * level, size.height - inset * 2f),
                    cornerRadius = CornerRadius(size.height * 0.16f)
                )
            }
            Icon(
                painter = painterResource(R.drawable.rounded_music_note_24),
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier
                    .size(batteryHeight * 0.5f)
                    .offset(x = (-3).dp)
            )
        }
    }
}

private class Confetto(
    val angle: Float,
    val distance: Float,
    val size: Float,
    val colorIndex: Int,
    val kind: Int,
    val spin: Float
)

private val Confetti = List(20) { i ->
    Confetto(
        angle = i * (TWO_PI / 20f) + (i % 3) * 0.12f,
        distance = 0.78f + (i % 5) * 0.14f,
        size = 4f + (i % 4) * 2.4f,
        colorIndex = i % 4,
        kind = i % 3,
        spin = 20f + (i % 6) * 15f
    )
}

/** Confetti that bursts out of the middle when the page arrives, then just sits there looking happy. */
@Composable
internal fun ConfettiBurst(
    entrance: State<Float>,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { }
    ) {
        val p = entrance.value.coerceAtLeast(0f)
        val alpha = p.coerceIn(0f, 1f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val reach = min(size.width * 0.42f, size.height * 0.56f)
        Confetti.forEach { c ->
            val d = c.distance * reach * p
            val x = cx + cos(c.angle) * d
            val y = cy + sin(c.angle) * d
            val color = colors[c.colorIndex]
            val s = c.size.dp.toPx()
            when (c.kind) {
                0 -> drawCircle(color = color, radius = s * 0.6f, center = Offset(x, y), alpha = alpha)
                else -> rotate(degrees = c.spin * p, pivot = Offset(x, y)) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x - s * 0.5f, y - s * 0.5f),
                        size = Size(s, if (c.kind == 1) s else s * 0.45f),
                        cornerRadius = CornerRadius(s * 0.2f),
                        alpha = alpha
                    )
                }
            }
        }
    }
}

/** A wavy line that scrolls sideways. Phase is read in the draw block only. */
@Composable
internal fun WaveLine(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 5.dp,
    amplitude: Dp = 5.dp,
    waves: Float = 6f
) {
    val phase = rememberSetupClock(durationMillis = 2_600)
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val amp = amplitude.toPx()
        val cy = size.height / 2f
        val samples = 90
        val shift = phase.value * TWO_PI
        val path = Path()
        for (i in 0..samples) {
            val x = size.width * i / samples
            val y = cy + amp * sin(shift + (i.toFloat() / samples) * waves * TWO_PI)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** The welcome art: the existing Material You illustration in a big rounded frame, with floating plates. */
@Composable
internal fun WelcomeArt(
    entrance: State<Float>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clock = rememberSetupClock(durationMillis = 5_400)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .graphicsLayer {
                    val e = staggered(entrance.value, 0)
                    val s = 0.92f + 0.08f * e
                    scaleX = s
                    scaleY = s
                    alpha = e.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(40.dp))
        ) {
            MaterialYouVectorDrawable(
                modifier = Modifier.fillMaxSize(),
                drawableResId = R.drawable.welcome_art
            )
            WaveLine(
                color = scheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
            )
        }
        ShapeBadge(
            icon = painterResource(R.drawable.rounded_music_note_24),
            polygon = SetupShapes.polygons[0],
            container = scheme.primary,
            content = scheme.onPrimary,
            size = 68.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-20).dp)
                .graphicsLayer {
                    val e = staggered(entrance.value, 3)
                    scaleX = e
                    scaleY = e
                    alpha = e.coerceIn(0f, 1f)
                    translationY = sin(clock.value * TWO_PI) * 5.dp.toPx()
                    rotationZ = 10f
                }
        )
        ShapeBadge(
            icon = painterResource(R.drawable.round_favorite_24),
            polygon = SetupShapes.polygons[3],
            container = scheme.tertiaryContainer,
            content = scheme.onTertiaryContainer,
            size = 52.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-8).dp, y = 18.dp)
                .graphicsLayer {
                    val e = staggered(entrance.value, 4)
                    scaleX = e
                    scaleY = e
                    alpha = e.coerceIn(0f, 1f)
                    translationY = sin((clock.value + 0.5f) * TWO_PI) * 5.dp.toPx()
                }
        )
    }
}
