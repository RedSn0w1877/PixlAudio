package com.theveloper.pixelplay.presentation.model

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether Settings › Developer mode is on. Everything a normal listener wouldn't understand
 * (diagnostics, keys, tuning, testing tools) only shows while this is true; hiding a row never
 * changes the value behind it. Provided once in `MainActivity` from
 * `UserPreferencesRepository.developerModeFlow`; read it with `LocalDeveloperMode.current`.
 */
val LocalDeveloperMode = compositionLocalOf { false }
