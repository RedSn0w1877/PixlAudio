package com.theveloper.pixelplay.data.spotify.connect

import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** URI building, skipping and chunking (ported from iOS `SpotifyConnectWindowTests`). */
class SpotifyConnectWindowTest {
    private fun uri(id: String) = SpotifyConnectSlot.Uri(id)
    private val skipped = SpotifyConnectSlot.Skipped
    private val pending = SpotifyConnectSlot.Pending

    @Test fun `real track ids and synthetic YouTube Music ids`() {
        assertTrue(SpotifyConnect.isTrackId("4iV5W9uYEdYUVa79Axb7Rh"))
        assertFalse(SpotifyConnect.isTrackId("0123456789abcdef012345")) // SHA-256 hex prefix
        assertFalse(SpotifyConnect.isTrackId("short"))
        assertFalse(SpotifyConnect.isTrackId("4iV5W9uYEdYUVa79Axb7R!"))
        assertFalse(SpotifyConnect.isTrackId(null))
        assertEquals("spotify:track:4iV5W9uYEdYUVa79Axb7Rh", SpotifyConnect.directUri("4iV5W9uYEdYUVa79Axb7Rh"))
        assertNull(SpotifyConnect.directUri(null))
        assertEquals("4iV5W9uYEdYUVa79Axb7Rh", SpotifyConnect.trackIdFromUri("spotify:track:4iV5W9uYEdYUVa79Axb7Rh"))
        assertNull(SpotifyConnect.trackIdFromUri("spotify:episode:4iV5W9uYEdYUVa79Axb7Rh"))
        // Synthetic ids for YouTube Music rows (SpotifyRepository.youTubeMusicSyntheticId: the first
        // 22 hex characters of a SHA-256) are never sent as Spotify ids.
        val synthetic = java.security.MessageDigest.getInstance("SHA-256")
            .digest("dQw4w9WgXcQ".toByteArray()).joinToString("") { "%02x".format(it) }.take(22)
        assertFalse(SpotifyConnect.isTrackId(synthetic))
    }

    @Test fun `a song with a Spotify id plays directly, others need a lookup`() {
        val spotify = ConnectTrack("1", "A", "B", 1_000, "4iV5W9uYEdYUVa79Axb7Rh", null)
        val synthetic = ConnectTrack("2", "A", "B", 1_000, "0123456789abcdef012345", null)
        val local = ConnectTrack("3", "A", "B", 1_000, null, "/music/a.flac")
        assertEquals("spotify:track:4iV5W9uYEdYUVa79Axb7Rh", spotify.directUri)
        assertNull(synthetic.directUri)
        assertNull(local.directUri)
    }

    @Test fun `windows skip misses, stop at the first unresolved entry and cap at the limit`() {
        val slots = listOf(uri("u0"), skipped, uri("u2"), uri("u3"), skipped, pending, uri("u6"))
        val fromStart = SpotifyConnectWindow.make(slots, 0)
        assertEquals(listOf("u0", "u2", "u3"), fromStart.uris)
        assertEquals(listOf(0, 2, 3), fromStart.queueIndices)
        assertTrue(SpotifyConnectWindow.hasMore(fromStart, slots))
        assertEquals(listOf(2, 3), SpotifyConnectWindow.make(slots, 1).queueIndices)
        assertEquals(listOf("u0", "u2"), SpotifyConnectWindow.make(slots, 0, maxCount = 2).uris)
        assertEquals(listOf("u6"), SpotifyConnectWindow.make(slots, 6).uris)
        assertFalse(SpotifyConnectWindow.hasMore(SpotifyConnectWindow.make(slots, 6), slots))
        assertTrue(SpotifyConnectWindow.make(slots, 9).isEmpty)
        assertEquals(2, SpotifyConnectWindow.skippedCount(slots, 0, 7))
        assertEquals(1, SpotifyConnectWindow.skippedCount(slots, 3, 99))
        val tail = listOf(uri("a"), skipped, skipped)
        assertFalse(SpotifyConnectWindow.hasMore(SpotifyConnectWindow.make(tail, 0), tail))
    }

    @Test fun `long queues are chunked into windows of 100 from the current entry`() {
        val long = (0 until 250).map { uri("u$it") }
        val first = SpotifyConnectWindow.make(long, 0)
        assertEquals(SpotifyConnect.MAX_URIS_PER_PLAY, first.count)
        assertEquals(99, first.queueIndices.last())
        assertTrue(SpotifyConnectWindow.hasMore(first, long))
        val middle = SpotifyConnectWindow.make(long, 120)
        assertEquals(100, middle.count)
        assertEquals(120, middle.queueIndices.first())
        assertEquals(219, middle.queueIndices.last())
        val last = SpotifyConnectWindow.make(long, 200)
        assertEquals(50, last.count)
        assertFalse(SpotifyConnectWindow.hasMore(last, long))
        // Skipped entries don't count toward the cap.
        val sparse = (0 until 300).map { if (it % 2 == 0) uri("u$it") else skipped }
        val window = SpotifyConnectWindow.make(sparse, 0)
        assertEquals(100, window.count)
        assertEquals(198, window.queueIndices.last())
    }

    @Test fun `positions handle duplicate songs and the skipped toast reads right`() {
        val window = SpotifyConnectWindow(listOf("a", "b", "a", "c"), listOf(4, 5, 6, 7))
        assertEquals(0, window.positionOf("a", 0))
        assertEquals(2, window.positionOf("a", 1))
        assertEquals(0, window.positionOf("a", 3))
        assertNull(window.positionOf("z", 0))
        assertEquals(2, window.positionOfQueueIndex(6))
        assertNull(SpotifyConnectWindow.skippedMessage(0))
        assertEquals("1 song isn't on Spotify and was skipped", SpotifyConnectWindow.skippedMessage(1))
        assertEquals("4 songs aren't on Spotify and were skipped", SpotifyConnectWindow.skippedMessage(4))
    }

    @Test fun `scopes are appended at the end and old logins are detected`() {
        assertEquals(listOf("user-read-playback-state", "user-modify-playback-state"), SpotifyConnect.REQUIRED_SCOPES)
        assertEquals(SpotifyConnect.REQUIRED_SCOPES.toSet(), SpotifyAuthManager.CONNECT_SCOPES)
        assertTrue(SpotifyConnect.missingScopes(null).isEmpty())
        assertEquals(
            SpotifyConnect.REQUIRED_SCOPES,
            SpotifyConnect.missingScopes(setOf("user-library-read", "user-top-read"))
        )
        assertTrue(SpotifyConnect.missingScopes(SpotifyConnect.REQUIRED_SCOPES.toSet() + "user-top-read").isEmpty())
        assertEquals(setOf("a", "b"), SpotifyAuthManager.parseScopes(" a  b "))
        assertNull(SpotifyAuthManager.parseScopes("  "))
    }

    @Test fun `devices list the active one first, then controllable ones by name`() {
        val devices = listOf(
            SpotifyConnectDevice("1", "Zed", "Speaker"),
            SpotifyConnectDevice(null, "Ghost", "Speaker"),
            SpotifyConnectDevice("2", "Alpha", "TV", isRestricted = true),
            SpotifyConnectDevice("3", "Kitchen Echo", "Speaker", isActive = true),
            SpotifyConnectDevice("4", "beta", "Computer")
        )
        assertEquals(
            listOf("Kitchen Echo", "beta", "Zed", "Alpha", "Ghost"),
            SpotifyConnectDevice.sortedForDisplay(devices).map { it.name }
        )
        assertEquals(SpotifyDeviceKind.SPEAKER, SpotifyDeviceKind.fromApiType("Speaker"))
        assertEquals(SpotifyDeviceKind.AVR, SpotifyDeviceKind.fromApiType("AVR"))
        assertEquals(SpotifyDeviceKind.CAST_AUDIO, SpotifyDeviceKind.fromApiType("CastAudio"))
        assertEquals(SpotifyDeviceKind.UNKNOWN, SpotifyDeviceKind.fromApiType("Toaster"))
    }
}
