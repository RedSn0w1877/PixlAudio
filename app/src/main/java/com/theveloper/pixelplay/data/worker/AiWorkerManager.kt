package com.theveloper.pixelplay.data.worker


import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiWorkerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    // AI generation is network-bound and non-urgent: defer until we actually have
    // connectivity and the battery isn't critical, avoiding guaranteed-failure retries
    // and background drain.
    private val aiConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enqueueAiTask(
        prompt: String,
        type: AiSystemPromptType,
        temperature: Float = 0.7f
    ) {
        val data = Data.Builder()
            .putString(AiWorker.INPUT_PROMPT, prompt)
            .putString(AiWorker.INPUT_TYPE, type.name)
            .putFloat(AiWorker.INPUT_TEMP, temperature)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(data)
            .setConstraints(aiConstraints)
            .addTag(AiWorker.WORK_NAME)
            .addTag(PIXELPLAY_JOB_TAG)
            .build()

        workManager.enqueue(workRequest)
    }

    fun cancelAllTasks() {
        workManager.cancelAllWorkByTag(AiWorker.WORK_NAME)
    }

    /**
     * Queues the quiet music-intelligence refresh. KEEP lets an in-flight refresh finish
     * instead of restarting its catalog queries whenever the player updates.
     */
    fun enqueueDailyMixRefresh() {
        val workRequest = OneTimeWorkRequestBuilder<AiDailyMixWorker>()
            .setConstraints(aiConstraints)
            .addTag(AiDailyMixWorker.WORK_NAME)
            .addTag(PIXELPLAY_JOB_TAG)
            .build()

        workManager.enqueueUniqueWork(
            AiDailyMixWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
    /** Quiet, persistent discovery refresh; Android schedules it when battery and network permit. */
    fun ensurePeriodicDiscovery() {
        val request = androidx.work.PeriodicWorkRequestBuilder<AiDailyMixWorker>(12, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(aiConstraints)
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(AiDailyMixWorker.WORK_NAME)
            .addTag(PIXELPLAY_JOB_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "music_intelligence_periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

}
