package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/**
 * The one glass modifier. Every glass surface in the app goes through here, which is what keeps
 * them consistent — and what lets the whole kit fall back per device in one place:
 *
 * - [GlassTier.Refractive]: blur + lens refraction + a shaded rim that follows [GlassLight].
 * - [GlassTier.Frosted]: heavier blur, flat rim, no lens (API 31–32, battery saver).
 * - [GlassTier.Solid]: painted frosted surface, no backdrop sampling (API 30).
 *
 * Performance: every lambda handed to `drawBackdrop` is remembered against the inputs it closes
 * over. `drawBackdrop`'s element compares its lambdas by identity, so fresh lambdas on each
 * recomposition (what the previous kit did) made every glass element rebuild its RenderEffect
 * chain — blur and a RuntimeShader — whenever its parent recomposed, even with nothing changed.
 *
 * @param tint surface fill override. `null` uses the palette's neutral glass tint; pass a scheme
 *   colour (e.g. a button's container colour) to keep a control's identity — it is laid down at
 *   glass strength, not opaque.
 * @param pressed optional press state for light-spill and squash; see [InteractiveHighlight].
 * @param layerBlock transform for the glass body; the backdrop is counter-transformed so the
 *   refracted image doesn't stretch along with it.
 */
@Composable
fun Modifier.glass(
    shape: CornerBasedShape,
    material: GlassMaterial = GlassMaterial.Regular,
    tint: Color? = null,
    backdrop: Backdrop = LocalAppBackdrop.current,
    shadow: Boolean = material.castsShadow,
    pressed: InteractiveHighlight? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null
): Modifier {
    val tier = LocalGlassTier.current
    val palette = LocalGlassPalette.current
    val light = LocalGlassLight.current
    val intensity = LocalGlassIntensity.current

    val fill = remember(tint, palette, material, intensity, tier) {
        resolveGlassFill(tint, palette, material, intensity, tier)
    }

    if (tier == GlassTier.Solid) {
        return this.solidGlass(shape, palette, fill, shadow, pressed, layerBlock)
    }

    val shapeProvider = remember(shape) { { shape } }
    val effects: BackdropEffectScope.() -> Unit = remember(material, tier) {
        if (tier == GlassTier.Refractive) {
            {
                vibrancy()
                blur(material.blur.toPx())
                lens(
                    material.lensHeight.toPx(),
                    material.lensAmount.toPx(),
                    depthEffect = material.depthEffect,
                    chromaticAberration = material.chromaticAberration
                )
            }
        } else {
            {
                vibrancy()
                blur(material.blur.toPx() * material.frostedBlurBoost)
            }
        }
    }
    val highlight: () -> Highlight? = remember(tier, palette, light, pressed) {
        if (tier == GlassTier.Refractive) {
            if (pressed != null) {
                {
                    // Rim thickens under the finger. At rest this is the cached per-angle
                    // Highlight; only an active press allocates.
                    val progress = pressed.pressProgress
                    if (progress < 0.01f) {
                        light.highlight(palette)
                    } else {
                        light.highlight(palette, alpha = 1f, width = (0.5f + 0.75f * progress).dp)
                    }
                }
            } else {
                { light.highlight(palette) }
            }
        } else {
            val rim = Highlight(style = HighlightStyle.Plain(color = palette.rim))
            ({ rim })
        }
    }
    val shadowProvider: (() -> Shadow?)? = remember(shadow, palette) {
        if (shadow) {
            val s = Shadow(radius = 18.dp, color = palette.shadow)
            ({ s })
        } else {
            null
        }
    }
    val surface: DrawScope.() -> Unit = remember(fill) { { drawRect(fill) } }
    val front: (DrawScope.() -> Unit)? = remember(pressed, palette) {
        pressed?.let { interaction -> lightSpill(interaction, palette) }
    }

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = shapeProvider,
        effects = effects,
        highlight = highlight,
        shadow = shadowProvider,
        layerBlock = layerBlock,
        onDrawSurface = surface,
        onDrawFront = front
    )
}

internal fun resolveGlassFill(
    tint: Color?,
    palette: GlassPalette,
    material: GlassMaterial,
    intensity: Float,
    tier: GlassTier
): Color {
    val scale = transparencyScale(intensity) * material.tintWeight
    return when (tier) {
        GlassTier.Solid -> tint?.copy(alpha = 0.92f) ?: palette.solidTint
        // No lens means less optical texture, so lean on the tint a little more for legibility.
        GlassTier.Frosted -> (tint?.copy(alpha = tint.alpha * 0.5f) ?: palette.tint).scaleAlpha(scale * 1.2f)
        GlassTier.Refractive -> (tint?.copy(alpha = tint.alpha * 0.42f) ?: palette.tint).scaleAlpha(scale)
    }
}

/**
 * A soft pool of light under the finger while a glass control is pressed. The brush is built once
 * per element size, centred on the origin, and moved with a translate — so tracking the finger
 * allocates nothing per frame.
 */
private fun lightSpill(
    interaction: InteractiveHighlight,
    palette: GlassPalette
): DrawScope.() -> Unit {
    var cachedRadius = -1f
    var brush: Brush? = null
    val glow = if (palette.isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.45f)
    return {
        val progress = interaction.pressProgress
        if (progress > 0.01f) {
            val radius = size.minDimension * 1.1f
            if (radius != cachedRadius || brush == null) {
                cachedRadius = radius
                brush = Brush.radialGradient(
                    0f to glow,
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = radius
                )
            }
            val p = interaction.position
            translate(p.x, p.y) {
                drawCircle(brush!!, radius = radius, center = Offset.Zero, alpha = progress)
            }
        }
    }
}

/**
 * The API 30 fallback. Tint, a top sheen standing in for the specular highlight, and a hairline
 * rim, all cached per size via [drawWithCache].
 */
private fun Modifier.solidGlass(
    shape: Shape,
    palette: GlassPalette,
    fill: Color,
    shadow: Boolean,
    pressed: InteractiveHighlight?,
    layerBlock: (GraphicsLayerScope.() -> Unit)?
): Modifier {
    val transformed = if (layerBlock != null) this.graphicsLayer(layerBlock) else this
    val shadowed = if (shadow) {
        transformed.shadow(elevation = 8.dp, shape = shape, clip = false, ambientColor = palette.shadow, spotColor = palette.shadow)
    } else {
        transformed
    }
    val sheen = palette.solidSheen
    val rim = palette.rim
    return shadowed.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply { addOutline(outline) }
        val sheenBrush = Brush.verticalGradient(
            0f to sheen,
            0.45f to Color.Transparent,
            startY = 0f,
            endY = size.height
        )
        val rimStroke = Stroke(width = 1.dp.toPx())
        onDrawWithContent {
            drawOutline(outline, fill)
            clipPath(clip) {
                drawRect(sheenBrush)
                if (pressed != null) {
                    val progress = pressed.pressProgress
                    if (progress > 0.01f) drawRect(Color.White.copy(alpha = 0.10f * progress))
                }
            }
            drawOutline(outline, rim, style = rimStroke)
            drawContent()
        }
    }
}

/**
 * Glass surfaces are transparent, so they need a *tint* rather than a fill. Kept for call sites
 * that paint their own glass-adjacent surfaces.
 */
@Composable
fun glassTint(alpha: Float = 0.28f): Color {
    val palette = LocalGlassPalette.current
    return palette.tint.copy(alpha = (alpha * transparencyScale(glassTransparency()) * 1.6f).coerceIn(0f, 1f))
}
