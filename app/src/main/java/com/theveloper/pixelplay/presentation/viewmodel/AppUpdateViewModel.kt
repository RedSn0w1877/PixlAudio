package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.theveloper.pixelplay.data.update.AppUpdateManager
import com.theveloper.pixelplay.data.update.AppUpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val manager: AppUpdateManager
) : ViewModel() {
    val state: StateFlow<AppUpdateState> = manager.state
    val installedVersionName: String = manager.installedVersionName
    val canSelfUpdate: Boolean = manager.canSelfUpdate

    fun checkForUpdates() = manager.checkNow()

    fun downloadAndInstall() = manager.downloadAndInstall()
}
