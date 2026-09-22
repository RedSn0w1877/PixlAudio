package com.theveloper.pixelplay.data.remix.cloud

import android.util.Base64
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

@Serializable
private data class ServerlessSubmit(val id: String = "", val status: String = "")

@Serializable
private data class ServerlessStatus(
    val id: String = "",
    val status: String = "",
    val output: ServerlessOutput? = null,
    val error: String? = null,
)

@Serializable
private data class ServerlessOutput(
    val stems: Map<String, String> = emptyMap(),
    val error: String? = null,
    val seconds: Float = 0f,
)

/**
 * Separation on a Runpod **serverless** endpoint — the on-demand version.
 *
 * The endpoint keeps zero workers while nothing is happening, so it costs nothing to leave
 * configured. A job wakes a worker, bills per second, and the worker shuts down again.
 *
 * Two consequences shape this client:
 *
 * 1. **Only the loop region is uploaded**, as a 16-bit WAV, not the whole track. Demucs costs
 *    roughly in proportion to input length, and Runpod caps a `/run` payload near 10 MB — a
 *    30-second region is about 7 MB once base64-encoded, a whole song would be neither cheap nor
 *    small enough.
 * 2. **Stems come back inline** as base64 in the job output, because a serverless worker has no
 *    persistent address to download from afterwards.
 *
 * The flow is Runpod's own: `POST /run` → poll `GET /status/{id}` → read `output`. That is the
 * same submit-then-poll shape as [RemixStemJobClient], so a dropped connection never costs the
 * job.
 */
@Singleton
class RemixServerlessStemClient @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun separate(
        config: RemixBackendConfig,
        regionWav: File,
        startMs: Int,
        durationMs: Int,
        outputDirectory: File,
        outputPrefix: String,
        onProgress: (Float) -> Unit,
    ): StemJobOutcome {
        val base = config.baseUrl.trimEnd('/')
        if (base.isBlank()) return StemJobOutcome.Failed("No separation endpoint configured.")
        if (!regionWav.exists()) return StemJobOutcome.Failed("Couldn't read the track's audio.")

        val payload = buildJsonObject {
            put("input", buildJsonObject {
                put("audio_base64", JsonPrimitive(Base64.encodeToString(regionWav.readBytes(), Base64.NO_WRAP)))
                put("filename", JsonPrimitive(regionWav.name))
                put("start_ms", JsonPrimitive(0)) // the upload is already the region
                put("duration_ms", JsonPrimitive(durationMs))
            })
        }

        val jobId = runCatching { submit(base, config.token, payload) }
            .getOrElse { return StemJobOutcome.Failed(describe(it)) }
        if (jobId.isBlank()) return StemJobOutcome.Failed("The endpoint returned no job id.")

        onProgress(0.1f)
        var polls = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            polls++
            val status = runCatching { status(base, config.token, jobId) }
                .getOrElse {
                    Timber.d(it, "Remix serverless: poll failed, retrying")
                    continue
                }

            // Serverless reports no percentage, so show movement based on the usual shape of a
            // job: a cold start, then a separation that takes tens of seconds.
            onProgress((0.1f + polls * 0.05f).coerceAtMost(0.9f))

            when (status.status.uppercase()) {
                "COMPLETED" -> {
                    val output = status.output
                        ?: return StemJobOutcome.Failed("The endpoint returned no output.")
                    output.error?.let { return StemJobOutcome.Failed(it) }
                    val files = runCatching { write(output, outputDirectory, outputPrefix) }
                        .getOrElse { return StemJobOutcome.Failed("Couldn't save the separated parts.") }
                    return if (files.size == RemixStemJobClient.EXPECTED_STEMS.size) {
                        onProgress(1f)
                        StemJobOutcome.Completed(files)
                    } else {
                        StemJobOutcome.Failed("The endpoint returned ${files.size} of 4 parts.")
                    }
                }
                "FAILED" -> return StemJobOutcome.Failed(status.error ?: "Separation failed on the endpoint.")
                "CANCELLED", "TIMED_OUT" -> return StemJobOutcome.Cancelled
                else -> Unit // IN_QUEUE / IN_PROGRESS
            }
        }
    }

    private fun submit(base: String, token: String, payload: JsonObject): String {
        val request = Request.Builder()
            .url("$base/run")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = checkNotNull(response.body).string()
            return json.decodeFromString(ServerlessSubmit.serializer(), text).id
        }
    }

    private fun status(base: String, token: String, jobId: String): ServerlessStatus {
        val request = Request.Builder()
            .url("$base/status/$jobId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = checkNotNull(response.body).string()
            return json.decodeFromString(ServerlessStatus.serializer(), text)
        }
    }

    private fun write(output: ServerlessOutput, directory: File, prefix: String): Map<String, File> {
        directory.mkdirs()
        val result = LinkedHashMap<String, File>()
        for (kind in RemixStemJobClient.EXPECTED_STEMS) {
            val encoded = output.stems[kind] ?: continue
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.size < 44) continue
            val partial = File(directory, "$prefix$kind.wav.part")
            partial.writeBytes(bytes)
            val target = File(directory, "$prefix$kind.wav")
            if (target.exists()) target.delete()
            if (partial.renameTo(target)) result[kind] = target else partial.delete()
        }
        return result
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpStatusException -> when (error.code) {
            401, 403 -> "Runpod rejected the API key."
            404 -> "That endpoint id doesn't exist."
            413 -> "The clip is too large to send — try a shorter loop."
            else -> "The endpoint answered ${error.code}."
        }
        else -> "Couldn't reach the endpoint."
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    companion object {
        /** Any address on Runpod's serverless API uses this protocol rather than the pod one. */
        fun handles(baseUrl: String): Boolean = baseUrl.contains("api.runpod.ai/v2", ignoreCase = true)

        private const val POLL_INTERVAL_MS = 3_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
