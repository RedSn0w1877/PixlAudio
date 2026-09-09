package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.Default }

/**
 * The recorded page content that glass surfaces refract.
 *
 * Defaults to [emptyBackdrop] so a component used outside a glass-enabled screen degrades to
 * "no backdrop" instead of crashing — glass over nothing just draws its tint and edges.
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The page recording, carried through *without* ever being re-scoped to empty.
 *
 * [LocalAppBackdrop] gets deliberately overridden to `emptyBackdrop()` for everything inside
 * `AppNavigation`, because a glass element drawn *inside* the subtree being recorded would make
 * the recording depend on itself — that's the native stack overflow this app hit before. Bottom
 * sheets, dialogs and popups are the exception: Compose renders them into their own window, so
 * they are composition-tree descendants but *not* part of the recorded draw pass. Sampling the
 * page recording from one of them is a plain layer blit with no cycle, which is what lets a sheet
 * actually refract the screen behind it instead of just tinting it.
 *
 * Read this only from something that genuinely renders in its own window. Anything drawn inline in
 * a screen must keep using [LocalAppBackdrop].
 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The user's 0..1 glass **transparency** dial (Settings → Experimental → Liquid Glass): 0 reads
 * straight through to whatever is behind the glass, 1 is fully frosted. This does NOT control how
 * strongly surfaces refract/bend — see [glassEffects] — because a refraction dial and a
 * transparency dial answer different questions ("how thick is the glass" vs "how much of the tint
 * shows"), and conflating them meant turning the slider down for a subtler look also flattened the
 * edge-bend down to nothing.
 */
val LocalGlassIntensity = staticCompositionLocalOf { 0.80f }

/**
 * 0 = fully see-through, 1 = fully frosted. Read this wherever a glass surface computes its tint
 * alpha; [glassEffects] (blur/refraction) intentionally does not use it.
 */
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

/**
 * The library's refraction knobs for a standard control, at [scale] = 1.
 *
 * These are NOT hand-tuned — they're taken directly from the reference implementation
 * (kyant/AndroidLiquidGlass-kmp, `LiquidButton.kt`: `blur(2.dp)`, `lens(12.dp, 24.dp)`, no
 * depthEffect, no chromaticAberration). Earlier passes in this app repeatedly guessed much bigger
 * numbers (up to 136/156dp) chasing a stronger "is this even glass" look on-device, and each time
 * that read as the control's own content smearing into illegible mush rather than a clean glass
 * edge — the reference's restraint is a deliberate design choice, not an oversight to correct.
 *
 * [scale] lets larger surfaces (a bottom sheet vs. a 48dp button) ask for proportionally bigger
 * values; see individual call sites for which reference component they're modeled on (e.g. the
 * dialog/sheet numbers come from `DialogContent.kt`'s `blur(8..16.dp)` / `lens(24.dp, 48.dp)`).
 */
@Composable
@ReadOnlyComposable
fun glassEffects(scale: Float = 1f): GlassEffectValues {
    return GlassEffectValues(
        blurRadius = 2.dp * scale,
        refractionHeight = 12.dp * scale,
        refractionAmount = 24.dp * scale
    )
}

data class GlassEffectValues(
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp
)
