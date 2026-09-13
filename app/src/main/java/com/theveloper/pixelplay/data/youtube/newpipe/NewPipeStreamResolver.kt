package com.theveloper.pixelplay.data.youtube.newpipe

import com.theveloper.pixelplay.data.youtube.ResolvedStream
import com.theveloper.pixelplay.data.youtube.potoken.PixelPlayPoTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resuelve audio reproducible con NewPipeExtractor en vez de a mano.
 *
 * [com.theveloper.pixelplay.data.youtube.SignatureCipherSolver] extraía la función de firma
 * de `base.js` con patrones de texto; YouTube cambió la forma del script y esos patrones
 * dejaron de encontrar nada. Replicar el nuevo formato a mano es justo el problema que
 * NewPipeExtractor ya resuelve y mantiene al día — así que se usa esa librería para esta
 * única cosa (resolver el stream), sin tocar la búsqueda ni el emparejamiento, que siguen
 * funcionando con el código propio.
 */
@Singleton
class NewPipeStreamResolver @Inject constructor(
    private val downloader: NewPipeDownloader,
    private val poTokenProvider: PixelPlayPoTokenProvider
) {
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    /** Último detalle de por qué falló, para el sondeo de depuración. */
    @Volatile
    var lastDetail: String? = null
        private set

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            NewPipe.init(downloader)
            // Sin esto, getAudioStreams() puede volver vacío: YouTube exige un poToken
            // (prueba de que el cliente es un navegador real) antes de entregar el audio de
            // algunos formatos, y sin este proveedor la librería no tiene forma de generarlo.
            // OJO: este poToken es del cliente WEB — NewPipeExtractor solo lo usa para
            // metadatos/miniaturas, nunca para los formatos de audio en sí. Esos salen
            // únicamente de los clientes ANDROID e IOS.
            YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)
            // Sin poToken de Android (DroidGuard, cerrado — nadie fuera de Google lo
            // resuelve), el cliente Android cae a una respuesta pensada para Shorts que no
            // trae formatos de audio normales. El cliente IOS está apagado por defecto y,
            // sin poToken tampoco, es la única vía que queda para conseguir audio real.
            YoutubeStreamExtractor.setFetchIosClient(true)
            initialized = true
        }
    }

    suspend fun resolve(videoId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startedAt = System.currentTimeMillis()
        try {
            val extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId"
            )
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                // getAudioStreams() vacío sin lanzar excepción no siempre es "no hay
                // formatos": a veces YouTube marca el vídeo como no reproducible en la
                // propia respuesta (region, edad, etc.) y eso no da error, solo listas
                // vacías. getErrorMessage() es lo único que expone esa razón.
                val playabilityError = runCatching { extractor.errorMessage }.getOrNull()
                val name = runCatching { extractor.name }.getOrNull()
                lastDetail = buildString {
                    append("0 pistas de audio")
                    append(" (name=${name ?: "?"}")
                    append(", errorMessage=${playabilityError ?: "none"})")
                }
                return@withContext null
            }

            val best = audioStreams
                .filter { it.isUrl && !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }

            if (best == null) {
                lastDetail = "${audioStreams.size} pistas, ninguna con URL directa"
                return@withContext null
            }

            lastDetail = "itag ${best.itag}, ${best.averageBitrate / 1000} kbps, ${best.format}"
            val elapsed = System.currentTimeMillis() - startedAt
            // urlTail: para poder distinguir en el log si dos resoluciones seguidas
            // devolvieron URLs distintas (esperado) o, si coinciden, que una resolución no
            // está devolviendo un enlace realmente nuevo.
            val urlTail = best.content.takeLast(24)
            Timber.d("NewPipeStreamResolver: $videoId -> itag ${best.itag} en ${elapsed}ms, url=…$urlTail")
            ResolvedStream(url = best.content, userAgent = NewPipeDownloader.USER_AGENT)
        } catch (e: Exception) {
            lastDetail = "${e.javaClass.simpleName}: ${e.message}"
            Timber.w(e, "NewPipeExtractor no pudo resolver $videoId")
            null
        }
    }
}
