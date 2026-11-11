package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.spatial.library.SpatialManifests
import com.theveloper.pixelplay.data.spatial.library.SpatialRenderManifest
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Spatial audio's library jobs in Cloud Studio's engine (cloud-audio-spec §4, §6.1 "Money", §6.2 steps 3-4): the job,
 * its own month and cap, the switches and network rules it obeys, collecting its renders into the local store, and
 * Sing staying first and unaffected.
 */
class CloudLibraryJobsTest {
    @TempDir lateinit var directory: Path

    private var now = 1_800_000_000_000L
    private val passMs = 10L * 60_000
    private val keys = ArrayDeque((1..50).map { "%08x-0000-4000-8000-%012x".format(it, it) })
    private val video = "J7p4bzqLvCw"
    private val song = CloudSong("spotify_x", "Song", "Artist", "Album", 200_000, isStreamed = true, hasAudioSource = true)
    private var unmetered = true
    private var aheadOk = true
    private var library = CloudLibrarySettings(enabled = true, monthlyCapMicroUsd = 30_000_000)
    private val settings = Settings()
    private val store = Store()
    private val host = Host()
    private val bucket = Bucket()
    private val runpod = RunPod()
    private val libraryHost = LibraryHost()
    private val preparer by lazy { Preparer(directory.resolve("uploads").toFile()) }

    private fun engine(): CloudStudioEngine = CloudStudioEngine(
        CloudStudioDependencies(
            settings = settings, store = store, host = host, preparer = preparer, transfers = bucket,
            makeRunPod = { if (it.hasRunPod) runpod else null }, makeObjects = { if (it.hasStorage) bucket else null },
            scheduler = Scheduler(), scope = CoroutineScope(Dispatchers.Unconfined), build = "test (1)",
            stagingDir = directory.resolve("staging").toFile(), isUnmetered = { unmetered }, nowMs = { now },
            monthStartMs = { 0L }, dayStartMs = { 0L }, newJobKey = { keys.removeFirst() },
            libraryHost = libraryHost, librarySettings = { library }, libraryAheadAllowed = { aheadOk },
        )
    )

    private suspend fun CloudStudioEngine.sendRender(profile: String = "studio", ahead: Boolean = false): CloudJobRecord =
        (sendLibraryJob(song.id, video, profile, ahead) as CloudLibrarySend.Sent).record

    @Test fun `a played song's render goes from the request to the local store, and the library keeps its objects`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender()
        assertEquals(CloudJobOrigin.LIBRARY, record.origin)
        assertEquals(listOf(CloudTask.SPATIAL), record.tasks)
        assertEquals(CloudLibraryJob(video, listOf("studio")), record.library)
        assertEquals(1, record.lane)
        // Not the song's instrumental work: Sing and the on-device studio don't see it.
        assertFalse(engine.hasPendingJob(song.id))
        assertTrue(engine.jobsFor(song.id).first().isEmpty())
        assertEquals("Spatial audio: 1 waiting", engine.summaryLine())
        assertFalse(engine.hasWorkForThePerson(), "quiet: no notification for it")

        engine.workerPass(now + passMs)
        val done = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertEquals(listOf("studio"), done.library?.imported)
        assertEquals(mapOf("studio" to "ok"), done.library?.renders)
        // The /run body: the spatial task, the library block naming the stream, the long timeout, no audio slot.
        val request = runpod.runs.single()
        assertEquals(listOf("spatial"), request.input.tasks)
        assertEquals(CloudLibrarySource(251, "opus"), request.input.library?.source)
        assertEquals(video, request.input.library?.videoId)
        assertEquals(listOf("studio"), request.input.library?.renders?.map { it.profile })
        assertEquals(1_800_000L, request.policy?.executionTimeout)
        assertEquals(listOf("manifest"), request.input.output?.put?.keys?.toList())
        assertNull(request.input.output?.codec)
        // The song's audio came from the phone's copy or a fresh fetch, never a permanent download.
        assertEquals(listOf(true), host.libraryAudioCalls)
        assertEquals(0, host.audioSourceCalls)
        // The render went into the local store; in/ and out/ are gone, lib/ stays for every other device.
        assertEquals(bucket.objects[CloudKeys.libraryAudio(video, "studio")]?.toList(), libraryHost.installed[video to "studio"]?.toList())
        assertTrue(bucket.objects.keys.all { it.startsWith("lib/") }, "left: ${bucket.objects.keys}")
        assertTrue(CloudKeys.libraryManifest(video, "studio") in bucket.objects)
        // Its money is the library's, not Sing's.
        assertEquals(0L, engine.committedThisMonthMicroUsd())
        assertTrue(engine.libraryCommittedThisMonthMicroUsd() > 0)
        assertEquals(engine.libraryCommittedThisMonthMicroUsd(), done.costMicroUsd)
    }

    @Test fun `one job per video and profile, and a play takes over the background queue's`() = runBlocking {
        val engine = engine()
        val ahead = engine.sendRender(ahead = true)
        assertTrue(ahead.isLibraryAhead)
        assertEquals(2, ahead.lane)
        assertEquals(1, engine.libraryAheadSentToday())
        assertEquals(ahead.jobKey, engine.sendRender(ahead = true).jobKey)
        assertEquals(1, engine.libraryAheadSentToday(), "the same job isn't counted twice")
        val played = engine.sendRender(ahead = false)
        assertEquals(ahead.jobKey, played.jobKey)
        assertFalse(played.isLibraryAhead)
        assertFalse(engine.job(ahead.jobKey)!!.isLibraryAhead)
        // Another profile is another job.
        assertNotEquals(ahead.jobKey, engine.sendRender(profile = "hall").jobKey)
        // What can't be a job isn't one.
        assertTrue(engine.sendLibraryJob(song.id, "spotify:x", "studio", false) is CloudLibrarySend.Refused)
        assertTrue(engine.sendLibraryJob(song.id, video, "atmos", false) is CloudLibrarySend.Refused)
    }

    @Test fun `library jobs have their own cap, and Sing's cap and its 3 dollar rule don't hold them`() = runBlocking {
        // Sing's month is spent (a cap of nothing): a library job still goes out.
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 0)
        val engine = engine()
        val record = engine.sendRender()
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
        assertNotEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        // The library's own cap: nothing more fits, so no job is made.
        library = library.copy(monthlyCapMicroUsd = engine.libraryCommittedThisMonthMicroUsd())
        assertEquals(CloudLibrarySend.CapReached, engine.sendLibraryJob(song.id, video, "night", false))
        // "No limit".
        library = library.copy(monthlyCapMicroUsd = null)
        assertTrue(engine.sendLibraryJob(song.id, video, "night", false) is CloudLibrarySend.Sent)
    }

    @Test fun `a library job uploaded over its cap waits there, without Sing's notice`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender()
        library = library.copy(monthlyCapMicroUsd = 0)
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.UPLOADED, engine.job(record.jobKey)!!.state)
        assertTrue(runpod.runs.isEmpty())
        assertNotEquals(CloudNotice.CAP_REACHED, engine.state.value.notice)
        library = library.copy(monthlyCapMicroUsd = 30_000_000)
        now += 1_000
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
    }

    @Test fun `clearing the list keeps each month's spend in its own ledger`() = runBlocking {
        val engine = engine()
        engine.sendRender()
        engine.workerPass(now + passMs)
        val spent = engine.libraryCommittedThisMonthMicroUsd()
        engine.clearFinished()
        assertTrue(engine.state.value.jobs.isEmpty())
        assertEquals(spent, settings.removedLibrary)
        assertEquals(0L, settings.removedStudio)
        assertEquals(spent, engine.libraryCommittedThisMonthMicroUsd())
        assertEquals(0L, engine.committedThisMonthMicroUsd())
    }

    @Test fun `with Spatial's switch off a library job doesn't move, and a finished one isn't downloaded`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender()
        library = library.copy(enabled = false)
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.QUEUED, engine.job(record.jobKey)!!.state)
        assertTrue(preparer.prepared.isEmpty())
        // On again: it goes out; RunPod holds it while the switch goes off once more.
        library = library.copy(enabled = true)
        runpod.finishImmediately = false
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.SUBMITTED, engine.job(record.jobKey)!!.state)
        runpod.completeAll()
        library = library.copy(enabled = false)
        now += 60_000
        engine.workerPass(now + passMs)
        val ended = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, ended.state)
        assertTrue(ended.library!!.imported.isEmpty())
        assertTrue(libraryHost.installed.isEmpty(), "nothing downloaded while the switch is off")
        assertTrue(CloudKeys.libraryAudio(video, "studio") in bucket.objects, "the render waits in the shared library")
    }

    @Test fun `a failed render is retried while one already on the phone isn't fetched again`() = runBlocking {
        val engine = engine()
        val hall = engine.sendRender(profile = "hall")
        val night = engine.sendRender(profile = "night")
        runpod.failing += "hall"
        libraryHost.installed[video to "night"] = ByteArray(1) // brought by the shared library meanwhile
        engine.workerPass(now + passMs)
        val failedHall = engine.job(hall.jobKey)!!
        assertNotEquals(CloudJobState.IMPORTED, failedHall.state, "an all-failed library job is a worker error")
        val doneNight = engine.job(night.jobKey)!!
        assertEquals(CloudJobState.IMPORTED, doneNight.state, "last error: ${doneNight.lastError}")
        assertEquals(listOf("night"), doneNight.library?.imported)
        assertEquals(1, libraryHost.installed[video to "night"]?.size, "not downloaded again")
        assertEquals(0, bucket.downloads.count { it.contains("sp-night.flac") })
    }

    @Test fun `a job with two renders where one fails stores the other`() = runBlocking {
        // A job stored by another build with two profiles (this app sends one): the worker's partial answer.
        store.saved = listOf(
            CloudJobRecord(jobKey = keys.removeFirst(), songId = song.id, videoId = video, tasks = listOf(CloudTask.SPATIAL),
                origin = CloudJobOrigin.LIBRARY, library = CloudLibraryJob(video, listOf("night", "hall")), isStreamed = true,
                createdAtMs = now),
        )
        runpod.failing += "hall"
        val engine = engine()
        engine.workerPass(now + passMs)
        val done = engine.state.value.jobs.single()
        assertEquals(CloudJobState.IMPORTED, done.state, "last error: ${done.lastError}")
        assertEquals("partial", done.resultStatus)
        assertEquals(mapOf("night" to "ok", "hall" to "failed"), done.library?.renders)
        assertEquals(listOf("night"), done.library?.imported)
        assertNotNull(libraryHost.installed[video to "night"])
        assertNull(libraryHost.installed[video to "hall"])
    }

    @Test fun `a render gone from the library before it came down sends the job round again`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender()
        runpod.janitor = true // the manifest is deleted right after the job
        engine.workerPass(now + passMs)
        val again = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.QUEUED, again.state)
        assertNotNull(again.nextAttemptAtMs)
        assertTrue(again.lastError!!.contains("gone"))
        assertTrue(libraryHost.installed.isEmpty())
    }

    @Test fun `a damaged render isn't stored and is fetched again later`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender()
        runpod.damage = true
        engine.workerPass(now + passMs)
        val waiting = engine.job(record.jobKey)!!
        assertEquals(CloudJobState.RESULTS_READY, waiting.state)
        assertTrue(waiting.lastError!!.startsWith("Import"))
        assertTrue(libraryHost.installed.isEmpty())
        runpod.damage = false
        bucket.objects[CloudKeys.libraryAudio(video, "studio")] = runpod.lastAudio!!
        now = waiting.nextAttemptAtMs!! + 1
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
    }

    @Test fun `the background queue's jobs move only on Wi-Fi with a battery that isn't low`() = runBlocking {
        val engine = engine()
        val record = engine.sendRender(ahead = true)
        aheadOk = false
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.QUEUED, engine.job(record.jobKey)!!.state)
        aheadOk = true
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
    }

    @Test fun `the person's render follows the mobile data setting`() = runBlocking {
        unmetered = false
        val engine = engine()
        val record = engine.sendRender()
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.QUEUED, engine.job(record.jobKey)!!.state)
        settings.snapshot = settings.snapshot.copy(useCellular = true)
        engine.workerPass(now + passMs)
        assertEquals(CloudJobState.IMPORTED, engine.job(record.jobKey)!!.state)
    }

    @Test fun `a Sing never joins a library job, and goes first`() = runBlocking {
        val engine = engine()
        val render = engine.sendRender()
        val sing = engine.startSing(song)
        assertTrue(sing is CloudSingStart.Started, "got $sing")
        val singKey = (sing as CloudSingStart.Started).jobKey
        assertNotEquals(render.jobKey, singKey)
        assertTrue(engine.hasPendingJob(song.id))
        assertEquals(listOf(singKey), engine.jobsFor(song.id).first().map { it.jobKey })
        engine.workerPass(now + passMs)
        assertEquals("instrumental", runpod.runs.first().input.tasks?.single(), "the Sing went out first")
        assertEquals(CloudJobState.IMPORTED, engine.job(render.jobKey)!!.state)
    }

    @Test fun `the shared library is read through signed lib keys only`() = runBlocking {
        val engine = engine()
        bucket.objects["lib/v1/$video/sp-studio.json"] = "{}".toByteArray()
        assertEquals("{}", engine.fetchLibraryObject("lib/v1/$video/sp-studio.json")?.toString(Charsets.UTF_8))
        assertNull(engine.fetchLibraryObject("lib/v1/$video/sp-hall.json"))
        val target = directory.resolve("fetched.flac").toFile()
        assertTrue(engine.downloadLibraryObject("lib/v1/$video/sp-hall.flac", target) is CloudTransferResult.Failed)
        val refused = runCatching { engine.fetchLibraryObject("out/x/manifest.json") }
        assertTrue(refused.isFailure)
        // The master switch off: nothing is read.
        settings.snapshot = settings.snapshot.copy(enabled = false)
        assertTrue(runCatching { engine.fetchLibraryObject("lib/v1/$video/sp-studio.json") }.isFailure)
    }

    // ─── Fakes ──────────────────────────────────────────────────────────────────────────────

    private class Settings : CloudStudioSettingsSource {
        var snapshot = CloudSettingsSnapshot(enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef")
        var removedStudio = 0L
        var removedLibrary = 0L
        var aheadToday = 0
        override fun snapshot() = snapshot
        override suspend fun secrets() = CloudSecretsState(CloudSecrets("rpa_key", "AKID", "SECRET"))
        override fun saveWorkerCaps(caps: CloudWorkerCaps?) = Unit
        override fun removedSpendMicroUsd(monthStartMs: Long) = removedStudio
        override fun addRemovedSpend(microUsd: Long, monthStartMs: Long) { removedStudio += microUsd }
        override fun removedLibrarySpendMicroUsd(monthStartMs: Long) = removedLibrary
        override fun addRemovedLibrarySpend(microUsd: Long, monthStartMs: Long) { removedLibrary += microUsd }
        override fun libraryAheadDayCount(dayStartMs: Long) = aheadToday
        override fun addLibraryAheadDayCount(count: Int, dayStartMs: Long) { aheadToday += count }
    }

    private class Store : CloudJobPersistence {
        var saved: List<CloudJobRecord> = emptyList()
        override suspend fun load() = saved
        override suspend fun save(jobs: List<CloudJobRecord>) { saved = jobs }
    }

    private inner class Host : CloudStudioHost {
        val libraryAudioCalls = mutableListOf<Boolean>()
        var audioSourceCalls = 0
        override suspend fun song(id: String) = song.takeIf { it.id == id }
        override suspend fun librarySongs() = listOf(song)
        override suspend fun audioSource(song: CloudSong): String { audioSourceCalls++; return "content://${song.id}" }
        override suspend fun cachedAudioSource(song: CloudSong): String? = null
        override suspend fun streamIdentity(song: CloudSong) = video
        override suspend fun hasInstrumental(songId: String) = false
        override suspend fun installInstrumental(staged: File, songId: String, flac: Boolean) { staged.delete() }
        override fun instrumentalImported(songId: String) = Unit
        override suspend fun libraryAudioSource(song: CloudSong, allowDownload: Boolean): String? {
            libraryAudioCalls += allowDownload
            return "cache://${song.id}"
        }
        override suspend fun streamItag(song: CloudSong): Int? = 251
    }

    private class Preparer(private val dir: File) : CloudAudioPreparing {
        val prepared = mutableListOf<String>()
        override suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio {
            prepared += jobKey
            dir.mkdirs()
            val file = File(dir, "$jobKey.flac").apply { writeBytes(ByteArray(1_000) { 5 }) }
            return CloudPreparedAudio(file, "flac", file.length(), CloudDigest.file(file).first, 200_000, 9_600_000, 48_000,
                sourceCodec = "opus")
        }
        override fun uploadFile(jobKey: String, ext: String) = File(dir, "$jobKey.$ext").takeIf { it.isFile }
        override fun removeUpload(jobKey: String) { File(dir, "$jobKey.flac").delete() }
    }

    private class Bucket : CloudObjectStoring, CloudTransfers {
        val objects = LinkedHashMap<String, ByteArray>()
        val downloads = mutableListOf<String>()
        override fun presignedUrl(method: String, key: String, expiresSeconds: Int) = "https://bucket.test/$key?X-Amz-Signature=$method"
        override suspend fun head(key: String) = objects[key]?.size?.toLong()
        override suspend fun get(key: String) = objects[key]
        override suspend fun put(key: String, data: ByteArray, contentType: String) { objects[key] = data }
        override suspend fun delete(key: String) { objects.remove(key) }
        override suspend fun list(prefix: String, delimiter: String?): S3ListResult {
            val folders = objects.keys.filter { it.startsWith(prefix) }
                .map { prefix + it.removePrefix(prefix).substringBefore('/') + "/" }.distinct()
            return S3ListResult(emptyList(), folders, false, null)
        }
        private fun keyOf(url: String) = url.removePrefix("https://bucket.test/").substringBefore('?')
        override suspend fun upload(file: File, url: String, contentType: String, onProgress: (Float) -> Unit): CloudTransferResult {
            objects[keyOf(url)] = file.readBytes()
            return CloudTransferResult.Ok
        }
        override suspend fun download(url: String, destination: File, onProgress: (Float) -> Unit): CloudTransferResult {
            downloads += keyOf(url)
            val data = objects[keyOf(url)] ?: return CloudTransferResult.Failed("not found", 404)
            destination.parentFile?.mkdirs()
            destination.writeBytes(data)
            return CloudTransferResult.Ok
        }
    }

    /** The local store as the engine sees it, checking each render the way the real one does. */
    private class LibraryHost : CloudLibraryHost {
        val installed = HashMap<Pair<String, String>, ByteArray>()
        override fun hasRender(videoId: String, profileId: String) = (videoId to profileId) in installed
        override suspend fun installRender(videoId: String, profileId: String, staged: File, manifest: ByteArray): String? {
            try {
                val parsed = SpatialManifests.decode(manifest) ?: return "unreadable manifest"
                val (sha, bytes) = CloudDigest.file(staged)
                if (!SpatialManifests.fileMatches(parsed, bytes, sha)) return "The downloaded render was damaged."
                installed[videoId to profileId] = staged.readBytes()
                return null
            } finally {
                staged.delete()
            }
        }
    }

    /** RunPod plus a pretend 1.1.0 worker: renders into lib/, writes each render's manifest, then the job's. */
    private inner class RunPod : FakeRunPodBase() {
        val runs = mutableListOf<CloudJobRequest>()
        var finishImmediately = true
        val failing = HashSet<String>()
        var janitor = false
        var damage = false
        var lastAudio: ByteArray? = null
        private val pending = mutableListOf<Pair<String, CloudJobRequest>>()
        /** Finished runs: `/status` answers COMPLETED with the manifest (the clock stands still, so listings don't repeat). */
        private val finished = HashMap<String, CloudJobResult>()

        override suspend fun run(request: CloudJobRequest): RunPodJob {
            runs += request
            val id = "rp-${runs.size}"
            if (finishImmediately) finished[id] = complete(request) else pending += id to request
            return RunPodJob(id, "IN_QUEUE")
        }

        override suspend fun status(jobId: String): RunPodJob =
            finished[jobId]?.let { RunPodJob(jobId, "COMPLETED", result = it) } ?: RunPodJob(jobId, "IN_QUEUE")
        override suspend fun cancel(jobId: String): RunPodJob? = RunPodJob(jobId, "CANCELLED")
        override suspend fun health() = RunPodHealth(idleWorkers = 1)

        fun completeAll() {
            for ((id, request) in pending) finished[id] = complete(request)
            pending.clear()
        }

        private fun complete(request: CloudJobRequest): CloudJobResult {
            val input = request.input
            val key = input.jobKey!!
            val outputs = LinkedHashMap<String, CloudOutputFile>()
            val renders = LinkedHashMap<String, String>()
            input.library?.let { library ->
                for (target in library.renders) {
                    val profile = target.profile
                    if (profile in failing) {
                        renders[profile] = CloudLibraryRenderState.FAILED
                        continue
                    }
                    val audioKey = CloudKeys.libraryAudio(library.videoId, profile)
                    val manifestKey = CloudKeys.libraryManifest(library.videoId, profile)
                    val existed = manifestKey in bucket.objects
                    val audio = bucket.objects[audioKey] ?: ByteArray(3_000) { (it % 89).toByte() }
                    lastAudio = audio
                    bucket.objects[audioKey] = if (damage) audio.copyOf().also { it[0] = 99 } else audio
                    bucket.objects[manifestKey] = renderManifest(library.videoId, profile, audio)
                    if (janitor) bucket.objects.remove(manifestKey)
                    renders[profile] = if (existed) CloudLibraryRenderState.EXISTS else CloudLibraryRenderState.OK
                    outputs[CloudKeys.librarySlot(profile)] = CloudOutputFile(audioKey, audio.size.toLong(),
                        CloudDigest.sha256Hex(audio), "flac", null, 48_000, 9_600_000)
                }
            }
            // A Sing's job: its instrumental, at the uploaded song's length (the import's length check).
            if (CloudTask.INSTRUMENTAL in input.typedTasks) {
                val codec = CloudOutputCodec.entries.first { it.wire == input.output?.codec }
                val instrumentalKey = CloudJobBuilder.outputKey(key, "instrumental", codec)
                val audio = ByteArray(2_000) { (it % 61).toByte() }
                bucket.objects[instrumentalKey] = audio
                outputs["instrumental"] = CloudOutputFile(instrumentalKey, audio.size.toLong(), CloudDigest.sha256Hex(audio),
                    codec.wire, null, 48_000, 9_600_000)
            }
            val anyOk = input.library == null || renders.values.any { it != CloudLibraryRenderState.FAILED }
            val status = when {
                !anyOk -> "error"
                renders.values.any { it == CloudLibraryRenderState.FAILED } -> "partial"
                else -> "ok"
            }
            val result = CloudJobResult(
                jobKey = key, status = status, error = if (!anyOk) CloudResultError("INTERNAL") else null,
                warnings = emptyList(), worker = CloudWorkerInfo(gpu = "NVIDIA L4"),
                input = CloudInputInfo(sha256 = input.audio!!.sha256), outputs = outputs,
                library = input.library?.takeIf { anyOk }?.let { CloudLibraryResult(it.videoId, 1, renders, "stems6") },
                timings = CloudTimings(coldStartMs = 1_000, totalMs = 70_000),
            )
            bucket.objects[CloudKeys.manifest(key)] = CloudJson.encodeToString(CloudJobResult.serializer(), result).toByteArray()
            if (anyOk) bucket.objects.remove(CloudKeys.input(key, input.audio!!.ext))
            return result
        }

        private fun renderManifest(videoId: String, profile: String, audio: ByteArray): ByteArray {
            val base = SpatialManifests.decode(CloudFixtures.worker("library.render.spatial.json").toByteArray())!!
            val manifest = base.copy(videoId = videoId, profile = profile, audio = base.audio.copy(
                key = CloudKeys.libraryAudio(videoId, profile), bytes = audio.size.toLong(), sha256 = CloudDigest.sha256Hex(audio)))
            return CloudJson.encodeToString(SpatialRenderManifest.serializer(), manifest).toByteArray()
        }
    }

    private class Scheduler : CloudWorkScheduler {
        override fun requestPass() = Unit
        override fun requestUnmeteredPass() = Unit
        override fun ensureWatch() = Unit
        override fun cancelWatch() = Unit
    }
}
