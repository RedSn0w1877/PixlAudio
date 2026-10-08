package com.theveloper.pixelplay.presentation.viewmodel

import android.media.AudioDeviceInfo
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectActiveDevice
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectDevice
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectUiState
import com.theveloper.pixelplay.data.spotify.connect.SpotifyDeviceKind
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Where media plays, for the full player's output pill (AudioDeviceInfo.TYPE_* are inlined constants). */
class AudioOutputCategoryTest {

    @Test fun `device types map to categories`() {
        assertEquals(AudioOutputCategory.Bluetooth, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Bluetooth, AudioDeviceInfo.TYPE_BLE_HEADSET.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Bluetooth, AudioDeviceInfo.TYPE_HEARING_AID.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Usb, AudioDeviceInfo.TYPE_USB_HEADSET.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Wired, AudioDeviceInfo.TYPE_WIRED_HEADPHONES.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Cast, AudioDeviceInfo.TYPE_HDMI.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.BuiltIn, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.BuiltIn, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Other, AudioDeviceInfo.TYPE_TELEPHONY.toAudioOutputCategory())
        assertEquals(AudioOutputCategory.Other, 9_999.toAudioOutputCategory())
    }

    @Test fun `the phone speaker never carries a name`() {
        // Android reports the model as the speaker's product name.
        val output = localAudioOutputOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 10 Pro", "Pixel 10 Pro", "Buds")
        assertEquals(LocalAudioOutput.Phone, output)
    }

    @Test fun `names equal to the phone model or blank are dropped`() {
        val wired = localAudioOutputOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "pixel 10 pro", "Pixel 10 Pro", null)
        assertEquals(LocalAudioOutput(AudioOutputCategory.Wired, null), wired)
        val usb = localAudioOutputOf(AudioDeviceInfo.TYPE_USB_HEADSET, " USB-C DAC ", "Pixel 10 Pro", null)
        assertEquals(LocalAudioOutput(AudioOutputCategory.Usb, "USB-C DAC"), usb)
    }

    @Test fun `a blank Bluetooth name falls back to the name read another way`() {
        val output = localAudioOutputOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "", "Pixel 10 Pro", "Pixel Buds Pro")
        assertEquals(LocalAudioOutput(AudioOutputCategory.Bluetooth, "Pixel Buds Pro"), output)
        val none = localAudioOutputOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, "Pixel 10 Pro", " ")
        assertEquals(LocalAudioOutput(AudioOutputCategory.Bluetooth, null), none)
    }

    @Test fun `the API 30-32 fallback prefers Bluetooth, then USB, wired and HDMI, never the phone`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val telephony = AudioDeviceInfo.TYPE_TELEPHONY
        assertNull(legacyMediaRouteIndex(listOf(earpiece, speaker, telephony)))
        assertNull(legacyMediaRouteIndex(emptyList()))
        val usb = AudioDeviceInfo.TYPE_USB_HEADSET
        val bt = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET
        val hdmi = AudioDeviceInfo.TYPE_HDMI
        assertEquals(3, legacyMediaRouteIndex(listOf(speaker, wired, usb, bt)))
        assertEquals(2, legacyMediaRouteIndex(listOf(speaker, wired, usb)))
        assertEquals(1, legacyMediaRouteIndex(listOf(hdmi, wired, speaker)))
        assertEquals(0, legacyMediaRouteIndex(listOf(hdmi, speaker, telephony)))
    }

    @Test fun `the top bar names the Connect device playing, or the one connecting`() {
        assertNull(topBarChipOf(SpotifyConnectUiState()))
        val active = SpotifyConnectUiState(
            active = SpotifyConnectActiveDevice("echo", "Kitchen Echo", "Speaker", supportsVolume = true, volumePercent = 40)
        )
        assertEquals(SpotifyConnectChip("Kitchen Echo", SpotifyDeviceKind.SPEAKER, connecting = false), topBarChipOf(active))
        val tv = SpotifyConnectDevice("tv", "Living Room TV", "TV")
        val connecting = SpotifyConnectUiState(devices = persistentListOf(tv), connectingDeviceId = "tv")
        assertEquals(SpotifyConnectChip("Living Room TV", SpotifyDeviceKind.TV, connecting = true), topBarChipOf(connecting))
        // Connecting to a device the list doesn't hold yet: nothing to name.
        assertNull(topBarChipOf(SpotifyConnectUiState(connectingDeviceId = "gone")))
    }
}
