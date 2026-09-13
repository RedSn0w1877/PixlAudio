package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Lightweight WorkManager wake-up for quiet library processing. The actual DSP remains in the
 * existing one-at-a-time workers; this worker only asks the coordinator to choose the next song.
 * It has no foreground service and posts no notification.
 */
@HiltWorker
class AutomaticStudioSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val manager: AutomaticStudioManager
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        manager.runBackgroundSweep()
        return Result.success()
    }
}
