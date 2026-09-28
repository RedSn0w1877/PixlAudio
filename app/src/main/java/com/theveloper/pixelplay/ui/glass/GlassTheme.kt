package com.theveloper.pixelplay.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop

/**
 * Which visual language the app is currently rendering in.
 *
 * These are not skins over the same widgets — Liquid Glass surfaces are transparent and refract
 * whatever is behind them, so they need a recorded backdrop and a light-on-dark content colour,
 * while Material 3 surfaces are opaque and get their colour from the scheme. Components branch
 * on this rather than trying to interpolate between the two.
 */
enum class AppUiStyle {
    /** Refractive glass: transparent surfaces, real backdrop sampling, specular edges. */
    LiquidGlass,

    /** Material 3 Expressive: opaque tonal surfaces from the colour scheme. */
    Material3;

    val isGlass: Boolean get() = this == LiquidGlass

    companion object {
        val Default: AppUiStyle = LiquidGlass

        fun fromPreference(value: String?): AppUiStyle = when (value) {
            LiquidGlass.name -> LiquidGlass
            Material3.name -> Material3
            else -> Default
        }
    }
}

/**
 * How much of the glass stack this device can afford *right now*.
 *
 * Resolved once at the root (see [ProvideGlassEnvironment]) from the API level and live power
 * state, so no component ever branches on `Build.VERSION` or battery saver itself.
 */
enum class GlassTier {
    /** API 33+: blur, AGSL lens refraction, shaded specular rim that follows the light. */
    Refractive,

    /**
     * API 31–32, or any device in battery saver: blur and a flat rim, no lens. Refraction is
     * the one piece of the stack that costs a full-surface shader pass per frame, so it's the
     * piece that goes when the system asks apps to save power.
     */
    Frosted,

    /**
     * API 30: no RenderEffect at all. Painted frosted surface (tint, sheen, rim) with no
     * backdrop sampling — sampling without blur would just show a sharp copy of the page
     * behind the control, which reads as a hole rather than glass.
     */
    Solid;

    val samplesBackdrop: Boolean get() = this != Solid
    val refracts: Boolean get() = this == Refractive

    companion object {
        fun resolve(powerSave: Boolean): GlassTier = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Solid
            powerSave || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> Frosted
            else -> Refractive
        }
    }
}

/**
 * The glass colour system. Two neutral bases (light and dark) with a faint wash of the current
 * accent — the album-art primary — on top, so glass belongs to the song that's playing without
 * turning into a coloured plastic.
 *
 * Everything that needs a colour composite is precomputed here, once per theme change, so the
 * draw lambdas that consume it are plain field reads.
 */
@Immutable
class GlassPalette(
    val isDark: Boolean,
    /** Surface fill laid over the refracted backdrop, at the default transparency. */
    val tint: Color,
    /** Opaque-ish fill for the [GlassTier.Solid] fallback, where nothing is sampled. */
    val solidTint: Color,
    /** Top-edge sheen for the Solid fallback, standing in for the specular highlight. */
    val solidSheen: Color,
    /** Hairline rim colour for Frosted/Solid tiers (Refractive uses the shaded highlight). */
    val rim: Color,
    /** Primary content colour on glass. */
    val content: Color,
    /** Secondary content colour on glass. */
    val contentSecondary: Color,
    /** Accent used by selection states on glass (the nav pill, toggles, sliders). */
    val accent: Color,
    /** Neutral track colour for toggles/sliders. */
    val track: Color,
    /** Shadow colour under floating glass. */
    val shadow: Color,
    /** Specular highlight colour. */
    val highlight: Color
) {
    companion object {
        fun create(isDark: Boolean, accent: Color): GlassPalette {
            return if (isDark) {
                val base = Color(0xFF16171B)
                GlassPalette(
                    isDark = true,
                    tint = accent.copy(alpha = 0.10f).compositeOver(base.copy(alpha = 0.40f)),
                    solidTint = accent.copy(alpha = 0.10f).compositeOver(base.copy(alpha = 0.90f)),
                    solidSheen = Color.White.copy(alpha = 0.07f),
                    rim = Color.White.copy(alpha = 0.22f),
                    content = Color.White,
                    contentSecondary = Color.White.copy(alpha = 0.72f),
                    accent = accent,
                    track = Color(0xFF787880).copy(alpha = 0.36f),
                    shadow = Color.Black.copy(alpha = 0.32f),
                    highlight = Color.White.copy(alpha = 0.55f)
                )
            } else {
                val base = Color(0xFFF7F8FA)
                GlassPalette(
                    isDark = false,
                    tint = accent.copy(alpha = 0.06f).compositeOver(base.copy(alpha = 0.50f)),
                    solidTint = accent.copy(alpha = 0.06f).compositeOver(base.copy(alpha = 0.93f)),
                    solidSheen = Color.White.copy(alpha = 0.55f),
                    rim = Color.White.copy(alpha = 0.70f),
                    content = Color(0xFF111114),
                    contentSecondary = Color(0xFF111114).copy(alpha = 0.66f),
                    accent = accent,
                    track = Color(0xFF787878).copy(alpha = 0.20f),
                    shadow = Color.Black.copy(alpha = 0.12f),
                    highlight = Color.White.copy(alpha = 0.80f)
                )
            }
        }

        val DefaultDark: GlassPalette = create(isDark = true, accent = Color(0xFF8AB4F8))
    }
}

/**
 * A glass *recipe*: how thick a surface is. Components pick the recipe matching their size class
 * rather than tuning blur/lens numbers per call site — that per-site tuning is what made the
 * previous kit inconsistent (every surface had its own hand-picked, slightly different glass).
 *
 * Lens numbers follow the reference library's own components at each size: a 48dp button bends
 * ~12/24dp, a pill ~20/32dp, a sheet ~24/48dp with depth. Going bigger than that is what used to
 * smear a control's own label into mush.
 */
@Immutable
class GlassMaterial(
    val blur: Dp,
    val lensHeight: Dp,
    val lensAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    /** Multiplier on the palette tint alpha. */
    val tintWeight: Float,
    /** Extra blur multiplier when the lens is off (Frosted), to keep the surface readable. */
    val frostedBlurBoost: Float,
    val castsShadow: Boolean
) {
    companion object {
        /** Icon buttons, transport controls, small chips. */
        val Thin = GlassMaterial(
            blur = 3.dp, lensHeight = 12.dp, lensAmount = 24.dp,
            depthEffect = false, chromaticAberration = false,
            tintWeight = 0.85f, frostedBlurBoost = 2.5f, castsShadow = true
        )

        /** Pills, cards, the mini player. */
        val Regular = GlassMaterial(
            blur = 8.dp, lensHeight = 20.dp, lensAmount = 32.dp,
            depthEffect = false, chromaticAberration = false,
            tintWeight = 1f, frostedBlurBoost = 2f, castsShadow = true
        )

        /** Navigation chrome — slightly clearer than a card so the page reads through. */
        val Chrome = GlassMaterial(
            blur = 8.dp, lensHeight = 24.dp, lensAmount = 24.dp,
            depthEffect = false, chromaticAberration = false,
            tintWeight = 0.9f, frostedBlurBoost = 2f, castsShadow = true
        )

        /** Sheets and dialogs: heavy frost, deep lens, no shadow (the scrim does that job). */
        val Thick = GlassMaterial(
            blur = 16.dp, lensHeight = 24.dp, lensAmount = 48.dp,
            depthEffect = true, chromaticAberration = false,
            // Sheets carry long-form text over busy content: noticeably more frost than a card.
            tintWeight = 1.5f, frostedBlurBoost = 1.5f, castsShadow = false
        )
    }
}

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.Default }

/** See [GlassTier]. Provided by [ProvideGlassEnvironment]. */
val LocalGlassTier = compositionLocalOf { GlassTier.Frosted }

/** See [GlassPalette]. Provided by [ProvideGlassEnvironment]. */
val LocalGlassPalette = compositionLocalOf { GlassPalette.DefaultDark }

/**
 * What inline glass refracts.
 *
 * At the root this is the page recording. Inside `AppNavigation` it is re-scoped to the ambient
 * backdrop (see [AmbientBackdrop]) — a glass element drawn *inside* the subtree being recorded
 * would make the recording depend on itself, which is the native stack overflow this app hit
 * before. Screens with floating glass over scrolling content re-scope it again to a local
 * recording of just that content (see [glassSource]).
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The page recording, carried through *without* ever being re-scoped.
 *
 * Bottom sheets, dialogs and popups render in their own window, so they are composition-tree
 * descendants but *not* part of the recorded draw pass. Sampling the page from one of them has
 * no cycle, which is what lets a sheet refract the screen behind it. Read this only from
 * something that genuinely renders in its own window.
 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The user's 0..1 glass **transparency** dial (Settings → Experimental → Liquid Glass): 0 reads
 * straight through to whatever is behind the glass, 1 is fully frosted. Scales the tint only —
 * refraction strength is a property of the [GlassMaterial], not of taste.
 */
val LocalGlassIntensity = staticCompositionLocalOf { 0.55f }

/** 0 = fully see-through, 1 = fully frosted. */
@Composable
@ReadOnlyComposable
fun glassTransparency(): Float = LocalGlassIntensity.current

/**
 * True only when glass should actually be drawn: the user picked it *and* it hasn't been
 * disabled. Read this rather than comparing [LocalAppUiStyle] by hand.
 */
val isGlassEnabled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalAppUiStyle.current.isGlass

/** Tint alpha multiplier for the transparency dial: 0.55x at 0, 1x at the 0.55 default, 1.6x at 1. */
internal fun transparencyScale(intensity: Float): Float {
    val t = intensity.coerceIn(0f, 1f)
    return if (t <= 0.55f) 0.55f + (t / 0.55f) * 0.45f else 1f + ((t - 0.55f) / 0.45f) * 0.6f
}

internal fun Color.scaleAlpha(factor: Float): Color =
    copy(alpha = (alpha * factor).coerceIn(0f, 1f))

/**
 * Legacy knob set, kept for call sites that build their own `drawBackdrop` (the player card).
 * Prefer a [GlassMaterial] for new code.
 */
@Composable
@ReadOnlyComposable
fun glassEffects(scale: Float = 1f): GlassEffectValues {
    val base = GlassMaterial.Thin
    return GlassEffectValues(
        blurRadius = base.blur * scale,
        refractionHeight = base.lensHeight * scale,
        refractionAmount = base.lensAmount * scale
    )
}

@Immutable
data class GlassEffectValues(
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp
)
