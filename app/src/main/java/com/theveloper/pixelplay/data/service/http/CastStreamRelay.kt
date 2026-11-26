package com.theveloper.pixelplay.data.service.http

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.StreamContentTypes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * Cast plays streamed songs through this relay (handoff/2026-11-25-cast-stream.md).
 *
 * Every song of this fork has a `spotify://<id>` address that no ContentProvider opens, so the
 * Cast file server ([MediaFileHttpServerService]) used to answer 404 for all of them. A Cast
 * receiver cannot reach the stream proxy either: `SpotifyStreamProxy` listens on 127.0.0.1 only.
 * The file server now forwards such a request to the proxy's loopback URL, Range header in and
 * status, length, range and type back out, so the receiver gets the same bytes this phone plays.
 *
 * The security rules are the file server's own ([CastSessionSecurity]), plus three for the relay:
 *  - the song id must be in the session's allowlist with a track id registered for it (no id from
 *    the request is ever turned into a stream on its own);
 *  - the asking client must be on the LAN (private, link-local or loopback address);
 *  - the relay only ever talks to the proxy's loopback `/spotify/<id>` route, never to a host
 *    named by anyone else. The proxy keeps its own upstream allowlist (googlevideo, SpotiFLAC).
 */
internal object CastStreamRelay {

    /** Response headers copied from the proxy to the receiver; everything else stays behind. */
    val RELAYED_RESPONSE_HEADERS: Set<String> = setOf("content-type", "content-length", "content-range", "accept-ranges")

    /** Used when nothing says what the stream is: [StreamContentTypes.DEFAULT]. */
    const val DEFAULT_STREAM_CONTENT_TYPE = StreamContentTypes.DEFAULT

    private const val SCHEME = "spotify://"
    private const val PROXY_ROUTE = "spotify"

    /**
     * What the stream proxy can tell without the network: the content type of the stream it has a
     * URL for right now (null when none). Set by [MediaFileHttpServerService] while it runs, read by
     * `CastPlayer` when it builds queue items.
     */
    @Volatile
    var cachedTypeLookup: ((String) -> String?)? = null

    /** The track id of a streamed song's address (`spotify://<22 base62>`), or null for anything else. */
    fun trackIdOf(contentUri: String?): String? {
        if (contentUri == null || !contentUri.startsWith(SCHEME)) return null
        val id = contentUri.removePrefix(SCHEME).substringBefore('/').substringBefore('?')
        return id.takeIf(CloudStreamSecurity::validateSpotifyTrackId)
    }

    /** Song id to track id, for the songs of a Cast queue that play through the relay. */
    fun relayTrackIdsOf(songs: Collection<Song>): Map<String, String> {
        val out = LinkedHashMap<String, String>(songs.size * 2)
        for (song in songs) {
            val trackId = trackIdOf(song.contentUriString) ?: continue
            out[song.id] = trackId
        }
        return out
    }

    /** The content type to tell the receiver for a streamed song before any of it was served. */
    fun contentTypeHint(trackId: String): String =
        runCatching { cachedTypeLookup?.invoke(trackId) }.getOrNull()?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
            ?: DEFAULT_STREAM_CONTENT_TYPE

    /**
     * True only for the stream proxy's own loopback route for one valid track id:
     * `http://127.0.0.1:<port>/spotify/<id>`, nothing else in it.
     */
    fun isAllowedRelayTarget(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http") return false
        if (parsed.host != "127.0.0.1") return false
        if (parsed.port !in 1..65535) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.querySize != 0 || parsed.fragment != null) return false
        val segments = parsed.pathSegments
        return segments.size == 2 && segments[0] == PROXY_ROUTE && CloudStreamSecurity.validateSpotifyTrackId(segments[1])
    }

    /** A client the relay serves: an address on this network (or this phone). Names are not addresses. */
    fun isLanClientAddress(rawAddress: String?): Boolean {
        val address = rawAddress?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return false
        val v4 = address.removePrefix("::ffff:")
        if (v4.count { it == '.' } == 3 && v4.all { it.isDigit() || it == '.' }) {
            return CloudStreamSecurity.isPrivateIpv4Literal(v4) && !v4.startsWith("0.")
        }
        if (address.contains(':') && address.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '%' || it == '.' }) {
            return CloudStreamSecurity.isLocalOrPrivateHost(address.substringBefore('%'))
        }
        return false
    }

    /** The request headers sent to the proxy: a valid single Range, nothing else. Null when the Range is invalid. */
    fun forwardedRequestHeaders(rangeHeader: String?): Map<String, String>? {
        val range = CloudStreamSecurity.validateRangeHeader(rangeHeader)
        if (!range.isValid) return null
        val normalized = range.normalizedHeader ?: return emptyMap()
        return mapOf("Range" to normalized)
    }

    /** [headers] (any case) without the ones the receiver must not see. Keys come back lower-case. */
    fun relayedResponseHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(4)
        for ((name, value) in headers) {
            val key = name.lowercase()
            if (key in RELAYED_RESPONSE_HEADERS) out[key] = value
        }
        return out
    }
}

/** What the proxy answered, ready to go out to the receiver. */
internal class RelayUpstream(
    val status: Int,
    /** Lower-case names, filtered by [CastStreamRelay.relayedResponseHeaders]. */
    val headers: Map<String, String>,
    val body: InputStream?,
) : AutoCloseable {
    override fun close() {
        runCatching { body?.close() }
    }
}

/** One request to the proxy. The real one is [OkHttpRelayTransport]; tests use a fake. */
internal fun interface RelayTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): RelayUpstream
}

/** Outcome of [CastRelayForwarder.open]. */
internal sealed class RelayOutcome {
    /** Refused before anything was asked of the proxy; [status] is what the receiver gets. */
    data class Refused(val status: Int, val reason: String) : RelayOutcome()

    /** The proxy answered (any status); its body is to be copied out and closed. */
    class Forwarded(val upstream: RelayUpstream) : RelayOutcome()
}

/** The relay's checks around one transport call. Pure apart from the transport. */
internal class CastRelayForwarder(private val transport: RelayTransport) {

    fun open(targetUrl: String?, rangeHeader: String?): RelayOutcome {
        if (!CastStreamRelay.isAllowedRelayTarget(targetUrl)) return RelayOutcome.Refused(502, "relay target is not the stream proxy")
        val headers = CastStreamRelay.forwardedRequestHeaders(rangeHeader)
            ?: return RelayOutcome.Refused(416, "invalid range")
        val upstream = try {
            transport.get(checkNotNull(targetUrl), headers)
        } catch (e: IOException) {
            return RelayOutcome.Refused(502, "stream proxy unreachable: ${e.javaClass.simpleName}")
        }
        return RelayOutcome.Forwarded(
            RelayUpstream(upstream.status, CastStreamRelay.relayedResponseHeaders(upstream.headers), upstream.body)
        )
    }
}

/** The transport over OkHttp. A plain client: it only ever talks to 127.0.0.1, never to YouTube. */
internal class OkHttpRelayTransport(private val client: OkHttpClient) : RelayTransport {
    override fun get(url: String, headers: Map<String, String>): RelayUpstream {
        val request = Request.Builder().url(url).get().apply {
            for ((name, value) in headers) header(name, value)
        }.build()
        val response = client.newCall(request).execute()
        val out = LinkedHashMap<String, String>()
        for (name in response.headers.names()) response.header(name)?.let { out[name] = it }
        return RelayUpstream(response.code, out, response.body.byteStream())
    }
}
