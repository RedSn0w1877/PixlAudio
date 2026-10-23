package com.theveloper.pixelplay.data.youtube

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamResolverPlayabilityTest {

    private val profile = InnerTubeContexts.VISIONOS

    // --- isVideoUnavailable ---

    @Test fun `two clients reporting the video dead is unavailable`() {
        assertTrue(isVideoUnavailable(listOf("UNPLAYABLE", "ERROR")))
        assertTrue(isVideoUnavailable(listOf("UNPLAYABLE", "UNPLAYABLE", "LOGIN_REQUIRED")))
    }

    @Test fun `one refusing client proves nothing`() {
        assertFalse(isVideoUnavailable(listOf("UNPLAYABLE")))
        assertFalse(isVideoUnavailable(emptyList()))
    }

    @Test fun `a client that said OK means the video exists`() {
        assertFalse(isVideoUnavailable(listOf("OK", "UNPLAYABLE", "UNPLAYABLE")))
    }

    @Test fun `bot checks and age gates are not about the video`() {
        assertFalse(isVideoUnavailable(listOf("LOGIN_REQUIRED", "LOGIN_REQUIRED", "AGE_VERIFICATION_REQUIRED")))
    }

    // --- the per-client status the chain reads ---

    @Test fun `a refused client records its playability status`() = runTest {
        val client = mockk<InnerTubeClient>()
        coEvery { client.fetchPlayer("vid", profile) } returns
            YouTubePlayerResponse("UNPLAYABLE", "Video unavailable", emptyList())
        val resolver = PreSignedStreamResolver(client, mockk(), profile)

        assertNull(resolver.resolve("vid"))
        assertEquals("UNPLAYABLE", resolver.lastPlayabilityStatus)
    }

    @Test fun `no response leaves no status and a later answer does not inherit it`() = runTest {
        val client = mockk<InnerTubeClient>()
        every { client.lastFailureReason } returns "offline"
        coEvery { client.fetchPlayer("first", profile) } returns
            YouTubePlayerResponse("UNPLAYABLE", null, emptyList())
        coEvery { client.fetchPlayer("second", profile) } returns null
        val resolver = PreSignedStreamResolver(client, mockk(), profile)

        resolver.resolve("first")
        assertEquals("UNPLAYABLE", resolver.lastPlayabilityStatus)
        resolver.resolve("second")
        assertNull(resolver.lastPlayabilityStatus)
    }

    @Test fun `a playable answer records OK`() = runTest {
        val client = mockk<InnerTubeClient>()
        val format = YouTubeAudioFormat(140, "audio/mp4", 128_000, "https://rr.googlevideo.com/a", null, null, null)
        coEvery { client.fetchPlayer("vid", profile) } returns YouTubePlayerResponse("OK", null, listOf(format))
        val solver = mockk<SignatureCipherSolver>()
        coEvery { solver.applyNTransform(any()) } coAnswers { firstArg() }
        val resolver = PreSignedStreamResolver(client, solver, profile)

        assertEquals("https://rr.googlevideo.com/a", resolver.resolve("vid"))
        assertEquals("OK", resolver.lastPlayabilityStatus)
    }

    // --- player script cache ---

    @Test fun `a working player is kept for hours`() {
        assertTrue(isPlayerScriptFresh(loadedAtMs = 0L, nowMs = 5 * 60 * 60_000L, hasFunctions = true))
        assertFalse(isPlayerScriptFresh(loadedAtMs = 0L, nowMs = 7 * 60 * 60_000L, hasFunctions = true))
    }

    @Test fun `a player without functions is retried within minutes`() {
        assertTrue(isPlayerScriptFresh(loadedAtMs = 0L, nowMs = 60_000L, hasFunctions = false))
        assertFalse(isPlayerScriptFresh(loadedAtMs = 0L, nowMs = 6 * 60_000L, hasFunctions = false))
    }

    @Test fun `a clock that went backwards refreshes instead of trusting the cache`() {
        assertFalse(isPlayerScriptFresh(loadedAtMs = 10_000L, nowMs = 5_000L, hasFunctions = true))
    }
}
