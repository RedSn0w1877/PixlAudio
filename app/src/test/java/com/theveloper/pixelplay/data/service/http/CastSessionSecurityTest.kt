package com.theveloper.pixelplay.data.service.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastSessionSecurityTest {

    @Test
    fun `buildAccessPolicy keeps existing token and narrows song ids`() {
        val policy = CastSessionSecurity.buildAccessPolicy(
            existingToken = "existing-token",
            allowedSongIds = listOf("1", " 2 ", "", "1"),
            castDeviceIpHint = "192.168.1.50"
        )

        assertEquals("existing-token", policy.authToken)
        assertEquals(setOf("1", "2"), policy.allowedSongIds)
        assertTrue(policy.enforceClientAddressAllowlist)
        assertTrue(policy.allowedClientAddresses.contains("192.168.1.50"))
    }

    @Test
    fun `isAuthorizedClientAddress always allows loopback`() {
        val policy = CastAccessPolicy.EMPTY.copy(
            enforceClientAddressAllowlist = true,
            allowedClientAddresses = setOf("192.168.1.50")
        )

        assertTrue(CastSessionSecurity.isAuthorizedClientAddress("127.0.0.1", policy))
        assertTrue(CastSessionSecurity.isAuthorizedClientAddress("::1", policy))
        assertFalse(CastSessionSecurity.isAuthorizedClientAddress("192.168.1.80", policy))
    }

    @Test
    fun `isAuthorizedSongRequest requires matching token and whitelisted id`() {
        val policy = CastAccessPolicy(
            authToken = "token-123",
            allowedSongIds = setOf("42"),
            allowedClientAddresses = emptySet(),
            enforceClientAddressAllowlist = false
        )

        assertTrue(CastSessionSecurity.isAuthorizedSongRequest("token-123", "42", policy))
        assertFalse(CastSessionSecurity.isAuthorizedSongRequest("wrong", "42", policy))
        assertFalse(CastSessionSecurity.isAuthorizedSongRequest("token-123", "43", policy))
    }

    @Test
    fun `buildSongUrl appends auth token and encodes song id`() {
        val url = CastSessionSecurity.buildSongUrl(
            serverAddress = "http://192.168.1.10:8080",
            songId = "abc/123",
            streamRevision = "deadbeef",
            authToken = "secret"
        )

        assertTrue(url.contains("/song/abc%2F123"))
        assertTrue(url.contains("v=deadbeef"))
        assertTrue(url.contains("${CastSessionSecurity.AUTH_QUERY_PARAMETER}=secret"))
        assertTrue(CastSessionSecurity.redactAuthToken(url).contains("${CastSessionSecurity.AUTH_QUERY_PARAMETER}=<redacted>"))
    }

    @Test
    fun `buildAccessPolicy generates token when missing`() {
        val policy = CastSessionSecurity.buildAccessPolicy(
            existingToken = null,
            allowedSongIds = listOf("1"),
            castDeviceIpHint = null
        )

        assertNotNull(policy.authToken)
        assertTrue(policy.authToken!!.length >= 32)
        assertFalse(policy.enforceClientAddressAllowlist)
    }

    // ── The relay for streamed songs (handoff/2026-11-25-cast-stream.md) ──

    private val trackA = "4uLU6hMCjMI75M1A2tKUQC"
    private val trackB = "1301WleyT98MSxVHPZCA6M"

    @Test
    fun `relay tracks are kept only for allowed songs with valid track ids`() {
        val policy = CastSessionSecurity.buildAccessPolicy(
            existingToken = "t",
            allowedSongIds = listOf("1", "2"),
            castDeviceIpHint = null,
            relayTrackIds = mapOf("1" to trackA, "2" to "not-a-track", "9" to trackB)
        )
        assertEquals(mapOf("1" to trackA), policy.relayTrackIds)
        assertEquals(trackA, CastSessionSecurity.relayTrackIdFor("1", policy))
        assertEquals(null, CastSessionSecurity.relayTrackIdFor("2", policy))
        assertEquals(null, CastSessionSecurity.relayTrackIdFor("9", policy), "not in the allowlist")
        assertEquals(null, CastSessionSecurity.relayTrackIdFor(null, policy))
    }

    @Test
    fun `appended picks join the allowlist under the same token and addresses`() {
        val policy = CastSessionSecurity.buildAccessPolicy(
            existingToken = "token-1",
            allowedSongIds = listOf("1"),
            castDeviceIpHint = "192.168.1.50",
            relayTrackIds = mapOf("1" to trackA)
        )
        val extended = CastSessionSecurity.extendAccessPolicy(policy, listOf("7"), mapOf("7" to trackB))
        assertEquals("token-1", extended.authToken)
        assertEquals(policy.allowedClientAddresses, extended.allowedClientAddresses)
        assertTrue(extended.enforceClientAddressAllowlist)
        assertEquals(setOf("1", "7"), extended.allowedSongIds)
        assertEquals(mapOf("1" to trackA, "7" to trackB), extended.relayTrackIds)
        assertTrue(CastSessionSecurity.isAuthorizedSongRequest("token-1", "7", extended))
        assertFalse(CastSessionSecurity.isAuthorizedSongRequest("other", "7", extended))
    }

    @Test
    fun `extending a session that had no token yet makes one`() {
        val extended = CastSessionSecurity.extendAccessPolicy(CastAccessPolicy.EMPTY, listOf("7"), mapOf("7" to trackB))
        assertNotNull(extended.authToken)
        assertEquals(trackB, CastSessionSecurity.relayTrackIdFor("7", extended))
    }
}
