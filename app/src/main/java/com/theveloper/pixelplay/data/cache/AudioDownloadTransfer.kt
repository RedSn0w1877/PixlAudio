package com.theveloper.pixelplay.data.cache

import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Keep cancellation connected to the HTTP call until the final body byte has been written. */
internal object AudioDownloadTransfer {
    suspend fun fetch(client: OkHttpClient, url: String, pending: File): String? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    pending.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val contentType = response.use {
                            if (response.code != 200) throw IOException("Full audio request returned HTTP ${response.code}")
                            val type = response.header("Content-Type")
                            if (type?.startsWith("text/", ignoreCase = true) == true ||
                                type?.contains("json", ignoreCase = true) == true) {
                                throw IOException("The audio server returned a non-audio response")
                            }
                            val body = response.body
                            val expectedBytes = body.contentLength()
                            var bytesWritten = 0L
                            body.byteStream().use { input ->
                                pending.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("Audio download cancelled")
                                        // Cancelling the continuation calls call.cancel(), which
                                        // interrupts this socket read even after headers arrived.
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        bytesWritten += count
                                    }
                                    output.fd.sync()
                                }
                            }
                            if (bytesWritten == 0L || (expectedBytes >= 0L && expectedBytes != bytesWritten)) {
                                throw IOException("Incomplete audio: $bytesWritten of $expectedBytes bytes")
                            }
                            type
                        }
                        if (continuation.isActive) continuation.resume(contentType) else pending.delete()
                    } catch (error: Exception) {
                        pending.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
}
