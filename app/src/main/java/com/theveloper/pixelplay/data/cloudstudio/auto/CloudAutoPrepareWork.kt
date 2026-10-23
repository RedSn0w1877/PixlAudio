package com.theveloper.pixelplay.data.cloudstudio.auto

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudSong
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.worker.AutomaticStudioCooldowns
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

// The WorkManager side of Smart auto-prepare: a periodic worker every 6 hours that runs [CloudAutoPrepareRunner], and the
// scheduler that turns it on and off with the switch. The worker shows no notification and isn't tagged as a visible job:
// automatic preparing is quiet by design.

/** Runs [CloudAutoPrepareRunner] every 6 hours, only on Wi-Fi, on a charger, with the battery and storage not low. */
@HiltWorker
class CloudAutoPrepareWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: CloudAutoPrepareCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            coordinator.run(deadlineMs = System.currentTimeMillis() + BUDGET_MS)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Every job's state is saved; the next window (or the cloud watch) carries on.
            Timber.w("Auto-prepare run failed: %s", error.javaClass.simpleName)
            Result.success()
        }
    }

    private companion object {
        /** A plain background job has 10 minutes; leave room to save and stop cleanly (the same budget as a cloud pass). */
        const val BUDGET_MS = 8L * 60_000
    }
}

/** Turns the periodic worker on while "Prepare songs ahead in the cloud" and Cloud processing are both on. */
@Singleton
class CloudAutoPrepareScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settings: CloudStudioSettings,
) {
    /** Matches the worker to the switches now (called when a setting changes and at app start). */
    fun sync() {
        val snapshot = settings.snapshot()
        if (snapshot.enabled && snapshot.autoPrepareEnabled) enable() else disable()
    }

    fun enable() {
        runCatching {
            // UPDATE so a changed rule reaches installs that already have the worker.
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, buildRequest())
        }.onFailure { Timber.d(it, "Unable to schedule auto-prepare") }
    }

    fun disable() {
        runCatching { workManager.cancelUniqueWork(WORK_NAME) }
            .onFailure { Timber.d(it, "Unable to cancel auto-prepare") }
    }

    companion object {
        const val WORK_NAME = "cloud_auto_prepare"
        const val PERIOD_HOURS = 6L

        /** Wi-Fi, a charger, battery and storage not low: the worker doesn't start otherwise. */
        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<CloudAutoPrepareWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()
    }
}

/** The songs that rest after being picked, kept in a small preference file (the same ledger shape as the on-device studio's). */
class CloudAutoCooldownStore(context: Context) : CloudAutoCooldowns {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val cooldowns by lazy {
        AutomaticStudioCooldowns(prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap())
    }

    @Synchronized
    override fun isResting(songId: String, nowMs: Long): Boolean = cooldowns.until(key(songId)) > nowMs

    @Synchronized
    override fun rest(songId: String, untilMs: Long) {
        cooldowns.record(key(songId), untilMs)
        prefs.edit().clear().apply { cooldowns.snapshot().forEach { (id, until) -> putLong(id, until) } }.apply()
    }

    private fun key(songId: String) = "$KEY_PREFIX$songId"

    private companion object {
        const val FILE = "cloud_auto_prepare_cooldowns_v1"
        const val KEY_PREFIX = "CLOUD_AUTO:"
    }
}

/** [CloudAutoEngine] over the real [CloudStudioEngine]. */
class CloudStudioAutoEngine(private val engine: CloudStudioEngine) : CloudAutoEngine {
    override suspend fun load() = engine.loadForDisplay()
    override fun usable() = engine.autoPrepareUsable()
    override fun personHasWork() = engine.hasWorkForThePerson()
    override fun pendingCanonicalIds() = engine.state.value.jobs.filter { it.state.isPending }
        .mapTo(HashSet()) { TaisInstrumentalIndex.canonicalSongId(it.songId) }
    override fun outstandingAutoJobs() = engine.state.value.jobs.count { it.origin == CloudJobOrigin.AUTO && it.state.isPending }
    override fun autoCommittedMicroUsd() = engine.autoCommittedThisMonthMicroUsd()
    override fun committedMicroUsd() = engine.committedThisMonthMicroUsd()
    override suspend fun sendAuto(songs: List<CloudSong>) = engine.sendAuto(songs)
    override suspend fun workerPass(deadlineMs: Long) {
        engine.workerPass(deadlineMs)
    }
}

/** Wires the real parts into a [CloudAutoPrepareRunner] for the worker (and for tests of the wiring). */
@Singleton
class CloudAutoPrepareCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: CloudStudioSettings,
    private val engine: CloudStudioEngine,
    private val gatherer: CloudAutoFactsGatherer,
    private val status: CloudAutoPrepareStatus,
) {
    private val runner by lazy {
        CloudAutoPrepareRunner(
            settings = settings,
            environment = LiveCloudAutoEnvironment(context),
            facts = gatherer,
            engine = CloudStudioAutoEngine(engine),
            cooldowns = CloudAutoCooldownStore(context),
            report = status,
        )
    }

    suspend fun run(deadlineMs: Long): CloudAutoRunResult {
        val result = runner.run(deadlineMs)
        Timber.d("Auto-prepare: %s (%d added)", result.outcome, result.added)
        return result
    }
}
