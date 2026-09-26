package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable

/**
 * Recomposition boundary for a screen's floating chrome (top bar, back button, actions).
 *
 * Deliberately a non-inline composable: state read inside [content] — the per-frame collapse
 * height or fraction of a collapsing header — recomposes only this lambda, not the whole screen.
 * Also the seam where the rebuilt glass mode can wrap a screen's chrome.
 */
@Composable
fun ScreenChrome(content: @Composable () -> Unit) {
    content()
}

/**
 * The same boundary for the scrolling layer of collapsing-header screens (album and artist
 * detail).
 */
@Composable
fun BoxScope.ScreenLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}
