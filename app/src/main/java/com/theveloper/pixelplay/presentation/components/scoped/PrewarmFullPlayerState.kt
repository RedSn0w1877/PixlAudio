package com.theveloper.pixelplay.presentation.components.scoped

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Composes a throw-away full player for a couple of frames when the first song appears, so its
 * first composition (fonts, images, pager) is warm before the user expands. One-shot per
 * "has a song" session: once the real full player is kept composed (see
 * [rememberFullPlayerCompositionPolicy]) a second copy on every skip is pure waste.
 */
@Composable
internal fun rememberPrewarmFullPlayer(hasCurrentSong: Boolean): Boolean {
    val context = LocalContext.current

    // OPT #5: Skip prewarm entirely on low-RAM devices. Having two FullPlayerContent
    // instances in the composition tree simultaneously (even with alpha=0) doubles
    // the recomposition cost. On low-end hardware this is not worth the UX benefit.
    val isLowRamDevice = remember(context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.isLowRamDevice
    }

    var prewarmFullPlayer by remember { mutableStateOf(false) }

    if (isLowRamDevice) return false

    LaunchedEffect(hasCurrentSong) {
        if (hasCurrentSong) {
            prewarmFullPlayer = true
        }
    }
    LaunchedEffect(hasCurrentSong, prewarmFullPlayer) {
        if (prewarmFullPlayer) {
            delay(32)
            prewarmFullPlayer = false
        }
    }

    return prewarmFullPlayer
}
