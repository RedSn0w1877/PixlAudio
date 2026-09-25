package com.theveloper.pixelplay.ui.glass

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import timber.log.Timber

/**
 * The soft frosted strip under a screen's top chrome (one per screen): content scrolling up
 * beneath the top bar blurs and fades into the surface colour instead of colliding with it.
 *
 * It samples the screen's own recording ([backdrop]) with a 4dp blur, masked by
 * `1 − smoothstep(h/2, h, y)` — fully frosted down to half its height, clear at the bottom edge —
 * and lays `surface @ .80` over it with the same falloff.
 *
 * - **API 33+:** the falloff is an AGSL shader, so the curve is exact at any height. One shader
 *   per use (mask and wash), built once; per frame only the height uniform is set.
 * - **Below 33:** a nine-stop vertical gradient sampled from the same curve, drawn the same way.
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
    val mask = remember { EdgeFalloff.create(Color.Black) }
    val wash = remember(surface) { EdgeFalloff.create(surface) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { if (blurPx > 0f) blur(blurPx) },
                highlight = null,
                shadow = null,
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                    mask.draw(this, BlendMode.DstIn)
                },
                onDrawSurface = { wash.draw(this, BlendMode.SrcOver) }
            )
    )
}

/**
 * [color] fading out along `1 − smoothstep(0.5, 1, y/h)`: full strength down to the middle,
 * easing to nothing at the bottom edge.
 */
private sealed class EdgeFalloff {
    abstract fun draw(scope: DrawScope, blendMode: BlendMode)

    /** AGSL: exact curve, height set per draw (no allocation). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class Shader(color: Color) : EdgeFalloff() {
        private val shader = android.graphics.RuntimeShader(Source).apply {
            // Plain (non-`layout(color)`) uniform, premultiplied in the shader.
            setFloatUniform("tint", color.red, color.green, color.blue, color.alpha)
            setFloatUniform("h", 1f)
        }
        private val brush = ShaderBrush(shader)
        private var lastHeight = -1f

        override fun draw(scope: DrawScope, blendMode: BlendMode) {
            val h = scope.size.height
            if (h <= 0f) return
            if (h != lastHeight) {
                shader.setFloatUniform("h", h)
                lastHeight = h
            }
            scope.drawRect(brush, blendMode = blendMode)
        }
    }

    /** Nine-stop gradient approximation for API 30–32. */
    private class Gradient(color: Color) : EdgeFalloff() {
        private val brush: Brush = run {
            val stops = Array(9) { i ->
                val y = 0.5f + 0.5f * i / 8f
                val t = ((y - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                y to color.copy(alpha = color.alpha * (1f - smooth))
            }
            Brush.verticalGradient(colorStops = arrayOf(0f to color) + stops)
        }

        override fun draw(scope: DrawScope, blendMode: BlendMode) {
            scope.drawRect(brush, blendMode = blendMode)
        }
    }

    companion object {
        private const val Source = """
uniform float4 tint;
uniform float h;

half4 main(float2 coord) {
    float a = 1.0 - smoothstep(h * 0.5, h, coord.y);
    return half4(tint.rgb * tint.a, tint.a) * a;
}"""

        fun create(color: Color): EdgeFalloff {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { return Shader(color) }
                    .onFailure { Timber.w(it, "Scroll-edge AGSL shader failed; using gradient") }
            }
            return Gradient(color)
        }
    }
}
