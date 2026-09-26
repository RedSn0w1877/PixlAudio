package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Keeps a window of items composed beyond the viewport (600 dp ahead and behind, the recipe from
 * NexHome's ScrollCache). The list pre-builds rows and starts their image loads during idle frames,
 * and reuses them when scrolling back, instead of composing each row on the frame it appears.
 */
@OptIn(ExperimentalFoundationApi::class)
private val AppListCacheWindow = LazyLayoutCacheWindow(ahead = 600.dp, behind = 600.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAppListState(): LazyListState = rememberLazyListState(cacheWindow = AppListCacheWindow)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAppGridState(): LazyGridState = rememberLazyGridState(cacheWindow = AppListCacheWindow)
