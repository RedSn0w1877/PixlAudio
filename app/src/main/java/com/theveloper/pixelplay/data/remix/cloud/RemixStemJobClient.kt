package com.theveloper.pixelplay.data.remix.cloud

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber

@Serializable
data class StemJobSubmitResponse(
    val id: String = "",
    val status: String = "",
)

@Serializable
data class StemJobStatusResponse(
    val id: String = "",
    val status: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val stems: Map<String, String> = emptyMap(),
)

/** Every call returns one of these — never a nullable-or-throw mix. */
sealed interface StemJobOutcome {
    data class Completed(val files: Map<String, File>) : StemJobOutcome
    data class Failed(val message: String) : StemJobOutcome
    data object Cancelled : StemJobOutcome
}

data class RemixBackendConfig(
    val baseUrl: String,
    val token: String,
)

/**
 * Talks to the Demucs separation server running on the GPU pod
 * (`tools/runpod/stem_server.py`).
 *
 * **Job-id polling, not one long POST.** The existing `DirectPostStemApiClient` holds a single
 * request open for the whole render and documents (`:25-33`) that this dies at roughly 5m40s to
 * NAT idle timeouts — a four-minute track on a busy GPU takes longer than that. Submitting, then
 * polling every few seconds with short connections, has no such ceiling and survives the phone
 * changing networks mid-render.
 *
 * Stems are fetched as **separate downloads**. Returning four WAVs base64-encoded in one JSON
 * body would be 150 MB+ of string and would OOM before it parsed.
 *
 * Deliberately a plain `@Singleton class` with uniform suspend return types: the previous attempt
 * at this protocol was abandoned after a KSP/Hilt resolution failure, and mixed `T?`/`T` returns
 * in one class is the shape that triggers it (see the `pixelplay-ksp-mixed-return-bug` note).
 */
@Singleton
class RemixStemJobClient @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    // The shared client times out at 8 s, which is fine for polls but not for uploading a track.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun separate(
        config: RemixBackendConfig,
        source: File,
        outputDirectory: File,
        outputPrefix: String,
        onProgress: (Float) -> Unit,
    ): StemJobOutcome {
        val base = config.baseUrl.trimEnd('/')
        if (base.isBlank()) return StemJobOutcome.Failed("No separation server configured.")
        if (!source.exists()) return StemJobOutcome.Failed("The track's audio file is missing.")

        val submitted = runCatching { submit(base, config.token, source) }
            .getOrElse { return StemJobOutcome.Failed(describe(it)) }
        if (submitted.id.isBlank()) return StemJobOutcome.Failed("The server did not return a job id.")

        var lastProgress = 0f
        while (true) {
            delay(POLL_INTERVAL_MS)
            val status = runCatching { status(base, config.token, submitted.id) }
                .getOrElse {
                    // A dropped poll is normal on mobile; the job keeps running on the pod.
                    Timber.d(it, "Remix separation: poll failed, retrying")
                    continue
                }

            if (status.progress > lastProgress) {
                lastProgress = status.progress
                onProgress(status.progress.coerceIn(0f, 1f))
            }

            when (status.status.uppercase()) {
                "COMPLETED" -> {
                    val files = runCatching {
                        download(base, config.token, status, outputDirectory, outputPrefix)
                    }.getOrElse { return StemJobOutcome.Failed(describe(it)) }
                    return if (files.size == EXPECTED_STEMS.size) {
                        StemJobOutcome.Completed(files)
                    } else {
                        StemJobOutcome.Failed("The server returned ${files.size} of ${EXPECTED_STEMS.size} parts.")
                    }
                }
                "FAILED" -> return StemJobOutcome.Failed(status.error ?: "Separation failed on the server.")
                "CANCELLED" -> return StemJobOutcome.Cancelled
                else -> Unit // IN_QUEUE / IN_PROGRESS
            }
        }
    }

    private fun submit(base: String, token: String, source: File): StemJobSubmitResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                source.name,
                source.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$base/run")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = checkNotNull(response.body).string()
            return json.decodeFromString(StemJobSubmitResponse.serializer(), text)
        }
    }

    private fun status(base: String, token: String, jobId: String): StemJobStatusResponse {
        val request = Request.Builder()
            .url("$base/status/$jobId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = checkNotNull(response.body).string()
            return json.decodeFromString(StemJobStatusResponse.serializer(), text)
        }
    }

    private fun download(
        base: String,
        token: String,
        status: StemJobStatusResponse,
        outputDirectory: File,
        outputPrefix: String,
    ): Map<String, File> {
        outputDirectory.mkdirs()
        val result = LinkedHashMap<String, File>()
        for (kind in EXPECTED_STEMS) {
            val path = status.stems[kind] ?: continue
            val url = if (path.startsWith("http")) path else "$base$path"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val partial = File(outputDirectory, "$outputPrefix$kind.wav.part")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                partial.outputStream().use { out ->
                    checkNotNull(response.body).byteStream().copyTo(out, 64 * 1024)
                }
            }
            val target = File(outputDirectory, "$outputPrefix$kind.wav")
            // Publish atomically: a half-written stem that looks complete is worse than none,
            // because the loader would happily play the truncated half.
            if (target.exists()) target.delete()
            if (partial.renameTo(target)) result[kind] = target else partial.delete()
        }
        return result
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpStatusException -> when (error.code) {
            401, 403 -> "The separation server rejected the access token."
            404 -> "The separation server address looks wrong."
            413 -> "That track is too large for the server."
            else -> "The separation server answered ${error.code}."
        }
        else -> "Couldn't reach the separation server."
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    companion object {
        val EXPECTED_STEMS = listOf("vocals", "drums", "bass", "other")
        private const val POLL_INTERVAL_MS = 4_000L
    }
}
