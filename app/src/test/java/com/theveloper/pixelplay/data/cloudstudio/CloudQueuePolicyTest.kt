package com.theveloper.pixelplay.data.cloudstudio

import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudQueuePolicyTest {
    private fun facts(
        id: String = "s1",
        durationMs: Long = 240_000,
        instrumental: Boolean = false,
        lyrics: CloudLyricsState = CloudLyricsState.TEXT_OR_LINE_SYNCED,
        audio: Boolean = true,
        streamed: Boolean = false,
        pending: Boolean = false,
    ) = CloudSongFacts(id, durationMs, instrumental, lyrics, audio, streamed, pending)

    private fun record(state: CloudJobState = CloudJobState.QUEUED, tasks: List<CloudTask> = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS)) =
        CloudJobRecord(jobKey = CloudFixtures.JOB_KEY, songId = "s1", tasks = tasks, state = state, createdAtMs = 0)

    @Test fun `the happy path through the state machine`() {
        var r = record()
        val events = listOf(
            CloudJobEvent.PREPARE_STARTED to CloudJobState.PREPARING,
            CloudJobEvent.PREPARED to CloudJobState.UPLOADING,
            CloudJobEvent.UPLOAD_FINISHED to CloudJobState.UPLOADED,
            CloudJobEvent.SUBMITTED to CloudJobState.SUBMITTED,
            CloudJobEvent.STARTED to CloudJobState.RUNNING,
            CloudJobEvent.RESULTS_READY to CloudJobState.RESULTS_READY,
            CloudJobEvent.DOWNLOAD_STARTED to CloudJobState.DOWNLOADING,
            CloudJobEvent.IMPORTED to CloudJobState.IMPORTED,
        )
        events.forEachIndexed { index, (event, expected) ->
            r = r.applying(event, index.toLong())!!
            assertEquals(expected, r.state)
        }
        assertEquals(2L, r.uploadedAtMs)
        assertEquals(3L, r.submittedAtMs)
        assertEquals(7L, r.importedAtMs)
        // Nothing moves an imported job.
        CloudJobEvent.entries.forEach { assertNull(r.applying(it, 9)) }
    }

    @Test fun `side branches`() {
        assertEquals(CloudJobState.QUEUED, CloudJobMachine.next(CloudJobState.FAILED, CloudJobEvent.REQUEUE))
        assertEquals(CloudJobState.UPLOADED, CloudJobMachine.next(CloudJobState.RUNNING, CloudJobEvent.RESUBMIT))
        assertEquals(CloudJobState.UPLOADED, CloudJobMachine.next(CloudJobState.EXPIRED, CloudJobEvent.RESUBMIT))
        assertNull(CloudJobMachine.next(CloudJobState.QUEUED, CloudJobEvent.RESUBMIT))
        assertEquals(CloudJobState.CANCELLED, CloudJobMachine.next(CloudJobState.UPLOADING, CloudJobEvent.CANCELLED))
        assertNull(CloudJobMachine.next(CloudJobState.CANCELLED, CloudJobEvent.FAILED))
        assertEquals(CloudJobState.RESULTS_READY, CloudJobMachine.next(CloudJobState.DOWNLOADING, CloudJobEvent.RESULTS_READY))
        assertEquals(CloudJobState.EXPIRED, CloudJobMachine.next(CloudJobState.SUBMITTED, CloudJobEvent.EXPIRED))
        val requeued = record(CloudJobState.RUNNING).copy(runpodJobId = "j", progressStage = "separate", nextAttemptAtMs = 5)
            .on(CloudJobEvent.REQUEUE, 10)
        assertNull(requeued.runpodJobId)
        assertNull(requeued.progressStage)
        assertNull(requeued.nextAttemptAtMs)
    }

    @Test fun `failures climb the backoff ladder, then stop`() {
        var r = record(CloudJobState.UPLOADED)
        r = r.recordingFailure("x", "GPU_OOM", retryable = true, nowMs = 1_000)
        assertEquals(1_000 + 60_000L, r.nextAttemptAtMs)
        assertFalse(r.isDue(30_000))
        assertTrue(r.isDue(61_000))
        r = r.recordingFailure("x", null, retryable = true, nowMs = 2_000)
        assertEquals(2_000 + 300_000L, r.nextAttemptAtMs)
        r = r.recordingFailure("x", null, retryable = true, nowMs = 3_000)
        assertEquals(3_000 + 900_000L, r.nextAttemptAtMs)
        assertEquals(CloudJobState.UPLOADED, r.state)
        r = r.recordingFailure("x", null, retryable = true, nowMs = 4_000)
        assertEquals(CloudJobState.FAILED, r.state)
        assertNull(r.nextAttemptAtMs)
        assertEquals(4, r.attempts)
        val stopped = record(CloudJobState.UPLOADED).recordingFailure("bad", "POISONED", retryable = false, nowMs = 1)
        assertEquals(CloudJobState.FAILED, stopped.state)
        assertEquals("POISONED", stopped.lastErrorCode)
        assertEquals(3_600_000L, CloudTiming.backoffMs(9))
    }

    @Test fun `selection plans one job per song with every missing task`() {
        val options = CloudSelectionOptions()
        val (plan, _) = CloudSelector.plan(facts(), options)
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), plan!!.tasks)
        assertEquals(CloudLyricsMode.ALIGN, plan.lyricsMode)
        assertEquals(CloudLyricsMode.TRANSCRIBE, CloudSelector.plan(facts(lyrics = CloudLyricsState.NONE), options).first!!.lyricsMode)
        assertNull(CloudSelector.plan(facts(lyrics = CloudLyricsState.NONE), options.copy(transcribeWhenMissing = false)).first!!.lyricsMode)
        assertEquals(CloudSkipReason.ALREADY_DONE,
            CloudSelector.plan(facts(instrumental = true, lyrics = CloudLyricsState.WORD_SYNCED), options).second)
        assertEquals(CloudSkipReason.USER_SYNCED,
            CloudSelector.plan(facts(instrumental = true, lyrics = CloudLyricsState.USER_SYNCED), options).second)
        assertEquals(CloudLyricsMode.ALIGN, CloudSelector.plan(facts(lyrics = CloudLyricsState.USER_SYNCED),
            options.copy(replaceUserSynced = true)).first!!.lyricsMode)
        assertEquals(CloudSkipReason.NO_AUDIO, CloudSelector.plan(facts(audio = false), options).second)
        assertEquals(CloudSkipReason.TOO_LONG, CloudSelector.plan(facts(durationMs = 900_001), options).second)
        assertEquals(CloudSkipReason.PENDING_JOB, CloudSelector.plan(facts(pending = true), options).second)
    }

    @Test fun `batches cap at 200 songs and 50 streamed ones, and skip duplicates`() {
        // 60 streamed songs first (50 go, 10 don't), then 200 local ones (150 fit, 50 don't), then a repeat.
        val songs = (0 until 260).map { facts(id = "s$it", streamed = it < 60) } + facts(id = "s1")
        val selection = CloudSelector.select(songs, CloudSelectionOptions())
        assertEquals(200, selection.plans.size)
        assertEquals(50, selection.plans.count { it.isStreamed })
        val counts = selection.skipCounts.toMap()
        assertEquals(50, counts[CloudSkipReason.BATCH_FULL])
        assertEquals(10, counts[CloudSkipReason.TOO_MANY_STREAMED])
        assertEquals(CloudSkipReason.BATCH_FULL, selection.skipCounts.first().first)
        assertEquals(260, selection.plans.size + selection.skipped.size)
    }

    @Test fun `synced hint, quality and the batch gate`() {
        assertTrue(CloudSelector.syncedHint(true, null, 200_000))
        assertTrue(CloudSelector.syncedHint(true, 201_500, 200_000))
        assertFalse(CloudSelector.syncedHint(true, 203_000, 200_000))
        assertFalse(CloudSelector.syncedHint(false, null, 200_000))
        assertEquals(CloudSeparationQuality.STANDARD, CloudSelector.quality(CloudSeparationQuality.BEST, 480_001))
        assertEquals(CloudSeparationQuality.BEST, CloudSelector.quality(CloudSeparationQuality.BEST, 480_000))
        assertFalse(CloudBatchGate.shouldSubmit(2, 0, null, 0))
        assertTrue(CloudBatchGate.shouldSubmit(0, 3, 0, 0))
        assertFalse(CloudBatchGate.shouldSubmit(1, 1, 1_000, 100_000))
        assertTrue(CloudBatchGate.shouldSubmit(1, 1, 1_000, 121_000))
    }

    @Test fun `cost estimates, actual cost and formatting`() {
        val plan = CloudSongPlan("s", listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), CloudLyricsMode.ALIGN, false, 240_000)
        assertEquals(20L, CloudCost.estimatedSeconds(plan, CloudSeparationQuality.STANDARD))
        assertEquals(42L, CloudCost.estimatedSeconds(plan.copy(lyricsMode = CloudLyricsMode.TRANSCRIBE), CloudSeparationQuality.BEST))
        assertEquals(5L, CloudCost.estimatedSeconds(plan.copy(durationMs = 10_000), CloudSeparationQuality.STANDARD))
        assertEquals((35L + 20) * 192, CloudCost.estimateMicroUsd(listOf(plan), CloudSeparationQuality.STANDARD, 192))
        assertEquals(0L, CloudCost.estimateMicroUsd(emptyList(), CloudSeparationQuality.STANDARD, 192))
        assertEquals(161L, CloudCost.pricePerSecondMicroUsd("NVIDIA RTX A4000"))
        assertEquals(306L, CloudCost.pricePerSecondMicroUsd("NVIDIA GeForce RTX 4090"))
        assertEquals(192L, CloudCost.pricePerSecondMicroUsd("NVIDIA L4"))
        assertEquals(777L, CloudCost.pricePerSecondMicroUsd("H100", fallback = 777))
        // 19.4 s + 18.4 s cold start on an L4.
        assertEquals((37_800L * 192 + 999) / 1000,
            CloudCost.actualMicroUsd(CloudTimings(coldStartMs = 18_400, totalMs = 19_400), "NVIDIA L4", 1))
        assertEquals("<$0.01", CloudCost.format(3_840))
        assertEquals("$0.00", CloudCost.format(0))
        assertEquals("$0.39", CloudCost.format(390_000))
        assertEquals("$3.00", CloudCost.format(3_000_000))
        assertEquals("$0.05", CloudCost.format(45_000))
    }

    @Test fun `the monthly budget counts finished jobs and what RunPod holds now`() {
        val month = CloudBudget.monthStartMs(Instant.parse("2026-10-08T15:00:00Z").toEpochMilli(), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), month)
        val local = CloudBudget.monthStartMs(Instant.parse("2026-10-31T23:30:00Z").toEpochMilli(), ZoneId.of("Asia/Ho_Chi_Minh"))
        assertEquals(Instant.parse("2026-10-31T17:00:00Z").toEpochMilli(), local) // already November there
        val done = record(CloudJobState.IMPORTED).copy(completedAtMs = month + 10, costMicroUsd = 5_000)
        val lastMonth = record(CloudJobState.IMPORTED).copy(completedAtMs = month - 10, costMicroUsd = 9_000)
        val running = record(CloudJobState.RUNNING).copy(submittedAtMs = month + 20, durationMs = 240_000)
        val records = listOf(done, lastMonth, running)
        assertEquals(5_000L, CloudBudget.spentMicroUsd(records, month))
        assertEquals(5_000L + 20 * 192, CloudBudget.committedMicroUsd(records, month, 192))
        assertTrue(CloudBudget.allows(1_000, 10_000, 9_000))
        assertFalse(CloudBudget.allows(1_001, 10_000, 9_000))
        assertEquals(0L, CloudBudget.remainingMicroUsd(5, 10))
        val estimate = CloudBatchEstimate.make(listOf(running.plan), CloudSeparationQuality.STANDARD, 192, 2_999_000, 3_000_000)
        assertFalse(estimate.fitsCap)
        assertEquals(4L, estimate.minutes)
        assertTrue(estimate.uploadMb in 20..35)
    }

    @Test fun `import checks`() {
        val ok = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.ok.json"))
        assertTrue(CloudImportCheck.manifestDescribesUpload(ok, ok.input!!.sha256!!.uppercase()))
        assertFalse(CloudImportCheck.manifestDescribesUpload(ok, "00".repeat(32)))
        assertTrue(CloudImportCheck.manifestDescribesUpload(ok.copy(input = null), "00"))
        assertTrue(CloudImportCheck.matches(10, "AB", 10, "ab"))
        assertFalse(CloudImportCheck.matches(10, "ab", 11, "ab"))
        assertTrue(CloudImportCheck.samplesMatch(10_628_100, 44_100.0, 10_628_100 + 1_000, 44_100.0))
        assertFalse(CloudImportCheck.samplesMatch(10_628_100, 44_100.0, 10_628_100 + 2_112, 44_100.0))
        // 48 kHz on both sides compares in 44.1 kHz frames.
        assertTrue(CloudImportCheck.samplesMatch(480_000, 48_000.0, 480_000, 48_000.0))
        assertFalse(CloudImportCheck.samplesMatch(0, 44_100.0, 1, 44_100.0))
    }

    @Test fun `retention, lost jobs and the worker's own limits`() {
        val day = 86_400_000L
        val imported = record(CloudJobState.IMPORTED).copy(importedAtMs = 0)
        assertFalse(CloudRetention.shouldPrune(imported, 30 * day))
        assertTrue(CloudRetention.shouldPrune(imported, 31 * day))
        assertFalse(CloudRetention.shouldPrune(record(CloudJobState.RUNNING), 365 * day))
        assertFalse(CloudRetention.isPastTtl(0, 3 * day))
        assertTrue(CloudRetention.isPastTtl(0, 3 * day + 900_001))
        assertTrue(CloudRetention.inputTooOldToSubmit(null, 0))
        assertTrue(CloudRetention.inputTooOldToSubmit(0, 3 * day + 1))
        assertFalse(CloudRetention.inputTooOldToSubmit(0, day))
        assertNull(CloudLimits.workerRefusal(10, 10, null))
        assertEquals(
            "The prepared file is 61 MB, over your cloud worker's 60 MB limit (PIXL_MAX_INPUT_MB on the endpoint).",
            CloudLimits.workerRefusal(61L * 1_048_576, 1, CloudWorkerCaps(maxInputMB = 60))
        )
        assertEquals(
            "This song is longer than your cloud worker's 10-minute limit (PIXL_MAX_AUDIO_S on the endpoint).",
            CloudLimits.workerRefusal(1, 600_001, CloudWorkerCaps(maxAudioS = 600))
        )
        assertEquals(263_700, CloudTiming.WORKER_PRESIGN_SECONDS)
        val paused = RunPodHealth(inQueue = 3)
        assertTrue(CloudEndpointWatch.looksPaused(0, 31 * 60_000, paused))
        assertFalse(CloudEndpointWatch.looksPaused(0, 29 * 60_000, paused))
        assertFalse(CloudEndpointWatch.looksPaused(0, 31 * 60_000, paused.copy(runningWorkers = 1)))
    }

    @Test fun `taking a result records cost once and keeps duplicates free`() {
        val ok = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.ok.json"))
        val taken = record(CloudJobState.RUNNING).takingResult(ok, 192, 100)
        assertEquals(CloudFixtures.JOB_KEY + "/lyrics.json", taken.lyricsKey!!.removePrefix("out/"))
        assertEquals(listOf("lyrics: 1 line fell back to line timing"), taken.warnings)
        assertEquals("NVIDIA L4", taken.gpu)
        val cost = taken.costMicroUsd!!
        assertTrue(cost > 0)
        val duplicate = taken.takingResult(ok.copy(warnings = listOf("duplicate")), 192, 200)
        assertEquals(cost, duplicate.costMicroUsd)
        assertTrue(duplicate.warnings.isEmpty())
        assertFalse(taken.isFullyImported)
        assertTrue(taken.copy(importedInstrumental = true, importedLyrics = true).isFullyImported)
        // A result without the lyrics file counts as done for lyrics.
        assertTrue(taken.copy(importedInstrumental = true, lyricsKey = null).isFullyImported)
    }

    @Test fun `job keys and content types`() {
        assertTrue(CloudKeys.isValidJobKey(CloudFixtures.JOB_KEY))
        assertFalse(CloudKeys.isValidJobKey(CloudFixtures.JOB_KEY.uppercase()))
        assertFalse(CloudKeys.isValidJobKey("../" + CloudFixtures.JOB_KEY.drop(3)))
        assertNull(CloudKeys.jobKeyFromOutputKey("in/${CloudFixtures.JOB_KEY}.flac"))
        assertEquals(CloudFixtures.JOB_KEY, CloudKeys.jobKeyFromOutputKey("out/${CloudFixtures.JOB_KEY}/manifest.json"))
        assertEquals("audio/flac", CloudKeys.contentType("FLAC"))
        assertEquals("audio/mp4", CloudKeys.contentType("m4a"))
    }
}
