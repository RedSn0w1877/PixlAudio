package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepare
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.worker.AutomaticStudioPolicy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** What a tap on Sing did with the cloud ([CloudStudioEngine.startSing]). */
sealed interface CloudSingStart {
    /** A new job was created for the song; its instrumental is on its way. */
    data class Started(val jobKey: String) : CloudSingStart
    /** The song already had a job on its way (an automatic one is now a Sing); this tap waits for the same result. */
    data class Joined(val jobKey: String) : CloudSingStart
    /** The cloud can't be used for this song now: the caller does it on this phone instead. */
    data class Unavailable(val reason: CloudSingUnavailable) : CloudSingStart
}

/** Why the cloud can't take a Sing (plain reasons the Sing button turns into a sentence). */
enum class CloudSingUnavailable {
    /** Cloud processing is switched off. */
    OFF,
    /** The keys or the endpoint aren't filled in (or RunPod doesn't know them). */
    NOT_CONFIGURED,
    /** The keys are gone from secure storage, or RunPod refused them. */
    KEYS_MISSING,
    /** This month's cloud budget can't take one more song. */
    CAP_REACHED,
    /** On mobile data, and "Use mobile data" is off. */
    NOT_ON_WIFI,
    /** Longer than the 15 minutes the cloud takes. */
    TOO_LONG,
    /**
     * Nothing the cloud could use: no audio on this phone to send, or the song's job on its way can't make an
     * instrumental (it was started without one and RunPod already has it).
     */
    NO_AUDIO,
    /** The song already has an instrumental: no job is needed. */
    ALREADY_HAS_INSTRUMENTAL,
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
 * - Nothing is sent while the consent switch is off. The cloud is never a silent fallback: it runs for a batch the
 *   person started, for a tap on Sing (which is consent for that one song), and, only when the person turned on
 *   "Prepare songs ahead in the cloud", for automatic jobs ([CloudJobOrigin.AUTO]) inside their own budget, on
 *   Wi-Fi and a charger. That switch is the one opt-in exception.
 * - A Sing (or a one-song "Process in cloud") goes first: the phone prepares and uploads it before anything else, and
 *   when it is ready to submit the automatic and batch jobs still waiting in RunPod's queue step aside and go out
 *   again right behind it ([CloudPriorityLane]; a job that already began is never stopped).
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
    private var lastUrgentRequestAtMs = 0L
    /** Wakes the foreground loop out of its wait: a priority job arrived, so its rhythm just got faster. */
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

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

    /**
     * The month's committed spend (recorded costs plus estimates of jobs at RunPod, and what jobs since taken off the
     * list spent this month).
     */
    fun committedThisMonthMicroUsd(): Long {
        val monthStart = deps.monthStartMs(now)
        return CloudBudget.committedMicroUsd(jobs, monthStart, deps.settings.snapshot().effectivePricePerSecondMicroUsd) +
            deps.settings.removedSpendMicroUsd(monthStart)
    }

    /**
     * Every job of the song (its library id or its Spotify alias, the same song), oldest first, as the list changes.
     * Empty until the stored jobs are read ([loadForDisplay], which [startSing] does too).
     */
    fun jobsFor(songId: String): Flow<List<CloudJobRecord>> {
        val canonical = TaisInstrumentalIndex.canonicalSongId(songId)
        return _state.map { state -> state.jobs.filter { TaisInstrumentalIndex.canonicalSongId(it.songId) == canonical } }
            .distinctUntilChanged()
    }

    /** The person has something here beyond automatic preparing (the "Cloud processing" notification is for that). */
    fun hasWorkForThePerson(): Boolean = jobs.any { it.state.isPending && it.origin != CloudJobOrigin.AUTO }

    /** A Sing is at RunPod, or its results are being collected: the person is waiting on it. */
    fun isWaitingOnSing(): Boolean = jobs.any {
        it.origin == CloudJobOrigin.SING &&
            (it.state.isAtRunPod || it.state == CloudJobState.RESULTS_READY || it.state == CloudJobState.DOWNLOADING)
    }

    /** A priority job needs the phone now (to prepare, upload or send): a pass that is waiting should look at once. */
    fun hasPriorityPhoneWork(): Boolean {
        val time = now
        return jobs.any {
            it.isPriority && it.isDue(time) &&
                (it.state == CloudJobState.QUEUED || it.state == CloudJobState.UPLOADING || it.state == CloudJobState.UPLOADED)
        }
    }

    /**
     * What this month's automatic jobs have used or will use (their recorded costs, the estimate of those at RunPod,
     * and what removed ones spent): Smart auto-prepare's own share of the month, apart from the shared cap.
     */
    fun autoCommittedThisMonthMicroUsd(): Long {
        val monthStart = deps.monthStartMs(now)
        return CloudBudget.autoCommittedMicroUsd(
            jobs, monthStart, deps.settings.snapshot().effectivePricePerSecondMicroUsd,
            deps.settings.removedAutoSpendMicroUsd(monthStart),
        )
    }

    /**
     * Whether the cloud's own automatic preparing looks after a song of [durationMs]: the switch is on and the cloud
     * is usable (it ignores the network right now). The on-device automatic studio leaves such songs to it.
     */
    fun cloudAutoPrepareOwns(durationMs: Long): Boolean {
        val settings = deps.settings.snapshot()
        val autoPrepareOn = settings.enabled && settings.autoPrepareEnabled && CloudAutoPrepare.isPreparable(durationMs)
        // Asked once per library song by the on-device studio's scan: the usual answer (the switch is off) costs nothing.
        if (!autoPrepareOn) return false
        return AutomaticStudioPolicy.cloudOwnsInstrumental(
            autoPrepareOn = true,
            cloudUsable = availability(durationMs, settings, checkNetwork = false) == null,
        )
    }

    /** The cloud can take an automatic job now, the network aside: on, set up, keys fine, room for one more song this month. */
    fun autoPrepareUsable(): Boolean =
        availability(CloudCost.REFERENCE_SONG_MS, deps.settings.snapshot(), checkNetwork = false) == null

    // ─── Sing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Whether a Sing on a song of [durationMs] can use the cloud now (null: yes). From the settings and the last pass's
     * notice only, with no I/O; [startSing] checks the rest (audio, an instrumental already there, the keys).
     */
    fun singAvailability(durationMs: Long): CloudSingUnavailable? =
        availability(durationMs, deps.settings.snapshot(), checkNetwork = true)

    private fun availability(durationMs: Long, settings: CloudSettingsSnapshot, checkNetwork: Boolean): CloudSingUnavailable? {
        if (!settings.enabled) return CloudSingUnavailable.OFF
        when (_state.value.notice) {
            CloudNotice.KEYS_MISSING, CloudNotice.SECURE_STORAGE_UNAVAILABLE, CloudNotice.RUNPOD_KEY_REFUSED ->
                return CloudSingUnavailable.KEYS_MISSING
            CloudNotice.NOT_CONFIGURED, CloudNotice.ENDPOINT_NOT_FOUND -> return CloudSingUnavailable.NOT_CONFIGURED
            else -> Unit
        }
        if (!settings.usesBuiltInKeys && (settings.endpointId.isBlank() || settings.r2Endpoint.isBlank())) {
            return CloudSingUnavailable.NOT_CONFIGURED
        }
        if (durationMs > CloudLimits.MAX_DURATION_MS) return CloudSingUnavailable.TOO_LONG
        if (!fitsOneSong(durationMs, settings)) return CloudSingUnavailable.CAP_REACHED
        if (checkNetwork && !settings.useCellular && !deps.isUnmetered()) return CloudSingUnavailable.NOT_ON_WIFI
        return null
    }

    /** One more song (instrumental and aligned lyrics, at the length given) fits what is left of this month. */
    private fun fitsOneSong(durationMs: Long, settings: CloudSettingsSnapshot): Boolean {
        val plan = CloudSongPlan("", listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), CloudLyricsMode.ALIGN, false, durationMs)
        val estimate = CloudCost.estimateMicroUsd(listOf(plan), settings.quality, settings.effectivePricePerSecondMicroUsd)
        return CloudBudget.allows(estimate, settings.effectiveMonthlyCapMicroUsd, committedThisMonthMicroUsd())
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────

    /** The screens opened: show the stored jobs even while the feature is off. */
    suspend fun loadForDisplay() {
        jobsLock.withLock { loadLocked() }
    }

    /**
     * The app came to the foreground or went away. While visible and switched on, a light pass runs at once and
     * every 15 s while something can change; nothing at all runs (no file read) while the feature is off.
     */
    @Synchronized
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
    @Synchronized
    private fun ensurePollLoop(requestWorker: Boolean) {
        if (!visible || !deps.settings.snapshot().enabled || pollLoop?.isActive == true) return
        pollLoop = deps.scope.launch {
            var first = true
            while (isActive && visible) {
                val outcome = try {
                    foregroundPass()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A disk or database hiccup: every change made so far is saved; the next tick tries again.
                    null
                }
                // Preparing and uploading are the worker's (they can take minutes); a light pass only asks for it.
                if ((first && requestWorker || !first) && outcome?.workBeforeSubmit == true) requestPassForWork()
                first = false
                if (!needsPolling()) break
                withTimeoutOrNull(pollIntervalMs()) { pollWake.receive() }
            }
        }
    }

    /** Asks for the worker: urgently (expedited) when a priority job is waiting for it, at most every 15 s. */
    private fun requestPassForWork() {
        if (!hasPriorityPhoneWork()) {
            deps.scheduler.requestPass()
            return
        }
        val time = now
        if (time - lastUrgentRequestAtMs < URGENT_REQUEST_GAP_MS) return
        lastUrgentRequestAtMs = time
        deps.scheduler.requestUrgentPass()
    }

    /** How often the foreground loop looks: every few seconds while a priority job (a Sing) is waited on. */
    private fun pollIntervalMs(): Long =
        if (jobs.any { it.isPriority && it.state.isPending }) CloudTiming.SING_POLL_INTERVAL_MS
        else CloudTiming.STATUS_POLL_INTERVAL_MS

    /** Something can change without the person: jobs at RunPod, a batch gate, a retry wait, results to collect. */
    private fun needsPolling(): Boolean = jobs.any { record ->
        record.state.isAtRunPod || record.state == CloudJobState.UPLOADED || record.state == CloudJobState.RESULTS_READY ||
            (record.isPriority && record.state.isPending) ||
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
        val estimate = CloudBatchEstimate.make(selection.plans, settings.quality, settings.effectivePricePerSecondMicroUsd,
            committedThisMonthMicroUsd(), settings.effectiveMonthlyCapMicroUsd)
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
        // One song is "Process in cloud" on that song: the person is waiting on it, so it goes ahead of larger batches.
        val priority = preview.plans.size == 1
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
                    priorityAtMs = time.takeIf { priority },
                )
                added++
            }
            out
        }
        if (added > 0 && priority) {
            // Someone is waiting on this one song: it is the phone's first job, and watched closely like a Sing.
            wakeForPriorityJob()
        } else {
            deps.scheduler.requestPass()
            deps.scheduler.ensureWatch()
        }
        return added
    }

    /**
     * A tap on Sing: the cloud makes this song's instrumental (and its word-timed lyrics, as Outputs say) ahead of
     * everything else. The tap is the consent for this one song, so there is no confirm step, and the instrumental is
     * asked for even when Outputs has it off.
     *
     * A song that already has a job on its way is joined, not started again (an automatic job becomes a Sing, with a
     * batch of its own so the batch gate doesn't hold it). [CloudSingStart.Unavailable] says why the cloud can't take it
     * and the caller does the work on this phone instead.
     */
    suspend fun startSing(song: CloudSong): CloudSingStart {
        loadForDisplay()
        val settings = deps.settings.snapshot()
        if (!settings.enabled) return CloudSingStart.Unavailable(CloudSingUnavailable.OFF)
        val canonical = TaisInstrumentalIndex.canonicalSongId(song.id)
        pendingJobFor(jobs, canonical)?.let { return joinSing(it) }
        singAvailability(song.durationMs)?.let { return CloudSingStart.Unavailable(it) }
        if (!settings.usesBuiltInKeys) {
            val secrets = deps.settings.secrets()
            if (secrets.storageUnavailable || secrets.keysMissing) {
                return CloudSingStart.Unavailable(CloudSingUnavailable.KEYS_MISSING)
            }
        }
        if (!song.hasAudioSource) return CloudSingStart.Unavailable(CloudSingUnavailable.NO_AUDIO)
        if (deps.host.hasInstrumental(song.id)) return CloudSingStart.Unavailable(CloudSingUnavailable.ALREADY_HAS_INSTRUMENTAL)
        val lyrics = deps.host.lyricsFacts(song)
        val facts = CloudSongFacts(song.id, song.durationMs, hasInstrumental = false, lyrics = lyrics.state,
            hasAudioSource = true, isStreamed = song.isStreamed, hasPendingJob = false)
        val (plan, skip) = CloudSelector.plan(facts, settings.selectionOptions.copy(forceInstrumental = true))
        if (plan == null) {
            return CloudSingStart.Unavailable(
                if (skip == CloudSkipReason.TOO_LONG) CloudSingUnavailable.TOO_LONG else CloudSingUnavailable.NO_AUDIO
            )
        }
        val estimate = CloudBatchEstimate.make(listOf(plan), settings.quality, settings.effectivePricePerSecondMicroUsd,
            committedThisMonthMicroUsd(), settings.effectiveMonthlyCapMicroUsd)
        if (!estimate.fitsCap) return CloudSingStart.Unavailable(CloudSingUnavailable.CAP_REACHED)
        val time = now
        val record = CloudJobRecord(
            jobKey = CloudKeys.jobKey(deps.newJobKey()), songId = song.id, title = song.title, artist = song.artist,
            songDurationMs = song.durationMs, batchId = deps.newJobKey(), isStreamed = plan.isStreamed,
            tasks = plan.tasks, lyricsMode = plan.lyricsMode, quality = CloudSelector.quality(settings.quality, plan.durationMs),
            createdAtMs = time, origin = CloudJobOrigin.SING, priorityAtMs = time,
        )
        var raced: CloudJobRecord? = null
        mutateJobs { list ->
            // Another tap got there first (this list is the only place that knows): join its job.
            raced = pendingJobFor(list, canonical)
            if (raced != null) list else list + record
        }
        raced?.let { return joinSing(it) }
        wakeForPriorityJob()
        return CloudSingStart.Started(record.jobKey)
    }

    private fun pendingJobFor(list: List<CloudJobRecord>, canonicalSongId: String): CloudJobRecord? =
        list.lastOrNull { it.state.isPending && TaisInstrumentalIndex.canonicalSongId(it.songId) == canonicalSongId }

    /** The song's job is already on its way: the Sing waits for it. An automatic job is promoted to a Sing. */
    private suspend fun joinSing(existing: CloudJobRecord): CloudSingStart {
        val hasInstrumental = CloudTask.INSTRUMENTAL in existing.tasks
        // A job without an instrumental can still be given one while it is on the phone; once RunPod has it, it can't.
        if (!hasInstrumental && !existing.state.isBeforeSubmit) {
            return CloudSingStart.Unavailable(CloudSingUnavailable.NO_AUDIO)
        }
        val promote = existing.origin == CloudJobOrigin.AUTO
        if (promote || !hasInstrumental) {
            val time = now
            update(existing.jobKey) { record ->
                var next = record
                if (promote) {
                    next = next.copy(
                        origin = CloudJobOrigin.SING, priorityAtMs = time,
                        // Alone in its batch, so the batch gate doesn't wait for the automatic jobs it came with.
                        batchId = if (record.state.isBeforeSubmit) deps.newJobKey() else record.batchId,
                    )
                    // A priority job itself now: if it had stepped aside for another Sing it no longer waits behind it.
                    if (next.isPaused) next = next.releasingPause()
                }
                if (CloudTask.INSTRUMENTAL !in next.tasks && next.state.isBeforeSubmit) {
                    next = next.copy(tasks = listOf(CloudTask.INSTRUMENTAL) + next.tasks)
                }
                next.copy(updatedAtMs = time)
            }
        }
        wakeForPriorityJob()
        return CloudSingStart.Joined(existing.jobKey)
    }

    /** A priority job is on the list: the phone gets to it first, and the foreground loop watches it closely. */
    private fun wakeForPriorityJob() {
        interruptAutoUploads()
        pollWake.trySend(Unit)
        deps.scheduler.requestUrgentPass()
        deps.scheduler.ensureWatch()
        ensurePollLoop(requestWorker = false)
    }

    /** An automatic upload in progress stops (it starts again from zero later) so the pass can reach the priority job. */
    private fun interruptAutoUploads() {
        for (record in jobs) {
            if (record.origin == CloudJobOrigin.AUTO && record.state == CloudJobState.UPLOADING) {
                transfersInFlight.remove(record.jobKey)?.cancel()
            }
        }
    }

    // ─── Automatic preparing ────────────────────────────────────────────────────────────────

    /**
     * Smart auto-prepare: one automatic job for each of [songs] (the ranker's picks), without a confirm step. Skips
     * songs with a job already, stays inside the day's cap and both budgets (the automatic share and the month's cap),
     * and sends nothing while either switch is off. The caller runs a pass afterwards (the automatic worker does, under
     * its own conditions). Returns how many jobs were added.
     */
    suspend fun sendAuto(songs: List<CloudSong>): Int {
        loadForDisplay()
        val settings = deps.settings.snapshot()
        if (!settings.enabled || !settings.autoPrepareEnabled || songs.isEmpty()) return 0
        val time = now
        val dayStart = deps.dayStartMs(time)
        val room = settings.autoPrepareDailyCap - deps.settings.autoDayCount(dayStart)
        if (room <= 0) return 0
        val pending = jobs.filter { it.state.isPending }.map { TaisInstrumentalIndex.canonicalSongId(it.songId) }.toSet()
        val facts = mutableListOf<CloudSongFacts>()
        val byId = LinkedHashMap<String, CloudSong>()
        for (song in songs) {
            if (byId.containsKey(song.id)) continue
            byId[song.id] = song
            facts += CloudSongFacts(song.id, song.durationMs, deps.host.hasInstrumental(song.id),
                deps.host.lyricsFacts(song).state, song.hasAudioSource, song.isStreamed,
                TaisInstrumentalIndex.canonicalSongId(song.id) in pending)
        }
        // The instrumental is what it is for; lyrics are only timed where the song has some (it never writes lyrics
        // nobody asked for with the AI transcriber), which is also what the ranker's cost estimate assumes.
        val options = settings.selectionOptions.copy(forceInstrumental = true, transcribeWhenMissing = false)
        val wanted = CloudSelector.select(facts, options).plans.take(room)
        if (wanted.isEmpty()) return 0
        // As many of the picks, in order, as both budgets can take together (one cold start is shared by the batch).
        val capRemaining = CloudBudget.remainingMicroUsd(settings.effectiveMonthlyCapMicroUsd, committedThisMonthMicroUsd())
        val autoCommitted = autoCommittedThisMonthMicroUsd()
        var count = wanted.size
        while (count > 0) {
            val estimate = CloudCost.estimateMicroUsd(
                wanted.take(count), settings.quality, settings.effectivePricePerSecondMicroUsd,
            )
            if (CloudBudget.allowsAuto(estimate, settings.effectiveAutoBudgetMicroUsd, autoCommitted, capRemaining)) break
            count--
        }
        if (count == 0) return 0
        val plans = wanted.take(count)
        val batchId = deps.newJobKey()
        var added = 0
        mutateJobs { list ->
            val out = list.toMutableList()
            for (plan in plans) {
                val song = byId[plan.songId] ?: continue
                if (out.any { it.songId == song.id && it.state.isPending }) continue
                out += CloudJobRecord(
                    jobKey = CloudKeys.jobKey(deps.newJobKey()), songId = song.id, title = song.title,
                    artist = song.artist, songDurationMs = song.durationMs, batchId = batchId,
                    isStreamed = plan.isStreamed, tasks = plan.tasks, lyricsMode = plan.lyricsMode,
                    quality = CloudSelector.quality(settings.quality, plan.durationMs), createdAtMs = time,
                    origin = CloudJobOrigin.AUTO,
                )
                added++
            }
            out
        }
        if (added > 0) {
            // The count is kept apart from the list, so removing finished jobs can't give the day's room back.
            deps.settings.addAutoDayCount(added, dayStart)
            deps.scheduler.ensureWatch()
        }
        return added
    }

    /**
     * "Prepare songs ahead" went off: automatic jobs still on the phone stop (their uploads are deleted); those at
     * RunPod finish, since they are paid for, and come back on their own.
     */
    suspend fun cancelPendingAuto() {
        loadForDisplay()
        for (record in jobs.filter { it.origin == CloudJobOrigin.AUTO && it.state.isBeforeSubmit }) {
            cancel(record.jobKey)
            // Nobody asked for these, so they leave the list instead of waiting there as "cancelled".
            remove(record.jobKey)
        }
    }

    /** Stops a job wherever it is: its transfer, RunPod, and its objects in the bucket. */
    suspend fun cancel(jobKey: String) {
        val record = jobsLock.withLock { loadLocked(); job(jobKey) } ?: return
        if (record.state.isFinished) return
        transfersInFlight.remove(jobKey)?.cancel()
        update(jobKey) { it.on(CloudJobEvent.CANCELLED, now) }
        // Jobs that stepped aside for this one don't wait for it any more.
        releasePausedFor(jobKey)
        // Clients from the current settings, even when no pass has run in this process yet: a job at RunPod must
        // be stopped there, or it keeps running and billing.
        val clients = currentClients() ?: runCatching {
            buildClients(resolveConfig(deps.settings.snapshot()))
        }.getOrNull()
        val runpodId = record.runpodJobId
        if (record.state.isAtRunPod && runpodId != null && clients != null) {
            cancelQuietly(clients, runpodId)
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
        removeJobs { it.jobKey == jobKey && it.state.isFinished }
    }

    /** Removes every imported job. */
    suspend fun clearFinished() {
        removeJobs { it.state == CloudJobState.IMPORTED }
    }

    /**
     * Takes jobs off the list. What they spent this month stays in the month's total
     * ([CloudStudioSettingsSource.addRemovedSpend]), so clearing the list never makes room under the monthly cap.
     */
    private suspend fun removeJobs(matches: (CloudJobRecord) -> Boolean) {
        mutateJobs { list ->
            val (removed, kept) = list.partition(matches)
            if (removed.isNotEmpty()) {
                val monthStart = deps.monthStartMs(now)
                deps.settings.addRemovedSpend(CloudBudget.spentMicroUsd(removed, monthStart), monthStart)
                // Automatic preparing has its own share of the month: its spend is kept apart too.
                deps.settings.addRemovedAutoSpend(
                    CloudBudget.spentMicroUsd(removed.filter { it.origin == CloudJobOrigin.AUTO }, monthStart), monthStart
                )
            }
            kept
        }
    }

    // ─── Test connection ────────────────────────────────────────────────────────────────────

    /** RunPod's `/health` and the bucket probe, each on its own (design §7.2). */
    suspend fun testConnection(): CloudConnectionReport = coroutineScope {
        val config = resolveConfig(deps.settings.snapshot())
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
        val config = resolveConfig(deps.settings.snapshot())
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
        val config: CloudConfigInput
        if (settings.usesBuiltInKeys) {
            // PixlAudio's own keys: nothing in secure storage is needed (and it is never opened for them).
            config = resolveConfig(settings)
        } else {
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
            config = ownConfig(settings, secrets.secrets)
        }
        val clients = clients(config) ?: run {
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
            var current = settings
            sendReady(clients, current)
            watchRunPod(clients)
            // Light passes (the app on screen) aren't stopped by WorkManager: their transfers have no window.
            val transferDeadline = if (mode == PassMode.FULL) deadlineMs else null
            collectResults(clients, current, transferDeadline)
            if (mode == PassMode.FULL) {
                val handled = HashSet<String>()
                while (now < deadlineMs && currentCoroutineContext().isActive) {
                    // The person can switch the feature (or mobile data) off, or leave Wi-Fi, mid-pass: read again
                    // before every step, so nothing more is prepared, sent or spent after that.
                    current = deps.settings.snapshot()
                    if (!current.enabled) break
                    val time = now
                    val autoOk = deps.autoWorkAllowed()
                    // The list is read again every step and a priority job (a Sing) comes first, so one added while
                    // this pass runs is the next thing it prepares and uploads.
                    val next = CloudPriorityLane.phoneOrder(jobs).firstOrNull { record ->
                        record.jobKey !in handled && record.isDue(time) && isPhoneStep(record) && stepAllowed(record, current, autoOk)
                    } ?: break
                    handled += next.jobKey
                    if (next.state == CloudJobState.QUEUED) prepare(next.jobKey, current)
                    val afterPrepare = deps.settings.snapshot()
                    if (job(next.jobKey)?.state == CloudJobState.UPLOADING && afterPrepare.enabled &&
                        uploadAllowed(next, afterPrepare) && hasTransferWindow(deadlineMs)
                    ) upload(next.jobKey, clients)
                    current = deps.settings.snapshot()
                    if (!current.enabled) break
                    sendReady(clients, current)
                    watchRunPod(clients)
                    collectResults(clients, current, transferDeadline)
                }
            }
            current = deps.settings.snapshot()
            if (!current.enabled) {
                setNotice(CloudNotice.OFF)
                return outcome(current).copy(blocked = true)
            }
            // Jobs the watch just sent back (lost at RunPod, a worker error without a wait) go out in this pass.
            sendReady(clients, current)
            val result = outcome(current)
            if (result.waitingForUnmetered) setNotice(CloudNotice.WAITING_FOR_WIFI)
            return result
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    private fun transfersAllowed(settings: CloudSettingsSnapshot): Boolean = settings.useCellular || deps.isUnmetered()

    /**
     * Whether the phone-step picker may take [record] now. The person's jobs wait for an unmetered network for steps
     * that move song data (unless mobile data is on); an automatic job's steps only run on a charger and Wi-Fi
     * ([autoOk] is [CloudStudioDependencies.autoWorkAllowed], read once by the caller), whatever the mobile-data
     * setting says.
     */
    private fun stepAllowed(record: CloudJobRecord, settings: CloudSettingsSnapshot, autoOk: Boolean): Boolean =
        if (record.origin == CloudJobOrigin.AUTO) autoOk else !movesData(record) || transfersAllowed(settings)

    /** Whether [record]'s upload may start now (same rule as [stepAllowed], for the data it moves). */
    private fun uploadAllowed(record: CloudJobRecord, settings: CloudSettingsSnapshot): Boolean =
        if (record.origin == CloudJobOrigin.AUTO) deps.autoWorkAllowed() else transfersAllowed(settings)

    /** Whether [record]'s results may be downloaded now: an automatic job never uses mobile data. */
    private fun downloadAllowed(record: CloudJobRecord, settings: CloudSettingsSnapshot): Boolean =
        if (record.origin == CloudJobOrigin.AUTO) deps.isUnmetered() else transfersAllowed(settings)

    /** The priority lane makes room first, then the ready jobs go out as one burst. */
    private suspend fun sendReady(clients: Clients, settings: CloudSettingsSnapshot) {
        prioritizeQueue(clients, settings)
        submitReadyBatches(clients, settings)
    }

    /**
     * A transfer only starts with a few minutes of the pass left: a plain background worker is stopped at 10
     * minutes, and an upload cut off there starts again from zero next time.
     */
    private fun hasTransferWindow(deadlineMs: Long?): Boolean = deadlineMs == null || deadlineMs - now >= MIN_TRANSFER_WINDOW_MS

    /** The switch went off: stop every running upload and download at once (their jobs keep their state). */
    fun stopTransfers() {
        transfersInFlight.values.forEach { it.cancel() }
    }

    private fun outcome(settings: CloudSettingsSnapshot): CloudPassOutcome {
        val time = now
        val pending = jobs.filter { it.state.isPending }
        val transfersAllowed = settings.useCellular || deps.isUnmetered()
        // Asked only when an automatic job is waiting on the phone: it reads the battery.
        val autoOk = pending.none { it.origin == CloudJobOrigin.AUTO && isPhoneStep(it) } || deps.autoWorkAllowed()
        val beforeSubmit = pending.any {
            it.isDue(time) && (it.state == CloudJobState.PREPARING || (isPhoneStep(it) && stepAllowed(it, settings, autoOk)))
        }
        // An automatic job only waits for Wi-Fi to download (its other steps wait for the charger too, and the
        // automatic worker, not a Wi-Fi pass, starts them).
        val waitingForNetwork = settings.enabled && pending.any {
            val waitsForDownload = it.state == CloudJobState.RESULTS_READY && CloudTask.INSTRUMENTAL in it.tasks &&
                !it.importedInstrumental && it.outputs?.get(INSTRUMENTAL_SLOT) != null
            if (it.origin == CloudJobOrigin.AUTO) waitsForDownload && !deps.isUnmetered()
            else !transfersAllowed && (movesData(it) || waitsForDownload)
        }
        val retries = pending.mapNotNull { record -> record.nextAttemptAtMs?.takeIf { it > time } }
        val gates = pending.filter { it.state == CloudJobState.UPLOADED }
            .mapNotNull { it.uploadedAtMs?.plus(CloudTiming.BATCH_GATE_MS) }
            .filter { it > time }
        // A priority job's hold on the others ends by itself: the pass looks again then.
        val holds = pending.filter { it.state == CloudJobState.UPLOADED && it.isPaused }
            .mapNotNull { it.pausedAtMs?.plus(CloudTiming.PRIORITY_HOLD_MAX_MS) }
            .filter { it > time }
        return CloudPassOutcome(
            pending = pending.isNotEmpty() && settings.enabled,
            workBeforeSubmit = beforeSubmit && settings.enabled,
            waitingForUnmetered = waitingForNetwork,
            nextWakeAtMs = (retries + gates + holds).minOrNull(),
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
            // An automatic job only uses audio already on the phone, never a fresh download through the YouTube layer;
            // if that audio is gone it quietly drops (the ranker may pick the song again later).
            val source = if (record.origin == CloudJobOrigin.AUTO) {
                deps.host.cachedAudioSource(song) ?: run {
                    dropAutoJob(jobKey)
                    return
                }
            } else deps.host.audioSource(song)
            val identity = if (record.isStreamed) deps.host.streamIdentity(song) else null
            // A streamed song's download always goes up decoded as FLAC (design §7.3): the worker then sees exactly
            // the samples this phone plays, whatever priming the download's container declares. So does the FLAC
            // redo of a result that didn't line up: decoded samples leave nothing to disagree about.
            val prepared = deps.preparer.prepare(source, jobKey, forceDecode = record.isStreamed || record.flacRedone)
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
                    lowQualitySource = prepared.lowQualitySource,
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

    /** An automatic job that can't be prepared quietly leaves the list: the person never asked for it, so it isn't a failure. */
    private suspend fun dropAutoJob(jobKey: String) {
        mutateJobs { list -> list.filterNot { it.jobKey == jobKey && it.origin == CloudJobOrigin.AUTO } }
        cleanUpLocal(jobKey)
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
        val result = try {
            transfer(jobKey) { deps.transfers.upload(file, url, CloudKeys.contentType(ext)) { progress(jobKey, it) } }
        } finally {
            clearProgress(jobKey)
        }
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

    // ─── Priority lane ──────────────────────────────────────────────────────────────────────

    /** The priority job still on the phone that has waited longest: the one other jobs step aside for. */
    private fun currentPriorityJob(): CloudJobRecord? =
        jobs.filter { it.isPriority && it.state.isBeforeSubmit }.minByOrNull { it.priorityAtMs ?: it.createdAtMs }

    /** Jobs that waited for [priorityJobKey] wait no more (it was cancelled): they go out again as they are. */
    private suspend fun releasePausedFor(priorityJobKey: String) {
        var released = false
        mutateJobs { list ->
            list.map { record ->
                if (record.pausedByJobKey == priorityJobKey) {
                    released = true
                    record.releasingPause()
                } else record
            }
        }
        if (released) deps.scheduler.requestPass()
    }

    /** Releases paused jobs whose wait is over: their priority job is on its way or gone, or the hold ran out. */
    private suspend fun releaseStalePauses() {
        val stale = CloudPriorityLane.releasable(jobs, now)
        if (stale.isEmpty()) return
        mutateJobs { list -> list.map { if (it.jobKey in stale) it.releasingPause() else it } }
    }

    /**
     * The priority lane (design D1–D8). When a priority job (a Sing, a one-song "Process in cloud") is uploaded and
     * ready to go, every automatic or batch job still WAITING in RunPod's queue steps aside, so the priority job is the
     * next one RunPod starts; the ones that stepped aside go out again right behind it, in the same burst
     * ([submitReadyBatches]). Nothing is cancelled while the priority job is still preparing or uploading (the GPU
     * keeps working through the queue), a running job is never stopped, and a job already holding the GPU keeps it.
     *
     * Safe order for each job: its intent is on disk first, then a fresh `/status` (only `IN_QUEUE` goes on), then
     * `/cancel` (only a reply that says `CANCELLED` counts), and only then does the job go back to `uploaded`, keeping
     * its upload. A lost reply leaves the intent: the next status poll that sees `CANCELLED` finishes the pause.
     * `/cancel`s are sent one at a time, 120 ms apart ([RunPodJobsClient]).
     */
    private suspend fun prioritizeQueue(clients: Clients, settings: CloudSettingsSnapshot) {
        releaseStalePauses()
        val time = now
        val priority = jobs.filter {
            it.isPriority && it.state == CloudJobState.UPLOADED && it.isDue(time) &&
                !CloudRetention.inputTooOldToSubmit(it.uploadedAtMs, time)
        }.minByOrNull { it.priorityAtMs ?: it.createdAtMs } ?: return
        val candidates = CloudPriorityLane.cancelOrder(CloudPriorityLane.pausable(jobs, priority.jobKey), jobs)
        if (candidates.isEmpty()) return
        // Making room only helps when the priority job can go out: inside what is left of the month once the jobs
        // that step aside no longer count as at RunPod (they count again when they are sent behind it).
        val price = settings.effectivePricePerSecondMicroUsd
        val monthStart = deps.monthStartMs(time)
        val freed = candidates.filter { it.isRunningAtRunPod }.sumOf { CloudCost.estimatedSeconds(it.plan, it.quality) * price }
        val committed = CloudBudget.committedMicroUsd(jobs, monthStart, price) + deps.settings.removedSpendMicroUsd(monthStart) - freed
        val estimate = CloudCost.estimatedSeconds(priority.plan, priority.quality) * price
        if (!CloudBudget.allows(estimate, settings.effectiveMonthlyCapMicroUsd, committed.coerceAtLeast(0))) return

        val keys = candidates.map { it.jobKey }.toSet()
        mutateJobs { list ->
            list.map { if (it.jobKey in keys && it.state == CloudJobState.SUBMITTED && !it.hasPauseIntent) it.beginningPause() else it }
        }
        val cancelled = mutableListOf<String>()
        for (candidate in candidates) {
            val live = job(candidate.jobKey)?.takeIf { it.state == CloudJobState.SUBMITTED } ?: continue
            val runpodId = live.runpodJobId ?: continue
            try {
                val status = clients.runpod.status(runpodId)
                when (CloudPriorityLane.check(status.typedStatus)) {
                    PauseCheck.IN_QUEUE -> Unit
                    // It began meanwhile, or reached an end: it stays where it is, and the normal handling takes over.
                    PauseCheck.STARTED, PauseCheck.FINISHED -> {
                        applyStatus(status, live.jobKey, clients)
                        update(live.jobKey) { if (it.state == CloudJobState.SUBMITTED) it.clearingPauseIntent() else it }
                        continue
                    }
                    PauseCheck.UNKNOWN -> continue
                }
                if (CloudPriorityLane.cancelConfirmed(clients.runpod.cancel(runpodId))) cancelled += live.jobKey
            } catch (error: CancellationException) {
                throw error
            } catch (error: RunPodError.Unauthorized) {
                setNotice(CloudNotice.RUNPOD_KEY_REFUSED)
                break
            } catch (error: RunPodError.RateLimited) {
                setNotice(CloudNotice.RATE_LIMITED)
                break
            } catch (error: RunPodError.JobNotFound) {
                handleMissingAtRunPod(live.jobKey, clients)
            } catch (error: Exception) {
                // Offline or a RunPod hiccup: the job keeps its intent and stays submitted; the next pass asks again.
            }
        }
        if (cancelled.isEmpty()) return
        // The worker writes out/<jobKey>/ the moment it has a job: a folder means the job began before the cancel
        // landed (it may even have finished), none means it never ran and left nothing behind. Unsure counts as began.
        val began: Set<String>? = try {
            clients.objects.list("out/", "/").commonPrefixes.mapNotNull(CloudKeys::jobKeyFromOutputKey).toSet()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
        val pausedAt = now
        mutateJobs { list ->
            list.map { record ->
                if (record.jobKey in cancelled && record.state == CloudJobState.SUBMITTED) {
                    record.completingPause(priority.jobKey, neverStarted = began != null && record.jobKey !in began, nowMs = pausedAt)
                        ?: record
                } else record
            }
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
        // Jobs waiting at the phone for a priority job that is still preparing or uploading stay put (for a few
        // minutes at most); a priority job going out in this very pass holds nobody back, it just goes first.
        val all = jobs
        val goingOut = ready.filter { key -> all.firstOrNull { it.jobKey == key }?.isPriority == true }.toSet()
        ready.removeAll { key ->
            val record = all.firstOrNull { it.jobKey == key }
            record != null && CloudPriorityLane.isHeld(record, all, goingOut, time)
        }
        // The pass is one burst: its last job carries `policy.last_in_batch`, so the worker stops itself after it
        // instead of staying up, idle and billed. Each job waits until the next one is ready (or the pass ends) to go
        // out, since only then is it known whether it is the last ([CloudSubmitBurst], iOS d84f8f8). A priority job
        // leads the burst and the jobs that stepped aside for it follow in their old order, so only the final one is
        // the last, whichever of them the cap lets through.
        val burst = CloudSubmitBurst<PendingSubmit>()
        for (jobKey in CloudPriorityLane.submitOrder(ready, all)) {
            val record = job(jobKey) ?: continue
            if (record.state != CloudJobState.UPLOADED || !record.isDue(time)) continue
            if (CloudRetention.inputTooOldToSubmit(record.uploadedAtMs, time)) {
                update(jobKey) { it.on(CloudJobEvent.REQUEUE, now) }
                continue
            }
            val price = settings.effectivePricePerSecondMicroUsd
            // The held job isn't at RunPod yet, but it will be: count it. Jobs taken off the list still count.
            val monthStart = deps.monthStartMs(time)
            val held = burst.holding
            val committed = CloudBudget.committedMicroUsd(jobs, monthStart, price) +
                deps.settings.removedSpendMicroUsd(monthStart) + (held?.estimateMicroUsd ?: 0)
            val estimate = CloudCost.estimatedSeconds(record.plan, record.quality) * price
            if (!CloudBudget.allows(estimate, settings.effectiveMonthlyCapMicroUsd, committed)) {
                setNotice(CloudNotice.CAP_REACHED)
                break // the job held so far still goes out, as the burst's last
            }
            clearNotice(CloudNotice.CAP_REACHED)
            if (record.origin == CloudJobOrigin.AUTO) {
                // Automatic jobs also stay inside their own share of the month. One over it is left waiting (not failed,
                // not the cap's notice) and the rest of the burst carries on: a Sing or a batch job is not held up by it.
                val autoCommitted = CloudBudget.autoCommittedMicroUsd(jobs, monthStart, price, deps.settings.removedAutoSpendMicroUsd(monthStart)) +
                    (held?.takeIf { it.origin == CloudJobOrigin.AUTO }?.estimateMicroUsd ?: 0)
                val capRemaining = CloudBudget.remainingMicroUsd(settings.effectiveMonthlyCapMicroUsd, committed)
                if (!CloudBudget.allowsAuto(estimate, settings.effectiveAutoBudgetMicroUsd, autoCommitted, capRemaining)) continue
            }
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
            // A job that stepped aside before it ever began left nothing in the bucket: no need to clear anything.
            if (record.submittedAtMs != null && record.pausedNeverStarted != true) {
                // One that may have begun before RunPod's cancel landed could even have finished: R2 decides.
                if (record.pausedNeverStarted == false && takeManifestIfPresent(jobKey, clients)) continue
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
            val previous = burst.ready(PendingSubmit(jobKey, request, estimate, record.origin))
            if (previous != null && !submit(previous, lastInBatch = false, clients)) return
        }
        burst.end()?.let { submit(it, lastInBatch = true, clients) }
    }

    /** A job of the current submit pass that is ready to go out, held until the next one is ready. */
    private class PendingSubmit(
        val jobKey: String,
        val request: CloudJobRequest,
        val estimateMicroUsd: Long,
        val origin: CloudJobOrigin = CloudJobOrigin.MANUAL,
    )

    /**
     * Sends one job of a burst. False when the pass should stop (the key, the Endpoint ID, rate limiting, missing
     * settings); a failure of this job alone carries on with the next. Cancellation propagates.
     */
    private suspend fun submit(pending: PendingSubmit, lastInBatch: Boolean, clients: Clients): Boolean {
        val jobKey = pending.jobKey
        // Cancelled while it waited for the next job to be ready.
        if (job(jobKey)?.state != CloudJobState.UPLOADED) return true
        try {
            // Once /run is out, its answer and the saved id must not be torn apart by a cancelled pass (the app went
            // to the background): a lost id means the job is sent, and billed, twice.
            withContext(NonCancellable) {
                val runpodJob = clients.runpod.run(pending.request.markingLastInBatch(lastInBatch))
                // The id is on disk before the next POST (design §7.4): update() saves. Only onto a job still waiting
                // to go out: one cancelled while the request was out is stopped at RunPod too.
                update(jobKey) {
                    if (it.state != CloudJobState.UPLOADED) it
                    else it.copy(runpodJobId = runpodJob.id, lastError = null, lastErrorCode = null, nextAttemptAtMs = null)
                        .on(CloudJobEvent.SUBMITTED, now)
                }
                if (job(jobKey)?.runpodJobId != runpodJob.id) cancelQuietly(clients, runpodJob.id)
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: RunPodError) {
            when (error) {
                is RunPodError.Unauthorized -> setNotice(CloudNotice.RUNPOD_KEY_REFUSED)
                is RunPodError.EndpointNotFound -> setNotice(CloudNotice.ENDPOINT_NOT_FOUND)
                is RunPodError.RateLimited -> setNotice(CloudNotice.RATE_LIMITED)
                is RunPodError.NotConfigured -> setNotice(CloudNotice.NOT_CONFIGURED)
                else -> {
                    // A lost response may still have queued the job; the worker's guard makes a repeat harmless.
                    fail(jobKey, CloudRedaction.redact(error.message ?: "RunPod error"), null, retryable = true)
                    return true
                }
            }
            return false
        } catch (error: Exception) {
            fail(jobKey, CloudRedaction.redact(error.message ?: "Couldn't reach RunPod"), null, retryable = true)
            return true
        }
    }

    // ─── Watch RunPod ───────────────────────────────────────────────────────────────────────

    private suspend fun watchRunPod(clients: Clients) {
        val time = now
        if (jobs.none { it.state.isAtRunPod }) return
        // One listing of out/ finds finished (and started) jobs, even after /status has expired.
        if (time - lastListAtMs >= pollIntervalMs()) {
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
        // /status for running jobs and the two oldest waiting ones, each at most every 15 s. A priority job (a Sing) is
        // always asked about, every few seconds, wherever it stands in the queue; so is a job whose pause is under way.
        val running = jobs.filter { it.state == CloudJobState.RUNNING }
        val submitted = jobs.filter { it.state == CloudJobState.SUBMITTED }
        val waiting = (submitted.sortedBy { it.submittedAtMs ?: 0 }.take(2) + submitted.filter { it.isPriority || it.hasPauseIntent })
            .distinctBy { it.jobKey }
        for (record in running + waiting) {
            val interval = if (record.isPriority) CloudTiming.SING_POLL_INTERVAL_MS else CloudTiming.STATUS_POLL_INTERVAL_MS
            if (time - (record.lastPolledAtMs ?: 0) < interval) continue
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
                // RunPod cancelled it because this app asked to make room for a priority job (the answer to that cancel
                // never arrived): that finishes the pause, it is not a failure, and costs the job no attempt.
                if (status.typedStatus == RunPodJobStatus.CANCELLED && job(jobKey)?.hasPauseIntent == true) {
                    val waitingFor = currentPriorityJob()?.jobKey
                    update(jobKey) { it.completingPause(waitingFor, neverStarted = false, nowMs = now) ?: it }
                    return
                }
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
        val price = deps.settings.snapshot().effectivePricePerSecondMicroUsd
        update(jobKey) { it.takingResult(result, price, now) }
        if (result.hasResults) {
            // The GPU work is done and paid for: importing it gets its own retry budget, so earlier hiccups (an upload,
            // a lost job) can't make one download error throw the results away.
            update(jobKey) {
                it.copy(lastError = null, lastErrorCode = null, attempts = 0, nextAttemptAtMs = null)
                    .on(CloudJobEvent.RESULTS_READY, now)
            }
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

    private suspend fun collectResults(clients: Clients, settings: CloudSettingsSnapshot, transferDeadlineMs: Long?) {
        val time = now
        // A priority job's results come first: someone is waiting to hear it.
        for (record in CloudPriorityLane.phoneOrder(jobs).filter { it.state == CloudJobState.RESULTS_READY && it.isDue(time) }) {
            collect(record.jobKey, clients, downloadAllowed(record, settings) && hasTransferWindow(transferDeadlineMs))
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
            // A lyrics failure waits out its retry before anything else of this job is fetched.
            if (job(jobKey)?.isDue(now) == false) return
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
            val result = try {
                transfer(jobKey) { deps.transfers.download(url, staged) { progress(jobKey, it) } }
            } finally {
                clearProgress(jobKey)
            }
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
        if (!record.isFullyImported) {
            // The instrumental is in but the lyrics wait for a retry: the next collect fetches them.
            if (record.state == CloudJobState.DOWNLOADING) update(jobKey) { it.on(CloudJobEvent.RESULTS_READY, now) }
            return
        }
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

    /** Best effort: stops a job at RunPod (cancellation of the caller still propagates). */
    private suspend fun cancelQuietly(clients: Clients, runpodJobId: String) {
        try {
            clients.runpod.cancel(runpodJobId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // RunPod drops it at its ttl anyway; the worker's guard refuses a job whose manifest exists.
        }
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

    /**
     * Whose keys the settings say are in use ([CloudKeyChoice]): PixlAudio's built-in ones (opened once, off this
     * thread; empty if they can't be, so nothing is ever sent half-configured) or the person's own fields.
     */
    private suspend fun resolveConfig(settings: CloudSettingsSnapshot): CloudConfigInput =
        if (settings.usesBuiltInKeys) deps.builtIn.load() ?: CloudConfigInput.EMPTY
        else ownConfig(settings, deps.settings.secrets().secrets)

    private fun ownConfig(settings: CloudSettingsSnapshot, secrets: CloudSecrets): CloudConfigInput = CloudConfigInput(
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
        return buildClients(config)?.also {
            clientsCache = it
            clientsKey = config
        }
    }

    private fun buildClients(config: CloudConfigInput): Clients? {
        if (!config.isComplete) return null
        val runpod = deps.makeRunPod(config) ?: return null
        val objects = deps.makeObjects(config) ?: return null
        return Clients(runpod, objects)
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
                // On disk first, then in memory: memory never runs ahead of what a restart would read.
                withContext(NonCancellable) { deps.store.save(next) }
                jobs = next
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
        removeJobs { CloudRetention.shouldPrune(it, time) }
    }

    private fun publish() {
        val snapshot = jobs
        _state.update { it.copy(jobs = snapshot, isLoaded = true) }
    }

    companion object {
        const val INSTRUMENTAL_SLOT = "instrumental"
        /** The least time left in a background pass for a transfer to start (see [hasTransferWindow]). */
        const val MIN_TRANSFER_WINDOW_MS = 3L * 60_000
        /** An urgent (expedited) pass is asked for at most this often while a priority job waits for the worker. */
        private const val URGENT_REQUEST_GAP_MS = 15_000L

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
