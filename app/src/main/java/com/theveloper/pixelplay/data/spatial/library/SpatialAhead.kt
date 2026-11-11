package com.theveloper.pixelplay.data.spatial.library

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.cloudstudio.CloudLibrarySend
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import com.theveloper.pixelplay.data.cloudstudio.auto.AutoCandidateFacts
import com.theveloper.pixelplay.data.cloudstudio.auto.AutoRanked
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoFacts
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoFactsGatherer
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepare
import com.theveloper.pixelplay.data.cloudstudio.auto.LiveCloudAutoEnvironment
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.data.spatial.SpatialSettingsRepository
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import com.theveloper.pixelplay.data.worker.PIXELPLAY_JOB_TAG
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// Spatial audio's background queue (cloud-audio-spec §6.4): the selected profile only, for the songs Sing's
// prepare-ahead would rank first. For each, the local store and the shared library first (downloads are cheap), then
// cloud jobs within `cloudAudio.dailyAhead` and the library's month. Wi-Fi only, battery not low, never under Data
// Saver; a song whose audio isn't on the phone may be fetched for it. Quiet: no notification, only the Active jobs row
// while a run works.

/** The background queue's ranking: Sing's prepare-ahead score, without its rules about instrumentals. */
object SpatialAheadRanker {
    /**
     * §6.4: "the same ranking Sing's prepare-ahead uses (favourites, play count, recency, skip rate)". That ranking
     * leaves out songs that have an instrumental or a Sing job, which says nothing about a render, so those two facts
     * are taken out; its other rules stay (1 to 8 minutes long, not skipped too often, no blocked artist).
     */
    fun rank(library: List<AutoCandidateFacts>, nowMs: Long): List<AutoRanked> =
        CloudAutoPrepare.rank(library.map { it.copy(hasInstrumental = false, hasPendingJob = false) }, nowMs)
}

/** What one run of the background queue did. */
data class SpatialAheadRun(
    val outcome: Outcome,
    /** Why it didn't run ([Outcome.WAITING]). */
    val waitingFor: String? = null,
    /** Songs looked up in the shared library, renders that came down from it, and cloud jobs sent. */
    val looked: Int = 0,
    val fetched: Int = 0,
    val sent: Int = 0,
) {
    enum class Outcome {
        /** No profile, the queue or a cloud switch is off. */
        OFF,

        /** The phone's conditions say no (§6.4). */
        WAITING,

        /** It ran. */
        RAN,
    }
}

/** One run of the background queue, free of WorkManager (tested on fakes). */
class SpatialAheadRunner(
    private val settings: suspend () -> SpatialSettings,
    /** Both cloud switches (§6.1 "Two switches"). */
    private val cloudRendersOn: () -> Boolean,
    private val conditions: () -> SpatialAheadConditions,
    private val facts: CloudAutoFacts,
    /** The matched YouTube video of a song, or null (not matched yet, or not a streamed song). */
    private val videoIdOf: suspend (AutoCandidateFacts) -> String?,
    private val target: SpatialAheadTarget,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The Active jobs row's line while the run works. */
    private val report: suspend (String) -> Unit = {},
) {
    suspend fun run(deadlineMs: Long): SpatialAheadRun {
        val spatial = settings()
        val profile = spatial.profile
        if (profile == null || !spatial.prepareAhead || !spatial.cloud || !cloudRendersOn()) {
            return SpatialAheadRun(SpatialAheadRun.Outcome.OFF)
        }
        SpatialAheadRules.block(conditions())?.let { return SpatialAheadRun(SpatialAheadRun.Outcome.WAITING, waitingFor = it) }
        val now = nowMs()
        val ranked = SpatialAheadRanker.rank(facts.gather(emptySet(), now), now)
        // 6.2 steps 1-2 first, over the best-ranked songs: the store, then the shared library.
        val misses = mutableListOf<Pair<AutoCandidateFacts, String>>()
        var looked = 0
        var fetched = 0
        // A run lasts minutes: the person may pick another profile, or switch the queue or the cloud off, meanwhile. The
        // settings are read again before every lookup and every job, and the run ends as soon as they changed, so
        // nothing is fetched or sent (and paid for) for a profile nobody selected.
        var stopped = false
        for (candidate in ranked) {
            if (looked >= SpatialAheadRules.CANDIDATES_PER_RUN || fetched >= SpatialAheadRules.FETCHES_PER_RUN) break
            if (nowMs() >= deadlineMs || SpatialAheadRules.block(conditions()) != null) break
            val videoId = videoIdOf(candidate.facts)?.takeIf(CloudKeys::isValidVideoId) ?: continue
            if (target.hasLocal(videoId, profile) || target.isBusy(videoId, profile)) continue
            if (!stillWanted(profile)) {
                stopped = true
                break
            }
            looked++
            report(LOOKING_LINE)
            when (target.lookUp(candidate.facts.songId, videoId, profile)) {
                SharedLookup.FOUND -> fetched++
                SharedLookup.MISSING -> misses += candidate.facts to videoId
                SharedLookup.ERROR, SharedLookup.BUSY -> Unit
            }
        }
        // Then step 3, within the day's count and the library's month.
        var room = SpatialAheadRules.jobRoom(spatial.dailyAhead, target.aheadSentToday())
        var sent = 0
        for ((song, videoId) in misses) {
            if (stopped || room <= 0 || nowMs() >= deadlineMs || SpatialAheadRules.block(conditions()) != null) break
            if (!stillWanted(profile)) break
            report(SENDING_LINE)
            when (target.sendAhead(song.songId, videoId, profile)) {
                is CloudLibrarySend.Sent -> {
                    sent++
                    room--
                }
                CloudLibrarySend.CapReached -> break
                is CloudLibrarySend.Refused -> Unit
            }
        }
        return SpatialAheadRun(SpatialAheadRun.Outcome.RAN, looked = looked, fetched = fetched, sent = sent)
    }

    /** The run's [profile] is still the selected one, with prepare ahead and both cloud switches on. */
    private suspend fun stillWanted(profile: SpatialProfile): Boolean {
        val now = settings()
        return now.profile == profile && now.prepareAhead && now.cloud && cloudRendersOn()
    }

    companion object {
        const val LOOKING_LINE = "Getting Spatial audio ready"
        const val SENDING_LINE = "Sending songs for Spatial audio"
    }
}

/** Wires the real parts into a [SpatialAheadRunner] for the worker. */
@Singleton
class SpatialAheadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: CloudSpatialLibrary,
    private val gatherer: CloudAutoFactsGatherer,
    private val spotifyDao: SpotifyDao,
    private val spatialSettings: SpatialSettingsRepository,
    private val networkConditions: StreamNetworkConditions,
) {
    suspend fun run(deadlineMs: Long, report: suspend (String) -> Unit): SpatialAheadRun {
        library.awaitSettings()
        val environment = LiveCloudAutoEnvironment(context)
        val runner = SpatialAheadRunner(
            settings = { spatialSettings.current() },
            cloudRendersOn = { library.cloudRendersOn.value },
            conditions = {
                val network = networkConditions.current()
                SpatialAheadConditions(
                    SpatialNetwork(
                        connected = network.network != StreamPrefetchPolicy.Network.OFFLINE,
                        unmetered = !network.metered,
                        dataSaver = network.dataSaver,
                    ),
                    batteryPercent = environment.conditions().batteryPercent,
                )
            },
            facts = gatherer,
            videoIdOf = { facts ->
                facts.spotifyId?.let { spotifyId -> withContext(Dispatchers.IO) { spotifyDao.getMatchedVideoId(spotifyId) } }
            },
            target = library,
            report = report,
        )
        return runner.run(deadlineMs)
    }
}

/**
 * One run of the background queue. Tagged as a visible job, so Home's Active jobs sheet shows a "Spatial audio" row
 * while it works, and never a notification (§6.4).
 */
@HiltWorker
class SpatialAheadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SpatialAheadCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val run = coordinator.run(deadlineMs = System.currentTimeMillis() + BUDGET_MS) { line ->
            runCatching { setProgress(workDataOf("detail" to line)) }
                .exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        Timber.d("Spatial audio ahead: %s (%d looked up, %d fetched, %d sent)", run.outcome, run.looked, run.fetched, run.sent)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Everything done so far is saved (renders in the store, jobs in Cloud Studio's list); the next run goes on.
        Timber.w("Spatial audio ahead run failed: %s", error.javaClass.simpleName)
        Result.success()
    }

    companion object {
        /** The run's unique work name, also its tag ([com.theveloper.pixelplay.data.worker.PixelPlayJobKind.SPATIAL_AUDIO]). */
        const val WORK_NAME = "spatial_prepare_ahead"
        /** A plain background job has 10 minutes; leave room to save and stop cleanly. */
        private const val BUDGET_MS = 8L * 60_000
    }
}

/**
 * The background queue's clock: every [SpatialAheadRules.PERIOD_HOURS] hours (on an unmetered network, battery and
 * storage not low) it asks for a run. Not tagged: a periodic job waits between its runs, and that wait is no job.
 */
@HiltWorker
class SpatialAheadTickWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: SpatialAheadScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        scheduler.runNow()
        return Result.success()
    }
}

/** Turns the background queue on and off with the switches (at app start, and whenever a setting changes). */
@Singleton
class SpatialAheadScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val cloudSettings: CloudStudioSettings,
    private val spatialSettings: SpatialSettingsRepository,
) {
    @Volatile private var scheduled: Boolean? = null

    /** On while a profile is selected, prepare ahead is on and both cloud switches are (§6.1, §6.4). */
    fun apply(on: Boolean) {
        if (scheduled == on) return
        scheduled = on
        if (on) enable() else disable()
    }

    /** Matches the schedule to the stored switches now (app start). */
    suspend fun sync() {
        val spatial = spatialSettings.current()
        scheduled = null
        apply(isOn(cloudSettings.snapshot().enabled, spatial))
    }

    /** A run as soon as its conditions hold (one at a time). */
    fun runNow() {
        runCatching { workManager.enqueueUniqueWork(SpatialAheadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, runRequest()) }
            .onFailure { Timber.d(it, "Unable to start Spatial audio's background queue") }
    }

    private fun enable() {
        runCatching {
            // UPDATE so a changed rule reaches installs that already have the clock.
            workManager.enqueueUniquePeriodicWork(TICK_WORK, ExistingPeriodicWorkPolicy.UPDATE, tickRequest())
        }.onFailure { Timber.d(it, "Unable to schedule Spatial audio's background queue") }
    }

    private fun disable() {
        runCatching {
            workManager.cancelUniqueWork(TICK_WORK)
            workManager.cancelUniqueWork(SpatialAheadWorker.WORK_NAME)
        }.onFailure { Timber.d(it, "Unable to cancel Spatial audio's background queue") }
    }

    companion object {
        const val TICK_WORK = "spatial_prepare_ahead_tick"

        fun isOn(masterOn: Boolean, spatial: SpatialSettings): Boolean =
            masterOn && spatial.cloud && spatial.prepareAhead && spatial.profile != null

        /** Wi-Fi (unmetered), battery and storage not low; Data Saver is checked by the run itself. No charger. */
        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        fun tickRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SpatialAheadTickWorker>(SpatialAheadRules.PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()

        fun runRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SpatialAheadWorker>()
            .setConstraints(constraints())
            .addTag(PIXELPLAY_JOB_TAG)
            .addTag(SpatialAheadWorker.WORK_NAME)
            .build()
    }
}
