package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.theveloper.pixelplay.BuildConfig
import kotlin.math.roundToInt
import timber.log.Timber

// ─── Placement ────────────────────────────────────────────────────────────────────────────────

/** How a glass component should render at its current position. See [glassPlacement]. */
enum class GlassPlacement {
    /** Material 3 mode: the component's normal M3 look, untouched. */
    Material,

    /** Content layer: an opaque tonal surface (plus a faint rim). No `drawBackdrop`. */
    Tonal,

    /** On another piece of glass: a translucent fill, never glass on glass. */
    Fill,

    /** Real glass, sampling [LocalAppBackdrop]. */
    Glass
}

/**
 * Resolves where the calling component sits in the glass layer model:
 *
 * - Material 3 mode → [GlassPlacement.Material].
 * - [GlassLayer.OnGlass] (inside a sheet or a [GlassGroup]) → [GlassPlacement.Fill].
 * - Nothing to sample ([LocalAppBackdrop] is empty, which is what the content layer provides) →
 *   [GlassPlacement.Tonal]: there is nothing to refract, so paying for an offscreen layer and a
 *   blur would only buy a tint.
 * - Otherwise → [GlassPlacement.Glass]. That includes a component inside a [GlassLayer.Content]
 *   scope that was explicitly handed a real backdrop below it — a screen giving its own top bar a
 *   sibling recording to refract, which is chrome in all but name.
 */
@Composable
fun glassPlacement(): GlassPlacement {
    if (!isGlassEnabled) return GlassPlacement.Material
    if (LocalGlassLayer.current == GlassLayer.OnGlass) return GlassPlacement.Fill
    if (LocalAppBackdrop.current === emptyBackdrop()) return GlassPlacement.Tonal
    return GlassPlacement.Glass
}

// ─── Budget ───────────────────────────────────────────────────────────────────────────────────

/**
 * Debug-only count of live `drawBackdrop` nodes created through [liquidGlass]. Every one is an
 * offscreen layer plus a blur (and a lens shader on API 33+), so the spec caps a screen at 6
 * including chrome. Going over logs a warning; nothing is enforced.
 */
object GlassBudget {
    const val MaxLiveNodes = 6

    var liveNodes: Int = 0
        private set

    internal fun attach(role: GlassRole) {
        liveNodes++
        if (liveNodes > MaxLiveNodes) {
            Timber.tag("GlassBudget").w(
                "%d live glass nodes (budget %d), latest %s", liveNodes, MaxLiveNodes, role
            )
        }
    }

    internal fun detach() {
        liveNodes = (liveNodes - 1).coerceAtLeast(0)
    }
}

// ─── The one glass modifier ───────────────────────────────────────────────────────────────────

/**
 * The single way this app draws a piece of glass. Every component funnels through here so the
 * recipe (spec §2), the capability tier, the recording guard and the budget are applied in one
 * place.
 *
 * Effects run colour filter → blur (Clamp) → lens, which keeps the library's padding at 0. Pixel
 * values are converted once per density, not per draw. The only animated value read inside the
 * effects is [materialize], quantised to 1/32 steps so a press doesn't rebuild the effect chain
 * every frame.
 *
 * @param recipe from [resolveRecipe].
 * @param shape the glass outline. Hoist it (or use [GlassShapes]) — `drawBackdrop` caches the
 *   outline by shape equality.
 * @param prominent draws the accent recipe (`accent` with [BlendMode.Hue], then `accent @ .75`)
 *   instead of the neutral tint: for the primary action and toggled-on states only.
 * @param accent the prominent colour. Defaults to the scheme's primary.
 * @param tint overrides the neutral tint entirely (drawn as-is). [Color.Unspecified] uses the recipe.
 * @param materialize 0..1 progress for roles that materialise (lens, highlight and inner shadow
 *   scale with it). Null for glass that is always fully there.
 * @param backdrop what to refract. Defaults to [LocalAppBackdrop].
 */
@Composable
fun Modifier.liquidGlass(
    recipe: GlassRecipe,
    shape: Shape,
    prominent: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    tint: Color = Color.Unspecified,
    materialize: (() -> Float)? = null,
    backdrop: Backdrop = LocalAppBackdrop.current,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    effectScale: Float = 1f,
    shadowEnabled: Boolean = true
): Modifier {
    val safeBackdrop = guardRecordingBackdrop(backdrop)

    if (BuildConfig.DEBUG) {
        DisposableEffect(recipe.role) {
            GlassBudget.attach(recipe.role)
            onDispose { GlassBudget.detach() }
        }
    }

    val density = LocalDensity.current
    val px = remember(recipe, density, effectScale) {
        with(density) {
            GlassPx(
                blur = (recipe.blur * effectScale).toPx(),
                lensHeight = (recipe.lensHeight * effectScale).toPx(),
                lensAmount = (recipe.lensAmount * effectScale).toPx(),
                border = recipe.border.toPx()
            )
        }
    }
    val quantized = remember(materialize) {
        if (materialize == null) null
        else derivedStateOf { (materialize().coerceIn(0f, 1f) * 32f).roundToInt() / 32f }
    }
    val progress: () -> Float = remember(quantized) {
        if (quantized == null) {
            { 1f }
        } else {
            { quantized.value }
        }
    }
    val materializes = materialize != null && recipe.materializes

    val effects: BackdropEffectScope.() -> Unit = remember(recipe, px, progress) {
        {
            when (recipe.colorFilter) {
                GlassColorFilter.Vibrancy -> vibrancy()
                GlassColorFilter.Controls -> colorControls(
                    brightness = recipe.brightness,
                    saturation = recipe.saturation
                )
                GlassColorFilter.None -> Unit
            }
            if (px.blur > 0f) blur(px.blur)
            if (recipe.hasLens) {
                val p = if (materializes) progress() else 1f
                if (p > 0f) {
                    lens(
                        px.lensHeight * p,
                        px.lensAmount * p,
                        depthEffect = recipe.depthEffect,
                        chromaticAberration = recipe.chromaticAberration
                    )
                }
            }
        }
    }

    val lights = remember(recipe, progress, materializes, shadowEnabled) {
        GlassLights(recipe, progress, materializes, shadowEnabled)
    }

    val onDrawSurface: DrawScope.() -> Unit =
        remember(recipe, prominent, accent, tint, progress, px, shape) {
            {
                when {
                    prominent -> {
                        drawRect(accent, blendMode = BlendMode.Hue)
                        drawRect(accent.copy(alpha = 0.75f))
                    }
                    tint.isSpecified -> drawRect(tint)
                    materializes -> drawRect(lerp(recipe.tint, recipe.materializedTint, progress()))
                    else -> drawRect(recipe.tint)
                }
                if (px.border > 0f) {
                    drawOutline(
                        outline = shape.createOutline(size, layoutDirection, this),
                        color = recipe.borderColor,
                        style = Stroke(px.border)
                    )
                }
            }
        }

    return this.drawBackdrop(
        backdrop = safeBackdrop,
        shape = { shape },
        effects = effects,
        highlight = lights.highlight,
        shadow = lights.shadow,
        innerShadow = lights.innerShadow,
        layerBlock = layerBlock,
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = onDrawSurface
    )
}

/**
 * The highlight/shadow/inner-shadow lambdas for one recipe, built once. Materialising roles scale
 * each one's alpha by the progress, read inside the lambda (draw phase), never in composition.
 */
private class GlassLights(
    recipe: GlassRecipe,
    progress: () -> Float,
    materializes: Boolean,
    shadowEnabled: Boolean
) {
    val highlight: (() -> Highlight?)? = recipe.highlight?.let { base ->
        if (materializes) {
            { base.copy(alpha = base.alpha * progress()) }
        } else {
            { base }
        }
    }
    val shadow: (() -> Shadow?)? = recipe.shadow?.takeIf { shadowEnabled }?.let { base ->
        if (materializes) {
            { base.copy(alpha = base.alpha * progress()) }
        } else {
            { base }
        }
    }
    val innerShadow: (() -> InnerShadow?)? = recipe.innerShadow?.let { base ->
        if (materializes) {
            { base.copy(alpha = base.alpha * progress()) }
        } else {
            { base }
        }
    }
}

private data class GlassPx(
    val blur: Float,
    val lensHeight: Float,
    val lensAmount: Float,
    val border: Float
)

/**
 * The crash guard: sampling a backdrop from inside its own recording makes the recording depend on
 * itself (RenderThread `prepareTreeImpl` stack overflow). Debug builds fail loudly at the call site;
 * release builds draw the glass over nothing instead.
 */
@Composable
private fun guardRecordingBackdrop(backdrop: Backdrop): Backdrop {
    if (backdrop in LocalRecordingBackdrops.current) {
        check(!BuildConfig.DEBUG) { "glass inside its own recording" }
        return emptyBackdrop()
    }
    return backdrop
}

// ─── Fallback surfaces ────────────────────────────────────────────────────────────────────────

/**
 * The content-layer look for glass components: an opaque tonal fill, and in glass mode a 0.5dp
 * `onSurface @ .06` rim so it still sits in the same family as the floating glass.
 */
@Composable
fun Modifier.glassTonal(shape: Shape, color: Color): Modifier {
    val rim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    return this
        .clip(shape)
        .background(color)
        .border(0.5.dp, rim, shape)
}

/**
 * A control sitting *on* glass (a sheet, a toolbar group): a translucent fill instead of a second
 * layer of glass. Apple's rule — fills and vibrancy on glass, never glass on glass.
 *
 * In Material 3 mode this is simply `clip(shape).background(color)`. In glass mode the fill is
 * `onSurface @ .08` (heavier under high contrast), unless [prominent], which keeps [color].
 */
@Composable
fun Modifier.glassFill(
    shape: Shape = GlassShapes.Capsule,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    prominent: Boolean = false
): Modifier {
    if (!isGlassEnabled || prominent) return this.clip(shape).background(color)
    val alpha = if (LocalGlassHighContrast.current) 0.20f else 0.08f
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
}

// ─── Components ───────────────────────────────────────────────────────────────────────────────

/**
 * Glass surfaces are transparent, so they need a *tint* rather than a fill: enough colour to keep
 * content legible over unpredictable backdrops without hiding the refraction underneath.
 *
 * Derived from the Material 3 scheme rather than hardcoded white/black, which is what keeps the
 * glass looking like it belongs to the album-art palette the rest of the app is themed from.
 */
@Composable
fun glassTint(alpha: Float = 0.28f): Color =
    MaterialTheme.colorScheme.surface.copy(
        alpha = (alpha * androidx.compose.ui.util.lerp(0.3f, 3f, glassTransparency())).coerceIn(0f, 1f)
    )

/**
 * Drop-in replacement for `Modifier.clip(shape).background(color)`.
 *
 * Under glass (with something to refract) the surface refracts and [color] becomes a translucent
 * tint; in the content layer it is a tonal fill; in Material 3 mode it is exactly the plain fill.
 *
 * @param effectScale scales the blur/refraction to the element's size (1 = a 48dp button).
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
    return when (glassPlacement()) {
        GlassPlacement.Material -> this.clip(shape).background(color)
        GlassPlacement.Tonal -> this.glassTonal(shape, color)
        GlassPlacement.Fill -> this.glassFill(shape, color)
        GlassPlacement.Glass -> {
            val recipe = resolveRecipe(GlassRole.PillButton)
            val tint = color.copy(
                alpha = (color.alpha * tintAlpha *
                    androidx.compose.ui.util.lerp(0.3f, 2.2f, glassTransparency())).coerceIn(0f, 1f)
            )
            this.liquidGlass(
                recipe = if (shadow) recipe.copy(shadow = recipe.shadow ?: Shadow.Default) else recipe,
                shape = shape,
                tint = tint,
                effectScale = effectScale,
                shadowEnabled = shadow
            )
        }
    }
}

/** True when [color] is not the default container colour, i.e. the caller is marking a state. */
@Composable
private fun isProminentContainer(containerColor: Color): Boolean =
    containerColor != MaterialTheme.colorScheme.surfaceContainer

/**
 * The base glass panel. Falls back to a normal tonal [Surface] under [AppUiStyle.Material3].
 *
 * @param tint colour laid over the refracted backdrop. Keep it translucent.
 * @param effectScale scales the refraction to the component's size.
 * @param role which recipe to draw with.
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
    role: GlassRole = GlassRole.Group,
    content: @Composable BoxScope.() -> Unit
) {
    when (glassPlacement()) {
        GlassPlacement.Material -> Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor
        ) {
            Box(contentAlignment = contentAlignment, content = content)
        }

        GlassPlacement.Tonal -> Box(
            modifier = modifier.glassTonal(shape, containerColor),
            contentAlignment = contentAlignment,
            content = content
        )

        GlassPlacement.Fill -> Box(
            modifier = modifier.glassFill(shape, containerColor),
            contentAlignment = contentAlignment,
            content = content
        )

        GlassPlacement.Glass -> Box(
            modifier = modifier.liquidGlass(
                recipe = resolveRecipe(role),
                shape = shape,
                tint = tint ?: Color.Unspecified,
                effectScale = effectScale,
                shadowEnabled = shadow
            ),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * A circular glass icon button — the settings cog, transport controls, etc.
 *
 * A [containerColor] other than the default `surfaceContainer`, or [prominent] = true, marks an
 * active/primary state and draws the accent recipe, so "on" states stay visible on glass.
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
    prominent: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    GlassControl(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        role = GlassRole.IconButton,
        tint = tint,
        containerColor = containerColor,
        contentColor = contentColor,
        enabled = enabled,
        interactionSource = interactionSource,
        prominent = prominent || isProminentContainer(containerColor),
        materialEffectScale = 0.4f,
        content = content
    )
}

/** A pill-shaped glass button with a label/icon row. See [GlassIconButton] for [prominent]. */
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
    prominent: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    GlassControl(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        role = GlassRole.PillButton,
        tint = tint,
        containerColor = containerColor,
        contentColor = contentColor,
        enabled = enabled,
        interactionSource = interactionSource,
        prominent = prominent || isProminentContainer(containerColor),
        materialEffectScale = 0.55f
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** Shared body of [GlassIconButton] and [GlassButton]. */
@Composable
private fun GlassControl(
    onClick: () -> Unit,
    modifier: Modifier,
    shape: CornerBasedShape,
    role: GlassRole,
    tint: Color?,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    prominent: Boolean,
    materialEffectScale: Float,
    content: @Composable BoxScope.() -> Unit
) {
    val placement = glassPlacement()
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        when (placement) {
            // Material 3: exactly the pre-glass look (tonal Surface + ripple).
            GlassPlacement.Material -> GlassSurface(
                modifier = modifier.glassClickable(
                    onClick = onClick,
                    enabled = enabled,
                    shape = shape,
                    interactionSource = interactionSource
                ),
                shape = shape,
                tint = tint,
                containerColor = containerColor,
                effectScale = materialEffectScale,
                content = content
            )

            GlassPlacement.Tonal, GlassPlacement.Fill -> Box(
                modifier = modifier
                    .then(
                        if (placement == GlassPlacement.Tonal) {
                            Modifier.glassTonal(shape, containerColor)
                        } else {
                            Modifier.glassFill(shape, containerColor, prominent = prominent)
                        }
                    )
                    .glassClickable(
                        onClick = onClick,
                        enabled = enabled,
                        shape = shape,
                        interactionSource = interactionSource
                    ),
                contentAlignment = Alignment.Center,
                content = content
            )

            GlassPlacement.Glass -> {
                val animationScope = rememberCoroutineScope()
                val highlight = remember(animationScope) { InteractiveHighlight(animationScope) }
                val reduceMotion = LocalGlassReduceMotion.current
                val recipe = resolveRecipe(role)
                val layerBlock: GraphicsLayerScope.() -> Unit = remember(highlight, reduceMotion) {
                    { applyGlassPress(highlight, reduceMotion) }
                }
                Box(
                    modifier = modifier
                        .liquidGlass(
                            recipe = recipe,
                            shape = shape,
                            prominent = prominent,
                            accent = if (containerColor != MaterialTheme.colorScheme.surfaceContainer) {
                                containerColor
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            tint = if (!prominent && tint != null) tint else Color.Unspecified,
                            layerBlock = if (enabled) layerBlock else null
                        )
                        .then(if (enabled) highlight.modifier else Modifier)
                        .then(if (enabled) highlight.gestureModifier else Modifier)
                        .glassClickable(
                            onClick = onClick,
                            enabled = enabled,
                            shape = shape,
                            interactionSource = interactionSource,
                            indication = null
                        ),
                    contentAlignment = Alignment.Center,
                    content = content
                )
            }
        }
    }
}

/**
 * A card for list rows and content blocks. Cards live in the content layer, so in glass mode this
 * is a tonal surface with a faint rim — never `drawBackdrop` (spec §3). Material 3 mode is unchanged.
 */
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
    if (!isGlassEnabled) {
        GlassSurface(
            modifier = modifier.then(clickable),
            shape = shape,
            tint = tint,
            containerColor = containerColor,
            effectScale = 1.5f,
            contentAlignment = Alignment.CenterStart,
            content = content
        )
        return
    }
    Box(
        modifier = modifier
            .glassTonal(shape, containerColor)
            .then(clickable),
        contentAlignment = Alignment.CenterStart,
        content = content
    )
}

/**
 * Several controls on ONE piece of glass: a top bar's trailing actions, the queue toolbar. The
 * group is a single `drawBackdrop` capsule; the buttons inside see [GlassLayer.OnGlass] and render
 * as fills, so a 3-button toolbar costs one glass node instead of four.
 *
 * In Material 3 mode it is a plain [Row] with no container, so migrating a call site to it leaves
 * the M3 look alone.
 */
@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.Capsule,
    contentPadding: PaddingValues = PaddingValues(4.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(4.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    val placement = glassPlacement()
    if (placement == GlassPlacement.Material) {
        Row(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content
        )
        return
    }
    val container = when (placement) {
        GlassPlacement.Glass -> Modifier.liquidGlass(resolveRecipe(GlassRole.Group), shape)
        GlassPlacement.Tonal -> Modifier.glassTonal(shape, MaterialTheme.colorScheme.surfaceContainer)
        else -> Modifier.glassFill(shape)
    }
    Row(
        modifier = modifier
            .then(container)
            .padding(contentPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment
    ) {
        CompositionLocalProvider(LocalGlassLayer provides GlassLayer.OnGlass) {
            content()
        }
    }
}
