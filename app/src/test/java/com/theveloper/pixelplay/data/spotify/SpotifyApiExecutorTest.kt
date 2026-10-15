package com.theveloper.pixelplay.data.spotify

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

class SpotifyApiExecutorTest {
    private val time = FakeTime()
    private val auth = mockk<SpotifyAuthManager>(relaxed = true) {
        coEvery { authorizationHeader() } returns "Bearer a"
    }

    private fun executor() = SpotifyApiExecutor(auth, time.clock, time.sleeper)

    @Test fun `429 honours Retry-After before the retry and then succeeds`() = runTest {
        var calls = 0
        val result = executor().execute("t") { _ ->
            calls++
            if (calls == 1) errorResponse<String>(429, "Retry-After" to "3") else Response.success("ok")
        }
        assertEquals(SpotifyResult.Ok("ok"), result)
        assertEquals(2, calls)
        // The 3 s Spotify asked for is slept in full before the second request.
        assertTrue(time.sleeps.any { it >= 3_000L }, "sleeps=${time.sleeps}")
    }

    @Test fun `429 with an hour-long Retry-After is not slept through and later calls fail fast`() = runTest {
        var calls = 0
        val first = executor().let { exec ->
            val r = exec.execute("t") { _ -> calls++; errorResponse<String>(429, "Retry-After" to "3600") }
            assertEquals(SpotifyResult.RateLimited(3600L), r)
            assertEquals(1, calls)
            assertTrue(time.sleeps.none { it > 60_000L }, "slept through the penalty: ${time.sleeps}")
            // Everything queued behind it must not hammer the API either.
            val second = exec.execute("t2") { _ -> calls++; Response.success("never") }
            assertTrue(second is SpotifyResult.RateLimited)
            assertEquals(1, calls)
            r
        }
        assertTrue(first is SpotifyResult.RateLimited)
    }

    @Test fun `repeated 429 without a header backs off exponentially and gives up`() = runTest {
        var calls = 0
        val result = executor().execute("t") { _ -> calls++; errorResponse<String>(429) }
        assertTrue(result is SpotifyResult.RateLimited)
        assertEquals(4, calls)
        val waits = time.sleeps.filter { it >= 1_000L }
        assertEquals(listOf(2_000L, 4_000L, 8_000L), waits.take(3))
    }

    @Test fun `401 refreshes the token once and retries with the new one`() = runTest {
        coEvery { auth.authorizationHeader() } returnsMany listOf("Bearer a", "Bearer b")
        coEvery { auth.refreshAfterRejected("Bearer a") } returns true
        val seen = mutableListOf<String>()
        val result = executor().execute("t") { header ->
            seen += header
            if (header == "Bearer a") errorResponse<String>(401) else Response.success("ok")
        }
        assertEquals(SpotifyResult.Ok("ok"), result)
        assertEquals(listOf("Bearer a", "Bearer b"), seen)
        coVerify(exactly = 1) { auth.refreshAfterRejected(any()) }
    }

    @Test fun `a second 401 after the refresh asks the user to sign in again`() = runTest {
        coEvery { auth.refreshAfterRejected(any()) } returns true
        var calls = 0
        val result = executor().execute("t") { _ -> calls++; errorResponse<String>(401) }
        assertEquals(SpotifyResult.Unauthorized, result)
        assertEquals(2, calls)
        coVerify(exactly = 1) { auth.refreshAfterRejected(any()) }
    }

    @Test fun `a failed refresh is Unauthorized without a retry`() = runTest {
        coEvery { auth.refreshAfterRejected(any()) } returns false
        var calls = 0
        val result = executor().execute("t") { _ -> calls++; errorResponse<String>(401) }
        assertEquals(SpotifyResult.Unauthorized, result)
        assertEquals(1, calls)
    }

    @Test fun `403 404 and 400 are returned at once without retrying`() = runTest {
        var calls = 0
        assertTrue(executor().execute("t") { _ -> calls++; errorResponse<String>(403) } is SpotifyResult.Forbidden)
        assertTrue(executor().execute("t") { _ -> calls++; errorResponse<String>(404) } is SpotifyResult.NotFound)
        assertTrue(executor().execute("t") { _ -> calls++; errorResponse<String>(400) } is SpotifyResult.BadRequest)
        assertEquals(3, calls)
    }

    @Test fun `5xx is retried a bounded number of times with growing backoff`() = runTest {
        var calls = 0
        val result = executor().execute("t") { _ -> calls++; errorResponse<String>(503) }
        assertTrue(result is SpotifyResult.ServerError)
        assertEquals(3, calls)
        assertEquals(listOf(800L, 1_600L), time.sleeps.filter { it >= 800L }.take(2))
    }

    @Test fun `network errors are retried a bounded number of times`() = runTest {
        var calls = 0
        val result = executor().execute<String>("t") { _ -> calls++; throw IOException("offline") }
        assertTrue(result is SpotifyResult.NetworkError)
        assertEquals(3, calls)
    }

    @Test fun `every call is spaced by the shared minimum interval`() = runTest {
        val exec = executor()
        repeat(3) { exec.execute("t") { _ -> Response.success("ok") } }
        // Three back-to-back calls: the 2nd and 3rd each wait for the interval to elapse.
        assertEquals(2, time.sleeps.count { it > 0L })
    }

    @Test fun `no session means Unauthorized`() = runTest {
        every { auth.isLoggedIn } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        coEvery { auth.authorizationHeader() } returns null
        assertEquals(SpotifyResult.Unauthorized, executor().execute<String>("t") { _ -> Response.success("x") })
    }
}
