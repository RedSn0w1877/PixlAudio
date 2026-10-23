package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudJobToRenderJobTest {

    private fun record(
        state: CloudJobState,
        streamed: Boolean = false,
        importedInstrumental: Boolean = false,
        progressPercent: Int? = null,
        tasks: List<CloudTask> = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS),
        origin: CloudJobOrigin = CloudJobOrigin.SING,
        error: String? = null,
    ) = CloudJobRecord(
        jobKey = "job-1",
        songId = "1",
        tasks = tasks,
        state = state,
        isStreamed = streamed,
        importedInstrumental = importedInstrumental,
        progressPercent = progressPercent,
        lastError = error,
        origin = origin,
        createdAtMs = 42L,
    )

    private fun map(record: CloudJobRecord, wifi: Boolean = false, transfer: Float? = null, path: String? = null) =
        record.toRenderJob(wifi, transfer, path)

    // ---- which jobs count ----------------------------------------------------------------------

    @Test
    fun `a job that makes no instrumental is not a render job`() {
        assertNull(map(record(CloudJobState.RUNNING, tasks = listOf(CloudTask.LYRICS))))
    }

    @Test
    fun `identity and origin carry over`() {
        val job = map(record(CloudJobState.SUBMITTED))!!

        assertEquals("job-1", job.id)
        assertEquals(42L, job.createdAtMs)
        assertEquals(RenderSource.CLOUD, job.source)
        assertFalse(job.automatic)
        assertTrue(map(record(CloudJobState.SUBMITTED, origin = CloudJobOrigin.AUTO))!!.automatic)
        assertFalse(map(record(CloudJobState.SUBMITTED, origin = CloudJobOrigin.MANUAL))!!.automatic)
    }

    // ---- every state, with its phase -----------------------------------------------------------

    @Test
    fun `steps before the GPU are queued, each with its phase`() {
        val expected = mapOf(
            CloudJobState.QUEUED to CloudPhase.PREPARING,
            CloudJobState.PREPARING to CloudPhase.PREPARING,
            CloudJobState.UPLOADING to CloudPhase.UPLOADING,
            CloudJobState.UPLOADED to CloudPhase.UPLOADING,
            CloudJobState.SUBMITTED to CloudPhase.QUEUED_AT_GPU,
        )
        expected.forEach { (state, phase) ->
            val job = map(record(state))!!
            assertEquals(RenderJobState.QUEUED, job.state, "state of $state")
            assertEquals(phase, job.phase, "phase of $state")
            assertNull(job.instrumentalPath)
        }
    }

    @Test
    fun `at the GPU the job runs`() {
        val job = map(record(CloudJobState.RUNNING, progressPercent = 40))!!

        assertEquals(RenderJobState.RUNNING, job.state)
        assertEquals(CloudPhase.PROCESSING, job.phase)
        assertEquals(66, job.percent)
    }

    @Test
    fun `results waiting to be imported keep the job running while they download`() {
        listOf(CloudJobState.RESULTS_READY, CloudJobState.DOWNLOADING).forEach { state ->
            val job = map(record(state))!!
            assertEquals(RenderJobState.RUNNING, job.state, "state of $state")
            assertEquals(CloudPhase.DOWNLOADING, job.phase, "phase of $state")
        }
    }

    @Test
    fun `the instrumental imported is a success before the whole job is imported`() {
        // The lyrics import comes first and the job only reaches IMPORTED after both: the Sing switch must not wait.
        val job = map(record(CloudJobState.DOWNLOADING, importedInstrumental = true), path = "/stems/1_cloud_inst.m4a")!!

        assertEquals(RenderJobState.SUCCEEDED, job.state)
        assertEquals("/stems/1_cloud_inst.m4a", job.instrumentalPath)
        assertEquals(100, job.percent)
        assertNull(job.phase)
    }

    @Test
    fun `an imported job succeeds with the file on disk`() {
        val job = map(record(CloudJobState.IMPORTED, importedInstrumental = true), path = "/stems/1_cloud_inst.flac")!!

        assertEquals(RenderJobState.SUCCEEDED, job.state)
        assertEquals("/stems/1_cloud_inst.flac", job.instrumentalPath)
    }

    @Test
    fun `an imported instrumental outlives a later failure of the job`() {
        // The lyrics step can fail after the instrumental came in: the instrumental is still there.
        val job = map(record(CloudJobState.FAILED, importedInstrumental = true, error = "lyrics"), path = "/p")!!

        assertEquals(RenderJobState.SUCCEEDED, job.state)
        assertNull(job.failureReason)
    }

    @Test
    fun `a path is only carried by a finished job`() {
        assertNull(map(record(CloudJobState.RUNNING), path = "/stems/1_cloud_inst.m4a")!!.instrumentalPath)
    }

    @Test
    fun `failed and expired jobs fail with the last error`() {
        listOf(CloudJobState.FAILED, CloudJobState.EXPIRED).forEach { state ->
            val job = map(record(state, error = "boom"))!!
            assertEquals(RenderJobState.FAILED, job.state, "state of $state")
            assertEquals("boom", job.failureReason)
            assertNull(job.phase)
        }
    }

    @Test
    fun `a cancelled job is cancelled`() {
        val job = map(record(CloudJobState.CANCELLED))!!

        assertEquals(RenderJobState.CANCELLED, job.state)
        assertNull(job.failureReason)
    }

    // ---- waiting for Wi-Fi ---------------------------------------------------------------------

    @Test
    fun `an upload waits for Wi-Fi when the engine says so`() {
        assertEquals(CloudPhase.WAITING_FOR_WIFI, map(record(CloudJobState.UPLOADING), wifi = true)!!.phase)
        assertEquals(CloudPhase.UPLOADING, map(record(CloudJobState.UPLOADING), wifi = false)!!.phase)
    }

    @Test
    fun `only a streamed song waits for Wi-Fi before it is prepared`() {
        assertEquals(CloudPhase.WAITING_FOR_WIFI, map(record(CloudJobState.QUEUED, streamed = true), wifi = true)!!.phase)
        assertEquals(CloudPhase.PREPARING, map(record(CloudJobState.QUEUED, streamed = false), wifi = true)!!.phase)
    }

    @Test
    fun `a result waiting to download waits for Wi-Fi`() {
        assertEquals(CloudPhase.WAITING_FOR_WIFI, map(record(CloudJobState.RESULTS_READY), wifi = true)!!.phase)
    }

    @Test
    fun `steps at the GPU never wait for Wi-Fi`() {
        listOf(CloudJobState.SUBMITTED, CloudJobState.RUNNING).forEach { state ->
            assertNotNull(map(record(state), wifi = true)!!.phase)
            assertTrue(map(record(state), wifi = true)!!.phase != CloudPhase.WAITING_FOR_WIFI, "state $state")
        }
    }

    // ---- the fill ------------------------------------------------------------------------------

    @Test
    fun `progress follows the step and its real fractions`() {
        fun fill(state: CloudJobState, transfer: Float? = null, percent: Int? = null) =
            record(state, progressPercent = percent).progressFraction(transfer)

        assertEquals(0f, fill(CloudJobState.QUEUED), 0.0001f)
        assertEquals(0.05f, fill(CloudJobState.PREPARING), 0.0001f)
        assertEquals(0.1f, fill(CloudJobState.UPLOADING), 0.0001f)
        assertEquals(0.25f, fill(CloudJobState.UPLOADING, transfer = 0.5f), 0.0001f)
        assertEquals(0.4f, fill(CloudJobState.UPLOADED), 0.0001f)
        assertEquals(0.45f, fill(CloudJobState.SUBMITTED), 0.0001f)
        assertEquals(0.5f, fill(CloudJobState.RUNNING), 0.0001f)
        assertEquals(0.7f, fill(CloudJobState.RUNNING, percent = 50), 0.0001f)
        assertEquals(0.9f, fill(CloudJobState.RESULTS_READY), 0.0001f)
        assertEquals(0.95f, fill(CloudJobState.DOWNLOADING, transfer = 0.5f), 0.0001f)
        assertEquals(1f, fill(CloudJobState.IMPORTED), 0.0001f)
        assertEquals(0f, fill(CloudJobState.FAILED), 0.0001f)
    }

    @Test
    fun `progress clamps stray numbers`() {
        assertEquals(0.4f, record(CloudJobState.UPLOADING).progressFraction(5f), 0.0001f)
        assertEquals(0.9f, record(CloudJobState.RUNNING, progressPercent = 400).progressFraction(null), 0.0001f)
    }
}
