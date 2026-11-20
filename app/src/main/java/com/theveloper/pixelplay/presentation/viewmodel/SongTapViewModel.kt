package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.songtap.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Song Tap screen's handle on [SongTapStateHolder]; the state itself lives in the holder. */
@HiltViewModel
class SongTapViewModel @Inject constructor(
    private val holder: SongTapStateHolder,
) : ViewModel() {

    val ui = holder.ui
    val haptics = holder.haptics

    fun attach() = holder.attach()
    fun allow(on: Boolean) = holder.allow(on)
    fun arm(role: Role) = holder.arm(role)
    fun disarm() = holder.disarm()
    fun switchRole(role: Role) = holder.switchRole(role)
    fun showCode() = holder.showCode()
    fun hideCode() = holder.hideCode()
    fun onScanned(text: String) = holder.onScanned(text)
    fun accept() = holder.accept()
    fun decline() = holder.decline()
    fun undo() = holder.undo()
    fun dismiss() = holder.dismiss()

    override fun onCleared() {
        holder.detach()
    }
}

/** The two Song Tap rows in Settings: the switch, and the way into the screen. Reads only the small settings store. */
@HiltViewModel
class SongTapSettingsViewModel @Inject constructor(
    private val settings: com.theveloper.pixelplay.data.songtap.SongTapSettings,
) : ViewModel() {

    val allowed = settings.allowed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAllowed(on: Boolean) {
        viewModelScope.launch { settings.setAllowed(on) }
    }
}
