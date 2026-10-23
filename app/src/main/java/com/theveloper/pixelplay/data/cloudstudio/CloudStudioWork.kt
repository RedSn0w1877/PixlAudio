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
import androidx.work.OutOfQuotaPolicy
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
        // The stored jobs first: whether anything here is the person's (not just automatic preparing) decides whether
        // the "Cloud processing" notification is worth showing at all.
        val quiet = try {
            engine.loadForDisplay()
            !engine.hasWorkForThePerson()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
        val foreground = !quiet && promoteToForeground()
        val budgetEnd = started + if (foreground) FOREGROUND_BUDGET_MS else BACKGROUND_BUDGET_MS
        return try {
            while (true) {
                reportProgress()
                val outcome = engine.workerPass(deadlineMs = budgetEnd)
                reportProgress()
                if (outcome.blocked || !outcome.pending) break
                val now = System.currentTimeMillis()
                // A Sing is at RunPod and the person is waiting for it: look again every few seconds while the pass has
                // time, so the instrumental arrives without the app being open to poll for it.
                if (engine.isWaitingOnSing() && now + CloudTiming.SING_POLL_INTERVAL_MS < budgetEnd) {
                    delay(CloudTiming.SING_POLL_INTERVAL_MS)
                    continue
                }
                val wake = outcome.nextWakeAtMs ?: break
                val wait = wake - now
                if (wait > MAX_INLINE_WAIT_MS || wake > budgetEnd - INLINE_WAIT_MARGIN_MS) break
                // Slept in short steps: a Sing tapped meanwhile is picked up at once, not at the end of a batch gate.
                var remaining = wait.coerceAtLeast(1_000)
                while (remaining > 0 && !engine.hasPriorityPhoneWork()) {
                    val step = minOf(remaining, PRIORITY_CHECK_STEP_MS)
                    delay(step)
                    remaining -= step
                }
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

    /** Home's active-jobs sheet shows "Cloud: 3 waiting, 1 processing" (iOS parity). */
    private suspend fun reportProgress() {
        val line = engine.summaryLine() ?: return
        runCatching { setProgress(androidx.work.workDataOf("detail" to line)) }
            .exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

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
        /** An expedited pass for a priority job (a Sing); it carries [PASS_WORK] as a tag too, so it shows as Cloud processing. */
        const val URGENT_WORK = "cloud_studio_urgent"
        private const val NOTIFICATION_ID = 4210
        /** A plain background job has 10 minutes; leave room to save and stop cleanly. */
        private const val BACKGROUND_BUDGET_MS = 8L * 60_000
        private const val FOREGROUND_BUDGET_MS = 45L * 60_000
        private const val MAX_INLINE_WAIT_MS = 3L * 60_000
        private const val INLINE_WAIT_MARGIN_MS = 60_000L
        private const val PRIORITY_CHECK_STEP_MS = 2_000L
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

    override fun requestUrgentPass() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                // A pass that is already running re-reads the job list before every step and picks the priority job
                // first, so it needs no second worker; only when none is running (or one waits for its turn) is an
                // expedited one worth asking for. Out of expedited quota it runs as a plain pass, like requestPass.
                val running = listOf(CloudStudioWorker.PASS_WORK, CloudStudioWorker.WIFI_WORK, CloudStudioWorker.WATCH_WORK)
                    .any { name ->
                        workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).any { it.state == WorkInfo.State.RUNNING }
                    }
                val urgent = workManager.getWorkInfosForUniqueWork(CloudStudioWorker.URGENT_WORK).get(5, TimeUnit.SECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                if (running || urgent) return@runCatching
                val request = OneTimeWorkRequestBuilder<CloudStudioWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(PIXELPLAY_JOB_TAG)
                    .addTag(CloudStudioWorker.PASS_WORK)
                    .addTag(CloudStudioWorker.URGENT_WORK)
                    .build()
                workManager.enqueueUniqueWork(CloudStudioWorker.URGENT_WORK, ExistingWorkPolicy.KEEP, request)
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
