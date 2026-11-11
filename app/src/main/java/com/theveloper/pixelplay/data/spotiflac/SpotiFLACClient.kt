package com.theveloper.pixelplay.data.spotiflac

import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.di.YouTubeOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-provider SpotiFLAC lossless stream resolver.
 *
 * Resolution fallback chain:
 * 1. **Monochrome Track Streamer (`tracks.monochrome.st`)** — searches `/search/tracks?q=...`,
 *    loads `/releases/:id` to verify exact ISRC + duration, and streams raw `.flac` audio via
 *    `/track/:trackId` (supports HTTP byte-range `206 Partial Content`).
 * 2. **Tidal HiFi / OpenAPI Instances (`api.monochrome.tf`, `tidal.kinoplus.online`, etc.)** —
 *    searches by ISRC (`/search/?i=<ISRC>`) or text (`/search/?s=<query>`) and resolves
 *    `/track/?id=<id>&quality=LOSSLESS` (both `OriginalTrackUrl` and base64 `manifest` JSON).
 * 3. **Deezer Public API (`api.deezer.com`)** — enriches missing ISRC or duration before querying
 *    Monochrome/Tidal when a track lacks an ISRC or needs disambiguation.
 */
@Singleton
class SpotiFLACClient @Inject constructor(
    @YouTubeOkHttpClient sharedOkHttpClient: OkHttpClient
) {

    private val probeOkHttpClient: OkHttpClient = sharedOkHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class CachedEntry(
        val stream: SpotiFLACResolvedStream,
        val expiresAtMs: Long
    )

    private val resolvedCache = ConcurrentHashMap<String, CachedEntry>()
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val releaseCache = ConcurrentHashMap<String, List<SpotiFLACCandidate>>()
    private val hostCooldownUntil = ConcurrentHashMap<String, Long>()

    var monochromeBaseUrl: String = DEFAULT_MONOCHROME_BASE_URL
    var hifiBaseUrls: List<String> = DEFAULT_HIFI_BASE_URLS
    var deezerBaseUrl: String = DEFAULT_DEEZER_BASE_URL

    /**
     * Resolves a lossless FLAC stream for [query], or returns `null` if no lossless provider
     * could match and resolve the track (allowing the caller to fall back to YouTube).
     */
    suspend fun resolve(query: SpotiFLACTrackQuery): SpotiFLACResolvedStream? = withContext(Dispatchers.IO) {
        val cacheKey = cacheKeyFor(query)
        val now = System.currentTimeMillis()

        resolvedCache[cacheKey]?.let { hit ->
            if (now < hit.expiresAtMs) return@withContext hit.stream
        }
        negativeCache[cacheKey]?.let { missUntil ->
            if (now < missUntil) return@withContext null
        }

        // Enrich missing ISRC / duration from Deezer if needed.
        val enrichedQuery = enrichQueryIfNeeded(query)

        // 1. Primary: Monochrome Track Streamer (`tracks.monochrome.st`)
        val monochromeStream = runCatching { resolveViaMonochrome(enrichedQuery) }
            .onFailure { Timber.d("SpotiFLACClient: Monochrome failed for ${query.spotifyId}: ${it.message}") }
            .getOrNull()
        if (monochromeStream != null) {
            storePositive(cacheKey, monochromeStream)
            return@withContext monochromeStream
        }

        // 2. Secondary: Tidal HiFi / OpenAPI instances
        val hifiStream = runCatching { resolveViaHiFiInstances(enrichedQuery) }
            .onFailure { Timber.d("SpotiFLACClient: HiFi instances failed for ${query.spotifyId}: ${it.message}") }
            .getOrNull()
        if (hifiStream != null) {
            storePositive(cacheKey, hifiStream)
            return@withContext hifiStream
        }

        negativeCache[cacheKey] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
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

    private suspend fun resolveViaMonochrome(query: SpotiFLACTrackQuery): SpotiFLACResolvedStream? {
        if (!isHostAvailable(monochromeBaseUrl)) return null

        val searchTerms = buildSearchQueries(query)
        var searchHits = emptyList<SpotiFLACCandidate>()

        for (term in searchTerms) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val encoded = urlEncode(term)
            val url = "${monochromeBaseUrl.trimEnd('/')}/search/tracks?q=$encoded&limit=6"
            val body = fetchText(url, hostKey = monochromeBaseUrl) ?: continue
            val parsed = SpotiFLACParsing.parseMonochromeSearchTracks(body)
            if (parsed.isNotEmpty()) {
                searchHits = parsed
                break
            }
        }

        if (searchHits.isEmpty()) return null

        val directBest = SpotiFLACMatcher.selectBestCandidate(searchHits, query)
        val normalizedQueryIsrc = SpotiFLACMatcher.normalizeIsrc(query.isrc)

        if (normalizedQueryIsrc != null || (directBest != null && query.durationMs > 0 && directBest.durationMs == 0L)) {
            val inspectedReleaseIds = HashSet<String>()
            val releaseCandidates = ArrayList<SpotiFLACCandidate>()

            for (hit in searchHits.take(3)) {
                val relId = hit.releaseId?.takeIf { it.isNotEmpty() } ?: continue
                if (!inspectedReleaseIds.add(relId)) continue

                val tracks = fetchMonochromeReleaseTracks(relId)
                releaseCandidates.addAll(tracks)

                if (normalizedQueryIsrc != null) {
                    val exact = tracks.firstOrNull {
                        SpotiFLACMatcher.normalizeIsrc(it.isrc) == normalizedQueryIsrc && it.playable
                    }
                    if (exact != null) {
                        return buildMonochromeStream(exact)
                    }
                }
            }

            SpotiFLACMatcher.selectBestCandidate(releaseCandidates, query)?.let { bestFromRelease ->
                return buildMonochromeStream(bestFromRelease)
            }
        }

        val chosen = directBest ?: return null
        return buildMonochromeStream(chosen)
    }

    private fun fetchMonochromeReleaseTracks(releaseId: String): List<SpotiFLACCandidate> {
        releaseCache[releaseId]?.let { return it }
        val url = "${monochromeBaseUrl.trimEnd('/')}/releases/$releaseId"
        val body = fetchText(url, hostKey = monochromeBaseUrl) ?: return emptyList()
        val parsed = SpotiFLACParsing.parseMonochromeReleaseTracks(body)
        if (parsed.isNotEmpty()) {
            if (releaseCache.size >= 128) releaseCache.clear()
            releaseCache[releaseId] = parsed
        }
        return parsed
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

    private suspend fun resolveViaHiFiInstances(query: SpotiFLACTrackQuery): SpotiFLACResolvedStream? {
        val normalizedIsrc = SpotiFLACMatcher.normalizeIsrc(query.isrc)
        val searchQuery = "${query.artist} ${query.title}".trim()
        val encodedSearch = urlEncode(searchQuery)

        for (baseUrl in hifiBaseUrls) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!isHostAvailable(baseUrl)) continue
            val trimmedBase = baseUrl.trimEnd('/')

            var matchedCandidate: SpotiFLACCandidate? = null

            // 2a. Search by ISRC first if available.
            if (normalizedIsrc != null) {
                val isrcUrl = "$trimmedBase/search/?i=$normalizedIsrc"
                val body = fetchText(isrcUrl, hostKey = baseUrl)
                if (body != null) {
                    val candidates = SpotiFLACParsing.parseHiFiSearchResponse(body)
                    matchedCandidate = SpotiFLACMatcher.selectBestCandidate(candidates, query)
                }
            }

            // 2b. Fallback to text search on the same instance.
            if (matchedCandidate == null && encodedSearch.isNotEmpty()) {
                val textUrl = "$trimmedBase/search/?s=$encodedSearch"
                val body = fetchText(textUrl, hostKey = baseUrl)
                if (body != null) {
                    val candidates = SpotiFLACParsing.parseHiFiSearchResponse(body)
                    matchedCandidate = SpotiFLACMatcher.selectBestCandidate(candidates, query)
                }
            }

            val chosen = matchedCandidate ?: continue

            // 2c. Resolve `/track/?id=<id>&quality=LOSSLESS`
            val trackUrl = "$trimmedBase/track/?id=${chosen.trackId}&quality=LOSSLESS"
            val trackBody = fetchText(trackUrl, hostKey = baseUrl) ?: continue
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
    // Step 3: Deezer ISRC / Duration Enrichment
    // -------------------------------------------------------------------------

    private suspend fun enrichQueryIfNeeded(query: SpotiFLACTrackQuery): SpotiFLACTrackQuery {
        val normalizedIsrc = SpotiFLACMatcher.normalizeIsrc(query.isrc)
        if (normalizedIsrc != null && query.durationMs > 0L) {
            return query
        }
        if (!isHostAvailable(deezerBaseUrl)) return query
        val trimmedBase = deezerBaseUrl.trimEnd('/')

        if (normalizedIsrc != null) {
            val url = "$trimmedBase/2.0/track/isrc:$normalizedIsrc"
            val body = fetchText(url, hostKey = deezerBaseUrl)
            if (body != null) {
                val track = SpotiFLACParsing.parseDeezerTrack(body)
                if (track != null && track.durationMs > 0L) {
                    return query.copy(durationMs = track.durationMs, isrc = track.isrc ?: normalizedIsrc)
                }
            }
            return query
        }

        val searchTerm = "${query.artist} ${query.title}".trim()
        val encoded = urlEncode(searchTerm)
        if (encoded.isEmpty()) return query

        val searchUrl = "$trimmedBase/2.0/search?q=$encoded&limit=5"
        val body = fetchText(searchUrl, hostKey = deezerBaseUrl) ?: return query
        val candidates = SpotiFLACParsing.parseDeezerSearchTracks(body)
        val best = SpotiFLACMatcher.selectBestCandidate(candidates, query) ?: return query

        // If search candidate lacks ISRC, fetch `/2.0/track/<id>` to obtain the exact ISRC.
        var enrichedIsrc = best.isrc
        if (enrichedIsrc == null) {
            val detailUrl = "$trimmedBase/2.0/track/${best.trackId}"
            val detailBody = fetchText(detailUrl, hostKey = deezerBaseUrl)
            if (detailBody != null) {
                enrichedIsrc = SpotiFLACParsing.parseDeezerTrack(detailBody)?.isrc
            }
        }

        return query.copy(
            durationMs = if (query.durationMs > 0L) query.durationMs else best.durationMs,
            isrc = enrichedIsrc ?: query.isrc
        )
    }

    // -------------------------------------------------------------------------
    // Helpers & HTTP
    // -------------------------------------------------------------------------

    private fun buildSearchQueries(query: SpotiFLACTrackQuery): List<String> {
        val primary = "${query.artist} ${query.title}".trim()
        val cleanedTitle = SpotiFLACMatcher.cleanString(query.title)
        val cleanedArtist = SpotiFLACMatcher.cleanString(query.artist)
        val secondary = "$cleanedArtist $cleanedTitle".trim()
        val list = ArrayList<String>(2)
        if (primary.isNotEmpty()) list.add(primary)
        if (secondary.isNotEmpty() && !secondary.equals(primary, ignoreCase = true)) {
            list.add(secondary)
        }
        return list
    }

    private fun fetchText(url: String, hostKey: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", SpotiFLACResolvedStream.DEFAULT_USER_AGENT)
            .build()

        return try {
            probeOkHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 429 || code in 500..599) {
                    markHostCooldown(hostKey)
                    return null
                }
                if (code !in 200..299) return null
                response.body?.string()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            markHostCooldown(hostKey)
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

        val DEFAULT_HIFI_BASE_URLS = listOf(
            "https://api.monochrome.tf",
            "https://tidal.kinoplus.online",
            "https://wolf.qqdl.site",
            "https://maus.qqdl.site"
        )

        const val DEFAULT_DEEZER_BASE_URL = "https://api.deezer.com"

        /**
         * Host suffixes allowed by [CloudStreamSecurity.isSafeRemoteStreamUrl] for SpotiFLAC FLAC streams.
         */
        val TRUSTED_AUDIO_HOST_SUFFIXES = setOf(
            "monochrome.st",
            "monochrome.tf",
            "tidal.com",
            "wimpmusic.com",
            "kinoplus.online",
            "qqdl.site",
            "dzcdn.net",
            "deezer.com",
            "qobuz.com"
        )

        private const val POSITIVE_CACHE_TTL_MS = 30L * 60L * 1_000L // 30 minutes
        private const val NEGATIVE_CACHE_TTL_MS = 3L * 60L * 1_000L // 3 minutes
        private const val INSTANCE_COOLDOWN_MS = 60L * 1_000L // 60 seconds
    }
}
