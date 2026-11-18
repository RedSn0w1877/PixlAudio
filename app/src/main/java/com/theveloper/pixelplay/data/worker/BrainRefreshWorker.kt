package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.brain.BrainRefresh
import com.theveloper.pixelplay.data.brain.BrainRefreshRunner
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The music brain's one background job (docs/brain-spec.md, 3.7, stage 2a): once a day, on an unmetered
 * network and a battery that is not low, it asks YouTube Music for the radios of the listener's own
 * tracks and the artist pages of their top artists, so Daily Mix and Your Mix have new music to pick from.
 * The rules (budget, off switches, never while music plays, idempotent, cancellable) live in
 * [BrainRefreshRunner]; this class only fits them to WorkManager.
 *
 * It is scheduled by [BrainRefreshScheduler] after the brain is first used, never at app start, and it
 * is switched off by the Developer mode switch or by `brain.refresh.enabled` in the remote config.
 */
@HiltWorker
class BrainRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val refresh: BrainRefresh,
    private val switch: BrainSwitch,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!switch.refreshAllowed()) return@withContext Result.success()
        // Defers while music plays, as AiDailyMixWorker does: a quiet job must not compete with playback.
        if (PlaybackActivityTracker.isPlaybackActive && runAttemptCount < MAX_PLAYBACK_DEFERRALS) {
            Timber.d("BrainRefreshWorker deferring (playback active, attempt=$runAttemptCount)")
            return@withContext Result.retry()
        }
        try {
            val report = refresh.run(playing = { PlaybackActivityTracker.isPlaybackActive })
            Timber.d(
                "BrainRefreshWorker: %d radios and %d artists fetched, %d from cache, %d missed, stopped by %s",
                report.radiosFetched, report.artistsFetched, report.radiosCached + report.artistsCached, report.missed, report.stopped,
            )
            if (report.stopped == BrainRefreshRunner.Stop.PLAYING && runAttemptCount < MAX_PLAYBACK_DEFERRALS) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "BrainRefreshWorker failed")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "brain_refresh"
        private const val MAX_PLAYBACK_DEFERRALS = 5
    }
}
