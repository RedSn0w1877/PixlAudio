package com.theveloper.pixelplay.data.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Schedules the brain's one background job (docs/brain-spec.md, 3.7): unique periodic work
 * `brain_refresh`, 24 hours, an unmetered network, a battery that is not low, and a random initial
 * delay of up to 60 minutes so a population of phones does not wake up together.
 *
 * Called after the brain has been used (the first mix build), never at app start. KEEP makes it
 * idempotent: calling it again on every build changes nothing and costs one WorkManager lookup.
 */
@Singleton
class BrainRefreshScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    @Volatile
    private var ensuredInThisProcess = false

    fun ensureScheduled() {
        if (ensuredInThisProcess) return
        try {
            workManager.enqueueUniquePeriodicWork(
                BrainRefreshWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request(),
            )
            ensuredInThisProcess = true
        } catch (e: Exception) {
            Timber.w(e, "brain: scheduling the refresh failed")
        }
    }

    /** Takes the job away again (the switch is off for good). */
    fun cancel() {
        runCatching { workManager.cancelUniqueWork(BrainRefreshWorker.WORK_NAME) }
        ensuredInThisProcess = false
    }

    companion object {
        const val PERIOD_HOURS = 24L
        const val MAX_INITIAL_DELAY_MINUTES = 60L

        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun request(initialDelayMinutes: Long = (Math.random() * (MAX_INITIAL_DELAY_MINUTES + 1)).toLong()) =
            PeriodicWorkRequestBuilder<BrainRefreshWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .addTag(BrainRefreshWorker.WORK_NAME)
                .addTag(PIXELPLAY_JOB_TAG)
                .build()
    }
}
