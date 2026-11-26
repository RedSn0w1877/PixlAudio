package com.theveloper.pixelplay.data.stream

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What kind of file an upstream stream URL is, from the URL alone (no request). A Cast receiver is
 * told the content type when the queue item is built, before any byte went out, so this is the
 * best guess that costs nothing: googlevideo's own `mime` parameter, else the itag, else what the
 * upstream said, else [DEFAULT].
 */
object StreamContentTypes {
    /** YouTube's AAC stream (itag 140) and most of what Cast is happy with. */
    const val DEFAULT = "audio/mp4"

    fun ofUrl(url: String?, upstreamContentType: String? = null): String {
        val parsed = url?.toHttpUrlOrNull()
        parsed?.queryParameter("mime")?.trim()?.lowercase()?.takeIf(::isMedia)?.let { return it }
        when (parsed?.queryParameter("itag")?.toIntOrNull()) {
            139, 140, 141 -> return "audio/mp4"
            249, 250, 251 -> return "audio/webm"
            18 -> return "video/mp4"
        }
        val upstream = upstreamContentType?.substringBefore(';')?.trim()?.lowercase()
        if (upstream != null && isMedia(upstream)) return upstream
        return DEFAULT
    }

    private fun isMedia(type: String): Boolean = type.startsWith("audio/") || type.startsWith("video/")
}
