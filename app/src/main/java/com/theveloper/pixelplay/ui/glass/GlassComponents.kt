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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Glass surfaces are transparent, so they need a *tint* rather than a fill: enough colour to keep
 * content legible over unpredictable backdrops without hiding the refraction underneath.
 *
 * Derived from the Material 3 scheme rather than hardcoded white/black, which is what keeps the
 * glass looking like it belongs to the album-art palette the rest of the app is themed from —
 * the user asked for glass that is still "Material 3 tinted", and this is the piece that does it.
 */
@Composable
fun glassTint(alpha: Float = 0.28f): Color =
    MaterialTheme.colorScheme.surface.copy(
        alpha = (alpha * androidx.compose.ui.util.lerp(0.3f, 3f, glassTransparency())).coerceIn(0f, 1f)
    )

/**
 * Drop-in replacement for `Modifier.clip(shape).background(color)`.
 *
 * Under glass the surface refracts and [color] becomes a translucent tint; otherwise it is an
 * ordinary opaque fill. Exists so existing call sites convert with a one-line swap instead of
 * being restructured around a wrapper composable.
 *
 * @param effectScale scale the refraction to the element's size — see [glassEffects]. 1 (the
 *   default) matches the reference's own button scale; go bigger for large surfaces.
 * @param tintAlpha how much of [color] to keep. Below ~0.2 icons start losing contrast against
 *   busy artwork.
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
    val backdrop = LocalAppBackdrop.current
    val effects = glassEffects(effectScale)
    val tint = color.copy(
        alpha = (color.alpha * tintAlpha * androidx.compose.ui.util.lerp(0.3f, 2.2f, glassTransparency())).coerceIn(0f, 1f)
    )
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        // No depthEffect/chromaticAberration — matches the reference's LiquidButton exactly.
        effects = {
            vibrancy()
            blur(effects.blurRadius.toPx())
            lens(effects.refractionHeight.toPx(), effects.refractionAmount.toPx())
        },
        highlight = { Highlight.Default },
        shadow = if (shadow) {
            { Shadow() }
        } else {
            null
        },
        onDrawSurface = { drawRect(tint) }
    )
}

/**
 * The base glass panel. Falls back to a normal tonal [Surface] under [AppUiStyle.Material3].
 *
 * @param tint colour laid over the refracted backdrop. Keep it translucent.
 * @param effectScale scales the refraction to the component's size — see [glassEffects].
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

    val backdrop = LocalAppBackdrop.current
    val effects = glassEffects(effectScale)
    val resolvedTint = tint ?: glassTint()

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            // No depthEffect/chromaticAberration — matches the reference's LiquidButton exactly.
            effects = {
                vibrancy()
                blur(effects.blurRadius.toPx())
                lens(effects.refractionHeight.toPx(), effects.refractionAmount.toPx())
            },
            highlight = { Highlight.Default },
            shadow = if (shadow) {
                { Shadow() }
            } else {
                null
            },
            onDrawSurface = { drawRect(resolvedTint) }
        ),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * A circular glass icon button — the settings cog, transport controls, etc. Same size class as
 * the reference's `LiquidButton` (default 48dp), so it uses [glassEffects] at scale 1 unscaled.
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
            GlassSurface(
                modifier = modifier.size(size).then(clickable),
                shape = CircleShape,
                tint = tint,
                containerColor = containerColor,
                effectScale = 0.4f,
                content = content
            )
        }
        return
    }

    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val backdrop = LocalAppBackdrop.current
    val effects = glassEffects()
    val resolvedTint = tint ?: glassTint()

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .size(size)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    // No depthEffect/chromaticAberration, no extra multiplier — matches the
                    // reference's LiquidButton exactly.
                    effects = {
                        vibrancy()
                        blur(effects.blurRadius.toPx())
                        lens(effects.refractionHeight.toPx(), effects.refractionAmount.toPx())
                    },
                    layerBlock = {
                        val progress = interactiveHighlight.pressProgress
                        val scaleVal = lerp(1f, 1.15f, progress)
                        val maxOffset = this.size.minDimension.coerceAtLeast(1f)
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(0.08f * offset.x / maxOffset)
                        translationY = maxOffset * tanh(0.08f * offset.y / maxOffset)
                        val maxDragScale = 0.25f
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scaleVal + maxDragScale * abs(cos(offsetAngle) * offset.x / this.size.maxDimension.coerceAtLeast(1f))
                        scaleY = scaleVal + maxDragScale * abs(sin(offsetAngle) * offset.y / this.size.maxDimension.coerceAtLeast(1f))
                    },
                    onDrawSurface = {
                        drawRect(resolvedTint)
                    }
                )
                .then(interactiveHighlight.modifier)
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
            GlassSurface(
                modifier = modifier.then(clickable),
                shape = shape,
                tint = tint,
                containerColor = containerColor,
                effectScale = 0.55f
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
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val backdrop = LocalAppBackdrop.current
    val effects = glassEffects()
    val resolvedTint = tint ?: glassTint()

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    // No depthEffect/chromaticAberration, no extra multiplier — matches the
                    // reference's LiquidButton exactly.
                    effects = {
                        vibrancy()
                        blur(effects.blurRadius.toPx())
                        lens(effects.refractionHeight.toPx(), effects.refractionAmount.toPx())
                    },
                    layerBlock = {
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val progress = interactiveHighlight.pressProgress
                        val scaleVal = lerp(1f, 1f + 4f.dp.toPx() / h, progress)
                        val maxOffset = size.minDimension.coerceAtLeast(1f)
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(0.06f * offset.x / maxOffset)
                        translationY = maxOffset * tanh(0.06f * offset.y / maxOffset)
                        val maxDragScale = 4f.dp.toPx() / h
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scaleVal + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension.coerceAtLeast(1f)) * (w / h).fastCoerceAtMost(1.5f)
                        scaleY = scaleVal + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension.coerceAtLeast(1f)) * (h / w).fastCoerceAtMost(1.5f)
                    },
                    onDrawSurface = {
                        drawRect(resolvedTint)
                    }
                )
                .then(interactiveHighlight.modifier)
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
