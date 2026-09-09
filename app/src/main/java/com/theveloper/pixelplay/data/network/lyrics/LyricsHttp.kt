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
