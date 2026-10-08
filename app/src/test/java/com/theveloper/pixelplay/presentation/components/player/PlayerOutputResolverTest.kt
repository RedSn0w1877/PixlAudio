package com.theveloper.pixelplay.presentation.components.player

import com.theveloper.pixelplay.data.spotify.connect.SpotifyDeviceKind
import com.theveloper.pixelplay.presentation.viewmodel.AudioOutputCategory
import com.theveloper.pixelplay.presentation.viewmodel.LocalAudioOutput
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyConnectChip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The full player's output pill: which output it shows (owner decision 2026-10-07, same as iOS). */
class PlayerOutputResolverTest {

    private fun resolve(
        castConnecting: Boolean = false,
        castRemote: Boolean = false,
        routeName: String? = null,
        connect: SpotifyConnectChip? = null,
        local: LocalAudioOutput = LocalAudioOutput.Phone,
    ) = resolvePlayerOutput(castConnecting, castRemote, routeName, connect, local)

    @Test fun `the phone speaker is icon only`() {
        val output = resolve()
        assertEquals(PlayerOutputKind.PHONE, output.kind)
        assertNull(output.name)
        assertFalse(output.isRemote)
        assertFalse(output.isConnecting)
    }

    @Test fun `Bluetooth shows its name, or nothing for the generic label`() {
        val named = resolve(local = LocalAudioOutput(AudioOutputCategory.Bluetooth, "Pixel Buds Pro"))
        assertEquals(PlayerOutputKind.BLUETOOTH, named.kind)
        assertEquals("Pixel Buds Pro", named.name)
        assertFalse(named.isRemote)

        val unnamed = resolve(local = LocalAudioOutput(AudioOutputCategory.Bluetooth, null))
        assertEquals(PlayerOutputKind.BLUETOOTH, unnamed.kind)
        assertNull(unnamed.name)
    }

    @Test fun `USB, wired, HDMI and other outputs map to their kinds`() {
        val usb = resolve(local = LocalAudioOutput(AudioOutputCategory.Usb, "USB-C DAC"))
        assertEquals(PlayerOutputKind.USB, usb.kind)
        assertEquals("USB-C DAC", usb.name)
        assertEquals(PlayerOutputKind.WIRED, resolve(local = LocalAudioOutput(AudioOutputCategory.Wired, null)).kind)
        assertEquals(PlayerOutputKind.DIGITAL, resolve(local = LocalAudioOutput(AudioOutputCategory.Cast, "TV")).kind)
        assertEquals(PlayerOutputKind.OTHER, resolve(local = LocalAudioOutput(AudioOutputCategory.Other, null)).kind)
    }

    @Test fun `a cast that is connecting wins over everything`() {
        val output = resolve(
            castConnecting = true,
            castRemote = true,
            routeName = "Living Room",
            connect = SpotifyConnectChip("Kitchen Echo", SpotifyDeviceKind.SPEAKER, connecting = false),
            local = LocalAudioOutput(AudioOutputCategory.Bluetooth, "Buds"),
        )
        assertEquals(PlayerOutputKind.CAST, output.kind)
        assertTrue(output.isConnecting)
        assertNull(output.name)
    }

    @Test fun `a Spotify Connect device wins over a local Bluetooth output`() {
        val output = resolve(
            connect = SpotifyConnectChip("Kitchen Echo", SpotifyDeviceKind.SPEAKER, connecting = false),
            local = LocalAudioOutput(AudioOutputCategory.Bluetooth, "Buds"),
        )
        assertEquals(PlayerOutputKind.SPOTIFY_CONNECT, output.kind)
        assertEquals("Kitchen Echo", output.name)
        assertEquals(SpotifyDeviceKind.SPEAKER, output.connectKind)
        assertTrue(output.isRemote)
        assertFalse(output.isConnecting)
    }

    @Test fun `a Connect session that is starting shows as connecting, without the dot`() {
        val output = resolve(connect = SpotifyConnectChip("Living Room TV", SpotifyDeviceKind.TV, connecting = true))
        assertEquals(PlayerOutputKind.SPOTIFY_CONNECT, output.kind)
        assertTrue(output.isConnecting)
        assertFalse(output.isRemote)
        assertEquals("Living Room TV", output.name)
    }

    @Test fun `a cast route shows its name and the dot`() {
        val output = resolve(castRemote = true, routeName = "Living Room speaker")
        assertEquals(PlayerOutputKind.CAST, output.kind)
        assertEquals("Living Room speaker", output.name)
        assertTrue(output.isRemote)
    }

    @Test fun `a cast route without a name falls back to the generic label`() {
        val output = resolve(castRemote = true, routeName = "  ")
        assertEquals(PlayerOutputKind.CAST, output.kind)
        assertNull(output.name)
        assertTrue(output.isRemote)
    }
}
