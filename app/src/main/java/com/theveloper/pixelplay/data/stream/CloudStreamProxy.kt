package com.theveloper.pixelplay.data.stream

import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.theveloper.pixelplay.data.youtube.awaitYouTubeResponse
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.ServerSocket

/**
 * Abstract base class for local HTTP proxy servers that stream cloud music audio.
 *
 * Subclasses define the route, ID type, validation, allowed hosts, and URL resolution.
 * The base class handles the full Ktor CIO server lifecycle, URL caching, and OkHttp
 * proxying with security checks via [CloudStreamSecurity].
 *
 * @param K The song identifier type (e.g. [String] for QQ Music songMid, [Long] for Netease songId)
 */
abstract class CloudStreamProxy<K : Any>(
    private val okHttpClient: OkHttpClient
) {
    // ─── Subclass Configuration ────────────────────────────────────────

    protected abstract val allowedHostSuffixes: Set<String>
    protected abstract val cacheExpirationMs: Long
    protected abstract val proxyTag: String

    /** Route path registered with Ktor, e.g. "/qqmusic/{songMid}" */
    protected abstract val routePath: String
    /** The parameter name inside the route path, e.g. "songMid" */
    protected abstract val routeParamName: String
    /** URI scheme this proxy handles, e.g. "qqmusic" or "netease" */
    protected abstract val uriScheme: String
    /** URL path prefix for proxy URLs, e.g. "/qqmusic" or "/netease" */
    protected abstract val routePrefix: String

    /** Parse the raw route parameter string into the typed ID, or null if invalid */
    protected abstract fun parseRouteParam(value: String): K?
    /** Validate whether the given ID is acceptable */
    protected abstract fun validateId(id: K): Boolean
    /** Convert the ID to a string for use in URLs */
    protected abstract fun formatIdForUrl(id: K): String
    /** Resolve the actual streaming URL for the given song ID */
    protected abstract suspend fun resolveStreamUrl(id: K): String?

    // ─── Server State ──────────────────────────────────────────────────

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverPort = MutableStateFlow(0)
    private val actualPort: Int get() = serverPort.value
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val urlCache = StreamUrlCache<K>(signedExpiryMs = { url ->
        Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.let { it * 1000L - 60_000L }
    })
    private val urlPrefetcher = StreamUrlPrefetcher<K>(
        scope = proxyScope,
        resolve = { id -> getOrFetchStreamUrl(id, minOf(cacheExpirationMs, 120_000L)); Unit },
        onFailure = { Timber.d(it, "$proxyTag next-track preparation failed") }
    )

    private companion object {
        // Bounded requests avoid CDN throttling without throwing away healthy signed
        // URLs every 600 KB. Refresh only on expiry or a real upstream rejection.
        const val UPSTREAM_CHUNK_SIZE = 512_000L
        const val MAX_UPSTREAM_ATTEMPTS = 4
    }

    // ─── Public API ────────────────────────────────────────────────────

    fun isReady(): Boolean = actualPort > 0

    @Synchronized
    fun startIfNeeded() {
        if (isReady() || startJob?.isActive == true) return
        start()
    }

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (isReady()) return true
        return withTimeoutOrNull(timeoutMs) { serverPort.first { it > 0 }; true } ?: false
    }

    suspend fun ensureReady(timeoutMs: Long = 10_000L): Boolean {
        startIfNeeded()
        return awaitReady(timeoutMs)
    }

    /**
     * Resolve only the next queued track's URL while playback is already underway.
     * This never requests audio or starts the HTTP server. Pass null to cancel when
     * paused/stopped; signed URLs and the normal foreground request share one cache.
     */
    fun prefetchStreamUrl(id: K?) {
        urlPrefetcher.prefetch(id?.takeIf(::validateId))
    }

    fun getProxyUrl(id: K): String {
        if (actualPort == 0) return ""
        if (!validateId(id)) return ""
        return "http://127.0.0.1:$actualPort$routePrefix/${formatIdForUrl(id)}"
    }

    /**
     * Parse a cloud URI (e.g. "qqmusic://xxxx" or "netease://12345") and return
     * the local proxy URL. Returns null if the URI doesn't match this proxy's scheme.
     */
    fun resolveUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != uriScheme) return null
        val rawId = extractIdFromUri(uri) ?: return null
        val id = parseRouteParam(rawId) ?: return null
        if (!validateId(id)) return null
        return getProxyUrl(id)
    }

    @Synchronized
    fun start() {
        if (isReady() || startJob?.isActive == true) return
        startJob?.cancel()
        startJob = proxyScope.launch {
            try {
                val freePort = ServerSocket(0).use { it.localPort }
                val createdServer = createServer(freePort)
                createdServer.start(wait = false)
                server = createdServer
                serverPort.value = freePort
                Timber.d("$proxyTag started on port $actualPort")
            } catch (_: CancellationException) {
                Timber.d("$proxyTag start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start $proxyTag")
            }
        }
    }

    @Synchronized
    fun stop() {
        urlPrefetcher.prefetch(null)
        startJob?.cancel()
        startJob = null
        proxyScope.coroutineContext.cancelChildren()
        server?.stop(1000, 2000)
        server = null
        serverPort.value = 0
        urlCache.clear()
        Timber.d("$proxyTag stopped")
    }

    // ─── Overridable Hooks ─────────────────────────────────────────────

    /** Extract the raw ID string from a parsed URI. Override for custom URI layouts. */
    protected open fun extractIdFromUri(uri: Uri): String? = uri.host

    /**
     * Extra headers to send when fetching the upstream audio.
     *
     * Some CDNs tie a stream URL to the client that requested it and answer 403 to anyone
     * else, so the subclass has to be able to reproduce the original request's identity.
     *
     * Takes [url] as well as [id] on purpose: with only [id], a subclass would have to look
     * the matching headers up in some id-keyed side table it fills in during resolution — and
     * under concurrent requests for the same [id] (a reconnect racing the connection it's
     * replacing, for instance), a second resolve can overwrite that entry before the first
     * request reads it, pairing request A's URL with request B's headers. Since [url] is
     * unique per resolve, keying the lookup by url instead of id removes that race entirely.
     */
    protected open fun upstreamHeaders(id: K, url: String): Map<String, String> = emptyMap()

    // ─── Internal ──────────────────────────────────────────────────────

    /** Olvida la URL cacheada de [id] para forzar que se vuelva a resolver. */
    protected fun invalidateCachedUrl(id: K) {
        urlCache.invalidate(id)
    }

    /** Called before refreshing so a source can advance past a rejected resolver. */
    protected open fun onUpstreamFailure(id: K, url: String, status: Int) = Unit

    protected suspend fun getOrFetchStreamUrl(id: K): String? = getOrFetchStreamUrl(id, cacheExpirationMs)

    private suspend fun getOrFetchStreamUrl(id: K, maxAgeMs: Long): String? =
        urlCache.getOrResolve(id, maxAgeMs) { withTimeoutOrNull(25_000L) { resolveStreamUrl(id) } }

    private fun safeUrl(url: String) = CloudStreamSecurity.isSafeRemoteStreamUrl(
        url, allowedHostSuffixes, allowHttpForAllowedHosts = true
    )

    private fun totalLength(response: okhttp3.Response, url: String): Long? =
        response.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
            ?: Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
            ?: response.body.contentLength().takeIf { response.code == 200 && it >= 0 }

    private fun createServer(port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get(routePath) {
                    val id = call.parameters[routeParamName]?.let(::parseRouteParam)
                    if (id == null || !validateId(id)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID")
                        return@get
                    }
                    val range = CloudStreamSecurity.validateRangeHeader(call.request.headers["Range"])
                    if (!range.isValid) {
                        call.respond(HttpStatusCode(416, "Range Not Satisfiable"), "Invalid range header")
                        return@get
                    }
                    var headersSent = false
                    var initialResponse: okhttp3.Response? = null
                    try {
                        var activeUrl = getOrFetchStreamUrl(id)
                        if (activeUrl.isNullOrBlank()) {
                            call.respond(HttpStatusCode.ServiceUnavailable, "Audio is not available yet; try again")
                            return@get
                        }
                        if (!safeUrl(activeUrl)) {
                            call.respond(HttpStatusCode.BadGateway, "Rejected upstream stream URL")
                            return@get
                        }
                        var url: String = activeUrl
                        var expectedTotal: Long? = null
                        var deliveredAnyBytes = false
                        var originalItag = Uri.parse(url).getQueryParameter("itag")

                        suspend fun fetch(start: Long, end: Long): okhttp3.Response {
                            repeat(MAX_UPSTREAM_ATTEMPTS) { attempt ->
                                val request = Request.Builder().url(url)
                                    .header("Range", "bytes=$start-$end")
                                    .header("Accept-Encoding", "identity")
                                    .apply { upstreamHeaders(id, url).forEach { (name, value) -> header(name, value) } }
                                    .build()
                                val response = okHttpClient.newCall(request).awaitYouTubeResponse()
                                val retryable = response.code in setOf(401, 403, 404, 410, 429, 500, 502, 503, 504)
                                if (!retryable || attempt == MAX_UPSTREAM_ATTEMPTS - 1) return response
                                val status = response.code
                                response.close()
                                // A new client can use another codec. Never splice its bytes
                                // into an existing file; only switch strategies before delivery.
                                // Refresh once with the same client first: the user's IP
                                // may have changed after switching Wi-Fi/mobile data.
                                if (!deliveredAnyBytes && attempt > 0) onUpstreamFailure(id, url, status)
                                invalidateCachedUrl(id)
                                if (status == 429 || status >= 500) delay(250L * (attempt + 1))
                                val refreshed = getOrFetchStreamUrl(id)
                                    ?: throw IOException("Could not refresh audio after HTTP $status")
                                if (!safeUrl(refreshed)) throw IOException("Rejected refreshed audio URL")
                                if (deliveredAnyBytes && originalItag != null &&
                                    Uri.parse(refreshed).getQueryParameter("itag") != originalItag) {
                                    throw IOException("Audio format changed during a stream; reconnect required")
                                }
                                url = refreshed
                            }
                            throw IOException("Audio retry budget exhausted")
                        }

                        fun verifyChunk(response: okhttp3.Response, start: Long) {
                            if (response.code != 200 && response.code != 206) {
                                throw IOException("Audio upstream returned HTTP ${response.code}")
                            }
                            if (response.code == 200 && start > 0) {
                                throw IOException("Audio upstream ignored the requested seek range")
                            }
                            if (response.code == 206) {
                                val returnedStart = response.header("Content-Range")
                                    ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                                if (returnedStart != start) throw IOException("Audio upstream returned an incorrect byte range")
                            }
                            val actualTotal = totalLength(response, url)
                            if (deliveredAnyBytes && expectedTotal != null && actualTotal != null && actualTotal != expectedTotal) {
                                throw IOException("Audio resource changed during a stream")
                            }
                            if (!CloudStreamSecurity.isSupportedAudioContentType(response.header("Content-Type"))) {
                                throw IOException("Unsupported audio content type")
                            }
                        }

                        var start = range.startInclusive ?: 0L
                        // Suffix ranges need the resource length before a bounded request.
                        if (range.isSuffixRange) {
                            fetch(0, 0).use { probe ->
                                verifyChunk(probe, 0)
                                val total = totalLength(probe, url) ?: throw IOException("Audio length unavailable for suffix range")
                                start = (total - (range.endInclusive ?: 0L)).coerceAtLeast(0L)
                                expectedTotal = total
                            }
                        }
                        val requestedEnd = if (range.isSuffixRange) null else range.endInclusive
                        val firstEnd = minOf(start + UPSTREAM_CHUNK_SIZE - 1, requestedEnd ?: Long.MAX_VALUE)
                        val first = fetch(start, firstEnd)
                        initialResponse = first
                        if (first.code == 416) {
                            first.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"))
                            return@get
                        }
                        verifyChunk(first, start)
                        originalItag = Uri.parse(url).getQueryParameter("itag")
                        val total = totalLength(first, url) ?: expectedTotal
                        expectedTotal = total
                        if (total != null && !CloudStreamSecurity.isAcceptableContentLength(total.toString())) {
                            call.respond(HttpStatusCode(413, "Payload Too Large"))
                            return@get
                        }
                        if (total != null && start >= total) {
                            call.response.header("Content-Range", "bytes */$total")
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"))
                            return@get
                        }
                        val end = if (total != null) minOf(requestedEnd ?: Long.MAX_VALUE, total - 1) else requestedEnd
                        val responseLength = end?.let { it - start + 1 }
                        val hasRange = range.normalizedHeader != null
                        call.response.status(if (hasRange && total != null) HttpStatusCode.PartialContent else HttpStatusCode.OK)
                        if (hasRange && total != null) {
                            call.response.header("Content-Range", "bytes $start-$end/$total")
                        }
                        call.response.header("Accept-Ranges", "bytes")
                        val contentType = first.header("Content-Type")?.substringBefore(';')
                            ?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Audio.Any
                        headersSent = true
                        call.respondBytesWriter(contentType = contentType, contentLength = responseLength) {
                            val channel = this
                            withContext(Dispatchers.IO) {
                                var position = start
                                var response = first
                                while (true) {
                                    val responseWasFull = response.code == 200
                                    val chunkLimit = if (responseWasFull) end?.let { it - position + 1 }
                                        else minOf(UPSTREAM_CHUNK_SIZE, end?.let { it - position + 1 } ?: Long.MAX_VALUE)
                                    var written = 0L
                                    response.use { upstream ->
                                        verifyChunk(upstream, position)
                                        upstream.body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (chunkLimit == null || written < chunkLimit) {
                                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(),
                                                    chunkLimit?.minus(written) ?: buffer.size.toLong()).toInt())
                                                if (count < 0) break
                                                channel.writeFully(buffer, 0, count)
                                                written += count
                                                deliveredAnyBytes = true
                                            }
                                        }
                                    }
                                    position += written
                                    if (end != null && position > end) break
                                    if (written == 0L || responseWasFull || (total == null && written < UPSTREAM_CHUNK_SIZE)) {
                                        if (end != null && position <= end) throw IOException("Audio stream ended before its advertised length")
                                        break
                                    }
                                    val nextEnd = minOf(position + UPSTREAM_CHUNK_SIZE - 1, end ?: Long.MAX_VALUE)
                                    response = fetch(position, nextEnd)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "$proxyTag stream failed")
                        if (!headersSent) call.respond(HttpStatusCode.BadGateway, "Unable to load audio; please retry")
                        else throw e // An incomplete body must fail, never appear to be a finished song.
                    } finally {
                        initialResponse?.close()
                    }
                }
            }
        }
}
