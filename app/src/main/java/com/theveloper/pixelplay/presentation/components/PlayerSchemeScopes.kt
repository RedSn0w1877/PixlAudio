package com.theveloper.pixelplay.presentation.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.theme.rememberGlassPalette
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme

/*
 * The player's album colours fade over ~40 frames after every skip. Reading the animated
 * scheme in a big composable (the sheet, the full player host) recomposes all of it on every
 * one of those frames. These helpers are small restart scopes: the scheme is read inside them,
 * so a colour frame re-runs only the helper and whoever reads the provided colours.
 */

/**
 * Provides [scheme] as [LocalMaterialTheme]. The lambda is read here, in this function's own
 * restart scope, never in the caller's.
 */
@Composable
internal fun ProvidePlayerScheme(
    scheme: () -> ColorScheme,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalMaterialTheme provides scheme(), content = content)
}

/**
 * `MaterialTheme(colorScheme = scheme(), …)` with the outer typography and shapes, reading
 * [scheme] in this function's own restart scope.
 */
@Composable
internal fun PlayerSchemeMaterialTheme(
    scheme: () -> ColorScheme,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

/**
 * Glass mode: gives the player sheet (mini player, full player, its queue / cast / lyrics overlays)
 * its own glass palette, whose accent is the player's album colour ([GlassPalette.playerAccent]).
 * The app-wide glass accent takes the chosen accent (Settings › Appearance › Accent Color), so
 * without this the player would lose its album colours in glass mode. With the default Dynamic
 * accent the two palettes are equal under Album Art, and an equal palette (a data class, compared
 * structurally by the dynamic local) invalidates nobody.
 *
 * Its own restart scope: the album scheme changes once per song and re-runs only this wrapper and
 * the palette's readers. Material 3 mode collects nothing and passes the palette through.
 */
@Composable
internal fun ProvidePlayerGlassPalette(
    playerViewModel: PlayerViewModel,
    content: @Composable () -> Unit
) {
    // [content] is called from one place in both styles, so switching style keeps the sheet's state.
    val palette = if (LocalGlassModeEnabled.current) {
        val isDark = LocalPixelPlayDarkTheme.current
        val playerPair by playerViewModel.activePlayerColorSchemePair.collectAsStateWithLifecycle()
        val accent = GlassPalette.playerAccent(
            playerAlbumPrimary = playerPair?.let { if (isDark) it.dark else it.light }?.primary,
            appPrimary = MaterialTheme.colorScheme.primary
        )
        rememberGlassPalette(isDark, accent)
    } else {
        LocalGlassPalette.current
    }
    CompositionLocalProvider(LocalGlassPalette provides palette, content = content)
}

/**
 * Same pixels as `Modifier.background(color, shape)`, but [color] is read at draw time, so a
 * colour animation only redraws. The outline is rebuilt only when the size, the layout
 * direction or a state read inside [shape] changes.
 */
internal fun Modifier.drawBackgroundOutline(
    shape: Shape,
    color: () -> Color
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind { drawOutline(outline, color = color()) }
}
