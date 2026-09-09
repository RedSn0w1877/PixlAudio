package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import com.theveloper.pixelplay.data.preferences.TaisRoformerBackendType
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.tais.stems.BsRoformerApiClient
import com.theveloper.pixelplay.data.tais.stems.DirectPostStemApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Render Studio Master (BS-RoFormer)" — sends the track to a hosted BS-RoFormer / Mel-Band
 * RoFormer model instead of running the on-device MDX-Net pipeline
 * ([com.theveloper.pixelplay.data.tais.stems.TaisStemSeparator]). Which wire protocol to use is
 * a user setting ([TaisRoformerBackendType]): a Gradio Space via [BsRoformerApiClient], or a bare
 * single-endpoint server (Colab/FastAPI, Modal, RunPod, ...) via [DirectPostStemApiClient] — the
 * two backends aren't distinguishable from the base URL alone, hence the explicit picker in
 * Experimental Settings. Writes
 * `<filesDir>/tais_stems/<songId>_hq_roformer_inst.wav` — a distinct filename from the on-device
 * path's `<songId>_instrumental.wav` so the two never collide or overwrite each other; a song can
 * have both cached at once and the user picks which one plays via the lyrics-screen toggle.
 *
 * No local fallback: if no backend is configured or the render fails, this worker fails outright
 * rather than silently substituting the on-device model — the whole point of this button is the
 * higher-quality cloud render, so a silent substitution would defeat it.
 */
@HiltWorker
class BsRoformerRenderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bsRoformerApiClient: BsRoformerApiClient,
    private val directPostStemApiClient: DirectPostStemApiClient,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy,
    private val instrumentalIndex: TaisInstrumentalIndex
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID) ?: return@withContext Result.failure()
        val inputSourceAudioPath = inputData.getString(INPUT_SOURCE_AUDIO_PATH) ?: return@withContext Result.failure()

        val baseUrl = userPreferencesRepository.taisRoformerBaseUrlFlow.first()
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(
                workDataOf(OUTPUT_FAILURE_REASON to "No BS-RoFormer backend configured — set one in Experimental Settings first.")
            )
        }
        val apiName = userPreferencesRepository.taisRoformerApiNameFlow.first()
        val apiKey = userPreferencesRepository.taisRoformerApiKeyFlow.first().takeIf { it.isNotBlank() }
        val extraArg = userPreferencesRepository.taisRoformerExtraArgFlow.first().takeIf { it.isNotBlank() }
        val backendType = userPreferencesRepository.taisRoformerBackendTypeFlow.first()

        val song = musicRepository.getSong(songId).first()
        reportProgress(0, "Connecting to BS-RoFormer GPU…", indeterminate = true)

        try {
            val spotifyId = song?.spotifyId
            val sourceAudioPath = if (spotifyId != null) {
                audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = true)?.absolutePath
                    ?: return@withContext Result.failure(
                        workDataOf(OUTPUT_FAILURE_REASON to "Couldn't download this song for upload — check your connection and try again.")
                    )
            } else {
                inputSourceAudioPath
            }

            val stemsDir = TaisInstrumentalIndex.stemsDirectory(applicationContext).apply { mkdirs() }
            val uploadFile = resolveUploadableFile(sourceAudioPath, stemsDir) ?: return@withContext Result.failure(
                workDataOf(OUTPUT_FAILURE_REASON to "Couldn't read this song's audio file for upload.")
            )

            val onStage: suspend (String) -> Unit = { stageText ->
                reportProgress(if (stageText.startsWith("Downloading")) 90 else 40, stageText, indeterminate = true)
            }
            val result = when (backendType) {
                TaisRoformerBackendType.GRADIO_SPACE -> bsRoformerApiClient.separate(
                    baseUrl = baseUrl,
                    apiName = apiName,
                    apiKey = apiKey,
                    sourceAudioFile = uploadFile,
                    outputDir = stemsDir,
                    extraArg = extraArg,
                    onStage = onStage
                )
                TaisRoformerBackendType.DIRECT_POST -> directPostStemApiClient.separate(
                    baseUrl = baseUrl,
                    route = apiName,
                    apiKey = apiKey,
                    sourceAudioFile = uploadFile,
                    outputDir = stemsDir,
                    onStage = onStage
                )
            } ?: return@withContext Result.failure(
                workDataOf(OUTPUT_FAILURE_REASON to "BS-RoFormer render failed — check the backend URL/route in Experimental Settings and try again.")
            )

            val finalInstrumental = File(stemsDir, "${songId}_hq_roformer_inst.wav")
            check(TaisInstrumentalIndex.isCompleteStem(result.instrumentalFile)) { "The backend returned incomplete audio. Your previous render was kept." }
            RetainedAudioFiles.copyAtomically(result.instrumentalFile, finalInstrumental)
            instrumentalIndex.refresh()

            reportProgress(100, "Done — studio master ready", indeterminate = false)
            Result.success(workDataOf(OUTPUT_INSTRUMENTAL_PATH to finalInstrumental.absolutePath))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BS-RoFormer render failed for songId=%s", songId)
            Result.failure(workDataOf(OUTPUT_FAILURE_REASON to (e.message ?: "unknown error")))
        }
    }

    /** `content://` sources (local on-device library tracks) have no path to open a raw file at — stream-copy them into a real file the multipart upload can read. Plain paths/`file://` URIs are used as-is. */
    private fun resolveUploadableFile(sourceAudioPath: String, stemsDir: File): File? {
        val uri = runCatching { Uri.parse(sourceAudioPath) }.getOrNull()
        return when (uri?.scheme?.lowercase()) {
            null, "" -> File(sourceAudioPath).takeIf { it.exists() }
            "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }
            "content" -> {
                val dest = File(stemsDir, "roformer_upload_source.audio")
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                dest
            }
            else -> null
        }
    }

    private suspend fun reportProgress(percent: Int, detailText: String, indeterminate: Boolean) {
        setProgress(workDataOf(PROGRESS_PERCENT to percent, PROGRESS_DETAIL to detailText))
        setForeground(
            TaisForegroundNotifications.foregroundInfo(
                context = applicationContext,
                notificationId = TaisForegroundNotifications.BS_ROFORMER_NOTIFICATION_ID,
                title = "BS-RoFormer Studio Master",
                text = detailText,
                progress = percent,
                indeterminate = indeterminate
            )
        )
    }

    companion object {
        private const val TAG = "BsRoformerRenderWorker"
        private const val INPUT_SONG_ID = "song_id"
        private const val INPUT_SOURCE_AUDIO_PATH = "source_audio_path"
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_DETAIL = "detail"
        const val OUTPUT_INSTRUMENTAL_PATH = "instrumental_path"
        const val OUTPUT_FAILURE_REASON = "failure_reason"
        const val WORK_NAME_PREFIX = "tais_bs_roformer_render_"

        fun uniqueWorkName(songId: String): String = WORK_NAME_PREFIX + songId

        fun enqueue(workManager: WorkManager, songId: String, sourceAudioPath: String) {
            val request = OneTimeWorkRequestBuilder<BsRoformerRenderWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_SOURCE_AUDIO_PATH to sourceAudioPath
                    )
                )
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(TaisStudioWorker.REQUEST_CREATED_TAG + System.currentTimeMillis())
                .addTag(uniqueWorkName(songId))
                .build()

            workManager.enqueueUniqueWork(uniqueWorkName(songId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
