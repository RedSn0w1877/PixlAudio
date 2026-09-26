package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop

/**
 * The backdrop kit glass surfaces refract by default. In glass mode the root provides the baked
 * ambient layer's `LayerBackdrop` here ([GlassModeRoot]); a sheet or dialog in its own window
 * provides the window-aligned ambient ([rememberGlassWindowBackdrop]). Defaults to an empty backdrop,
 * so a surface composed outside glass mode never crashes.
 *
 * HARD RULE: never provide a LayerBackdrop captured by an ANCESTOR of the reading surface (a
 * `layerBackdrop(X)` above a `drawBackdrop(X)` is a RenderNode cycle that crashes HWUI natively).
 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The raw base layer (the ambient layer, never another glass surface), for surfaces that must sample
 * the wallpaper itself rather than the nearest glass, e.g. a sheet scrim. Mirrors NexHome's
 * `LocalAppBackdrop`, but defaults to an empty backdrop instead of throwing, because PixlAudio also
 * composes everything in Material 3 mode, where no glass root exists.
 */
val LocalAppBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * Whether Liquid Glass mode is drawing right now (the preference says glass, the device can do it,
 * and "Disable blur all over" is off; see `VisualStyle.isGlassMode`). Surfaces branch on this to
 * pick their glass or their Material 3 look. Static: it only changes when the user switches style.
 */
val LocalGlassModeEnabled = staticCompositionLocalOf { false }

/**
 * Performance policy for glass-on-glass. A container that exports its own glass for its children to
 * refract renders its lens a second time into the export, and every child re-renders whenever the
 * container does — so a scrolling sheet, or one animated aura inside a stage, re-renders the whole
 * chain (scrim → section → stage → dial → knob) every frame. On device (NexHome), that chain produced
 * 170–275 ms GPU frames.
 *
 * When true, nested containers stop exporting and [ProvideGlassBackdrop] is a no-op: children refract
 * the BASE layer (the ambient layer) directly. Every surface still refracts; only the second-order
 * "refracting the glass behind me" is dropped.
 */
const val FlattenNestedGlass = true

/**
 * Re-provides a container's exported glass to its children. A no-op under [FlattenNestedGlass]:
 * children keep refracting the base layer.
 */
@Composable
fun ProvideGlassBackdrop(backdrop: Backdrop, content: @Composable () -> Unit) {
    if (FlattenNestedGlass) {
        content()
    } else {
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop, content = content)
    }
}

/** Provides a BASE layer (the root ambient layer, a window's aligned ambient). Always takes effect. */
@Composable
fun ProvideBaseGlassBackdrop(backdrop: Backdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalAppBackdrop provides backdrop,
        content = content
    )
}
