package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.youtube.ChainedYouTubeStreamResolver
import com.theveloper.pixelplay.data.youtube.newpipe.NewPipeStreamResolver
import com.theveloper.pixelplay.data.youtube.piped.PipedStreamResolver
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proxy HTTP local que sirve el audio de una pista de Spotify.
 *
 * ExoPlayer recibe siempre `spotify://<trackId>`, nunca la URL real de YouTube. Eso deja
 * el emparejamiento como un detalle interno: si una pista se vuelve a emparejar con otro
 * vídeo, solo cambia una columna de la base de datos — la cola, las playlists y los
 * favoritos siguen apuntando al mismo sitio.
 *
 * Las URLs de `googlevideo.com` caducan (van ligadas a la IP y a un plazo de unas 6 horas),
 * así que la caché es más corta que eso.
 */
@Singleton
class SpotifyStreamProxy @Inject constructor(
    @com.theveloper.pixelplay.di.YouTubeOkHttpClient okHttpClient: OkHttpClient,
    private val spotifyDao: SpotifyDao,
    private val trackMatcher: TrackMatcher,
    private val streamResolver: ChainedYouTubeStreamResolver,
    private val newPipeStreamResolver: NewPipeStreamResolver,
    private val pipedStreamResolver: PipedStreamResolver
) : CloudStreamProxy<String>(okHttpClient) {

    // trustedHosts crece en caliente con las instancias que el registro oficial de Piped
    // devuelva (ver PipedStreamResolver) — de ahí el getter en vez de un valor fijo: la lista
    // fija de tres instancias murió entera de una sesión a otra, así que hace falta que esto
    // siga al día con lo que el resolver haya descubierto, no solo lo que había al arrancar.
    override val allowedHostSuffixes: Set<String>
        get() = setOf("googlevideo.com") + pipedStreamResolver.trustedHosts

    // 3 horas: la mitad del plazo típico de caducidad, para no servir nunca una URL muerta.
    override val cacheExpirationMs: Long = 3 * 60 * 60 * 1000L

    override val proxyTag: String = "SpotifyStreamProxy"
    override val routePath: String = "/spotify/{trackId}"
    override val routeParamName: String = "trackId"
    override val uriScheme: String = "spotify"
    override val routePrefix: String = "/spotify"

    /**
     * Los ids de Spotify son base62 y distinguen mayúsculas, así que se recorta la cadena
     * a mano en vez de usar `uri.host` — el parseo de host puede normalizar el caso y un
     * solo carácter cambiado convierte el id en otro distinto.
     */
    override fun extractIdFromUri(uri: android.net.Uri): String? =
        uri.toString().removePrefix("spotify://").substringBefore('/').takeIf { it.isNotBlank() }

    override fun parseRouteParam(value: String): String? = value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean = CloudStreamSecurity.validateSpotifyTrackId(id)

    override fun formatIdForUrl(id: String): String = id

    /**
     * `User-Agent` con el que se resolvió cada URL. googlevideo ata el enlace al cliente que
     * lo pidió, así que descargarlo con otro agente devuelve 403.
     *
     * Clave por URL, no por trackId: con la canción como clave, dos resoluciones
     * concurrentes de la MISMA pista (p. ej. una reconexión que arranca antes de que la
     * conexión anterior termine de cerrarse) podían pisarse la entrada la una a la otra,
     * emparejando la URL de una petición con el user-agent de otra — un desajuste que
     * googlevideo también responde con 403, indistinguible del resto. Cada URL es única por
     * resolución, así que usarla de clave elimina la carrera sin más.
     */
    private data class StreamIdentity(val userAgent: String, val strategy: String?)
    private val identityByUrl = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, StreamIdentity>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamIdentity>?) = size > 256
        }
    )
    private data class FailedStrategy(val name: String, val atMs: Long)
    private val failuresById = java.util.concurrent.ConcurrentHashMap<String, List<FailedStrategy>>()
    private val matchLocks = Array(32) { Mutex() }

    override fun onUpstreamFailure(id: String, url: String, status: Int) {
        if (status !in setOf(401, 403, 404, 410)) return
        val strategy = identityByUrl[url]?.strategy ?: return
        if (failuresById.size > 128) failuresById.clear()
        failuresById.compute(id) { _, previous ->
            previous.orEmpty().filter { it.name != strategy } + FailedStrategy(strategy, System.currentTimeMillis())
        }
    }

    private fun excludedStrategies(id: String): Set<String> = failuresById[id].orEmpty()
        .filter { System.currentTimeMillis() - it.atMs < 120_000L }
        .mapTo(HashSet()) { it.name }


    private fun rememberUserAgent(url: String, userAgent: String, strategy: String?) {
        identityByUrl[url] = StreamIdentity(userAgent, strategy)
    }

    override fun upstreamHeaders(id: String, url: String): Map<String, String> {
        val userAgent = identityByUrl[url]?.userAgent ?: return emptyMap()
        // Exactamente las mismas cabeceras con las que se validó el enlace, ni una más.
        return com.theveloper.pixelplay.data.youtube.InnerTubeContexts.streamHeaders(userAgent)
    }

    override suspend fun resolveStreamUrl(id: String): String? {
        // Playback and offline rendering may start before WorkManager runs. Repair the
        // missing match here, coalescing concurrent requests for the same track.
        val videoId = spotifyDao.getMatchedVideoId(id) ?: matchLocks[(id.hashCode() and Int.MAX_VALUE) % matchLocks.size].withLock {
            spotifyDao.getMatchedVideoId(id) ?: run {
                val song = spotifyDao.getSongBySpotifyId(id) ?: return@withLock null
                if (song.matchState == SpotifyMatchState.MANUAL) return@withLock null
                val match = withTimeoutOrNull(25_000L) { trackMatcher.findMatch(song) } ?: return@withLock null
                spotifyDao.updateAutomaticMatch(id, match.videoId, match.score, SpotifyMatchState.MATCHED)
                spotifyDao.getMatchedVideoId(id)
            }
        }
        if (videoId.isNullOrBlank()) return null
        if (!CloudStreamSecurity.validateYouTubeVideoId(videoId)) {
            Timber.w("videoId guardado no válido para $id")
            return null
        }
        // Resolver propio primero: es el único que manda la sesión de YouTube iniciada por
        // el usuario (ver YouTubeAuthManager) — eso es lo que de verdad quita el muro de
        // "confirma que no eres un robot" que YouTube le pone a cualquier cliente anónimo,
        // Piped incluido (su servidor usa la misma extracción sin sesión y se topa con el
        // mismo muro). Antes este resolver iba el último, detrás de NewPipeExtractor — pero
        // NewPipeExtractor SIEMPRE "resuelve" (da una URL), solo que esa URL viene recortada
        // a ~900 KB por ser anónima, así que el resolver autenticado nunca llegaba a probarse
        // de verdad aunque hubiera sesión iniciada.
        //
        // validate = false: que la única descarga sea la del propio proxy. Comprobar la URL
        // aquí la gastaría (googlevideo la da por usada) y la reproducción real moriría con
        // un 403 justo después.
        val excluded = excludedStrategies(id)
        val resolved = streamResolver.resolveStream(videoId, validate = false, excludedStrategies = excluded)
        if (resolved != null) {
            rememberUserAgent(resolved.url, resolved.userAgent, resolved.strategyName)
            return resolved.url
        }
        Timber.d("Resolver propio no resolvió $videoId (${streamResolver.lastDetail}); probando Piped")

        val pipedResolved = if ("Piped" in excluded) null else withTimeoutOrNull(15_000L) { pipedStreamResolver.resolve(videoId) }
        if (pipedResolved != null) {
            rememberUserAgent(pipedResolved.url, pipedResolved.userAgent, "Piped")
            return pipedResolved.url
        }
        Timber.d("Piped no resolvió $videoId (${pipedStreamResolver.lastDetail}); probando NewPipeExtractor")

        val newPipeResolved = if ("NewPipe" in excluded) null else withTimeoutOrNull(20_000L) { newPipeStreamResolver.resolve(videoId) }
        if (newPipeResolved != null) {
            rememberUserAgent(newPipeResolved.url, newPipeResolved.userAgent, "NewPipe")
            return newPipeResolved.url
        }
        Timber.d("NewPipeExtractor no resolvió $videoId (${newPipeStreamResolver.lastDetail})")
        return null
    }
}
