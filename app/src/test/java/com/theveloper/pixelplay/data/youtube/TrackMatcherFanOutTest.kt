package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.SpotifySongEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TrackMatcherFanOutTest {

    private val song = SpotifySongEntity("row", "track", "playlist", "Northern Lights", "Nova", "First Light", null,
        180_000, null, null, 0L)

    // buildQueries(song): title + artist, title + artist + album, title alone.
    private val q0 = "Northern Lights Nova"
    private val q1 = "Northern Lights Nova First Light"
    private val q2 = "Northern Lights"

    // Scores ~0.80: a loose duration keeps it under the early accept (0.88), above the minimum.
    private val weak = YouTubeSearchResult("weakweakwea", "Northern Lights", "Nova", null, 186)
    private val exact = YouTubeSearchResult("exactexacte", "Northern Lights", "Nova", "First Light", 180)
    private val video = YouTubeSearchResult("videovideov", "Nova - Northern Lights [Official Music Video]",
        "NovaVEVO", null, 183, isMusicVideo = true)

    private sealed interface Answer
    private data class Hits(val results: List<YouTubeSearchResult>, val delayMs: Long = 0) : Answer
    private data class Fails(val delayMs: Long = 0) : Answer

    private fun client(songs: Map<String, Answer>, videos: Answer = Hits(emptyList())): InnerTubeClient {
        val client = mockk<InnerTubeClient>()
        suspend fun reply(answer: Answer?): List<YouTubeSearchResult> = when (answer) {
            null -> emptyList()
            is Hits -> { delay(answer.delayMs); answer.results }
            is Fails -> { delay(answer.delayMs); throw IOException("offline") }
        }
        coEvery { client.searchSongs(any(), any()) } coAnswers { reply(songs[firstArg<String>()]) }
        coEvery { client.searchVideos(any(), any()) } coAnswers { reply(videos) }
        return client
    }

    @Test
    fun `an early accept on the first search runs nothing else`() = runTest {
        val client = client(mapOf(q0 to Hits(listOf(exact))))
        val match = TrackMatcher(client).findMatchFanOut(song)
        assertEquals("exactexacte", match?.videoId)
        coVerify(exactly = 1) { client.searchSongs(any(), any()) }
        coVerify(exactly = 0) { client.searchVideos(any(), any()) }
    }

    @Test
    fun `a later query wins exactly as in the sequential matcher, and sooner`() = runTest {
        val answers = mapOf(
            q0 to Hits(listOf(weak), delayMs = 100),
            q1 to Hits(emptyList(), delayMs = 100),
            q2 to Hits(listOf(exact), delayMs = 100)
        )
        val sequentialStart = currentTime
        val sequential = TrackMatcher(client(answers, Hits(listOf(video), 100))).findMatch(song)
        val sequentialMs = currentTime - sequentialStart

        val fanOutStart = currentTime
        val fanOut = TrackMatcher(client(answers, Hits(listOf(video), 100))).findMatchFanOut(song)
        val fanOutMs = currentTime - fanOutStart

        assertEquals("exactexacte", sequential?.videoId)
        assertEquals(sequential, fanOut)
        assertEquals(300L, sequentialMs)
        assertEquals(200L, fanOutMs)
    }

    @Test
    fun `results are judged in order even when they finish out of order`() = runTest {
        // q2 (exact) finishes first, but q1 also early-accepts and comes first in findMatch's order.
        val q1Exact = exact.copy(videoId = "firstfirstf")
        val answers = mapOf(
            q0 to Hits(listOf(weak)),
            q1 to Hits(listOf(q1Exact), delayMs = 300),
            q2 to Hits(listOf(exact), delayMs = 10)
        )
        val sequential = TrackMatcher(client(answers)).findMatch(song)
        val fanOut = TrackMatcher(client(answers)).findMatchFanOut(song)
        assertEquals("firstfirstf", sequential?.videoId)
        assertEquals(sequential, fanOut)
    }

    @Test
    fun `a video-only release is found the same way`() = runTest {
        val answers = mapOf(q0 to Hits(emptyList()), q1 to Hits(emptyList()), q2 to Hits(emptyList()))
        val sequential = TrackMatcher(client(answers, Hits(listOf(video)))).findMatch(song)
        val fanOut = TrackMatcher(client(answers, Hits(listOf(video)))).findMatchFanOut(song)
        assertEquals("videovideov", sequential?.videoId)
        assertEquals(sequential, fanOut)
    }

    @Test
    fun `the best weak candidate is kept when nothing early-accepts`() = runTest {
        val answers = mapOf(q0 to Hits(listOf(weak)), q1 to Hits(emptyList()), q2 to Hits(emptyList()))
        val sequential = TrackMatcher(client(answers)).findMatch(song)
        val fanOut = TrackMatcher(client(answers)).findMatchFanOut(song)
        assertEquals("weakweakwea", sequential?.videoId)
        assertEquals(sequential, fanOut)
    }

    @Test
    fun `a failure findMatch would never reach does not count`() = runTest {
        // q1 early-accepts, so findMatch never runs q2 or the video search; their failures
        // (which the fan-out did run) must not turn the match into an error.
        val answers = mapOf(
            q0 to Hits(listOf(weak)),
            q1 to Hits(listOf(exact), delayMs = 50),
            q2 to Fails(delayMs = 10)
        )
        val fanOut = TrackMatcher(client(answers, Fails())).findMatchFanOut(song)
        assertEquals(TrackMatcher(client(answers, Fails())).findMatch(song), fanOut)
        assertEquals("exactexacte", fanOut?.videoId)
    }

    @Test
    fun `a failure findMatch would reach is reported the same way`() = runTest {
        val answers = mapOf(q0 to Fails(), q1 to Fails(), q2 to Fails())
        val sequentialError = runCatching { TrackMatcher(client(answers)).findMatch(song) }.exceptionOrNull()
        val fanOutError = runCatching { TrackMatcher(client(answers)).findMatchFanOut(song) }.exceptionOrNull()
        assertTrue(sequentialError is IOException)
        assertTrue(fanOutError is IOException)
    }

    @Test
    fun `nothing acceptable and no failure means no match`() = runTest {
        val stranger = YouTubeSearchResult("strangerstr", "Northern Lights", "Someone Else", null, 180)
        val answers = mapOf(q0 to Hits(listOf(stranger)), q1 to Hits(emptyList()), q2 to Hits(emptyList()))
        assertNull(TrackMatcher(client(answers)).findMatchFanOut(song))
        assertNull(TrackMatcher(client(answers)).findMatch(song))
    }
}
