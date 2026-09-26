package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * "Lens bloom" progress (0..1, may overshoot) that kit sections use as their refraction multiplier.
 * A sheet or screen entrance provides its enter animation here so every section inside it blooms
 * its lens in with it. Defaults to fully bloomed.
 */
val LocalLensBloom = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * A round glass orb framing an [icon] (ported from NexHome). [glow] (0..1, read in the draw phase)
 * floods the orb with [accent] and lights its rim. Refracts [backdrop] and receives light.
 *
 * PixlAudio changes: the glow wash is a cached brush drawn in its own child layer (NexHome built a
 * radial gradient on every draw inside the glass node, re-recording the lens each frame of a glow
 * change — and GlassSettingRow keeps its glow on permanently); the base tint comes from the palette.
 */
@Composable
fun GlassIconOrb(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = Color.White,
    size: Dp = 40.dp,
    glow: () -> Float = { 0f },
    iconTint: Color = accent,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val receiver = rememberGlassLightReceiver()
    val capability = LocalGlassCapability.current
    val orbSurface = LocalGlassPalette.current.orbSurface
    val wash = remember(accent) { OrbGlowWash(accent) }
    val orbSize = size
    Box(
        modifier
            .size(orbSize)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    val d = this.size.minDimension
                    if (capability.hasLens) {
                        lens(d * 0.18f, d * 0.36f, depthEffect = true)
                    } else {
                        blur(FrostedBlur.toPx())
                    }
                },
                highlight = {
                    val g = glow().fastCoerceIn(0f, 1f)
                    receiver.highlight(
                        Highlight(
                            width = androidx.compose.ui.unit.lerp(0.5.dp, 1.5.dp, g),
                            style = HighlightStyle.Default(
                                color = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.5f), accent, g),
                                falloff = 1.5f,
                            ),
                        )
                    )
                },
                shadow = {
                    val g = glow().fastCoerceIn(0f, 1f)
                    if (g <= 0.01f) null else Shadow(radius = orbSize * 0.45f, color = accent.copy(alpha = 0.55f), alpha = g)
                },
                onDrawSurface = {
                    drawRect(orbSurface)
                },
            )
            .then(receiver.modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (accent.isSpecified) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .graphicsLayer()
                    .drawBehind { wash.draw(this, glow().fastCoerceIn(0f, 1f)) }
            )
        }
        GlassIcon(icon, tint = iconTint, size = orbSize * 0.5f)
    }
}

/**
 * The orb's additive accent wash (NexHome values: `accent@0.40g → accent@0.08g` at (0.5w, 0.62h),
 * radius `maxDim·0.75`). The brush is built at full glow once per size; the glow level is applied as
 * draw alpha, which gives the same premultiplied result without allocating per frame.
 */
private class OrbGlowWash(private val accent: Color) {
    private var cachedSize = Size.Zero
    private var brush: Brush? = null

    fun draw(scope: DrawScope, glow: Float) {
        if (glow <= 0.01f) return
        val s = scope.size
        if (s.width <= 0f || s.height <= 0f) return
        var b = brush
        if (b == null || s != cachedSize) {
            cachedSize = s
            b = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.40f), accent.copy(alpha = 0.08f)),
                center = Offset(s.width * 0.5f, s.height * 0.62f),
                radius = s.maxDimension * 0.75f,
            )
            brush = b
        }
        scope.drawRect(b, alpha = glow, blendMode = BlendMode.Plus)
    }
}

/**
 * A soft additive radial accent wash for glass surface layers (NexHome `AccentWash`). The brush is
 * cached per size, so redraws (scrolling, animations) never allocate; the intensity is passed per
 * draw. Keep levels at NexHome's approved values (0.26 / 0.16 on heroes, 0.24 on cards).
 */
class AccentWash(
    private val color: Color,
    private val centerX: Float = 0.12f,
    private val centerY: Float = 0.1f,
    private val radiusFraction: Float = 0.95f,
) {
    private var cachedSize = Size.Zero
    private var brush: Brush? = null

    fun draw(scope: DrawScope, alpha: Float) {
        if (alpha <= 0.005f) return
        val s = scope.size
        if (s.width <= 0f || s.height <= 0f) return
        var b = brush
        if (b == null || s != cachedSize) {
            cachedSize = s
            b = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = Offset(s.width * centerX, s.height * centerY),
                radius = s.maxDimension * radiusFraction,
            )
            brush = b
        }
        scope.drawRect(b, alpha = alpha.coerceAtMost(1f), blendMode = BlendMode.Plus)
    }
}

/** Slim 3 dp fill bar along the bottom edge (NexHome `drawGlassFillBar`); draw it in a child layer. */
fun DrawScope.drawGlassFillBar(fraction: Float, accent: Color, inset: Dp = 16.dp, bottom: Dp = 9.dp) {
    val h = 3.dp.toPx()
    val left = inset.toPx()
    val width = size.width - left * 2f
    if (width <= 0f) return
    val top = size.height - bottom.toPx() - h
    val radius = androidx.compose.ui.geometry.CornerRadius(h / 2f)
    drawRoundRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(left, top), size = Size(width, h), cornerRadius = radius)
    val f = fraction.coerceIn(0f, 1f)
    if (f > 0.001f) {
        drawRoundRect(accent, topLeft = Offset(left, top), size = Size(width * f, h), cornerRadius = radius)
    }
}

// ---------------------------------------------------------------------------------------------
// Shared internals for kit controls (internal, prefixed "kit" to avoid clashes).
// ---------------------------------------------------------------------------------------------

/** White glow tinted 45 % toward [accent] (plain white when unspecified). */
internal fun kitGlowColor(accent: Color): Color =
    if (accent.isSpecified) androidx.compose.ui.graphics.lerp(Color.White, accent, 0.45f) else Color.White

/** Remembers an [InteractiveHighlight] whose glow follows the latest [accent]. */
@Composable
internal fun rememberKitHighlight(accent: Color): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    val currentAccent by rememberUpdatedState(accent)
    return remember(scope) { InteractiveHighlight(scope, color = { kitGlowColor(currentAccent) }) }
}

/** Lens multiplier: glass thickens under the finger. */
internal fun kitLensFactor(highlight: InteractiveHighlight): Float =
    lerp(1f, LiquidMotion.LensThicken, highlight.pressProgress.fastCoerceIn(0f, 1f))

/**
 * LiquidButton-style jelly: water swell to [pressScale] + tanh follow toward the finger and an
 * anisotropic stretch along the drag direction. [extraScale] multiplies the result (pops, bounces).
 */
internal fun GraphicsLayerScope.kitJelly(
    highlight: InteractiveHighlight,
    pressScale: Float,
    extraScale: Float = 1f,
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val scale = lerp(1f, pressScale, highlight.swell) * extraScale
    val maxOffset = size.minDimension
    val offset = highlight.offset
    translationX = maxOffset * tanh(LiquidMotion.JellyFollow * offset.x / maxOffset)
    translationY = maxOffset * tanh(LiquidMotion.JellyFollow * offset.y / maxOffset)
    val angle = atan2(offset.y, offset.x)
    scaleX = scale + LiquidMotion.JellyStretch *
        abs(cos(angle) * offset.x / size.maxDimension).fastCoerceAtMost(1f) *
        (width / height).fastCoerceAtMost(1f)
    scaleY = scale + LiquidMotion.JellyStretch *
        abs(sin(angle) * offset.y / size.maxDimension).fastCoerceAtMost(1f) *
        (height / width).fastCoerceAtMost(1f)
}
