package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.os.Trace
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnostics
import com.theveloper.pixelplay.utils.AlbumArtCacheManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SyncMode {
    INCREMENTAL,
    FULL,
    REBUILD
}

/**
 * Library housekeeping worker. PixlAudio is Spotify-only: there is no MediaStore / on-device scan
 * any more. This worker (a) purges any leftover on-device song rows from older installs (the
 * "safest" option: a one-shot purge, with RoomSongRepository additionally filtering them out of
 * every query), and (b) on maintenance runs cleans the album-art cache. Spotify rows are never touched.
 */
@HiltWorker
class SyncWorker
@AssistedInject
constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicDao: MusicDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                Trace.beginSection("SyncWorker.doWork")
                try {
                    val runMaintenance = inputData.getBoolean(INPUT_RUN_MAINTENANCE, true)
                    val startTime = System.currentTimeMillis()

                    val purged = musicDao.purgeLocalSongs()
                    if (purged > 0) {
                        Timber.tag(TAG).i("Purged $purged leftover on-device songs (Spotify-only app).")
                    }

                    if (runMaintenance) {
                        setProgress(
                            workDataOf(PROGRESS_PHASE to SyncProgress.SyncPhase.CLEANING_CACHE.ordinal)
                        )
                        val allSongIds = musicDao.getAllSongIds().toSet()
                        AlbumArtCacheManager.cleanOrphanedCacheFiles(applicationContext, allSongIds)
                    }

                    val totalSongs = musicDao.getSongCount().first()
                    AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                        type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                        name = "sync_worker_success"
                    ) {
                        mapOf(
                            "durationMs" to (System.currentTimeMillis() - startTime).toString(),
                            "totalSongs" to totalSongs.toString(),
                            "purgedLocal" to purged.toString()
                        )
                    }
                    Result.success(workDataOf(OUTPUT_TOTAL_SONGS to totalSongs.toLong()))
                } catch (e: Exception) {
                    Log.e(TAG, "Library housekeeping failed", e)
                    Result.failure()
                } finally {
                    Trace.endSection()
                }
            }

    companion object {
        const val WORK_NAME = "com.theveloper.pixelplay.data.worker.SyncWorker"
        // Distinct unique name so background maintenance never feeds the WORK_NAME-bound
        // isSyncing/syncProgress flows — the loading indicator stays silent for it.
        const val PERIODIC_MAINTENANCE_WORK_NAME =
            "com.theveloper.pixelplay.data.worker.SyncWorker.PeriodicMaintenance"
        private const val TAG = "SyncWorker"
        const val INPUT_FORCE_METADATA = "input_force_metadata"
        const val INPUT_RUN_MAINTENANCE = "input_run_maintenance"
        const val INPUT_SYNC_MODE = "input_sync_mode"
        // INCREMENTAL syncs back off this many times while playback is active
        // before running anyway. With WorkManager's exponential backoff
        // (30s -> 60s -> 2m -> 4m -> 8m -> ...), 5 retries cover ~16 minutes.
        private const val MAX_PLAYBACK_DEFERRALS = 5

        // Progress reporting constants
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PHASE = "progress_phase"
        const val OUTPUT_TOTAL_SONGS = "output_total_songs"

        fun startUpSyncWork(deepScan: Boolean = false) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true,
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name
                                )
                        )
                        .addTag(PIXELPLAY_JOB_TAG)
                        .addTag(WORK_NAME)
                        .build()

        fun incrementalSyncWork(
            runMaintenance: Boolean = true
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                                        INPUT_RUN_MAINTENANCE to runMaintenance
                                )
                        )
                        .addTag(PIXELPLAY_JOB_TAG)
                        .addTag(WORK_NAME)
                        .build()

        // Full rescans and rebuilds do heavy bulk writes to Room + the album art cache.
        // Requiring non-critical storage prevents partial/corrupt syncs when the device is
        // nearly full. Not applied to incremental/startup sync so the library still appears
        // immediately when the user opens the app.
        private val heavySyncConstraints: Constraints =
                Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()

        fun fullSyncWork(
            deepScan: Boolean = false
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .addTag(PIXELPLAY_JOB_TAG)
                        .addTag(WORK_NAME)
                        .build()

        fun rebuildDatabaseWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.REBUILD.name,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .addTag(PIXELPLAY_JOB_TAG)
                        .addTag(WORK_NAME)
                        .build()

        // Daily "heavy cleanup" (LRC scan, album-art cache, cloud sync). Runs a FULL sync
        // with maintenance on, but only while charging on an unmetered network so it stays
        // invisible to the user.
        fun periodicMaintenanceWork(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                        INPUT_RUN_MAINTENANCE to true
                    )
                )
                .setConstraints(constraints)
                .build()
        }
    }
    
}
