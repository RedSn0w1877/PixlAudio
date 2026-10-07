package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Reads a bounded body on OkHttp's thread; cancellation closes the underlying socket. */
internal suspend fun OkHttpClient.lyricsJson(url: HttpUrl): JsonObject? = withContext(Dispatchers.IO) {
    val call = newCall(Request.Builder().url(url).header("User-Agent", "PixelPlay/0.7.6 (lyrics lookup)").build())
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful || it.body.contentLength() > 1_048_576) null else {
                            val output = ByteArrayOutputStream()
                            it.body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 1_048_576) throw IOException("Lyrics response too large")
                                    output.write(buffer, 0, count)
                                }
                            }
                            val raw = output.toString("UTF-8")
                            if (!com.theveloper.pixelplay.data.model.LyricsDocCodec.isBoundedJson(raw)) null
                            else JsonParser.parseString(raw).asJsonObject
                        }
                    }
                    continuation.resume(result) { _, _, _ -> }
                } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            }
        })
    }
}

/** Status, redirect target and (for 2xx only) a size-capped UTF-8 body. */
internal data class BoundedResponse(val code: Int, val body: String?, val location: String?, val retryAfter: String?)

/**
 * One GET with no implicit redirect handling by the caller's choice of client: the response
 * is reported as-is so the caller can vet a `Location` before following it. Bodies over
 * [maxBytes] fail with an [IOException]; cancellation cancels the call and closes the socket.
 */
internal suspend fun OkHttpClient.boundedGet(url: HttpUrl, maxBytes: Int, accept: String): BoundedResponse =
    withContext(Dispatchers.IO) {
        val call = newCall(Request.Builder().url(url).header("Accept", accept).get().build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val body = if (!it.isSuccessful) null else {
                                if (it.body.contentLength() > maxBytes) throw IOException("Lyrics response too large")
                                val output = ByteArrayOutputStream()
                                it.body.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > maxBytes) throw IOException("Lyrics response too large")
                                        output.write(buffer, 0, count)
                                    }
                                }
                                output.toString("UTF-8")
                            }
                            BoundedResponse(it.code, body, it.header("Location"), it.header("Retry-After"))
                        }
                        continuation.resume(result) { _, _, _ -> }
                    } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                }
            })
        }
    }
