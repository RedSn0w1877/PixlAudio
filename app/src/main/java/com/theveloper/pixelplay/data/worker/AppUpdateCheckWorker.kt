package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.update.AppUpdateManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Twice-daily check against the GitHub releases. Failures are silent; the next run retries. */
@HiltWorker
class AppUpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val manager: AppUpdateManager
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        manager.runBackgroundCheck()
        return Result.success()
    }
}
