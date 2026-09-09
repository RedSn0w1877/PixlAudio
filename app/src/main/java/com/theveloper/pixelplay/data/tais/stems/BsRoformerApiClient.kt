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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Client for a hosted BS-RoFormer / Mel-Band RoFormer vocal-separation model — targets the real
 * Gradio Spaces Client REST API, the same queue-based HTTP protocol `gradio_client` (Python) and
 * `@gradio/client` (JS) use, and the one every Hugging Face Space's auto-generated "Use via API"
 * page documents. This works against *any* Space exposing a BS-RoFormer/Mel-Band-RoFormer
 * separation function this way — a public HF Space, a private/gated one (with
 * [apiKey] as a bearer token), or a self-hosted Gradio app on Modal/RunPod/etc — not one specific
 * hardcoded model, since Space slugs and function names aren't stable across authors or time.
 *
 * Protocol (Gradio ≥4.x "queue" API):
 * ```
 * POST {base}/gradio_api/upload                    multipart field "files" = the source audio
 *   -> ["<server-side temp path>"]
 * POST {base}/gradio_api/call/{apiName}             body {"data": [<FileData referencing the upload>]}
 *   -> {"event_id": "..."}
 * GET  {base}/gradio_api/call/{apiName}/{eventId}   text/event-stream; poll until `event: complete`
 *   -> data: [<function outputs>, ...]
 * GET  <output FileData's "url">                    -> the separated stem's audio bytes
 * ```
 * [apiName] (e.g. `/predict`) is NOT universal — every Space author names their inference
 * function differently, so it must match whatever Space [baseUrl] actually points at; there's no
 * way to auto-detect it without also parsing `/gradio_api/info`, which this client doesn't do.
 *
 * Output shape is a second unavoidable assumption: this reads every file-typed value in the
 * function's output array, in order. Two file outputs -> first is treated as vocals, last as the
 * instrumental (the common convention across UVR/HTDemucs-style separator UIs). One file output
 * -> treated as the instrumental only, vocals stays null. A Space returning outputs in a
 * different order or shape needs a matching change here — there is no generic solution to that.
 */
@Singleton
class BsRoformerApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /**
     * Submits [sourceAudioFile] to the Space at [baseUrl]/[apiName], polls the SSE result stream
     * until the function completes (or [pollTimeoutMs] elapses), then downloads whichever stem
     * file(s) it returned into [outputDir]. Returns null on any failure — bad upload, submit
     * error, timeout, an `event: error` from the Space, or a response with no file outputs at
     * all — so the caller can fall back to on-device separation without inspecting exception
     * types.
     */
    /**
     * [extraArg] is appended as a second positional string argument after the audio file, for
     * Spaces whose function needs one (e.g. a stem-variant dropdown) — see class doc, some Spaces
     * error out server-side if a "default"-documented trailing arg is omitted entirely rather
     * than actually applying the default. Null/blank sends only the audio file.
     * [onStage] reports coarse progress text — this is a single GPU inference call with no
     * per-percent signal to surface, unlike the on-device pipeline's per-chunk progress.
     */
    suspend fun separate(
        baseUrl: String,
        apiName: String,
        apiKey: String?,
        sourceAudioFile: File,
        outputDir: File,
        extraArg: String? = null,
        pollTimeoutMs: Long = 15 * 60 * 1000L,
        onStage: suspend (String) -> Unit = {}
    ): RoformerResult? = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val route = apiName.trim().trimStart('/')
        try {
            onStage("Connecting to BS-RoFormer GPU…")
            val uploadedPath = uploadFile(base, apiKey, sourceAudioFile) ?: run {
                Timber.tag(TAG).w("BS-RoFormer upload failed for %s", baseUrl)
                return@withContext null
            }
            val eventId = submitCall(base, route, apiKey, uploadedPath, extraArg) ?: run {
                Timber.tag(TAG).w("BS-RoFormer call submission failed for %s/%s", baseUrl, apiName)
                return@withContext null
            }
            onStage("Isolating stems…")
            val fileOutputs = pollForCompletion(base, route, apiKey, eventId, pollTimeoutMs)
                ?: return@withContext null
            if (fileOutputs.isEmpty()) {
                Timber.tag(TAG).w("BS-RoFormer job %s completed with no file outputs", eventId)
                return@withContext null
            }

            onStage("Downloading the isolated instrumental…")
            outputDir.mkdirs()
            val instrumentalUrl = fileOutputs.last()
            val vocalsUrl = if (fileOutputs.size > 1) fileOutputs.first() else null

            val instrumentalFile = downloadFile(instrumentalUrl, apiKey, File(outputDir, "roformer_${eventId}_instrumental.wav"))
                ?: return@withContext null
            val vocalsFile = vocalsUrl?.let { downloadFile(it, apiKey, File(outputDir, "roformer_${eventId}_vocals.wav")) }

            RoformerResult(instrumentalFile = instrumentalFile, vocalsFile = vocalsFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "BS-RoFormer separation failed, caller should fall back to on-device")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 1: upload the source audio so it can be referenced by path in the call payload.
    // ---------------------------------------------------------------------------------------

    private fun uploadFile(base: String, apiKey: String?, sourceAudioFile: File): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                sourceAudioFile.name,
                sourceAudioFile.asRequestBody("audio/*".toMediaType())
            )
            .build()
        val request = authedRequestBuilder("$base/gradio_api/upload", apiKey)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("BS-RoFormer upload HTTP %d", response.code)
                return null
            }
            val json = JSONArray(response.body?.string().orEmpty())
            return if (json.length() > 0) json.optString(0).takeIf { it.isNotBlank() } else null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 2: submit the queued call, referencing the uploaded file as this function's audio
    // argument via Gradio's FileData payload shape, plus an optional trailing string argument
    // (see [separate]'s extraArg doc).
    // ---------------------------------------------------------------------------------------

    private fun submitCall(base: String, route: String, apiKey: String?, uploadedPath: String, extraArg: String?): String? {
        val fileData = JSONObject().apply {
            put("path", uploadedPath)
            put("meta", JSONObject().put("_type", "gradio.FileData"))
        }
        val dataArray = JSONArray().put(fileData)
        if (!extraArg.isNullOrBlank()) dataArray.put(extraArg)
        val payload = JSONObject().apply {
            put("data", dataArray)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = authedRequestBuilder("$base/gradio_api/call/$route", apiKey)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("BS-RoFormer call submit HTTP %d: %s", response.code, response.body?.string().orEmpty())
                return null
            }
            val json = JSONObject(response.body?.string().orEmpty())
            return json.optString("event_id").takeIf { it.isNotBlank() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 3: read the SSE result stream until `event: complete` (success), `event: error`
    // (failure), or [pollTimeoutMs] elapses. Every file-typed entry in the completed output array
    // is returned in order — see class doc for how vocals/instrumental are inferred from order.
    //
    // The connection itself is expected to drop mid-render — observed in practice as a plain
    // `SocketException: Software caused connection abort` after roughly a minute of no data, well
    // inside both our own and the server's nominal timeouts. That's consistent with an
    // intermediate proxy (Hugging Face's own front door, in front of the actual Space) enforcing
    // its own idle-connection limit that neither side's app-level timeout controls. The job itself
    // keeps running server-side regardless — Gradio's queue API is built to be reconnected to, so
    // on any read failure this just re-opens the same `GET .../call/{route}/{eventId}` and keeps
    // reading from wherever the job currently stands, instead of failing the whole render over a
    // transient network hiccup.
    // ---------------------------------------------------------------------------------------

    private fun pollForCompletion(
        base: String,
        route: String,
        apiKey: String?,
        eventId: String,
        pollTimeoutMs: Long
    ): List<String>? {
        // The shared OkHttpClient's default read timeout (tuned for ordinary API calls) would
        // kill this connection long before a GPU render finishes — an SSE stream can sit open for
        // minutes between "generating"/heartbeat lines. Give this one call its own timeout
        // matching the caller's actual patience for the render.
        val streamingClient = okHttpClient.newBuilder()
            .readTimeout(pollTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val deadline = System.currentTimeMillis() + pollTimeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                pollOnce(streamingClient, base, route, apiKey, eventId)?.let { return it }
                // pollOnce returning null (not throwing) means it read a genuine `event: error` or
                // a non-2xx response — a real failure, not a dropped connection. Don't retry those.
                return null
            } catch (e: java.io.IOException) {
                lastError = e
                Timber.tag(TAG).w(e, "BS-RoFormer result stream for job %s dropped, reconnecting", eventId)
                Thread.sleep(1_000L) // brief backoff so a persistently broken connection doesn't spin-loop
            }
        }
        Timber.tag(TAG).w(lastError, "BS-RoFormer job %s timed out after %dms", eventId, pollTimeoutMs)
        return null
    }

    /** One connection attempt at the SSE stream — returns the completed output URLs, null for a definitive failure (bad HTTP status / `event: error`), or throws [java.io.IOException] for a dropped connection the caller should retry. */
    private fun pollOnce(client: OkHttpClient, base: String, route: String, apiKey: String?, eventId: String): List<String>? {
        val request = authedRequestBuilder("$base/gradio_api/call/$route/$eventId", apiKey)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("BS-RoFormer result stream HTTP %d", response.code)
                return null
            }
            val source = response.body?.source() ?: return null
            var currentEvent: String? = null
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val dataText = line.removePrefix("data:").trim()
                        when (currentEvent) {
                            "complete" -> return extractFileUrls(dataText)
                            "error" -> {
                                Timber.tag(TAG).w("BS-RoFormer job %s reported an error: %s", eventId, dataText)
                                return null
                            }
                            // "generating" / "heartbeat" / null (some Gradio builds omit the event
                            // line and just repeat data) — keep reading until complete/error/EOF.
                        }
                    }
                }
            }
            // Reached EOF without ever seeing `complete`/`error` — indistinguishable from a
            // silent connection drop, so treat it the same way: let the caller reconnect rather
            // than declaring the whole render failed over what's likely the same proxy-idle issue.
            throw java.io.IOException("BS-RoFormer result stream for job $eventId ended without a completion event")
        }
    }

    /** Walks the completed call's output array and pulls the "url" (or "path") out of every Gradio FileData-shaped element, in order. */
    private fun extractFileUrls(dataText: String): List<String> {
        val array = JSONArray(dataText)
        val urls = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val element = array.opt(i) as? JSONObject ?: continue
            val url = element.optString("url").takeIf { it.isNotBlank() }
                ?: element.optString("path").takeIf { it.isNotBlank() }
            if (url != null) urls.add(url)
        }
        return urls
    }

    // ---------------------------------------------------------------------------------------
    // Step 4: download a resolved output file. Gradio's FileData "url" is already an absolute,
    // directly-downloadable link into the Space's file-serving route.
    // ---------------------------------------------------------------------------------------

    private fun downloadFile(url: String, apiKey: String?, destination: File): File? {
        val request = authedRequestBuilder(url, apiKey).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("BS-RoFormer output download HTTP %d for %s", response.code, url)
                return null
            }
            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            return destination
        }
    }

    private fun authedRequestBuilder(url: String, apiKey: String?): Request.Builder =
        Request.Builder().url(url).apply {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        }

    private companion object {
        const val TAG = "BsRoformerApiClient"
    }
}
