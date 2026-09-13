package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.presentation.viewmodel.DailyMixStateHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Quiet on-device taste ranking plus bounded catalog discovery. Runs without an AI API key.
 * Results are persisted even when no player ViewModel exists, and the previous mix remains
 * usable while refresh runs. Optional conversational AI remains a separate user-driven feature.
 */
@HiltWorker
class AiDailyMixWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val discoveryRepository: com.theveloper.pixelplay.data.recommendation.MusicDiscoveryRepository,
    private val tasteRepository: com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "ai_daily_mix_worker"
        private const val TARGET_MAX = 30
        // Same reasoning as AiWorker: an LLM call competes for CPU/thermal/network budget with
        // playback, and daily mix curation is the least urgent AI task in the app.
        private const val MAX_PLAYBACK_DEFERRALS = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (PlaybackActivityTracker.isPlaybackActive && runAttemptCount < MAX_PLAYBACK_DEFERRALS) {
            Timber.d("AiDailyMixWorker deferring (playback active, attempt=$runAttemptCount)")
            return@withContext Result.retry()
        }

        try {
            val allSongs = musicRepository.getAllSongsOnce()
            if (allSongs.isEmpty()) return@withContext Result.success()

            val favoriteIds = musicRepository.getFavoriteSongIdsFlow().first()
            val picks = dailyMixManager.personalizedPicks(allSongs, favoriteIds, TARGET_MAX)
            val daily = discoveryRepository.augment(picks.map { it.song }, allSongs)
            val yourMix = discoveryRepository.augment(dailyMixManager.generateYourMix(allSongs, favoriteIds), allSongs)
            dailyMixStateHolder.publishBackgroundMix(daily, yourMix)
            val known = allSongs.mapTo(hashSetOf()) { it.id }
            tasteRepository.saveReport(
                "Updated ${java.time.LocalDateTime.now().withNano(0)}\n" +
                    "${allSongs.size} library candidates · ${daily.size} daily picks · ${daily.count { it.id !in known }} new discoveries\n" +
                    "${daily.map { com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine.artistKey(it) }.distinct().size} artists\n\n" +
                    picks.take(12).joinToString("\n") { "${it.song.title} — ${it.reason}" }
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AiDailyMixWorker failed")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
