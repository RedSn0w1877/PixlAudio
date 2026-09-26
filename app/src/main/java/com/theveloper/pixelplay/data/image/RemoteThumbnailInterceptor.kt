package com.theveloper.pixelplay.data.image

import android.net.Uri
import android.util.LruCache
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a thumbnail-sized variant of remote artwork for CDNs that encode the image size in the
 * URL, instead of the 640–1000 px original, when the request is displayed small.
 *
 * Opt-in per request ([optIn], set by `SmartImage` for its own display requests), so palette
 * extraction, the MediaSession artwork and the full player never change source. The rewritten URL
 * is what Coil keys its memory and disk caches on, so a small row and the large player each get
 * the variant they asked for. Spotify's `i.scdn.co` hashes are never touched: their size is not in
 * the URL in a form that can be derived safely.
 *
 * The rewrite never costs an image that used to show: artwork already in the disk cache under its
 * original URL (cached before this interceptor existed, or by a full-size load) keeps loading from
 * there, and a variant that fails (offline, or a size the CDN refuses) falls back to the original
 * request. The disk check runs once per URL per process on IO; the decision is remembered, so a
 * memory-cache hit on a later bind stays synchronous.
 */
class RemoteThumbnailInterceptor(
    private val diskCache: () -> DiskCache? = { null },
) : Interceptor {

    /** Original URL -> true when it must load as-is (already on disk, or its variant failed). */
    private val useOriginal = LruCache<String, Boolean>(DECISION_CACHE_SIZE)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.parameters.value<Boolean>(PARAM_KEY) != true) return chain.proceed(request)
        val url = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed(request)
        }
        val targetPx = chain.size.maxPixels() ?: return chain.proceed(request)
        val variant = RemoteThumbnailUrls.variantFor(url, targetPx) ?: return chain.proceed(request)
        if (request.diskCachePolicy.readEnabled) {
            val keepOriginal = useOriginal.get(url) ?: isOnDisk(request.diskCacheKey ?: url).also {
                useOriginal.put(url, it)
            }
            if (keepOriginal) return chain.proceed(request)
        }
        val result = chain.proceed(request.newBuilder().data(variant).build())
        if (result !is ErrorResult) return result
        useOriginal.put(url, true)
        return chain.proceed(request)
    }

    private suspend fun isOnDisk(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { diskCache()?.openSnapshot(key)?.use { true } ?: false }.getOrDefault(false)
    }

    private fun Size.maxPixels(): Int? {
        val w = (width as? Dimension.Pixels)?.px ?: return null
        val h = (height as? Dimension.Pixels)?.px ?: return null
        return maxOf(w, h).takeIf { it > 0 }
    }

    companion object {
        private const val PARAM_KEY = "pixelplay.remoteThumbnailVariant"
        private const val DECISION_CACHE_SIZE = 2048

        /** Marks a display request as allowed to load a smaller CDN variant (no cache-key effect). */
        fun optIn(builder: ImageRequest.Builder): ImageRequest.Builder =
            builder.setParameter(PARAM_KEY, true, memoryCacheKey = null)
    }
}

/** Pure URL rewriting for [RemoteThumbnailInterceptor]; never returns a larger variant. */
object RemoteThumbnailUrls {

    /** Sizes Deezer's API itself hands out (picture_small / medium / big / xl). */
    private val DeezerSizes = intArrayOf(56, 250, 500, 1000)
    private val DeezerSizeRegex = Regex("/(\\d{2,4})x(\\d{2,4})([\\-.])")

    /** Buckets for googleusercontent `=wN-hN` / `=sN`, so nearby display sizes share a cache entry. */
    private val GoogleSizes = intArrayOf(64, 128, 192, 256, 384, 512, 768, 1024)
    private val GoogleWidthHeightRegex = Regex("=w(\\d+)-h(\\d+)(-[^/?#]*)?$")
    private val GoogleSideRegex = Regex("=s(\\d+)(-[^/?#]*)?$")

    /**
     * The URL of the smallest known variant at least [targetPx] on its longest side, or null when
     * [url] is not a supported CDN URL or is already no larger than that.
     */
    fun variantFor(url: String, targetPx: Int): String? {
        if (targetPx <= 0) return null
        val host = hostOf(url) ?: return null
        return when {
            host.endsWith("dzcdn.net") -> deezer(url, targetPx)
            host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com") -> google(url, targetPx)
            else -> null
        }
    }

    private fun deezer(url: String, targetPx: Int): String? {
        if (!url.contains("/images/")) return null
        val match = DeezerSizeRegex.find(url) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        if (w != h) return null
        val size = DeezerSizes.firstOrNull { it >= targetPx } ?: return null
        if (size >= w) return null
        return url.replaceRange(match.range, "/${size}x${size}${match.groupValues[3]}")
    }

    private fun google(url: String, targetPx: Int): String? {
        val size = GoogleSizes.firstOrNull { it >= targetPx } ?: return null
        GoogleWidthHeightRegex.find(url)?.let { match ->
            val w = match.groupValues[1].toIntOrNull() ?: return null
            val h = match.groupValues[2].toIntOrNull() ?: return null
            if (w != h || size >= w) return null
            return url.replaceRange(match.range, "=w$size-h$size${match.groupValues[3]}")
        }
        GoogleSideRegex.find(url)?.let { match ->
            val s = match.groupValues[1].toIntOrNull() ?: return null
            if (size >= s) return null
            return url.replaceRange(match.range, "=s$size${match.groupValues[2]}")
        }
        return null
    }

    private fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true)) return null
        val start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i
                break
            }
        }
        return url.substring(start, end).lowercase().takeIf { it.isNotEmpty() }
    }
}
