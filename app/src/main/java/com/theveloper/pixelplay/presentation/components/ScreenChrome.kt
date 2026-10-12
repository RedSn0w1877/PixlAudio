package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.theveloper.pixelplay.R

/**
 * Recomposition boundary for a screen's floating chrome (top bar, back button, actions).
 *
 * Deliberately a non-inline composable: state read inside [content] — the per-frame collapse
 * height or fraction of a collapsing header — recomposes only this lambda, not the whole screen.
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
