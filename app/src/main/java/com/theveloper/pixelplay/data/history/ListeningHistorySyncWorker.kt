package com.theveloper.pixelplay.data.history

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Pulls recent listening from Spotify (and YouTube Music when signed in) in the background. Enqueued on app
 * start and after the Spotify library sync and the periodic library housekeeping; the repository itself keeps
 * real syncs at least six hours apart, so enqueueing often is cheap.
 */
@HiltWorker
class ListeningHistorySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: ListeningHistoryRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        if (inputData.getBoolean(INPUT_FORCE, false)) repository.sync(force = true) else repository.syncIfDue()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Less data is the only cost of a failed sync; the next app start tries again.
        Timber.w(e, "Listening history sync failed")
        Result.success()
    }

    companion object {
        const val WORK_NAME = "listening_history_sync"
        private const val INPUT_FORCE = "force"

        /** KEEP: a sync already queued or running covers this request. */
        fun enqueue(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ListeningHistorySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(INPUT_FORCE to force))
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
