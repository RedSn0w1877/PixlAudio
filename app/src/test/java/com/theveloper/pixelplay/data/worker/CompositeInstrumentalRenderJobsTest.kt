package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudSingStart
import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioState
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioStateHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The app's render jobs: on-device (WorkManager) and Cloud Studio jobs for one song, side by side, with a
 * cloud job done as soon as its instrumental is imported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeInstrumentalRenderJobsTest {

    private val song = Song(
        id = "1", title = "Song", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L,
        path = "path", contentUriString = "content://dummy/1", albumArtUriString = null,
        duration = 180_000L, mimeType = "audio/mpeg", bitrate = null, sampleRate = null
    )

    private fun record(
        state: CloudJobState,
        importedInstrumental: Boolean = false,
        streamed: Boolean = false,
    ) = CloudJobRecord(
        jobKey = "job-1",
        songId = "1",
        tasks = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS),
        state = state,
        isStreamed = streamed,
        importedInstrumental = importedInstrumental,
        origin = CloudJobOrigin.SING,
        createdAtMs = 5L,
    )

    private fun workJob() = InstrumentalRenderJob(
        id = "work-1", state = RenderJobState.RUNNING, percent = 30, instrumentalPath = null,
        failureReason = null, automatic = false, createdAtMs = 1L,
    )

    private class Fixture(
        val jobs: CompositeInstrumentalRenderJobs,
        val onDevice: WorkManagerInstrumentalRenderJobs,
        val holder: CloudStudioStateHolder,
        val engine: MutableStateFlow<CloudStudioState>,
    )

    private fun fixture(
        work: List<InstrumentalRenderJob> = emptyList(),
        records: Flow<List<CloudJobRecord>> = flowOf(emptyList()),
        engine: CloudStudioState = CloudStudioState(),
        pathOnDisk: String? = null,
    ): Fixture {
        val onDevice = mockk<WorkManagerInstrumentalRenderJobs>()
        every { onDevice.jobsFor("1") } returns flowOf(work)
        coEvery { onDevice.bestAvailablePath("1") } returns pathOnDisk
        val engineState = MutableStateFlow(engine)
        val holder = mockk<CloudStudioStateHolder>()
        every { holder.engineState } returns engineState
        every { holder.cloudJobsFor("1") } returns records
        val lazyHolder = object : dagger.Lazy<CloudStudioStateHolder> {
            override fun get(): CloudStudioStateHolder = holder
        }
        return Fixture(CompositeInstrumentalRenderJobs(onDevice, lazyHolder), onDevice, holder, engineState)
    }

    @Test
    fun `on-device and cloud jobs are listed side by side`() = runTest {
        val f = fixture(work = listOf(workJob()), records = flowOf(listOf(record(CloudJobState.RUNNING))))

        val jobs = f.jobs.jobsFor("1").first()

        assertEquals(listOf(RenderSource.ON_DEVICE, RenderSource.CLOUD), jobs.map { it.source })
        assertEquals(listOf("work-1", "job-1"), jobs.map { it.id })
    }

    @Test
    fun `a cloud job is done when its instrumental is imported, with the file that is on disk`() = runTest {
        // The lyrics import comes first and the job itself is still downloading: the instrumental is already there.
        val f = fixture(
            records = flowOf(listOf(record(CloudJobState.DOWNLOADING, importedInstrumental = true))),
            pathOnDisk = "/stems/1_cloud_inst.m4a",
        )

        val job = f.jobs.jobsFor("1").first().single()

        assertEquals(RenderJobState.SUCCEEDED, job.state)
        assertEquals("/stems/1_cloud_inst.m4a", job.instrumentalPath)
        assertEquals(RenderSource.CLOUD, job.source)
    }

    @Test
    fun `the disk is only asked while something was imported`() = runTest {
        val f = fixture(records = flowOf(listOf(record(CloudJobState.RUNNING))), pathOnDisk = "/stems/x.m4a")

        val job = f.jobs.jobsFor("1").first().single()

        assertEquals(RenderJobState.RUNNING, job.state)
        assertNull(job.instrumentalPath)
        coVerify(exactly = 0) { f.onDevice.bestAvailablePath(any()) }
    }

    @Test
    fun `waiting for Wi-Fi follows the engine's notice`() = runTest {
        val f = fixture(
            records = flowOf(listOf(record(CloudJobState.UPLOADING))),
            engine = CloudStudioState(notice = CloudNotice.WAITING_FOR_WIFI),
        )
        assertEquals(CloudPhase.WAITING_FOR_WIFI, f.jobs.jobsFor("1").first().single().phase)

        f.engine.value = CloudStudioState()
        assertEquals(CloudPhase.UPLOADING, f.jobs.jobsFor("1").first().single().phase)
    }

    @Test
    fun `a running upload fills the job`() = runTest {
        val f = fixture(
            records = flowOf(listOf(record(CloudJobState.UPLOADING))),
            engine = CloudStudioState(transferProgress = mapOf("job-1" to 0.5f)),
        )

        // The upload is the 10% to 40% stretch of the whole job: halfway through it is 25%.
        assertEquals(25, f.jobs.jobsFor("1").first().single().percent)
    }

    @Test
    fun `a job that makes no instrumental is left out`() = runTest {
        val noInstrumental = record(CloudJobState.RUNNING).copy(tasks = listOf(CloudTask.LYRICS))
        val f = fixture(work = listOf(workJob()), records = flowOf(listOf(noInstrumental)))

        assertEquals(listOf("work-1"), f.jobs.jobsFor("1").first().map { it.id })
    }

    @Test
    fun `a Cloud Studio failure leaves the on-device jobs alone`() = runTest {
        val f = fixture(
            work = listOf(workJob()),
            records = flow { throw IllegalStateException("boom") },
        )

        assertEquals(listOf("work-1"), f.jobs.jobsFor("1").first().map { it.id })
    }

    @Test
    fun `the cloud's answer and its job start come from Cloud Studio`() = runTest {
        val f = fixture()
        every { f.holder.singAvailability(song) } returns CloudSingUnavailable.CAP_REACHED
        coEvery { f.holder.startSing(song) } returns CloudSingStart.Started("job-1")

        assertEquals(CloudSingUnavailable.CAP_REACHED, f.jobs.cloudAvailability(song))
        assertEquals(CloudSingStart.Started("job-1"), f.jobs.startCloud(song))
    }
}
