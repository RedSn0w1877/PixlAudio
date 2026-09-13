package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.TaisAiEngine
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.tais.stems.TaisStemSeparator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Background job for TAIS Engine 2's actual AI stem separation (as opposed to the always-on DSP
 * fallback in [com.theveloper.pixelplay.data.service.player.MidSideVocalProcessor]), backed by
 * a real MDX-Net-derived `.tflite` model — see [TaisStemSeparator]'s class doc for the model
 * contract. [INPUT_SOURCE_PCM_PATH] takes the original source audio file (any format MediaCodec
 * can decode) — [TaisStemSeparator] does the decoding itself.
 */
@HiltWorker
class StemSeparatorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val stemSeparator: TaisStemSeparator,
    private val musicRepository: MusicRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy,
    private val instrumentalIndex: TaisInstrumentalIndex,
    private val automaticEnvironment: AutomaticStudioEnvironment
) : CoroutineWorker(appContext, workerParams) {

    private val automatic: Boolean get() = inputData.getBoolean(INPUT_AUTOMATIC_STUDIO, false)

    override suspend fun doWork(): Result = if (automatic) {
        automaticEnvironment.runQuietly(AutomaticStudioKind.INSTRUMENTAL) { performWork() }
    } else performWork()

    private suspend fun performWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID) ?: return@withContext Result.failure()
        val inputSourceAudioPath = inputData.getString(INPUT_SOURCE_PCM_PATH) ?: return@withContext Result.failure()

        val song = musicRepository.getSong(songId).first()
        if (automatic) {
            if (song == null || !AutomaticStudioPolicy.canProcessDuration(song.duration)) {
                return@withContext AutomaticStudioEnvironment.deferredResult("This track needs manual processing")
            }
            TaisInstrumentalIndex.bestAvailableFile(applicationContext, songId)?.let {
                return@withContext Result.success(workDataOf(OUTPUT_INSTRUMENTAL_PATH to it.absolutePath))
            }
        }

        // Promote to a genuine foreground service before any heavy work — keeps this
        // memory-heavy job off the tight background-process heap clamp. See
        // TaisForegroundNotifications doc for why this matters, not just for UX.
        try {
            reportProgress(0, "Separating ${song?.title ?: "this song"}…", indeterminate = true)
            // Spotify-matched/cloud-resolved songs stream from a local proxy that only exists
            // while MusicService is actively playing them — nothing is there for a background
            // job to read. Reuse (or create) a permanent local download through the same cache
            // the "Download" feature uses, same as TaisStudioWorker.
            val spotifyId = song?.spotifyId
            val sourceAudioPath = if (automatic) {
                AutomaticStudioEnvironment.localAudio(song!!, audioCacheManager)
                    ?: return@withContext AutomaticStudioEnvironment.deferredResult("Waiting for audio already on this phone")
            } else if (spotifyId != null) {
                // The cache/proxy subsystem keys by the actual Spotify track id, not this app's
                // internal (synthetic, negative) Song.id — see EnhancedSongListItem's download
                // badge / DualPlayerEngine's auto-cache hookup for the same convention.
                audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = true)?.absolutePath
                    ?: return@withContext songFailure("Couldn't download this song for local processing — check your connection and try again.")
            } else {
                inputSourceAudioPath
            }

            val stemsDir = TaisInstrumentalIndex.stemsDirectory(applicationContext).apply { mkdirs() }
            val result = stemSeparator.separate(songId, sourceAudioPath, stemsDir) { done, total ->
                val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                reportProgress(percent, "Rendering the instrumental — $percent% through the track…")
            }
            instrumentalIndex.refresh()
            Result.success(
                workDataOf(
                    OUTPUT_VOCALS_PATH to result.vocalsFile.absolutePath,
                    OUTPUT_INSTRUMENTAL_PATH to result.instrumentalFile.absolutePath
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: TaisAiEngine.ModelNotBundledException) {
            Timber.tag(TAG).w(e, "Stem separation unavailable for songId=%s", songId)
            songFailure(e.message ?: "model not bundled")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Stem separation failed for songId=%s", songId)
            songFailure(e.message ?: "unknown error")
        }
    }

    private fun songFailure(reason: String): Result {
        val output = workDataOf(OUTPUT_FAILURE_REASON to reason)
        return if (inputData.getBoolean(INPUT_CONTINUE_ON_FAILURE, false)) Result.success(output)
        else Result.failure(output)
    }

    /** Updates both WorkManager's own progress data (for [com.theveloper.pixelplay.presentation.components.tais.TaisStudioProgressCard]) and the foreground notification in one call. */
    private suspend fun reportProgress(percent: Int, detailText: String, indeterminate: Boolean = false) {
        setProgress(workDataOf(PROGRESS_PERCENT to percent, PROGRESS_DETAIL to detailText))
        if (automatic) return // Quiet work does not create a foreground service or notification.
        setForeground(
            TaisForegroundNotifications.foregroundInfo(
                context = applicationContext,
                notificationId = TaisForegroundNotifications.STEM_SEPARATOR_NOTIFICATION_ID,
                title = "Magic Instrumentalize",
                text = detailText,
                progress = percent,
                indeterminate = indeterminate
            )
        )
    }

    companion object {
        private const val TAG = "StemSeparatorWorker"
        private const val INPUT_SONG_ID = "song_id"
        private const val INPUT_SOURCE_PCM_PATH = "source_pcm_path"
        private const val INPUT_CONTINUE_ON_FAILURE = "continue_on_failure"
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_DETAIL = "detail"
        const val OUTPUT_VOCALS_PATH = "vocals_path"
        const val OUTPUT_INSTRUMENTAL_PATH = "instrumental_path"
        const val OUTPUT_FAILURE_REASON = "failure_reason"
        const val WORK_NAME_PREFIX = "tais_stem_separation_"

        fun uniqueWorkName(songId: String): String = WORK_NAME_PREFIX + songId

        /**
         * Builds the request without enqueuing it — [enqueue] uses this for the normal
         * single-song path, and bulk callers (e.g. "Instrumentalize all songs") use it to
         * build a whole batch and hand it to [enqueueSequentialBatch] instead, so dozens of
         * songs don't all resolve YouTube audio in parallel and starve whatever is actually
         * playing (confirmed: this is exactly what was happening before this existed).
         */
        fun buildRequest(songId: String, sourcePcmPath: String, continueOnFailure: Boolean = false, automatic: Boolean = false): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<StemSeparatorWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_AUTOMATIC_STUDIO to automatic,
                        INPUT_SOURCE_PCM_PATH to sourcePcmPath,
                        INPUT_CONTINUE_ON_FAILURE to continueOnFailure
                    )
                )
                .apply {
                    if (automatic) {
                        addTag(AUTO_STUDIO_WORK_TAG)
                        addTag(AUTO_INSTRUMENTAL_TAG)
                        addTag(AUTO_SONG_TAG_PREFIX + songId)
                        setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                    }
                }
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(TaisStudioWorker.REQUEST_CREATED_TAG + System.currentTimeMillis())
                .addTag(uniqueWorkName(songId))
                .build()

        fun enqueue(workManager: WorkManager, songId: String, sourcePcmPath: String) {
            // Explicit user work always preempts quiet maintenance work for responsiveness.
            workManager.cancelAllWorkByTag(AUTO_STUDIO_WORK_TAG)
            workManager.enqueueUniqueWork(
                uniqueWorkName(songId),
                ExistingWorkPolicy.KEEP,
                buildRequest(songId, sourcePcmPath)
            )
        }
    }
}
