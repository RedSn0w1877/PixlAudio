package com.theveloper.pixelplay.ui.glass

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
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

        /**
         * The style actually drawn: the user's choice, unless "Disable blur all over" is on or
         * the device cannot draw glass at all ([GlassCapability.None]). The preference itself is
         * left untouched either way, so it comes back if either condition goes away.
         */
        fun resolve(
            preference: String?,
            disableBlurAllOver: Boolean,
            capability: GlassCapability
        ): AppUiStyle = when {
            disableBlurAllOver -> Material3
            capability == GlassCapability.None -> Material3
            else -> fromPreference(preference)
        }
    }
}

/**
 * The style in effect. MainActivity provides the resolved user choice around the main UI; anything
 * composed outside that scope (setup, the crash-report dialog, other activities, previews) gets
 * [AppUiStyle.Material3] — plain, opaque components that need no backdrop, capability tier or
 * snapshot — rather than glass with nothing set up to draw it.
 */
val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.Material3 }

/**
 * How much of the glass pipeline this device can run.
 *
 * - [Full] (API 33+): blur, colour filters, the SDF lens and the highlight/touch-glow shaders.
 * - [BlurOnly] (API 31–32): `RenderEffect` exists but `RuntimeShader` does not, so no lens and
 *   no shaders. Tints get heavier (+0.20 alpha) and blur wider (×1.5) to make up for the missing
 *   refraction, and highlights drop to [com.kyant.backdrop.highlight.Highlight.Plain].
 * - [None] (API 30): neither. Glass resolves to Material 3 (see [AppUiStyle.resolve]); the saved
 *   preference is kept.
 */
enum class GlassCapability {
    Full,
    BlurOnly,
    None;

    val hasLens: Boolean get() = this == Full
    val hasBlur: Boolean get() = this != None

    companion object {
        fun forDevice(sdkInt: Int = Build.VERSION.SDK_INT): GlassCapability = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> Full
            sdkInt >= Build.VERSION_CODES.S -> BlurOnly
            else -> None
        }
    }
}

val LocalGlassCapability = staticCompositionLocalOf { GlassCapability.forDevice() }

/**
 * Where a glass component sits in the "glass layer" model.
 *
 * Glass belongs to the navigation/control layer that floats above content, never to the content
 * itself, and never on top of other glass.
 */
enum class GlassLayer {
    /**
     * Inside a recorded page (everything under `AppNavigation`, or a [RecordedContent]). There is
     * nothing to refract here without sampling the recording from inside itself, so glass
     * components render their tonal fallback and skip `drawBackdrop` entirely.
     */
    Content,

    /** Floating chrome drawn above a recording and sampling it: nav bar, mini player, top bars. */
    Chrome,

    /**
     * Sitting on a glass surface (a sheet, a [GlassGroup]). Controls here use fills
     * ([glassFill]) instead of stacking a second piece of glass on the first.
     */
    OnGlass
}

val LocalGlassLayer = staticCompositionLocalOf { GlassLayer.Chrome }

/**
 * Whether glass should use its dark-theme values. Provided from the app's own dark setting
 * (MainActivity's `useDarkTheme`), not `isSystemInDarkTheme()` — the user can force a theme that
 * differs from the system one. Inside the full player it is re-provided from the album scheme's
 * background luminance. Null means "not provided": [glassIsDark] then derives it from the scheme.
 */
val LocalGlassIsDark = staticCompositionLocalOf<Boolean?> { null }

/** Reduce Motion (system animator duration scale is 0): no squash, no tanh drag, static lens. */
val LocalGlassReduceMotion = staticCompositionLocalOf { false }

/** High-contrast text is on: heavier tints plus a 1dp `onSurface` border on glass. */
val LocalGlassHighContrast = staticCompositionLocalOf { false }

/** Resolves [LocalGlassIsDark], falling back to the current scheme's surface luminance. */
@Composable
@ReadOnlyComposable
fun glassIsDark(): Boolean =
    LocalGlassIsDark.current ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)

/** Reads the system "Remove animations" state (animator duration scale 0). */
fun readGlassReduceMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}.getOrDefault(false)

/** Reads the system high-contrast-text setting. Not a public constant, hence the literal key. */
fun readGlassHighContrast(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
}.getOrDefault(false)

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
 * page from one of them goes through [PageBackdrop.snapshotBitmap], a half-size bitmap captured on
 * demand while a sheet is registered (see [RegisterGlassSnapshot]).
 *
 * Read this only from something that genuinely renders in its own window. Anything drawn inline in
 * a screen must keep using [LocalAppBackdrop].
 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/** Default transparency dial. Matches `UserPreferencesRepository.liquidGlassIntensityFlow`. */
const val DefaultGlassIntensity = 0.55f

/**
 * The user's 0..1 glass **transparency** dial (Settings → Experimental → Liquid Glass): 0 reads
 * straight through to whatever is behind the glass, 1 is fully frosted. This does NOT control how
 * strongly surfaces refract/bend — see [GlassTokens] — because a refraction dial and a
 * transparency dial answer different questions ("how thick is the glass" vs "how much of the tint
 * shows"), and conflating them meant turning the slider down for a subtler look also flattened the
 * edge-bend down to nothing.
 */
val LocalGlassIntensity = staticCompositionLocalOf { DefaultGlassIntensity }

/**
 * 0 = fully see-through, 1 = fully frosted. Read this wherever a glass surface computes its tint
 * alpha; blur/refraction intentionally do not use it.
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
 * Legacy entry point, kept for call sites that have not moved to [GlassTokens] yet. The values
 * come from the reference implementation (kyant/AndroidLiquidGlass-kmp, `LiquidButton.kt`:
 * `blur(2.dp)`, `lens(12.dp, 24.dp)`). [scale] lets larger surfaces ask for proportionally bigger
 * values.
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
