package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJob
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJobs
import com.theveloper.pixelplay.data.worker.RenderJobState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSingStateHolderTest {

    private fun song(id: String) = Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L,
        path = "path", contentUriString = "content://dummy/$id", albumArtUriString = null,
        duration = 180_000L, mimeType = "audio/mpeg", bitrate = null, sampleRate = null
    )

    private fun job(
        id: String,
        state: RenderJobState,
        path: String? = null,
        failure: String? = null,
        automatic: Boolean = false,
        createdAt: Long = 1L,
    ) = InstrumentalRenderJob(id, state, 0, path, failure, automatic, createdAtMs = createdAt)

    /** WorkManager stand-in: one job list per song, a file on disk per song. */
    private class FakeJobs : InstrumentalRenderJobs {
        val jobs = HashMap<String, MutableStateFlow<List<InstrumentalRenderJob>>>()
        val onDisk = HashMap<String, String>()
        val enqueued = mutableListOf<String>()
        fun flowFor(songId: String) = jobs.getOrPut(songId) { MutableStateFlow(emptyList()) }
        override fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>> = flowFor(songId)
        override fun enqueue(song: Song) {
            enqueued += song.id
        }
        override suspend fun bestAvailablePath(songId: String): String? = onDisk[songId]
    }

    private class Harness(
        val holder: LyricsSingStateHolder,
        val player: MutableStateFlow<StablePlayerState>,
        val connectDevice: MutableStateFlow<String?>,
        val requests: MutableList<SingRequest>,
        val messages: MutableList<String>,
    )

    // The holder runs in backgroundScope, so the tests step it with runCurrent(): advanceUntilIdle()
    // stops as soon as no FOREGROUND work is left and would never run it.
    private fun TestScope.harness(jobs: FakeJobs): Harness {
        val player = MutableStateFlow(StablePlayerState(currentSong = song("1")))
        val playback = mockk<PlaybackStateHolder>(relaxed = true)
        every { playback.stablePlayerState } returns player
        val cast = mockk<CastStateHolder>(relaxed = true)
        every { cast.isRemotePlaybackActive } returns MutableStateFlow(false)
        every { cast.isCastConnecting } returns MutableStateFlow(false)
        val connectDevice = MutableStateFlow<String?>(null)
        val connect = mockk<SpotifyConnectStateHolder>(relaxed = true)
        every { connect.playingOnName } returns connectDevice
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } answers { "s${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg<Any>()) } answers { "s${firstArg<Int>()}" }
        val holder = LyricsSingStateHolder(context, playback, cast, connect, jobs)
        holder.initialize(backgroundScope)
        val requests = mutableListOf<SingRequest>()
        val messages = mutableListOf<String>()
        backgroundScope.launch { holder.requests.collect { requests += it } }
        backgroundScope.launch { holder.messages.collect { messages += it } }
        runCurrent()
        return Harness(holder, player, connectDevice, requests, messages)
    }

    @Test
    fun `with a render on disk a tap turns the vocals off`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
        assertTrue(jobs.enqueued.isEmpty())
    }

    @Test
    fun `vocals off, a tap brings them back`() = runTest {
        val h = harness(FakeJobs())
        h.holder.onInstrumentalPlaying()

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Original), h.requests)
    }

    @Test
    fun `without a render a tap starts one and switches exactly once when it lands`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.enqueued)
        assertTrue(h.requests.isEmpty())

        jobs.flowFor("1").value = listOf(job("a", RenderJobState.RUNNING))
        runCurrent()
        assertTrue(h.holder.ui.value.rendering)

        val done = listOf(job("a", RenderJobState.SUCCEEDED, path = "/stems/1.wav"))
        jobs.flowFor("1").value = done
        runCurrent()
        // The same finished job reported again must not switch a second time.
        jobs.flowFor("1").value = done.toList()
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
        assertTrue(h.holder.available.value)
    }

    @Test
    fun `a render landing for a song that is no longer playing does not switch`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        h.player.value = StablePlayerState(currentSong = song("2"))
        runCurrent()
        jobs.flowFor("1").value = listOf(job("a", RenderJobState.SUCCEEDED, path = "/stems/1.wav"))
        jobs.flowFor("2").value = listOf(job("b", RenderJobState.SUCCEEDED, path = "/stems/2.wav"))
        runCurrent()

        assertTrue(h.requests.isEmpty())
        assertFalse(h.holder.active.value)
    }

    @Test
    fun `a failed render reports its reason`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(job("a", RenderJobState.FAILED, failure = "no audio"))
        runCurrent()

        assertEquals(listOf("s${R.string.lyrics_sing_queued}", "s${R.string.lyrics_sing_failed}"), h.messages)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `quiet automatic work cancelled by the tap does not answer it`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(job("auto", RenderJobState.CANCELLED, automatic = true))
        runCurrent()
        jobs.flowFor("1").value = listOf(
            job("auto", RenderJobState.CANCELLED, automatic = true),
            job("mine", RenderJobState.SUCCEEDED, path = "/stems/1.wav", createdAt = 2L),
        )
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
    }

    @Test
    fun `a render that failed before the tap does not answer it`() = runTest {
        val jobs = FakeJobs()
        val old = job("old", RenderJobState.FAILED, failure = "yesterday")
        jobs.flowFor("1").value = listOf(old)
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.enqueued)
        // WorkManager reports the old job again (another table change) before the new one shows.
        h.holder.onJobs("1", listOf(old))
        assertEquals(listOf("s${R.string.lyrics_sing_queued}"), h.messages)

        jobs.flowFor("1").value = listOf(old, job("new", RenderJobState.SUCCEEDED, path = "/stems/1.wav", createdAt = 2L))
        runCurrent()
        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
    }

    @Test
    fun `an old render whose file is gone neither switches nor hides the new render's progress`() = runTest {
        val jobs = FakeJobs()
        val old = job("old", RenderJobState.SUCCEEDED, path = "/stems/gone.wav")
        jobs.flowFor("1").value = listOf(old)
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.enqueued)
        h.holder.onJobs("1", listOf(old))
        assertTrue(h.requests.isEmpty())
        assertFalse(h.holder.available.value)

        jobs.flowFor("1").value = listOf(old, job("new", RenderJobState.RUNNING, createdAt = 2L))
        runCurrent()
        assertTrue(h.holder.ui.value.rendering)

        jobs.flowFor("1").value = listOf(old, job("new", RenderJobState.SUCCEEDED, path = "/stems/1.wav", createdAt = 2L))
        runCurrent()
        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
    }

    @Test
    fun `a tap while a Connect speaker plays never renders or switches`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)
        h.connectDevice.value = "Echo"
        runCurrent()

        h.holder.onSingTapped()
        runCurrent()

        assertTrue(jobs.enqueued.isEmpty())
        assertTrue(h.requests.isEmpty())
        assertEquals(listOf("s${R.string.lyrics_sing_remote_only}"), h.messages)
        assertFalse(h.holder.ui.value.enabled)
    }

    @Test
    fun `a new song starts with vocals`() = runTest {
        val h = harness(FakeJobs())
        h.holder.onInstrumentalPlaying()
        assertTrue(h.holder.active.value)

        h.player.value = StablePlayerState(currentSong = song("2"))
        runCurrent()

        assertFalse(h.holder.active.value)
    }
}
