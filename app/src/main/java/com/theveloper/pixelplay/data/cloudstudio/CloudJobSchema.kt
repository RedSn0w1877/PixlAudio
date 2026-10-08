package com.theveloper.pixelplay.data.cloudstudio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Cloud Studio job schema v1 (design: handoff/2026-10-07-plans/cloud-studio-design.md §2.3). These are the
// phone's copies of the worker's `cloud/runpod-worker/schema/v1/*.schema.json` (PixlAudio-iOS repo): the `/run` body
// the phone sends, the result manifest the worker returns (and writes to R2 as `manifest.json`), `lyrics.json`, the
// selftest answer and the guard's `attempt.json`. iOS and Android talk to the same endpoint with the same schema.
//
// Versioning (§2.3): `v` is an integer major version; new optional fields don't bump it and both sides ignore unknown
// fields ([CloudJson] sets ignoreUnknownKeys). Everything the worker may leave out on an error path is optional here,
// so a short error manifest still decodes. Enum-like fields of the RESULT stay `String` on the wire (an unknown value
// from a newer worker must not fail the whole manifest); typed views sit next to them.

/** Schema names and the version this app speaks. */
object CloudSchema {
    const val VERSION = 1
    /** Versions this app can send and read (the selftest reports the worker's own list). */
    val SUPPORTED_VERSIONS: List<Int> = listOf(1)
    const val JOB = "pixl.cloudstudio.job"
    const val RESULT = "pixl.cloudstudio.result"
    const val LYRICS = "pixl.cloudstudio.lyrics"
    const val SELFTEST = "pixl.cloudstudio.selftest"
    const val ATTEMPT = "pixl.cloudstudio.attempt"
    /** `client.app` (free text on the worker side, ≤ 64 chars). */
    const val CLIENT_APP = "pixlaudio-android"
}

/** `input.op`. */
enum class CloudOp(val wire: String) {
    PROCESS("process"),
    SELFTEST("selftest"),
    BENCH("bench"),
}

/** `input.tasks` entries. */
@Serializable
enum class CloudTask(val wire: String) {
    @SerialName("instrumental") INSTRUMENTAL("instrumental"),
    @SerialName("vocals") VOCALS("vocals"),
    @SerialName("stems4") STEMS4("stems4"),
    @SerialName("lyrics") LYRICS("lyrics");

    /** The `output.put` keys a task needs (`stems4` needs drums, bass and other). */
    val outputSlots: List<String>
        get() = when (this) {
            INSTRUMENTAL -> listOf("instrumental")
            VOCALS -> listOf("vocals")
            STEMS4 -> listOf("drums", "bass", "other")
            LYRICS -> listOf("lyrics")
        }

    companion object {
        fun fromWire(value: String): CloudTask? = entries.firstOrNull { it.wire == value }
    }
}

/** `input.storage`. */
enum class CloudStorageMode(val wire: String) {
    PRESIGNED("presigned"),
    VOLUME("volume"),
}

/** `input.separation.quality`: `standard` = overlap 2, `best` = overlap 4 (songs ≤ 8 min). */
@Serializable
enum class CloudSeparationQuality(val wire: String) {
    @SerialName("standard") STANDARD("standard"),
    @SerialName("best") BEST("best");

    companion object {
        fun fromWire(value: String?): CloudSeparationQuality? = entries.firstOrNull { it.wire == value }
    }
}

/** `input.lyrics.mode`. */
@Serializable
enum class CloudLyricsMode(val wire: String) {
    /** Needs `lines`. */
    @SerialName("align") ALIGN("align"),
    /** Ignores `lines`. */
    @SerialName("transcribe") TRANSCRIBE("transcribe"),
    /** Aligns when there are lines, transcribes otherwise. */
    @SerialName("auto") AUTO("auto"),
}

/** `input.output.codec`. */
@Serializable
enum class CloudOutputCodec(val wire: String) {
    @SerialName("aac") AAC("aac"),
    @SerialName("flac") FLAC("flac");

    /** The file extension of an audio output in this codec. */
    val fileExtension: String get() = if (this == AAC) "m4a" else "flac"
}

// ─── Input (the /run body) ──────────────────────────────────────────────────────────────────────

/** The whole `/run` body: `{"input": …, "policy": …}`. */
@Serializable
data class CloudJobRequest(
    val input: CloudJobInput,
    val policy: CloudJobPolicy? = null,
)

/** RunPod's per-request execution policy (milliseconds). `ttl` covers queue time and is a hard kill. */
@Serializable
data class CloudJobPolicy(
    val ttl: Long,
    val executionTimeout: Long,
)

/** `input` for `op: "process"` (and the shared head of the other ops). */
@Serializable
data class CloudJobInput(
    val schema: String = CloudSchema.JOB,
    val v: Int = CloudSchema.VERSION,
    val op: String = CloudOp.PROCESS.wire,
    val jobKey: String? = null,
    val client: CloudClientInfo? = null,
    val storage: String? = null,
    val audio: CloudAudioInput? = null,
    val tasks: List<String>? = null,
    val separation: CloudSeparation? = null,
    val lyrics: CloudLyricsRequest? = null,
    val output: CloudOutputRequest? = null,
    /** Optional (an old app may omit it); without it the worker just processes the job. */
    val guard: CloudJobGuard? = null,
    /** `op: "bench"` only. */
    val bench: CloudBenchOptions? = null,
) {
    /** The typed tasks (unknown entries dropped). */
    val typedTasks: List<CloudTask> get() = tasks.orEmpty().mapNotNull(CloudTask::fromWire)
}

/** `input.client`. */
@Serializable
data class CloudClientInfo(
    val app: String = CloudSchema.CLIENT_APP,
    val build: String? = null,
)

/** `input.audio`. Presigned mode carries `get`/`delete`; the volume fallback carries `key` instead. */
@Serializable
data class CloudAudioInput(
    val get: String? = null,
    val delete: String? = null,
    /** Volume fallback only: `in/<jobKey>.<ext>`. */
    val key: String? = null,
    val ext: String,
    val bytes: Long,
    /** Lower-case hex. */
    val sha256: String,
    val durationMs: Long,
)

/** `input.separation`. */
@Serializable
data class CloudSeparation(val quality: String)

/** `input.lyrics`. */
@Serializable
data class CloudLyricsRequest(
    val mode: String,
    /** Optional ISO 639-1 hint. */
    val language: String? = null,
    /** `false` = plain lyrics without line times (the worker takes windows from voice activity). */
    val synced: Boolean = true,
    val lines: List<CloudLyricsInputLine>? = null,
)

/** One known lyric line sent for alignment. */
@Serializable
data class CloudLyricsInputLine(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
)

/**
 * `input.output`: codec and one presigned PUT per output slot (`instrumental`, `vocals`, `drums`, `bass`, `other`,
 * `lyrics`, `manifest`). The volume fallback has no URLs, so `put` is absent there.
 */
@Serializable
data class CloudOutputRequest(
    val codec: String,
    val kbps: Int? = null,
    val put: Map<String, String>? = null,
)

/** `input.guard`: the duplicate / poison checks the worker runs before downloading anything. */
@Serializable
data class CloudJobGuard(
    val manifestGet: String,
    val attemptGet: String,
    val attemptPut: String,
)

/** `input.bench` (`op: "bench"`): a synthetic signal of `seconds` through `stages`; `crash` only on test builds. */
@Serializable
data class CloudBenchOptions(
    val seconds: Int? = null,
    val stages: List<String>? = null,
    val crash: Boolean? = null,
)

/** `op: "selftest"` / `"bench"`: no audio, no URLs. */
@Serializable
data class CloudOpRequest(val input: CloudJobInput) {
    companion object {
        fun of(op: CloudOp, build: String): CloudOpRequest =
            CloudOpRequest(CloudJobInput(op = op.wire, client = CloudClientInfo(build = build)))
    }
}

// ─── Result (job output = manifest.json) ────────────────────────────────────────────────────────

/** `status`. */
enum class CloudResultStatus(val wire: String) {
    OK("ok"),
    /** The instrumental arrived but lyrics failed (reason in `warnings`). */
    PARTIAL("partial"),
    ERROR("error");

    companion object {
        fun fromWire(value: String?): CloudResultStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** The worker's error codes (§2.3). */
enum class CloudErrorCode(val wire: String) {
    BAD_SCHEMA("BAD_SCHEMA"),
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION"),
    BAD_OP("BAD_OP"),
    BAD_URL("BAD_URL"),
    INPUT_TOO_LARGE("INPUT_TOO_LARGE"),
    INPUT_MISMATCH("INPUT_MISMATCH"),
    TOO_LONG("TOO_LONG"),
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT"),
    DECODE_FAILED("DECODE_FAILED"),
    GPU_OOM("GPU_OOM"),
    DEADLINE("DEADLINE"),
    UPLOAD_FAILED("UPLOAD_FAILED"),
    INTERNAL("INTERNAL"),
    /** The guard refused a third delivery of the same RunPod job. */
    POISONED("POISONED"),
    /** The input GET answered 404: upload again rather than retrying blindly. */
    INPUT_MISSING("INPUT_MISSING"),
    /** The input GET failed for another reason (timeouts, 5xx) after the worker's own retries. */
    DOWNLOAD_FAILED("DOWNLOAD_FAILED");

    /** Whether the phone should try the same job again later (with the backoff ladder) rather than give up. */
    val isRetryable: Boolean
        get() = when (this) {
            GPU_OOM, DEADLINE, UPLOAD_FAILED, INTERNAL, INPUT_MISSING, BAD_URL, DOWNLOAD_FAILED -> true
            BAD_SCHEMA, UNSUPPORTED_VERSION, BAD_OP, INPUT_TOO_LARGE, INPUT_MISMATCH, TOO_LONG, UNSUPPORTED_FORMAT,
            DECODE_FAILED, POISONED -> false
        }

    /** The input has to be uploaded again before a retry. */
    val needsReupload: Boolean get() = this == INPUT_MISSING || this == INPUT_MISMATCH

    /** Plain words for the queue screen (the code stays visible next to it). */
    val message: String
        get() = when (this) {
            BAD_SCHEMA, UNSUPPORTED_VERSION, BAD_OP -> "The cloud worker doesn't understand this app version."
            BAD_URL -> "The upload links were refused or had expired."
            INPUT_TOO_LARGE -> "This song's file is too big for the cloud worker."
            INPUT_MISMATCH -> "The uploaded file didn't arrive intact."
            TOO_LONG -> "This song is longer than 15 minutes."
            UNSUPPORTED_FORMAT -> "The cloud worker can't read this audio format."
            DECODE_FAILED -> "The cloud worker couldn't decode this song."
            GPU_OOM -> "The GPU ran out of memory."
            DEADLINE -> "The job ran out of time."
            UPLOAD_FAILED -> "The worker couldn't upload the results."
            INTERNAL -> "The cloud worker hit an internal error."
            POISONED -> "This song crashed the worker twice, so it was stopped."
            INPUT_MISSING -> "The uploaded song was gone; it will be uploaded again."
            DOWNLOAD_FAILED -> "The cloud worker couldn't fetch the uploaded song."
        }

    companion object {
        /** A code this app doesn't know reads as `INTERNAL` (the schema's rule). */
        fun fromWire(value: String): CloudErrorCode = entries.firstOrNull { it.wire == value } ?: INTERNAL

        fun fromWireOrNull(value: String): CloudErrorCode? = entries.firstOrNull { it.wire == value }
    }
}

/** The result manifest (`pixl.cloudstudio.result` v1): identical to the RunPod job output and to `manifest.json`. */
@Serializable
data class CloudJobResult(
    val schema: String = CloudSchema.RESULT,
    val v: Int = CloudSchema.VERSION,
    val jobKey: String,
    val status: String,
    val error: CloudResultError? = null,
    val warnings: List<String>? = null,
    val worker: CloudWorkerInfo? = null,
    val models: CloudModelsInfo? = null,
    val input: CloudInputInfo? = null,
    val outputs: Map<String, CloudOutputFile>? = null,
    val lyrics: CloudLyricsSummary? = null,
    val timings: CloudTimings? = null,
) {
    /** The typed status (an unknown status reads as `error`). */
    val typedStatus: CloudResultStatus get() = CloudResultStatus.fromWire(status) ?: CloudResultStatus.ERROR
    /** The typed error code; a code this app doesn't know reads as `INTERNAL`. */
    val errorCode: CloudErrorCode? get() = error?.let { CloudErrorCode.fromWire(it.code) }
    /** `ok` or `partial`: something is there to import. */
    val hasResults: Boolean get() = typedStatus != CloudResultStatus.ERROR
    /** The worker flagged this result as returned by the duplicate guard. */
    val isDuplicate: Boolean get() = warnings.orEmpty().contains("duplicate")
}

/** `error` of an error manifest. */
@Serializable
data class CloudResultError(
    val code: String,
    val message: String? = null,
)

/** `worker`. */
@Serializable
data class CloudWorkerInfo(
    val version: String? = null,
    val gitSha: String? = null,
    val gpu: String? = null,
    val vramGB: Double? = null,
    val cuda: String? = null,
)

/** `models` (null entries were not used by this job). */
@Serializable
data class CloudModelsInfo(
    val separator: String? = null,
    val stems4: String? = null,
    val aligner: String? = null,
    val asr: String? = null,
)

/** `input` of the manifest: what the worker decoded. */
@Serializable
data class CloudInputInfo(
    val codec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val decodedSamples: Long? = null,
    val durationMs: Long? = null,
    /** The verified input's SHA-256 (= the job's `audio.sha256`). Older workers omit it. */
    val sha256: String? = null,
)

/** One output file in R2. */
@Serializable
data class CloudOutputFile(
    /** `out/<jobKey>/<slot>.<ext>`. */
    val key: String,
    val bytes: Long,
    val sha256: String,
    val codec: String? = null,
    val kbps: Int? = null,
    /** Always the input's sample rate. */
    val sampleRate: Int? = null,
    /** Decoded sample frames per channel at `sampleRate`, for the length check. */
    val samples: Long? = null,
)

/** `lyrics` of the manifest: where `lyrics.json` is and what it holds. */
@Serializable
data class CloudLyricsSummary(
    val key: String,
    /** Size and SHA-256 of `lyrics.json` (the import checks them like any other output). */
    val bytes: Long? = null,
    val sha256: String? = null,
    /** `aligned` or `transcribed` (machine-written text). */
    val mode: String? = null,
    val language: String? = null,
    val lines: Int? = null,
    val wordTimedLines: Int? = null,
    val words: Int? = null,
    /** The global shift the worker's offset check applied to the sent line times (§2.4 stage 6); 0 when none. */
    val offsetMs: Long? = null,
)

/** `timings` (milliseconds). `coldStartMs` is non-zero only on the first job of a worker process. */
@Serializable
data class CloudTimings(
    val coldStartMs: Long? = null,
    val downloadMs: Long? = null,
    val decodeMs: Long? = null,
    val separateMs: Long? = null,
    val stemsMs: Long? = null,
    val lyricsMs: Long? = null,
    val encodeMs: Long? = null,
    val uploadMs: Long? = null,
    val totalMs: Long? = null,
)

// ─── lyrics.json ────────────────────────────────────────────────────────────────────────────────

/** `pixl.cloudstudio.lyrics` v1. */
@Serializable
data class CloudLyricsDocument(
    val schema: String = CloudSchema.LYRICS,
    val v: Int = CloudSchema.VERSION,
    /** `aligned` or `transcribed`. */
    val mode: String,
    val language: String? = null,
    /** The global shift applied to the sent line times (0 when none). */
    val offsetMs: Long? = null,
    val lines: List<CloudLyricsLine> = emptyList(),
) {
    /** Machine-written text (shown as "AI-written lyrics"). */
    val isTranscribed: Boolean get() = mode == "transcribed"
}

/** One line: the original text unchanged, with word timing (`timing: "word"`) or only line timing (`"line"`). */
@Serializable
data class CloudLyricsLine(
    val i: Int? = null,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val timing: String? = null,
    val words: List<CloudLyricsWord>? = null,
) {
    val isWordTimed: Boolean get() = timing != "line" && !words.isNullOrEmpty()
}

/** One aligned word: its time and its UTF-16 range `[c0, c1)` in the original line text. */
@Serializable
data class CloudLyricsWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val conf: Double? = null,
    val c0: Int,
    val c1: Int,
)

// ─── selftest and attempt ───────────────────────────────────────────────────────────────────────

/**
 * A model's state in the selftest: loaded (`true`), available but loaded on first use (`"lazy"`), or missing
 * (`false`). Any other string is kept as it came.
 */
sealed interface CloudModelAvailability {
    data object Loaded : CloudModelAvailability
    data object Lazy : CloudModelAvailability
    data object Missing : CloudModelAvailability
    data class Other(val text: String) : CloudModelAvailability

    val isAvailable: Boolean get() = this == Loaded || this == Lazy

    companion object {
        fun of(element: JsonElement): CloudModelAvailability {
            val primitive = element as? JsonPrimitive ?: return Other(element.toString())
            primitive.booleanOrNull?.let { return if (it) Loaded else Missing }
            val text = if (primitive.isString) primitive.content else primitive.toString()
            return if (text == "lazy") Lazy else Other(text)
        }
    }
}

/**
 * The selftest's job output (`pixl.cloudstudio.selftest` v1): versions, GPU, models, and the job versions the
 * worker accepts (the app sends the highest one both know).
 */
@Serializable
data class CloudSelftestResult(
    val schema: String = CloudSchema.SELFTEST,
    val v: Int = CloudSchema.VERSION,
    val status: String,
    val supported: List<Int> = emptyList(),
    val ops: List<String>? = null,
    val worker: CloudWorkerInfo? = null,
    /** Model id → `true` / `false` / `"lazy"` (see [CloudModelAvailability]). */
    val models: Map<String, JsonElement>? = null,
    /** Library versions (`null` when one isn't installed). */
    val versions: Map<String, JsonElement>? = null,
    /** Languages that get word timing; the rest get line timing. */
    val wordTimingLanguages: List<String>? = null,
    val coldStartMs: Long? = null,
    /** The worker's server-side limits. Older workers omit them. */
    val caps: CloudWorkerCaps? = null,
    val error: CloudResultError? = null,
) {
    val isOk: Boolean get() = status == "ok"

    /** The highest job version both sides speak, or null when there is none. */
    val agreedVersion: Int? get() = supported.filter { it in CloudSchema.SUPPORTED_VERSIONS }.maxOrNull()

    val modelAvailability: Map<String, CloudModelAvailability>
        get() = models.orEmpty().mapValues { (_, value) -> CloudModelAvailability.of(value) }
}

/**
 * The selftest's `caps`: the endpoint's limits (its environment variables). The app can't raise them, so it keeps its
 * own uploads inside them ([CloudLimits.workerRefusal]).
 */
@Serializable
data class CloudWorkerCaps(
    val maxInputMB: Int? = null,
    val maxAudioS: Int? = null,
    /** Longest song "Best" quality is used for; longer songs are separated at Standard. */
    val bestMaxAudioS: Int? = null,
    val maxLyricsLines: Int? = null,
    val maxLyricsChars: Int? = null,
    val maxBodyKB: Int? = null,
    /** Storage hosts in the worker's allowlist; 0 means every presigned job fails `BAD_URL`. */
    val hostsConfigured: Int? = null,
)

/** `out/<jobKey>/attempt.json`, the guard's delivery counter (the phone only deletes it). */
@Serializable
data class CloudAttempt(
    val schema: String = CloudSchema.ATTEMPT,
    val v: Int = CloudSchema.VERSION,
    val runpodJobId: String,
    val attempts: Int,
    val updatedAt: String? = null,
)

// ─── Coding ─────────────────────────────────────────────────────────────────────────────────────

/**
 * JSON for the schema types. Unknown keys are ignored (the versioning rule), nulls are left out of what the phone
 * sends (the worker treats a missing optional field and an explicit null alike, and Swift's encoder omits them too),
 * and defaults are written, so `schema`, `v` and `op` always reach the worker.
 */
val CloudJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
