package com.theveloper.pixelplay.presentation.viewmodel

import android.media.AudioDeviceInfo
import androidx.compose.runtime.Immutable

/** What kind of output an [AudioDeviceInfo] is (Device capabilities and the full player's output pill). */
enum class AudioOutputCategory {
    BuiltIn,
    Bluetooth,
    Usb,
    Wired,
    Cast,
    Other
}

internal fun Int.toAudioOutputCategory(): AudioOutputCategory {
    return when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutputCategory.BuiltIn
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputCategory.Bluetooth
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputCategory.Usb
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioOutputCategory.Wired
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> AudioOutputCategory.Cast
        else -> AudioOutputCategory.Other
    }
}

/**
 * Where this phone's media audio goes right now (not Cast or Spotify Connect, which play on
 * another device): the category, and the device's own name when it has a useful one.
 */
@Immutable
data class LocalAudioOutput(val category: AudioOutputCategory, val name: String?) {
    companion object {
        /** The phone's own speaker (or earpiece): no name, the pill shows only its icon. */
        val Phone = LocalAudioOutput(AudioOutputCategory.BuiltIn, null)
    }
}

/**
 * The [LocalAudioOutput] for a device of [type] called [productName].
 *
 * - Built-in outputs never carry a name: Android reports the phone model ("Pixel 10 Pro") as the
 *   speaker's product name, and the owner wants the phone speaker icon-only.
 * - Other outputs keep their product name unless it is blank or just the phone model again (some
 *   wired and HDMI ports report that), else [fallbackName] (a Bluetooth name read another way).
 */
internal fun localAudioOutputOf(
    type: Int,
    productName: String?,
    model: String,
    fallbackName: String?
): LocalAudioOutput {
    val category = type.toAudioOutputCategory()
    if (category == AudioOutputCategory.BuiltIn) return LocalAudioOutput.Phone
    val own = productName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(model.trim(), ignoreCase = true) }
    val name = own ?: fallbackName?.trim()?.takeIf { it.isNotEmpty() }
    return LocalAudioOutput(category, name)
}

/**
 * API 30-32, where the routed device can't be asked for (`getAudioDevicesForAttributes` is API
 * 33): the index in [types] (connected output types) of the sink Android's media routing prefers,
 * or null when media plays on the phone itself. Bluetooth wins over USB, then wired, then HDMI;
 * built-in and odd outputs (telephony, FM, remote submix…) are never picked, so they can't make
 * the pill claim an "Other output".
 */
internal fun legacyMediaRouteIndex(types: List<Int>): Int? {
    var best: Int? = null
    var bestPriority = Int.MAX_VALUE
    types.forEachIndexed { index, type ->
        val priority = when (type.toAudioOutputCategory()) {
            AudioOutputCategory.Bluetooth -> 0
            AudioOutputCategory.Usb -> 1
            AudioOutputCategory.Wired -> 2
            AudioOutputCategory.Cast -> 3
            AudioOutputCategory.Other, AudioOutputCategory.BuiltIn -> return@forEachIndexed
        }
        if (priority < bestPriority) {
            best = index
            bestPriority = priority
        }
    }
    return best
}
