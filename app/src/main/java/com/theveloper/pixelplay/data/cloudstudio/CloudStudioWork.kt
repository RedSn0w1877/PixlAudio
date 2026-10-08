package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.worker.PIXELPLAY_JOB_TAG
import com.theveloper.pixelplay.data.worker.TaisForegroundNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs Cloud Studio passes in the background (design §8: WorkManager for upload, submit, poll and import, so
 * "process later" works with the app closed). Three kinds of work share this worker:
 * - `cloud_studio_pass`: a pass soon after Send, Retry, or a pass that ran out of time (any network);
 * - `cloud_studio_wifi`: a pass once an unmetered network is there, for transfers waiting on Wi-Fi;
 * - `cloud_studio_watch`: every 15 minutes while jobs are in flight (WorkManager's minimum), cancelled when the
 *   queue is empty.
 *
 * Preparing and uploading take minutes, so the worker promotes itself to a foreground "Cloud processing"
 * notification when it can (always when the person just tapped Send). Android 12+ refuses that from the background;
 * the pass then stops in time for WorkManager's 10-minute limit and a follow-up pass carries on. Short waits (a batch
 * gate, the first retry) are slept through here rather than scheduled.
 */
@HiltWorker
class CloudStudioWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: CloudStudioEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val foreground = promoteToForeground()
        val budgetEnd = started + if (foreground) FOREGROUND_BUDGET_MS else BACKGROUND_BUDGET_MS
        return try {
            while (true) {
                val outcome = engine.workerPass(deadlineMs = budgetEnd)
                if (outcome.blocked || !outcome.pending) break
                val now = System.currentTimeMillis()
                val wake = outcome.nextWakeAtMs ?: break
                val wait = wake - now
                if (wait > MAX_INLINE_WAIT_MS || wake > budgetEnd - INLINE_WAIT_MARGIN_MS) break
                delay(wait.coerceAtLeast(1_000))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Every job's state is already saved; the watch or the next pass picks up from there.
            Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = TaisForegroundNotifications.foregroundInfo(
        context = applicationContext,
        notificationId = NOTIFICATION_ID,
        title = applicationContext.getString(com.theveloper.pixelplay.R.string.cloud_processing_title),
        text = applicationContext.getString(com.theveloper.pixelplay.R.string.cloud_processing_notification),
        progress = 0,
        indeterminate = true,
    )

    private suspend fun promoteToForeground(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // ForegroundServiceStartNotAllowedException when the app isn't visible (Android 12+): run as a plain job.
        false
    }

    companion object {
        const val PASS_WORK = "cloud_studio_pass"
        const val WIFI_WORK = "cloud_studio_wifi"
        const val WATCH_WORK = "cloud_studio_watch"
        private const val NOTIFICATION_ID = 4210
        /** A plain background job has 10 minutes; leave room to save and stop cleanly. */
        private const val BACKGROUND_BUDGET_MS = 8L * 60_000
        private const val FOREGROUND_BUDGET_MS = 45L * 60_000
        private const val MAX_INLINE_WAIT_MS = 3L * 60_000
        private const val INLINE_WAIT_MARGIN_MS = 60_000L
    }
}

/** [CloudWorkScheduler] over WorkManager. Calls never block the caller (WorkManager queries run on IO). */
class CloudStudioScheduler(
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
) : CloudWorkScheduler {

    override fun requestPass() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                // At most one pass waits behind a running one (APPEND keeps a running pass from being replaced, and
                // lets a pass ask for its own follow-up).
                val infos = workManager.getWorkInfosForUniqueWork(CloudStudioWorker.PASS_WORK).get(5, TimeUnit.SECONDS)
                if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@runCatching
                val request = OneTimeWorkRequestBuilder<CloudStudioWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(PIXELPLAY_JOB_TAG)
                    .addTag(CloudStudioWorker.PASS_WORK)
                    .build()
                workManager.enqueueUniqueWork(CloudStudioWorker.PASS_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
    }

    override fun requestUnmeteredPass() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CloudStudioWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .addTag(PIXELPLAY_JOB_TAG)
                    .addTag(CloudStudioWorker.WIFI_WORK)
                    .build()
                workManager.enqueueUniqueWork(CloudStudioWorker.WIFI_WORK, ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    override fun ensureWatch() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<CloudStudioWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(CloudStudioWorker.WATCH_WORK)
                    .build()
                workManager.enqueueUniquePeriodicWork(CloudStudioWorker.WATCH_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }

    override fun cancelWatch() {
        scope.launch(Dispatchers.IO) {
            runCatching { workManager.cancelUniqueWork(CloudStudioWorker.WATCH_WORK) }
        }
    }
}
