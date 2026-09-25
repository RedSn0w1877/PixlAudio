package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/**
 * The soft frosted strip under a screen's top chrome (one per screen): the content scrolling up
 * beneath the top bar blurs and fades into the surface colour instead of colliding with it.
 *
 * It samples the screen's own recording ([backdrop]) with a 4dp blur, masked by a smoothstep
 * falloff — fully frosted down to half its height, clear at the bottom edge — and lays
 * `surface @ .80` over it with the same falloff. The mask is a multi-stop gradient drawn with
 * [BlendMode.DstIn] inside the backdrop layer, which gives the same curve as an AGSL
 * `smoothstep(h, h/2, y)` mask on every API level, so there is no separate fallback path.
 *
 * Renders nothing in Material 3 mode.
 *
 * @param height the strip height; defaults to the status bar plus 64dp.
 */
@Composable
fun ScrollEdgeEffect(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
) {
    if (!isGlassEnabled) return
    val recipe = resolveRecipe(GlassRole.ScrollEdge)
    val blurPx = with(LocalDensity.current) { recipe.blur.toPx() }
    val surface = recipe.tint
    val mask = remember { smoothstepMask(Color.Black) }
    val surfaceWash = remember(surface) { smoothstepMask(surface) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = { if (blurPx > 0f) blur(blurPx) },
                highlight = null,
                shadow = null,
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                },
                onDrawSurface = { drawSurfaceWash(surfaceWash) }
            )
    )
}

private fun DrawScope.drawSurfaceWash(brush: Brush) {
    drawRect(brush)
}

/**
 * A vertical gradient of [color] following `1 − smoothstep(0.5, 1, y/h)`: full strength down to
 * the middle, easing out to nothing at the bottom. Sampled at nine stops, which is visually
 * indistinguishable from the analytic curve at this height.
 */
private fun smoothstepMask(color: Color): Brush {
    val stops = Array(9) { i ->
        val y = 0.5f + 0.5f * i / 8f
        val t = ((y - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        y to color.copy(alpha = color.alpha * (1f - smooth))
    }
    return Brush.verticalGradient(colorStops = arrayOf(0f to color) + stops)
}
