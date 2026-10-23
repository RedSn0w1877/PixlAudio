package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.cloudstudio.CloudSingStart
import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.service.player.SingClipEvents
import com.theveloper.pixelplay.data.tais.SingClipWindow
import com.theveloper.pixelplay.data.worker.CloudPhase
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJob
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJobs
import com.theveloper.pixelplay.data.worker.RenderJobState
import com.theveloper.pixelplay.data.worker.RenderSource
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyricsBuilder
import com.theveloper.pixelplay.presentation.lyrics.sing.SingOnlineProbe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSingStateHolderTest {

    private fun song(id: String, durationMs: Long = 180_000L) = Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L,
        path = "path", contentUriString = "content://dummy/$id", albumArtUriString = null,
        duration = durationMs, mimeType = "audio/mpeg", bitrate = null, sampleRate = null
    )

    /** An on-device (WorkManager) render job. */
    private fun job(
        id: String,
        state: RenderJobState,
        path: String? = null,
        failure: String? = null,
        automatic: Boolean = false,
        createdAt: Long = 1L,
    ) = InstrumentalRenderJob(id, state, 0, path, failure, automatic, createdAtMs = createdAt)

    /** A Cloud Studio job, as the render-job mapping hands it over. */
    private fun cloudJob(
        id: String,
        state: RenderJobState,
        path: String? = null,
        failure: String? = null,
        automatic: Boolean = false,
        createdAt: Long = 1L,
        percent: Int = 0,
        phase: CloudPhase? = null,
        clip: SingClipWindow? = null,
    ) = InstrumentalRenderJob(id, state, percent, path, failure, automatic, createdAt, RenderSource.CLOUD, phase, clip = clip)

    /** What the context's getString answers (see [harness]): the string id, so tests name messages by resource. */
    private fun s(resId: Int) = "s$resId"

    /** WorkManager and Cloud Studio stand-in: one job list per song, a file on disk per song, a cloud that answers as told. */
    private class FakeJobs : InstrumentalRenderJobs {
        val jobs = HashMap<String, MutableStateFlow<List<InstrumentalRenderJob>>>()
        val onDisk = HashMap<String, String>()
        val enqueued = mutableListOf<String>()
        val cloudStarts = mutableListOf<String>()
        /** The playhead each cloud start was given (for its preview clip). */
        val playheads = mutableListOf<Long?>()
        /** Songs whose preview clips were asked to go. */
        val discarded = mutableListOf<String>()

        /** What keeps the cloud from taking a song: off, so the on-device tests stay on the phone. */
        var cloudBlocker: CloudSingUnavailable? = CloudSingUnavailable.OFF
        var startResult: (Song) -> CloudSingStart = { CloudSingStart.Started("cloud-${it.id}") }

        /** Holds [startCloud] open until completed, like a job being set up. */
        var startGate: CompletableDeferred<Unit>? = null

        fun flowFor(songId: String) = jobs.getOrPut(songId) { MutableStateFlow(emptyList()) }
        override fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>> = flowFor(songId)
        override fun enqueue(song: Song) {
            enqueued += song.id
        }

        override suspend fun bestAvailablePath(songId: String): String? = onDisk[songId]
        override fun cloudAvailability(song: Song): CloudSingUnavailable? = cloudBlocker
        override suspend fun startCloud(song: Song, playheadMs: Long?): CloudSingStart {
            cloudStarts += song.id
            playheads += playheadMs
            startGate?.await()
            return startResult(song)
        }

        override suspend fun discardClips(songId: String) {
            discarded += songId
        }
    }

    private class Harness(
        val holder: LyricsSingStateHolder,
        val player: MutableStateFlow<StablePlayerState>,
        val connectDevice: MutableStateFlow<String?>,
        val prepared: MutableStateFlow<PreparedLyrics?>,
        val requests: MutableList<SingRequest>,
        val messages: MutableList<String>,
        val clipEvents: SingClipEvents,
    )

    // The holder runs in backgroundScope, so the tests step it with runCurrent(): advanceUntilIdle()
    // stops as soon as no FOREGROUND work is left and would never run it.
    private fun TestScope.harness(
        jobs: FakeJobs,
        durationMs: Long = 180_000L,
        online: Boolean = true,
        positionMs: Long = 10_000L,
        syncOffsetMs: Int = 0,
        prepared: PreparedLyrics? = null,
        lyrics: Lyrics? = null,
    ): Harness {
        val player = MutableStateFlow(StablePlayerState(currentSong = song("1", durationMs), lyrics = lyrics))
        val playback = mockk<PlaybackStateHolder>(relaxed = true)
        every { playback.stablePlayerState } returns player
        every { playback.exactPositionMs() } returns positionMs
        val cast = mockk<CastStateHolder>(relaxed = true)
        every { cast.isRemotePlaybackActive } returns MutableStateFlow(false)
        every { cast.isCastConnecting } returns MutableStateFlow(false)
        val connectDevice = MutableStateFlow<String?>(null)
        val connect = mockk<SpotifyConnectStateHolder>(relaxed = true)
        every { connect.playingOnName } returns connectDevice
        val preparedFlow = MutableStateFlow(prepared)
        val lyricsState = mockk<LyricsStateHolder>(relaxed = true)
        every { lyricsState.preparedLyrics } returns preparedFlow
        every { lyricsState.currentSongSyncOffset } returns MutableStateFlow(syncOffsetMs)
        val probe = mockk<SingOnlineProbe>()
        every { probe.isOnline() } returns online
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } answers { "s${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg<Any>()) } answers { "s${firstArg<Int>()}" }
        val clipEvents = SingClipEvents()
        val holder = LyricsSingStateHolder(context, playback, cast, connect, jobs, lyricsState, probe, clipEvents)
        holder.initialize(backgroundScope)
        val requests = mutableListOf<SingRequest>()
        val messages = mutableListOf<String>()
        backgroundScope.launch { holder.requests.collect { requests += it } }
        backgroundScope.launch { holder.messages.collect { messages += it } }
        runCurrent()
        return Harness(holder, player, connectDevice, preparedFlow, requests, messages, clipEvents)
    }

    /** "la" held 10.0 to 11.0 s, a second "la" 12.0 to 12.5 s: a gap between the words from 11.0 to 12.0 s. */
    private fun gapLyrics(): PreparedLyrics = PreparedLyricsBuilder.build(
        LyricsDoc(
            lines = listOf(
                TimedLine(
                    10_000, 12_500, "la la", "lead",
                    listOf(TimedSyllable(10_000, 1_000, "la "), TimedSyllable(12_000, 500, "la")),
                )
            )
        )
    ) ?: throw AssertionError("expected a lyrics model")

    private fun syncedLyrics() = Lyrics(synced = listOf(SyncedLine(10_000, "la")))

    // ---- an instrumental is already here ----------------------------------------------------------

    @Test
    fun `with a render on disk a tap turns the vocals off`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()

        // At the same position, at once: no plan and no cloud.
        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
        assertTrue(jobs.enqueued.isEmpty())
        assertTrue(jobs.cloudStarts.isEmpty())
    }

    @Test
    fun `a render on disk is used even when the cloud could take the song`() = runTest {
        val jobs = FakeJobs().apply {
            onDisk["1"] = "/stems/1.wav"
            cloudBlocker = null
        }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
        assertTrue(jobs.cloudStarts.isEmpty())
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
    fun `the button says Vocals off only once the player reports the switch`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertFalse(h.holder.ui.value.active)

        h.holder.onInstrumentalPlaying()
        runCurrent()
        assertTrue(h.holder.ui.value.active)
    }

    @Test
    fun `a switch the player could not make is reported and frees the button`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        h.holder.onSwitchFailed()
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_switch_failed)), h.messages)
        assertFalse(h.holder.ui.value.active)
        assertEquals(SingPhase.IDLE, h.holder.ui.value.phase)
        // The next tap tries again.
        h.holder.onSingTapped()
        runCurrent()
        assertEquals(2, h.requests.size)
    }

    @Test
    fun `a player that never answers does not keep the button waiting`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        assertEquals(SingPhase.SWITCHING, h.holder.ui.value.phase)

        advanceTimeBy(8_001)
        runCurrent()

        assertEquals(SingPhase.IDLE, h.holder.ui.value.phase)
    }

    // ---- the cloud is off: this phone does it ---------------------------------------------------------

    @Test
    fun `without a render a tap starts one on this phone, says why, and switches exactly once when it lands`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.enqueued)
        assertTrue(jobs.cloudStarts.isEmpty())
        assertEquals(listOf(s(R.string.lyrics_sing_fallback_off)), h.messages)
        assertTrue(h.requests.isEmpty())

        jobs.flowFor("1").value = listOf(job("a", RenderJobState.RUNNING))
        runCurrent()
        assertTrue(h.holder.ui.value.rendering)
        assertEquals(SingPhase.ON_DEVICE, h.holder.ui.value.phase)

        val done = listOf(job("a", RenderJobState.SUCCEEDED, path = "/stems/1.wav"))
        jobs.flowFor("1").value = done
        runCurrent()
        // The same finished job reported again must not switch a second time.
        jobs.flowFor("1").value = done.toList()
        runCurrent()

        // A render made here switches at once: the song is not being waited on for a gap.
        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1.wav")), h.requests)
        assertTrue(h.holder.available.value)
    }

    @Test
    fun `each reason the cloud is not used is named in the message`() = runTest {
        val expected = mapOf(
            CloudSingUnavailable.OFF to R.string.lyrics_sing_fallback_off,
            CloudSingUnavailable.NOT_CONFIGURED to R.string.lyrics_sing_fallback_not_set_up,
            CloudSingUnavailable.KEYS_MISSING to R.string.lyrics_sing_fallback_not_set_up,
            CloudSingUnavailable.CAP_REACHED to R.string.lyrics_sing_fallback_budget,
            CloudSingUnavailable.NOT_ON_WIFI to R.string.lyrics_sing_fallback_wifi,
            CloudSingUnavailable.TOO_LONG to R.string.lyrics_sing_fallback_too_long,
            CloudSingUnavailable.NO_AUDIO to R.string.lyrics_sing_fallback_other,
        )
        expected.forEach { (blocker, message) ->
            val jobs = FakeJobs().apply { cloudBlocker = blocker }
            val h = harness(jobs)

            h.holder.onSingTapped()
            runCurrent()

            assertEquals(listOf(s(message)), h.messages, "for $blocker")
            assertEquals(listOf("1"), jobs.enqueued, "for $blocker")
            assertTrue(jobs.cloudStarts.isEmpty(), "for $blocker")
        }
    }

    @Test
    fun `offline the cloud is not tried`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, online = false)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_fallback_offline)), h.messages)
        assertEquals(listOf("1"), jobs.enqueued)
        assertTrue(jobs.cloudStarts.isEmpty())
    }

    @Test
    fun `a song over fifteen minutes stays on this phone`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, durationMs = 16 * 60_000L)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_fallback_too_long)), h.messages)
        assertEquals(listOf("1"), jobs.enqueued)
        assertTrue(jobs.cloudStarts.isEmpty())
    }

    @Test
    fun `a failed render reports its reason`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(job("a", RenderJobState.FAILED, failure = "no audio"))
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_fallback_off), s(R.string.lyrics_sing_failed)), h.messages)
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
        assertEquals(listOf(s(R.string.lyrics_sing_fallback_off)), h.messages)

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
    fun `a tap while a Connect speaker plays never renders or switches`() = runTest {
        val jobs = FakeJobs().apply { onDisk["1"] = "/stems/1.wav" }
        val h = harness(jobs)
        h.connectDevice.value = "Echo"
        runCurrent()

        h.holder.onSingTapped()
        runCurrent()

        assertTrue(jobs.enqueued.isEmpty())
        assertTrue(jobs.cloudStarts.isEmpty())
        assertTrue(h.requests.isEmpty())
        assertEquals(listOf(s(R.string.lyrics_sing_remote_only)), h.messages)
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

    // ---- the cloud takes the song ------------------------------------------------------------------------

    @Test
    fun `a usable cloud starts a Sing job while the original keeps playing`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf("1"), jobs.cloudStarts)
        assertTrue(jobs.enqueued.isEmpty(), "nothing is rendered on this phone")
        assertTrue(h.requests.isEmpty(), "the original keeps playing")
        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued)), h.messages)
        val ui = h.holder.ui.value
        assertTrue(ui.rendering)
        assertEquals(SingPhase.CLOUD, ui.phase)
        assertFalse(ui.active)
    }

    @Test
    fun `the button follows the cloud job's steps`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.QUEUED, phase = CloudPhase.WAITING_FOR_WIFI))
        runCurrent()
        assertEquals(SingPhase.CLOUD_WIFI, h.holder.ui.value.phase)

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.RUNNING, percent = 60, phase = CloudPhase.PROCESSING))
        runCurrent()
        val ui = h.holder.ui.value
        assertEquals(SingPhase.CLOUD, ui.phase)
        assertEquals(0.6f, ui.progress!!, 0.0001f)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `when the cloud's instrumental lands the song fades to it a moment later`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        // No word timing to find a gap in: start a moment from now (player position + the player's own lead).
        val h = harness(jobs, positionMs = 10_000L)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()

        assertEquals(
            listOf<SingRequest>(
                SingRequest.Instrumental("/stems/1_cloud_inst.m4a", CrossfadePlan(startAtPositionMs = 10_360L, fadeMs = 700))
            ),
            h.requests,
        )
        assertTrue(h.holder.available.value)
        // Until the player says the switch happened the button is not "Vocals off".
        assertFalse(h.holder.ui.value.active)
        h.holder.onInstrumentalPlaying()
        runCurrent()
        assertTrue(h.holder.ui.value.active)
    }

    @Test
    fun `the fade is placed in a gap between sung words, in player time`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        // The lyrics run 500 ms ahead of the player: the gap at 11.0 s of lyrics is at 10.5 s of player time.
        val h = harness(jobs, positionMs = 10_000L, syncOffsetMs = 500, prepared = gapLyrics())
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()

        assertEquals(
            listOf<SingRequest>(
                SingRequest.Instrumental("/stems/1_cloud_inst.m4a", CrossfadePlan(startAtPositionMs = 10_500L, fadeMs = 700))
            ),
            h.requests,
        )
    }

    @Test
    fun `lyrics still being prepared are waited for`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 10_000L, syncOffsetMs = 500, lyrics = syncedLyrics())
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertTrue(h.requests.isEmpty(), "the plan waits for the lyrics model")

        h.prepared.value = gapLyrics()
        runCurrent()

        val request = h.requests.single() as SingRequest.Instrumental
        assertEquals(CrossfadePlan(startAtPositionMs = 10_500L, fadeMs = 700), request.plan)
    }

    @Test
    fun `lyrics that never settle do not hold the switch for long`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 10_000L, lyrics = syncedLyrics())
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertTrue(h.requests.isEmpty())

        advanceTimeBy(301)
        runCurrent()

        val request = h.requests.single() as SingRequest.Instrumental
        assertEquals(CrossfadePlan(startAtPositionMs = 10_360L, fadeMs = 700), request.plan)
    }

    @Test
    fun `a second tap while the cloud works cancels only the switch`() = runTest {
        val jobs = FakeJobs().apply {
            cloudBlocker = null
            startResult = { CloudSingStart.Started("c") }
        }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.RUNNING, percent = 40, phase = CloudPhase.PROCESSING))
        runCurrent()
        assertTrue(h.holder.ui.value.rendering)

        h.holder.onSingTapped()
        runCurrent()

        // The button is a plain Sing again, the job is not asked to stop, and no second job starts.
        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued), s(R.string.lyrics_sing_switch_cancelled)), h.messages)
        assertEquals(listOf("1"), jobs.cloudStarts)
        assertEquals(SingUi(), h.holder.ui.value)

        // The job still finishes: the file is kept, but the song is not switched.
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertTrue(h.requests.isEmpty())
        assertTrue(h.holder.available.value)

        // And the next tap, with the file on disk, uses it at once.
        jobs.onDisk["1"] = "/stems/1_cloud_inst.m4a"
        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf<SingRequest>(SingRequest.Instrumental("/stems/1_cloud_inst.m4a")), h.requests)
        assertEquals(listOf("1"), jobs.cloudStarts)
    }

    @Test
    fun `a tap while the job is still being set up is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val jobs = FakeJobs().apply {
            cloudBlocker = null
            startGate = gate
        }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.cloudStarts)

        h.holder.onSingTapped()
        runCurrent()
        // Not a cancel and not a second job: the first tap is still choosing.
        assertTrue(h.messages.isEmpty())
        assertEquals(listOf("1"), jobs.cloudStarts)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued)), h.messages)
        assertEquals(listOf("1"), jobs.cloudStarts)
    }

    @Test
    fun `a tap on a song whose cloud job is already on its way joins it`() = runTest {
        val jobs = FakeJobs().apply {
            cloudBlocker = null
            startResult = { CloudSingStart.Joined("c") }
        }
        // Started from Song info, or quietly ahead of time.
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.RUNNING, automatic = true, phase = CloudPhase.PROCESSING))
        val h = harness(jobs)
        // The quiet job alone is not the person waiting: the button is a plain Sing.
        assertEquals(SingUi(), h.holder.ui.value)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf("1"), jobs.cloudStarts)
        assertTrue(jobs.enqueued.isEmpty())
        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued)), h.messages)
        assertEquals(SingPhase.CLOUD, h.holder.ui.value.phase)

        // The job (now the person's) lands: the song switches.
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a cloud job that failed tells the person and the next tap renders on this phone`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.FAILED, failure = "boom"))
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued), s(R.string.lyrics_sing_cloud_failed)), h.messages)
        assertTrue(h.requests.isEmpty())
        assertEquals(SingUi(), h.holder.ui.value)

        h.holder.onSingTapped()
        runCurrent()

        // No second cloud job, and the phone's own render starts (without a "why": the person was just told).
        assertEquals(listOf("1"), jobs.cloudStarts)
        assertEquals(listOf("1"), jobs.enqueued)
        assertEquals(s(R.string.lyrics_sing_queued), h.messages.last())
    }

    @Test
    fun `a cloud job that was stopped clears the wait without a message`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.CANCELLED))
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued)), h.messages)
        assertEquals(SingUi(), h.holder.ui.value)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `when the cloud refuses the job the phone does it and says why`() = runTest {
        val jobs = FakeJobs().apply {
            cloudBlocker = null
            startResult = { CloudSingStart.Unavailable(CloudSingUnavailable.CAP_REACHED) }
        }
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf("1"), jobs.cloudStarts)
        assertEquals(listOf("1"), jobs.enqueued)
        assertEquals(listOf(s(R.string.lyrics_sing_fallback_budget)), h.messages)
        assertEquals(SingPhase.IDLE, h.holder.ui.value.phase)
    }

    @Test
    fun `a cloud result that lands after the song changed is only stored`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()

        h.player.value = StablePlayerState(currentSong = song("2"))
        runCurrent()
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertTrue(h.requests.isEmpty())

        // Back on the first song: the old tap is forgotten, the result is just there for the next one.
        h.player.value = StablePlayerState(currentSong = song("1"))
        runCurrent()
        assertTrue(h.requests.isEmpty())
        assertEquals(SingUi(), h.holder.ui.value)
        assertTrue(h.holder.available.value)
    }

    @Test
    fun `a cloud result that lands while a Connect speaker plays is only stored`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        h.connectDevice.value = "Echo"
        runCurrent()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()

        assertTrue(h.requests.isEmpty())
        assertTrue(h.holder.available.value)
        assertEquals(SingPhase.IDLE, h.holder.ui.value.phase)
    }

    @Test
    fun `a better instrumental landing while another plays changes nothing`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onInstrumentalPlaying()

        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()

        assertTrue(h.requests.isEmpty())
        assertTrue(h.holder.active.value)
    }

    @Test
    fun `a cloud job that finished before the tap does not answer it`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        // Imported earlier, its file since deleted.
        val old = cloudJob("old", RenderJobState.SUCCEEDED, path = "/stems/gone.m4a")
        jobs.flowFor("1").value = listOf(old)
        val h = harness(jobs)

        h.holder.onSingTapped()
        runCurrent()
        assertEquals(listOf("1"), jobs.cloudStarts)
        h.holder.onJobs("1", listOf(old))
        assertTrue(h.requests.isEmpty())
        assertFalse(h.holder.available.value)

        jobs.flowFor("1").value = listOf(old, cloudJob("new", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a", createdAt = 2L))
        runCurrent()
        assertEquals(1, h.requests.size)
        assertEquals("/stems/1_cloud_inst.m4a", (h.requests.single() as SingRequest.Instrumental).path)
    }

    @Test
    fun `the button does not wait for ever when the player never answers after the cloud`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()
        assertTrue(h.holder.ui.value.rendering)

        advanceTimeBy(8_001)
        runCurrent()

        assertFalse(h.holder.ui.value.rendering)
        assertNull(h.holder.ui.value.progress)
    }

    @Test
    fun `going back to the original forgets a switch that was waiting`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs)
        h.holder.onSingTapped()
        runCurrent()
        // The instrumental came in some other way and is playing.
        h.holder.onInstrumentalPlaying()

        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.SUCCEEDED, path = "/stems/1_cloud_inst.m4a"))
        runCurrent()

        assertEquals(listOf<SingRequest>(SingRequest.Original), h.requests)
    }

    // ---- the preview clip ----------------------------------------------------------------------------------

    /** The clip a tap at 10 s gets: 7 s to 70 s of the song. */
    private val clipWindow = SingClipWindow(7_000L, 70_000L)

    private fun landedClip(window: SingClipWindow = clipWindow) =
        cloudJob("clip", RenderJobState.SUCCEEDED, path = "/cache/sing_clips/1_clip_7000.flac", createdAt = 2L, clip = window)

    private val fullPath = "/stems/1_cloud_inst.m4a"

    /** A cloud Sing tapped at 10 s whose preview clip landed first and plays now. */
    private fun TestScope.onClip(jobs: FakeJobs): Harness {
        val h = harness(jobs, positionMs = 10_000L)
        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()
        h.holder.onInstrumentalPlaying()
        runCurrent()
        return h
    }

    @Test
    fun `the cloud is told where the song is, for the preview clip`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 42_000L)

        h.holder.onSingTapped()
        runCurrent()

        assertEquals(listOf<Long?>(42_000L), jobs.playheads)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a preview clip that lands first plays at a gap, with the way back planned before it runs out`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 10_000L)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()

        // In at the next moment (no word timing), back out just after 68 s: 2 s before the clip's end.
        assertEquals(
            listOf<SingRequest>(
                SingRequest.Clip(
                    "/cache/sing_clips/1_clip_7000.flac", clipWindow,
                    plan = CrossfadePlan(startAtPositionMs = 10_360L, fadeMs = 700),
                    returnPlan = CrossfadePlan(startAtPositionMs = 68_060L, fadeMs = 700),
                )
            ),
            h.requests,
        )
        // "Preparing…" until the player says the clip plays; then "Vocals off".
        assertTrue(h.holder.ui.value.rendering)
        assertFalse(h.holder.ui.value.active)
        h.holder.onInstrumentalPlaying()
        runCurrent()
        assertTrue(h.holder.ui.value.active)
        // A clip is not the song's instrumental.
        assertFalse(h.holder.available.value)
    }

    @Test
    fun `the full instrumental landing while the clip plays takes over at once`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = onClip(jobs)

        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.SUCCEEDED, path = fullPath))
        runCurrent()

        // No gap plan: from instrumental to instrumental the player swaps straight away.
        assertEquals(SingRequest.Instrumental(fullPath), h.requests.last())
        assertEquals(2, h.requests.size)
        assertTrue(h.holder.ui.value.active)
        h.holder.onInstrumentalPlaying()
        runCurrent()
        assertTrue(h.holder.ui.value.active)
        assertTrue(h.holder.available.value)
    }

    @Test
    fun `a clip that ran out brings back Preparing, and the full instrumental then fades in at a gap`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = onClip(jobs)

        h.clipEvents.clipLeft("1")
        runCurrent()

        val ui = h.holder.ui.value
        assertFalse(ui.active)
        assertTrue(ui.rendering)
        assertEquals(SingPhase.CLOUD, ui.phase)

        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.SUCCEEDED, path = fullPath))
        runCurrent()
        assertEquals(SingRequest.Instrumental(fullPath, CrossfadePlan(startAtPositionMs = 10_360L, fadeMs = 700)), h.requests.last())
    }

    @Test
    fun `a clip the song is already past is skipped and the wait goes on`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 66_000L)
        h.holder.onSingTapped()
        runCurrent()

        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()

        assertTrue(h.requests.isEmpty())
        assertEquals(SingPhase.CLOUD, h.holder.ui.value.phase)
        // Reported again, it is not tried a second time.
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, percent = 50, phase = CloudPhase.PROCESSING))
        runCurrent()
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a clip is not played once the switch was called off, or over an instrumental already playing`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 10_000L)
        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()
        h.holder.onSingTapped() // calls off the switch
        runCurrent()
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()
        assertTrue(h.requests.isEmpty())

        val other = FakeJobs().apply { cloudBlocker = null }
        val g = harness(other, positionMs = 10_000L)
        g.holder.onInstrumentalPlaying()
        other.flowFor("1").value = listOf(landedClip())
        runCurrent()
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `a clip that could not start keeps the wait without a message`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = harness(jobs, positionMs = 10_000L)
        h.holder.onSingTapped()
        runCurrent()
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.RUNNING, phase = CloudPhase.PROCESSING))
        runCurrent()

        h.holder.onSwitchFailed()
        runCurrent()

        assertEquals(listOf(s(R.string.lyrics_sing_cloud_queued)), h.messages)
        assertEquals(SingPhase.CLOUD, h.holder.ui.value.phase)
        // The full instrumental still switches when it lands.
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.SUCCEEDED, path = fullPath))
        runCurrent()
        assertEquals(fullPath, (h.requests.last() as SingRequest.Instrumental).path)
    }

    @Test
    fun `tapping while the clip plays brings the vocals back for good`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = onClip(jobs)

        h.holder.onSingTapped()
        runCurrent()
        h.holder.onOriginalPlaying()
        jobs.flowFor("1").value = listOf(landedClip(), cloudJob("c", RenderJobState.SUCCEEDED, path = fullPath))
        runCurrent()

        assertEquals(SingRequest.Original, h.requests.last())
        assertFalse(h.holder.ui.value.active)
    }

    @Test
    fun `a clip-left signal for another song, or with no clip playing, changes nothing`() = runTest {
        val jobs = FakeJobs().apply { cloudBlocker = null }
        val h = onClip(jobs)
        h.clipEvents.clipLeft("2")
        runCurrent()
        assertTrue(h.holder.ui.value.active)

        val other = harness(FakeJobs())
        other.holder.onInstrumentalPlaying()
        other.clipEvents.clipLeft("1")
        runCurrent()
        assertTrue(other.holder.ui.value.active)
    }

    @Test
    fun `a song left behind takes its preview clips with it`() = runTest {
        val jobs = FakeJobs()
        val h = harness(jobs)

        h.player.value = StablePlayerState(currentSong = song("2"))
        runCurrent()

        assertEquals(listOf("1"), jobs.discarded)
    }
}
