package com.theveloper.pixelplay.data.cloudstudio

import java.io.File

// The network seams of Cloud Studio. [CloudHttp] carries the small requests (RunPod's JSON API and the bucket's
// HEAD/GET/PUT/DELETE/List of KB-sized objects); [CloudTransfers] streams the big files (the song upload and the
// instrumental download) from and to disk. The live implementations are OkHttp ([OkHttpCloudHttp]); unit tests use
// fakes, so no test ever touches the network.

/** One small request. Headers are sent as given; the body is the whole payload. */
class CloudHttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
) {
    fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** No URL query (a presigned URL's signature) and no header value (the RunPod key) ever reaches a log line. */
    override fun toString(): String = "CloudHttpRequest($method ${CloudRedaction.hostAndPath(url)})"
}

/** One response: status, headers (names lower-cased) and the whole body. */
class CloudHttpResponse(
    val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    private val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]

    val text: String get() = body.toString(Charsets.UTF_8)

    val isSuccessful: Boolean get() = statusCode in 200..299
}

/** Sends small requests. Throws [java.io.IOException] (or another exception) when no response arrived. */
fun interface CloudHttp {
    suspend fun send(request: CloudHttpRequest): CloudHttpResponse
}

/** What became of a streamed transfer. */
sealed interface CloudTransferResult {
    data object Ok : CloudTransferResult

    /** [status] is the HTTP status when the server answered, null when the connection failed. */
    data class Failed(val message: String, val status: Int? = null) : CloudTransferResult
}

/** Streams the big files. [onProgress] gets 0…1 (throttled by the implementation). */
interface CloudTransfers {
    suspend fun upload(file: File, url: String, contentType: String, onProgress: (Float) -> Unit): CloudTransferResult

    /** Writes the body to [destination] (replacing it); a failed or non-2xx download leaves no file behind. */
    suspend fun download(url: String, destination: File, onProgress: (Float) -> Unit): CloudTransferResult
}

/** Removes presigned-URL queries and bearer keys from text that may be logged or shown. */
object CloudRedaction {
    fun redact(text: String): String {
        var out = text
        if ("X-Amz-" in out) {
            val builder = StringBuilder()
            var rest = out
            // Every `X-Amz-…` parameter up to the end of its URL (whitespace, quote or closing bracket) becomes "…".
            while (true) {
                val marker = rest.indexOf("X-Amz-")
                if (marker < 0) break
                builder.append(rest, 0, marker).append('…')
                val tail = rest.substring(marker + "X-Amz-".length)
                val end = tail.indexOfFirst { it == ' ' || it == '"' || it == '\'' || it == ')' || it == '\n' || it == '>' }
                rest = if (end < 0) "" else tail.substring(end)
            }
            out = builder.append(rest).toString()
        }
        if ("Bearer " in out) out = out.replace(Regex("Bearer\\s+\\S+"), "Bearer …")
        return out
    }

    /** `host/path` of a URL, without its query. */
    fun hostAndPath(url: String): String = url.substringAfter("://").substringBefore('?')
}
