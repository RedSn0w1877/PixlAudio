package com.theveloper.pixelplay.data.diagnostics

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Records failed HTTP calls in the diagnostics log: method, host, path (no query string, no
 * headers, no body) and the status code or exception class. Successful calls (2xx/3xx) are not
 * logged, so the log stays small and only shows what went wrong, such as a 429 rate limit or a 401
 * from Spotify during an import. Add it as an application interceptor.
 */
object DiagnosticsHttpInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        try {
            val response = chain.proceed(request)
            if (response.code >= 400) {
                val retryAfter = response.header("Retry-After")?.takeIf { it.length <= 6 && it.all(Char::isDigit) }
                DiagnosticsLog.event(
                    "Http",
                    "${request.method} ${describe(request.url)} -> ${response.code}" +
                        (retryAfter?.let { " retryAfter=${it}s" } ?: "") +
                        " ${elapsedMs(startedAt)}ms"
                )
            }
            return response
        } catch (e: IOException) {
            DiagnosticsLog.event(
                "Http",
                "${request.method} ${describe(request.url)} failed: ${e.javaClass.simpleName} ${elapsedMs(startedAt)}ms"
            )
            throw e
        }
    }

    private fun describe(url: HttpUrl): String = url.host + url.encodedPath

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L
}
