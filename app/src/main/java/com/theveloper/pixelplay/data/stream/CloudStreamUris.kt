package com.theveloper.pixelplay.data.stream

/**
 * Conversión entre la URI canónica de una pista y la URL del proxy local.
 *
 * La URL del proxy (`http://127.0.0.1:46399/spotify/<id>`) solo vale mientras viva ese
 * arranque del servidor: el puerto se pide libre al sistema y cambia cada vez. Por eso
 * **nunca** debe guardarse en ningún sitio duradero — ni en disco, ni en una caché que
 * sobreviva a un reinicio del proxy.
 *
 * Guardar una en la cola de reproducción persistida hacía que, al volver a abrir la app,
 * el reproductor intentase conectarse a un puerto cerrado y fallara con "connection
 * refused" pase lo que pase aguas arriba.
 */
object CloudStreamUris {

    // Solo loopback: cualquier otra cosa es una URL remota legítima que hay que respetar.
    private val LOOPBACK_PROXY_URL = Regex(
        """^https?://(?:127\.0\.0\.1|localhost)(?::\d+)?/([a-z]+)/([^/?#]+)"""
    )

    /**
     * Devuelve la URI canónica (`spotify://<id>`) si [uri] es una URL del proxy local;
     * si no, la deja intacta.
     */
    fun canonicalize(uri: String): String {
        val match = LOOPBACK_PROXY_URL.find(uri) ?: return uri
        val scheme = match.groupValues[1]
        val id = match.groupValues[2]
        return "$scheme://$id"
    }

    /** true si [uri] apunta al proxy local y, por tanto, no se puede persistir. */
    fun isLocalProxyUrl(uri: String): Boolean = LOOPBACK_PROXY_URL.containsMatchIn(uri)
}
