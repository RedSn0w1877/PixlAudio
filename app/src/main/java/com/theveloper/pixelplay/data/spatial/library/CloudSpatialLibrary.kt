package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudBudget
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.cloudstudio.CloudLibraryRenderState
import com.theveloper.pixelplay.data.cloudstudio.CloudLibrarySend
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudTransferResult
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialLibraryEvent
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the library needs from Cloud Studio's engine (a seam, so the library is tested without one). */
interface SpatialCloudEngine {
    /** Every job, as the list changes (only list changes, not transfer progress). */
    val jobs: Flow<List<CloudJobRecord>>
    fun currentJobs(): List<CloudJobRecord>
    suspend fun load()
    suspend fun fetchLibraryObject(key: String): ByteArray?
    suspend fun downloadLibraryObject(key: String, destination: File): CloudTransferResult
    suspend fun sendLibraryJob(songId: String, videoId: String, profileId: String, ahead: Boolean): CloudLibrarySend
    suspend fun cancelPendingLibrary(matches: (CloudJobRecord) -> Boolean)
    suspend fun libraryCapAllows(durationMs: Long): Boolean
    fun libraryCommittedMicroUsd(): Long
    fun libraryJobEstimateMicroUsd(durationMs: Long): Long
    fun aheadSentToday(): Int
}

/** [SpatialCloudEngine] over the real [CloudStudioEngine]. */
class EngineSpatialCloud(private val engine: CloudStudioEngine) : SpatialCloudEngine {
    override val jobs: Flow<List<CloudJobRecord>> = engine.state.map { it.jobs }.distinctUntilChanged()
    override fun currentJobs(): List<CloudJobRecord> = engine.state.value.jobs
    override suspend fun load() = engine.loadForDisplay()
    override suspend fun fetchLibraryObject(key: String): ByteArray? = engine.fetchLibraryObject(key)
    override suspend fun downloadLibraryObject(key: String, destination: File): CloudTransferResult =
        engine.downloadLibraryObject(key, destination)
    override suspend fun sendLibraryJob(songId: String, videoId: String, profileId: String, ahead: Boolean) =
        engine.sendLibraryJob(songId, videoId, profileId, ahead)
    override suspend fun cancelPendingLibrary(matches: (CloudJobRecord) -> Boolean) = engine.cancelPendingLibrary(matches)
    override suspend fun libraryCapAllows(durationMs: Long): Boolean = engine.libraryCapAllows(durationMs)
    override fun libraryCommittedMicroUsd(): Long = engine.libraryCommittedThisMonthMicroUsd()
    override fun libraryJobEstimateMicroUsd(durationMs: Long): Long = engine.libraryJobEstimateMicroUsd(durationMs)
    override fun aheadSentToday(): Int = engine.libraryAheadSentToday()
}

/** What one look at the shared library found (§6.2 step 2). */
enum class SharedLookup {
    /** A valid render was there, came down, was verified and is in the local store now. */
    FOUND,

    /** Nothing usable is there: a cloud job is the way to get it. */
    MISSING,

    /** The lookup or the download failed (the network, a damaged file): the failure backoff applies. */
    ERROR,

    /** Something is already under way for this song and profile, or it waits out a failure's backoff. */
    BUSY,
}

/** What the background queue asks of the library (§6.4), so its runner is tested without one. */
interface SpatialAheadTarget {
    fun hasLocal(videoId: String, profile: SpatialProfile): Boolean
    /** Under way (a lookup, a download, a job), waiting for the network or the cap, or waiting out a failure. */
    fun isBusy(videoId: String, profile: SpatialProfile): Boolean
    /** §6.2 steps 1-2 for one song, with the queue's own network rule; never sends a job. */
    suspend fun lookUp(songId: String, videoId: String, profile: SpatialProfile): SharedLookup
    /** §6.2 step 3 for one song, as the background queue's job. */
    suspend fun sendAhead(songId: String, videoId: String, profile: SpatialProfile): CloudLibrarySend
    fun aheadSentToday(): Int
}

/**
 * The cloud side of Spatial audio ([SpatialLibrary], cloud-audio-spec §3, §6.1, §6.2, §6.4) over Cloud Studio:
 *
 * - [localRender]: the local store ([SpatialRenderStore]), from memory or one file check.
 * - [request]: §6.2 in order. Nothing while either switch is off ([cloudRendersOn]). Else, on a network the person
 *   allows for cloud work, a signed GET of the render's manifest in the shared library; a valid one brings the FLAC
 *   down, verified, into the store. Else a cloud job (`tasks: ["spatial"]`, one render) through Cloud Studio's engine,
 *   inside the library's own month. What can't start yet waits with its reason (`waitingForNetwork`, `capReached`)
 *   and starts by itself when the network comes back, the month turns or the cap is raised.
 * - [status]: §6.1's state from the store, what the library is doing, the song's library job and the failures; a
 *   failure holds a new attempt back for 10 minutes, then 1 hour, then 1 day, kept across restarts.
 * - The background queue of §6.4 runs through [SpatialAheadTarget]; its WorkManager schedule follows the switches.
 */
class CloudSpatialLibrary(
    private val engine: SpatialCloudEngine,
    private val store: SpatialRenderStore,
    settings: Flow<SpatialSettings>,
    private val cloudSettings: StateFlow<CloudSettingsSnapshot>,
    private val networkSource: SpatialNetworkSource,
    private val ledger: SpatialFailureLedger,
    /** Turns the background queue's schedule on or off (§6.4). */
    private val aheadSchedule: (Boolean) -> Unit,
    /** Where shared-library downloads land before the store checks them. */
    private val stagingDir: File,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SpatialLibrary, SpatialAheadTarget {

    private data class Want(val songId: String, val ahead: Boolean)

    /** The settings once the store has been read (null before: nothing is decided on a default). */
    private val spatialSettings = MutableStateFlow<SpatialSettings?>(null)
    private val _cloudRendersOn = MutableStateFlow(false)
    private val activities = MutableStateFlow<Map<SpatialRenderKey, SpatialActivity>>(emptyMap())
    /** Wants that can't start yet, oldest first (guarded by [lock]). */
    private val parked = LinkedHashMap<SpatialRenderKey, Want>()
    private val lock = Mutex()

    override val cloudRendersOn: StateFlow<Boolean> = _cloudRendersOn.asStateFlow()

    override val events: SharedFlow<SpatialLibraryEvent> get() = store.events

    override val stats: StateFlow<SpatialLibraryStats> = combine(store.usage, engine.jobs) { usage, _ ->
        SpatialLibraryStats(
            songsReady = usage.songs,
            bytesOnPhone = usage.bytes,
            spentThisMonthMicroUsd = engine.libraryCommittedMicroUsd(),
            sentToday = engine.aheadSentToday(),
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, SpatialLibraryStats())

    private data class StatusInputs(
        val network: SpatialNetwork = SpatialNetwork(),
        val useCellular: Boolean = false,
        val capMicroUsd: Long? = 0,
        val failuresVersion: Long = 0,
    )

    private val statusInputs: StateFlow<StatusInputs> = combine(
        networkSource.network, cloudSettings, spatialSettings.filterNotNull(), ledger.version,
    ) { network, cloud, spatial, version -> StatusInputs(network, cloud.useCellular, spatial.monthlyCapMicroUsd, version) }
        .stateIn(scope, SharingStarted.Eagerly, StatusInputs())

    init {
        scope.launch {
            stagingDir.listFiles()?.forEach { it.delete() } // what a stopped download left
            store.ensureIndexed()
        }
        scope.launch { engine.load() }
        scope.launch {
            var before: SpatialSettings? = null
            combine(settings, cloudSettings) { spatial, cloud -> spatial to cloud }.collect { (spatial, cloud) ->
                onSettings(spatial, cloud, before)
                before = spatial
            }
        }
        scope.launch { engine.jobs.collect { recordOutcomes(it) } }
        scope.launch { networkSource.network.collect { if (it.connected) resume(SpatialActivity.WAITING_FOR_NETWORK) } }
        scope.launch {
            // The month turns, a job's estimate settles into its real cost: a want held by the cap looks again.
            while (isActive) {
                delay(CAP_RECHECK_MS)
                resume(SpatialActivity.CAP_REACHED)
            }
        }
    }

    // ─── SpatialLibrary ─────────────────────────────────────────────────────────────────────

    override suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender? =
        store.localRender(videoId, profile)

    override fun request(songId: String, videoId: String, profile: SpatialProfile) {
        if (!CloudKeys.isValidVideoId(videoId) || !_cloudRendersOn.value) return
        scope.launch { requestNow(songId, videoId, profile) }
    }

    /** [request], done in the caller (the tests wait for it). */
    internal suspend fun requestNow(songId: String, videoId: String, profile: SpatialProfile) {
        if (!CloudKeys.isValidVideoId(videoId)) return
        pursue(SpatialRenderKey(videoId, profile), Want(songId, ahead = false))
    }

    override fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus> {
        val key = SpatialRenderKey(videoId, profile)
        return combine(store.renders, activities, engine.jobs, statusInputs) { renders, acts, jobs, inputs ->
            statusOf(key, renders.containsKey(key), acts[key], jobs, inputs)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override fun markPlayed(videoId: String, profile: SpatialProfile) = store.markPlayed(videoId, profile)

    override suspend fun removeAll() = store.removeAll()

    // ─── SpatialAheadTarget ─────────────────────────────────────────────────────────────────

    override fun hasLocal(videoId: String, profile: SpatialProfile): Boolean = store.localRender(videoId, profile) != null

    override fun isBusy(videoId: String, profile: SpatialProfile): Boolean {
        val key = SpatialRenderKey(videoId, profile)
        return activities.value.containsKey(key) || pendingJob(engine.currentJobs(), key) != null ||
            SpatialBackoff.isWaiting(ledger.failure(key), nowMs())
    }

    override suspend fun lookUp(songId: String, videoId: String, profile: SpatialProfile): SharedLookup {
        val key = SpatialRenderKey(videoId, profile)
        if (!networkSource.network.value.allowsAhead) return SharedLookup.BUSY
        if (claim(key, Want(songId, ahead = true)) != Claim.CLAIMED) return SharedLookup.BUSY
        return try {
            fetchShared(key).also { result ->
                if (result == SharedLookup.ERROR) failed(key) else release(key)
            }
        } catch (error: CancellationException) {
            release(key)
            throw error
        } catch (error: Exception) {
            failed(key)
            SharedLookup.ERROR
        }
    }

    override suspend fun sendAhead(songId: String, videoId: String, profile: SpatialProfile): CloudLibrarySend =
        if (!_cloudRendersOn.value) CloudLibrarySend.Refused("Cloud renders are off.")
        else engine.sendLibraryJob(songId, videoId, profile.id, ahead = true)

    override fun aheadSentToday(): Int = engine.aheadSentToday()

    // ─── Getting a render (§6.2) ────────────────────────────────────────────────────────────

    private enum class Claim { CLAIMED, NOT_NEEDED, BUSY, PROMOTE }

    /** Steps 1-3 for one wanted render. Never throws; a failure is recorded for the backoff. */
    private suspend fun pursue(key: SpatialRenderKey, want: Want) {
        when (claim(key, want)) {
            Claim.CLAIMED -> Unit
            Claim.PROMOTE -> {
                // The background queue's job for this song is the person's now: it follows their network rule.
                runCatching { engine.sendLibraryJob(want.songId, key.videoId, key.profile.id, ahead = false) }
                return
            }
            Claim.NOT_NEEDED, Claim.BUSY -> return
        }
        try {
            if (!networkAllows(want.ahead)) {
                park(key, want, SpatialActivity.WAITING_FOR_NETWORK)
                return
            }
            when (fetchShared(key)) {
                SharedLookup.FOUND, SharedLookup.BUSY -> {
                    release(key)
                    return
                }
                SharedLookup.ERROR -> {
                    failed(key)
                    return
                }
                SharedLookup.MISSING -> Unit
            }
            setActivity(key, SpatialActivity.SENDING)
            when (engine.sendLibraryJob(want.songId, key.videoId, key.profile.id, want.ahead)) {
                is CloudLibrarySend.Sent -> release(key) // the job carries the status from here
                CloudLibrarySend.CapReached -> park(key, want, SpatialActivity.CAP_REACHED)
                is CloudLibrarySend.Refused -> release(key)
            }
        } catch (error: CancellationException) {
            release(key)
            throw error
        } catch (error: Exception) {
            failed(key)
        }
    }

    /**
     * Takes [key] for one attempt, or says why not: switched off or already on the phone ([Claim.NOT_NEEDED]), under
     * way or waiting out a failure ([Claim.BUSY]), or carried by the background queue's job ([Claim.PROMOTE] when the
     * person now wants it, else busy). A want that waited for the network or the cap is taken again.
     */
    private suspend fun claim(key: SpatialRenderKey, want: Want): Claim = lock.withLock {
        if (!_cloudRendersOn.value) return@withLock Claim.NOT_NEEDED
        if (store.localRender(key.videoId, key.profile) != null) return@withLock Claim.NOT_NEEDED
        when (activities.value[key]) {
            SpatialActivity.LOOKUP, SpatialActivity.FETCH, SpatialActivity.SENDING -> return@withLock Claim.BUSY
            else -> Unit
        }
        pendingJob(engine.currentJobs(), key)?.let { job ->
            return@withLock if (!want.ahead && job.isLibraryAhead) Claim.PROMOTE else Claim.BUSY
        }
        if (SpatialBackoff.isWaiting(ledger.failure(key), nowMs())) return@withLock Claim.BUSY
        parked.remove(key)
        setActivity(key, SpatialActivity.LOOKUP)
        Claim.CLAIMED
    }

    /**
     * §6.2 step 2: a signed GET of `lib/v1/<videoId>/sp-<profile>.json`; a manifest that is the one asked for (§3.1)
     * brings the FLAC down, and the store takes it once its size and SHA-256 match. Network errors throw.
     */
    private suspend fun fetchShared(key: SpatialRenderKey): SharedLookup {
        val manifestBytes = engine.fetchLibraryObject(CloudKeys.libraryManifest(key.videoId, key.profile.id))
            ?: return SharedLookup.MISSING
        val manifest = SpatialManifests.decode(manifestBytes)
        if (manifest == null || SpatialManifests.refusal(manifest, key.videoId, key.profile) != null) return SharedLookup.MISSING
        setActivity(key, SpatialActivity.FETCH)
        stagingDir.mkdirs()
        val staged = File(stagingDir, "lib-${key.videoId}-sp-${key.profile.id}.flac")
        when (val result = engine.downloadLibraryObject(CloudKeys.libraryAudio(key.videoId, key.profile.id), staged)) {
            CloudTransferResult.Ok -> Unit
            is CloudTransferResult.Failed -> {
                staged.delete()
                // The janitor removes the manifest before the audio; a missing file means the render is going away.
                return if (result.status == 404) SharedLookup.MISSING else SharedLookup.ERROR
            }
        }
        val refusal = store.installRender(key.videoId, key.profile.id, staged, manifestBytes)
        if (refusal != null) return SharedLookup.ERROR
        ledger.clear(key, nowMs() + 1)
        return SharedLookup.FOUND
    }

    private fun networkAllows(ahead: Boolean): Boolean {
        val network = networkSource.network.value
        return if (ahead) network.allowsAhead else network.allowsCloudWork(cloudSettings.value.useCellular)
    }

    private fun setActivity(key: SpatialRenderKey, activity: SpatialActivity) {
        activities.update { it + (key to activity) }
    }

    private suspend fun release(key: SpatialRenderKey) {
        lock.withLock { parked.remove(key) }
        activities.update { it - key }
    }

    private suspend fun failed(key: SpatialRenderKey) {
        ledger.recordFailure(key, nowMs())
        release(key)
    }

    private suspend fun park(key: SpatialRenderKey, want: Want, reason: SpatialActivity) {
        val dropped = lock.withLock {
            parked.remove(key)
            parked[key] = want
            if (parked.size > MAX_PARKED) parked.keys.first().also { parked.remove(it) } else null
        }
        activities.update { current -> (if (dropped != null) current - dropped else current) + (key to reason) }
    }

    /** Wants waiting for [reason] try again (each re-checks everything). */
    private suspend fun resume(reason: SpatialActivity) {
        if (!_cloudRendersOn.value) return
        val due = lock.withLock { parked.filterKeys { activities.value[it] == reason }.toList() }
        for ((key, want) in due) scope.launch { pursue(key, want) }
    }

    // ─── Settings and outcomes ──────────────────────────────────────────────────────────────

    /** Waits until the stored settings were read once (a fresh background process decides nothing on defaults). */
    suspend fun awaitSettings() {
        spatialSettings.filterNotNull().first()
    }

    private suspend fun onSettings(spatial: SpatialSettings, cloud: CloudSettingsSnapshot, before: SpatialSettings?) {
        val on = cloud.enabled && spatial.cloud
        _cloudRendersOn.value = on
        spatialSettings.value = spatial
        aheadSchedule(on && spatial.prepareAhead && spatial.profile != null)
        if (!on) {
            // Off means nothing waits to start either (§6.1 "Two switches").
            lock.withLock { parked.clear() }
            activities.update { current -> current.filterValues { it == SpatialActivity.LOOKUP || it == SpatialActivity.FETCH || it == SpatialActivity.SENDING } }
        }
        try {
            if (!spatial.cloud) {
                // Spatial's own switch went off: its jobs still on the phone stop (those at RunPod finish, unpaid-for
                // work never starts). The master switch alone keeps Cloud Studio's queue as it is, like Sing's jobs.
                engine.cancelPendingLibrary { true }
            } else {
                val selected = spatial.profile?.id
                engine.cancelPendingLibrary { job ->
                    job.isLibraryAhead && (!spatial.prepareAhead || selected == null || job.library?.profiles?.contains(selected) != true)
                }
            }
            if (before != null && spatial.storageMb < before.storageMb) store.trimToBudget()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The next change, or the next pass, tries again.
        }
        if (on && before != null && before.monthlyCapUsd != spatial.monthlyCapUsd) resume(SpatialActivity.CAP_REACHED)
    }

    /** Library jobs that ended feed the failure backoff: a failed job or render counts once, a delivered one clears it. */
    private fun recordOutcomes(jobs: List<CloudJobRecord>) {
        for (record in jobs) {
            val job = record.library ?: continue
            val at = record.updatedAtMs ?: record.createdAtMs
            for (profile in job.profiles.mapNotNull(SpatialProfile::fromId)) {
                val key = SpatialRenderKey(job.videoId, profile)
                when (record.state) {
                    CloudJobState.FAILED, CloudJobState.EXPIRED -> ledger.recordFailure(key, at)
                    CloudJobState.IMPORTED -> when (job.renders?.get(profile.id)) {
                        CloudLibraryRenderState.FAILED -> ledger.recordFailure(key, at)
                        CloudLibraryRenderState.OK, CloudLibraryRenderState.EXISTS ->
                            if (profile.id in job.imported) ledger.clear(key, at)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
    }

    // ─── Status (§6.1) ──────────────────────────────────────────────────────────────────────

    private fun statusOf(
        key: SpatialRenderKey,
        inStore: Boolean,
        activity: SpatialActivity?,
        jobs: List<CloudJobRecord>,
        inputs: StatusInputs,
    ): SpatialRenditionStatus {
        val hasRender = inStore || store.localRender(key.videoId, key.profile) != null
        val job = pendingJob(jobs, key)
        val capReached = job != null && job.state == CloudJobState.UPLOADED && inputs.capMicroUsd.let { cap ->
            cap != null && !CloudBudget.allowsLibrary(
                engine.libraryJobEstimateMicroUsd(job.durationMs ?: job.songDurationMs ?: CloudCost.REFERENCE_SONG_MS),
                cap, engine.libraryCommittedMicroUsd(),
            )
        }
        return SpatialStatusRules.status(
            hasRender = hasRender,
            activity = activity,
            job = job,
            failed = ledger.failure(key) != null,
            personNetworkOk = inputs.network.allowsCloudWork(inputs.useCellular),
            aheadNetworkOk = inputs.network.allowsAhead,
            capReached = capReached,
        )
    }

    private fun pendingJob(jobs: List<CloudJobRecord>, key: SpatialRenderKey): CloudJobRecord? = jobs.lastOrNull { record ->
        val job = record.library
        job != null && record.state.isPending && job.videoId == key.videoId && key.profile.id in job.profiles
    }

    companion object {
        /** Wants kept waiting at most (the oldest go first): songs played on mobile data, say. */
        const val MAX_PARKED = 200
        const val CAP_RECHECK_MS = 15L * 60_000
    }
}
