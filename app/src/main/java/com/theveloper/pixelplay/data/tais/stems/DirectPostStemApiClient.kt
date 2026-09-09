package com.theveloper.pixelplay.data.tais.stems

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber

/**
 * Client for a bare "upload a file, get audio back" stem-separation server — one
 * `POST {base}{route}` multipart call, no upload/submit/poll dance. This is what a small
 * self-hosted FastAPI app (Colab + ngrok, a local server on the same LAN, Modal, RunPod, ...)
 * looks like when it isn't wrapped in Gradio. [BsRoformerApiClient] speaks Gradio Spaces' queue
 * protocol instead, a completely different wire format on completely different routes.
 *
 * Known limitation: a render slow enough (no/weak local GPU, several minutes) with nothing sent
 * over the connection in the meantime can get silently killed by something on the network path
 * (a home router's NAT idle-connection timeout is the most likely single cause — confirmed
 * against a real failure that died at ~5m40s of silence). A job-id-based polling protocol (short
 * separate connections instead of one long-held one) would fix this, but ran into a reproducible
 * KSP/Hilt resolution failure in this project's current toolchain — every structural variant of
 * that approach made `DirectPostStemApiClient` unresolvable to KSP's InjectProcessingStep with no
 * underlying compiler error, confirmed by extensive bisection (see
 * pixelplay-ksp-mixed-return-bug memory). Revisit once the Kotlin/KSP/Hilt versions have moved;
 * for now, keep local/self-hosted renders short enough to finish before an idle router kills the
 * connection, or use the Gradio path instead if that's a hard blocker.
 */
@Singleton
class DirectPostStemApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    /**
     * [route] is whatever single endpoint this server exposes (default "/predict" to match the
     * common convention, but nothing about this protocol is standardized the way Gradio's is).
     * [onStage] only fires twice — there's no server-side progress signal for a single blocking
     * POST the way there is per-chunk progress for the on-device pipeline or an SSE queue for
     * Gradio.
     */
    suspend fun separate(
        baseUrl: String,
        route: String,
        apiKey: String?,
        sourceAudioFile: File,
        outputDir: File,
        readTimeoutMs: Long = 15 * 60 * 1000L,
        onStage: suspend (String) -> Unit = {}
    ): RoformerResult? = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val path = route.trim().ifBlank { "/predict" }.let { if (it.startsWith("/")) it else "/$it" }
        try {
            onStage("Connecting to render server…")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    sourceAudioFile.name,
                    sourceAudioFile.asRequestBody("audio/*".toMediaType())
                )
                .build()
            val request = authedRequestBuilder("$base$path", apiKey)
                .header("ngrok-skip-browser-warning", "true")
                .post(body)
                .build()

            // A GPU render can take minutes and this protocol has no progress channel to poll —
            // the whole call is one blocking POST until the WAV comes back, so it needs the same
            // generous read timeout the Gradio client gives its SSE stream.
            val longReadClient = okHttpClient.newBuilder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()

            onStage("Isolating stems…")
            longReadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w(
                        "Direct POST render HTTP %d: %s",
                        response.code,
                        response.body?.string().orEmpty().take(500)
                    )
                    return@withContext null
                }
                val responseBody = response.body ?: return@withContext null
                val contentType = responseBody.contentType()?.toString().orEmpty()
                val looksLikeAudio = contentType.isBlank() ||
                    contentType.startsWith("audio", ignoreCase = true) ||
                    contentType.contains("octet-stream", ignoreCase = true)
                if (!looksLikeAudio) {
                    Timber.tag(TAG).w(
                        "Direct POST render returned non-audio content-type %s: %s",
                        contentType,
                        responseBody.string().take(500)
                    )
                    return@withContext null
                }

                onStage("Downloading the isolated instrumental…")
                outputDir.mkdirs()
                val instrumentalFile = File(outputDir, "direct_post_${System.currentTimeMillis()}_instrumental.wav")
                responseBody.byteStream().use { input ->
                    instrumentalFile.outputStream().use { output -> input.copyTo(output) }
                }
                RoformerResult(instrumentalFile = instrumentalFile, vocalsFile = null)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct POST render failed, caller should fall back to on-device")
            null
        }
    }

    private fun authedRequestBuilder(url: String, apiKey: String?): Request.Builder =
        Request.Builder().url(url).apply {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        }

    private companion object {
        const val TAG = "DirectPostStemApiClient"
    }
}
