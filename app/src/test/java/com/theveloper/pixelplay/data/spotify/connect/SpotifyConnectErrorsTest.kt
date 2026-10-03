package com.theveloper.pixelplay.data.spotify.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Error mapping (ported from iOS `errorsMapToTheSpecMessages`). */
class SpotifyConnectErrorsTest {
    private fun map(status: Int, body: String, retryAfter: String? = null) =
        SpotifyConnectErrors.map(status, body, retryAfter)

    @Test fun `errors map to the spec messages`() {
        val premium = map(403, """{"error":{"status":403,"message":"Player command failed: Premium required","reason":"PREMIUM_REQUIRED"}}""")
        assertEquals(SpotifyConnectError.PremiumRequired, premium)
        assertEquals("Spotify Connect needs Spotify Premium", premium.userMessage)
        assertEquals(SpotifyConnectError.PremiumRequired, map(403, """{"error":{"status":403,"message":"Premium required"}}"""))
        assertEquals(
            SpotifyConnectError.NoActiveDevice,
            map(404, """{"error":{"status":404,"message":"Player command failed: No active device found","reason":"NO_ACTIVE_DEVICE"}}""")
        )
        assertEquals(SpotifyConnectError.NoActiveDevice, map(404, ""))
        val scope = map(403, """{"error":{"status":403,"message":"Insufficient client scope"}}""")
        assertEquals(SpotifyConnectError.MissingScope, scope)
        assertEquals("Reconnect Spotify to use Connect", scope.userMessage)
        assertEquals(SpotifyConnectError.MissingScope, map(401, """{"error":{"status":401,"message":"Permissions missing"}}"""))
        assertEquals(
            SpotifyConnectError.Failed(403, "Player command failed: Restriction violated"),
            map(403, """{"error":{"status":403,"message":"Player command failed: Restriction violated","reason":"UNKNOWN"}}""")
        )
        assertEquals(SpotifyConnectError.VolumeNotSupported, map(403, """{"error":{"status":403,"message":"x","reason":"VOLUME_CONTROL_DISALLOW"}}"""))
        assertEquals(SpotifyConnectError.DeviceRestricted, map(403, """{"error":{"status":403,"message":"Device is restricted"}}"""))
        assertEquals(SpotifyConnectError.DeviceRestricted, map(403, """{"error":{"status":403,"message":"x","reason":"DEVICE_NOT_CONTROLLABLE"}}"""))
        assertEquals(SpotifyConnectError.RateLimited(7_000), map(429, "", "7"))
        assertEquals(
            SpotifyConnectError.RateLimited(5_000),
            map(429, """{"error":{"status":429,"message":"Too many requests","reason":"QUOTA_EXCEEDED"}}""")
        )
        assertEquals(SpotifyConnectError.RateLimited(60_000), map(429, "", "600"))
        assertEquals(SpotifyConnectError.RateLimited(1_000), map(429, "", "0"))
        assertEquals("Spotify is busy. Try again in 2 s", SpotifyConnectError.RateLimited(1_200).userMessage)
        assertEquals(SpotifyConnectError.Unavailable(503), map(503, ""))
        assertEquals(SpotifyConnectError.Failed(400, "Malformed json"), map(400, """{"error":{"status":400,"message":"Malformed json"}}"""))
        assertEquals(SpotifyConnectError.NotSignedIn, map(401, "{}"))
        assertEquals(SpotifyConnectError.Failed(400, null), map(400, "not json at all"))
    }

    @Test fun `only some errors end a running session`() {
        assertTrue(SpotifyConnectError.PremiumRequired.endsSession)
        assertTrue(SpotifyConnectError.MissingScope.endsSession)
        assertTrue(SpotifyConnectError.NoActiveDevice.endsSession)
        assertFalse(SpotifyConnectError.RateLimited(1_000).endsSession)
        assertFalse(SpotifyConnectError.Network(null).endsSession)
        assertFalse(SpotifyConnectError.VolumeNotSupported.endsSession)
    }
}
