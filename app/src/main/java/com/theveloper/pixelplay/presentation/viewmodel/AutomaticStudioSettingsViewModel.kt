package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.AutomaticStudioManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AutomaticStudioSettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val studio: AutomaticStudioManager
) : ViewModel() {
    val lyricsEnabled = preferences.automaticLyricsEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val instrumentalsEnabled = preferences.automaticInstrumentalsEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val status = studio.status
    fun setLyrics(enabled: Boolean) { viewModelScope.launch { preferences.setAutomaticLyricsEnabled(enabled) } }
    fun setInstrumentals(enabled: Boolean) { viewModelScope.launch { preferences.setAutomaticInstrumentalsEnabled(enabled) } }
    fun checkNow() = studio.scanNow()
}
