package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The orchestrator end to end with fakes (design §7.6): a local song from Send to the imported instrumental and
 * lyrics, plus the three fixes the iOS review asked for (a restart never erases stored jobs, a retried worker error
 * isn't re-read as the new run's answer, streamed songs go up decoded) and the failure paths.
 */
class CloudStudioEngineTest {
    @TempDir lateinit var directory: Path

    private var now = 1_800_000_000_000L
    /** A worker pass with room for transfers (a pass doesn't start one in its last 3 minutes). */
    private val PASS_MS = 10L * 60_000
    private val keys = ArrayDeque((1..50).map { "%08x-0000-4000-8000-%012x".format(it, it) })
    private lateinit var settings: FakeSettings
    private lateinit var store: FakeStore
    private lateinit var host: FakeHost
    private lateinit var preparer: FakePreparer
    private lateinit var bucket: FakeBucket
    private lateinit var runpod: FakeRunPod
    private lateinit var scheduler: FakeScheduler
    private var unmetered = true
    /** The phone is on a charger and Wi-Fi with a healthy battery: what automatic jobs need for their own steps. */
    private var autoWork = true
    private var builtIn: CloudBuiltInConfig = NoBuiltInCloudConfig
    /** Every configuration the engine built RunPod clients from, in order. */
    private val configsSeen = mutableListOf<CloudConfigInput>()
    /** Bucket deletes and RunPod runs, in the order they happened. */
    private val events = mutableListOf<String>()

    private val localSong = CloudSong("101", "Local", "Artist", "Album", 240_000, isStreamed = false, hasAudioSource = true)
    private val streamedSong = CloudSong("spotify_x", "Streamed", "Artist", "Album", 200_000, isStreamed = true, hasAudioSource = true)

    @BeforeEach fun setUp() {
        settings = FakeSettings()
        store = FakeStore()
        host = FakeHost(listOf(localSong, streamedSong))
        preparer = FakePreparer(directory.resolve("uploads").toFile())
        bucket = FakeBucket()
        runpod = FakeRunPod(bucket)
        scheduler = FakeScheduler()
    }

    private fun engine(): CloudStudioEngine = CloudStudioEngine(
        CloudStudioDependencies(
            settings = settings, store = store, host = host, preparer = preparer, transfers = bucket,
            makeRunPod = { configsSeen += it; if (it.hasRunPod) runpod else null },
            makeObjects = { if (it.hasStorage) bucket else null }, builtIn = builtIn,
            scheduler = scheduler, scope = CoroutineScope(Dispatchers.Unconfined), build = "test (1)",
            stagingDir = directory.resolve("staging").toFile(), isUnmetered = { unmetered },
            nowMs = { now }, monthStartMs = { 0L }, dayStartMs = { CloudBudget.dayStartMs(it) },
            newJobKey = { keys.removeFirst() }, autoWorkAllowed = { autoWork },
        )
    )

    private suspend fun sendOne(engine: CloudStudioEngine, song: CloudSong = localSong): CloudJobRecord {
        val preview = engine.preview(listOf(song), "Current song")
        assertEquals(1, engine.send(preview))
        return engine.state.value.jobs.last()
    }

    @Test fun `a local song goes from Send to an imported instrumental and word-timed lyrics`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), record.tasks)
        assertTrue(scheduler.passRequests > 0)
        engine.workerPass(now + PASS_MS)
        val done = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertTrue(done.importedInstrumental && done.importedLyrics)
        assertEquals(false, preparer.forceDecodes.single())
        // The /run body: FLAC input from the preparer, URLs signed for the worker, the song's lyrics.
        val input = runpod.runs.single().input
        assertEquals("flac", input.audio?.ext)
        assertEquals(preparer.sha, input.audio?.sha256)
        assertEquals(CloudSchema.CLIENT_APP, input.client?.app)
        assertEquals("align", input.lyrics?.mode)
        assertEquals(listOf("first line", "second line"), input.lyrics?.lines?.map { it.text })
        assertTrue(done.costMicroUsd!! > 0)
        // Results went into the stores, and every object of the job left the bucket.
        assertEquals(record.songId to false, host.installed.single().let { it.second to it.third })
        assertEquals(listOf(record.songId), host.instrumentalNotes)
        assertEquals(CloudLyrics.SOURCE, host.savedLyrics.single().metadata.source)
        assertTrue(bucket.objects.isEmpty(), "left behind: ${bucket.objects.keys}")
        assertFalse(preparer.hasUpload(record.jobKey))
        assertEquals(1, scheduler.watchCancels)
    }

    @Test fun `a restart never erases the stored jobs`() = runBlocking {
        // A previous process left two jobs on disk.
        store.saved = listOf(
            CloudJobRecord(jobKey = keys.removeFirst(), songId = "a", state = CloudJobState.SUBMITTED, runpodJobId = "r1",
                submittedAtMs = now, createdAtMs = now - 2),
            CloudJobRecord(jobKey = keys.removeFirst(), songId = "b", state = CloudJobState.IMPORTED, createdAtMs = now - 1,
                importedAtMs = now),
        )
        val stored = store.saved.map { it.jobKey }
        // A fresh engine's first act changes the list before anything else read it.
        val engine = engine()
        engine.cancel("ffffffff-0000-4000-8000-ffffffffffff") // unknown job
        sendOne(engine)
        assertEquals(stored, store.saved.take(2).map { it.jobKey })
        assertEquals(3, store.saved.size)
        assertTrue(store.history.all { snapshot -> stored.all { key -> snapshot.any { it.jobKey == key } } })
        // And a pass while switched off never writes an empty list either.
        settings.snapshot = settings.snapshot.copy(enabled = false)
        val fresh = engine()
        fresh.workerPass(now + PASS_MS)
        assertEquals(3, store.saved.size)
        assertEquals(CloudNotice.OFF, fresh.state.value.notice)
    }

    @Test fun `a retried worker error is not re-read as the new run's answer`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        // The first run dies with GPU_OOM; the worker writes an error manifest and its guard marker.
        runpod.behaviour = { request, id ->
            val key = request.input.jobKey!!
            bucket.objects[CloudKeys.attempt(key)] = """{"schema":"pixl.cloudstudio.attempt","v":1,"runpodJobId":"$id","attempts":1}""".toByteArray()
            bucket.objects[CloudKeys.manifest(key)] = CloudJson.encodeToString(
                CloudJobResult(jobKey = key, status = "error", error = CloudResultError("GPU_OOM", "out of memory"))
            ).toByteArray()
            RunPodJob(id, "IN_QUEUE")
        }
        engine.workerPass(now + PASS_MS)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.UPLOADED, job.state)
        assertEquals("GPU_OOM", job.lastErrorCode)
        assertEquals(1, job.attempts)
        // After the backoff the job goes out again; this run is still queued at RunPod.
        now = job.nextAttemptAtMs!! + 1
        runpod.behaviour = { _, id -> RunPodJob(id, "IN_QUEUE") }
        runpod.statusFor = { id -> RunPodJob(id, "IN_QUEUE") }
        engine.workerPass(now + PASS_MS)
        job = engine.job(record.jobKey)!!
        assertEquals(2, runpod.runs.size)
        // The old manifest and marker were removed BEFORE the second /run, so the listing can't take them.
        val secondRunAt = events.lastIndexOf("RUN")
        val manifestDeletedAt = events.indexOf("DELETE ${CloudKeys.manifest(record.jobKey)}")
        val attemptDeletedAt = events.indexOf("DELETE ${CloudKeys.attempt(record.jobKey)}")
        assertTrue(manifestDeletedAt in 0 until secondRunAt, "events: $events")
        assertTrue(attemptDeletedAt in 0 until secondRunAt, "events: $events")
        assertTrue(job.state.isAtRunPod, "state ${job.state}")
        assertEquals(1, job.attempts)
    }

    // ─── Idle workers (last_in_batch) ───────────────────────────────────────────────────────

    private val threeSongs = listOf("a", "b", "c").map { CloudSong(it, "Song $it", "Artist", "Album", 240_000, false, true) }

    @Test fun `a single song is a burst of one and asks the worker to stop after it`() = runBlocking {
        val engine = engine()
        sendOne(engine)
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        val run = runpod.runs.single()
        assertEquals(CloudJobInputPolicy(lastInBatch = true), run.input.policy)
        // RunPod's own request policy is untouched.
        assertEquals(CloudJobPolicy(CloudTiming.TTL_MS, CloudTiming.EXECUTION_TIMEOUT_MS), run.policy)
    }

    @Test fun `only the last job of a burst asks the worker to stop`() = runBlocking {
        host = FakeHost(threeSongs)
        runpod.finishImmediately = false
        val engine = engine()
        assertEquals(3, engine.send(engine.preview(threeSongs, "Three")))
        val keys = engine.state.value.jobs.map { it.jobKey }
        engine.workerPass(now + PASS_MS)
        assertEquals(keys, runpod.runs.map { it.input.jobKey }, "one burst, in queue order")
        // The worker stays warm between the songs and stops itself after the last one; the others send no policy.
        assertEquals(listOf(null, null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.map { it.input.policy })
        assertTrue(engine.state.value.jobs.all { it.state.isAtRunPod }, "states: ${engine.state.value.jobs.map { it.state }}")
    }

    @Test fun `a job sent later is the last of its own burst`() = runBlocking {
        host = FakeHost(threeSongs)
        runpod.finishImmediately = false
        val engine = engine()
        sendOne(engine, threeSongs[0])
        engine.workerPass(now + PASS_MS)
        now += 1_000
        sendOne(engine, threeSongs[1])
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf(true, true), runpod.runs.map { it.input.isLastInBatch })
    }

    @Test fun `the cap ends a burst with the last job that went out`() = runBlocking {
        host = FakeHost(threeSongs)
        runpod.finishImmediately = false
        val engine = engine()
        assertEquals(3, engine.send(engine.preview(threeSongs, "Three")))
        val keys = engine.state.value.jobs.map { it.jobKey }
        // Room for two songs this month: the third waits, and the second is the burst's last.
        val record = engine.job(keys[0])!!
        val plan = record.plan.copy(durationMs = 240_000)
        val estimate = CloudCost.estimatedSeconds(plan, record.quality) * settings.snapshot.pricePerSecondMicroUsd
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 2 * estimate)
        engine.workerPass(now + PASS_MS)
        assertEquals(keys.take(2), runpod.runs.map { it.input.jobKey })
        assertEquals(listOf(false, true), runpod.runs.map { it.input.isLastInBatch })
        assertEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        assertEquals(CloudJobState.UPLOADED, engine.job(keys[2])!!.state)
    }

    // ─── Review fixes ───────────────────────────────────────────────────────────────────────

    @Test fun `switching the feature off mid-pass stops preparing and sending at the next step`() = runBlocking {
        host = FakeHost(threeSongs)
        val engine = engine()
        assertEquals(3, engine.send(engine.preview(threeSongs, "Three")))
        // The person switches Cloud processing off while the first song is being prepared.
        preparer.onPrepare = { settings.snapshot = settings.snapshot.copy(enabled = false) }
        val outcome = engine.workerPass(now + PASS_MS)
        assertEquals(1, preparer.forceDecodes.size, "only the song already in hand was prepared")
        assertTrue(runpod.runs.isEmpty(), "nothing was sent after the switch went off")
        assertTrue(outcome.blocked)
        assertEquals(CloudNotice.OFF, engine.state.value.notice)
    }

    @Test fun `cancel stops a job at RunPod even before any pass ran in this process`() = runBlocking {
        val key = keys.removeFirst()
        store.saved = listOf(CloudJobRecord(jobKey = key, songId = localSong.id, state = CloudJobState.SUBMITTED,
            runpodJobId = "rp-old", submittedAtMs = now, createdAtMs = now))
        val engine = engine()
        engine.cancel(key)
        assertEquals(CloudJobState.CANCELLED, engine.job(key)!!.state)
        assertEquals(listOf("rp-old"), runpod.cancelled)
    }

    @Test fun `a retry wait ends once the step goes through`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        runpod.finishImmediately = false
        bucket.failUploads = 1
        engine.workerPass(now + PASS_MS)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.UPLOADING, job.state)
        assertNotNull(job.nextAttemptAtMs)
        now = job.nextAttemptAtMs!! + 1
        engine.workerPass(now + PASS_MS)
        job = engine.job(record.jobKey)!!
        assertTrue(job.state.isAtRunPod, "state ${job.state}")
        assertNull(job.nextAttemptAtMs, "no \"Trying again soon\" once the upload went through")
    }

    @Test fun `a streamed song is decoded, tied to its video, and refused if the match changed`() = runBlocking {
        host.videoIds[streamedSong.id] = "VIDEO_A"
        val engine = engine()
        val record = sendOne(engine, streamedSong)
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf(true), preparer.forceDecodes)
        assertEquals(listOf(streamedSong.id), host.audioSourceCalls)
        assertEquals("VIDEO_A", engine.job(record.jobKey)!!.videoId)
        // The Spotify match moved to another video before the results came back.
        host.videoIds[streamedSong.id] = "VIDEO_B"
        runpod.completeAll()
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + PASS_MS)
        val job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.FAILED, job.state)
        assertTrue(job.lastError!!.contains("different YouTube video"))
        assertTrue(host.installed.isEmpty())
    }

    @Test fun `streamed songs and uploads wait for Wi-Fi unless mobile data is allowed`() = runBlocking {
        unmetered = false
        val engine = engine()
        val streamed = sendOne(engine, streamedSong)
        val local = sendOne(engine, localSong)
        val outcome = engine.workerPass(now + PASS_MS)
        assertTrue(preparer.forceDecodes == listOf(false), "only the local song was prepared")
        assertEquals(CloudJobState.QUEUED, engine.job(streamed.jobKey)!!.state)
        assertEquals(CloudJobState.UPLOADING, engine.job(local.jobKey)!!.state)
        assertTrue(outcome.waitingForUnmetered)
        assertEquals(1, scheduler.unmeteredRequests)
        assertEquals(CloudNotice.WAITING_FOR_WIFI, engine.state.value.notice)
        assertTrue(runpod.runs.isEmpty())
        // Allowing mobile data lets everything go.
        settings.snapshot = settings.snapshot.copy(useCellular = true)
        engine.workerPass(now + PASS_MS)
        assertEquals(2, runpod.runs.size)
    }

    @Test fun `the monthly cap stops submissions and the confirm sheet`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 1_000)
        val engine = engine()
        val preview = engine.preview(listOf(localSong), "x")
        assertFalse(preview.estimate.fitsCap)
        assertEquals(0, engine.send(preview))
        // A job already queued (the cap was lowered after Send) isn't submitted either.
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 3_000_000)
        val record = sendOne(engine)
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 10)
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.UPLOADED, engine.job(record.jobKey)!!.state)
        assertEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        assertTrue(runpod.runs.isEmpty())
    }

    @Test fun `a missing input is uploaded again, a poisoned job stops`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        runpod.statusFor = { id -> RunPodJob(id, "FAILED", error = "INPUT_MISSING: the input GET answered 404") }
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + 1) // deadline already reached: watch only
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.QUEUED, job.state)
        assertEquals("INPUT_MISSING", job.lastErrorCode)
        assertNotNull(job.nextAttemptAtMs)
        // Uploaded again and sent again; this time the worker refuses it as poisoned: no retry.
        runpod.statusFor = null
        now = job.nextAttemptAtMs!! + 1
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.RUNNING, engine.job(record.jobKey)!!.state)
        runpod.statusFor = { id -> RunPodJob(id, "FAILED", error = "POISONED: third delivery") }
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + 1)
        job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.FAILED, job.state)
        assertEquals("POISONED", job.lastErrorCode)
        assertFalse(bucket.objects.keys.any { it.contains(record.jobKey) })
    }

    @Test fun `a job RunPod lost goes out once more, then expires`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        runpod.statusFor = { throw RunPodError.JobNotFound() }
        now += CloudTiming.TTL_MS + CloudTiming.EXECUTION_TIMEOUT_MS + 1
        engine.workerPass(now + PASS_MS)
        var job = engine.job(record.jobKey)!!
        assertEquals(1, job.resubmits)
        // Its upload is now older than 3 days (the R2 lifecycle deletes inputs after 7), so it goes up again first.
        assertEquals(CloudJobState.QUEUED, job.state)
        engine.workerPass(now + PASS_MS)
        assertEquals(2, runpod.runs.size)
        assertTrue(engine.job(record.jobKey)!!.state.isAtRunPod)
        now += CloudTiming.TTL_MS + CloudTiming.EXECUTION_TIMEOUT_MS + 1
        engine.workerPass(now + 1)
        job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.EXPIRED, job.state)
    }

    @Test fun `an AAC result that doesn't line up is redone once as FLAC`() = runBlocking {
        runpod.samplesOffset = 5_000
        val engine = engine()
        val record = sendOne(engine)
        engine.workerPass(now + PASS_MS)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudOutputCodec.FLAC, job.outputCodec)
        assertTrue(job.flacRedone)
        assertEquals(CloudJobState.QUEUED, job.state)
        assertTrue(job.importedLyrics)
        assertEquals(listOf(CloudTask.INSTRUMENTAL), job.tasks)
        // The redo still doesn't line up: it stops instead of looping.
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + PASS_MS)
        job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.FAILED, job.state)
        assertEquals("flac", runpod.runs.last().input.output?.codec)
        assertTrue(host.installed.isEmpty())
        // The redo went up decoded (never an AAC passthrough again), so the phone's count is exact.
        assertEquals(listOf(false, true), preparer.forceDecodes)
    }

    @Test fun `cancel stops the job at RunPod and clears the bucket, and retry starts over`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        assertTrue(engine.job(record.jobKey)!!.state.isAtRunPod)
        engine.cancel(record.jobKey)
        assertEquals(CloudJobState.CANCELLED, engine.job(record.jobKey)!!.state)
        assertEquals(listOf(runpod.runIds.single()), runpod.cancelled)
        assertFalse(bucket.objects.keys.any { it.contains(record.jobKey) })
        engine.retry(record.jobKey)
        assertEquals(CloudJobState.QUEUED, engine.job(record.jobKey)!!.state)
        engine.remove(record.jobKey) // not finished: stays
        assertNotNull(engine.job(record.jobKey))
    }

    @Test fun `missing keys and incomplete fields block without looping`() = runBlocking {
        settings.secretsState = CloudSecretsState(CloudSecrets.EMPTY, keysMissing = true)
        val engine = engine()
        sendOne(engine)
        val before = scheduler.passRequests
        val outcome = engine.workerPass(now + PASS_MS)
        assertTrue(outcome.blocked)
        assertEquals(CloudNotice.KEYS_MISSING, engine.state.value.notice)
        assertEquals(before, scheduler.passRequests)
        settings.secretsState = CloudSecretsState(CloudSecrets("rpa", "", ""))
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudNotice.NOT_CONFIGURED, engine.state.value.notice)
    }

    @Test fun `previews skip what is done or pending and count the rest`() = runBlocking {
        host.lyrics[localSong.id] = CloudLyricsFacts(CloudLyricsState.WORD_SYNCED)
        host.instrumentals += localSong.id
        val engine = engine()
        val preview = engine.preview(CloudBatchKind.MISSING_LYRICS, null, "Missing lyrics")!!
        assertEquals(listOf(streamedSong.id), preview.plans.map { it.songId })
        assertNull(engine.preview(CloudBatchKind.CURRENT, null, "Current"))
        val done = engine.preview(listOf(localSong), "x")
        assertTrue(done.isEmpty)
        assertEquals(listOf(CloudSkipReason.ALREADY_DONE to 1), done.skipped)
        assertEquals("Cloud: 1 waiting", run { sendOne(engine, streamedSong); engine.summaryLine() })
    }

    // ─── Built-in keys ──────────────────────────────────────────────────────────────────────

    private val builtInConfig = CloudConfigInput(
        "builtinendpoint1", "rpa_builtin_dummy", "https://fedcba9876543210fedcba9876543210.r2.cloudflarestorage.com",
        "pixl-cloud-studio", "BUILTINAKID", "builtin-secret",
    )

    private class FixedBuiltIn(private val config: CloudConfigInput?) : CloudBuiltInConfig {
        override val isBundled = config != null
        override suspend fun load() = config
    }

    @Test fun `built-in keys carry the job without touching secure storage`() = runBlocking {
        builtIn = FixedBuiltIn(builtInConfig)
        // Nothing of the person's own: secure storage is even unavailable, which must not matter for built-in keys.
        settings.snapshot = CloudSettingsSnapshot(enabled = true, builtInAvailable = true, useOwnKeys = false)
        settings.secretsState = CloudSecretsState(CloudSecrets.EMPTY, storageUnavailable = true)
        val engine = engine()
        val record = sendOne(engine)
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state, "last error: ${engine.job(record.jobKey)!!.lastError}")
        assertEquals(setOf("builtinendpoint1"), configsSeen.map { it.trimmedEndpointId }.toSet())
    }

    @Test fun `the person's own keys override the built-in ones`() = runBlocking {
        builtIn = FixedBuiltIn(builtInConfig)
        settings.snapshot = settings.snapshot.copy(builtInAvailable = true, useOwnKeys = true)
        val engine = engine()
        val record = sendOne(engine)
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
        assertEquals(setOf("ep1"), configsSeen.map { it.trimmedEndpointId }.toSet())
    }

    @Test fun `built-in keys that did not open send nothing`() = runBlocking {
        builtIn = FixedBuiltIn(null)
        settings.snapshot = CloudSettingsSnapshot(enabled = true, builtInAvailable = true, useOwnKeys = false)
        val engine = engine()
        val record = sendOne(engine)
        val outcome = engine.workerPass(now + PASS_MS)
        assertTrue(outcome.blocked)
        assertEquals(CloudNotice.NOT_CONFIGURED, engine.state.value.notice)
        assertTrue(runpod.runs.isEmpty())
        assertEquals(CloudJobState.QUEUED, engine.job(record.jobKey)!!.state)
    }

    @Test fun `with built-in keys the cap is clamped to 3 dollars and the price has a floor`() = runBlocking {
        builtIn = FixedBuiltIn(builtInConfig)
        val engine = engine()
        settings.snapshot = settings.snapshot.copy(builtInAvailable = true, useOwnKeys = false,
            monthlyCapMicroUsd = 100_000_000, pricePerSecondMicroUsd = 1)
        val withBuiltIn = engine.preview(listOf(localSong), "x").estimate
        assertEquals(CloudKeyChoice.BUILT_IN_MONTHLY_CAP_MICRO_USD, withBuiltIn.capMicroUsd)
        // The same song at the endpoint's own price: a typed-in 1 µ$/s changes nothing.
        settings.snapshot = settings.snapshot.copy(useOwnKeys = true, pricePerSecondMicroUsd = CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD)
        val atFloor = engine.preview(listOf(localSong), "x").estimate
        assertEquals(atFloor.costMicroUsd, withBuiltIn.costMicroUsd)
        // Own keys keep the person's own cap.
        assertEquals(100_000_000, atFloor.capMicroUsd)
    }

    @Test fun `jobs taken off the list still count against the month`() = runBlocking {
        val spent = CloudJobRecord(jobKey = keys.removeFirst(), songId = "x", state = CloudJobState.IMPORTED, createdAtMs = now - 2,
            importedAtMs = now - 1, completedAtMs = now - 1, costMicroUsd = 2_500_000)
        store.saved = listOf(spent)
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 3_000_000)
        val engine = engine()
        engine.loadForDisplay()
        assertEquals(2_500_000, engine.committedThisMonthMicroUsd())
        engine.clearFinished()
        assertTrue(engine.state.value.jobs.isEmpty())
        // Clearing the list freed no room: still $2.50 used, so a batch that costs more than the 50 cents left is refused.
        assertEquals(2_500_000, engine.committedThisMonthMicroUsd())
        val preview = engine.preview(listOf(localSong), "x")
        assertEquals(500_000, preview.estimate.remainingMicroUsd)
        // Removing one finished job by hand is remembered the same way.
        val another = spent.copy(jobKey = keys.removeFirst(), songId = "y")
        store.saved = listOf(another)
        val second = engine()
        second.loadForDisplay()
        second.remove(another.jobKey)
        assertEquals(5_000_000, second.committedThisMonthMicroUsd())
    }

    // ─── Sing ───────────────────────────────────────────────────────────────────────────────

    private val fourSongs = listOf("a", "b", "c", "d").map { CloudSong(it, "Song $it", "Artist", "Album", 240_000, false, true) }

    private fun fourSongEngine(): CloudStudioEngine {
        host = FakeHost(fourSongs)
        return engine()
    }

    private suspend fun sing(engine: CloudStudioEngine, song: CloudSong): CloudSingStart.Started =
        engine.startSing(song) as CloudSingStart.Started

    /** The answer to a Sing tap with the settings changed by [change] (on top of a working setup) and nothing queued. */
    private suspend fun singAnswer(
        song: CloudSong = localSong,
        change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot = { it },
    ): CloudSingStart {
        settings.snapshot = change(CloudSettingsSnapshot(enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef"))
        store.saved = emptyList()
        return engine().startSing(song)
    }

    @Test fun `a Sing asks for the instrumental even when Outputs has it off, and the cloud makes it`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(wantsInstrumental = false)
        val engine = engine()
        assertNull(engine.singAvailability(localSong.durationMs), "usable")
        val job = engine.job(sing(engine, localSong).jobKey)!!
        assertEquals(CloudJobOrigin.SING, job.origin)
        assertTrue(job.isPriority)
        // The lyrics follow Outputs (on); the instrumental is forced on.
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), job.tasks)
        assertEquals(1, scheduler.urgentRequests, "the phone is asked to start right away")
        assertTrue(engine.hasPriorityPhoneWork())
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        assertFalse(engine.hasPriorityPhoneWork())
        assertTrue(engine.isWaitingOnSing(), "at RunPod now: the worker keeps looking every few seconds")
        runpod.completeAll()
        now += CloudTiming.SING_POLL_INTERVAL_MS + 1
        engine.workerPass(now + PASS_MS)
        val done = engine.job(job.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertEquals(listOf(localSong.id), host.instrumentalNotes)
        assertFalse(engine.isWaitingOnSing())
    }

    @Test fun `a second Sing tap joins the job already on its way`() = runBlocking {
        val engine = engine()
        val first = sing(engine, localSong)
        assertEquals(CloudSingStart.Joined(first.jobKey), engine.startSing(localSong))
        assertEquals(1, engine.state.value.jobs.size)
        assertEquals(CloudJobOrigin.SING, engine.job(first.jobKey)!!.origin)
        assertEquals(2, scheduler.urgentRequests)
    }

    @Test fun `a Sing promotes an automatic job that is already waiting`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        val engine = engine()
        assertEquals(1, engine.sendAuto(listOf(localSong)))
        val auto = engine.state.value.jobs.single()
        assertEquals(CloudJobOrigin.AUTO, auto.origin)
        assertFalse(auto.isPriority)
        assertEquals(CloudSingStart.Joined(auto.jobKey), engine.startSing(localSong))
        val promoted = engine.job(auto.jobKey)!!
        assertEquals(CloudJobOrigin.SING, promoted.origin)
        assertTrue(promoted.isPriority)
        // Alone in its batch now, so the batch gate doesn't wait for the automatic jobs it came with.
        assertNotEquals(auto.batchId, promoted.batchId)
        assertEquals(1, engine.state.value.jobs.size)
    }

    @Test fun `an automatic job that had stepped aside for a Sing goes ahead as a Sing itself`() = runBlocking {
        host = FakeHost(fourSongs)
        val (p, x) = listOf(keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            pausedJob(x, fourSongs[0], waitingFor = p).copy(origin = CloudJobOrigin.AUTO),
            storedJob(p, fourSongs[3], CloudJobState.QUEUED, origin = CloudJobOrigin.SING, priority = true),
        )
        val engine = engine()
        engine.loadForDisplay()
        assertTrue(engine.job(x)!!.isPaused)
        assertEquals(CloudSingStart.Joined(x), engine.startSing(fourSongs[0]))
        val promoted = engine.job(x)!!
        assertEquals(CloudJobOrigin.SING, promoted.origin)
        assertTrue(promoted.isPriority)
        // It is a priority job now, not one still waiting behind the other Sing; it keeps its upload and its history.
        assertFalse(promoted.isPaused)
        assertNull(promoted.pausedByJobKey)
        assertEquals(CloudJobState.UPLOADED, promoted.state)
        assertNotNull(promoted.submittedAtMs)
        assertFalse(CloudPriorityLane.isHeld(promoted, engine.state.value.jobs, emptySet(), now))
    }

    @Test fun `a Sing says why the cloud can't take it, and the on-device render takes over`() = runBlocking {
        fun unavailable(reason: CloudSingUnavailable) = CloudSingStart.Unavailable(reason)
        assertEquals(unavailable(CloudSingUnavailable.OFF), singAnswer { it.copy(enabled = false) })
        assertEquals(unavailable(CloudSingUnavailable.NOT_CONFIGURED), singAnswer { it.copy(endpointId = "") })
        assertEquals(unavailable(CloudSingUnavailable.CAP_REACHED), singAnswer { it.copy(monthlyCapMicroUsd = 1_000) })
        val long = CloudSong("long", "Long", "Artist", "Album", 960_000, isStreamed = false, hasAudioSource = true)
        assertEquals(unavailable(CloudSingUnavailable.TOO_LONG), singAnswer(long))
        val silent = CloudSong("silent", "Silent", "Artist", "Album", 240_000, isStreamed = false, hasAudioSource = false)
        assertEquals(unavailable(CloudSingUnavailable.NO_AUDIO), singAnswer(silent))
        settings.secretsState = CloudSecretsState(CloudSecrets.EMPTY, keysMissing = true)
        assertEquals(unavailable(CloudSingUnavailable.KEYS_MISSING), singAnswer())
        settings.secretsState = CloudSecretsState(CloudSecrets("rpa_key", "AKID", "SECRET"))
        host.instrumentals += localSong.id
        assertEquals(unavailable(CloudSingUnavailable.ALREADY_HAS_INSTRUMENTAL), singAnswer())
        host.instrumentals -= localSong.id
        // On mobile data with "Use mobile data" off the answer is a reason, not a job waiting for Wi-Fi.
        unmetered = false
        assertEquals(unavailable(CloudSingUnavailable.NOT_ON_WIFI), singAnswer())
        assertEquals(CloudSingUnavailable.NOT_ON_WIFI, engine().singAvailability(240_000))
        assertTrue(singAnswer { it.copy(useCellular = true) } is CloudSingStart.Started)
    }

    @Test fun `a refused key makes the cloud unavailable for a Sing`() = runBlocking {
        val engine = engine()
        sendOne(engine)
        runpod.behaviour = { _, _ -> throw RunPodError.Unauthorized(401) }
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudNotice.RUNPOD_KEY_REFUSED, engine.state.value.notice)
        assertEquals(CloudSingUnavailable.KEYS_MISSING, engine.singAvailability(240_000))
    }

    @Test fun `a Sing is prepared and uploaded before a batch queued earlier, and goes to RunPod first`() = runBlocking {
        val engine = fourSongEngine()
        assertEquals(3, engine.send(engine.preview(fourSongs.take(3), "Three")))
        val sing = sing(engine, fourSongs[3])
        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf("d", "a", "b", "c"), host.audioSourceCalls)
        assertEquals(listOf(sing.jobKey), runpod.runs.take(1).map { it.input.jobKey })
        assertEquals(CloudJobInputPolicy(lastInBatch = true), runpod.runs.first().input.policy, "alone in its burst, so it is the last")
    }

    @Test fun `a one-song Process in cloud goes ahead of a batch the same way, with an urgent pass`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        assertEquals(0, scheduler.urgentRequests, "a batch asks for a plain pass")
        val single = sendOne(engine, fourSongs[3])
        assertEquals(CloudJobOrigin.MANUAL, single.origin)
        assertTrue(single.isPriority)
        assertEquals(1, scheduler.urgentRequests)
        now += 1_000
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf("rp-3", "rp-2"), runpod.cancelled)
        assertEquals(listOf(single.jobKey, b, c), runpod.runs.drop(3).map { it.input.jobKey })
    }

    @Test fun `jobsFor lists a song's jobs under either of its ids`() = runBlocking {
        val engine = engine()
        engine.loadForDisplay()
        sendOne(engine)
        sendOne(engine, streamedSong)
        assertEquals(listOf("101"), engine.jobsFor("101").first().map { it.songId })
        assertEquals(listOf("spotify_x"), engine.jobsFor("spotify_x").first().map { it.songId })
        // The unified library row of a Spotify song is the same song as its spotify_ alias.
        assertEquals(listOf("spotify_x"), engine.jobsFor(TaisInstrumentalIndex.canonicalSongId("spotify_x")).first().map { it.songId })
        assertTrue(engine.jobsFor("nothing").first().isEmpty())
    }

    // ─── Polling a Sing ─────────────────────────────────────────────────────────────────────

    @Test fun `a Sing at RunPod is asked about every few seconds`() = runBlocking {
        runpod.finishImmediately = false
        runpod.statusFor = { id -> RunPodJob(id, "IN_QUEUE") }
        val engine = engine()
        val started = sing(engine, localSong)
        engine.workerPass(now + PASS_MS)
        val id = engine.job(started.jobKey)!!.runpodJobId!!
        assertEquals(1, runpod.statusCalls.count { it == id }, "once, when it went out")
        now += 1_000
        engine.workerPass(now + 1)
        assertEquals(1, runpod.statusCalls.count { it == id }, "one second later is too soon")
        now += CloudTiming.SING_POLL_INTERVAL_MS
        engine.workerPass(now + 1)
        assertEquals(2, runpod.statusCalls.count { it == id }, "a few seconds later it is asked again, not 15")
    }

    @Test fun `a batch job is asked about no more often than every 15 seconds`() = runBlocking {
        host = FakeHost(threeSongs)
        runpod.finishImmediately = false
        runpod.statusFor = { id -> RunPodJob(id, "IN_QUEUE") }
        val engine = engine()
        assertEquals(3, engine.send(engine.preview(threeSongs, "Three")))
        engine.workerPass(now + PASS_MS)
        assertEquals(1, runpod.statusCalls.count { it == "rp-1" })
        now += CloudTiming.SING_POLL_INTERVAL_MS + 1
        engine.workerPass(now + 1)
        assertEquals(1, runpod.statusCalls.count { it == "rp-1" }, "a Sing's rhythm is not the batch's")
        now += CloudTiming.STATUS_POLL_INTERVAL_MS
        engine.workerPass(now + 1)
        assertEquals(2, runpod.statusCalls.count { it == "rp-1" })
    }

    // ─── The priority lane ──────────────────────────────────────────────────────────────────

    /** Three batch jobs at RunPod: the first is running, the other two still wait in RunPod's queue. Returns their keys. */
    private suspend fun threeAtRunPod(engine: CloudStudioEngine): List<String> {
        runpod.finishImmediately = false
        runpod.statusFor = { id ->
            if (id == "rp-1") RunPodJob(id, "IN_PROGRESS", outputText = "separate:40") else RunPodJob(id, "IN_QUEUE")
        }
        assertEquals(3, engine.send(engine.preview(fourSongs.take(3), "Three")))
        val keys = engine.state.value.jobs.map { it.jobKey }
        engine.workerPass(now + PASS_MS)
        assertEquals(
            listOf(CloudJobState.RUNNING, CloudJobState.SUBMITTED, CloudJobState.SUBMITTED),
            keys.map { engine.job(it)!!.state },
        )
        assertEquals(listOf("rp-1", "rp-2", "rp-3"), keys.map { engine.job(it)!!.runpodJobId })
        return keys
    }

    @Test fun `a Sing steps the queued jobs aside, goes out first, and they follow in the same burst`() = runBlocking {
        val engine = fourSongEngine()
        val (a, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        now += 1_000
        engine.workerPass(now + PASS_MS)
        // Only the two jobs still in RunPod's queue were cancelled, the tail first; the running one was left alone.
        assertEquals(listOf("rp-3", "rp-2"), runpod.cancelled)
        assertEquals(CloudJobState.RUNNING, engine.job(a)!!.state)
        assertEquals("rp-1", engine.job(a)!!.runpodJobId)
        // One burst: the Sing, then the jobs that stepped aside in their old order; only the final one asks the worker to stop.
        val burst = runpod.runs.drop(3)
        assertEquals(listOf(sing.jobKey, b, c), burst.map { it.input.jobKey })
        assertEquals(listOf(null, null, CloudJobInputPolicy(lastInBatch = true)), burst.map { it.input.policy })
        // Back at RunPod behind the Sing, no mark left of having stepped aside, and no attempt used up.
        listOf(b, c).forEach {
            val job = engine.job(it)!!
            assertTrue(job.state.isAtRunPod, "state ${job.state}")
            assertFalse(job.isPaused)
            assertNull(job.pauseIntentRunpodJobId)
            assertEquals(0, job.attempts)
            assertNull(job.lastError)
        }
        // They never began, so the stale-object clean-up (two deletes each) was skipped.
        assertTrue(events.none { it == "DELETE ${CloudKeys.manifest(b)}" || it == "DELETE ${CloudKeys.manifest(c)}" }, "events: $events")
        // The cancels came before the Sing went out.
        assertTrue(events.indexOf("CANCEL rp-2") < events.lastIndexOf("RUN"), "events: $events")
    }

    @Test fun `a job that began meanwhile is left running, not cancelled`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        // The second job reached the GPU between the last look and the Sing being ready.
        runpod.statusFor = { id ->
            when (id) {
                "rp-1", "rp-2" -> RunPodJob(id, "IN_PROGRESS", outputText = "separate:40")
                else -> RunPodJob(id, "IN_QUEUE")
            }
        }
        now += 1_000
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf("rp-3"), runpod.cancelled)
        assertEquals(CloudJobState.RUNNING, engine.job(b)!!.state)
        assertNull(engine.job(b)!!.pauseIntentRunpodJobId)
        val burst = runpod.runs.drop(3)
        assertEquals(listOf(sing.jobKey, c), burst.map { it.input.jobKey })
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), burst.map { it.input.policy })
    }

    @Test fun `a cancel that did not take the job out of the queue pauses nothing`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        // RunPod answers that the last job is already in progress: too late.
        runpod.cancelResult = { id -> if (id == "rp-3") RunPodJob(id, "IN_PROGRESS") else RunPodJob(id, "CANCELLED") }
        now += 1_000
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.SUBMITTED, engine.job(c)!!.state)
        assertEquals("rp-3", engine.job(c)!!.runpodJobId)
        assertFalse(engine.job(c)!!.isPaused)
        assertEquals(listOf(sing.jobKey, b), runpod.runs.drop(3).map { it.input.jobKey })
    }

    @Test fun `a cancel that fails leaves the job queued with its intent saved, and the Sing still goes out`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        runpod.cancelResult = { throw RunPodError.Network("connection reset") }
        now += 1_000
        engine.workerPass(now + PASS_MS)
        listOf(b to "rp-2", c to "rp-3").forEach { (key, runId) ->
            val job = engine.job(key)!!
            assertEquals(CloudJobState.SUBMITTED, job.state)
            assertEquals(runId, job.runpodJobId)
            assertEquals(runId, job.pauseIntentRunpodJobId, "the intent stays, so a CANCELLED that did go through reads as a pause")
            assertEquals(runId, store.saved.first { it.jobKey == key }.pauseIntentRunpodJobId, "and it is on disk")
            assertEquals(0, job.attempts)
        }
        val burst = runpod.runs.drop(3)
        assertEquals(listOf(sing.jobKey), burst.map { it.input.jobKey })
        assertEquals(listOf(CloudJobInputPolicy(lastInBatch = true)), burst.map { it.input.policy })
    }

    @Test fun `a CANCELLED seen for a job with a pause intent finishes the pause, it is not a failure`() = runBlocking {
        val engine = fourSongEngine()
        val (_, _, c) = threeAtRunPod(engine)
        // The phone began pausing the last job and was stopped before it heard RunPod's answer.
        store.saved = store.saved.map { if (it.jobKey == c) it.beginningPause() else it }
        runpod.statusFor = { id ->
            when (id) {
                "rp-1" -> RunPodJob(id, "IN_PROGRESS", outputText = "separate:40")
                "rp-3" -> RunPodJob(id, "CANCELLED")
                else -> RunPodJob(id, "IN_QUEUE")
            }
        }
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        val restarted = engine()
        restarted.workerPass(now + PASS_MS)
        val job = restarted.job(c)!!
        assertEquals(0, job.attempts, "a pause costs no attempt")
        assertNull(job.lastError)
        assertNull(job.nextAttemptAtMs, "and no backoff wait")
        // Nobody is waiting for it to step aside any more, so it goes out again as a fresh run of the same job.
        assertEquals("rp-4", job.runpodJobId)
        assertTrue(job.state.isAtRunPod)
        assertEquals(2, runpod.runs.count { it.input.jobKey == c })
    }

    @Test fun `when the month only has room for the Sing, it goes out as the last and the others follow later`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        // Room for two songs (the one running and the Sing), not for the two that stepped aside.
        val one = CloudCost.estimatedSeconds(engine.job(b)!!.plan, engine.job(b)!!.quality) * settings.snapshot.pricePerSecondMicroUsd
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 2 * one)
        now += 1_000
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf("rp-3", "rp-2"), runpod.cancelled)
        assertEquals(listOf(sing.jobKey), runpod.runs.drop(3).map { it.input.jobKey })
        assertEquals(listOf(CloudJobInputPolicy(lastInBatch = true)), runpod.runs.drop(3).map { it.input.policy })
        assertEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        listOf(b, c).forEach { assertEquals(CloudJobState.UPLOADED, engine.job(it)!!.state) }
        // Room again (a raised cap): they go out together, and only the last of them asks the worker to stop.
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 3_000_000)
        now += 1_000
        engine.workerPass(now + PASS_MS)
        val second = runpod.runs.drop(4)
        assertEquals(listOf(b, c), second.map { it.input.jobKey })
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), second.map { it.input.policy })
        assertTrue(events.none { it == "DELETE ${CloudKeys.manifest(b)}" }, "they never began: nothing stale to clear")
    }

    @Test fun `a job that finished before its cancel landed is imported, not sent again`() = runBlocking {
        val engine = fourSongEngine()
        val (_, b, c) = threeAtRunPod(engine)
        val sing = sing(engine, fourSongs[3])
        // The GPU got to the second job just before RunPod's cancel: it finished and wrote its results.
        runpod.cancelResult = { id ->
            if (id == "rp-2") runpod.complete(runpod.runs[1])
            RunPodJob(id, "CANCELLED")
        }
        now += 1_000
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf("rp-3", "rp-2"), runpod.cancelled)
        assertEquals(CloudJobState.IMPORTED, engine.job(b)!!.state, "last error: ${engine.job(b)!!.lastError}")
        assertEquals(listOf(sing.jobKey, c), runpod.runs.drop(3).map { it.input.jobKey })
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.drop(3).map { it.input.policy })
        assertTrue("b" in host.instrumentalNotes)
    }

    /** A job as an earlier run left it on disk: its upload is in the bucket, the fields a send needs are filled. */
    private fun storedJob(
        key: String, song: CloudSong, state: CloudJobState, origin: CloudJobOrigin = CloudJobOrigin.MANUAL,
        batch: String = key, runpodId: String? = null, submittedAt: Long? = null, priority: Boolean = false,
    ) = CloudJobRecord(
        jobKey = key, songId = song.id, title = song.title, artist = song.artist, songDurationMs = song.durationMs,
        batchId = batch, tasks = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), lyricsMode = CloudLyricsMode.ALIGN,
        origin = origin, priorityAtMs = now.takeIf { priority }, state = state, runpodJobId = runpodId,
        inputExt = "flac", sha256 = preparer.sha, bytes = 1_000, durationMs = song.durationMs,
        decodedFrames = preparer.frames, sampleRate = 44_100, createdAtMs = now - 10_000,
        uploadedAtMs = now.takeIf { state != CloudJobState.QUEUED }, submittedAtMs = submittedAt,
    )

    private fun pausedJob(key: String, song: CloudSong, waitingFor: String, pausedAt: Long = now) =
        storedJob(key, song, CloudJobState.UPLOADED, batch = "stepped-aside", submittedAt = now - 5_000)
            .copy(pausedByJobKey = waitingFor, pausedAtMs = pausedAt, pausedNeverStarted = true)

    @Test fun `jobs that stepped aside wait for the Sing to be uploaded, then go out behind it in one burst`() = runBlocking {
        host = FakeHost(fourSongs)
        val (p, b, c) = listOf(keys.removeFirst(), keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            pausedJob(b, fourSongs[1], waitingFor = p), pausedJob(c, fourSongs[2], waitingFor = p),
            storedJob(p, fourSongs[3], CloudJobState.QUEUED, origin = CloudJobOrigin.SING, priority = true),
        )
        runpod.finishImmediately = false
        engine().workerPass(now + PASS_MS)
        assertEquals(listOf(p, b, c), runpod.runs.map { it.input.jobKey })
        assertEquals(listOf(null, null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.map { it.input.policy })
        assertTrue(events.none { it.startsWith("DELETE") && (b in it || c in it) }, "events: $events")
    }

    @Test fun `the hold ends by itself, so a Sing stuck on Wi-Fi never freezes the queue`() = runBlocking {
        host = FakeHost(fourSongs)
        unmetered = false // the Sing is prepared but its upload waits for Wi-Fi
        val (p, b) = listOf(keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            pausedJob(b, fourSongs[1], waitingFor = p, pausedAt = now - CloudTiming.PRIORITY_HOLD_MAX_MS - 1),
            storedJob(p, fourSongs[3], CloudJobState.QUEUED, origin = CloudJobOrigin.SING, priority = true),
        )
        runpod.finishImmediately = false
        val engine = engine()
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf(b), runpod.runs.map { it.input.jobKey })
        assertFalse(engine.job(b)!!.isPaused)
        assertEquals(CloudJobState.UPLOADING, engine.job(p)!!.state, "the Sing is still waiting for Wi-Fi")
    }

    @Test fun `cancelling the Sing lets the jobs that waited for it go`() = runBlocking {
        host = FakeHost(fourSongs)
        val (p, b, c) = listOf(keys.removeFirst(), keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            pausedJob(b, fourSongs[1], waitingFor = p), pausedJob(c, fourSongs[2], waitingFor = p),
            storedJob(p, fourSongs[3], CloudJobState.QUEUED, origin = CloudJobOrigin.SING, priority = true),
        )
        runpod.finishImmediately = false
        val engine = engine()
        engine.loadForDisplay()
        assertTrue(engine.job(b)!!.isPaused)
        val before = scheduler.passRequests
        engine.cancel(p)
        assertEquals(CloudJobState.CANCELLED, engine.job(p)!!.state)
        assertFalse(engine.job(b)!!.isPaused)
        assertFalse(engine.job(c)!!.isPaused)
        assertTrue(scheduler.passRequests > before, "a pass is asked for")
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf(b, c), runpod.runs.map { it.input.jobKey })
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.map { it.input.policy })
    }

    @Test fun `a Sing that failed lets the jobs that waited for it go`() = runBlocking {
        host = FakeHost(fourSongs)
        val (p, b, c) = listOf(keys.removeFirst(), keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            pausedJob(b, fourSongs[1], waitingFor = p), pausedJob(c, fourSongs[2], waitingFor = p),
            storedJob(p, fourSongs[3], CloudJobState.FAILED, origin = CloudJobOrigin.SING, priority = true),
        )
        runpod.finishImmediately = false
        engine().workerPass(now + PASS_MS)
        assertEquals(listOf(b, c), runpod.runs.map { it.input.jobKey })
    }

    // ─── Smart auto-prepare ─────────────────────────────────────────────────────────────────

    @Test fun `an automatic job waits for a Sing that is still on the phone, then goes out behind it`() = runBlocking {
        host = FakeHost(fourSongs)
        val (x, p) = listOf(keys.removeFirst(), keys.removeFirst())
        store.saved = listOf(
            storedJob(x, fourSongs[0], CloudJobState.UPLOADED, origin = CloudJobOrigin.AUTO),
            storedJob(p, fourSongs[3], CloudJobState.QUEUED, origin = CloudJobOrigin.SING, priority = true),
        )
        runpod.finishImmediately = false
        engine().workerPass(now + PASS_MS)
        assertEquals(listOf(p, x), runpod.runs.map { it.input.jobKey })
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.map { it.input.policy })
    }

    @Test fun `without a Sing waiting, an uploaded automatic job goes out at once`() = runBlocking {
        host = FakeHost(fourSongs)
        val x = keys.removeFirst()
        store.saved = listOf(storedJob(x, fourSongs[0], CloudJobState.UPLOADED, origin = CloudJobOrigin.AUTO))
        runpod.finishImmediately = false
        engine().workerPass(now + PASS_MS)
        assertEquals(listOf(x), runpod.runs.map { it.input.jobKey })
    }

    @Test fun `an automatic job only prepares audio already on the phone, and quietly drops when it is gone`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        host.cachedIds += localSong.id
        val engine = engine()
        assertEquals(1, engine.sendAuto(listOf(localSong)))
        val job = engine.state.value.jobs.single()
        assertEquals(CloudJobOrigin.AUTO, job.origin)
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), job.tasks)
        // The audio was evicted before the job's turn: nothing is downloaded for it, and the job just goes away.
        host.cachedIds.clear()
        engine.workerPass(now + PASS_MS)
        assertTrue(engine.state.value.jobs.isEmpty())
        assertTrue(host.audioSourceCalls.isEmpty(), "never the downloading source")
        assertTrue(preparer.forceDecodes.isEmpty())
        assertTrue(runpod.runs.isEmpty())
    }

    @Test fun `an automatic job goes through on a charger and Wi-Fi, and waits when the phone isn't ready`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        host.cachedIds += localSong.id
        val engine = engine()
        assertEquals(1, engine.sendAuto(listOf(localSong)))
        val key = engine.state.value.jobs.single().jobKey
        autoWork = false
        val waiting = engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.QUEUED, engine.job(key)!!.state)
        assertTrue(preparer.forceDecodes.isEmpty(), "nothing is prepared on battery")
        assertFalse(waiting.workBeforeSubmit, "and no pass is asked for in a loop")
        assertEquals(0, scheduler.passRequests)
        autoWork = true
        engine.workerPass(now + PASS_MS)
        val done = engine.job(key)!!
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertEquals(listOf(localSong.id), host.instrumentalNotes)
        assertTrue(host.audioSourceCalls.isEmpty())
    }

    @Test fun `automatic jobs stop at the day's cap, at their own budget and at the shared cap`() = runBlocking {
        host = FakeHost(fourSongs)
        fun fresh(change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot): CloudStudioEngine {
            store.saved = emptyList()
            settings.autoDays.clear()
            settings.snapshot = change(CloudSettingsSnapshot(enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef"))
            return engine()
        }
        // The switch is off: nothing is made.
        assertEquals(0, fresh { it }.sendAuto(fourSongs))
        // Two songs a day, and the day's count is kept apart from the list.
        val capped = fresh { it.copy(autoPrepareEnabled = true, autoPrepareDailyCap = 2) }
        assertEquals(2, capped.sendAuto(fourSongs))
        assertEquals(0, capped.sendAuto(fourSongs), "the day is full")
        now += 86_400_000
        assertEquals(2, capped.sendAuto(fourSongs), "a new day starts the count again")
        // Their own share of the month ($0.005) is smaller than one song.
        assertEquals(0, fresh { it.copy(autoPrepareEnabled = true, autoPrepareMonthlyBudgetMicroUsd = 5_000) }.sendAuto(fourSongs))
        // Room for one song (a cold start and 38 s at 192 µ$ a second) but not for two: as many as fit are made.
        val oneSong = CloudCost.estimateMicroUsd(
            listOf(CloudSongPlan("a", listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), CloudLyricsMode.ALIGN, false, 240_000)),
            CloudSeparationQuality.STANDARD, 192,
        )
        assertEquals(1, fresh { it.copy(autoPrepareEnabled = true, autoPrepareMonthlyBudgetMicroUsd = oneSong) }.sendAuto(fourSongs))
        // The shared cap has less room than one song, though their own share has plenty.
        assertEquals(0, fresh { it.copy(autoPrepareEnabled = true, monthlyCapMicroUsd = 5_000) }.sendAuto(fourSongs))
        // Cloud processing itself is off.
        assertEquals(0, fresh { it.copy(autoPrepareEnabled = true, enabled = false) }.sendAuto(fourSongs))
    }

    @Test fun `clearing finished jobs gives back neither the day's count nor the month's automatic spend`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        host.cachedIds += localSong.id
        val engine = engine()
        assertEquals(1, engine.sendAuto(listOf(localSong)))
        engine.workerPass(now + PASS_MS)
        assertEquals(CloudJobState.IMPORTED, engine.state.value.jobs.single().state)
        val spent = engine.autoCommittedThisMonthMicroUsd()
        assertTrue(spent > 0)
        engine.clearFinished()
        assertTrue(engine.state.value.jobs.isEmpty())
        assertEquals(spent, engine.autoCommittedThisMonthMicroUsd())
        assertEquals(1, settings.autoDayCount(CloudBudget.dayStartMs(now)))
    }

    @Test fun `turning the switch off removes the automatic jobs still on the phone and leaves the person's own`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        host.cachedIds += localSong.id
        val engine = engine()
        assertEquals(1, engine.sendAuto(listOf(localSong)))
        val manual = sendOne(engine, streamedSong)
        engine.cancelPendingAuto()
        assertEquals(listOf(manual.jobKey), engine.state.value.jobs.map { it.jobKey })
    }

    @Test fun `the on-device studio leaves a song to the cloud only while the switch is on and the cloud is usable`() = runBlocking {
        val engine = engine()
        assertFalse(engine.cloudAutoPrepareOwns(240_000), "the switch is off")
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        assertTrue(engine.cloudAutoPrepareOwns(240_000))
        assertTrue(engine.autoPrepareUsable())
        assertFalse(engine.cloudAutoPrepareOwns(30_000), "too short for the cloud's picks")
        assertFalse(engine.cloudAutoPrepareOwns(9 * 60_000), "too long for them")
        settings.snapshot = settings.snapshot.copy(endpointId = "")
        assertFalse(engine.cloudAutoPrepareOwns(240_000), "the cloud isn't set up, so nothing else would prepare it")
        settings.snapshot = settings.snapshot.copy(endpointId = "ep1", enabled = false)
        assertFalse(engine.cloudAutoPrepareOwns(240_000))
        // Being off Wi-Fi right now is no reason for the on-device studio to step in.
        unmetered = false
        settings.snapshot = settings.snapshot.copy(enabled = true)
        assertTrue(engine.cloudAutoPrepareOwns(240_000))
    }

    @Test fun `the person has work in the cloud only when something other than an automatic job is pending`() = runBlocking {
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true)
        host.cachedIds += localSong.id
        val engine = engine()
        assertFalse(engine.hasWorkForThePerson())
        engine.sendAuto(listOf(localSong))
        assertFalse(engine.hasWorkForThePerson(), "automatic jobs post no notification")
        sendOne(engine, streamedSong)
        assertTrue(engine.hasWorkForThePerson())
    }

    // ─── Sing preview clip ──────────────────────────────────────────────────────────────────

    @Test fun `a Sing with a playhead sends a preview clip ahead of the song, and the song carries the one stop flag`() = runBlocking {
        val engine = engine()
        val started = engine.startSing(localSong, playheadMs = 60_000) as CloudSingStart.Started
        val full = engine.job(started.jobKey)!!
        val clip = engine.state.value.jobs.single { it.isClip }
        // The minute from the playhead (3 s before it), the instrumental only, a Sing of its own batch.
        assertEquals(57_000L to 120_000L, clip.clipStartMs to clip.clipEndMs)
        assertEquals(started.jobKey, clip.clipOfJobKey)
        assertEquals(listOf(CloudTask.INSTRUMENTAL), clip.tasks)
        assertNull(clip.lyricsMode)
        assertEquals(CloudSeparationQuality.STANDARD, clip.quality)
        assertEquals(CloudOutputCodec.FLAC, clip.outputCodec)
        assertEquals(CloudJobOrigin.SING, clip.origin)
        assertTrue(clip.isPriority)
        assertNotEquals(full.batchId, clip.batchId)
        // One Sing on the queue's lists, not two rows; the song still has its pending job.
        assertEquals(listOf(started.jobKey), engine.state.value.activeJobs.map { it.jobKey })
        assertTrue(engine.hasPendingJob(localSong.id))
        assertEquals("Cloud: 1 waiting", engine.summaryLine())

        runpod.finishImmediately = false
        engine.workerPass(now + PASS_MS)
        assertEquals(listOf(57_000L to 120_000L), preparer.clipRanges)
        assertEquals(listOf(clip.jobKey, started.jobKey), runpod.runs.map { it.input.jobKey }, "the clip first, the song right behind")
        // The clip leaves the worker up for the song coming behind it; the song is the last.
        assertEquals(listOf(null, CloudJobInputPolicy(lastInBatch = true)), runpod.runs.map { it.input.policy })
        assertEquals(listOf("instrumental"), runpod.runs.first().input.tasks)
        assertEquals(CloudOutputCodec.FLAC.wire, runpod.runs.first().input.output?.codec)
    }

    @Test fun `a preview clip is kept apart, never counts as the song's instrumental, and goes with the song's import`() = runBlocking {
        val engine = engine()
        val started = engine.startSing(localSong, playheadMs = 60_000) as CloudSingStart.Started
        val clipKey = engine.state.value.jobs.single { it.isClip }.jobKey
        engine.workerPass(now + PASS_MS)
        val clip = engine.job(clipKey)!!
        assertEquals(CloudJobState.IMPORTED, clip.state, "last error: ${clip.lastError}")
        // Its length check used the clip's own decode (63 s), not the song's.
        assertEquals(63_000L * 44_100 / 1000, clip.decodedFrames)
        assertEquals(listOf(Triple(localSong.id, 57_000L, true)), host.installedClips)
        assertTrue(host.installed.isEmpty(), "the instrumental store is untouched")
        assertTrue(host.instrumentalNotes.isEmpty())
        assertTrue(engine.hasPendingJob(localSong.id))
        assertTrue(engine.state.value.finishedJobs.isEmpty(), "a finished clip is not a row either")

        now += CloudTiming.SING_POLL_INTERVAL_MS + 1
        engine.workerPass(now + PASS_MS)
        val done = engine.job(started.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertEquals(listOf(localSong.id), host.instrumentalNotes)
        assertEquals(listOf(localSong.id), host.discardedClips, "the song's own instrumental is in: its clips go")
        assertEquals(listOf(started.jobKey), engine.state.value.finishedJobs.map { it.jobKey })
    }

    @Test fun `a joined Sing gets a clip while the song's job hasn't begun, and only one`() = runBlocking {
        val engine = engine()
        val first = sing(engine, localSong)
        assertTrue(engine.state.value.jobs.none { it.isClip }, "no playhead, no clip")
        assertEquals(CloudSingStart.Joined(first.jobKey), engine.startSing(localSong, playheadMs = 30_000))
        val clip = engine.state.value.jobs.single { it.isClip }
        assertEquals(27_000L to 90_000L, clip.clipStartMs to clip.clipEndMs)
        assertEquals(first.jobKey, clip.clipOfJobKey)
        assertEquals(CloudSingStart.Joined(first.jobKey), engine.startSing(localSong, playheadMs = 40_000))
        assertEquals(1, engine.state.value.jobs.count { it.isClip })

        // A song whose job already runs at RunPod gets no clip: the full instrumental is nearly there.
        store.saved = listOf(
            storedJob(keys.removeFirst(), streamedSong, CloudJobState.RUNNING, origin = CloudJobOrigin.SING,
                runpodId = "r1", submittedAt = now, priority = true)
        )
        val fresh = engine()
        assertTrue(fresh.startSing(streamedSong, playheadMs = 30_000) is CloudSingStart.Joined)
        assertTrue(fresh.state.value.jobs.none { it.isClip })
    }

    @Test fun `no clip near the end of the song, or when the month only has room for the song`() = runBlocking {
        assertTrue(singWithPlayhead(230_000) is CloudSingStart.Started)
        assertTrue(store.saved.none { it.isClip }, "within 20 s of the end")
        // The song alone is (15 + 38) s at 192 µ$; with the clip it is 10 s more: a cap in between takes the song only.
        assertTrue(singWithPlayhead(60_000) { it.copy(monthlyCapMicroUsd = 11_000) } is CloudSingStart.Started)
        assertTrue(store.saved.none { it.isClip }, "no room for the clip")
        assertTrue(store.saved.single().isPriority)
        assertTrue(singWithPlayhead(60_000) is CloudSingStart.Started)
        assertEquals(1, store.saved.count { it.isClip })
    }

    private suspend fun singWithPlayhead(
        playheadMs: Long,
        change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot = { it },
    ): CloudSingStart {
        settings.snapshot = change(CloudSettingsSnapshot(enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef"))
        store.saved = emptyList()
        return engine().startSing(localSong, playheadMs)
    }

    @Test fun `cancelling the song's job stops its clip too`() = runBlocking {
        val engine = engine()
        val started = engine.startSing(localSong, playheadMs = 60_000) as CloudSingStart.Started
        val clipKey = engine.state.value.jobs.single { it.isClip }.jobKey
        engine.cancel(started.jobKey)
        assertEquals(CloudJobState.CANCELLED, engine.job(clipKey)!!.state)
    }

    // ─── Fakes ──────────────────────────────────────────────────────────────────────────────

    private class FakeSettings : CloudStudioSettingsSource {
        val removedSpend = HashMap<Long, Long>()
        override fun removedSpendMicroUsd(monthStartMs: Long) = removedSpend[monthStartMs] ?: 0L
        override fun addRemovedSpend(microUsd: Long, monthStartMs: Long) {
            if (microUsd > 0) removedSpend[monthStartMs] = (removedSpend[monthStartMs] ?: 0L) + microUsd
        }

        // The automatic jobs' ledger, kept apart from the job list like the real settings do.
        val removedAutoSpend = HashMap<Long, Long>()
        val autoDays = HashMap<Long, Int>()
        override fun removedAutoSpendMicroUsd(monthStartMs: Long) = removedAutoSpend[monthStartMs] ?: 0L
        override fun addRemovedAutoSpend(microUsd: Long, monthStartMs: Long) {
            if (microUsd > 0) removedAutoSpend[monthStartMs] = (removedAutoSpend[monthStartMs] ?: 0L) + microUsd
        }
        override fun autoDayCount(dayStartMs: Long) = autoDays[dayStartMs] ?: 0
        override fun addAutoDayCount(count: Int, dayStartMs: Long) {
            if (count > 0) autoDays[dayStartMs] = (autoDays[dayStartMs] ?: 0) + count
        }

        var snapshot = CloudSettingsSnapshot(enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef")
        var secretsState = CloudSecretsState(CloudSecrets("rpa_key", "AKID", "SECRET"))
        var caps: CloudWorkerCaps? = null
        override fun snapshot() = snapshot
        override suspend fun secrets() = secretsState
        override fun saveWorkerCaps(caps: CloudWorkerCaps?) { this.caps = caps }
    }

    private class FakeStore : CloudJobPersistence {
        var saved: List<CloudJobRecord> = emptyList()
        val history = mutableListOf<List<CloudJobRecord>>()
        override suspend fun load() = saved
        override suspend fun save(jobs: List<CloudJobRecord>) { saved = jobs; history += jobs }
    }

    private inner class FakeHost(songs: List<CloudSong>) : CloudStudioHost {
        val songs = songs.associateBy { it.id }
        val lyrics = HashMap<String, CloudLyricsFacts>()
        val instrumentals = HashSet<String>()
        val videoIds = HashMap<String, String>()
        val audioSourceCalls = mutableListOf<String>()
        val installed = mutableListOf<Triple<File, String, Boolean>>()
        val instrumentalNotes = mutableListOf<String>()
        val savedLyrics = mutableListOf<LyricsDoc>()
        override suspend fun song(id: String) = songs[id]
        override suspend fun librarySongs() = songs.values.toList()
        override suspend fun audioSource(song: CloudSong): String { audioSourceCalls += song.id; return "content://${song.id}" }
        /** Songs whose audio is already on the phone; an automatic job only prepares from these. */
        val cachedIds = HashSet<String>()
        override suspend fun cachedAudioSource(song: CloudSong): String? = "content://${song.id}".takeIf { song.id in cachedIds }
        override suspend fun streamIdentity(song: CloudSong) = videoIds[song.id]
        override suspend fun lyricsFacts(song: CloudSong) = lyrics[song.id] ?: CloudLyricsFacts(
            CloudLyricsState.TEXT_OR_LINE_SYNCED,
            listOf(CloudLyricsInputLine(1_000, 2_000, "first line"), CloudLyricsInputLine(2_000, 3_000, "second line")),
            hasLineTimes = true,
        )
        override suspend fun hasInstrumental(songId: String) = songId in instrumentals
        override suspend fun installInstrumental(staged: File, songId: String, flac: Boolean) {
            installed += Triple(staged, songId, flac)
            staged.delete()
        }
        override suspend fun saveLyrics(doc: LyricsDoc, song: CloudSong, replaceUserSynced: Boolean): CloudLyricsSaveOutcome {
            savedLyrics += doc
            return CloudLyricsSaveOutcome.SAVED
        }
        override fun instrumentalImported(songId: String) { instrumentalNotes += songId }
        override fun lyricsImported(songId: String) = Unit
        /** Preview clips kept (song, start, FLAC), and songs whose clips were let go. */
        val installedClips = mutableListOf<Triple<String, Long, Boolean>>()
        val discardedClips = mutableListOf<String>()
        override suspend fun installClip(staged: File, songId: String, clipStartMs: Long, flac: Boolean) {
            installedClips += Triple(songId, clipStartMs, flac)
            staged.delete()
        }
        override suspend fun discardClips(songId: String) { discardedClips += songId }
    }

    private class FakePreparer(private val dir: File) : CloudAudioPreparing {
        val forceDecodes = mutableListOf<Boolean>()
        var onPrepare: () -> Unit = {}
        val sha = "ab".repeat(32)
        val frames = 10_584_000L // 240 s at 44.1 kHz
        override suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio {
            forceDecodes += forceDecode
            onPrepare()
            dir.mkdirs()
            val file = File(dir, "$jobKey.flac").apply { writeBytes(ByteArray(1_000) { 7 }) }
            return CloudPreparedAudio(file, "flac", file.length(), sha, 240_000, frames, 44_100)
        }
        /** Every clip asked for (start, end), and each job's own frame count (what its result must line up with). */
        val clipRanges = mutableListOf<Pair<Long, Long>>()
        val framesByJob = HashMap<String, Long>()
        override suspend fun prepareClip(source: String, jobKey: String, startMs: Long, endMs: Long): CloudPreparedAudio {
            clipRanges += startMs to endMs
            dir.mkdirs()
            val file = File(dir, "$jobKey.flac").apply { writeBytes(ByteArray(500) { 3 }) }
            val clipFrames = (endMs - startMs) * 44_100 / 1000
            framesByJob[jobKey] = clipFrames
            return CloudPreparedAudio(file, "flac", file.length(), sha, endMs - startMs, clipFrames, 44_100)
        }
        override fun uploadFile(jobKey: String, ext: String) = File(dir, "$jobKey.$ext").takeIf { it.isFile }
        override fun removeUpload(jobKey: String) { File(dir, "$jobKey.flac").delete() }
        fun hasUpload(jobKey: String) = File(dir, "$jobKey.flac").isFile
    }

    /** An in-memory bucket that is also the transfer layer (uploads land in it, downloads come from it). */
    private inner class FakeBucket : CloudObjectStoring, CloudTransfers {
        val objects = LinkedHashMap<String, ByteArray>()
        override fun presignedUrl(method: String, key: String, expiresSeconds: Int) = "https://bucket.test/$key?X-Amz-Signature=$method"
        override suspend fun head(key: String) = objects[key]?.size?.toLong()
        override suspend fun get(key: String) = objects[key]
        override suspend fun put(key: String, data: ByteArray, contentType: String) { objects[key] = data }
        override suspend fun delete(key: String) {
            events += "DELETE $key"
            objects.remove(key)
        }
        override suspend fun list(prefix: String, delimiter: String?): S3ListResult {
            val folders = objects.keys.filter { it.startsWith(prefix) }
                .map { prefix + it.removePrefix(prefix).substringBefore('/') + "/" }.distinct()
            return S3ListResult(emptyList(), folders, false, null)
        }
        private fun keyOf(url: String) = url.removePrefix("https://bucket.test/").substringBefore('?')
        var failUploads = 0
        override suspend fun upload(file: File, url: String, contentType: String, onProgress: (Float) -> Unit): CloudTransferResult {
            if (failUploads > 0) {
                failUploads--
                return CloudTransferResult.Failed("connection reset")
            }
            objects[keyOf(url)] = file.readBytes()
            onProgress(1f)
            return CloudTransferResult.Ok
        }
        override suspend fun download(url: String, destination: File, onProgress: (Float) -> Unit): CloudTransferResult {
            val data = objects[keyOf(url)] ?: return CloudTransferResult.Failed("not found", 404)
            destination.parentFile?.mkdirs()
            destination.writeBytes(data)
            return CloudTransferResult.Ok
        }
    }

    /** RunPod plus a pretend worker: by default it finishes each job at once, writing outputs and the manifest. */
    private inner class FakeRunPod(private val bucket: FakeBucket) : FakeRunPodBase() {
        val runs = mutableListOf<CloudJobRequest>()
        val runIds = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var finishImmediately = true
        var samplesOffset = 0L
        var behaviour: ((CloudJobRequest, String) -> RunPodJob)? = null
        var statusFor: ((String) -> RunPodJob)? = null
        private val pending = mutableListOf<CloudJobRequest>()

        override suspend fun run(request: CloudJobRequest): RunPodJob {
            events += "RUN"
            runs += request
            val id = "rp-${runs.size}"
            runIds += id
            behaviour?.let { return it(request, id) }
            if (finishImmediately) complete(request) else pending += request
            return RunPodJob(id, "IN_QUEUE")
        }

        /** Every `/status` asked, in order. */
        val statusCalls = mutableListOf<String>()

        override suspend fun status(jobId: String): RunPodJob {
            statusCalls += jobId
            return statusFor?.invoke(jobId) ?: RunPodJob(jobId, "IN_PROGRESS", outputText = "separate:40")
        }

        /** What `/cancel` answers (it may also throw); by default the job was taken out of the queue. */
        var cancelResult: ((String) -> RunPodJob?)? = null

        override suspend fun cancel(jobId: String): RunPodJob? {
            events += "CANCEL $jobId"
            cancelled += jobId
            val custom = cancelResult
            return if (custom != null) custom(jobId) else RunPodJob(jobId, "CANCELLED")
        }

        override suspend fun health() = RunPodHealth(idleWorkers = 1)

        fun completeAll() {
            pending.forEach(::complete)
            pending.clear()
        }

        fun complete(request: CloudJobRequest) {
            val input = request.input
            val key = input.jobKey!!
            val codec = CloudOutputCodec.entries.first { it.wire == input.output!!.codec }
            val instrumentalKey = CloudJobBuilder.outputKey(key, "instrumental", codec)
            val audio = ByteArray(2_000) { (it % 251).toByte() }
            bucket.objects[instrumentalKey] = audio
            val outputs = mapOf("instrumental" to CloudOutputFile(instrumentalKey, audio.size.toLong(),
                CloudDigest.sha256Hex(audio), input.output!!.codec, null, 44_100,
                (preparer.framesByJob[key] ?: preparer.frames) + samplesOffset))
            var lyricsSummary: CloudLyricsSummary? = null
            if (input.lyrics != null) {
                val doc = CloudLyricsDocument(mode = "aligned", lines = listOf(
                    CloudLyricsLine(0, 1_000, 2_000, "first line", "word",
                        listOf(CloudLyricsWord(1_000, 1_400, "first", null, 0, 5), CloudLyricsWord(1_500, 1_900, "line", null, 6, 10))),
                    CloudLyricsLine(1, 2_000, 3_000, "second line", "line", emptyList()),
                ))
                val bytes = CloudJson.encodeToString(doc).toByteArray()
                bucket.objects[CloudKeys.lyrics(key)] = bytes
                lyricsSummary = CloudLyricsSummary(CloudKeys.lyrics(key), bytes.size.toLong(), CloudDigest.sha256Hex(bytes),
                    "aligned", null, 2, 1, 2)
            }
            val manifest = CloudJobResult(jobKey = key, status = "ok", warnings = emptyList(),
                worker = CloudWorkerInfo(gpu = "NVIDIA L4"), input = CloudInputInfo(sha256 = input.audio!!.sha256),
                outputs = outputs, lyrics = lyricsSummary, timings = CloudTimings(coldStartMs = 1_000, totalMs = 20_000))
            bucket.objects[CloudKeys.manifest(key)] = CloudJson.encodeToString(manifest).toByteArray()
            bucket.objects.remove(CloudKeys.input(key, input.audio!!.ext)) // the worker deletes the input after ok
        }
    }

    private class FakeScheduler : CloudWorkScheduler {
        var passRequests = 0
        var urgentRequests = 0
        var unmeteredRequests = 0
        var watchCancels = 0
        override fun requestPass() { passRequests++ }
        // An urgent pass is still a pass: the default of the real interface does the same.
        override fun requestUrgentPass() { urgentRequests++; passRequests++ }
        override fun requestUnmeteredPass() { unmeteredRequests++ }
        override fun ensureWatch() = Unit
        override fun cancelWatch() { watchCancels++ }
    }
}
