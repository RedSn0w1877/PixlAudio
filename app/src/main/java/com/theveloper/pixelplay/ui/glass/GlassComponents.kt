package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** Maps the legacy per-call-site `effectScale` knob onto a [GlassMaterial] size class. */
internal fun materialForScale(effectScale: Float): GlassMaterial = when {
    effectScale < 1.25f -> GlassMaterial.Thin
    effectScale < 2.5f -> GlassMaterial.Regular
    else -> GlassMaterial.Thick
}

/**
 * Drop-in replacement for `Modifier.clip(shape).background(color)`.
 *
 * Under glass the surface refracts and [color] becomes the glass tint; otherwise it is an ordinary
 * opaque fill. Exists so existing call sites convert with a one-line swap.
 *
 * @param effectScale size class hint: < 1.25 small control, < 2.5 card/pill, otherwise panel.
 * @param tintAlpha extra multiplier on how much of [color] survives as tint (1 = kit default).
 */
@Composable
fun Modifier.glassPanel(
    shape: CornerBasedShape,
    color: Color,
    effectScale: Float = 1f,
    tintAlpha: Float = 0.45f,
    shadow: Boolean = false
): Modifier {
    if (!isGlassEnabled) {
        return this.clip(shape).background(color)
    }
    // The kit's default tint strength corresponds to the old 0.45 tintAlpha.
    val tint = color.copy(alpha = (color.alpha * tintAlpha / 0.45f).coerceIn(0f, 1f))
    return this.glass(
        shape = shape,
        material = materialForScale(effectScale),
        tint = tint,
        shadow = shadow
    )
}

/**
 * The base glass panel. Falls back to a normal tonal [Surface] under [AppUiStyle.Material3].
 *
 * @param tint colour laid over the refracted backdrop; `null` uses the palette's neutral glass.
 * @param effectScale size class hint — see [glassPanel].
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    tint: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    effectScale: Float = 1f,
    shadow: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    if (!isGlassEnabled) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor
        ) {
            Box(contentAlignment = contentAlignment, content = content)
        }
        return
    }

    Box(
        modifier = modifier.glass(
            shape = shape,
            material = materialForScale(effectScale),
            tint = tint,
            shadow = shadow
        ),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * A circular glass icon button — the settings cog, transport controls, etc. Squashes toward the
 * finger and fills with light while pressed.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit
) {
    if (!isGlassEnabled) {
        val clickable = Modifier.glassClickable(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            interactionSource = interactionSource
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Surface(
                modifier = modifier.size(size).then(clickable),
                shape = CircleShape,
                color = containerColor
            ) {
                Box(contentAlignment = Alignment.Center, content = content)
            }
        }
        return
    }

    val animationScope = rememberCoroutineScope()
    val interaction = remember(animationScope) { InteractiveHighlight(animationScope) }
    val squash: GraphicsLayerScope.() -> Unit = remember(interaction) {
        {
            val progress = interaction.pressProgress
            val scaleVal = lerp(1f, 1.12f, progress)
            val maxOffset = this.size.minDimension.coerceAtLeast(1f)
            val offset = interaction.offset
            translationX = maxOffset * tanh(0.08f * offset.x / maxOffset)
            translationY = maxOffset * tanh(0.08f * offset.y / maxOffset)
            val maxDragScale = 0.2f
            val offsetAngle = atan2(offset.y, offset.x)
            val maxDim = this.size.maxDimension.coerceAtLeast(1f)
            scaleX = scaleVal + maxDragScale * abs(cos(offsetAngle) * offset.x / maxDim)
            scaleY = scaleVal + maxDragScale * abs(sin(offsetAngle) * offset.y / maxDim)
        }
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .size(size)
                .glass(
                    shape = CircleShape,
                    material = GlassMaterial.Thin,
                    tint = tint,
                    pressed = interaction,
                    layerBlock = squash
                )
                .then(interaction.modifier)
                .glassClickable(onClick = onClick, enabled = enabled, shape = CircleShape, interactionSource = interactionSource),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/** A pill-shaped glass button with a label/icon row. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(percent = 50),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    tint: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    if (!isGlassEnabled) {
        val clickable = Modifier.glassClickable(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            interactionSource = interactionSource
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Surface(
                modifier = modifier.then(clickable),
                shape = shape,
                color = containerColor
            ) {
                Row(
                    modifier = Modifier.padding(contentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
        return
    }

    val animationScope = rememberCoroutineScope()
    val interaction = remember(animationScope) { InteractiveHighlight(animationScope) }
    val squash: GraphicsLayerScope.() -> Unit = remember(interaction) {
        {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val progress = interaction.pressProgress
            val scaleVal = lerp(1f, 1f + 4f.dp.toPx() / h, progress)
            val maxOffset = size.minDimension.coerceAtLeast(1f)
            val offset = interaction.offset
            translationX = maxOffset * tanh(0.06f * offset.x / maxOffset)
            translationY = maxOffset * tanh(0.06f * offset.y / maxOffset)
            val maxDragScale = 4f.dp.toPx() / h
            val offsetAngle = atan2(offset.y, offset.x)
            val maxDim = size.maxDimension.coerceAtLeast(1f)
            scaleX = scaleVal + maxDragScale * abs(cos(offsetAngle) * offset.x / maxDim) * (w / h).fastCoerceAtMost(1.5f)
            scaleY = scaleVal + maxDragScale * abs(sin(offsetAngle) * offset.y / maxDim) * (h / w).fastCoerceAtMost(1.5f)
        }
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .glass(
                    shape = shape,
                    material = GlassMaterial.Regular,
                    tint = tint,
                    pressed = interaction,
                    layerBlock = squash
                )
                .then(interaction.modifier)
                .glassClickable(onClick = onClick, enabled = enabled, shape = shape, interactionSource = interactionSource),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

/** A glass card for list rows and content blocks. Non-interactive by default. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(20.dp),
    tint: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clickable = if (onClick != null) {
        Modifier.glassClickable(onClick = onClick, enabled = true, shape = shape)
    } else {
        Modifier
    }
    GlassSurface(
        modifier = modifier.then(clickable),
        shape = shape,
        tint = tint,
        containerColor = containerColor,
        effectScale = 1.5f,
        contentAlignment = Alignment.CenterStart,
        content = content
    )
}
