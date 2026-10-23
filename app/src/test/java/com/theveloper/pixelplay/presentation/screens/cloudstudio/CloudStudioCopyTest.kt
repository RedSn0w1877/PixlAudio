package com.theveloper.pixelplay.presentation.screens.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudSkipReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The lines a "Process in cloud" tap answers with, now that no confirm sheet reports them. */
class CloudStudioCopyTest {
    @Test
    fun `started message counts the songs and mentions Wi-Fi only when uploads wait for it`() {
        assertEquals("Processing 1 song in the cloud", CloudStudioCopy.startedMessage(1, waitsForWifi = false))
        assertEquals("Processing 3 songs in the cloud", CloudStudioCopy.startedMessage(3, waitsForWifi = false))
        assertEquals(
            "Processing 2 songs in the cloud — it uploads when you're on Wi-Fi.",
            CloudStudioCopy.startedMessage(2, waitsForWifi = true),
        )
    }

    @Test
    fun `nothing to process names the first reason in lower case`() {
        assertEquals("Nothing to process.", CloudStudioCopy.nothingToProcessMessage(null))
        assertEquals(
            "Nothing to process: already waiting in the cloud queue.",
            CloudStudioCopy.nothingToProcessMessage(CloudSkipReason.PENDING_JOB),
        )
    }

    @Test
    fun `the off message points at Settings, Sing, Cloud processing`() {
        assertEquals(
            "Cloud processing is off. Turn it on in Settings › Sing › Cloud processing.",
            CloudStudioCopy.OFF_MESSAGE,
        )
        assertEquals(CloudStudioCopy.OFF_MESSAGE, CloudStudioCopy.notice(CloudNotice.OFF))
    }

    @Test
    fun `the cap message never sends a normal listener to a hidden setting`() {
        assertTrue("Raise" !in CloudStudioCopy.CAP_MESSAGE)
        assertTrue("monthly cap" !in CloudStudioCopy.CAP_MESSAGE)
    }

    @Test
    fun `queue notices about keys and the cloud read in plain words outside developer mode`() {
        val keyNotices = listOf(
            CloudNotice.NOT_CONFIGURED,
            CloudNotice.KEYS_MISSING,
            CloudNotice.SECURE_STORAGE_UNAVAILABLE,
            CloudNotice.RUNPOD_KEY_REFUSED,
            CloudNotice.ENDPOINT_NOT_FOUND,
            CloudNotice.ENDPOINT_PAUSED,
        )
        keyNotices.forEach { notice ->
            assertEquals(CloudStudioCopy.UNAVAILABLE_MESSAGE, CloudStudioCopy.notice(notice))
        }
        assertEquals(CloudStudioCopy.CAP_MESSAGE, CloudStudioCopy.notice(CloudNotice.CAP_REACHED))
        assertTrue("RunPod" in CloudStudioCopy.notice(CloudNotice.RUNPOD_KEY_REFUSED, developerMode = true))
    }

    @Test
    fun `only the Wi-Fi notice sends a listener to Cloud processing`() {
        CloudNotice.entries.forEach { notice ->
            assertEquals(
                notice == CloudNotice.WAITING_FOR_WIFI,
                CloudStudioCopy.noticeOpensSettings(notice),
                notice.name,
            )
        }
        assertTrue(CloudStudioCopy.noticeOpensSettings(CloudNotice.RUNPOD_KEY_REFUSED, developerMode = true))
    }

    @Test
    fun `a failed job keeps the cloud's own explanation for developers`() {
        val failed = CloudJobRecord(
            jobKey = "job",
            songId = "song",
            state = CloudJobState.FAILED,
            lastError = "RunPod lost this job twice.",
            lastErrorCode = "lost",
        )
        assertEquals(CloudStudioCopy.FAILED_DETAIL, CloudStudioCopy.detail(failed))
        assertEquals("Failed", CloudStudioCopy.status(failed, progress = null))
        assertEquals("RunPod lost this job twice.", CloudStudioCopy.detail(failed, developerMode = true))
        assertEquals("Failed · lost", CloudStudioCopy.status(failed, progress = null, developerMode = true))
        assertFalse("RunPod" in CloudStudioCopy.FAILED_DETAIL)
    }

    @Test
    fun `a job that stepped aside for a song being sung says so`() {
        val queued = CloudJobRecord(
            jobKey = "job", songId = "song", state = CloudJobState.SUBMITTED, runpodJobId = "rp-1", submittedAtMs = 1,
        )
        assertEquals("Waiting in the cloud", CloudStudioCopy.status(queued, progress = null))
        val paused = queued.completingPause("P", neverStarted = true, nowMs = 5)!!
        assertEquals("Paused for a song you're singing", CloudStudioCopy.status(paused, progress = null))
        assertEquals(CloudStudioCopy.PAUSED_FOR_SING, CloudStudioCopy.status(paused, progress = null, developerMode = true))
        // Released (the Sing went out, or was cancelled), it is just an uploaded job again.
        assertEquals("Uploaded", CloudStudioCopy.status(paused.releasingPause(), progress = null))
        // A pause that is only an intent (the cancel isn't confirmed yet) still reads as waiting.
        assertEquals("Waiting in the cloud", CloudStudioCopy.status(queued.beginningPause(), progress = null))
        // A failure's retry wording wins over the pause.
        val retrying = paused.copy(nextAttemptAtMs = 99, lastError = "x")
        assertEquals("Trying again soon", CloudStudioCopy.status(retrying, progress = null))
    }
}
