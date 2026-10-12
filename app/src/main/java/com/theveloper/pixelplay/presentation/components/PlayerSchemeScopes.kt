package com.theveloper.pixelplay.presentation.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

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
