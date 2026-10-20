package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprueba que una URL de audio es realmente reproducible.
 *
 * Que YouTube devuelva una URL no significa que sirva: sin la atestación que hoy exige a
 * varios clientes, `googlevideo` responde 403 a una URL con pinta perfectamente válida.
 * Sin esta comprobación el reproductor recibía enlaces muertos y fallaba con un error de
 * origen — y el diagnóstico los daba por buenos, porque solo miraba que hubiera texto.
 *
 * Se piden dos bytes: basta para saber si el servidor deja leer sin descargar nada.
 */
@Singleton
class StreamUrlValidator @Inject constructor(
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient private val okHttpClient: OkHttpClient
) {

    data class Probe(
        val ok: Boolean,
        val httpStatus: Int? = null,
        val contentType: String? = null,
        val error: String? = null
    ) {
        /** Resumen corto para el informe de diagnóstico. */
        fun describe(): String = when {
            ok -> "playable (${contentType ?: "unknown type"})"
            error != null -> error
            httpStatus != null -> "HTTP $httpStatus"
            else -> "unreachable"
        }
    }

    /**
     * @param userAgent el del cliente que produjo la URL. googlevideo la ata a ese agente,
     *   así que probarla con otro daría un falso negativo — y descargarla con otro, un 403.
     */
    /**
     * @param sendRange false para imitar exactamente la primera petición de ExoPlayer, que
     *   no lleva cabecera Range. Comprobar siempre con Range daba falsos verdes: el enlace
     *   respondía a un trozo pequeño y luego rechazaba la descarga real.
     */
    suspend fun probe(
        url: String,
        userAgent: String = InnerTubeContexts.ANDROID_VR.userAgent,
        sendRange: Boolean = true
    ): Probe = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .apply { if (sendRange) header("Range", "bytes=0-1") }
                .apply {
                    // El mismo juego de cabeceras que usará el proxy al descargar de
                    // verdad. Si aquí se manda algo distinto, esta comprobación deja de
                    // significar nada.
                    InnerTubeContexts.streamHeaders(userAgent).forEach { (k, v) -> header(k, v) }
                }
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                // 206 es lo esperado con Range; 200 vale si el servidor lo ignora.
                val statusOk = response.code == 206 || response.code == 200
                if (!statusOk) {
                    return@withContext Probe(
                        ok = false,
                        httpStatus = response.code,
                        contentType = contentType
                    )
                }
                // Una respuesta HTML con código 200 es una página de error disfrazada.
                val looksLikeMedia = contentType == null ||
                    contentType.startsWith("audio/") ||
                    contentType.startsWith("video/") ||
                    contentType.contains("octet-stream")
                Probe(
                    ok = looksLikeMedia,
                    httpStatus = response.code,
                    contentType = contentType,
                    error = if (looksLikeMedia) null else "server returned $contentType, not audio"
                )
            }
        } catch (e: Exception) {
            Probe(ok = false, error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim())
        }
    }
}
