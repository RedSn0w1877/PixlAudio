package com.theveloper.pixelplay.data.spotiflac

import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.youtube.awaitYouTubeResponse
import com.theveloper.pixelplay.di.YouTubeOkHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-provider SpotiFLAC lossless stream resolver. Same order and rules as iOS
 * (`PixlNet/SpotiFLAC/SpotiFLACClient.swift`):
 *
 * 1. **Monochrome Track Streamer (`tracks.monochrome.st`)** — an `isrc:<ISRC>` search finds the exact
 *    recording in one request; otherwise `/search/tracks?q=<main artist> <title>` (then the title alone)
 *    is scored by [SpotiFLACMatcher]. Audio is `/track/:trackId`: raw FLAC with byte ranges.
 * 2. **Tidal HiFi / OpenAPI instances** — `/search/?i=<ISRC>` or `?s=<query>`, then
 *    `/track/?id=<id>&quality=LOSSLESS`. No instance is built in any more (see [DEFAULT_HIFI_BASE_URLS]).
 * 3. **Deezer public API** — fills a missing title, artist or duration by ISRC (rare for Spotify rows).
 *
 * Every request is cancellable: the stream proxy gives the whole lookup a short budget and then falls
 * back to YouTube, so a slow provider costs at most that budget and nothing keeps running after it.
 */
@Singleton
class SpotiFLACClient @Inject constructor(
    @YouTubeOkHttpClient sharedOkHttpClient: OkHttpClient
) {

    private val httpClient: OkHttpClient = sharedOkHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private data class CachedEntry(
        val stream: SpotiFLACResolvedStream,
        val expiresAtMs: Long
    )

    /** Whether any request of one lookup failed (network, 429, 5xx, a host cooling down). */
    private class Lookup {
        @Volatile var hadErrors = false
    }

    private val resolvedCache = ConcurrentHashMap<String, CachedEntry>()
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val hostCooldownUntil = ConcurrentHashMap<String, Long>()

    var monochromeBaseUrl: String = DEFAULT_MONOCHROME_BASE_URL
    var hifiBaseUrls: List<String> = DEFAULT_HIFI_BASE_URLS
    var deezerBaseUrl: String = DEFAULT_DEEZER_BASE_URL

    /**
     * Resolves a lossless FLAC stream for [query], or returns `null` if no lossless provider
     * could match and resolve the track (the caller then falls back to YouTube).
     */
    suspend fun resolve(query: SpotiFLACTrackQuery): SpotiFLACResolvedStream? = withContext(Dispatchers.IO) {
        val cacheKey = cacheKeyFor(query)
        val now = System.currentTimeMillis()

        resolvedCache[cacheKey]?.let { hit ->
            if (now < hit.expiresAtMs) return@withContext hit.stream
            resolvedCache.remove(cacheKey)
        }
        negativeCache[cacheKey]?.let { missUntil ->
            if (now < missUntil) return@withContext null
            negativeCache.remove(cacheKey)
        }

        val lookup = Lookup()
        val enrichedQuery = enrichQueryIfNeeded(query, lookup)

        val stream = resolveViaMonochrome(enrichedQuery, lookup)
            ?: resolveViaHiFiInstances(enrichedQuery, lookup)
        if (stream != null) {
            storePositive(cacheKey, stream)
            return@withContext stream
        }

        // Only a clean "no such track" is remembered: a lookup that met errors or a cooling-down host
        // may well succeed on the next play.
        if (!lookup.hadErrors) {
            if (negativeCache.size >= 512) negativeCache.clear()
            negativeCache[cacheKey] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
        }
        null
    }

    /**
     * Invalidates any cached resolution for [spotifyId] (or query cache key).
     */
    fun invalidate(spotifyId: String) {
        resolvedCache.remove(spotifyId)
        negativeCache.remove(spotifyId)
    }

    // -------------------------------------------------------------------------
    // Step 1: Monochrome (`tracks.monochrome.st`)
    // -------------------------------------------------------------------------

    private suspend fun resolveViaMonochrome(query: SpotiFLACTrackQuery, lookup: Lookup): SpotiFLACResolvedStream? {
        // 1a. The exact recording: `isrc:` is Monochrome's own search syntax (a bare ISRC matches text).
        SpotiFLACMatcher.normalizeIsrc(query.isrc)?.let { isrc ->
            val exact = SpotiFLACMatcher.selectBestCandidate(searchMonochrome("isrc:$isrc", lookup), query)
            if (exact != null) return buildMonochromeStream(exact)
        }

        // 1b. Text search: main artist + title, then the title alone.
        for (term in buildSearchQueries(query)) {
            val best = SpotiFLACMatcher.selectBestCandidate(searchMonochrome(term, lookup), query)
            if (best != null) return buildMonochromeStream(best)
        }
        return null
    }

    private suspend fun searchMonochrome(term: String, lookup: Lookup): List<SpotiFLACCandidate> {
        val encoded = urlEncode(term).takeIf { it.isNotEmpty() } ?: return emptyList()
        val url = "${monochromeBaseUrl.trimEnd('/')}/search/tracks?q=$encoded&limit=$SEARCH_LIMIT"
        val body = fetchText(url, hostKey = monochromeBaseUrl, lookup = lookup) ?: return emptyList()
        return SpotiFLACParsing.parseMonochromeSearchTracks(body)
    }

    private fun buildMonochromeStream(candidate: SpotiFLACCandidate): SpotiFLACResolvedStream? {
        val streamUrl = SpotiFLACParsing.monochromeTrackStreamUrl(monochromeBaseUrl, candidate.trackId)
        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(streamUrl, TRUSTED_AUDIO_HOST_SUFFIXES)) {
            return null
        }
        return SpotiFLACResolvedStream(
            url = streamUrl,
            mimeType = "audio/flac",
            provider = SpotiFLACProvider.MONOCHROME_TRACKS,
            providerTrackId = candidate.trackId,
            isrc = candidate.isrc,
            quality = candidate.quality ?: "LOSSLESS",
            bitDepth = 16,
            sampleRateHz = 44_100,
            durationMs = candidate.durationMs.takeIf { it > 0L },
            userAgent = SpotiFLACResolvedStream.DEFAULT_USER_AGENT
        )
    }

    // -------------------------------------------------------------------------
    // Step 2: Tidal HiFi / OpenAPI Instances
    // -------------------------------------------------------------------------

    private suspend fun resolveViaHiFiInstances(query: SpotiFLACTrackQuery, lookup: Lookup): SpotiFLACResolvedStream? {
        val normalizedIsrc = SpotiFLACMatcher.normalizeIsrc(query.isrc)

        for (baseUrl in hifiBaseUrls) {
            currentCoroutineContext().ensureActive()
            val trimmedBase = baseUrl.trimEnd('/')
            val candidates = ArrayList<SpotiFLACCandidate>()

            // 2a. Search by ISRC first if available.
            if (normalizedIsrc != null) {
                fetchText("$trimmedBase/search/?i=$normalizedIsrc", hostKey = baseUrl, lookup = lookup)
                    ?.let { candidates.addAll(SpotiFLACParsing.parseHiFiSearchResponse(it)) }
            }

            // 2b. Text search on the same instance until something is acceptable.
            if (SpotiFLACMatcher.selectBestCandidate(candidates, query) == null) {
                for (term in buildSearchQueries(query)) {
                    val body = fetchText("$trimmedBase/search/?s=${urlEncode(term)}", hostKey = baseUrl, lookup = lookup)
                        ?: continue
                    candidates.addAll(SpotiFLACParsing.parseHiFiSearchResponse(body))
                    if (SpotiFLACMatcher.selectBestCandidate(candidates, query) != null) break
                }
            }

            val chosen = SpotiFLACMatcher.selectBestCandidate(candidates, query) ?: continue

            // 2c. Resolve `/track/?id=<id>&quality=LOSSLESS`
            val trackBody = fetchText(
                "$trimmedBase/track/?id=${urlEncode(chosen.trackId)}&quality=LOSSLESS",
                hostKey = baseUrl,
                lookup = lookup
            ) ?: continue
            val stream = SpotiFLACParsing.parseHiFiStreamResponse(
                jsonText = trackBody,
                fallbackTrackId = chosen.trackId,
                isrc = chosen.isrc ?: normalizedIsrc
            ) ?: continue

            if (!CloudStreamSecurity.isSafeRemoteStreamUrl(stream.url, TRUSTED_AUDIO_HOST_SUFFIXES)) {
                continue
            }

            return stream.copy(
                durationMs = stream.durationMs ?: chosen.durationMs.takeIf { it > 0L }
            )
        }

        return null
    }

    // -------------------------------------------------------------------------
    // Step 3: Deezer metadata enrichment
    // -------------------------------------------------------------------------

    /** Fills a missing title, artist or duration from Deezer when the ISRC is known (as on iOS). */
    private suspend fun enrichQueryIfNeeded(query: SpotiFLACTrackQuery, lookup: Lookup): SpotiFLACTrackQuery {
        val isrc = SpotiFLACMatcher.normalizeIsrc(query.isrc) ?: return query
        if (query.title.isNotBlank() && query.artist.isNotBlank() && query.durationMs > 0L) return query

        val body = fetchText(
            "${deezerBaseUrl.trimEnd('/')}/2.0/track/isrc:$isrc",
            hostKey = deezerBaseUrl,
            lookup = lookup
        ) ?: return query
        val track = SpotiFLACParsing.parseDeezerTrack(body) ?: return query
        return query.copy(
            title = query.title.ifBlank { track.title },
            artist = query.artist.ifBlank { track.artist },
            album = query.album ?: track.album,
            durationMs = if (query.durationMs > 0L) query.durationMs else track.durationMs,
            isrc = isrc
        )
    }

    // -------------------------------------------------------------------------
    // Helpers & HTTP
    // -------------------------------------------------------------------------

    /** `<main artist> <title>`, then the title alone (same as iOS). */
    private fun buildSearchQueries(query: SpotiFLACTrackQuery): List<String> {
        val title = query.title.trim()
        if (title.isEmpty()) return emptyList()
        val mainArtist = query.artist.split(',', ';', '&', '/').firstOrNull()?.trim().orEmpty()
        val list = ArrayList<String>(2)
        if (mainArtist.isNotEmpty()) list.add("$mainArtist $title")
        list.add(title)
        return list
    }

    /**
     * One GET. Cancellation propagates (and cancels the call); every other failure is a `null`
     * after noting it on [lookup], and network trouble, 429 and 5xx cool the host down for a minute.
     */
    private suspend fun fetchText(url: String, hostKey: String, lookup: Lookup): String? {
        if (!isHostAvailable(hostKey)) {
            lookup.hadErrors = true
            return null
        }
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", SpotiFLACResolvedStream.DEFAULT_USER_AGENT)
                .build()
        } catch (e: IllegalArgumentException) {
            return null
        }

        return try {
            httpClient.newCall(request).awaitYouTubeResponse().use { response ->
                val code = response.code
                when {
                    code == 429 || code >= 500 -> {
                        markHostCooldown(hostKey)
                        lookup.hadErrors = true
                        null
                    }
                    code !in 200..299 -> null
                    else -> response.body.string().takeIf { it.isNotBlank() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A call cancelled with its coroutine also surfaces as an IOException: that is not the host's fault.
            currentCoroutineContext().ensureActive()
            Timber.d("SpotiFLAC: %s unreachable (%s)", hostKey, e.javaClass.simpleName)
            markHostCooldown(hostKey)
            lookup.hadErrors = true
            null
        }
    }

    private fun isHostAvailable(hostKey: String): Boolean {
        val until = hostCooldownUntil[hostKey] ?: return true
        return System.currentTimeMillis() >= until
    }

    private fun markHostCooldown(hostKey: String) {
        hostCooldownUntil[hostKey] = System.currentTimeMillis() + INSTANCE_COOLDOWN_MS
    }

    private fun storePositive(cacheKey: String, stream: SpotiFLACResolvedStream) {
        if (resolvedCache.size >= 256) {
            val now = System.currentTimeMillis()
            resolvedCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (resolvedCache.size >= 256) resolvedCache.clear()
        }
        resolvedCache[cacheKey] = CachedEntry(
            stream = stream,
            expiresAtMs = System.currentTimeMillis() + POSITIVE_CACHE_TTL_MS
        )
        negativeCache.remove(cacheKey)
    }

    private fun cacheKeyFor(query: SpotiFLACTrackQuery): String {
        val trimmedId = query.spotifyId.trim()
        if (trimmedId.isNotEmpty()) return trimmedId
        val isrc = SpotiFLACMatcher.normalizeIsrc(query.isrc)
        if (isrc != null) return "isrc:$isrc"
        return "${SpotiFLACMatcher.cleanString(query.artist)}|${SpotiFLACMatcher.cleanString(query.title)}"
    }

    private fun urlEncode(value: String): String =
        runCatching { URLEncoder.encode(value, Charsets.UTF_8.name()) }.getOrDefault("")

    companion object {
        const val DEFAULT_MONOCHROME_BASE_URL = "https://tracks.monochrome.st"

        /**
         * Tidal HiFi API instances tried after Monochrome. Empty: the four this shipped with were all gone
         * on 2026-11-11 (`api.monochrome.tf` and `tidal.kinoplus.online` no longer resolve; `wolf.qqdl.site`
         * and `maus.qqdl.site` are parked domains). Add a live instance here and its host to
         * [TRUSTED_AUDIO_HOST_SUFFIXES] (Tidal's own CDN, `tidal.com`, already is).
         */
        val DEFAULT_HIFI_BASE_URLS: List<String> = emptyList()

        const val DEFAULT_DEEZER_BASE_URL = "https://api.deezer.com"

        /**
         * Host suffixes allowed by [CloudStreamSecurity.isSafeRemoteStreamUrl] for SpotiFLAC FLAC streams:
         * only the hosts a resolver can actually return (an unused or lapsed domain here would let whoever
         * owns it feed audio through the local proxy).
         */
        val TRUSTED_AUDIO_HOST_SUFFIXES: Set<String> = setOf(
            "monochrome.st",
            "tidal.com"
        )

        private const val SEARCH_LIMIT = 6
        private const val POSITIVE_CACHE_TTL_MS = 30L * 60L * 1_000L // 30 minutes
        private const val NEGATIVE_CACHE_TTL_MS = 10L * 60L * 1_000L // 10 minutes (as on iOS)
        private const val INSTANCE_COOLDOWN_MS = 60L * 1_000L // 60 seconds
    }
}
