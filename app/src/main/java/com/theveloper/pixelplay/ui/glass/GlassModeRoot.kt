package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.theveloper.pixelplay.ui.glass.light.LocalGlassLight
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLight
import com.theveloper.pixelplay.ui.glass.theme.GlassAmbientLayer
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassAmbient
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.theme.rememberGlassAmbientState
import com.theveloper.pixelplay.ui.glass.theme.rememberGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.ProvideUISensor

/**
 * The root of Liquid Glass mode (NexHome's root provider stack, orchestrator decision G1):
 *
 * ```
 * Box {
 *   GlassAmbientLayer(Modifier.layerBackdrop(root))   // the ONLY recorded layer
 *   content()                                          // every glass reader is its SIBLING
 * }
 * ```
 *
 * plus the one accelerometer ([ProvideUISensor]), the window's light ([LocalGlassLight]), the palette,
 * the ambient state (for sheets/dialogs in other windows), `LocalGlassBackdrop` / `LocalAppBackdrop`
 * = the root layer, and the shader warm-up after the first frame.
 *
 * HARD RULE (native SIGSEGV): `layerBackdrop(root)` sits on the ambient layer only. Nothing inside
 * [content] is a descendant of it, so any `drawBackdrop(root)` in the app is legal.
 *
 * When [enabled] is false (Material 3 mode) nothing glass is created: no ambient bake, no sensor, no
 * light, no warm-up; [content] is composed in the same place with empty defaults, so switching style
 * keeps the app's state.
 *
 * @param isDark the app's own dark setting (not the system's).
 * @param accent the album-art scheme's primary (else the app scheme's).
 * @param artUri the current song's artwork for the ambient bake.
 * @param blobs 2–3 palette colours for the ambient blobs, or null for NexHome's NEBULA.
 * @param powerSave battery saver: stops the accelerometer and skips the warm-up, nothing else (G5).
 */
@Composable
fun GlassModeRoot(
    enabled: Boolean,
    isDark: Boolean,
    accent: Color,
    artUri: String?,
    blobs: List<Color>?,
    powerSave: Boolean,
    content: @Composable () -> Unit,
) {
    val capability = LocalGlassCapability.current
    val active = enabled && capability.hasBlur
    val palette = rememberGlassPalette(isDark, accent)
    val root = if (active) rememberLayerBackdrop() else null
    val ambientState = if (active) rememberGlassAmbientState(isDark) else null
    val light = if (active) rememberGlassLight() else null

    Box(Modifier.fillMaxSize()) {
        if (root != null && ambientState != null) {
            GlassAmbientLayer(
                state = ambientState,
                artUri = artUri,
                blobs = blobs,
                isDark = isDark,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(root),
            )
        }
        ProvideUISensor(enabled = active && capability.hasLens && !powerSave) {
            val backdrop = root ?: emptyBackdrop()
            CompositionLocalProvider(
                LocalGlassModeEnabled provides active,
                LocalGlassPalette provides palette,
                LocalGlassLight provides light,
                LocalGlassAmbient provides ambientState,
                LocalGlassBackdrop provides backdrop,
                LocalAppBackdrop provides backdrop,
            ) {
                content()
            }
        }
        if (root != null) {
            GlassShaderWarmUp(
                backdrop = root,
                enabled = !powerSave,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}
