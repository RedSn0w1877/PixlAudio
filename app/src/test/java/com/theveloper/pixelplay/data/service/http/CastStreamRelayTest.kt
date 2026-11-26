package com.theveloper.pixelplay.data.service.http

import com.theveloper.pixelplay.data.model.Song
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastStreamRelayTest {

    private val trackId = "4uLU6hMCjMI75M1A2tKUQC"
    private val proxyUrl = "http://127.0.0.1:46399/spotify/$trackId"

    private fun song(id: String, uri: String) = Song(
        id = id, title = "t", artist = "a", artistId = 1L, album = "al", albumId = 1L, path = "",
        contentUriString = uri, albumArtUriString = null, duration = 1L, mimeType = null, bitrate = null, sampleRate = null,
    )

    /** A transport that records what it was asked and answers [answer]. */
    private class FakeTransport(val answer: () -> RelayUpstream) : RelayTransport {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): RelayUpstream {
            calls.add(url to headers)
            return answer()
        }
    }

    @Test
    fun `only a streamed song's address gives a track id`() {
        assertEquals(trackId, CastStreamRelay.trackIdOf("spotify://$trackId"))
        assertNull(CastStreamRelay.trackIdOf("content://media/external/audio/media/12"))
        assertNull(CastStreamRelay.trackIdOf("spotify://short"))
        assertNull(CastStreamRelay.trackIdOf("spotify://../../etc/passwd"))
        assertNull(CastStreamRelay.trackIdOf(null))
    }

    @Test
    fun `a queue's streamed songs are mapped to their tracks, files are left out`() {
        val ids = CastStreamRelay.relayTrackIdsOf(listOf(song("1", "spotify://$trackId"), song("2", "content://media/2")))
        assertEquals(mapOf("1" to trackId), ids)
    }

    @Test
    fun `the relay talks only to the proxy's loopback route for one valid track`() {
        assertTrue(CastStreamRelay.isAllowedRelayTarget(proxyUrl))
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://192.168.1.5:46399/spotify/$trackId"), "LAN host")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://localhost:46399/spotify/$trackId"), "a name, not the address")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("https://127.0.0.1:46399/spotify/$trackId"), "scheme")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://127.0.0.1:46399/other/$trackId"), "route")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://127.0.0.1:46399/spotify/$trackId/x"), "extra segment")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://127.0.0.1:46399/spotify/$trackId?u=https://evil"), "query")
        assertFalse(CastStreamRelay.isAllowedRelayTarget("http://127.0.0.1:46399/spotify/bad"), "invalid id")
        assertFalse(CastStreamRelay.isAllowedRelayTarget(""), "the proxy is not running (empty url)")
        assertFalse(CastStreamRelay.isAllowedRelayTarget(null))
    }

    @Test
    fun `only clients on this network are served`() {
        assertTrue(CastStreamRelay.isLanClientAddress("192.168.1.50"))
        assertTrue(CastStreamRelay.isLanClientAddress("10.0.0.7"))
        assertTrue(CastStreamRelay.isLanClientAddress("172.20.1.1"))
        assertTrue(CastStreamRelay.isLanClientAddress("::ffff:192.168.1.50"))
        assertTrue(CastStreamRelay.isLanClientAddress("127.0.0.1"))
        assertTrue(CastStreamRelay.isLanClientAddress("fe80::1%wlan0"))
        assertTrue(CastStreamRelay.isLanClientAddress("fd12:3456::1"))
        assertFalse(CastStreamRelay.isLanClientAddress("8.8.8.8"))
        assertFalse(CastStreamRelay.isLanClientAddress("2001:4860:4860::8888"))
        assertFalse(CastStreamRelay.isLanClientAddress("0.0.0.0"))
        assertFalse(CastStreamRelay.isLanClientAddress("printer"), "a name is not an address")
        assertFalse(CastStreamRelay.isLanClientAddress(null))
    }

    @Test
    fun `only a valid Range goes to the proxy, and only four headers come back`() {
        assertEquals(emptyMap<String, String>(), CastStreamRelay.forwardedRequestHeaders(null))
        assertEquals(mapOf("Range" to "bytes=100-"), CastStreamRelay.forwardedRequestHeaders("bytes=100-"))
        assertNull(CastStreamRelay.forwardedRequestHeaders("bytes=0-1,5-9"))
        val back = CastStreamRelay.relayedResponseHeaders(
            mapOf("Content-Type" to "audio/webm", "Content-Length" to "10", "Content-Range" to "bytes 0-9/99",
                "Accept-Ranges" to "bytes", "Set-Cookie" to "x", "Server" to "ktor")
        )
        assertEquals(setOf("content-type", "content-length", "content-range", "accept-ranges"), back.keys)
    }

    @Test
    fun `the forwarder relays status, range and body from the proxy`() {
        val transport = FakeTransport {
            RelayUpstream(206, mapOf("Content-Range" to "bytes 5-9/10", "Content-Type" to "audio/mp4", "X-Upstream" to "googlevideo"),
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
        val outcome = CastRelayForwarder(transport).open(proxyUrl, "bytes=5-9")
        assertTrue(outcome is RelayOutcome.Forwarded)
        val upstream = (outcome as RelayOutcome.Forwarded).upstream
        assertEquals(206, upstream.status)
        assertEquals("bytes 5-9/10", upstream.headers["content-range"])
        assertNull(upstream.headers["x-upstream"])
        assertEquals(5, upstream.body!!.readBytes().size)
        assertEquals(listOf(proxyUrl to mapOf("Range" to "bytes=5-9")), transport.calls)
    }

    @Test
    fun `the forwarder refuses before asking the proxy when the target or the range is wrong`() {
        val transport = FakeTransport { error("must not be called") }
        val forwarder = CastRelayForwarder(transport)
        assertEquals(502, (forwarder.open("http://10.0.0.2:80/spotify/$trackId", null) as RelayOutcome.Refused).status)
        assertEquals(416, (forwarder.open(proxyUrl, "bytes=a-b") as RelayOutcome.Refused).status)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `a proxy that cannot be reached is a bad gateway, not a crash`() {
        val outcome = CastRelayForwarder { _, _ -> throw IOException("refused") }.open(proxyUrl, null)
        assertEquals(502, (outcome as RelayOutcome.Refused).status)
    }

    @Test
    fun `the receiver is told the type the proxy knows, else mp4`() {
        CastStreamRelay.cachedTypeLookup = { id -> if (id == trackId) "audio/webm" else null }
        try {
            assertEquals("audio/webm", CastStreamRelay.contentTypeHint(trackId))
            assertEquals("audio/mp4", CastStreamRelay.contentTypeHint("0000000000000000000000"))
        } finally {
            CastStreamRelay.cachedTypeLookup = null
        }
        assertEquals("audio/mp4", CastStreamRelay.contentTypeHint(trackId))
    }
}
