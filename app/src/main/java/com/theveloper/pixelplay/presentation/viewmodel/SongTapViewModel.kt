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

    /** The approach glow, 0..500 permille. */
    val glow = holder.glow

    /** The numbers behind the tap detector (Developer mode diagnostics). */
    val diag = holder.diag

    fun attach() = holder.attach(this)
    fun allow(on: Boolean) = holder.allow(on)
    fun arm(role: Role) = holder.arm(role)
    fun disarm() = holder.disarm()
    fun switchRole(role: Role) = holder.switchRole(role)
    fun showCode() = holder.showCode()
    fun hideCode() = holder.hideCode()

    /** "Use QR code instead" on the sender. */
    fun showQrInstead() = holder.showQrInstead()

    // The ring code, receiving: the camera's analysis thread reports to [ringSink], the scan screen opens and closes the session.
    val ringSink get() = holder.ringSink
    val ringDiag get() = holder.ringDiag
    fun ringScanStart(cameraAvailable: Boolean) = holder.ringScanStart(cameraAvailable)
    fun ringScanStop() = holder.ringScanStop()
    fun ringCameraFailed() = holder.ringCameraFailed()
    fun onScanned(text: String) = holder.onScanned(text)
    fun accept() = holder.accept()
    fun decline() = holder.decline()
    fun undo() = holder.undo()
    fun dismiss() = holder.dismiss()
    fun bluetoothChanged() = holder.bluetoothChanged()
    fun mark(label: String) = holder.mark(label)
    fun setSensitivity(value: com.theveloper.pixelplay.songtap.SongTapSensitivity) = holder.setSensitivity(value)
    fun startCalibration() = holder.startCalibration()
    fun endCalibration() = holder.endCalibration()
    fun resetOffset() = holder.resetOffset()

    // Song Drop (spec 5)
    fun approve(peer: Int, name: String) = holder.approve(peer, name)
    fun decideNo(peer: Int) = holder.decideNo(peer)
    fun cancelDrop() = holder.cancelDrop()
    fun endSongDrop() = holder.endSongDrop()

    override fun onCleared() {
        holder.detach(this)
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

    /** How sensitive the tap is: Normal or High (the default). */
    val sensitivity = settings.sensitivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.theveloper.pixelplay.songtap.SongTapSensitivity.DEFAULT)

    fun setSensitivity(value: com.theveloper.pixelplay.songtap.SongTapSensitivity) {
        viewModelScope.launch { settings.setSensitivity(value) }
    }

    /** The name other phones show for this one (the device's name until the listener types one). */
    val name = settings.name.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.defaultName())

    /** Saves the name; an empty one goes back to the device's name. */
    fun setName(raw: String) {
        viewModelScope.launch { settings.setName(raw) }
    }
}

/**
 * The song options sheet's Song Drop button: hands the song to Song Tap and asks the navigation layer to open the picker.
 * A view model of its own so every place that shows the sheet gets the button without being given a navigation handle.
 */
@HiltViewModel
class SongDropViewModel @Inject constructor(
    private val holder: SongTapStateHolder,
) : ViewModel() {

    /** Raised each time a song's options asked for Song Drop. */
    val requests = holder.dropRequests

    fun start(song: com.theveloper.pixelplay.data.model.Song) = holder.beginSongDrop(song)
}
