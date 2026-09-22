package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.net.Uri
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.remix.RemixStemLoader
import com.theveloper.pixelplay.data.remix.cloud.RemixBackendConfig
import com.theveloper.pixelplay.data.remix.cloud.RemixServerlessStemClient
import com.theveloper.pixelplay.data.remix.cloud.RemixStemJobClient
import com.theveloper.pixelplay.data.remix.cloud.StemJobOutcome
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sends a track to the GPU separation server and writes back four stems for Remix Studio.
 *
 * Runs in WorkManager with a foreground notification because a full song on a busy GPU takes
 * minutes — long past the point where a plain coroutine would be killed with the screen off.
 *
 * Output filenames use a `_stem_<kind>.wav` suffix that deliberately does **not** match
 * `TaisInstrumentalIndex.stemSongId()`'s `_instrumental.wav` / `_hq_roformer_inst.wav` patterns,
 * so the existing instrumental index keeps working untouched and a song can hold both.
 */
@HiltWorker
class RemixStemSeparationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val client: RemixStemJobClient,
    private val serverlessClient: RemixServerlessStemClient,
    private val userPreferencesRepository: UserPreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID)
            ?: return@withContext failure("No song was given.")
        val sourcePath = inputData.getString(INPUT_SOURCE_PATH)
            ?: return@withContext failure("This track has no local file to upload.")

        val consent = userPreferencesRepository.remixUploadConsentFlow.first()
        if (!consent) return@withContext failure("Uploading to the separation server isn't switched on.")

        val baseUrl = userPreferencesRepository.remixBackendUrlFlow.first()
        if (baseUrl.isBlank()) return@withContext failure("No separation server is configured.")
        if (!baseUrl.startsWith("https://") && !baseUrl.contains("://localhost") && !baseUrl.contains("://10.")) {
            // The track is the user's audio; it should not leave the device in the clear.
            return@withContext failure("The separation server must use https.")
        }
        val token = userPreferencesRepository.remixBackendTokenFlow.first()

        val source = File(sourcePath)
        val directory = TaisInstrumentalIndex.stemsDirectory(applicationContext)
        // The filename carries the region the stems cover, because an on-demand job separates
        // only the loop — not the whole song. Without it the loader would happily seek to a song
        // timestamp inside a file that starts somewhere else and play silence.
        val startMs = inputData.getInt(INPUT_START_MS, 0)
        val durationMs = inputData.getInt(INPUT_DURATION_MS, DEFAULT_REGION_MS)
        val prefix = stemPrefix(songId, startMs, durationMs)

        reportProgress(0, "Uploading to the separation server…", indeterminate = true)

        // Fire-and-forget: progress reporting must never slow the poll loop.
        val onProgress: (Float) -> Unit = { progress ->
            runCatching {
                setProgressAsync(
                    workDataOf(
                        PROGRESS_PERCENT to (progress * 100).toInt(),
                        PROGRESS_DETAIL to "Separating into four parts…",
                    )
                )
            }
        }
        val config = RemixBackendConfig(baseUrl = baseUrl, token = token)

        val outcome: StemJobOutcome = if (RemixServerlessStemClient.handles(baseUrl)) {
            // On-demand endpoint: upload only the loop region. Demucs costs scale with input
            // length, and a serverless payload is capped near 10 MB, so sending a whole song
            // would be both expensive and rejected.
            val clip = File(applicationContext.cacheDir, "remix_upload_${System.currentTimeMillis()}.wav")
            val exported = RemixStemLoader.exportRegionWav(
                context = applicationContext,
                uri = Uri.fromFile(source),
                startMs = startMs,
                durationMs = durationMs,
                target = clip,
            )
            if (exported == null) {
                return@withContext failure("Couldn't read that part of the track.")
            }
            try {
                serverlessClient.separate(
                    config = config,
                    regionWav = exported,
                    startMs = startMs,
                    durationMs = durationMs,
                    outputDirectory = directory,
                    outputPrefix = prefix,
                    onProgress = onProgress,
                )
            } finally {
                clip.delete()
            }
        } else {
            client.separate(
                config = config,
                source = source,
                outputDirectory = directory,
                outputPrefix = prefix,
                onProgress = onProgress,
            )
        }

        return@withContext when (outcome) {
            is StemJobOutcome.Completed -> {
                reportProgress(100, "Four parts ready", indeterminate = false)
                Timber.i("Remix separation finished for $songId: ${outcome.files.keys}")
                Result.success(workDataOf(OUTPUT_STEM_COUNT to outcome.files.size))
            }
            is StemJobOutcome.Failed -> failure(outcome.message)
            StemJobOutcome.Cancelled -> failure("Separation was cancelled on the server.")
        }
    }

    private fun failure(reason: String): Result {
        Timber.w("Remix separation failed: $reason")
        return Result.failure(workDataOf(OUTPUT_FAILURE_REASON to reason))
    }

    private suspend fun reportProgress(percent: Int, detail: String, indeterminate: Boolean) {
        setProgress(workDataOf(PROGRESS_PERCENT to percent, PROGRESS_DETAIL to detail))
        runCatching {
            setForeground(
                TaisForegroundNotifications.foregroundInfo(
                    context = applicationContext,
                    notificationId = TaisForegroundNotifications.REMIX_STEMS_NOTIFICATION_ID,
                    title = "Remix Studio",
                    text = detail,
                    progress = percent,
                    indeterminate = indeterminate,
                )
            )
        }
    }

    companion object {
        const val INPUT_SONG_ID = "song_id"
        const val INPUT_SOURCE_PATH = "source_path"
        const val INPUT_START_MS = "start_ms"
        const val INPUT_DURATION_MS = "duration_ms"
        /** Matches the studio's default loop length. */
        const val DEFAULT_REGION_MS = 8_000
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_DETAIL = "detail"
        const val OUTPUT_FAILURE_REASON = "failure_reason"
        const val OUTPUT_STEM_COUNT = "stem_count"

        fun uniqueWorkName(songId: String): String = "$REMIX_STEM_WORK_NAME_PREFIX$songId"

        /**
         * `<songId>_stem_<startMs>_<durationMs>_<kind>.wav`.
         *
         * The region is part of the name so a later session can tell whether the stems on disk
         * actually cover the loop being asked for — see `RemixStemLoader`'s lookup. The `_stem_`
         * infix also keeps these clear of `TaisInstrumentalIndex`'s `_instrumental.wav` pattern.
         */
        fun stemPrefix(songId: String, startMs: Int, durationMs: Int): String =
            "${TaisInstrumentalIndex.canonicalSongId(songId)}_stem_${startMs}_${durationMs}_"

        private val STEM_FILE = Regex("""^(.+)_stem_(\d+)_(\d+)_([a-z]+)\.wav$""")

        /** Region covered by a stem file, or null if the name isn't one of ours. */
        fun regionOf(fileName: String): IntRange? {
            val match = STEM_FILE.find(fileName) ?: return null
            val start = match.groupValues[2].toIntOrNull() ?: return null
            val duration = match.groupValues[3].toIntOrNull() ?: return null
            return start until (start + duration)
        }

        fun enqueue(
            context: Context,
            songId: String,
            sourcePath: String,
            startMs: Int = 0,
            durationMs: Int = DEFAULT_REGION_MS,
        ) {
            val request = OneTimeWorkRequestBuilder<RemixStemSeparationWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_SOURCE_PATH to sourcePath,
                        INPUT_START_MS to startMs,
                        INPUT_DURATION_MS to durationMs,
                    )
                )
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(uniqueWorkName(songId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(songId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
