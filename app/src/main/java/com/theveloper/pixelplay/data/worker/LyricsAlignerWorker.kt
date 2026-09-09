package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.tais.TaisAiEngine
import com.theveloper.pixelplay.data.tais.lyrics.TaisLyricsAligner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Background job for TAIS Engine 1's NPU forced alignment — upgrading a track that only has
 * plain or line-level lyrics into word-level timestamps by aligning the text against decoded
 * audio. See [TaisLyricsAligner.forceAlign] for why this currently always fails: there is no
 * forced-alignment model bundled in this repo yet. This worker exists so the WorkManager
 * plumbing is ready to enqueue real alignment jobs the moment one is sourced.
 */
@HiltWorker
class LyricsAlignerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val lyricsAligner: TaisLyricsAligner
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID) ?: return@withContext Result.failure()
        val pcmFilePath = inputData.getString(INPUT_PCM_FILE_PATH) ?: return@withContext Result.failure()
        val plainLines = inputData.getStringArray(INPUT_PLAIN_LINES)?.toList() ?: return@withContext Result.failure()

        try {
            val aligned = lyricsAligner.forceAlign(pcmFilePath, plainLines)
            Result.success(workDataOf(OUTPUT_ALIGNED_LINE_COUNT to aligned.size))
        } catch (e: TaisAiEngine.ModelNotBundledException) {
            Timber.tag(TAG).w(e, "Forced alignment unavailable for songId=%s", songId)
            Result.failure(workDataOf(OUTPUT_FAILURE_REASON to (e.message ?: "model not bundled")))
        }
    }

    companion object {
        private const val TAG = "LyricsAlignerWorker"
        private const val INPUT_SONG_ID = "song_id"
        private const val INPUT_PCM_FILE_PATH = "pcm_file_path"
        private const val INPUT_PLAIN_LINES = "plain_lines"
        const val OUTPUT_ALIGNED_LINE_COUNT = "aligned_line_count"
        const val OUTPUT_FAILURE_REASON = "failure_reason"
        const val WORK_NAME_PREFIX = "tais_lyrics_alignment_"

        fun enqueue(workManager: WorkManager, songId: String, pcmFilePath: String, plainLines: List<String>) {
            val request = OneTimeWorkRequestBuilder<LyricsAlignerWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_PCM_FILE_PATH to pcmFilePath,
                        INPUT_PLAIN_LINES to plainLines.toTypedArray()
                    )
                )
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(WORK_NAME_PREFIX + songId)
                .build()

            workManager.enqueueUniqueWork(WORK_NAME_PREFIX + songId, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
