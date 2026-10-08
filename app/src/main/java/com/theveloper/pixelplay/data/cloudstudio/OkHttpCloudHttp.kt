package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source

/**
 * [CloudHttp] and [CloudTransfers] over OkHttp, for RunPod and the R2 bucket only.
 *
 * The client is Cloud Studio's own ([CloudStudioModule.provideCloudOkHttpClient]): the app's shared client logs
 * request lines in debug builds, and a presigned URL's query must never reach logcat; neither may the RunPod key
 * (it travels only in the `Authorization` header of RunPod calls, never to the bucket).
 */
class OkHttpCloudHttp(private val client: OkHttpClient) : CloudHttp, CloudTransfers {

    override suspend fun send(request: CloudHttpRequest): CloudHttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val type = request.header("Content-Type")?.toMediaTypeOrNull()
        val body = request.body?.toRequestBody(type)
            ?: if (request.method == "PUT" || request.method == "POST") ByteArray(0).toRequestBody(type) else null
        builder.method(request.method, body)
        val call = client.newCall(builder.build())
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        return call.await { response ->
            val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
            CloudHttpResponse(response.code, headers, if (request.method == "HEAD") ByteArray(0) else response.body.bytes())
        }
    }

    override suspend fun upload(file: File, url: String, contentType: String, onProgress: (Float) -> Unit): CloudTransferResult {
        if (!file.isFile) return CloudTransferResult.Failed("the prepared file is missing")
        val request = Request.Builder().url(url)
            .put(ProgressFileBody(file, contentType.toMediaTypeOrNull(), onProgress))
            .build()
        return try {
            client.newCall(request).await { response ->
                if (response.isSuccessful) CloudTransferResult.Ok
                // The body of an S3 error can quote the request; only the status goes on.
                else CloudTransferResult.Failed("HTTP ${response.code}", response.code)
            }
        } catch (error: IOException) {
            CloudTransferResult.Failed(CloudRedaction.redact(error.message ?: "network error"))
        }
    }

    override suspend fun download(url: String, destination: File, onProgress: (Float) -> Unit): CloudTransferResult {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        val request = Request.Builder().url(url).get().build()
        return try {
            val result = client.newCall(request).await { response ->
                if (!response.isSuccessful) return@await CloudTransferResult.Failed("HTTP ${response.code}", response.code)
                val body = response.body
                val total = body.contentLength()
                var written = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total >= 0 && written != total) {
                    CloudTransferResult.Failed("incomplete download ($written of $total bytes)")
                } else CloudTransferResult.Ok
            }
            if (result == CloudTransferResult.Ok) {
                destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.delete()
                    return CloudTransferResult.Failed("couldn't save the download")
                }
            } else partial.delete()
            result
        } catch (error: IOException) {
            partial.delete()
            CloudTransferResult.Failed(CloudRedaction.redact(error.message ?: "network error"))
        } catch (error: Throwable) {
            // Cancelled (or anything else): no half-written file is left behind.
            partial.delete()
            throw error
        }
    }

    /** Enqueues the call and reads the response on OkHttp's thread; cancelling the coroutine cancels the call. */
    private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error as? IOException ?: IOException(error.message, error))
                    }
                }
            }
        })
    }

    /** Streams the upload from disk (a 100 MB FLAC never sits in memory) and reports whole percents. */
    private class ProgressFileBody(
        private val file: File,
        private val type: MediaType?,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            var lastPercent = -1
            file.source().use { source ->
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, 64L * 1024)
                    if (read < 0) break
                    sink.write(buffer, read)
                    sent += read
                    if (total > 0) {
                        val percent = (sent * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
        }
    }
}
