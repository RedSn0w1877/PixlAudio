package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resultado de la prueba de reproducción, en frases legibles.
 *
 * La cadena Spotify → YouTube tiene cuatro puntos donde puede romperse y desde fuera todos
 * se ven igual: "no suena". Esto los separa para poder decir cuál falló sin mirar logcat.
 */
data class PlaybackDiagnosticsReport(
    val steps: List<Step>,
    val succeeded: Boolean
) {
    data class Step(
        val title: String,
        val ok: Boolean,
        val detail: String
    )

    /** Texto compacto para pegar en un mensaje. */
    fun asPlainText(): String = buildString {
        steps.forEach { step ->
            append(if (step.ok) "OK  " else "FAIL ")
            append(step.title)
            append(" — ")
            appendLine(step.detail)
        }
    }
}

/**
 * Ejecuta la cadena completa sobre una sola pista y cuenta qué pasó en cada paso.
 */
@Singleton
class PlaybackDiagnostics @Inject constructor(
    private val spotifyDao: SpotifyDao,
    private val innerTubeClient: InnerTubeClient,
    private val trackMatcher: TrackMatcher,
    private val streamResolver: ChainedYouTubeStreamResolver,
    private val streamProxy: SpotifyStreamProxy,
    private val validator: StreamUrlValidator
) {

    suspend fun run(): PlaybackDiagnosticsReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<PlaybackDiagnosticsReport.Step>()

        // 1. ¿Hay algo importado?
        val song = runCatching { spotifyDao.getAnySong() }.getOrNull()
        if (song == null) {
            steps += PlaybackDiagnosticsReport.Step(
                title = "Imported tracks",
                ok = false,
                detail = "No Spotify tracks in the database yet — run Sync first."
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }
        steps += PlaybackDiagnosticsReport.Step(
            title = "Imported tracks",
            ok = true,
            detail = "Testing with \"${song.title}\" by ${song.artist}."
        )

        // 2. ¿Contesta la búsqueda de YouTube Music?
        val query = "${song.title} ${song.artist.substringBefore(",").trim()}"
        val candidates = runCatching { innerTubeClient.searchSongs(query, limit = 5) }
            .getOrElse { emptyList() }
        if (candidates.isEmpty()) {
            steps += PlaybackDiagnosticsReport.Step(
                title = "YouTube Music search",
                ok = false,
                detail = innerTubeClient.lastFailureReason
                    ?: "Search returned nothing. YouTube is refusing the request."
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }
        steps += PlaybackDiagnosticsReport.Step(
            title = "YouTube Music search",
            ok = true,
            detail = "${candidates.size} candidates, top one: \"${candidates.first().title}\"."
        )

        // 3. ¿Alguno se parece lo bastante?
        val match = runCatching { trackMatcher.findMatch(song) }.getOrNull()
        if (match == null) {
            val bestScore = candidates.maxOfOrNull { trackMatcher.score(song, it) } ?: 0f
            steps += PlaybackDiagnosticsReport.Step(
                title = "Track matching",
                ok = false,
                detail = "Found results but none scored high enough " +
                    "(best ${"%.2f".format(bestScore)}, need ${TrackMatcher.MIN_ACCEPT_SCORE})."
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }
        steps += PlaybackDiagnosticsReport.Step(
            title = "Track matching",
            ok = true,
            detail = "Matched \"${match.candidateTitle}\" (score ${"%.2f".format(match.score)})."
        )

        // 4. ¿Se puede convertir el vídeo en audio que de verdad se pueda leer?
        //    `resolve` ya comprueba cada enlace antes de darlo por bueno, así que aquí
        //    "verde" significa reproducible de verdad, no solo "hubo respuesta".
        val streamUrl = runCatching { streamResolver.resolve(match.videoId) }.getOrNull()
        val attempts = streamResolver.lastAttempts

        if (streamUrl.isNullOrBlank()) {
            steps += PlaybackDiagnosticsReport.Step(
                title = "Audio stream",
                ok = false,
                detail = if (attempts.isEmpty()) {
                    "No YouTube client responded at all — check the internet connection."
                } else {
                    "Every YouTube client refused:\n" + attempts.joinToString("\n") { "• $it" }
                }
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }

        steps += PlaybackDiagnosticsReport.Step(
            title = "Audio stream",
            ok = true,
            detail = buildString {
                append("Playable via ${streamResolver.lastSuccessfulStrategy ?: "unknown"}.")
                val rejected = attempts.dropLast(1)
                if (rejected.isNotEmpty()) {
                    append("\nTried first:\n")
                    append(rejected.joinToString("\n") { "• $it" })
                }
            }
        )

        // 5. El paso que de verdad usa el reproductor.
        //    Todo lo anterior habla con YouTube directamente, pero ExoPlayer no: lee de un
        //    servidor local que hace de intermediario. Si eso falla, los cuatro pasos de
        //    arriba salen verdes y la canción sigue sin sonar.
        //    Se guarda el emparejamiento porque el proxy lo busca en la base de datos.
        runCatching {
            spotifyDao.updateMatch(
                spotifyId = song.spotifyId,
                videoId = match.videoId,
                score = match.score,
                state = SpotifyMatchState.MATCHED
            )
        }

        val proxyReady = runCatching { streamProxy.ensureReady() }.getOrElse { false }
        if (!proxyReady) {
            steps += PlaybackDiagnosticsReport.Step(
                title = "Local audio server",
                ok = false,
                detail = "The in-app audio server would not start, so the player has " +
                    "nothing to read from."
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }

        val proxyUrl = streamProxy.getProxyUrl(song.spotifyId)
        if (proxyUrl.isBlank()) {
            steps += PlaybackDiagnosticsReport.Step(
                title = "Local audio server",
                ok = false,
                detail = "The server started but refused to build an address for this track."
            )
            return@withContext PlaybackDiagnosticsReport(steps, succeeded = false)
        }

        // Sin Range, igual que la primera petición real de ExoPlayer. Con Range este paso
        // salía verde mientras la reproducción moría con un 403.
        val throughProxy = validator.probe(proxyUrl, sendRange = false)
        steps += PlaybackDiagnosticsReport.Step(
            title = "Local audio server",
            ok = throughProxy.ok,
            detail = if (throughProxy.ok) {
                "Audio reaches the player (${throughProxy.contentType ?: "unknown type"})."
            } else {
                "The player's own route failed: ${throughProxy.describe()}. " +
                    "YouTube hands over the audio, but it does not survive the hop " +
                    "through the local server."
            }
        )

        PlaybackDiagnosticsReport(steps, succeeded = throughProxy.ok)
    }
}
