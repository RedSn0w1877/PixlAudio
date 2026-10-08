package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.model.LyricsDoc
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val keys = ArrayDeque((1..50).map { "%08x-0000-4000-8000-%012x".format(it, it) })
    private lateinit var settings: FakeSettings
    private lateinit var store: FakeStore
    private lateinit var host: FakeHost
    private lateinit var preparer: FakePreparer
    private lateinit var bucket: FakeBucket
    private lateinit var runpod: FakeRunPod
    private lateinit var scheduler: FakeScheduler
    private var unmetered = true
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
            makeRunPod = { if (it.hasRunPod) runpod else null }, makeObjects = { if (it.hasStorage) bucket else null },
            scheduler = scheduler, scope = CoroutineScope(Dispatchers.Unconfined), build = "test (1)",
            stagingDir = directory.resolve("staging").toFile(), isUnmetered = { unmetered },
            nowMs = { now }, monthStartMs = { 0L }, newJobKey = { keys.removeFirst() },
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
        engine.workerPass(now + 60_000)
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
        fresh.workerPass(now + 1_000)
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
        engine.workerPass(now + 60_000)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.UPLOADED, job.state)
        assertEquals("GPU_OOM", job.lastErrorCode)
        assertEquals(1, job.attempts)
        // After the backoff the job goes out again; this run is still queued at RunPod.
        now = job.nextAttemptAtMs!! + 1
        runpod.behaviour = { _, id -> RunPodJob(id, "IN_QUEUE") }
        runpod.statusFor = { id -> RunPodJob(id, "IN_QUEUE") }
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
        now += 1_000
        sendOne(engine, threeSongs[1])
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
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
        val outcome = engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.UPLOADING, job.state)
        assertNotNull(job.nextAttemptAtMs)
        now = job.nextAttemptAtMs!! + 1
        engine.workerPass(now + 60_000)
        job = engine.job(record.jobKey)!!
        assertTrue(job.state.isAtRunPod, "state ${job.state}")
        assertNull(job.nextAttemptAtMs, "no \"Trying again soon\" once the upload went through")
    }

    @Test fun `a streamed song is decoded, tied to its video, and refused if the match changed`() = runBlocking {
        host.videoIds[streamedSong.id] = "VIDEO_A"
        val engine = engine()
        val record = sendOne(engine, streamedSong)
        runpod.finishImmediately = false
        engine.workerPass(now + 60_000)
        assertEquals(listOf(true), preparer.forceDecodes)
        assertEquals(listOf(streamedSong.id), host.audioSourceCalls)
        assertEquals("VIDEO_A", engine.job(record.jobKey)!!.videoId)
        // The Spotify match moved to another video before the results came back.
        host.videoIds[streamedSong.id] = "VIDEO_B"
        runpod.completeAll()
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + 60_000)
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
        val outcome = engine.workerPass(now + 60_000)
        assertTrue(preparer.forceDecodes == listOf(false), "only the local song was prepared")
        assertEquals(CloudJobState.QUEUED, engine.job(streamed.jobKey)!!.state)
        assertEquals(CloudJobState.UPLOADING, engine.job(local.jobKey)!!.state)
        assertTrue(outcome.waitingForUnmetered)
        assertEquals(1, scheduler.unmeteredRequests)
        assertEquals(CloudNotice.WAITING_FOR_WIFI, engine.state.value.notice)
        assertTrue(runpod.runs.isEmpty())
        // Allowing mobile data lets everything go.
        settings.snapshot = settings.snapshot.copy(useCellular = true)
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
        assertEquals(CloudJobState.UPLOADED, engine.job(record.jobKey)!!.state)
        assertEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        assertTrue(runpod.runs.isEmpty())
    }

    @Test fun `a missing input is uploaded again, a poisoned job stops`() = runBlocking {
        val engine = engine()
        val record = sendOne(engine)
        runpod.finishImmediately = false
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
        runpod.statusFor = { throw RunPodError.JobNotFound() }
        now += CloudTiming.TTL_MS + CloudTiming.EXECUTION_TIMEOUT_MS + 1
        engine.workerPass(now + 60_000)
        var job = engine.job(record.jobKey)!!
        assertEquals(1, job.resubmits)
        // Its upload is now older than 3 days (the R2 lifecycle deletes inputs after 7), so it goes up again first.
        assertEquals(CloudJobState.QUEUED, job.state)
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
        var job = engine.job(record.jobKey)!!
        assertEquals(CloudOutputCodec.FLAC, job.outputCodec)
        assertTrue(job.flacRedone)
        assertEquals(CloudJobState.QUEUED, job.state)
        assertTrue(job.importedLyrics)
        assertEquals(listOf(CloudTask.INSTRUMENTAL), job.tasks)
        // The redo still doesn't line up: it stops instead of looping.
        now += CloudTiming.STATUS_POLL_INTERVAL_MS + 1
        engine.workerPass(now + 60_000)
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
        engine.workerPass(now + 60_000)
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
        val outcome = engine.workerPass(now + 60_000)
        assertTrue(outcome.blocked)
        assertEquals(CloudNotice.KEYS_MISSING, engine.state.value.notice)
        assertEquals(before, scheduler.passRequests)
        settings.secretsState = CloudSecretsState(CloudSecrets("rpa", "", ""))
        engine.workerPass(now + 60_000)
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

    // ─── Fakes ──────────────────────────────────────────────────────────────────────────────

    private class FakeSettings : CloudStudioSettingsSource {
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

        override suspend fun status(jobId: String): RunPodJob =
            statusFor?.invoke(jobId) ?: RunPodJob(jobId, "IN_PROGRESS", outputText = "separate:40")

        override suspend fun cancel(jobId: String) { cancelled += jobId }

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
                CloudDigest.sha256Hex(audio), input.output!!.codec, null, 44_100, preparer.frames + samplesOffset))
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
        var unmeteredRequests = 0
        var watchCancels = 0
        override fun requestPass() { passRequests++ }
        override fun requestUnmeteredPass() { unmeteredRequests++ }
        override fun ensureWatch() = Unit
        override fun cancelWatch() { watchCancels++ }
    }
}
