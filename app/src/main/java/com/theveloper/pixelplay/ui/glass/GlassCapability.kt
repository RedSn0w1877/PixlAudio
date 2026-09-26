package com.theveloper.pixelplay.ui.glass

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the device can draw of the kit. The Backdrop library degrades silently below API 33 (no
 * `lens()`, no AGSL rims or glow) and draws nothing but an unfiltered copy below API 31, so the tier
 * is decided once, from the SDK level only.
 *
 * Owner decision (G5): there is **no battery-saver downgrade of the look**. Battery saver may only
 * stop the accelerometer (highlights keep a static angle) and skip the shader warm-up.
 */
enum class GlassCapability(val hasBlur: Boolean, val hasLens: Boolean) {
    /** API 30: no `RenderEffect` at all. Glass mode is never drawn; the app shows Material 3. */
    None(hasBlur = false, hasLens = false),

    /** API 31–32: vibrancy, blur and colour controls work, `lens()` and AGSL do not: frosted glass. */
    Frosted(hasBlur = true, hasLens = false),

    /** API 33+: the full NexHome kit. */
    Full(hasBlur = true, hasLens = true);

    companion object {
        fun forSdk(sdkInt: Int): GlassCapability = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> Full
            sdkInt >= Build.VERSION_CODES.S -> Frosted
            else -> None
        }

        /** The running device's tier. */
        val current: GlassCapability get() = forSdk(Build.VERSION.SDK_INT)
    }
}

/** The running device's [GlassCapability]; kit surfaces branch inside `effects {}` on it. */
val LocalGlassCapability = staticCompositionLocalOf { GlassCapability.current }

/**
 * Blur a frosted-tier (API 31–32) surface adds in place of the lens it can't draw. NexHome has no
 * tier below 33, so this is PixlAudio's value: the ambient layer it blurs is already soft, so a
 * moderate radius reads as frosted glass without smearing it into a flat fill.
 */
val FrostedBlur: Dp = 16.dp
