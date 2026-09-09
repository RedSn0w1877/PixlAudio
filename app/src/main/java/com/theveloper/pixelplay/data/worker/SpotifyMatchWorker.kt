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
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Busca en YouTube Music el audio de las pistas de Spotify que aún no lo tienen.
 *
 * Cada pista son una o dos búsquedas contra InnerTube, y ahí el tiempo se va esperando a la
 * red, no calculando. Antes esto iba estrictamente en serie y además dormía entre pista y
 * pista, así que el hilo estaba parado la mayor parte del tiempo y una biblioteca mediana
 * tardaba horas. Ahora van [CONCURRENCY_IDLE] en paralelo — bastante para saturar la
 * latencia sin parecer un bot — y la ejecución encadena lotes hasta agotar su presupuesto
 * de tiempo, en vez de reencolarse y pagar la latencia de WorkManager cada 50 pistas.
 *
 * Mientras suena música se baja a [CONCURRENCY_PLAYING]: emparejar no debe competir por la
 * red con la canción que se está escuchando.
 */
@HiltWorker
class SpotifyMatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyDao: SpotifyDao,
    private val trackMatcher: TrackMatcher
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Una petición explícita del usuario reencola lo que se dio por perdido: si algo
        // falló por un corte de red o por un cambio en la API, ahora tiene otra oportunidad.
        if (runAttemptCount == 0 && inputData.getBoolean(INPUT_RETRY_FAILED, false)) {
            val requeued = spotifyDao.requeueUnmatchedSongs()
            Timber.i("SpotifyMatchWorker: $requeued pistas devueltas a la cola")
        }

        val startedAt = System.currentTimeMillis()
        val totalPending = spotifyDao.countByMatchState(SpotifyMatchState.PENDING)
        if (totalPending == 0) {
            Timber.d("SpotifyMatchWorker: no queda nada por emparejar")
            return@withContext Result.success()
        }

        var matched = 0
        var failed = 0
        var errored = 0
        var done = 0
        var moreWork = false
        // Failed rows remain PENDING. A keyset cursor still lets the next batch
        // advance past them instead of reading the same first page forever.
        var afterSpotifyId = ""

        while (true) {
            if (isStopped) break
            if (System.currentTimeMillis() - startedAt >= RUN_BUDGET_MS) {
                moreWork = true
                break
            }

            val batch = spotifyDao.getUnmatchedSongsAfter(afterSpotifyId, limit = BATCH_SIZE)
            if (batch.isEmpty()) break
            afterSpotifyId = batch.last().spotifyId

            val permits = if (PlaybackActivityTracker.isPlaybackActive) {
                CONCURRENCY_PLAYING
            } else {
                CONCURRENCY_IDLE
            }
            val gate = Semaphore(permits)

            val results = coroutineScope {
                batch.map { song ->
                    async {
                        gate.withPermit {
                            song to runCatching { trackMatcher.findMatch(song) }
                                .onFailure {
                                    if (it is CancellationException) throw it
                                    Timber.w(it, "Fallo buscando '${song.title}'")
                                }
                        }
                    }
                }.awaitAll()
            }

            // Las escrituras van después y en serie: son rápidas, y así una sola
            // transacción por pista no compite con las búsquedas en vuelo.
            results.forEach { (song, outcome) ->
                val match = outcome.getOrNull()
                when {
                    // La petición reventó (red caída, YouTube limitando el ritmo). No es
                    // que la pista no exista, así que se queda PENDING: marcarla UNMATCHED
                    // la daría por perdida para siempre, y una racha de 429 se llevaría por
                    // delante media biblioteca.
                    outcome.isFailure -> errored++

                    match != null -> {
                        spotifyDao.updateAutomaticMatch(
                            spotifyId = song.spotifyId,
                            videoId = match.videoId,
                            score = match.score,
                            state = SpotifyMatchState.MATCHED
                        )
                        matched++
                    }

                    else -> {
                        spotifyDao.updateAutomaticMatch(
                            spotifyId = song.spotifyId,
                            videoId = null,
                            score = null,
                            state = SpotifyMatchState.UNMATCHED
                        )
                        failed++
                    }
                }
            }

            done += results.size
            setProgressAsync(
                workDataOf(
                    PROGRESS_DONE to done,
                    PROGRESS_TOTAL to totalPending
                )
            )

            // Lote entero fallido: no es mala suerte, es que algo de fuera está caído o
            // limitando. Seguir sólo empeora la racha; se corta y se reintenta con la
            // espera exponencial de WorkManager.
            if (results.isNotEmpty() && results.all { it.second.isFailure }) {
                Timber.w("SpotifyMatchWorker: lote completo fallido, se reintentará más tarde")
                return@withContext Result.retry()
            }

            // Respiro corto entre lotes. No es para ir despacio, es para no encadenar
            // ráfagas idénticas: un patrón perfectamente regular es lo que se detecta.
            delay(BETWEEN_BATCHES_MS)
        }

        Timber.i(
            "SpotifyMatchWorker: $matched emparejadas, $failed sin resultado, " +
                "$errored con error (de $totalPending)"
        )

        // Quedaban pistas cuando se acabó el presupuesto, o algunas se dejaron en PENDING
        // por errores de red: se continúa en otra ejecución.
        // Network errors need WorkManager's backoff, not an immediate continuation loop.
        if (errored > 0 && !isStopped) return@withContext Result.retry()
        if ((moreWork || spotifyDao.countByMatchState(SpotifyMatchState.PENDING) > 0) && !isStopped) {
            enqueueContinuation(applicationContext)
        }

        Result.success(workDataOf(OUTPUT_MATCHED to matched, OUTPUT_FAILED to failed))
    }

    companion object {
        const val WORK_NAME = "spotify_match_worker"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"
        const val OUTPUT_MATCHED = "output_matched"
        const val OUTPUT_FAILED = "output_failed"
        const val INPUT_RETRY_FAILED = "input_retry_failed"

        private const val BATCH_SIZE = 48
        private const val CONCURRENCY_IDLE = 4
        private const val CONCURRENCY_PLAYING = 2
        private const val BETWEEN_BATCHES_MS = 250L

        /**
         * Margen con el que se para sola una ejecución: WorkManager mata el worker a los
         * 10 minutos y sin avisar, y lo que esté a medio escribir se pierde.
         */
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L

        /**
         * @param retryFailed true cuando lo pide el usuario: reencola también las pistas
         *   marcadas como no encontradas antes de empezar.
         */
        fun enqueue(context: Context, retryFailed: Boolean = false) {
            // Una petición del usuario manda sobre el lote en curso; el encadenado
            // automático (retryFailed=false) respeta el que ya está corriendo para no
            // duplicar búsquedas.
            enqueue(
                context = context,
                retryFailed = retryFailed,
                policy = if (retryFailed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            )
        }

        /**
         * Siguiente tramo cuando una ejecución se queda sin presupuesto.
         *
         * APPEND_OR_REPLACE porque se encola desde dentro de `doWork`: con esta misma
         * ejecución activa, KEEP descartaría la petición y el emparejamiento se pararía
         * a medias.
         */
        private fun enqueueContinuation(context: Context) =
            enqueue(context, retryFailed = false, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)

        private fun enqueue(context: Context, retryFailed: Boolean, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<SpotifyMatchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(INPUT_RETRY_FAILED to retryFailed))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
