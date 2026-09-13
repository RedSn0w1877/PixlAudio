package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Importa la biblioteca de Spotify en segundo plano.
 *
 * Va en un worker y no en el ViewModel porque una biblioteca grande son decenas de
 * páginas encadenadas: debe sobrevivir a que el usuario cierre la pantalla.
 */
@HiltWorker
class SpotifySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyRepository: SpotifyRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!spotifyRepository.isLoggedIn.value) {
            Timber.d("SpotifySyncWorker: sin sesión, nada que importar")
            return@withContext Result.success()
        }

        val startedAt = System.currentTimeMillis()

        try {
            val result = spotifyRepository.syncAllPlaylistsAndSongs(
                // WorkManager corta una ejecución a los 10 minutos y lo hace de golpe.
                // Parar por las buenas antes de llegar deja la pasada en un punto del que
                // se puede reanudar, en vez de que la maten a mitad de una playlist.
                shouldContinue = {
                    !isStopped && System.currentTimeMillis() - startedAt < RUN_BUDGET_MS
                },
                resumeInterrupted = inputData.getBoolean(INPUT_IS_CONTINUATION, false)
            ) { current, total, name ->
                setProgressAsync(
                    workDataOf(
                        PROGRESS_CURRENT to current,
                        PROGRESS_TOTAL to total,
                        PROGRESS_NAME to name
                    )
                )
            }
            Timber.i(
                "Spotify importado: ${result.syncedSongCount} canciones en ${result.playlistCount} listas " +
                    "(${result.failedPlaylistCount} fallidas, completa=${result.isComplete})"
            )
            // Importar solo trae metadatos; el audio lo busca el emparejador después. Se
            // lanza aunque queden playlists por bajar: puede ir emparejando lo que ya hay.
            if (result.syncedSongCount > 0) {
                SpotifyMatchWorker.enqueue(applicationContext)
            }
            if (!result.isComplete && !isStopped) {
                enqueueContinuation(applicationContext)
            }
            Result.success(
                workDataOf(
                    OUTPUT_SONG_COUNT to result.syncedSongCount,
                    OUTPUT_PLAYLIST_COUNT to result.playlistCount
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Fallo la importación de Spotify")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "spotify_sync_worker"
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_NAME = "progress_name"
        const val OUTPUT_SONG_COUNT = "output_song_count"
        const val OUTPUT_PLAYLIST_COUNT = "output_playlist_count"

        private const val MAX_ATTEMPTS = 3

        /**
         * Margen con el que se para sola una ejecución. WorkManager mata el worker a los
         * 10 minutos exactos y sin avisar; parar antes deja la pasada reanudable.
         */
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L

        /** Marca el tramo siguiente de una pasada cortada, no una petición del usuario. */
        const val INPUT_IS_CONTINUATION = "input_is_continuation"

        fun enqueue(context: Context) =
            enqueue(context, ExistingWorkPolicy.REPLACE, isContinuation = false)

        /**
         * Siguiente tramo de una importación que se quedó sin tiempo.
         *
         * APPEND_OR_REPLACE y no KEEP: se encola desde dentro de `doWork`, con esta misma
         * ejecución todavía en marcha, y KEEP descartaría la petición por haber ya trabajo
         * activo con ese nombre. Appendida arranca justo al terminar la actual.
         */
        private fun enqueueContinuation(context: Context) =
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, isContinuation = true)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy, isContinuation: Boolean) {
            val request = OneTimeWorkRequestBuilder<SpotifySyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(INPUT_IS_CONTINUATION to isContinuation))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(WORK_NAME)
                .build()

            // REPLACE y no KEEP en la ruta manual: si el usuario pide sincronizar otra vez
            // es porque quiere datos frescos, no que se ignore la petición.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
