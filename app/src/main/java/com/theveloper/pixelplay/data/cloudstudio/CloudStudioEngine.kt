package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A line above the queue that applies to every job. The screens show it from strings.xml. */
enum class CloudNotice {
    /** Nothing leaves this phone until the switch is on. */
    OFF,
    NOT_CONFIGURED,
    /** Keys were saved once but secure storage has none now: paste them again. */
    KEYS_MISSING,
    /** The Keystore-backed store can't be opened, so the keys can't be read. */
    SECURE_STORAGE_UNAVAILABLE,
    RUNPOD_KEY_REFUSED,
    ENDPOINT_NOT_FOUND,
    RATE_LIMITED,
    CAP_REACHED,
    ENDPOINT_PAUSED,
    /** Uploads or downloads wait for Wi-Fi ("Use mobile data" is off). */
    WAITING_FOR_WIFI,
}

/** Where a batch comes from (the queue's Add menu). */
enum class CloudBatchKind { CURRENT, MISSING_LYRICS, MISSING_INSTRUMENTAL }

/** One batch the person is about to send (the confirm sheet, design §7.3). */
data class CloudBatchPreview(
    /** The batch id the records get. */
    val id: String,
    val title: String,
    val songs: List<CloudSong>,
    val plans: List<CloudSongPlan>,
    val skipped: List<Pair<CloudSkipReason, Int>>,
    val estimate: CloudBatchEstimate,
    val replaceUserSynced: Boolean,
) {
    val isEmpty: Boolean get() = plans.isEmpty()
}

/** What the screens show. */
data class CloudStudioState(
    /** Every job, oldest first. */
    val jobs: List<CloudJobRecord> = emptyList(),
    val isLoaded: Boolean = false,
    val notice: CloudNotice? = null,
    /** Upload or download progress (0…1) of a job's running transfer. */
    val transferProgress: Map<String, Float> = emptyMap(),
    /** A pass is running (the queue shows a quiet "working" line). */
    val isWorking: Boolean = false,
) {
    val activeJobs: List<CloudJobRecord> get() = jobs.filter { it.state.isPending }
    val finishedJobs: List<CloudJobRecord> get() = jobs.filter { it.state == CloudJobState.IMPORTED }.asReversed()
    val attentionJobs: List<CloudJobRecord>
        get() = jobs.filter { it.state == CloudJobState.FAILED || it.state == CloudJobState.CANCELLED || it.state == CloudJobState.EXPIRED }
}

/** What a pass leaves for later. */
data class CloudPassOutcome(
    /** Jobs not yet finished (the background watch keeps running while there are any). */
    val pending: Boolean = false,
    /** Songs to prepare or upload are due now (a full pass should run). */
    val workBeforeSubmit: Boolean = false,
    /** Transfers wait for an unmetered network. */
    val waitingForUnmetered: Boolean = false,
    /** The earliest retry or batch-gate moment, when one is pending. */
    val nextWakeAtMs: Long? = null,
    /** The pass stopped early (switched off, keys missing, fields incomplete): nothing can move until the person acts. */
    val blocked: Boolean = false,
)

/**
 * Cloud Studio's orchestrator (design §1, §7, §8), ported from the iOS app's `CloudStudio.swift`: the per-song jobs
 * from "Send" to the imported instrumental and word-timed lyrics. It only sequences I/O; every rule (states, retries,
 * caps, gates, checks) is in [CloudQueuePolicy]/[CloudJobBuilder], and every side effect goes through
 * [CloudStudioDependencies], so unit tests drive it with fakes.
 *
 * - Nothing is sent while the consent switch is off, and the cloud is never used automatically or as a fallback:
 *   only batches the person confirmed run.
 * - Android runs the work in passes instead of iOS's background URLSession: a WorkManager worker runs full passes
 *   (prepare → upload → submit → watch → collect), a periodic worker watches every 15 minutes while jobs are in
 *   flight, and while the app is on screen a light pass (submit → watch → collect) runs every 15 s, so results
 *   come back right away. Passes never overlap ([passLock]).
 * - The job list is read before anything is saved, and every change is saved at once (a RunPod job id is on disk
 *   before the next `/run`), so a process the system killed and restarted never loses its queue.
 */
class CloudStudioEngine(private val deps: CloudStudioDependencies) {
    private val _state = MutableStateFlow(CloudStudioState())
    val state: StateFlow<CloudStudioState> = _state.asStateFlow()

    private val jobsLock = Mutex()
    private val passLock = Mutex()
    @Volatile private var jobs: List<CloudJobRecord> = emptyList()
    @Volatile private var loaded = false
    private val transfersInFlight = ConcurrentHashMap<String, Deferred<*>>()
    private var clientsKey: CloudConfigInput? = null
    private var clientsCache: Clients? = null
    private var lastListAtMs = 0L
    private var lastHealthAtMs = 0L
    @Volatile private var visible = false
    private var pollLoop: Job? = null

    private class Clients(val runpod: RunPodJobsApi, val objects: CloudObjectStoring)

    private enum class PassMode { FULL, LIGHT }

    private val now: Long get() = deps.nowMs()

    // ─── Queries ────────────────────────────────────────────────────────────────────────────

    fun job(jobKey: String): CloudJobRecord? = jobs.firstOrNull { it.jobKey == jobKey }

    /** The song has a job that isn't finished. */
    fun hasPendingJob(songId: String): Boolean = jobs.any { it.songId == songId && it.state.isPending }

    /** "Cloud: 12 waiting, 1 processing" (null when nothing is pending). */
    fun summaryLine(): String? {
        val active = jobs.filter { it.state.isPending }
        if (active.isEmpty()) return null
        val processing = active.count { it.state == CloudJobState.RUNNING }
        val waiting = active.size - processing
        return "Cloud: " + listOfNotNull(
            "$waiting waiting".takeIf { waiting > 0 },
            "$processing processing".takeIf { processing > 0 },
        ).joinToString(", ")
    }

    /** The month's committed spend (recorded costs plus estimates of jobs at RunPod). */
    fun committedThisMonthMicroUsd(): Long = CloudBudget.committedMicroUsd(
        jobs, deps.monthStartMs(now), deps.settings.snapshot().pricePerSecondMicroUsd
    )

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────

    /** The screens opened: show the stored jobs even while the feature is off. */
    suspend fun loadForDisplay() {
        jobsLock.withLock { loadLocked() }
    }

    /**
     * The app came to the foreground or went away. While visible and switched on, a light pass runs at once and
     * every 15 s while something can change; nothing at all runs (no file read) while the feature is off.
     */
    fun setAppVisible(isVisible: Boolean) {
        visible = isVisible
        if (!isVisible) {
            pollLoop?.cancel()
            pollLoop = null
            return
        }
        ensurePollLoop(requestWorker = true)
    }

    /** Starts the foreground poll loop while the app is visible and the feature is on (one loop at a time). */
    private fun ensurePollLoop(requestWorker: Boolean) {
        if (!visible || !deps.settings.snapshot().enabled || pollLoop?.isActive == true) return
        pollLoop = deps.scope.launch {
            var first = true
            while (isActive && visible) {
                val outcome = foregroundPass()
                // Preparing and uploading are the worker's (they can take minutes); a light pass only asks for it.
                if ((first && requestWorker || !first) && outcome?.workBeforeSubmit == true) deps.scheduler.requestPass()
                first = false
                if (!needsPolling()) break
                delay(CloudTiming.STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    /** Something can change without the person: jobs at RunPod, a batch gate, a retry wait, results to collect. */
    private fun needsPolling(): Boolean = jobs.any { record ->
        record.state.isAtRunPod || record.state == CloudJobState.UPLOADED || record.state == CloudJobState.RESULTS_READY ||
            (record.state.isPending && (record.nextAttemptAtMs ?: 0) > 0)
    }

    /** A light pass unless one is already running (then that one does the work). Null when skipped. */
    suspend fun foregroundPass(): CloudPassOutcome? {
        if (!passLock.tryLock()) return null
        return try {
            pass(PassMode.LIGHT, deadlineMs = now + 60_000)
        } finally {
            passLock.unlock()
        }
    }

    /** The WorkManager worker's pass: everything due, until [deadlineMs]. Schedules what comes next. */
    suspend fun workerPass(deadlineMs: Long): CloudPassOutcome {
        val outcome = passLock.withLock { pass(PassMode.FULL, deadlineMs) }
        schedule(outcome)
        // Submitted jobs answer within seconds to minutes: while the app is open, watch them every 15 s.
        if (outcome.pending) ensurePollLoop(requestWorker = false)
        return outcome
    }

    private fun schedule(outcome: CloudPassOutcome) {
        val scheduler = deps.scheduler
        if (!outcome.pending) {
            scheduler.cancelWatch()
            return
        }
        scheduler.ensureWatch()
        // Blocked passes wait for the person (the periodic watch looks again); never loop on them.
        if (outcome.blocked) return
        // Work left when the pass ran out of time continues in the next one. Short waits (a batch gate, the first
        // retry) are slept through by the worker itself; longer ones are the periodic watch's.
        if (outcome.workBeforeSubmit) scheduler.requestPass()
        if (outcome.waitingForUnmetered) scheduler.requestUnmeteredPass()
    }

    // ─── The person's actions ───────────────────────────────────────────────────────────────

    /**
     * What sending [songs] would do (the confirm sheet). [onlyMissing] keeps the songs that lack that output (the Add
     * menu's filters); gathering stops once a full batch of such songs is found, so a large library isn't read end
     * to end.
     */
    suspend fun preview(
        songs: List<CloudSong>,
        title: String,
        onlyMissing: CloudTask? = null,
        replaceUserSynced: Boolean = false,
    ): CloudBatchPreview {
        loadForDisplay()
        val host = deps.host
        val pending = jobs.filter { it.state.isPending }.map { it.songId }.toSet()
        val facts = mutableListOf<CloudSongFacts>()
        val byId = LinkedHashMap<String, CloudSong>()
        for (song in songs) {
            if (byId.containsKey(song.id)) continue
            if (onlyMissing != null && facts.size >= CloudLimits.MAX_SONGS_PER_BATCH) break
            val lyrics = host.lyricsFacts(song)
            val instrumental = host.hasInstrumental(song.id)
            if (onlyMissing == CloudTask.LYRICS &&
                (lyrics.state == CloudLyricsState.WORD_SYNCED || lyrics.state == CloudLyricsState.USER_SYNCED)
            ) continue
            if (onlyMissing == CloudTask.INSTRUMENTAL && instrumental) continue
            if (onlyMissing != null && song.id in pending) continue
            byId[song.id] = song
            facts += CloudSongFacts(song.id, song.durationMs, instrumental, lyrics.state, song.hasAudioSource,
                song.isStreamed, song.id in pending)
        }
        val settings = deps.settings.snapshot()
        val selection = CloudSelector.select(facts, settings.selectionOptions.copy(replaceUserSynced = replaceUserSynced))
        val estimate = CloudBatchEstimate.make(selection.plans, settings.quality, settings.pricePerSecondMicroUsd,
            committedThisMonthMicroUsd(), settings.monthlyCapMicroUsd)
        return CloudBatchPreview(
            id = deps.newJobKey(), title = title, songs = selection.plans.mapNotNull { byId[it.songId] },
            plans = selection.plans, skipped = selection.skipCounts, estimate = estimate,
            replaceUserSynced = replaceUserSynced,
        )
    }

    /** The confirm sheet for one of the queue's Add choices (null when there is no current song). */
    suspend fun preview(kind: CloudBatchKind, currentSong: CloudSong?, title: String): CloudBatchPreview? = when (kind) {
        CloudBatchKind.CURRENT -> currentSong?.let { preview(listOf(it), title) }
        CloudBatchKind.MISSING_LYRICS -> preview(deps.host.librarySongs(), title, onlyMissing = CloudTask.LYRICS)
        CloudBatchKind.MISSING_INSTRUMENTAL -> preview(deps.host.librarySongs(), title, onlyMissing = CloudTask.INSTRUMENTAL)
    }

    /** The person confirmed the batch: one job per song, then everything runs on its own. */
    suspend fun send(preview: CloudBatchPreview): Int {
        val settings = deps.settings.snapshot()
        if (!settings.enabled || preview.isEmpty || !preview.estimate.fitsCap) return 0
        val byId = preview.songs.associateBy { it.id }
        val time = now
        var added = 0
        mutateJobs { list ->
            val out = list.toMutableList()
            for (plan in preview.plans) {
                val song = byId[plan.songId] ?: continue
                if (out.any { it.songId == song.id && it.state.isPending }) continue
                out += CloudJobRecord(
                    jobKey = CloudKeys.jobKey(deps.newJobKey()), songId = song.id, title = song.title,
                    artist = song.artist, songDurationMs = song.durationMs, batchId = preview.id,
                    isStreamed = plan.isStreamed, tasks = plan.tasks, lyricsMode = plan.lyricsMode,
                    quality = CloudSelector.quality(settings.quality, plan.durationMs),
                    replaceUserSynced = preview.replaceUserSynced, createdAtMs = time,
                )
                added++
            }
            out
        }
        deps.scheduler.requestPass()
        deps.scheduler.ensureWatch()
        return added
    }

    /** Stops a job wherever it is: its transfer, RunPod, and its objects in the bucket. */
    suspend fun cancel(jobKey: String) {
        val record = jobsLock.withLock { loadLocked(); job(jobKey) } ?: return
        if (record.state.isFinished) return
        transfersInFlight.remove(jobKey)?.cancel()
        update(jobKey) { it.on(CloudJobEvent.CANCELLED, now) }
        val clients = currentClients()
        val runpodId = record.runpodJobId
        if (record.state.isAtRunPod && runpodId != null && clients != null) {
            runCatching { clients.runpod.cancel(runpodId) }
        }
        cleanUpLocal(jobKey)
        clients?.let { deleteRemote(CloudJobBuilder.objectKeys(record), it) }
    }

    /** Starts a failed, cancelled or expired job again from the upload. */
    suspend fun retry(jobKey: String) {
        update(jobKey) { record ->
            if (record.state != CloudJobState.FAILED && record.state != CloudJobState.CANCELLED &&
                record.state != CloudJobState.EXPIRED
            ) return@update record
            record.copy(attempts = 0, resubmits = 0, lastError = null, lastErrorCode = null)
                .on(CloudJobEvent.REQUEUE, now)
        }
        deps.scheduler.requestPass()
        deps.scheduler.ensureWatch()
        ensurePollLoop(requestWorker = false)
    }

    /** Removes a finished job from the list. */
    suspend fun remove(jobKey: String) {
        mutateJobs { list -> list.filterNot { it.jobKey == jobKey && it.state.isFinished } }
    }

    /** Removes every imported job. */
    suspend fun clearFinished() {
        mutateJobs { list -> list.filterNot { it.state == CloudJobState.IMPORTED } }
    }

    // ─── Test connection ────────────────────────────────────────────────────────────────────

    /** RunPod's `/health` and the bucket probe, each on its own (design §7.2). */
    suspend fun testConnection(): CloudConnectionReport = coroutineScope {
        val config = configInput(deps.settings.snapshot(), deps.settings.secrets().secrets)
        val runpod = async { CloudConnectionTest.checkRunPod(deps.makeRunPod(config)) }
        val storage = async { CloudConnectionTest.checkStorage(deps.makeObjects(config), deps.newJobKey()) }
        val report = CloudConnectionReport(runpod.await(), storage.await())
        if (report.allOk) {
            _state.update {
                if (it.notice == CloudNotice.RUNPOD_KEY_REFUSED || it.notice == CloudNotice.ENDPOINT_NOT_FOUND) {
                    it.copy(notice = null)
                } else it
            }
        }
        report
    }

    /** "Run selftest (~1¢)": one cold start through `/runsync`. Keeps the endpoint's limits it reports. */
    suspend fun runSelftest(): CloudSelftestReport {
        val config = configInput(deps.settings.snapshot(), deps.settings.secrets().secrets)
        val report = CloudConnectionTest.selftestReport(deps.makeRunPod(config), deps.build)
        report.caps?.let(deps.settings::saveWorkerCaps)
        return report
    }

    // ─── The pass ───────────────────────────────────────────────────────────────────────────

    private suspend fun pass(mode: PassMode, deadlineMs: Long): CloudPassOutcome {
        jobsLock.withLock { loadLocked() }
        prune()
        val settings = deps.settings.snapshot()
        if (!settings.enabled) {
            setNotice(CloudNotice.OFF)
            return outcome(settings).copy(blocked = true)
        }
        val secrets = deps.settings.secrets()
        when {
            secrets.storageUnavailable -> {
                setNotice(CloudNotice.SECURE_STORAGE_UNAVAILABLE)
                return outcome(settings).copy(blocked = true)
            }
            secrets.keysMissing -> {
                setNotice(CloudNotice.KEYS_MISSING)
                return outcome(settings).copy(blocked = true)
            }
        }
        val clients = clients(configInput(settings, secrets.secrets)) ?: run {
            setNotice(CloudNotice.NOT_CONFIGURED)
            return outcome(settings).copy(blocked = true)
        }
        _state.update {
            val transient = it.notice in setOf(CloudNotice.OFF, CloudNotice.NOT_CONFIGURED, CloudNotice.KEYS_MISSING,
                CloudNotice.SECURE_STORAGE_UNAVAILABLE, CloudNotice.RATE_LIMITED, CloudNotice.WAITING_FOR_WIFI)
            it.copy(notice = if (transient) null else it.notice, isWorking = true)
        }
        try {
            settleInterruptedWork()
            val transfersAllowed = settings.useCellular || deps.isUnmetered()
            submitReadyBatches(clients, settings)
            watchRunPod(clients)
            collectResults(clients, transfersAllowed)
            if (mode == PassMode.FULL) {
                val handled = HashSet<String>()
                while (now < deadlineMs && currentCoroutineContext().isActive) {
                    val time = now
                    val next = jobs.firstOrNull { record ->
                        record.jobKey !in handled && record.isDue(time) && isPhoneStep(record) &&
                            (transfersAllowed || !movesData(record))
                    } ?: break
                    handled += next.jobKey
                    if (next.state == CloudJobState.QUEUED) prepare(next.jobKey, settings)
                    if (transfersAllowed && job(next.jobKey)?.state == CloudJobState.UPLOADING) upload(next.jobKey, clients)
                    submitReadyBatches(clients, settings)
                    watchRunPod(clients)
                    collectResults(clients, transfersAllowed)
                }
            }
            // Jobs the watch just sent back (lost at RunPod, a worker error without a wait) go out in this pass.
            submitReadyBatches(clients, settings)
            val result = outcome(settings)
            if (result.waitingForUnmetered) setNotice(CloudNotice.WAITING_FOR_WIFI)
            return result
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    private fun outcome(settings: CloudSettingsSnapshot): CloudPassOutcome {
        val time = now
        val pending = jobs.filter { it.state.isPending }
        val transfersAllowed = settings.useCellular || deps.isUnmetered()
        val beforeSubmit = pending.any {
            it.isDue(time) && (it.state == CloudJobState.PREPARING || (isPhoneStep(it) && (transfersAllowed || !movesData(it))))
        }
        val waitingForNetwork = settings.enabled && !transfersAllowed && pending.any {
            movesData(it) || (it.state == CloudJobState.RESULTS_READY && CloudTask.INSTRUMENTAL in it.tasks &&
                !it.importedInstrumental && it.outputs?.get(INSTRUMENTAL_SLOT) != null)
        }
        val retries = pending.mapNotNull { record -> record.nextAttemptAtMs?.takeIf { it > time } }
        val gates = pending.filter { it.state == CloudJobState.UPLOADED }
            .mapNotNull { it.uploadedAtMs?.plus(CloudTiming.BATCH_GATE_MS) }
            .filter { it > time }
        return CloudPassOutcome(
            pending = pending.isNotEmpty() && settings.enabled,
            workBeforeSubmit = beforeSubmit && settings.enabled,
            waitingForUnmetered = waitingForNetwork,
            nextWakeAtMs = (retries + gates).minOrNull(),
        )
    }

    /** Steps the phone does before RunPod sees a job. */
    private fun isPhoneStep(record: CloudJobRecord): Boolean =
        record.state == CloudJobState.QUEUED || record.state == CloudJobState.UPLOADING

    /**
     * Steps that move song data over the network: the upload, and preparing a streamed song (it is downloaded
     * first). They wait for an unmetered network unless "Use mobile data" is on (design §8).
     */
    private fun movesData(record: CloudJobRecord): Boolean =
        record.state == CloudJobState.UPLOADING || (record.state == CloudJobState.QUEUED && record.isStreamed)

    /**
     * Preparing and downloading only happen inside a pass, and passes never overlap, so a job left in either state
     * was interrupted (the worker was stopped, the process was killed): it starts that step again.
     */
    private suspend fun settleInterruptedWork() {
        val time = now
        mutateJobs { list ->
            list.map { record ->
                when (record.state) {
                    CloudJobState.PREPARING -> record.on(CloudJobEvent.REQUEUE, time)
                    CloudJobState.DOWNLOADING -> record.on(CloudJobEvent.RESULTS_READY, time)
                    else -> record
                }
            }
        }
    }

    // ─── Prepare and upload ─────────────────────────────────────────────────────────────────

    private suspend fun prepare(jobKey: String, settings: CloudSettingsSnapshot) {
        update(jobKey) { it.on(CloudJobEvent.PREPARE_STARTED, now) }
        val record = job(jobKey)?.takeIf { it.state == CloudJobState.PREPARING } ?: return
        val song = deps.host.song(record.songId)
        if (song == null) {
            fail(jobKey, "This song is no longer in your library.", null, retryable = false)
            return
        }
        try {
            val source = deps.host.audioSource(song)
            val identity = if (record.isStreamed) deps.host.streamIdentity(song) else null
            // A streamed song's download always goes up decoded as FLAC (design §7.3): the worker then sees exactly
            // the samples this phone plays. Android decodes every upload anyway; the flag keeps the intent explicit.
            val prepared = deps.preparer.prepare(source, jobKey, forceDecode = record.isStreamed)
            if (job(jobKey)?.state != CloudJobState.PREPARING) {
                deps.preparer.removeUpload(jobKey)
                return
            }
            val refusal = when {
                prepared.bytes > CloudLimits.MAX_INPUT_BYTES ->
                    "The prepared file is ${(prepared.bytes + 524_288) / 1_048_576} MB, over the cloud's 160 MB limit."
                prepared.durationMs > CloudLimits.MAX_DURATION_MS -> "This song is longer than 15 minutes."
                else -> CloudLimits.workerRefusal(prepared.bytes, prepared.durationMs, settings.workerCaps)
            }
            if (refusal != null) {
                // Stop before the upload and the GPU time.
                deps.preparer.removeUpload(jobKey)
                update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                fail(jobKey, refusal, null, retryable = false)
                return
            }
            update(jobKey) { r ->
                r.copy(
                    inputExt = prepared.ext, sha256 = prepared.sha256.lowercase(), bytes = prepared.bytes,
                    durationMs = prepared.durationMs, decodedFrames = prepared.frames, sampleRate = prepared.sampleRate,
                    videoId = identity ?: r.videoId,
                    // The worker's AAC encoder stops at 96 kHz; a hi-res upload asks for FLAC back.
                    outputCodec = if (prepared.sampleRate > CloudLimits.MAX_AAC_SAMPLE_RATE) CloudOutputCodec.FLAC else r.outputCodec,
                ).on(CloudJobEvent.PREPARED, now)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Couldn't prepare this song."
            val decodeFailure = (error as? CloudPrepareException)?.isDecodeFailure == true
            deps.preparer.removeUpload(jobKey)
            update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
            fail(jobKey, CloudRedaction.redact(message), null, retryable = !decodeFailure)
        }
    }

    /** The song upload: a presigned PUT valid 24 h, signed right before it starts. */
    private suspend fun upload(jobKey: String, clients: Clients) {
        val record = job(jobKey)?.takeIf { it.state == CloudJobState.UPLOADING } ?: return
        val ext = record.inputExt
        val file = ext?.let { deps.preparer.uploadFile(jobKey, it) }
        if (ext == null || file == null) {
            // The prepared file is gone (storage was cleaned): prepare it again.
            update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
            return
        }
        val url = CloudJobBuilder.uploadUrl(jobKey, ext) { method, key, seconds ->
            clients.objects.presignedUrl(method, key, seconds)
        }
        if (url == null) {
            setNotice(CloudNotice.NOT_CONFIGURED)
            return
        }
        val result = transfer(jobKey) {
            deps.transfers.upload(file, url, CloudKeys.contentType(ext)) { progress(jobKey, it) }
        }
        clearProgress(jobKey)
        if (job(jobKey)?.state != CloudJobState.UPLOADING || result == null) return
        when (result) {
            CloudTransferResult.Ok -> update(jobKey) { it.on(CloudJobEvent.UPLOAD_FINISHED, now) }
            is CloudTransferResult.Failed ->
                fail(jobKey, "Upload: ${CloudRedaction.redact(result.message)}", null, retryable = true)
        }
    }

    /** Runs one transfer so [cancel] can stop it; null when it was cancelled that way. */
    private suspend fun transfer(jobKey: String, block: suspend () -> CloudTransferResult): CloudTransferResult? =
        coroutineScope {
            val running = async {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    CloudTransferResult.Failed(error.message ?: "network error")
                }
            }
            transfersInFlight[jobKey] = running
            try {
                running.await()
            } catch (error: CancellationException) {
                if (running.isCancelled && currentCoroutineContext().isActive) null else throw error
            } finally {
                transfersInFlight.remove(jobKey, running)
            }
        }

    // ─── Submit ─────────────────────────────────────────────────────────────────────────────

    private suspend fun submitReadyBatches(clients: Clients, settings: CloudSettingsSnapshot) {
        val time = now
        val open = jobs.filter { !it.state.isFinished }
        val ready = mutableListOf<String>()
        for ((_, members) in open.groupBy { it.batchId }) {
            val uploaded = members.filter { it.state == CloudJobState.UPLOADED }
            if (uploaded.isEmpty()) continue
            val pendingUploads = members.count {
                it.state == CloudJobState.QUEUED || it.state == CloudJobState.PREPARING || it.state == CloudJobState.UPLOADING
            }
            val firstDone = uploaded.mapNotNull { it.uploadedAtMs }.minOrNull()
            ready += if (CloudBatchGate.shouldSubmit(pendingUploads, uploaded.size, firstDone, time)) {
                uploaded.map { it.jobKey }
            } else {
                // A job sent before (lost at RunPod, a retry) doesn't wait for the rest of its batch.
                uploaded.filter { it.submittedAtMs != null }.map { it.jobKey }
            }
        }
        val order = jobs.withIndex().associate { (index, record) -> record.jobKey to index }
        for (jobKey in ready.sortedBy { order[it] ?: 0 }) {
            val record = job(jobKey) ?: continue
            if (record.state != CloudJobState.UPLOADED || !record.isDue(time)) continue
            if (CloudRetention.inputTooOldToSubmit(record.uploadedAtMs, time)) {
                update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                continue
            }
            val price = settings.pricePerSecondMicroUsd
            val committed = CloudBudget.committedMicroUsd(jobs, deps.monthStartMs(time), price)
            val estimate = CloudCost.estimatedSeconds(record.plan, record.quality) * price
            if (!CloudBudget.allows(estimate, settings.monthlyCapMicroUsd, committed)) {
                setNotice(CloudNotice.CAP_REACHED)
                return
            }
            clearNotice(CloudNotice.CAP_REACHED)
            var lyrics: CloudLyricsRequest? = null
            if (CloudTask.LYRICS in record.tasks) {
                val song = deps.host.song(record.songId)
                val facts = song?.let { deps.host.lyricsFacts(it) } ?: CloudLyricsFacts.NONE
                lyrics = CloudJobBuilder.lyricsRequest(
                    mode = record.lyricsMode ?: CloudLyricsMode.AUTO, lines = facts.lines,
                    hasLineTimes = facts.hasLineTimes, language = record.language ?: facts.language,
                    lyricsReferenceDurationMs = facts.referenceDurationMs, audioDurationMs = record.durationMs ?: 0,
                )
            }
            val request = CloudJobBuilder.request(record, deps.build, lyrics) { method, key, seconds ->
                clients.objects.presignedUrl(method, key, seconds)
            }
            if (request == null) {
                update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                fail(jobKey, "This job lost its upload details; it will be uploaded again.", null, retryable = true)
                continue
            }
            if (record.submittedAtMs != null) {
                // Sent before (a worker error, a lost job, a re-upload, Retry): the earlier run's manifest and guard
                // marker are still in the bucket, and the worker doesn't clear them. Left there, the next listing would
                // read the old error as this run's answer and send the job again and again (iOS review fix).
                try {
                    clients.objects.delete(CloudKeys.manifest(jobKey))
                    clients.objects.delete(CloudKeys.attempt(jobKey))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(jobKey, "Couldn't clear the earlier attempt from storage; trying again soon.", null, retryable = true)
                    continue
                }
                if (job(jobKey)?.state != CloudJobState.UPLOADED) continue
            }
            try {
                val runpodJob = clients.runpod.run(request)
                if (job(jobKey)?.state != CloudJobState.UPLOADED) {
                    // Cancelled while the request was out: stop it at RunPod too.
                    runCatching { clients.runpod.cancel(runpodJob.id) }
                    continue
                }
                // The id is on disk before the next POST (design §7.4): update() saves.
                update(jobKey) {
                    it.copy(runpodJobId = runpodJob.id, lastError = null, lastErrorCode = null, nextAttemptAtMs = null)
                        .on(CloudJobEvent.SUBMITTED, now)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RunPodError) {
                when (error) {
                    is RunPodError.Unauthorized -> { setNotice(CloudNotice.RUNPOD_KEY_REFUSED); return }
                    is RunPodError.EndpointNotFound -> { setNotice(CloudNotice.ENDPOINT_NOT_FOUND); return }
                    is RunPodError.RateLimited -> { setNotice(CloudNotice.RATE_LIMITED); return }
                    is RunPodError.NotConfigured -> { setNotice(CloudNotice.NOT_CONFIGURED); return }
                    // A lost response may still have queued the job; the worker's guard makes a repeat harmless.
                    else -> fail(jobKey, error.message ?: "RunPod error", null, retryable = true)
                }
            } catch (error: Exception) {
                fail(jobKey, CloudRedaction.redact(error.message ?: "Couldn't reach RunPod"), null, retryable = true)
            }
        }
    }

    // ─── Watch RunPod ───────────────────────────────────────────────────────────────────────

    private suspend fun watchRunPod(clients: Clients) {
        val time = now
        if (jobs.none { it.state.isAtRunPod }) return
        // One listing of out/ finds finished (and started) jobs, even after /status has expired.
        if (time - lastListAtMs >= CloudTiming.STATUS_POLL_INTERVAL_MS) {
            lastListAtMs = time
            val listing = try {
                clients.objects.list("out/", "/")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            if (listing != null) {
                val folders = listing.commonPrefixes.mapNotNull(CloudKeys::jobKeyFromOutputKey).toSet()
                for (record in jobs.filter { it.state.isAtRunPod && it.jobKey in folders }) {
                    if (takeManifestIfPresent(record.jobKey, clients)) continue
                    // attempt.json is there but no manifest yet: the worker has the job.
                    update(record.jobKey) { it.on(CloudJobEvent.STARTED, now) }
                }
            }
        }
        // /status for running jobs and the two oldest waiting ones, each at most every 15 s.
        val running = jobs.filter { it.state == CloudJobState.RUNNING }
        val waiting = jobs.filter { it.state == CloudJobState.SUBMITTED }.sortedBy { it.submittedAtMs ?: 0 }.take(2)
        for (record in running + waiting) {
            if (time - (record.lastPolledAtMs ?: 0) < CloudTiming.STATUS_POLL_INTERVAL_MS) continue
            if (job(record.jobKey)?.state?.isAtRunPod != true) continue
            val jobId = record.runpodJobId
            if (jobId == null) {
                update(record.jobKey) { it.on(CloudJobEvent.RESUBMIT, now) }
                continue
            }
            update(record.jobKey) { it.copy(lastPolledAtMs = time) }
            try {
                applyStatus(clients.runpod.status(jobId), record.jobKey, clients)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RunPodError.JobNotFound) {
                handleMissingAtRunPod(record.jobKey, clients)
            } catch (error: RunPodError.Unauthorized) {
                setNotice(CloudNotice.RUNPOD_KEY_REFUSED)
                break
            } catch (error: Exception) {
                // Offline or a RunPod hiccup: the next pass asks again.
            }
        }
        checkForPausedEndpoint(clients)
    }

    private suspend fun applyStatus(status: RunPodJob, jobKey: String, clients: Clients) {
        when (status.typedStatus) {
            RunPodJobStatus.IN_QUEUE, null -> Unit
            RunPodJobStatus.IN_PROGRESS -> update(jobKey) { record ->
                val progress = status.progress
                record.on(CloudJobEvent.STARTED, now).let {
                    if (progress != null) it.copy(progressStage = progress.stage, progressPercent = progress.percent) else it
                }
            }
            RunPodJobStatus.COMPLETED -> {
                val result = status.result
                if (result != null && result.jobKey == jobKey) {
                    take(result, jobKey, clients)
                } else if (!takeManifestIfPresent(jobKey, clients)) {
                    retryAtRunPod(jobKey, "RunPod finished the job without a result.")
                }
            }
            RunPodJobStatus.FAILED, RunPodJobStatus.TIMED_OUT, RunPodJobStatus.CANCELLED -> {
                // The manifest in R2 has the details when the worker wrote one.
                if (takeManifestIfPresent(jobKey, clients)) return
                val code = status.errorCode
                if (code != null) {
                    handleError(code, jobKey)
                } else {
                    retryAtRunPod(jobKey, "RunPod stopped the job (${status.status.lowercase()}).")
                }
            }
        }
    }

    /**
     * `/status` 404: retention passed or it never existed (design §2.5). R2 decides; a job past its ttl with no
     * manifest is lost and goes out once more with the same key.
     */
    private suspend fun handleMissingAtRunPod(jobKey: String, clients: Clients) {
        if (takeManifestIfPresent(jobKey, clients)) return
        val record = job(jobKey) ?: return
        val submitted = record.submittedAtMs ?: return
        if (!CloudRetention.isPastTtl(submitted, now)) return
        if (record.resubmits < 1) {
            update(jobKey) { it.copy(resubmits = it.resubmits + 1).on(CloudJobEvent.RESUBMIT, now) }
        } else {
            val keys = CloudJobBuilder.objectKeys(record)
            update(jobKey) { it.copy(lastError = "RunPod lost this job twice.").on(CloudJobEvent.EXPIRED, now) }
            cleanUpLocal(jobKey)
            deleteRemote(keys, clients)
        }
    }

    private suspend fun takeManifestIfPresent(jobKey: String, clients: Clients): Boolean {
        val data = try {
            clients.objects.get(CloudKeys.manifest(jobKey))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return false
        val result = runCatching { CloudJson.decodeFromString(CloudJobResult.serializer(), data.toString(Charsets.UTF_8)) }
            .getOrNull() ?: return false
        // Absent, unreadable, or an earlier attempt's (another input): this upload's job goes on.
        if (result.jobKey != jobKey || !CloudImportCheck.manifestDescribesUpload(result, job(jobKey)?.sha256)) return false
        take(result, jobKey, clients)
        return true
    }

    private suspend fun take(result: CloudJobResult, jobKey: String, clients: Clients) {
        val record = job(jobKey) ?: return
        if (!record.state.isAtRunPod && record.state != CloudJobState.UPLOADED) return
        val price = deps.settings.snapshot().pricePerSecondMicroUsd
        update(jobKey) { it.takingResult(result, price, now) }
        if (result.hasResults) {
            update(jobKey) { it.copy(lastError = null, lastErrorCode = null).on(CloudJobEvent.RESULTS_READY, now) }
        } else {
            handleError(result.errorCode ?: CloudErrorCode.INTERNAL, jobKey)
        }
    }

    /** A worker error (design §2.3): upload again, send again, or stop. */
    private suspend fun handleError(code: CloudErrorCode, jobKey: String) {
        when {
            code.needsReupload -> {
                update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                fail(jobKey, code.message, code.wire, retryable = true)
            }
            code.isRetryable -> {
                update(jobKey) { it.on(CloudJobEvent.RESUBMIT, now) }
                fail(jobKey, code.message, code.wire, retryable = true)
            }
            else -> fail(jobKey, code.message, code.wire, retryable = false)
        }
    }

    private suspend fun retryAtRunPod(jobKey: String, message: String) {
        update(jobKey) { it.on(CloudJobEvent.RESUBMIT, now) }
        fail(jobKey, message, null, retryable = true)
    }

    private suspend fun checkForPausedEndpoint(clients: Clients) {
        val time = now
        val oldest = jobs.filter { it.state == CloudJobState.SUBMITTED }.mapNotNull { it.submittedAtMs }.minOrNull()
        if (oldest == null || time - oldest <= CloudTiming.PAUSED_ENDPOINT_AFTER_MS || time - lastHealthAtMs <= 5 * 60_000) {
            if (oldest == null) clearNotice(CloudNotice.ENDPOINT_PAUSED)
            return
        }
        lastHealthAtMs = time
        val health = try {
            clients.runpod.health()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return
        }
        if (CloudEndpointWatch.looksPaused(oldest, time, health)) setNotice(CloudNotice.ENDPOINT_PAUSED)
        else clearNotice(CloudNotice.ENDPOINT_PAUSED)
    }

    // ─── Collect and import ─────────────────────────────────────────────────────────────────

    private suspend fun collectResults(clients: Clients, transfersAllowed: Boolean) {
        val time = now
        for (record in jobs.filter { it.state == CloudJobState.RESULTS_READY && it.isDue(time) }) {
            collect(record.jobKey, clients, transfersAllowed)
        }
    }

    /**
     * Lyrics first (KB-sized, a normal request), so they show even while the instrumental waits for Wi-Fi; then the
     * instrumental download, checked and moved into the instrumental store.
     */
    private suspend fun collect(jobKey: String, clients: Clients, transfersAllowed: Boolean) {
        val record = job(jobKey)?.takeIf { it.state == CloudJobState.RESULTS_READY } ?: return
        val song = deps.host.song(record.songId)
        if (song == null) {
            val keys = CloudJobBuilder.objectKeys(record)
            fail(jobKey, "This song is no longer in your library.", null, retryable = false)
            deleteRemote(keys, clients)
            return
        }
        // A streamed song's results belong to the video that was uploaded (design §7.3).
        val uploadedVideo = record.videoId
        if (uploadedVideo != null) {
            val current = deps.host.streamIdentity(song)
            if (current != null && current != uploadedVideo) {
                val keys = CloudJobBuilder.objectKeys(record)
                fail(jobKey, "This song now plays a different YouTube video than the one processed. Process it again.",
                    null, retryable = false)
                deleteRemote(keys, clients)
                return
            }
        }
        val lyricsKey = record.lyricsKey
        if (CloudTask.LYRICS in record.tasks && !record.importedLyrics && lyricsKey != null) {
            importLyrics(jobKey, lyricsKey, song, clients)
        }
        val current = job(jobKey)?.takeIf { it.state == CloudJobState.RESULTS_READY } ?: return
        val output = current.outputs?.get(INSTRUMENTAL_SLOT)
        if (output != null && CloudTask.INSTRUMENTAL in current.tasks && !current.importedInstrumental) {
            if (!output.key.startsWith(CloudKeys.outputPrefix(jobKey)) || ".." in output.key) {
                fail(jobKey, "The result named a file outside its folder.", null, retryable = false)
                return
            }
            if (!transfersAllowed) return // waits for an unmetered network
            val url = clients.objects.presignedUrl("GET", output.key, CloudTiming.UPLOAD_PRESIGN_SECONDS) ?: return
            update(jobKey) { it.on(CloudJobEvent.DOWNLOAD_STARTED, now) }
            val ext = if (current.outputCodec == CloudOutputCodec.FLAC) "flac" else "m4a"
            deps.stagingDir.mkdirs()
            val staged = File(deps.stagingDir, "$jobKey.$ext")
            val result = transfer(jobKey) { deps.transfers.download(url, staged) { progress(jobKey, it) } }
            clearProgress(jobKey)
            if (result == null || job(jobKey)?.state != CloudJobState.DOWNLOADING) {
                staged.delete()
                return
            }
            when (result) {
                CloudTransferResult.Ok -> importInstrumental(jobKey, output, staged, clients)
                is CloudTransferResult.Failed -> {
                    staged.delete()
                    if (result.status == 404) {
                        // The lifecycle (or someone) removed the result before it was fetched: start the job over.
                        update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                        fail(jobKey, "The result was gone from storage; the song will be processed again.", null, retryable = true)
                    } else {
                        update(jobKey) { it.on(CloudJobEvent.RESULTS_READY, now) }
                        fail(jobKey, "Download: ${CloudRedaction.redact(result.message)}", null, retryable = true)
                    }
                }
            }
            return
        }
        finishIfDone(jobKey, clients)
    }

    private suspend fun importLyrics(jobKey: String, key: String, song: CloudSong, clients: Clients) {
        val record = job(jobKey) ?: return
        if (!key.startsWith(CloudKeys.outputPrefix(jobKey)) || ".." in key) {
            update(jobKey) { it.copy(importedLyrics = true) }
            return
        }
        try {
            val data = clients.objects.get(key)
            if (data == null) {
                update(jobKey) { it.copy(importedLyrics = true, warnings = it.warnings + "The lyrics file was gone from storage.") }
                return
            }
            val summary = record.lyricsFile
            if (summary != null) {
                val bytesOk = summary.bytes?.let { it == data.size.toLong() } ?: true
                val shaOk = summary.sha256?.let { it.equals(CloudDigest.sha256Hex(data), ignoreCase = true) } ?: true
                if (!bytesOk || !shaOk) {
                    fail(jobKey, "The lyrics file arrived damaged; it will be fetched again.", null, retryable = true)
                    return
                }
            }
            val cloud = try {
                CloudJson.decodeFromString(CloudLyricsDocument.serializer(), data.toString(Charsets.UTF_8))
            } catch (error: Exception) {
                update(jobKey) { it.copy(importedLyrics = true, warnings = it.warnings + "The cloud lyrics file couldn't be read.") }
                return
            }
            val duration = if (song.durationMs > 0) song.durationMs else record.durationMs ?: 0
            val doc = CloudLyrics.lyricsDoc(cloud, duration, song.title, song.artist, song.album)
            val outcome = if (doc != null) deps.host.saveLyrics(doc, song, record.replaceUserSynced) else CloudLyricsSaveOutcome.UNUSABLE
            update(jobKey) { r ->
                val warning = when (outcome) {
                    CloudLyricsSaveOutcome.SAVED -> null
                    CloudLyricsSaveOutcome.KEPT_BETTER -> "Kept the lyrics you already had (they were as good or better)."
                    CloudLyricsSaveOutcome.KEPT_USER_SYNCED -> "Kept the lyrics you synced yourself."
                    CloudLyricsSaveOutcome.UNUSABLE -> "The cloud lyrics had no usable timing."
                }
                r.copy(importedLyrics = true, warnings = if (warning != null) r.warnings + warning else r.warnings)
            }
            if (outcome == CloudLyricsSaveOutcome.SAVED) deps.host.lyricsImported(song.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(jobKey, "Lyrics: ${CloudRedaction.redact(error.message ?: "couldn't fetch them")}", null, retryable = true)
        }
    }

    /**
     * Verifies a downloaded instrumental (size, SHA-256, and the worker's sample count against the frames this phone
     * uploaded) and moves it into the instrumental store (design §7.5).
     *
     * Unlike iOS, the phone doesn't decode the AAC result to count it: Android's player (ExoPlayer) honours the MP4
     * edit list that removes the encoder's priming, exactly like the worker's ffmpeg does when it reports `samples`,
     * while a raw `MediaCodec` decode would count the priming and flag every AAC result. The size and SHA-256 prove
     * the file is the one the worker counted.
     */
    private suspend fun importInstrumental(jobKey: String, output: CloudOutputFile, staged: File, clients: Clients) {
        val record = job(jobKey) ?: run { staged.delete(); return }
        try {
            val (sha, bytes) = CloudDigest.file(staged)
            if (!CloudImportCheck.matches(output.bytes, output.sha256, bytes, sha)) {
                staged.delete()
                update(jobKey) { it.on(CloudJobEvent.RESULTS_READY, now) }
                fail(jobKey, "The downloaded instrumental was damaged; it will be fetched again.", null, retryable = true)
                return
            }
            if (!samplesLineUp(record, output)) {
                staged.delete()
                redoAsFlacOrFail(jobKey, clients)
                return
            }
            if (job(jobKey)?.state != CloudJobState.DOWNLOADING) {
                staged.delete()
                return
            }
            deps.host.installInstrumental(staged, record.songId, flac = record.outputCodec == CloudOutputCodec.FLAC)
            update(jobKey) { it.copy(importedInstrumental = true) }
            deps.host.instrumentalImported(record.songId)
            finishIfDone(jobKey, clients)
        } catch (error: CancellationException) {
            staged.delete()
            throw error
        } catch (error: Exception) {
            staged.delete()
            update(jobKey) { it.on(CloudJobEvent.RESULTS_READY, now) }
            fail(jobKey, "Import: ${CloudRedaction.redact(error.message ?: "couldn't save the instrumental")}", null, retryable = true)
        }
    }

    /**
     * An AAC result that doesn't line up is asked for once more as FLAC (no encoder delay); a FLAC one that doesn't
     * line up stops with a message.
     */
    private suspend fun redoAsFlacOrFail(jobKey: String, clients: Clients) {
        val record = job(jobKey) ?: return
        val keys = CloudJobBuilder.objectKeys(record)
        if (record.outputCodec == CloudOutputCodec.AAC && !record.flacRedone) {
            update(jobKey) { r ->
                r.copy(
                    flacRedone = true, outputCodec = CloudOutputCodec.FLAC,
                    tasks = if (r.importedLyrics) r.tasks - CloudTask.LYRICS else r.tasks,
                    outputs = null, lyricsKey = null, lyricsFile = null,
                    warnings = r.warnings + "The AAC instrumental didn't line up with the song; asking for FLAC instead.",
                ).on(CloudJobEvent.REQUEUE, now)
            }
            // The worker deleted the input after its result: the redo uploads again. Old outputs go now.
            deleteRemote(keys, clients)
            deps.scheduler.requestPass()
        } else {
            fail(jobKey, "The cloud instrumental doesn't line up with this song, so it wasn't used.", null, retryable = false)
            cleanUpLocal(jobKey)
            deleteRemote(keys, clients)
        }
    }

    private suspend fun finishIfDone(jobKey: String, clients: Clients) {
        val record = job(jobKey) ?: return
        if (record.state != CloudJobState.RESULTS_READY && record.state != CloudJobState.DOWNLOADING) return
        if (!record.isFullyImported) return
        val keys = CloudJobBuilder.objectKeys(record)
        update(jobKey) { it.on(CloudJobEvent.IMPORTED, now) }
        cleanUpLocal(jobKey)
        deleteRemote(keys, clients)
    }

    // ─── Failures, clients, persistence ────────────────────────────────────────────────────

    /** Records a failure: retryable ones wait on the backoff ladder; the rest (and exhausted ones) stop as Failed. */
    private suspend fun fail(jobKey: String, message: String, code: String?, retryable: Boolean) {
        val time = now
        update(jobKey) { it.recordingFailure(message, code, retryable, time) }
        val record = job(jobKey) ?: return
        if (record.state == CloudJobState.FAILED) {
            cleanUpLocal(jobKey)
            currentClients()?.let { deleteRemote(CloudJobBuilder.objectKeys(record), it) }
        }
    }

    private fun cleanUpLocal(jobKey: String) {
        deps.preparer.removeUpload(jobKey)
        deps.stagingDir.listFiles()?.filter { it.name.startsWith("$jobKey.") }?.forEach { it.delete() }
    }

    /** Best effort: the lifecycle rules remove anything left (7 d `in/`, 30 d `out/`). */
    private suspend fun deleteRemote(keys: List<String>, clients: Clients) {
        for (key in keys) {
            try {
                clients.objects.delete(key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The lifecycle rules are the backstop.
            }
        }
    }

    private fun configInput(settings: CloudSettingsSnapshot, secrets: CloudSecrets) = CloudConfigInput(
        endpointId = settings.endpointId, runpodKey = secrets.runpodKey, endpoint = settings.r2Endpoint,
        bucket = settings.bucket, accessKeyId = secrets.accessKeyId, secretAccessKey = secrets.secretAccessKey,
    )

    private fun clients(config: CloudConfigInput): Clients? {
        clientsCache?.let { if (clientsKey == config) return it }
        if (!config.isComplete) {
            clientsCache = null
            clientsKey = null
            return null
        }
        val runpod = deps.makeRunPod(config) ?: return null
        val objects = deps.makeObjects(config) ?: return null
        return Clients(runpod, objects).also {
            clientsCache = it
            clientsKey = config
        }
    }

    private fun currentClients(): Clients? = clientsCache

    private fun progress(jobKey: String, fraction: Float) {
        _state.update { it.copy(transferProgress = it.transferProgress + (jobKey to fraction.coerceIn(0f, 1f))) }
    }

    private fun clearProgress(jobKey: String) {
        _state.update { it.copy(transferProgress = it.transferProgress - jobKey) }
    }

    private fun setNotice(notice: CloudNotice) {
        _state.update { it.copy(notice = notice) }
    }

    private fun clearNotice(notice: CloudNotice) {
        _state.update { if (it.notice == notice) it.copy(notice = null) else it }
    }

    /** Reads the stored list once. Nothing is ever saved before this ran: an unread list is empty. */
    private suspend fun loadLocked() {
        if (loaded) return
        val stored = deps.store.load()
        // Jobs added before the file was read (none in practice) stay after the stored ones.
        jobs = stored + jobs.filter { added -> stored.none { it.jobKey == added.jobKey } }
        loaded = true
        publish()
    }

    private suspend fun mutateJobs(change: (List<CloudJobRecord>) -> List<CloudJobRecord>): List<CloudJobRecord> =
        jobsLock.withLock {
            loadLocked()
            val next = change(jobs)
            if (next != jobs) {
                jobs = next
                deps.store.save(next)
                publish()
            }
            next
        }

    /** Changes one job and saves the list at once. */
    private suspend fun update(jobKey: String, change: (CloudJobRecord) -> CloudJobRecord) {
        mutateJobs { list -> list.map { if (it.jobKey == jobKey) change(it) else it } }
    }

    private suspend fun prune() {
        val time = now
        mutateJobs { list -> list.filterNot { CloudRetention.shouldPrune(it, time) } }
    }

    private fun publish() {
        val snapshot = jobs
        _state.update { it.copy(jobs = snapshot, isLoaded = true) }
    }

    companion object {
        const val INSTRUMENTAL_SLOT = "instrumental"

        /**
         * The result's length equals the phone's own count of the uploaded audio within ±1 AAC frame (R13), using the
         * worker's count of its output.
         */
        fun samplesLineUp(record: CloudJobRecord, output: CloudOutputFile): Boolean {
            val source = record.decodedFrames ?: return true
            val rate = record.sampleRate ?: return true
            if (source <= 0 || rate <= 0) return true
            val samples = output.samples ?: return true
            val resultRate = (output.sampleRate ?: rate).toDouble()
            return CloudImportCheck.samplesMatch(source, rate.toDouble(), samples, resultRate)
        }
    }
}
