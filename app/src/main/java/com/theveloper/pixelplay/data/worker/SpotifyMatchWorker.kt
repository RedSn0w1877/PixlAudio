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
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.data.youtube.TrackMatch
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Busca en YouTube Music el audio de las pistas de Spotify que aún no lo tienen.
 *
 * Cada pista son una o dos búsquedas contra InnerTube, y ahí el tiempo se va esperando a la
 * red, no calculando. Por eso van [CONCURRENCY_IDLE] en paralelo — bastante para saturar la
 * latencia sin parecer un bot — bajo un limitador propio ([SearchPacer]) que frena solo
 * cuando YouTube empieza a fallar. La ejecución encadena lotes hasta agotar su presupuesto
 * de tiempo, en vez de reencolarse y pagar la latencia de WorkManager cada pocas pistas.
 *
 * Arranca en cuanto aterriza la primera página de la importación (no espera a que termine) y
 * empareja primero lo que el usuario ve primero: lo que acaba de traer del catálogo, luego
 * "Me gusta" de más nuevo a más viejo y después el resto. Cada resultado se escribe en el
 * momento en que llega.
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
        var totalPending = spotifyDao.countByMatchState(SpotifyMatchState.PENDING)
        if (totalPending == 0) {
            Timber.d("SpotifyMatchWorker: no queda nada por emparejar")
            return@withContext Result.success()
        }

        val matched = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var errored = 0
        var done = 0
        var moreWork = false
        // Rows that hit a network error stay PENDING and keep their place in the priority
        // order; skipping that many rows lets the next batch move past them instead of
        // retrying the same ones forever.
        var skip = 0
        val pacer = SearchPacer()
        // The same recording (ISRC), or the same title/artist/length under another Spotify
        // id, is searched once per run.
        val resultCache = ConcurrentHashMap<String, TrackMatchOrNone>()

        while (true) {
            if (isStopped) break
            if (System.currentTimeMillis() - startedAt >= RUN_BUDGET_MS) {
                moreWork = true
                break
            }

            val batch = spotifyDao.getPendingSongsByPriority(limit = BATCH_SIZE, skip = skip)
            if (batch.isEmpty()) break

            val permits = if (PlaybackActivityTracker.isPlaybackActive) {
                CONCURRENCY_PLAYING
            } else {
                CONCURRENCY_IDLE
            }
            val gate = Semaphore(permits)
            val batchErrors = AtomicInteger(0)

            coroutineScope {
                batch.map { song ->
                    async {
                        gate.withPermit {
                            val cacheKey = cacheKeyOf(song)
                            val cached = resultCache[cacheKey]
                            val outcome: kotlin.Result<TrackMatch?> = if (cached != null) {
                                kotlin.Result.success(cached.match)
                            } else {
                                pacer.await()
                                runCatching { trackMatcher.findMatch(song) }
                                    .onSuccess {
                                        resultCache[cacheKey] = TrackMatchOrNone(it)
                                        pacer.onSuccess()
                                    }
                                    .onFailure {
                                        if (it is CancellationException) throw it
                                        pacer.onFailure()
                                        Timber.w(it, "Fallo buscando '${song.title}'")
                                    }
                            }

                            // Escribe en cuanto llega el resultado, no al final del lote: la
                            // canción queda reproducible en el momento en que se encuentra.
                            val match = outcome.getOrNull()
                            when {
                                // La petición reventó (red caída, YouTube limitando el ritmo). No es
                                // que la pista no exista, así que se queda PENDING: marcarla UNMATCHED
                                // la daría por perdida para siempre.
                                outcome.isFailure -> batchErrors.incrementAndGet()

                                match != null -> {
                                    spotifyDao.updateAutomaticMatch(
                                        spotifyId = song.spotifyId,
                                        videoId = match.videoId,
                                        score = match.score,
                                        state = SpotifyMatchState.MATCHED
                                    )
                                    matched.incrementAndGet()
                                }

                                else -> {
                                    spotifyDao.updateAutomaticMatch(
                                        spotifyId = song.spotifyId,
                                        videoId = null,
                                        score = null,
                                        state = SpotifyMatchState.UNMATCHED
                                    )
                                    failed.incrementAndGet()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            val batchErrorCount = batchErrors.get()
            errored += batchErrorCount
            skip += batchErrorCount
            done += batch.size

            // The import keeps adding songs while this runs, so the total grows with it.
            totalPending = maxOf(
                totalPending,
                done + spotifyDao.countByMatchState(SpotifyMatchState.PENDING) - skip
            )
            setProgressAsync(
                workDataOf(
                    PROGRESS_DONE to done,
                    PROGRESS_TOTAL to totalPending,
                    // Read by the Active jobs list.
                    "percent" to (done * 100 / totalPending.coerceAtLeast(1)).coerceIn(0, 100),
                    "detail" to "Matching $done/$totalPending songs"
                )
            )

            // Lote entero fallido: no es mala suerte, es que algo de fuera está caído o
            // limitando. Seguir sólo empeora la racha; se corta y se reintenta con la
            // espera exponencial de WorkManager.
            if (batchErrorCount == batch.size) {
                Timber.w("SpotifyMatchWorker: lote completo fallido, se reintentará más tarde")
                return@withContext Result.retry()
            }

            // Respiro corto entre lotes. No es para ir despacio, es para no encadenar
            // ráfagas idénticas: un patrón perfectamente regular es lo que se detecta.
            delay(BETWEEN_BATCHES_MS)
        }

        Timber.i(
            "SpotifyMatchWorker: ${matched.get()} emparejadas, ${failed.get()} sin resultado, " +
                "$errored con error (de $totalPending)"
        )

        // Quedaban pistas cuando se acabó el presupuesto, o algunas se dejaron en PENDING
        // por errores de red: se continúa en otra ejecución.
        // Network errors need WorkManager's backoff, not an immediate continuation loop.
        if (errored > 0 && !isStopped) return@withContext Result.retry()
        if ((moreWork || spotifyDao.countByMatchState(SpotifyMatchState.PENDING) > 0) && !isStopped) {
            enqueueContinuation(applicationContext)
        }

        Result.success(workDataOf(OUTPUT_MATCHED to matched.get(), OUTPUT_FAILED to failed.get()))
    }

    /** A cached search result; [match] null means "searched, nothing acceptable". */
    private class TrackMatchOrNone(val match: TrackMatch?)

    /** ISRC identifies the recording; without one, title + artist + length (to the second). */
    private fun cacheKeyOf(song: SpotifySongEntity): String =
        song.isrc?.takeIf { it.isNotBlank() }?.let { "isrc:$it" }
            ?: "t:${song.title.lowercase()}|${song.artist.lowercase()}|${song.durationMs / 1000}"

    /**
     * The matcher's own rate limiting: a minimum gap between the start of two song searches,
     * shared by every task in flight, that doubles (up to [MAX_GAP_MS]) while searches are
     * failing and relaxes back after successes. It keeps a rate-limited YouTube from being
     * hit harder precisely when it is complaining.
     */
    private class SearchPacer {
        private val lock = Mutex()
        private var nextStartAtMs = 0L

        @Volatile
        private var gapMs = MIN_GAP_MS

        suspend fun await() {
            lock.withLock {
                val wait = nextStartAtMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                nextStartAtMs = System.currentTimeMillis() + gapMs
            }
        }

        fun onSuccess() {
            gapMs = (gapMs * 3 / 4).coerceAtLeast(MIN_GAP_MS)
        }

        fun onFailure() {
            gapMs = (gapMs * 2).coerceAtMost(MAX_GAP_MS)
        }

        private companion object {
            const val MIN_GAP_MS = 60L
            const val MAX_GAP_MS = 2_000L
        }
    }

    companion object {
        const val WORK_NAME = "spotify_match_worker"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"
        const val OUTPUT_MATCHED = "output_matched"
        const val OUTPUT_FAILED = "output_failed"
        const val INPUT_RETRY_FAILED = "input_retry_failed"

        private const val BATCH_SIZE = 40
        private const val CONCURRENCY_IDLE = 5
        private const val CONCURRENCY_PLAYING = 2
        private const val BETWEEN_BATCHES_MS = 150L

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
