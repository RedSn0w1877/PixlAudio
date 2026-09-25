package com.theveloper.pixelplay.ui.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * What a piece of glass is for. Each role has one recipe in [GlassTokens]; components ask for a
 * role instead of hand-picking blur/lens numbers, which is what kept the old call sites drifting
 * apart.
 */
enum class GlassRole {
    /** The floating tab bar (68dp capsule). */
    NavBar,

    /** The selected-tab pill on the nav bar; materialises on press/drag. */
    NavPill,

    /** The collapsed player card. Tint colour comes from the player's own background. */
    MiniPlayer,

    /** A 40–48dp circular button in a bar. */
    IconButton,

    /** A capsule button with a label. */
    PillButton,

    /** A capsule holding several buttons ([GlassGroup]). */
    Group,

    /** A floating action (queue more-options, 56dp). */
    FloatingAction,

    /** A modal bottom sheet. */
    Sheet,

    /** A dialog. */
    Dialog,

    /** Slider / toggle thumb; materialises while dragged. */
    Thumb,

    /** Full-player play/pause: persistent, prominent. */
    PlayPause,

    /** Full-player prev/next, lyrics/queue: glyph only at rest, glass while pressed. */
    TransientControl,

    /** The blurred strip under a screen's top chrome. */
    ScrollEdge
}

/** Colour filter applied to the backdrop before the blur. */
enum class GlassColorFilter { None, Vibrancy, Controls }

/**
 * Everything one glass surface needs, already resolved for theme, capability and contrast.
 *
 * Effects are always applied colour filter → `blur(r, Clamp)` → `lens`, which keeps the
 * library's padding at 0.
 *
 * @property tint the surface tint at rest (alpha included).
 * @property materializedTint the tint at full materialisation (`p = 1`). Equal to [tint] for roles
 *   that do not materialise.
 * @property tintAlpha the alpha [tint] was built with, for re-tinting with a caller's colour.
 * @property materializes whether lens, highlight and inner shadow scale with the materialise
 *   progress `p` (nav pill, thumbs, transient controls).
 */
@Immutable
data class GlassRecipe(
    val role: GlassRole,
    val colorFilter: GlassColorFilter,
    val brightness: Float,
    val saturation: Float,
    val blur: Dp,
    val lensHeight: Dp,
    val lensAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val highlight: Highlight?,
    val shadow: Shadow?,
    val innerShadow: InnerShadow?,
    val tint: Color,
    val materializedTint: Color,
    val tintAlpha: Float,
    val materializes: Boolean,
    val border: Dp,
    val borderColor: Color
) {
    val hasLens: Boolean get() = lensHeight > 0.dp && lensAmount > 0.dp

    /** This recipe with its neutral tint swapped for [color], at the recipe's own alpha. */
    fun withTintColor(color: Color): GlassRecipe {
        val alpha = (color.alpha * tintAlpha).coerceIn(0f, 1f)
        val tinted = color.copy(alpha = alpha)
        return copy(tint = tinted, materializedTint = if (materializes) materializedTint else tinted)
    }
}

/** The handful of scheme colours the recipes are built from. */
@Immutable
data class GlassColors(
    val surface: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val primary: Color,
    val onSurface: Color
)

/**
 * The Liquid Glass recipe table (spec §2). Pure: every input is a parameter, so it is unit-tested
 * without Compose. [resolveRecipe] feeds it from the composition.
 *
 * `t` is the user's transparency dial (0 = clear, 1 = frosted, default 0.55).
 */
object GlassTokens {

    private const val BlurOnlyTintBoost = 0.20f
    private const val BlurOnlyBlurScale = 1.5f
    private const val HighContrastTintBoost = 0.25f

    fun recipe(
        role: GlassRole,
        t: Float,
        isDark: Boolean,
        capability: GlassCapability,
        highContrast: Boolean,
        colors: GlassColors
    ): GlassRecipe {
        val tt = t.coerceIn(0f, 1f)
        val base = baseRecipe(role, tt, isDark, colors)
        return adjust(base, capability, highContrast, colors)
    }

    private fun baseRecipe(role: GlassRole, t: Float, isDark: Boolean, c: GlassColors): GlassRecipe {
        fun r(
            filter: GlassColorFilter = GlassColorFilter.Vibrancy,
            brightness: Float = 0f,
            saturation: Float = 1f,
            blur: Dp = 0.dp,
            lensHeight: Dp = 0.dp,
            lensAmount: Dp = 0.dp,
            depth: Boolean = false,
            ca: Boolean = false,
            highlight: Highlight? = Highlight.Default,
            shadow: Shadow? = null,
            innerShadow: InnerShadow? = null,
            tintColor: Color = c.surface,
            tintAlpha: Float,
            materializedTint: Color? = null
        ): GlassRecipe {
            val tint = tintColor.copy(alpha = tintAlpha.coerceIn(0f, 1f))
            return GlassRecipe(
                role = role,
                colorFilter = filter,
                brightness = brightness,
                saturation = saturation,
                blur = blur,
                lensHeight = lensHeight,
                lensAmount = lensAmount,
                depthEffect = depth,
                chromaticAberration = ca,
                highlight = highlight,
                shadow = shadow,
                innerShadow = innerShadow,
                tint = tint,
                materializedTint = materializedTint ?: tint,
                tintAlpha = tintAlpha.coerceIn(0f, 1f),
                materializes = materializedTint != null,
                border = 0.dp,
                borderColor = Color.Transparent
            )
        }

        return when (role) {
            GlassRole.NavBar -> r(
                blur = 8.dp, lensHeight = 24.dp, lensAmount = 24.dp,
                shadow = Shadow(
                    radius = 24.dp,
                    offset = DpOffset(0.dp, 4.dp),
                    color = Color.Black.copy(alpha = if (isDark) 0.22f else 0.10f)
                ),
                tintAlpha = lerp(0.30f, 0.72f, t)
            )

            GlassRole.NavPill -> r(
                filter = GlassColorFilter.None,
                lensHeight = 10.dp, lensAmount = 14.dp, ca = true,
                shadow = Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)),
                innerShadow = InnerShadow(radius = 8.dp),
                tintColor = c.primary,
                tintAlpha = if (isDark) 0.20f else 0.14f,
                // Once lifted the pill is clear glass; only a trace of the accent stays so it
                // still reads as "the selected one" mid-drag.
                materializedTint = c.primary.copy(alpha = 0.06f)
            )

            GlassRole.MiniPlayer -> r(
                blur = 8.dp, lensHeight = 20.dp, lensAmount = 28.dp,
                tintAlpha = lerp(0.28f, 0.60f, t)
            )

            GlassRole.IconButton, GlassRole.PillButton -> r(
                blur = 2.dp, lensHeight = 12.dp, lensAmount = 24.dp,
                tintAlpha = lerp(0.18f, 0.50f, t)
            )

            GlassRole.Group -> r(
                blur = 4.dp, lensHeight = 12.dp, lensAmount = 20.dp,
                shadow = Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.06f)),
                tintAlpha = lerp(0.22f, 0.55f, t)
            )

            GlassRole.FloatingAction -> r(
                blur = 4.dp, lensHeight = 16.dp, lensAmount = 32.dp,
                shadow = Shadow(
                    radius = 16.dp,
                    offset = DpOffset(0.dp, 3.dp),
                    color = Color.Black.copy(alpha = 0.12f)
                ),
                tintAlpha = lerp(0.22f, 0.55f, t)
            )

            GlassRole.Sheet -> r(
                filter = GlassColorFilter.Controls,
                brightness = if (isDark) 0f else 0.2f,
                saturation = 1.5f,
                blur = if (isDark) 12.dp else 16.dp,
                lensHeight = 24.dp, lensAmount = 48.dp, depth = true,
                highlight = Highlight.Plain,
                tintColor = c.surfaceContainerLow,
                tintAlpha = lerp(0.55f, 0.85f, t)
            )

            GlassRole.Dialog -> r(
                filter = GlassColorFilter.Controls,
                brightness = if (isDark) 0f else 0.2f,
                saturation = 1.5f,
                blur = if (isDark) 8.dp else 16.dp,
                lensHeight = 24.dp, lensAmount = 48.dp, depth = true,
                highlight = Highlight.Plain,
                shadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.18f)),
                tintColor = c.surfaceContainerHigh,
                tintAlpha = lerp(0.60f, 0.88f, t)
            )

            GlassRole.Thumb -> r(
                filter = GlassColorFilter.None,
                lensHeight = 10.dp, lensAmount = 14.dp, ca = true,
                // The catalog's thumbs use the thinner ambient rim rather than the default edge
                // light: a 24dp capsule with the full 0.5dp specular reads as a hard outline.
                highlight = Highlight.Ambient.copy(
                    width = Highlight.Ambient.width / 1.5f,
                    blurRadius = Highlight.Ambient.blurRadius / 1.5f
                ),
                // A white thumb on a near-white light surface needs a little more separation
                // than the 5% the dark theme gets (the catalog's toggle uses 10%).
                shadow = Shadow(
                    radius = 4.dp,
                    color = Color.Black.copy(alpha = if (isDark) 0.05f else 0.10f)
                ),
                innerShadow = InnerShadow(radius = 4.dp),
                tintColor = Color.White,
                tintAlpha = 1f,
                materializedTint = Color.White.copy(alpha = 0f)
            )

            GlassRole.PlayPause -> r(
                blur = 4.dp, lensHeight = 16.dp, lensAmount = 32.dp,
                tintAlpha = lerp(0.18f, 0.50f, t)
            )

            GlassRole.TransientControl -> r(
                filter = GlassColorFilter.None,
                lensHeight = 12.dp, lensAmount = 24.dp,
                tintColor = Color.White,
                tintAlpha = 0f,
                materializedTint = Color.White.copy(alpha = 0.08f)
            )

            GlassRole.ScrollEdge -> r(
                filter = GlassColorFilter.None,
                blur = 4.dp,
                highlight = null,
                tintAlpha = 0.80f
            )
        }
    }

    private fun adjust(
        base: GlassRecipe,
        capability: GlassCapability,
        highContrast: Boolean,
        c: GlassColors
    ): GlassRecipe {
        var recipe = base
        if (capability != GlassCapability.Full) {
            // BlurOnly (and, defensively, None): no lens, no shaders. Blur a bit wider and tint a
            // bit heavier so the surface still separates from what's behind it.
            val boosted = boostTint(recipe, BlurOnlyTintBoost).let {
                if (it.role == GlassRole.NavBar && it.tintAlpha < 0.50f) retint(it, 0.50f) else it
            }
            recipe = boosted.copy(
                blur = boosted.blur * BlurOnlyBlurScale,
                lensHeight = 0.dp,
                lensAmount = 0.dp,
                depthEffect = false,
                chromaticAberration = false,
                highlight = boosted.highlight?.let { Highlight.Plain.copy(alpha = it.alpha) }
            )
        }
        if (highContrast) {
            recipe = boostTint(recipe, HighContrastTintBoost).copy(
                border = 1.dp,
                borderColor = c.onSurface.copy(alpha = 0.5f)
            )
        }
        return recipe
    }

    /** Raises a translucent at-rest tint; leaves fully clear/opaque tints alone. */
    private fun boostTint(recipe: GlassRecipe, by: Float): GlassRecipe {
        if (recipe.tintAlpha <= 0f || recipe.tintAlpha >= 1f) return recipe
        return retint(recipe, (recipe.tintAlpha + by).coerceAtMost(1f))
    }

    private fun retint(recipe: GlassRecipe, alpha: Float): GlassRecipe {
        val tint = recipe.tint.copy(alpha = alpha)
        return recipe.copy(
            tint = tint,
            tintAlpha = alpha,
            materializedTint = if (recipe.materializes) recipe.materializedTint else tint
        )
    }
}

/** The recipe for [role] in the current theme, capability, contrast and transparency. */
@Composable
fun resolveRecipe(role: GlassRole): GlassRecipe {
    val scheme = MaterialTheme.colorScheme
    val colors = GlassColors(
        surface = scheme.surface,
        surfaceContainerLow = scheme.surfaceContainerLow,
        surfaceContainerHigh = scheme.surfaceContainerHigh,
        primary = scheme.primary,
        onSurface = scheme.onSurface
    )
    val t = glassTransparency()
    val isDark = glassIsDark()
    val capability = LocalGlassCapability.current
    val highContrast = LocalGlassHighContrast.current
    return remember(role, t, isDark, capability, highContrast, colors) {
        GlassTokens.recipe(role, t, isDark, capability, highContrast, colors)
    }
}
