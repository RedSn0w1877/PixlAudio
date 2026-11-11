package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudLibraryJob
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.CAP_REACHED
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.DOWNLOADING
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.FAILED
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.NONE
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.PROCESSING
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.QUEUED
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.READY
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus.WAITING_FOR_NETWORK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** cloud-audio-spec §6.1's state per song and profile, the failure backoff, and the background queue's rules. */
class SpatialLibraryRulesTest {
    private val key = SpatialRenderKey("J7p4bzqLvCw", SpatialProfile.STUDIO)

    private fun job(state: CloudJobState, ahead: Boolean = false) = CloudJobRecord(
        jobKey = "3d9f0c52-7a41-4e8b-b6f3-91c2d5e7a804", songId = "s", tasks = listOf(CloudTask.SPATIAL),
        origin = CloudJobOrigin.LIBRARY, library = CloudLibraryJob(key.videoId, listOf("studio"), ahead = ahead), state = state,
    )

    private fun status(
        hasRender: Boolean = false,
        activity: SpatialActivity? = null,
        job: CloudJobRecord? = null,
        failed: Boolean = false,
        personNetworkOk: Boolean = true,
        aheadNetworkOk: Boolean = true,
        capReached: Boolean = false,
    ): SpatialRenditionStatus =
        SpatialStatusRules.status(hasRender, activity, job, failed, personNetworkOk, aheadNetworkOk, capReached)

    @Test fun `a render on the phone is ready, whatever else is going on`() {
        assertEquals(READY, status(hasRender = true, activity = SpatialActivity.FETCH, job = job(CloudJobState.RUNNING), failed = true))
        assertEquals(NONE, status())
    }

    @Test fun `what the library does itself comes before a job and a failure`() {
        assertEquals(QUEUED, status(activity = SpatialActivity.LOOKUP, failed = true))
        assertEquals(QUEUED, status(activity = SpatialActivity.SENDING))
        assertEquals(DOWNLOADING, status(activity = SpatialActivity.FETCH))
        assertEquals(WAITING_FOR_NETWORK, status(activity = SpatialActivity.WAITING_FOR_NETWORK))
        assertEquals(CAP_REACHED, status(activity = SpatialActivity.CAP_REACHED))
        assertEquals(QUEUED, status(activity = SpatialActivity.LOOKUP, job = job(CloudJobState.RUNNING)))
    }

    @Test fun `a job's state maps onto the spec's states`() {
        assertEquals(QUEUED, status(job = job(CloudJobState.QUEUED)))
        assertEquals(QUEUED, status(job = job(CloudJobState.PREPARING)))
        assertEquals(QUEUED, status(job = job(CloudJobState.UPLOADING)))
        assertEquals(QUEUED, status(job = job(CloudJobState.UPLOADED)))
        assertEquals(QUEUED, status(job = job(CloudJobState.SUBMITTED)), "waiting for a worker is still queued")
        assertEquals(PROCESSING, status(job = job(CloudJobState.RUNNING)))
        assertEquals(DOWNLOADING, status(job = job(CloudJobState.RESULTS_READY)))
        assertEquals(DOWNLOADING, status(job = job(CloudJobState.DOWNLOADING)))
        // Not pending: the job says nothing, a recorded failure does.
        assertEquals(NONE, status(job = job(CloudJobState.IMPORTED)))
        assertEquals(FAILED, status(job = job(CloudJobState.FAILED), failed = true))
        // A pending job is a new attempt: it hides the last failure.
        assertEquals(PROCESSING, status(job = job(CloudJobState.RUNNING), failed = true))
    }

    @Test fun `a job waits for the network its rules allow, and for the month's cap`() {
        assertEquals(WAITING_FOR_NETWORK, status(job = job(CloudJobState.QUEUED), personNetworkOk = false))
        assertEquals(WAITING_FOR_NETWORK, status(job = job(CloudJobState.UPLOADING), personNetworkOk = false))
        assertEquals(WAITING_FOR_NETWORK, status(job = job(CloudJobState.RESULTS_READY), personNetworkOk = false))
        assertEquals(PROCESSING, status(job = job(CloudJobState.RUNNING), personNetworkOk = false), "the GPU doesn't need the phone")
        // The background queue's job follows its own network (Wi-Fi, no Data Saver), the person's doesn't matter.
        assertEquals(WAITING_FOR_NETWORK, status(job = job(CloudJobState.QUEUED, ahead = true), aheadNetworkOk = false))
        assertEquals(QUEUED, status(job = job(CloudJobState.QUEUED, ahead = true), personNetworkOk = false))
        assertEquals(CAP_REACHED, status(job = job(CloudJobState.UPLOADED), capReached = true))
        assertEquals(QUEUED, status(job = job(CloudJobState.QUEUED), capReached = true), "the cap holds a job back at submit")
        assertTrue(status(job = job(CloudJobState.UPLOADED), capReached = true).isQueued)
    }

    @Test fun `the backoff is 10 minutes, then an hour, then a day`() {
        assertEquals(10L * 60_000, SpatialBackoff.delayMs(1))
        assertEquals(60L * 60_000, SpatialBackoff.delayMs(2))
        assertEquals(24L * 60 * 60_000, SpatialBackoff.delayMs(3))
        assertEquals(24L * 60 * 60_000, SpatialBackoff.delayMs(9))
        val failure = SpatialFailure(count = 2, lastFailedAtMs = 1_000)
        assertEquals(1_000 + 3_600_000L, failure.retryAtMs)
        assertTrue(SpatialBackoff.isWaiting(failure, 1_000 + 3_599_999))
        assertFalse(SpatialBackoff.isWaiting(failure, 1_000 + 3_600_000))
        assertFalse(SpatialBackoff.isWaiting(null, 0))
    }

    @Test fun `the failure ledger counts each failure once, clears on success and survives a restart`() {
        val saved = HashMap<String, String>()
        val ledger = SpatialFailureLedger(load = { saved }, save = { saved.clear(); saved.putAll(it) })
        assertNull(ledger.failure(key))
        assertEquals(1, ledger.recordFailure(key, 1_000)?.count)
        assertEquals(1, ledger.recordFailure(key, 1_000)?.count, "the same failure read again")
        assertEquals(1, ledger.recordFailure(key, 500)?.count, "an older one is already counted")
        assertEquals(2, ledger.recordFailure(key, 2_000)?.count)
        val version = ledger.version.value
        // A success from before the last failure doesn't clear it; a later one does.
        ledger.clear(key, 1_500)
        assertEquals(2, ledger.failure(key)?.count)
        val restarted = SpatialFailureLedger(load = { saved })
        assertEquals(SpatialFailure(2, 2_000), restarted.failure(key))
        ledger.clear(key, 2_500)
        assertNull(ledger.failure(key))
        assertTrue(ledger.version.value > version)
        // A failure from before the success, reported late (a failed job read again), stays forgotten, after a restart too.
        assertNull(ledger.recordFailure(key, 2_400))
        assertNull(SpatialFailureLedger(load = { saved }).recordFailure(key, 2_200))
        assertNull(ledger.failure(key))
        // A new failure starts the ladder again.
        assertEquals(1, ledger.recordFailure(key, 3_000)?.count)
        // A success with nothing failed before is remembered too.
        val other = SpatialRenderKey("ZD6rXLXZOEI", SpatialProfile.NIGHT)
        ledger.clear(other, 5_000)
        assertNull(ledger.recordFailure(other, 4_000))
        assertEquals(1, ledger.recordFailure(other, 6_000)?.count)
        assertEquals(key, SpatialFailureLedger.key(SpatialFailureLedger.name(key)))
        assertNull(SpatialFailureLedger.key("J7p4bzqLvCw/atmos"))
    }

    @Test fun `the ledger stays small`() {
        val ledger = SpatialFailureLedger()
        val start = 10L * 86_400_000
        for (index in 0 until SpatialFailureLedger.MAX_ENTRIES + 10) {
            ledger.recordFailure(SpatialRenderKey("v%010d".format(index), SpatialProfile.STUDIO), start + index)
        }
        assertNull(ledger.failure(SpatialRenderKey("v%010d".format(0), SpatialProfile.STUDIO)), "the oldest went")
        assertNotNull(ledger.failure(SpatialRenderKey("v%010d".format(SpatialFailureLedger.MAX_ENTRIES + 9), SpatialProfile.STUDIO)))
        // Old enough to forget.
        ledger.recordFailure(key, start + SpatialFailureLedger.FORGET_AFTER_MS + 10_000)
        assertNull(ledger.failure(SpatialRenderKey("v%010d".format(SpatialFailureLedger.MAX_ENTRIES + 9), SpatialProfile.STUDIO)))
    }

    @Test fun `a full ledger isn't rewritten for successes and failures older than everything it keeps`() {
        var saves = 0
        val ledger = SpatialFailureLedger(save = { saves++ })
        val start = 10L * 86_400_000
        fun keyOf(prefix: String, index: Int) = SpatialRenderKey(prefix + "%010d".format(index), SpatialProfile.STUDIO)
        for (index in 0 until SpatialFailureLedger.MAX_ENTRIES) ledger.clear(keyOf("n", index), start + 1_000 + index)
        val saved = saves
        val version = ledger.version.value
        // Delivered jobs stay on the list 30 days and are read on every change of it: once the ledger is full, the
        // older ones would be dropped as soon as they went in, so nothing is written for them, however often.
        repeat(3) {
            for (index in 0 until 100) ledger.clear(keyOf("o", index), start + index)
        }
        assertEquals(saved, saves)
        assertEquals(version, ledger.version.value)
        // A failure reported that late changes nothing either (and is still answered).
        assertEquals(1, ledger.recordFailure(keyOf("o", 0), start)?.count)
        assertNull(ledger.failure(keyOf("o", 0)))
        assertEquals(saved, saves)
        // Anything newer still goes in, and the oldest entry makes room for it.
        assertEquals(1, ledger.recordFailure(keyOf("f", 0), start + 5_000)?.count)
        assertEquals(1, ledger.failure(keyOf("f", 0))?.count)
        assertEquals(saved + 1, saves)
        ledger.clear(keyOf("c", 0), start + 6_000)
        assertEquals(saved + 2, saves)
        assertTrue(ledger.version.value > version)
        // A success remembered before still holds a late failure back (the full ledger changes nothing about that).
        assertNull(ledger.recordFailure(keyOf("c", 0), start + 5_500))
    }

    @Test fun `the person's network rule for cloud work, and the queue's Wi-Fi only`() {
        val wifi = SpatialNetwork(connected = true, unmetered = true)
        val mobile = SpatialNetwork(connected = true, unmetered = false)
        assertTrue(wifi.allowsCloudWork(useCellular = false))
        assertFalse(mobile.allowsCloudWork(useCellular = false))
        assertTrue(mobile.allowsCloudWork(useCellular = true))
        assertFalse(SpatialNetwork().allowsCloudWork(useCellular = true), "offline")
        assertTrue(wifi.allowsAhead)
        assertFalse(mobile.allowsAhead)
        assertFalse(wifi.copy(dataSaver = true).allowsAhead)
    }

    @Test fun `the background queue runs on Wi-Fi with a battery that isn't low, never under Data Saver`() {
        val wifi = SpatialNetwork(connected = true, unmetered = true)
        assertNull(SpatialAheadRules.block(SpatialAheadConditions(wifi, batteryPercent = 50)))
        assertNull(SpatialAheadRules.block(SpatialAheadConditions(wifi, batteryPercent = -1)), "unknown battery doesn't hold it")
        assertNotNull(SpatialAheadRules.block(SpatialAheadConditions(wifi, batteryPercent = 14)))
        assertNull(SpatialAheadRules.block(SpatialAheadConditions(wifi, batteryPercent = 15)))
        assertNotNull(SpatialAheadRules.block(SpatialAheadConditions(wifi.copy(unmetered = false), batteryPercent = 80)))
        assertNotNull(SpatialAheadRules.block(SpatialAheadConditions(wifi.copy(dataSaver = true), batteryPercent = 80)))
        assertNotNull(SpatialAheadRules.block(SpatialAheadConditions(SpatialNetwork(), batteryPercent = 80)))
        // The day's count: what is left of cloudAudio.dailyAhead, at most a run's share.
        assertEquals(SpatialAheadRules.JOBS_PER_RUN, SpatialAheadRules.jobRoom(dailyAhead = 25, sentToday = 0))
        assertEquals(3, SpatialAheadRules.jobRoom(dailyAhead = 25, sentToday = 22))
        assertEquals(0, SpatialAheadRules.jobRoom(dailyAhead = 25, sentToday = 25))
        assertEquals(0, SpatialAheadRules.jobRoom(dailyAhead = 1, sentToday = 4))
        // Runs every 2 hours with a run's share reach the default 25 a day.
        assertTrue(24 / SpatialAheadRules.PERIOD_HOURS * SpatialAheadRules.JOBS_PER_RUN >= 25)
    }
}
