package com.theveloper.pixelplay.data.spotify

import android.os.SystemClock
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.diagnostics.StreamStartTimings
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.preferences.AudioQuality
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.spotiflac.AudioFileFacts
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.theveloper.pixelplay.data.spotiflac.SpotiFLACClient
import com.theveloper.pixelplay.data.spotiflac.SpotiFLACResolvedStream
import com.theveloper.pixelplay.data.spotiflac.toSpotiFLACQuery
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.StreamItag
import com.theveloper.pixelplay.data.youtube.ChainedYouTubeStreamResolver
import com.theveloper.pixelplay.data.youtube.newpipe.NewPipeStreamResolver
import com.theveloper.pixelplay.data.youtube.piped.PipedStreamResolver
import io.ktor.http.ContentType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stream [SpotifyStreamProxy] serves for a song: the YouTube video its URL was resolved for, the stream's itag, and
 * its length in bytes (the URL's `clen`; null when the URL doesn't say). A file of exactly that length fetched from the
 * proxy is that stream, byte for byte. [isSpotiFLAC]: the song's SpotiFLAC file instead of the video's audio (no itag;
 * its length is known once the proxy served it).
 */
data class StreamSource(
    val videoId: String,
    val itag: Int?,
    val contentLength: Long?,
    val isSpotiFLAC: Boolean = false
)

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
    private val pipedStreamResolver: PipedStreamResolver,
    private val spotiFLACClient: SpotiFLACClient,
    private val userPreferencesRepository: UserPreferencesRepository
) : CloudStreamProxy<String>(okHttpClient) {

    // trustedHosts crece en caliente con las instancias que el registro oficial de Piped
    // devuelva (ver PipedStreamResolver) — de ahí el getter en vez de un valor fijo: la lista
    // fija de tres instancias murió entera de una sesión a otra, así que hace falta que esto
    // siga al día con lo que el resolver haya descubierto, no solo lo que había al arrancar.
    override val allowedHostSuffixes: Set<String>
        get() = setOf("googlevideo.com") +
            pipedStreamResolver.trustedHosts +
            SpotiFLACClient.TRUSTED_AUDIO_HOST_SUFFIXES

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
    private data class StreamIdentity(
        val userAgent: String,
        val strategy: String?,
        /** The YouTube video the URL was resolved for (null for a SpotiFLAC file). */
        val videoId: String? = null,
        val mimeType: String? = null
    )
    private val identityByUrl = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, StreamIdentity>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamIdentity>?) = size > 256
        }
    )
    private data class FailedStrategy(val name: String, val atMs: Long)
    private val failuresById = java.util.concurrent.ConcurrentHashMap<String, List<FailedStrategy>>()
    private val matchLocks = Array(32) { Mutex() }

    // Streaming speed R11: a song nothing on YouTube matches is not searched again for 10
    // minutes; the next-song warm-up re-checks every 60 s and would otherwise run its searches
    // four times a minute. The stored match is still read first, so a later manual or
    // background match wins at once. Timeouts and network failures are not remembered.
    private val noMatchUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // A track whose matched video turned out unplayable is re-matched once (to the next-best
    // candidate) and not again for a while, so a second failure can't loop.
    private val rematchedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private companion object {
        const val NO_MATCH_MEMORY_MS = 10 * 60_000L
        const val REMATCH_MEMORY_MS = 30 * 60_000L

        const val SPOTIFLAC_STRATEGY = "SpotiFLAC"

        // The whole SpotiFLAC lookup (usually one ISRC search, ~0.6 s) gets this long before the
        // YouTube chain runs; that chain still has to fit in the proxy's 25 s.
        const val SPOTIFLAC_BUDGET_MS = 6_000L

        // Monochrome answers each range request after about a second and caps a connection near
        // 0.3 MB/s, about twice what FLAC plays at: 1 MB pieces, three at a time (~1 MB/s).
        const val SPOTIFLAC_CHUNK_BYTES = 1_048_576L
        const val SPOTIFLAC_PARALLEL_PIECES = 3
    }

    /** The audio quality setting (Pro if it can't be read). */
    private suspend fun audioQuality(): AudioQuality = try {
        userPreferencesRepository.audioQualityFlow.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AudioQuality.DEFAULT
    }

    /**
     * What each track's SpotiFLAC file turned out to be, read from its first bytes (see
     * [acceptStreamHead]). Pro uses it to skip a FLAC over 48 kHz without fetching it again.
     */
    private val spotiFLACFactsById = java.util.concurrent.ConcurrentHashMap<String, AudioFileFacts>()

    /** Tracks whose SpotiFLAC file was chosen only because YouTube had nothing: any file beats none. */
    private val spotiFLACLastResort = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    override fun wantsStreamHead(id: String, url: String): Boolean = isSpotiFLACUrl(url)

    override suspend fun acceptStreamHead(id: String, url: String, head: ByteArray): Boolean {
        val facts = AudioFileFacts.parse(head)
        if (spotiFLACFactsById.size > 512) spotiFLACFactsById.clear()
        spotiFLACFactsById[id] = facts
        if (id in spotiFLACLastResort) return true
        val accepted = audioQuality().allowsFlacFirst(facts.flacSampleRateHz)
        if (!accepted) {
            Timber.d("SpotiFLAC file for %s is %s Hz; above the quality setting, using YouTube", id, facts.sampleRateHz)
        }
        return accepted
    }

    /** A SpotiFLAC audio URL, even after its identity left [identityByUrl]. */
    private fun isSpotiFLACUrl(url: String): Boolean {
        if (identityByUrl[url]?.strategy == SPOTIFLAC_STRATEGY) return true
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return SpotiFLACClient.TRUSTED_AUDIO_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    override fun upstreamChunkSize(id: String, url: String): Long =
        if (isSpotiFLACUrl(url)) SPOTIFLAC_CHUNK_BYTES else super.upstreamChunkSize(id, url)

    override fun upstreamParallelism(id: String, url: String): Int =
        if (isSpotiFLACUrl(url)) SPOTIFLAC_PARALLEL_PIECES else super.upstreamParallelism(id, url)

    override fun onUpstreamFailure(id: String, url: String, status: Int) {
        if (status !in setOf(401, 403, 404, 410)) return
        val strategy = identityByUrl[url]?.strategy ?: return
        if (strategy == SPOTIFLAC_STRATEGY) {
            spotiFLACClient.invalidate(id)
        }
        if (failuresById.size > 128) failuresById.clear()
        failuresById.compute(id) { _, previous ->
            previous.orEmpty().filter { it.name != strategy } + FailedStrategy(strategy, System.currentTimeMillis())
        }
    }

    private fun excludedStrategies(id: String): Set<String> = failuresById[id].orEmpty()
        .filter { System.currentTimeMillis() - it.atMs < 120_000L }
        .mapTo(HashSet()) { it.name }


    private fun rememberUserAgent(
        url: String,
        userAgent: String,
        strategy: String?,
        videoId: String? = null,
        mimeType: String? = null
    ) {
        identityByUrl[url] = StreamIdentity(userAgent, strategy, videoId, mimeType)
    }

    /**
     * The stream this proxy serves for [spotifyId] while it is [videoId]'s: the YouTube video its URL was resolved for,
     * with that stream's itag and length. The URL cache is keyed by Spotify id and a changed match doesn't reach it (the
     * dashboard's Test playback, a catalog play), so a cached URL resolved for another video is dropped and resolved
     * again once. Null when nothing resolves, or the song now resolves to another video (its match changed again, or an
     * unplayable video was re-matched meanwhile). Resolves on demand (network), never downloads audio. Cloud Studio's
     * Spatial audio jobs use it to prove the audio they upload is the video their render is stored under.
     */
    suspend fun streamSourceFor(spotifyId: String, videoId: String): StreamSource? {
        if (!validateId(spotifyId) || !CloudStreamSecurity.validateYouTubeVideoId(videoId)) return null
        repeat(2) { attempt ->
            val url = getOrFetchStreamUrl(spotifyId) ?: return null
            val identity = identityByUrl[url]
            if (identity?.videoId == videoId) {
                return StreamSource(videoId, StreamItag.fromUrl(url), StreamItag.contentLengthFromUrl(url))
            }
            // SpotiFLAC's file (Pro / Studio Pro): the song's own recording, found by its ISRC, is the render's source
            // (Hoa, 2026-11-11: "the flac to basically be the source"). It is stored under the video all the same; a
            // phone playing other audio of the song (the video, on a capped tier) won't switch to it mid-song when the
            // lengths differ (SpatialRenditionRule.linesUp) and starts on it from the next play instead.
            if (identity?.strategy == SPOTIFLAC_STRATEGY || (identity == null && isSpotiFLACUrl(url))) {
                return StreamSource(videoId, itag = null, contentLength = spotiFLACTotalByUrl[url], isSpotiFLAC = true)
            }
            if (attempt == 0) invalidateCachedUrl(spotifyId)
        }
        return null
    }

    /** Each SpotiFLAC file's whole length once the proxy served it (its URL carries no `clen`). */
    private val spotiFLACTotalByUrl = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onUpstreamTotal(id: String, url: String, totalBytes: Long) {
        if (!isSpotiFLACUrl(url)) return
        if (spotiFLACTotalByUrl.size > 512) spotiFLACTotalByUrl.clear()
        spotiFLACTotalByUrl[url] = totalBytes
    }

    override fun resolveResponseContentType(
        id: String,
        url: String,
        upstreamContentType: String?
    ): ContentType {
        // Monochrome sends everything as application/octet-stream. Its own first bytes say what the file
        // is (read by acceptStreamHead); before that, Monochrome's files are nearly always FLAC.
        if (isSpotiFLACUrl(url)) {
            val normalizedUpstream = upstreamContentType?.substringBefore(';')?.trim()?.lowercase()
            val upstreamSaysNothing = normalizedUpstream == null ||
                normalizedUpstream == "application/octet-stream" ||
                normalizedUpstream == "binary/octet-stream"
            val sniffed = spotiFLACFactsById[id]?.format?.mimeType
            val known = sniffed ?: identityByUrl[url]?.mimeType
                ?: "audio/flac".takeIf { url.toHttpUrlOrNull()?.host?.lowercase()?.endsWith("monochrome.st") == true }
            if (known != null && (upstreamSaysNothing || sniffed != null)) {
                return runCatching { ContentType.parse(known) }.getOrNull()
                    ?: super.resolveResponseContentType(id, url, upstreamContentType)
            }
        }
        return super.resolveResponseContentType(id, url, upstreamContentType)
    }

    override fun upstreamHeaders(id: String, url: String): Map<String, String> {
        val identity = identityByUrl[url]
        if (identity?.strategy == SPOTIFLAC_STRATEGY || (identity == null && isSpotiFLACUrl(url))) {
            return mapOf("User-Agent" to (identity?.userAgent ?: SpotiFLACResolvedStream.DEFAULT_USER_AGENT))
        }
        if (identity == null) return emptyMap()
        // Exactamente las mismas cabeceras con las que se validó el enlace, ni una más.
        return com.theveloper.pixelplay.data.youtube.InnerTubeContexts.streamHeaders(identity.userAgent)
    }

    override suspend fun resolveStreamUrl(id: String): String? {
        val excluded = excludedStrategies(id)
        // SpotiFLAC is skipped for two minutes after its URL failed on this track.
        val spotiFLACUsable = SPOTIFLAC_STRATEGY !in excluded
        // Pro and Studio Pro: SpotiFLAC first, YouTube as the fallback; Pro skips a file it already
        // knows is over 48 kHz. Low/Medium/High stay on YouTube at their cap and only fall back to
        // SpotiFLAC when YouTube has nothing for the song.
        val flacFirst = spotiFLACUsable &&
            audioQuality().allowsFlacFirst(spotiFLACFactsById[id]?.flacSampleRateHz)
        if (flacFirst) {
            resolveViaSpotiFLAC(id)?.let {
                spotiFLACLastResort.remove(id)
                return it
            }
        }
        val viaYouTube = resolveViaYouTube(id, excluded)
        if (viaYouTube != null || flacFirst || !spotiFLACUsable) return viaYouTube
        return resolveViaSpotiFLAC(id)?.also {
            if (spotiFLACLastResort.size > 512) spotiFLACLastResort.clear()
            spotiFLACLastResort.add(id)
        }
    }

    /**
     * The SpotiFLAC lossless chain (Monochrome, then any Tidal HiFi instance), unless the user pinned a
     * YouTube video by hand. Bounded by SPOTIFLAC_BUDGET_MS; any failure in it is a `null`.
     */
    private suspend fun resolveViaSpotiFLAC(id: String): String? {
        val song = spotifyDao.getSongBySpotifyId(id) ?: return null
        if (song.matchState == SpotifyMatchState.MANUAL) return null
        val flacResolveStartMs = SystemClock.elapsedRealtime()
        val flacStream = try {
            withTimeoutOrNull(SPOTIFLAC_BUDGET_MS) {
                spotiFLACClient.resolve(song.toSpotiFLACQuery())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SpotiFLAC lookup failed for %s", id)
            null
        } ?: return null
        rememberUserAgent(
            url = flacStream.url,
            userAgent = flacStream.userAgent,
            strategy = SPOTIFLAC_STRATEGY,
            mimeType = flacStream.mimeType
        )
        StreamStartTimings.urlResolved(
            id = id,
            matchMs = null,
            resolveMs = SystemClock.elapsedRealtime() - flacResolveStartMs,
            strategy = SPOTIFLAC_STRATEGY,
            nParam = false,
            nTransformMs = null
        )
        return flacStream.url
    }

    /** YouTube matching & InnerTube / Piped / NewPipe resolution. */
    private suspend fun resolveViaYouTube(id: String, excluded: Set<String>): String? {
        // R12: how long matching (only when it happens on the spot) and resolving took.
        var matchMs: Long? = null

        // Playback and offline rendering may start before WorkManager runs. Repair the
        // missing match here, coalescing concurrent requests for the same track.
        val videoId = spotifyDao.getMatchedVideoId(id) ?: matchLocks[(id.hashCode() and Int.MAX_VALUE) % matchLocks.size].withLock {
            spotifyDao.getMatchedVideoId(id) ?: run {
                val song = spotifyDao.getSongBySpotifyId(id) ?: return@withLock null
                if (song.matchState == SpotifyMatchState.MANUAL) return@withLock null
                val nowMs = System.currentTimeMillis()
                if ((noMatchUntilMs[id] ?: 0L) > nowMs) return@withLock null
                val matchStartedAtMs = SystemClock.elapsedRealtime()
                var timedOut = true
                // Fan-out: same video as the background matcher, without waiting for each
                // search in turn (TrackMatcher.findMatchFanOut).
                val match = withTimeoutOrNull(25_000L) {
                    trackMatcher.findMatchFanOut(song).also { timedOut = false }
                }
                matchMs = SystemClock.elapsedRealtime() - matchStartedAtMs
                if (match == null) {
                    if (!timedOut) {
                        if (noMatchUntilMs.size > 256) noMatchUntilMs.clear()
                        noMatchUntilMs[id] = nowMs + NO_MATCH_MEMORY_MS
                    }
                    return@withLock null
                }
                noMatchUntilMs.remove(id)
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
        val resolveStartedAtMs = SystemClock.elapsedRealtime()
        fun reportResolved(url: String, strategy: String?, nTransformMs: Long?) {
            StreamStartTimings.urlResolved(
                id = id,
                matchMs = matchMs,
                resolveMs = SystemClock.elapsedRealtime() - resolveStartedAtMs,
                strategy = strategy,
                nParam = android.net.Uri.parse(url).getQueryParameter("n") != null,
                nTransformMs = nTransformMs
            )
        }
        val resolved = streamResolver.resolveStream(videoId, validate = false, excludedStrategies = excluded)
        // Read right away: the flag belongs to this resolve (two clients said the video itself is
        // unplayable, none said OK; the bot wall is not counted).
        val videoUnavailable = resolved == null && streamResolver.lastResolveVideoUnavailable
        if (resolved != null) {
            rememberUserAgent(resolved.url, resolved.userAgent, resolved.strategyName, videoId)
            reportResolved(resolved.url, resolved.strategyName, resolved.nTransformMs)
            return resolved.url
        }
        Timber.d("Resolver propio no resolvió $videoId (${streamResolver.lastDetail}); probando Piped")

        val pipedResolved = if ("Piped" in excluded) null else withTimeoutOrNull(15_000L) { pipedStreamResolver.resolve(videoId) }
        if (pipedResolved != null) {
            rememberUserAgent(pipedResolved.url, pipedResolved.userAgent, "Piped", videoId)
            reportResolved(pipedResolved.url, "Piped", null)
            return pipedResolved.url
        }
        Timber.d("Piped no resolvió $videoId (${pipedStreamResolver.lastDetail}); probando NewPipeExtractor")

        val newPipeResolved = if ("NewPipe" in excluded) null else withTimeoutOrNull(20_000L) { newPipeStreamResolver.resolve(videoId) }
        if (newPipeResolved != null) {
            rememberUserAgent(newPipeResolved.url, newPipeResolved.userAgent, "NewPipe", videoId)
            reportResolved(newPipeResolved.url, "NewPipe", null)
            return newPipeResolved.url
        }
        Timber.d("NewPipeExtractor no resolvió $videoId (${newPipeStreamResolver.lastDetail})")

        // The matched video itself won't play (region lock, age gate, removed): try the next-best
        // match once instead of skipping this song every time. A manual pick is never replaced.
        val lastRematch = rematchedAtMs[id]
        if (videoUnavailable && (lastRematch == null || System.currentTimeMillis() - lastRematch > REMATCH_MEMORY_MS)) {
            val song = spotifyDao.getSongBySpotifyId(id)
            if (song != null && song.matchState != SpotifyMatchState.MANUAL) {
                val replacement = withTimeoutOrNull(25_000L) { trackMatcher.findMatchFanOut(song, setOf(videoId)) }
                // Remembered only once the search actually ran: if the caller's timeout cancels it, we never get
                // here and the next attempt may re-match. Still set before the recursive resolve below.
                if (rematchedAtMs.size > 256) rematchedAtMs.clear()
                rematchedAtMs[id] = System.currentTimeMillis()
                if (replacement != null && replacement.videoId != videoId &&
                    CloudStreamSecurity.validateYouTubeVideoId(replacement.videoId)
                ) {
                    Timber.d("Video $videoId unplayable; re-matched $id to ${replacement.videoId}")
                    spotifyDao.updateMatch(id, replacement.videoId, replacement.score, SpotifyMatchState.MATCHED)
                    return resolveStreamUrl(id)
                }
            }
        }
        return null
    }
}
