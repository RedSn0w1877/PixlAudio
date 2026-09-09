package com.theveloper.pixelplay.data.worker

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
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Trae el archivo de audio completo de una canción y lo guarda en local — para la caché
 * automática (se llama sola, ver [AudioCacheManager.maybeAutoCache]) y para una descarga
 * explícita del usuario ([AudioCacheManager.requestDownload]); la única diferencia entre
 * las dos es [isPermanent] y dónde se guarda el resultado.
 *
 * No repite la lógica de refresco de enlaces/avance secuencial que ya tiene
 * [com.theveloper.pixelplay.data.stream.CloudStreamProxy]: en vez de eso, le pide el archivo
 * al PROPIO proxy local sin cabecera Range. Una petición así hace que el proxy sirva el
 * archivo entero en una sola respuesta continua (refrescando y avanzando por dentro tantas
 * veces como haga falta) — así que este worker solo tiene que guardar en disco lo que ya le
 * están mandando, sin duplicar nada de la parte difícil.
 */
@HiltWorker
class SongDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyStreamProxy: SpotifyStreamProxy,
    private val audioCacheManager: AudioCacheManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID) ?: return@withContext Result.failure()
        val isPermanent = inputData.getBoolean(INPUT_IS_PERMANENT, false)
        if (audioCacheManager.wasRemovedAfter(songId, inputData.getLong(INPUT_ENQUEUED_AT, 0L))) {
            return@withContext Result.success()
        }

        val file = audioCacheManager.downloadAndCache(spotifyStreamProxy, songId, isPermanent)
        if (file == null) {
            Timber.w("SongDownloadWorker: downloadAndCache failed for $songId, retrying")
            // A permanently unavailable song must not hold every later download hostage.
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.success(
                workDataOf(OUTPUT_FAILURE_REASON to "Could not download this song after 4 attempts. Tap Download to try again.")
            )
        }
        Timber.d("SongDownloadWorker: $songId guardado (${file.length()} bytes, permanente=$isPermanent)")
        Result.success()
    }

    companion object {
        private const val INPUT_SONG_ID = "song_id"
        private const val INPUT_IS_PERMANENT = "is_permanent"
        private const val INPUT_ENQUEUED_AT = "enqueued_at"
        const val OUTPUT_FAILURE_REASON = "failure_reason"

        // Un solo nombre de trabajo único para TODAS las descargas, en vez de uno por canción:
        // cada descarga completa ya implica varios resolves lentos (~4-7s cada uno) y refrescos
        // repetidos contra el propio proxy local — dejar que varias corran a la vez satura ese
        // mismo servidor embebido y puede tirar hasta la reproducción en directo (confirmado:
        // "Socket closed" en ExoPlayer con solo 2-3 descargas simultáneas de fondo). APPEND las
        // encola una detrás de otra en vez de dispararlas todas a la vez.
        private const val WORK_QUEUE_NAME = "song_download_queue"

        /** Tag prefix [pixelPlayJobKind] matches on — this worker has one fixed queue name, not a per-song one. */
        const val WORK_NAME_PREFIX = WORK_QUEUE_NAME
        fun songTag(songId: String): String = "song_download_$songId"

        fun enqueue(
            workManager: WorkManager,
            songId: String,
            isPermanent: Boolean
        ) {
            val request = OneTimeWorkRequestBuilder<SongDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_IS_PERMANENT to isPermanent,
                        INPUT_ENQUEUED_AT to System.currentTimeMillis()
                    )
                )
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(WORK_QUEUE_NAME)
                .addTag(songTag(songId))
                .build()

            workManager.enqueueUniqueWork(WORK_QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
