package com.theveloper.pixelplay.data.cloudstudio

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

// RunPod Serverless job API for the Cloud Studio endpoint (design §1, §5, §7.1), ported from the iOS app's
// `RunPodJobsClient.swift`: `/run`, `/status`, `/cancel`, `/health`, and `/runsync` for the selftest only. The phone
// holds a Restricted key (Read/Write on this one endpoint) and calls nothing else: it never creates or changes
// endpoints and never reads billing.
//
// Behaviour:
// - A 429 sets a Retry-After gate: every call before it ends fails fast with RateLimited (SpotifyConnectClient's
//   pattern on iOS).
// - Reads (`/status`, `/health`, `/cancel`) retry a 5xx or a dropped connection twice with backoff. `/run` is never
//   retried here: a lost response may still have queued the job, so the engine decides (the worker's guard makes a
//   duplicate harmless).
// - `/run` calls are at least 100 ms apart (§5, app guards).
// - A `/status` 404 is JobNotFound ("expired or never existed"; results then come from R2), a 404 anywhere else is
//   EndpointNotFound.

/** RunPod's job states. */
enum class RunPodJobStatus(val wire: String) {
    IN_QUEUE("IN_QUEUE"),
    IN_PROGRESS("IN_PROGRESS"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    TIMED_OUT("TIMED_OUT");

    /** The job will not change any more. */
    val isFinal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT

    companion object {
        fun fromWire(value: String?): RunPodJobStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** The worker's `progress_update` text, `stage:pct` (e.g. `separate:40`). */
data class CloudProgress(val stage: String, val percent: Int?) {
    /** A short label for the queue screen. */
    val label: String
        get() = when (stage) {
            "fetch", "download" -> "Fetching the song"
            "probe", "decode" -> "Reading the audio"
            "separate" -> "Separating vocals"
            "stems4", "stems" -> "Splitting stems"
            "lyrics", "align" -> "Timing the lyrics"
            "transcribe" -> "Writing the lyrics"
            "encode" -> "Encoding"
            "upload" -> "Uploading results"
            "guard" -> "Starting"
            else -> stage.replaceFirstChar { it.uppercase() }
        }

    companion object {
        /** Parses `stage:pct` (or a bare stage name). */
        fun parse(text: String): CloudProgress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 64 || trimmed.startsWith("{")) return null
            val stage = trimmed.substringBefore(':')
            if (stage.isEmpty() || !stage.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
            val percent = if (':' in trimmed) {
                trimmed.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 100)
            } else null
            return CloudProgress(stage, percent)
        }
    }
}

/** A job as `/run`, `/status`, `/runsync` and `/cancel` describe it. */
data class RunPodJob(
    val id: String,
    val status: String,
    /** The manifest, when the output is one (`pixl.cloudstudio.result`). */
    val result: CloudJobResult? = null,
    /** A text output: the progress string while running. */
    val outputText: String? = null,
    /** The selftest's output (`pixl.cloudstudio.selftest`). */
    val selftest: CloudSelftestResult? = null,
    /** The raw output JSON when it is an object that isn't a manifest (the selftest's too). */
    val outputJson: JsonObject? = null,
    /** RunPod's `error` (the handler returns `"<CODE>: <message>"`). */
    val error: String? = null,
    val delayTimeMs: Long? = null,
    val executionTimeMs: Long? = null,
) {
    val typedStatus: RunPodJobStatus? get() = RunPodJobStatus.fromWire(status)

    /** Progress while running. */
    val progress: CloudProgress? get() = outputText?.let(CloudProgress::parse)

    /** The worker error code in a failed job's `error` (`"POISONED: …"`), else the manifest's. */
    val errorCode: CloudErrorCode?
        get() {
            result?.errorCode?.let { return it }
            val head = error?.substringBefore(':')?.trim() ?: return null
            return CloudErrorCode.fromWireOrNull(head)
        }

    companion object {
        /** Parses a RunPod job response body. */
        fun parse(body: ByteArray): RunPodJob? {
            val root = runCatching { CloudJson.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject }
                .getOrNull() ?: return null
            val id = root.string("id")?.takeIf { it.isNotEmpty() } ?: return null
            var job = RunPodJob(
                id = id,
                status = root.string("status").orEmpty(),
                delayTimeMs = (root["delayTime"] as? JsonPrimitive)?.longOrNull,
                executionTimeMs = (root["executionTime"] as? JsonPrimitive)?.longOrNull,
            )
            when (val error = root["error"]) {
                null, JsonNull -> Unit
                is JsonPrimitive -> job = job.copy(error = error.contentOrNull)
                else -> job = job.copy(error = error.toString())
            }
            when (val output = root["output"]) {
                is JsonPrimitive -> if (output.isString) job = job.copy(outputText = output.content)
                is JsonObject -> {
                    val schema = output.string("schema")
                    job = when {
                        schema == CloudSchema.RESULT -> {
                            val result = runCatching { CloudJson.decodeFromJsonElement<CloudJobResult>(output) }.getOrNull()
                            if (result != null) job.copy(result = result) else job.copy(outputJson = output)
                        }
                        schema == CloudSchema.SELFTEST -> job.copy(
                            selftest = runCatching { CloudJson.decodeFromJsonElement<CloudSelftestResult>(output) }.getOrNull(),
                            outputJson = output,
                        )
                        output.string("error") != null && job.error == null ->
                            job.copy(error = output.string("error"), outputJson = output)
                        else -> job.copy(outputJson = output)
                    }
                }
                else -> Unit
            }
            return job
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `/health`: queue and worker counts. */
data class RunPodHealth(
    val inQueue: Int = 0,
    val inProgress: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val idleWorkers: Int = 0,
    val runningWorkers: Int = 0,
    val initializingWorkers: Int = 0,
    val unhealthyWorkers: Int = 0,
) {
    companion object {
        fun parse(body: ByteArray): RunPodHealth? {
            val root = runCatching { CloudJson.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject }
                .getOrNull() ?: return null
            fun int(group: String, key: String): Int =
                ((root[group] as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            return RunPodHealth(
                inQueue = int("jobs", "inQueue"),
                inProgress = int("jobs", "inProgress"),
                completed = int("jobs", "completed"),
                failed = int("jobs", "failed"),
                idleWorkers = int("workers", "idle") + int("workers", "ready"),
                runningWorkers = int("workers", "running"),
                initializingWorkers = int("workers", "initializing"),
                unhealthyWorkers = int("workers", "unhealthy"),
            )
        }
    }
}

/** A RunPod request failed. Messages never carry the key or a presigned URL. */
sealed class RunPodError(message: String) : Exception(message) {
    /** 401/403: the key is wrong, disabled, or not allowed on this endpoint. */
    class Unauthorized(val status: Int) : RunPodError("RunPod refused the key (HTTP $status)")
    /** 404 on the endpoint: the Endpoint ID is wrong (or the endpoint was deleted). */
    class EndpointNotFound : RunPodError("Endpoint not found — check the Endpoint ID")
    /** 404 on `/status/<id>`: the job's 30-minute retention (or its ttl) passed, or it never existed. */
    class JobNotFound : RunPodError("Job not found (expired)")
    /** 429; calls fail fast until the gate ends. */
    class RateLimited(val retryAfterMs: Long) : RunPodError("Rate limited for ${retryAfterMs / 1000} s")
    class Server(val status: Int) : RunPodError("RunPod server error (HTTP $status)")
    class Http(val status: Int) : RunPodError("RunPod answered HTTP $status")
    class Network(detail: String) : RunPodError("Network error: $detail")
    class BadResponse(detail: String) : RunPodError("Unexpected RunPod answer: $detail")
    /** The Endpoint ID or key is missing or malformed. */
    class NotConfigured : RunPodError("RunPod isn't set up")
}

/** What the engine needs from RunPod. [RunPodJobsClient] is the real one; unit tests use fakes. */
interface RunPodJobsApi {
    suspend fun run(request: CloudJobRequest): RunPodJob
    suspend fun status(jobId: String): RunPodJob
    suspend fun cancel(jobId: String)
    suspend fun health(): RunPodHealth
    /** `/runsync` with `op: "selftest"` (about one cold start, ~1¢). */
    suspend fun selftest(build: String): RunPodJob
}

class RunPodJobsClient(
    private val http: CloudHttp,
    endpointId: String,
    apiKey: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : RunPodJobsApi {
    private val endpointId = endpointId.trim()
    private val apiKey = apiKey.trim()
    private val gate = Mutex()
    @Volatile private var notBeforeMs: Long = 0
    private var lastRunAtMs: Long? = null

    /** How long the Retry-After gate still holds (0 = open). */
    val retryAfterRemainingMs: Long get() = (notBeforeMs - nowMs()).coerceAtLeast(0)

    override suspend fun run(request: CloudJobRequest): RunPodJob {
        val body = CloudJson.encodeToString(CloudJobRequest.serializer(), request).toByteArray()
        gate.withLock {
            lastRunAtMs?.let { last ->
                val wait = MINIMUM_RUN_INTERVAL_MS - (nowMs() - last)
                if (wait > 0) sleep(wait)
            }
            lastRunAtMs = nowMs()
        }
        val response = send("POST", "run", body, retries = false)
        return RunPodJob.parse(response.body) ?: throw RunPodError.BadResponse("no job id")
    }

    override suspend fun status(jobId: String): RunPodJob {
        if (!isSafeIdentifier(jobId, 128)) throw RunPodError.JobNotFound()
        val response = send("GET", "status/$jobId", retries = true, notFound = { RunPodError.JobNotFound() })
        return RunPodJob.parse(response.body) ?: throw RunPodError.BadResponse("unreadable job status")
    }

    override suspend fun cancel(jobId: String) {
        if (!isSafeIdentifier(jobId, 128)) throw RunPodError.JobNotFound()
        send("POST", "cancel/$jobId", retries = true, notFound = { RunPodError.JobNotFound() })
    }

    override suspend fun health(): RunPodHealth {
        val response = send("GET", "health", retries = true)
        return RunPodHealth.parse(response.body) ?: throw RunPodError.BadResponse("unreadable health")
    }

    override suspend fun selftest(build: String): RunPodJob {
        val body = CloudJson.encodeToString(CloudOpRequest.serializer(), CloudOpRequest.of(CloudOp.SELFTEST, build))
            .toByteArray()
        val response = send("POST", "runsync", body, retries = false, timeoutMs = 150_000)
        return RunPodJob.parse(response.body) ?: throw RunPodError.BadResponse("no selftest answer")
    }

    private suspend fun send(
        method: String,
        path: String,
        body: ByteArray? = null,
        retries: Boolean,
        notFound: () -> RunPodError = { RunPodError.EndpointNotFound() },
        timeoutMs: Long = 30_000,
    ): CloudHttpResponse {
        if (!isValidEndpointId(endpointId) || apiKey.isEmpty()) throw RunPodError.NotConfigured()
        var attempt = 0
        while (true) {
            val wait = notBeforeMs - nowMs()
            if (wait > 0) throw RunPodError.RateLimited(wait)
            val headers = buildList {
                add("Authorization" to "Bearer $apiKey")
                add("Accept" to "application/json")
                if (body != null) add("Content-Type" to "application/json")
            }
            val request = CloudHttpRequest(method, "$BASE_URL/$endpointId/$path", headers, body, timeoutMs)
            val failure: RunPodError = try {
                val response = http.send(request)
                when (response.statusCode) {
                    in 200..299 -> return response
                    401, 403 -> throw RunPodError.Unauthorized(response.statusCode)
                    404 -> throw notFound()
                    429 -> {
                        val ms = retryAfterMs(response.header("Retry-After"))
                        notBeforeMs = nowMs() + ms
                        throw RunPodError.RateLimited(ms)
                    }
                    in 500..599 -> RunPodError.Server(response.statusCode)
                    else -> throw RunPodError.Http(response.statusCode)
                }
            } catch (error: RunPodError) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RunPodError.Network(CloudRedaction.redact(error.message ?: error.javaClass.simpleName))
            }
            if (!retries || attempt >= READ_RETRY_DELAYS_MS.size) throw failure
            sleep(READ_RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }

    companion object {
        const val BASE_URL = "https://api.runpod.ai/v2"
        /** §5: one `/run` per 100 ms or slower. */
        const val MINIMUM_RUN_INTERVAL_MS: Long = 100
        /** Backoff before the 2nd and 3rd try of a read. */
        val READ_RETRY_DELAYS_MS: List<Long> = listOf(1_000, 3_000)

        /** RunPod endpoint ids are short alphanumerics; anything else can't be put in a URL path. */
        fun isValidEndpointId(id: String): Boolean = isSafeIdentifier(id, 64)

        internal fun isSafeIdentifier(id: String, maxLength: Int): Boolean =
            id.isNotEmpty() && id.length <= maxLength &&
                id.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '-' || it == '_' }

        /** `Retry-After` in seconds (default 5 s, capped at 10 min). */
        internal fun retryAfterMs(header: String?): Long {
            val seconds = header?.trim()?.toDoubleOrNull()
            if (seconds == null || !seconds.isFinite() || seconds < 0) return 5_000
            return minOf((seconds * 1000).toLong(), 600_000)
        }
    }
}
