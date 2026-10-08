package com.theveloper.pixelplay.data.cloudstudio

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// "Test connection" in Settings › Cloud processing (design §7.2), ported from the iOS app's
// `CloudConnectionTest.swift`: RunPod's `/health` and a tiny probe object in the bucket (PUT, HEAD, DELETE under
// `probe/`), each reported on its own in plain English, plus the optional selftest. Nothing here runs a GPU except
// the selftest (about one cold start, ~1¢).

/** One check's outcome. */
data class CloudCheck(
    val ok: Boolean,
    /** One sentence the person can act on. */
    val message: String,
    /** Extra facts on success (queue and worker counts, the worker's GPU), else null. */
    val detail: String? = null,
)

/** Both checks of one "Test connection". */
data class CloudConnectionReport(val runpod: CloudCheck, val storage: CloudCheck) {
    val allOk: Boolean get() = runpod.ok && storage.ok
}

/** The selftest's sentence plus the endpoint's limits it reported (kept so uploads stay inside them). */
data class CloudSelftestReport(val check: CloudCheck, val caps: CloudWorkerCaps? = null)

object CloudConnectionTest {
    private const val FILL_RUNPOD = "Fill in the Endpoint ID and the RunPod key first."
    private const val FILL_STORAGE = "Fill in the R2 endpoint, bucket, access key ID and secret first."
    private const val UNREACHABLE = "Couldn't reach RunPod. Check your connection and try again."

    /** `GET /health`: 200 → the endpoint answers; 401/403 → the key; 404 → the Endpoint ID. */
    suspend fun checkRunPod(api: RunPodJobsApi?): CloudCheck {
        if (api == null) return CloudCheck(false, FILL_RUNPOD)
        return try {
            val health = api.health()
            CloudCheck(true, "RunPod works: the endpoint answered and accepted the key.", healthSummary(health))
        } catch (error: CancellationException) {
            throw error
        } catch (error: RunPodError) {
            CloudCheck(false, message(error))
        } catch (error: Exception) {
            CloudCheck(false, UNREACHABLE)
        }
    }

    /** "1 song waiting · 1 worker running". */
    fun healthSummary(health: RunPodHealth): String {
        fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        val waiting = count(health.inQueue, "song waiting", "songs waiting")
        val workers = health.runningWorkers + health.initializingWorkers
        val running = if (workers == 0 && health.idleWorkers == 0) {
            "no worker awake (normal when idle)"
        } else {
            count(workers, "worker running", "workers running") +
                if (health.idleWorkers > 0) ", ${health.idleWorkers} idle" else ""
        }
        var text = "$waiting · $running"
        if (health.unhealthyWorkers > 0) {
            text += " · " + count(health.unhealthyWorkers, "unhealthy worker", "unhealthy workers")
        }
        return text
    }

    fun message(error: RunPodError): String = when (error) {
        is RunPodError.Unauthorized ->
            "RunPod refused the key. Use the Restricted key with Read/Write on this endpoint, and check it isn't disabled."
        is RunPodError.EndpointNotFound ->
            "RunPod doesn't know this Endpoint ID. Copy it again from Serverless › pixl-cloud-studio."
        is RunPodError.JobNotFound -> "RunPod no longer has that job."
        is RunPodError.RateLimited -> "RunPod asked to slow down. Try again in a minute."
        is RunPodError.Server -> "RunPod had a problem on its side (HTTP ${error.status}). Try again later."
        is RunPodError.Http -> "RunPod answered with an unexpected error (HTTP ${error.status})."
        is RunPodError.Network -> UNREACHABLE
        is RunPodError.BadResponse -> "RunPod answered something this app doesn't understand."
        is RunPodError.NotConfigured -> FILL_RUNPOD
    }

    /**
     * PUT a 1-byte object at `probe/<probeId>`, HEAD it, DELETE it. Each step's failure says which permission or
     * field is wrong.
     */
    suspend fun checkStorage(store: CloudObjectStoring?, probeId: String): CloudCheck {
        if (store == null) return CloudCheck(false, FILL_STORAGE)
        val key = CloudKeys.probe(probeId)
        try {
            store.put(key, byteArrayOf(0x31), "application/octet-stream")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return CloudCheck(false, storageMessage(error, "write a test file"))
        }
        try {
            if (store.head(key) == null) {
                return CloudCheck(false, "Storage took the test file but couldn't find it again. Check the bucket name.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { store.delete(key) }
            return CloudCheck(false, storageMessage(error, "read the test file back"))
        }
        try {
            store.delete(key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return CloudCheck(
                false,
                storageMessage(error, "delete the test file") + " The token needs Object Read & Write, which includes deleting."
            )
        }
        return CloudCheck(true, "Storage works: a test file was written, found and deleted.")
    }

    internal fun storageMessage(error: Exception, step: String): String = when (error) {
        is CloudStorageError.Unauthorized ->
            "Storage refused the key when trying to $step. Check the access key ID and secret, and that the token has " +
                "Object Read & Write on this bucket."
        is CloudStorageError.BucketNotFound ->
            "Storage says this bucket doesn't exist. Check the bucket name and the account ID in the endpoint."
        is CloudStorageError.Http -> "Storage answered HTTP ${error.status} when trying to $step."
        is CloudStorageError.Network ->
            "Couldn't reach storage. Check the R2 endpoint (or account ID) and your connection."
        is CloudStorageError.BadResponse -> "Storage answered something this app doesn't understand."
        is CloudStorageError.NotConfigured -> FILL_STORAGE
        else -> "Couldn't $step: ${CloudRedaction.redact(error.message ?: error.javaClass.simpleName)}"
    }

    /** `op: "selftest"` through `/runsync`: the worker's version and GPU (one cold start, about 1¢). */
    suspend fun selftestReport(api: RunPodJobsApi?, build: String): CloudSelftestReport {
        if (api == null) return CloudSelftestReport(CloudCheck(false, FILL_RUNPOD))
        return try {
            val job = api.selftest(build)
            val selftest = job.selftest
            if (job.typedStatus == RunPodJobStatus.COMPLETED && selftest != null) {
                CloudSelftestReport(check(selftest), selftest.caps)
            } else {
                CloudSelftestReport(check(job))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RunPodError) {
            CloudSelftestReport(CloudCheck(false, message(error)))
        } catch (error: Exception) {
            CloudSelftestReport(CloudCheck(false, UNREACHABLE))
        }
    }

    /** A decoded selftest: problems first, then the worker, its GPU and (when it reports them) its limits. */
    internal fun check(selftest: CloudSelftestResult): CloudCheck {
        if (!selftest.isOk) {
            val reason = selftest.error?.let { "${it.code}: ${it.message.orEmpty()}" } ?: "status ${selftest.status}"
            return CloudCheck(false, "The worker started but reported a problem (${CloudRedaction.redact(reason)}).")
        }
        if (selftest.agreedVersion == null) {
            return CloudCheck(
                false,
                "The worker doesn't speak this app's job format (v${CloudSchema.VERSION}). Update the worker or the app."
            )
        }
        val missing = selftest.modelAvailability.filterValues { !it.isAvailable }.keys.sorted()
        if (missing.isNotEmpty()) {
            return CloudCheck(false, "The worker is missing models: ${missing.joinToString(", ")}.")
        }
        if (selftest.caps?.hostsConfigured == 0) {
            return CloudCheck(
                false,
                "The worker allows no storage host, so every song would fail. Set PIXL_ALLOWED_HOST_SUFFIXES on the " +
                    "endpoint to <account ID>.r2.cloudflarestorage.com and deploy again."
            )
        }
        val version = selftest.worker?.version ?: "?"
        val gpu = selftest.worker?.gpu ?: "unknown GPU"
        var detail = "Worker $version on $gpu"
        val mb = selftest.caps?.maxInputMB
        val seconds = selftest.caps?.maxAudioS
        if (mb != null && seconds != null) detail += " · songs up to $mb MB and ${seconds / 60} min"
        return CloudCheck(true, "The worker answered.", detail)
    }

    /** A selftest job whose output isn't the selftest schema (an older worker), or that didn't complete. */
    internal fun check(job: RunPodJob): CloudCheck {
        val output = job.outputJson
        if (job.typedStatus == RunPodJobStatus.COMPLETED && output != null) {
            val worker = output["worker"] as? JsonObject
            val version = worker?.string("version") ?: "?"
            val gpu = worker?.string("gpu") ?: "unknown GPU"
            val supported = (output["supported"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            if (supported.isNotEmpty() && CloudSchema.VERSION !in supported) {
                return CloudCheck(
                    false,
                    "The worker doesn't speak this app's job format (v${CloudSchema.VERSION}). Update the worker or the app."
                )
            }
            return CloudCheck(true, "The worker answered.", "Worker $version on $gpu")
        }
        if (job.typedStatus == RunPodJobStatus.IN_QUEUE || job.typedStatus == RunPodJobStatus.IN_PROGRESS) {
            return CloudCheck(
                false,
                "The worker is still starting (a cold start can take a few minutes). Try again shortly."
            )
        }
        return CloudCheck(false, "The selftest failed: ${CloudRedaction.redact(job.error ?: job.status)}.")
    }
}
