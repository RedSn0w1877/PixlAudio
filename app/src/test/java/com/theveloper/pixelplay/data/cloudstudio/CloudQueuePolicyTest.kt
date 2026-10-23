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
        // The 2026-10-22 bench (RTX A4500): 38 s warm for a 4-minute song, 15 s for a cold start.
        assertEquals(38L, CloudCost.WARM_SECONDS_STANDARD)
        assertEquals(15L, CloudCost.COLD_START_SECONDS)
        assertEquals(38L, CloudCost.estimatedSeconds(plan, CloudSeparationQuality.STANDARD))
        assertEquals(60L, CloudCost.estimatedSeconds(plan.copy(lyricsMode = CloudLyricsMode.TRANSCRIBE), CloudSeparationQuality.BEST))
        assertEquals(10L, CloudCost.estimatedSeconds(plan.copy(durationMs = 10_000), CloudSeparationQuality.STANDARD))
        assertEquals((15L + 38) * 192, CloudCost.estimateMicroUsd(listOf(plan), CloudSeparationQuality.STANDARD, 192))
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
        assertEquals(5_000L + 38 * 192, CloudBudget.committedMicroUsd(records, month, 192))
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

    // ─── Forcing the instrumental (Sing, automatic preparing) ───────────────────────────────

    @Test fun `forcing the instrumental overrides an Outputs toggle that is off`() {
        val off = CloudSelectionOptions(instrumental = false)
        // With the toggle off and nothing else to do, there is no job at all.
        assertEquals(CloudSkipReason.ALREADY_DONE, CloudSelector.plan(facts(lyrics = CloudLyricsState.WORD_SYNCED), off).second)
        assertEquals(listOf(CloudTask.LYRICS), CloudSelector.plan(facts(), off).first!!.tasks)
        assertEquals(
            listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS),
            CloudSelector.plan(facts(), off.copy(forceInstrumental = true)).first!!.tasks,
        )
        // Lyrics already word-timed: the job is the instrumental alone.
        assertEquals(
            listOf(CloudTask.INSTRUMENTAL),
            CloudSelector.plan(facts(lyrics = CloudLyricsState.WORD_SYNCED), off.copy(forceInstrumental = true)).first!!.tasks,
        )
        // An instrumental that exists is never asked for again.
        assertEquals(
            CloudSkipReason.ALREADY_DONE,
            CloudSelector.plan(facts(instrumental = true, lyrics = CloudLyricsState.WORD_SYNCED), off.copy(forceInstrumental = true)).second,
        )
    }

    // ─── The priority lane ──────────────────────────────────────────────────────────────────

    private fun laneJob(
        key: String,
        state: CloudJobState = CloudJobState.SUBMITTED,
        origin: CloudJobOrigin = CloudJobOrigin.MANUAL,
        priority: Boolean = false,
        runpodId: String? = "rp-$key",
        submittedAt: Long? = 1_000,
    ) = CloudJobRecord(
        jobKey = key, songId = key, origin = origin, priorityAtMs = if (priority) 5L else null, state = state,
        runpodJobId = runpodId, submittedAtMs = submittedAt, createdAtMs = 0, uploadedAtMs = 10,
    )

    @Test fun `a job that steps aside goes back to uploaded and keeps its upload`() {
        assertEquals(CloudJobState.UPLOADED, CloudJobMachine.next(CloudJobState.SUBMITTED, CloudJobEvent.PAUSE))
        // Only a job still waiting in RunPod's queue can step aside: never a running one, nor any other state.
        CloudJobState.entries.filter { it != CloudJobState.SUBMITTED }.forEach {
            assertNull(CloudJobMachine.next(it, CloudJobEvent.PAUSE), "$it must not pause")
        }
        val queued = laneJob("a").copy(sha256 = "ab", inputExt = "flac", attempts = 1)
        val intent = queued.beginningPause()
        assertEquals("rp-a", intent.pauseIntentRunpodJobId)
        assertTrue(intent.hasPauseIntent)
        assertFalse(intent.isPaused)
        // The intent belongs to one RunPod run: a new run id is no longer the one being paused.
        assertFalse(intent.copy(runpodJobId = "rp-other").hasPauseIntent)
        val paused = intent.completingPause("P", neverStarted = true, nowMs = 50)!!
        assertEquals(CloudJobState.UPLOADED, paused.state)
        assertNull(paused.runpodJobId)
        assertEquals("P", paused.pausedByJobKey)
        assertEquals(50L, paused.pausedAtMs)
        assertEquals(true, paused.pausedNeverStarted)
        assertNull(paused.pauseIntentRunpodJobId)
        assertTrue(paused.isPaused)
        // Its upload, its first submission time and its tries are untouched: pausing costs the job nothing.
        assertEquals(10L, paused.uploadedAtMs)
        assertEquals(1_000L, paused.submittedAtMs)
        assertEquals(1, paused.attempts)
        assertEquals("ab", paused.sha256)
        assertNull(laneJob("r", state = CloudJobState.RUNNING).completingPause("P", neverStarted = true, nowMs = 1))
    }

    @Test fun `every other event ends a pause, and releasing keeps what the resubmit needs`() {
        val paused = laneJob("a").completingPause("P", neverStarted = true, nowMs = 50)!!
        val sent = paused.applying(CloudJobEvent.SUBMITTED, 60)!!
        assertNull(sent.pausedByJobKey)
        assertNull(sent.pausedAtMs)
        assertNull(sent.pausedNeverStarted)
        assertEquals(CloudJobState.SUBMITTED, sent.state)
        assertNull(paused.applying(CloudJobEvent.REQUEUE, 60)!!.pausedNeverStarted)
        assertNull(paused.on(CloudJobEvent.CANCELLED, 60).pausedByJobKey)
        // A started job loses a pause that was only an intent.
        val intent = laneJob("b").beginningPause()
        val started = intent.applying(CloudJobEvent.STARTED, 60)!!
        assertNull(started.pauseIntentRunpodJobId)
        assertEquals(CloudJobState.RUNNING, started.state)
        // Released: no longer waiting for anyone, but the resubmit still knows the run never began.
        val released = paused.releasingPause()
        assertFalse(released.isPaused)
        assertNull(released.pausedAtMs)
        assertEquals(true, released.pausedNeverStarted)
        assertEquals(CloudJobState.UPLOADED, released.state)
        // An intent that was dropped (it started after all) is back to a plain submitted job.
        assertNull(intent.clearingPauseIntent().pauseIntentRunpodJobId)
    }

    @Test fun `who goes first on the phone, and what the lane may pause`() {
        assertFalse(laneJob("m").isPriority)
        assertTrue(laneJob("s", origin = CloudJobOrigin.SING).isPriority)
        assertTrue(laneJob("p", priority = true).isPriority)
        assertEquals(0, laneJob("s", origin = CloudJobOrigin.SING).lane)
        assertEquals(0, laneJob("p", priority = true).lane)
        assertEquals(1, laneJob("m").lane)
        assertEquals(2, laneJob("a", origin = CloudJobOrigin.AUTO).lane)
        val jobs = listOf(
            laneJob("a", origin = CloudJobOrigin.AUTO), laneJob("m"), laneJob("s", origin = CloudJobOrigin.SING),
            laneJob("p", priority = true), laneJob("a2", origin = CloudJobOrigin.AUTO),
        )
        assertEquals(listOf("s", "p", "m", "a", "a2"), CloudPriorityLane.phoneOrder(jobs).map { it.jobKey })

        val queue = listOf(
            laneJob("run", state = CloudJobState.RUNNING),
            laneJob("auto", origin = CloudJobOrigin.AUTO),
            laneJob("manual"),
            laneJob("otherSing", origin = CloudJobOrigin.SING),
            laneJob("onePriority", priority = true),
            laneJob("noRunId", runpodId = null),
            laneJob("waitingAtPhone", state = CloudJobState.UPLOADED),
            laneJob("P", state = CloudJobState.UPLOADED, origin = CloudJobOrigin.SING),
        )
        assertEquals(listOf("auto", "manual"), CloudPriorityLane.pausable(queue, "P").map { it.jobKey })
    }

    @Test fun `the tail of RunPod's queue is cancelled before its head`() {
        val a = laneJob("a", submittedAt = 1_000)
        val b = laneJob("b", submittedAt = 1_000)
        val c = laneJob("c", submittedAt = 900)
        val jobs = listOf(a, b, c)
        // Newest submission first; one burst (the same instant) goes from its last job back to its first.
        assertEquals(listOf("b", "a", "c"), CloudPriorityLane.cancelOrder(jobs, jobs).map { it.jobKey })
    }

    @Test fun `only a status that says the job is still queued lets it be cancelled`() {
        assertEquals(PauseCheck.IN_QUEUE, CloudPriorityLane.check(RunPodJobStatus.IN_QUEUE))
        assertEquals(PauseCheck.STARTED, CloudPriorityLane.check(RunPodJobStatus.IN_PROGRESS))
        listOf(RunPodJobStatus.COMPLETED, RunPodJobStatus.FAILED, RunPodJobStatus.CANCELLED, RunPodJobStatus.TIMED_OUT)
            .forEach { assertEquals(PauseCheck.FINISHED, CloudPriorityLane.check(it)) }
        assertEquals(PauseCheck.UNKNOWN, CloudPriorityLane.check(null))
        assertTrue(CloudPriorityLane.cancelConfirmed(RunPodJob("x", "CANCELLED")))
        assertFalse(CloudPriorityLane.cancelConfirmed(RunPodJob("x", "IN_PROGRESS")))
        assertFalse(CloudPriorityLane.cancelConfirmed(RunPodJob("x", "COMPLETED")))
        assertFalse(CloudPriorityLane.cancelConfirmed(RunPodJob("x", "something new")))
        assertFalse(CloudPriorityLane.cancelConfirmed(null))
    }

    // ─── Preview clip ───────────────────────────────────────────────────────────────────────

    @Test fun `a preview clip covers the minute from 3 s before the playhead, inside the song`() {
        assertEquals(CloudClipRange(57_000, 120_000), CloudClipPlanner.range(60_000, 240_000))
        assertEquals(63_000, CloudClipPlanner.range(60_000, 240_000)!!.lengthMs)
        // At the start of the song the clip starts at 0; near the end it stops at the end.
        assertEquals(CloudClipRange(0, 61_000), CloudClipPlanner.range(1_000, 240_000))
        assertEquals(CloudClipRange(0, 60_000), CloudClipPlanner.range(-500, 240_000))
        assertEquals(CloudClipRange(187_000, 240_000), CloudClipPlanner.range(190_000, 240_000))
    }

    @Test fun `no clip for a short song or a playhead near the end`() {
        assertNull(CloudClipPlanner.range(10_000, 74_999), "shorter than 75 s")
        assertTrue(CloudClipPlanner.range(10_000, 75_000) != null)
        // Within 20 s of the end: the full job alone.
        assertNull(CloudClipPlanner.range(220_000, 240_000))
        assertTrue(CloudClipPlanner.range(219_999, 240_000) != null)
        assertNull(CloudClipPlanner.range(10_000, 0), "an unknown length")
    }

    @Test fun `a clip's plan is the instrumental only at the clip's length, and costs a small share of the song`() {
        val clip = CloudClipPlanner.plan("s1", isStreamed = true, CloudClipRange(57_000, 120_000))
        assertEquals(CloudSongPlan("s1", listOf(CloudTask.INSTRUMENTAL), null, true, 63_000), clip)
        val song = CloudSongPlan("s1", listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), CloudLyricsMode.ALIGN, true, 240_000)
        // 38 warm seconds for the song; the clip's 63 s are 10 more on the same warm worker (one cold start for both).
        assertEquals(10, CloudCost.estimatedSeconds(clip, CloudSeparationQuality.STANDARD))
        assertEquals((15 + 38 + 10) * 192L, CloudCost.estimateMicroUsd(listOf(clip, song), CloudSeparationQuality.STANDARD, 192))
    }

    @Test fun `the clip is cut sample-exactly out of the decode's buffers`() {
        // Clip frames 1,000..3,000; buffers of 1,024 frames from the start of the song.
        assertEquals(CloudFrameSlice(1_000, 1_024), CloudClipFrames.slice(0, 1_024, 1_000, 3_000))
        assertEquals(CloudFrameSlice(0, 1_024), CloudClipFrames.slice(1_024, 1_024, 1_000, 3_000))
        assertEquals(CloudFrameSlice(0, 952), CloudClipFrames.slice(2_048, 1_024, 1_000, 3_000))
        assertNull(CloudClipFrames.slice(3_072, 1_024, 1_000, 3_000), "after the clip")
        assertNull(CloudClipFrames.slice(0, 512, 1_000, 3_000), "before the clip")
        // Every frame of the clip, exactly once.
        val total = (0 until 4).mapNotNull { CloudClipFrames.slice(it * 1_024L, 1_024, 1_000, 3_000) }.sumOf { it.frames }
        assertEquals(2_000, total)
    }

    private fun clipOf(key: String, full: String, state: CloudJobState = CloudJobState.UPLOADED) =
        laneJob(key, state = state, origin = CloudJobOrigin.SING, runpodId = null, submittedAt = null)
            .copy(tasks = listOf(CloudTask.INSTRUMENTAL), clipStartMs = 57_000, clipEndMs = 120_000, clipOfJobKey = full)

    @Test fun `a clip goes ahead of the songs in the priority lane, on the phone and in the burst`() {
        val full = laneJob("F", state = CloudJobState.UPLOADED, origin = CloudJobOrigin.SING, runpodId = null, submittedAt = null)
        val clip = clipOf("C", "F")
        val manual = laneJob("m", state = CloudJobState.UPLOADED, runpodId = null, submittedAt = null)
        // The clip was added after its song (a Sing that joined), yet it goes first.
        val jobs = listOf(manual, full, clip)
        assertEquals(listOf("C", "F", "m"), CloudPriorityLane.phoneOrder(jobs).map { it.jobKey })
        assertEquals(listOf("C", "F", "m"), CloudPriorityLane.submitOrder(setOf("m", "F", "C"), jobs))
    }

    @Test fun `a clip-then-song burst carries exactly one stop flag, on its last job`() {
        val queuedFull = laneJob("F", state = CloudJobState.QUEUED, origin = CloudJobOrigin.SING, runpodId = null, submittedAt = null)
        val clip = clipOf("C", "F")
        // Both ready together: one burst [clip, song]; only the song is the last, as for any burst.
        val readyFull = queuedFull.copy(state = CloudJobState.UPLOADED)
        assertEquals(listOf("C", "F"), CloudPriorityLane.submitOrder(setOf("C", "F"), listOf(readyFull, clip)))
        assertTrue(CloudPriorityLane.marksLastInBatch(readyFull, listOf(readyFull, clip)))
        // The clip alone, its song still being uploaded: it doesn't stop the worker the song is coming to.
        assertEquals(queuedFull, CloudPriorityLane.siblingStillComing(clip, listOf(queuedFull, clip)))
        assertFalse(CloudPriorityLane.marksLastInBatch(clip, listOf(queuedFull, clip)))
        // Once the song has gone out (or failed, or isn't a priority job) the clip is a normal last.
        listOf(
            queuedFull.copy(state = CloudJobState.SUBMITTED), queuedFull.copy(state = CloudJobState.FAILED),
            queuedFull.copy(origin = CloudJobOrigin.MANUAL),
        ).forEach { sibling -> assertTrue(CloudPriorityLane.marksLastInBatch(clip, listOf(sibling, clip)), "with $sibling") }
        assertTrue(CloudPriorityLane.marksLastInBatch(clip, listOf(clip)), "its song is gone")
        assertTrue(CloudPriorityLane.marksLastInBatch(null, emptyList()))
    }

    @Test fun `jobs that step aside for a clip wait for its song`() {
        val full = laneJob("F", state = CloudJobState.UPLOADING, origin = CloudJobOrigin.SING, runpodId = null, submittedAt = null)
        val clip = clipOf("C", "F")
        assertEquals("F", CloudPriorityLane.waitTarget(clip, listOf(full, clip)))
        assertEquals("C", CloudPriorityLane.waitTarget(clip, listOf(full.copy(state = CloudJobState.SUBMITTED), clip)))
        assertEquals("F", CloudPriorityLane.waitTarget(full, listOf(full, clip)))
    }

    @Test fun `ready jobs go out priority first, then in list order`() {
        val jobs = listOf(
            laneJob("a", state = CloudJobState.UPLOADED), laneJob("b", state = CloudJobState.UPLOADED),
            laneJob("S", state = CloudJobState.UPLOADED, origin = CloudJobOrigin.SING), laneJob("c", state = CloudJobState.UPLOADED),
        )
        assertEquals(listOf("S", "a", "b", "c"), CloudPriorityLane.submitOrder(setOf("c", "a", "S", "b"), jobs))
        // Only what is ready, and a key that isn't on the list is left out.
        assertEquals(listOf("S", "c"), CloudPriorityLane.submitOrder(listOf("c", "S", "ghost"), jobs))
    }

    @Test fun `jobs wait for a priority job that is still on its way, for a few minutes at most`() {
        val hold = CloudTiming.PRIORITY_HOLD_MAX_MS
        val priority = laneJob("P", state = CloudJobState.UPLOADING, origin = CloudJobOrigin.SING).copy(priorityAtMs = 1_000)
        val paused = laneJob("a").completingPause("P", neverStarted = true, nowMs = 2_000)!!
        val auto = laneJob("auto", state = CloudJobState.UPLOADED, origin = CloudJobOrigin.AUTO)
        val manual = laneJob("manual", state = CloudJobState.UPLOADED)
        val jobs = listOf(priority, paused, auto, manual)
        assertTrue(CloudPriorityLane.isHeld(paused, jobs, emptySet(), nowMs = 3_000))
        assertTrue(CloudPriorityLane.isHeld(auto, jobs, emptySet(), nowMs = 3_000))
        assertFalse(CloudPriorityLane.isHeld(manual, jobs, emptySet(), nowMs = 3_000), "the person's own batch is never held")
        // The priority job going out in this very pass holds nobody back.
        assertFalse(CloudPriorityLane.isHeld(paused, jobs, setOf("P"), nowMs = 3_000))
        assertFalse(CloudPriorityLane.isHeld(auto, jobs, setOf("P"), nowMs = 3_000))
        // A hold ends by itself: a priority job stuck on Wi-Fi can't freeze the queue.
        assertTrue(CloudPriorityLane.isHeld(paused, jobs, emptySet(), nowMs = 2_000 + hold - 1))
        assertFalse(CloudPriorityLane.isHeld(paused, jobs, emptySet(), nowMs = 2_000 + hold))
        assertTrue(CloudPriorityLane.isHeld(auto, jobs, emptySet(), nowMs = 1_000 + hold - 1))
        assertFalse(CloudPriorityLane.isHeld(auto, jobs, emptySet(), nowMs = 1_000 + hold))
        // Once the priority job is at RunPod (or gone) the wait is over.
        val sent = priority.copy(state = CloudJobState.SUBMITTED)
        assertFalse(CloudPriorityLane.isHeld(paused, listOf(sent, paused), emptySet(), nowMs = 3_000))
        assertFalse(CloudPriorityLane.isHeld(paused, listOf(paused), emptySet(), nowMs = 3_000))
        assertFalse(CloudPriorityLane.isHeld(auto, listOf(sent, auto), emptySet(), nowMs = 3_000))
    }

    @Test fun `a pause is released when the priority job has moved on or the hold ran out`() {
        val hold = CloudTiming.PRIORITY_HOLD_MAX_MS
        val priority = laneJob("P", state = CloudJobState.UPLOADED, origin = CloudJobOrigin.SING)
        val waiting = laneJob("a").completingPause("P", neverStarted = false, nowMs = 100)!!
        assertEquals(emptyList<String>(), CloudPriorityLane.releasable(listOf(priority, waiting), nowMs = 200))
        assertEquals(listOf("a"), CloudPriorityLane.releasable(listOf(priority, waiting), nowMs = 100 + hold))
        assertEquals(listOf("a"), CloudPriorityLane.releasable(listOf(priority.copy(state = CloudJobState.SUBMITTED), waiting), nowMs = 200))
        assertEquals(listOf("a"), CloudPriorityLane.releasable(listOf(priority.copy(state = CloudJobState.FAILED), waiting), nowMs = 200))
        assertEquals(listOf("a"), CloudPriorityLane.releasable(listOf(waiting), nowMs = 200), "its priority job is gone")
        // A job that is not paused is never "released".
        assertEquals(emptyList<String>(), CloudPriorityLane.releasable(listOf(laneJob("m", state = CloudJobState.UPLOADED)), nowMs = 1))
    }

    @Test fun `the lane's timings`() {
        assertEquals(3_000L, CloudTiming.SING_POLL_INTERVAL_MS)
        assertTrue(CloudTiming.SING_POLL_INTERVAL_MS < CloudTiming.STATUS_POLL_INTERVAL_MS)
        // RunPod allows 100 cancels per 10 s; the app keeps to 120 ms apart (about 83 per 10 s).
        assertEquals(120L, CloudTiming.CANCEL_MIN_INTERVAL_MS)
        assertTrue(10_000 / CloudTiming.CANCEL_MIN_INTERVAL_MS < 100)
        assertEquals(5L * 60_000, CloudTiming.PRIORITY_HOLD_MAX_MS)
    }

    // ─── Smart auto-prepare's own share of the month ────────────────────────────────────────

    @Test fun `automatic jobs have their own budget inside the shared cap`() {
        val month = 1_000_000L
        val done = record(CloudJobState.IMPORTED).copy(origin = CloudJobOrigin.AUTO, completedAtMs = month + 10, costMicroUsd = 5_000)
        val doneLastMonth = done.copy(completedAtMs = month - 1, costMicroUsd = 7_000)
        val manual = record(CloudJobState.IMPORTED).copy(completedAtMs = month + 10, costMicroUsd = 9_000)
        val running = record(CloudJobState.RUNNING).copy(
            origin = CloudJobOrigin.AUTO, submittedAtMs = month + 20, durationMs = 240_000, lyricsMode = CloudLyricsMode.ALIGN,
        )
        val records = listOf(done, doneLastMonth, manual, running)
        // Automatic jobs only: this month's recorded cost, what RunPod holds now, and what removed ones spent.
        assertEquals(5_000L + 38 * 192, CloudBudget.autoCommittedMicroUsd(records, month, 192, 0))
        assertEquals(5_000L + 38 * 192 + 1_500, CloudBudget.autoCommittedMicroUsd(records, month, 192, 1_500))
        assertEquals(1_500L, CloudBudget.autoCommittedMicroUsd(emptyList(), month, 192, 1_500))
        assertEquals(0L, CloudBudget.autoCommittedMicroUsd(emptyList(), month, 192, -9), "a negative ledger value counts as 0")
        // The shared month, for comparison, counts the person's job too.
        assertEquals(5_000L + 9_000 + 38 * 192, CloudBudget.committedMicroUsd(records, month, 192))
    }

    @Test fun `an automatic job needs room in both budgets`() {
        // Auto share $1.00 with $0.90 used; the cap has plenty.
        assertTrue(CloudBudget.allowsAuto(100_000, 1_000_000, 900_000, 5_000_000))
        assertFalse(CloudBudget.allowsAuto(100_001, 1_000_000, 900_000, 5_000_000), "over its own share")
        // The auto share has room, the shared cap does not.
        assertTrue(CloudBudget.allowsAuto(60_000, 1_000_000, 0, 60_000))
        assertFalse(CloudBudget.allowsAuto(60_001, 1_000_000, 0, 60_000), "over the month's cap")
        assertFalse(CloudBudget.allowsAuto(1, 1_000_000, 1_000_000, 5_000_000), "its share is used up")
        assertFalse(CloudBudget.allowsAuto(1, 1_000_000, 0, -5), "a negative cap remainder is none")
        assertTrue(CloudBudget.allowsAuto(0, 0, 0, 0), "nothing to pay")
        assertFalse(CloudBudget.allowsAuto(1, 0, 0, 5_000_000), "a $0.00 budget allows nothing")
    }

    @Test fun `the day starts where the phone's calendar says`() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val late = Instant.parse("2026-10-31T20:00:00Z").toEpochMilli() // already November 1 at 03:00 there
        assertEquals(Instant.parse("2026-10-31T17:00:00Z").toEpochMilli(), CloudBudget.dayStartMs(late, zone))
        assertEquals(Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(), CloudBudget.dayStartMs(late))
        assertEquals(CloudBudget.dayStartMs(late, zone), CloudBudget.dayStartMs(late + 3_600_000, zone))
    }
}
