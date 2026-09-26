package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.LaunchTab
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.data.worker.SyncProgress
import com.theveloper.pixelplay.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The few preferences the very first frame depends on, read together from the settings
 * DataStore. MainActivity keeps the splash screen up until this is known, so the first frame
 * already has the right theme, the right setup gate and the right start tab.
 */
data class StartupPrefs(
    val setupDone: Boolean,
    val themeMode: String,
    val launchTab: String,
    val showScrollbar: Boolean
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncManager: SyncManager,
    musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    themePreferencesRepository: ThemePreferencesRepository
) : ViewModel() {

    /**
     * Startup snapshot. `null` until the DataStore answers (usually 10-40 ms); a failed read
     * falls back to the defaults so the splash never hangs.
     */
    val startupPrefs: StateFlow<StartupPrefs?> = combine(
        userPreferencesRepository.initialSetupDoneFlow,
        themePreferencesRepository.appThemeModeFlow,
        userPreferencesRepository.launchTabFlow,
        userPreferencesRepository.showScrollbarFlow
    ) { setupDone, themeMode, launchTab, showScrollbar ->
        StartupPrefs(setupDone, themeMode, launchTab, showScrollbar)
    }
        .distinctUntilChanged()
        .catch { emit(StartupPrefs(false, AppThemeMode.FOLLOW_SYSTEM, LaunchTab.HOME, true)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val isSetupComplete: StateFlow<Boolean?> = userPreferencesRepository.initialSetupDoneFlow
        .map { it as Boolean? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val hasCompletedInitialSync: StateFlow<Boolean> = userPreferencesRepository.lastSyncTimestampFlow
        .map { it > 0L }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true // 乐观策略：默认已同步
        )

    /**
     * Un Flow que emite `true` si el SyncWorker está encolado o en ejecución.
     * Ideal para mostrar un indicador de carga.
     */
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /**
     * Flow that exposes detailed sync progress including file count and phase.
     */
    val syncProgress: StateFlow<SyncProgress> = syncManager.syncProgress
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncProgress()
        )

    /**
     * Un Flow que emite `true` si la base de datos de Room no tiene canciones.
     * Nos ayuda a saber si es la primera vez que se abre la app.
     * A COUNT(*) query: loading and mapping every song just to learn "is it empty" competed
     * with the first seconds of animation, and re-ran on every write during a sync.
     */
    val isLibraryEmpty: StateFlow<Boolean> = musicRepository
        .getSongCountFlow()
        .map { it == 0 }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /**
     * Función para iniciar la sincronización de la biblioteca de música.
     * Se debe llamar después de que los permisos hayan sido concedidos.
     */
    fun startSync() {
        LogUtils.i(this, "startSync called")
        viewModelScope.launch {
            // For fresh installs after setup, SetupViewModel.setSetupComplete() triggers sync
            // For returning users (setup already complete), we trigger sync here
            // The gate can open from the startup snapshot a moment before isSetupComplete has
            // its first value, so fall back to reading the flag directly.
            val setupDone = isSetupComplete.value
                ?: userPreferencesRepository.initialSetupDoneFlow.first()
            if (setupDone) {
                syncManager.sync()
            }
        }
    }
}
