package com.theveloper.pixelplay.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The system high-contrast-text accessibility setting. MainActivity provides it around the main
 * UI and refreshes it on resume; anything composed outside that scope reads `false`.
 */
val LocalHighContrastText = staticCompositionLocalOf { false }

/** Reads the system "Remove animations" state (animator duration scale 0). */
fun readReduceMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}.getOrDefault(false)

/** Reads the system high-contrast-text setting. Not a public constant, hence the literal key. */
fun readHighContrastText(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
}.getOrDefault(false)

/** Battery saver as state, kept current by the system broadcast. */
@Composable
fun rememberPowerSaveMode(): State<Boolean> {
    val appContext = LocalContext.current.applicationContext
    val powerManager = remember(appContext) { appContext.getSystemService(PowerManager::class.java) }
    val state = remember(appContext) { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state.value = powerManager?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        state.value = powerManager?.isPowerSaveMode == true
        onDispose { appContext.unregisterReceiver(receiver) }
    }
    return state
}
