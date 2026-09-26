package com.theveloper.pixelplay.presentation.components

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/**
 * Placeholder shimmer: a linear gradient (base, highlight, base) running from the top-left corner to
 * `(t, t)`, with `t` animating 0..1000 px over 1000 ms after a 200 ms pause, repeating.
 *
 * Performance: the animated value is read only in draw, so the box never recomposes while it
 * shimmers, and nothing is allocated per frame. The gradient is built once per colour pair as a
 * unit gradient (0,0)-(1,1) and scaled to `(t, t)` through its local matrix, which draws exactly the
 * same pixels as a fresh `Brush.linearGradient(start = Offset.Zero, end = Offset(t, t))`.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    // Use MaterialTheme colors for proper dark/light mode support
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlightColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val gradient = remember(baseColor, highlightColor) {
        ShimmerGradient(baseColor, highlightColor)
    }

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = 200),
        ),
        label = "shimmerTranslate"
    )

    Box(
        modifier = modifier.drawBehind {
            val t = translateAnim.value
            if (t <= 0f) {
                // A zero-length clamped gradient draws its last colour, which is the base colour.
                drawRect(color = baseColor)
            } else {
                drawRect(brush = gradient.brushFor(t))
            }
        }
    )
}

private class ShimmerGradient(
    baseColor: androidx.compose.ui.graphics.Color,
    highlightColor: androidx.compose.ui.graphics.Color,
) {
    private val shader: Shader = LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(baseColor.toArgb(), highlightColor.toArgb(), baseColor.toArgb()),
        null,
        Shader.TileMode.CLAMP
    )
    private val matrix = Matrix()
    private var lastScale = Float.NaN

    val brush: ShaderBrush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }

    fun brushFor(t: Float): ShaderBrush {
        if (t != lastScale) {
            lastScale = t
            matrix.setScale(t, t)
            shader.setLocalMatrix(matrix)
        }
        return brush
    }
}
