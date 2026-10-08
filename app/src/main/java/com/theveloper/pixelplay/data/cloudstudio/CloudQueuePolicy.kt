package com.theveloper.pixelplay.data.cloudstudio

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Cloud Studio's pure rules (design §1, §2.5, §5, §7.3–§7.5), ported from the iOS app's `CloudQueuePolicy.swift` so
// both apps behave the same against the one endpoint: object keys, the job state machine, the retry ladder,
// presigned-URL lifetimes, the batch submission gate, song selection, the cost estimate and the monthly cap, the
// import checks and retention. [CloudStudioEngine] only sequences I/O around these.

// ─── Keys ───────────────────────────────────────────────────────────────────────────────────────

/** Object keys in the bucket and the job key format. */
object CloudKeys {
    /** `in/<jobKey>.<ext>`: the uploaded song. */
    fun input(jobKey: String, ext: String): String = "in/$jobKey.$ext"
    /** `out/<jobKey>/`: everything the worker writes for a job. */
    fun outputPrefix(jobKey: String): String = "out/$jobKey/"
    fun output(jobKey: String, slot: String, ext: String): String = "out/$jobKey/$slot.$ext"
    fun manifest(jobKey: String): String = "out/$jobKey/manifest.json"
    fun attempt(jobKey: String): String = "out/$jobKey/attempt.json"
    fun lyrics(jobKey: String): String = "out/$jobKey/lyrics.json"
    /** The connection test's 1-byte object. */
    fun probe(id: String): String = "probe/$id"

    /** The worker's jobKey rule: 36 characters of `0-9 a-f -` (a lower-case UUID). */
    fun isValidJobKey(key: String): Boolean =
        key.length == 36 && key.all { it in '0'..'9' || it in 'a'..'f' || it == '-' }

    /** A new job key from a UUID string (any case). */
    fun jobKey(uuid: String): String = uuid.lowercase()

    /** The jobKey of an `out/<jobKey>/…` key or `out/<jobKey>/` prefix, if valid. */
    fun jobKeyFromOutputKey(key: String): String? {
        if (!key.startsWith("out/")) return null
        val candidate = key.removePrefix("out/").substringBefore('/')
        return candidate.takeIf(::isValidJobKey)
    }

    /** Input formats the worker accepts (`PIXL_ALLOWED_FORMATS`), by extension. */
    val INPUT_EXTENSIONS: Set<String> = setOf("m4a", "mp3", "flac", "wav", "ogg", "opus", "webm")

    /** The Content-Type of an uploaded or downloaded file. */
    fun contentType(ext: String): String = when (ext.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}

// ─── Limits and timing ──────────────────────────────────────────────────────────────────────────

/** Caps the app enforces before anything is sent (the worker enforces its own as well). */
object CloudLimits {
    /** `PIXL_MAX_AUDIO_S` 900. */
    const val MAX_DURATION_MS: Long = 900_000
    /** `PIXL_BEST_MAX_AUDIO_S` 480: `best` quality only up to 8 minutes. */
    const val BEST_QUALITY_MAX_DURATION_MS: Long = 480_000
    /**
     * `PIXL_MAX_INPUT_MB` 160 (the worker's streamed byte cap): a 15-minute song decoded to 16-bit FLAC is about
     * 90–110 MB, so the design's 60 MB (sized for AAC) would refuse long lossless uploads.
     */
    const val MAX_INPUT_BYTES: Long = 160L * 1_048_576
    const val MAX_SONGS_PER_BATCH = 200
    /** Streamed songs are fetched a few at a time, at most 50 per batch (§7.3). */
    const val MAX_STREAMED_PER_BATCH = 50
    /** `PIXL_MAX_LYRICS_LINES` / `_CHARS`. */
    const val MAX_LYRICS_LINES = 500
    const val MAX_LYRICS_CHARS = 20_000
    /** The schema's `maxLength` of one line's text. */
    const val MAX_LYRICS_LINE_CHARS = 2_000
    /** Output AAC bitrate (decision 4: AAC 256k). */
    const val OUTPUT_KBPS = 256
    /** The worker's AAC encoder keeps the input's rate only up to 96 kHz; above that the job asks for FLAC. */
    const val MAX_AAC_SAMPLE_RATE = 96_000

    /**
     * Why the endpoint would refuse a prepared upload under its own limits (the selftest's `caps`, set by its
     * environment variables), or null. The app's limits above are checked on their own; this catches an endpoint set
     * stricter than them before the upload and the GPU time are spent.
     */
    fun workerRefusal(bytes: Long, durationMs: Long, caps: CloudWorkerCaps?): String? {
        if (caps == null) return null
        val mb = caps.maxInputMB
        if (mb != null && mb > 0 && bytes > mb.toLong() * 1_048_576) {
            return "The prepared file is ${(bytes + 524_288) / 1_048_576} MB, over your cloud worker's $mb MB limit " +
                "(PIXL_MAX_INPUT_MB on the endpoint)."
        }
        val seconds = caps.maxAudioS
        if (seconds != null && seconds > 0 && durationMs > seconds.toLong() * 1000) {
            val limit = if (seconds % 60 == 0) "${seconds / 60}-minute" else "$seconds-second"
            return "This song is longer than your cloud worker's $limit limit (PIXL_MAX_AUDIO_S on the endpoint)."
        }
        return null
    }
}

/** Lifetimes and intervals (milliseconds unless named otherwise). */
object CloudTiming {
    /** `policy.ttl`: 3 days in RunPod's queue, a hard kill. */
    const val TTL_MS: Long = 3L * 86_400_000
    /** `policy.executionTimeout`: 900 s. */
    const val EXECUTION_TIMEOUT_MS: Long = 900_000
    /** The upload PUT the phone presigns for itself: 24 h. */
    const val UPLOAD_PRESIGN_SECONDS = 86_400
    /** The worker's URLs, signed at submission: `ttl + executionTimeout + 1 h` (≈ 76 h; R2's maximum is 7 d). */
    val WORKER_PRESIGN_SECONDS: Int = ((TTL_MS + EXECUTION_TIMEOUT_MS) / 1000).toInt() + 3_600
    /**
     * An input older than this is uploaded again instead of being submitted (upload age + ttl < the 7-day `in/`
     * lifecycle).
     */
    const val MAX_INPUT_AGE_AT_SUBMIT_MS: Long = 3L * 86_400_000
    /** A batch is submitted when all its uploads are done, or this long after the first one finished. */
    const val BATCH_GATE_MS: Long = 120_000
    /** `/status` for running jobs at most this often in the foreground. */
    const val STATUS_POLL_INTERVAL_MS: Long = 15_000
    /** RunPod keeps a finished job's `/status` for 30 minutes. */
    const val STATUS_RETENTION_MS: Long = 30L * 60_000
    /** Retry ladder after a failure: 1 → 5 → 15 → 60 minutes, then every hour. */
    val BACKOFF_LADDER_MS: List<Long> = listOf(60_000, 300_000, 900_000, 3_600_000)
    /** Automatic retries before a job waits for the person's Retry. */
    const val MAX_AUTOMATIC_ATTEMPTS = 4
    /** The background watch (WorkManager's periodic minimum) while jobs are in flight. */
    const val BACKGROUND_WATCH_MS: Long = 15L * 60_000
    /** Jobs queued this long while the endpoint shows no workers at all suggest a paused endpoint. */
    const val PAUSED_ENDPOINT_AFTER_MS: Long = 30L * 60_000

    /** The wait before attempt `attempts + 1` (attempts already made ≥ 1). */
    fun backoffMs(afterAttempts: Int): Long =
        BACKOFF_LADDER_MS[min(max(afterAttempts - 1, 0), BACKOFF_LADDER_MS.size - 1)]
}

// ─── State machine ──────────────────────────────────────────────────────────────────────────────

/**
 * A job's state (§7.3): queued → preparing → uploading → uploaded → submitted ("Waiting for a GPU") → running →
 * resultsReady → downloading → imported, with the side branches failed, cancelled and expired.
 */
@Serializable
enum class CloudJobState {
    @SerialName("queued") QUEUED,
    @SerialName("preparing") PREPARING,
    @SerialName("uploading") UPLOADING,
    @SerialName("uploaded") UPLOADED,
    @SerialName("submitted") SUBMITTED,
    @SerialName("running") RUNNING,
    @SerialName("resultsReady") RESULTS_READY,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("imported") IMPORTED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("expired") EXPIRED;

    /** Nothing more happens without the person (Retry or remove). */
    val isFinished: Boolean get() = this == IMPORTED || this == FAILED || this == CANCELLED || this == EXPIRED
    /** RunPod holds the job: `/status` or R2 tell what became of it. */
    val isAtRunPod: Boolean get() = this == SUBMITTED || this == RUNNING
    /** Counts as "pending" for the automatic studio and the song sheet. */
    val isPending: Boolean get() = !isFinished
    /** The phone still has work to do before RunPod sees the job. */
    val isBeforeSubmit: Boolean get() = this == QUEUED || this == PREPARING || this == UPLOADING || this == UPLOADED

    /** Plain words for the queue screen. */
    val label: String
        get() = when (this) {
            QUEUED -> "Waiting"
            PREPARING -> "Preparing the audio"
            UPLOADING -> "Uploading"
            UPLOADED -> "Uploaded"
            SUBMITTED -> "Waiting for a GPU"
            RUNNING -> "Processing"
            RESULTS_READY -> "Results ready"
            DOWNLOADING -> "Downloading results"
            IMPORTED -> "Done"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
            EXPIRED -> "Expired"
        }
}

/** What can happen to a job. */
enum class CloudJobEvent {
    PREPARE_STARTED,
    PREPARED,
    UPLOAD_STARTED,
    UPLOAD_FINISHED,
    SUBMITTED,
    STARTED,
    RESULTS_READY,
    DOWNLOAD_STARTED,
    IMPORTED,
    FAILED,
    CANCELLED,
    EXPIRED,
    /** Back to the start (Retry, a lost job resubmitted, a missing input). */
    REQUEUE,
    /** The input is in R2 but the job must be sent again (lost at RunPod, or a FLAC redo). */
    RESUBMIT,
}

object CloudJobMachine {
    /** The next state, or null when the event doesn't apply in [state] (the engine then ignores it). */
    fun next(state: CloudJobState, event: CloudJobEvent): CloudJobState? {
        val s = state
        return when (event) {
            CloudJobEvent.PREPARE_STARTED -> if (s == CloudJobState.QUEUED) CloudJobState.PREPARING else null
            CloudJobEvent.PREPARED -> if (s == CloudJobState.PREPARING) CloudJobState.UPLOADING else null
            CloudJobEvent.UPLOAD_STARTED ->
                if (s == CloudJobState.UPLOADING || s == CloudJobState.PREPARING) CloudJobState.UPLOADING else null
            CloudJobEvent.UPLOAD_FINISHED -> if (s == CloudJobState.UPLOADING) CloudJobState.UPLOADED else null
            CloudJobEvent.SUBMITTED -> if (s == CloudJobState.UPLOADED) CloudJobState.SUBMITTED else null
            CloudJobEvent.STARTED ->
                if (s == CloudJobState.SUBMITTED || s == CloudJobState.RUNNING) CloudJobState.RUNNING else null
            CloudJobEvent.RESULTS_READY -> when (s) {
                CloudJobState.SUBMITTED, CloudJobState.RUNNING, CloudJobState.UPLOADED,
                CloudJobState.DOWNLOADING -> CloudJobState.RESULTS_READY
                else -> null
            }
            CloudJobEvent.DOWNLOAD_STARTED ->
                if (s == CloudJobState.RESULTS_READY || s == CloudJobState.DOWNLOADING) CloudJobState.DOWNLOADING else null
            CloudJobEvent.IMPORTED ->
                if (s == CloudJobState.RESULTS_READY || s == CloudJobState.DOWNLOADING) CloudJobState.IMPORTED else null
            CloudJobEvent.EXPIRED -> when (s) {
                CloudJobState.SUBMITTED, CloudJobState.RUNNING, CloudJobState.UPLOADED -> CloudJobState.EXPIRED
                else -> null
            }
            CloudJobEvent.CANCELLED -> if (!s.isFinished) CloudJobState.CANCELLED else null
            CloudJobEvent.FAILED -> if (!s.isFinished) CloudJobState.FAILED else null
            CloudJobEvent.REQUEUE -> when (s) {
                CloudJobState.FAILED, CloudJobState.CANCELLED, CloudJobState.EXPIRED, CloudJobState.UPLOADED,
                CloudJobState.SUBMITTED, CloudJobState.RUNNING, CloudJobState.UPLOADING, CloudJobState.PREPARING,
                CloudJobState.RESULTS_READY, CloudJobState.DOWNLOADING -> CloudJobState.QUEUED
                else -> null
            }
            CloudJobEvent.RESUBMIT -> when (s) {
                CloudJobState.SUBMITTED, CloudJobState.RUNNING, CloudJobState.EXPIRED, CloudJobState.FAILED,
                CloudJobState.RESULTS_READY, CloudJobState.DOWNLOADING -> CloudJobState.UPLOADED
                else -> null
            }
        }
    }
}

// ─── Selection ──────────────────────────────────────────────────────────────────────────────────

/** What the library knows about a song's lyrics. */
enum class CloudLyricsState {
    NONE,
    /** Plain text, or line timing only (alignment adds word timing). */
    TEXT_OR_LINE_SYNCED,
    /** Word timing from a catalog or an earlier run: nothing to add. */
    WORD_SYNCED,
    /** The person synced it themselves. */
    USER_SYNCED,
}

/** One song's facts for selection (gathered by the app off the main thread). */
data class CloudSongFacts(
    val songId: String,
    val durationMs: Long,
    val hasInstrumental: Boolean,
    val lyrics: CloudLyricsState,
    /** A local file or a stream that can be downloaded. */
    val hasAudioSource: Boolean,
    val isStreamed: Boolean,
    val hasPendingJob: Boolean,
)

/** The person's choices (Settings › Cloud processing › Outputs). */
data class CloudSelectionOptions(
    val instrumental: Boolean = true,
    val lyrics: Boolean = true,
    /** "Write lyrics when none are found (AI transcription)". */
    val transcribeWhenMissing: Boolean = true,
    /** Re-time lyrics the person synced themselves. */
    val replaceUserSynced: Boolean = false,
)

/** Why a song wasn't sent. */
enum class CloudSkipReason(val label: String) {
    ALREADY_DONE("Already has an instrumental and word-timed lyrics"),
    PENDING_JOB("Already waiting in the cloud queue"),
    NO_AUDIO("No audio this phone can fetch"),
    TOO_LONG("Longer than 15 minutes"),
    USER_SYNCED("You synced these lyrics yourself"),
    BATCH_FULL("More than 200 songs in one batch"),
    TOO_MANY_STREAMED("More than 50 streamed songs in one batch"),
}

/** What one song needs. */
data class CloudSongPlan(
    val songId: String,
    val tasks: List<CloudTask>,
    /** `align` when the song has lyrics, `transcribe` when it has none. */
    val lyricsMode: CloudLyricsMode?,
    val isStreamed: Boolean,
    val durationMs: Long,
)

/** The outcome of selecting a batch. */
data class CloudSelection(
    val plans: List<CloudSongPlan>,
    val skipped: List<Pair<String, CloudSkipReason>>,
) {
    /** Skips grouped by reason, most common first (the confirm sheet's summary). */
    val skipCounts: List<Pair<CloudSkipReason, Int>>
        get() = skipped.groupingBy { it.second }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<CloudSkipReason, Int>> { it.second }.thenBy { it.first.name })
}

object CloudSelector {
    /** One song's plan, or why it is skipped. One job per song covers every missing task. */
    fun plan(facts: CloudSongFacts, options: CloudSelectionOptions): Pair<CloudSongPlan?, CloudSkipReason?> {
        if (facts.hasPendingJob) return null to CloudSkipReason.PENDING_JOB
        if (facts.durationMs > CloudLimits.MAX_DURATION_MS) return null to CloudSkipReason.TOO_LONG
        val tasks = mutableListOf<CloudTask>()
        if (options.instrumental && !facts.hasInstrumental) tasks += CloudTask.INSTRUMENTAL
        var mode: CloudLyricsMode? = null
        var userSyncedSkip = false
        if (options.lyrics) {
            when (facts.lyrics) {
                CloudLyricsState.NONE -> if (options.transcribeWhenMissing) mode = CloudLyricsMode.TRANSCRIBE
                CloudLyricsState.TEXT_OR_LINE_SYNCED -> mode = CloudLyricsMode.ALIGN
                CloudLyricsState.WORD_SYNCED -> Unit
                CloudLyricsState.USER_SYNCED ->
                    if (options.replaceUserSynced) mode = CloudLyricsMode.ALIGN else userSyncedSkip = true
            }
        }
        if (mode != null) tasks += CloudTask.LYRICS
        if (tasks.isEmpty()) {
            return null to if (userSyncedSkip) CloudSkipReason.USER_SYNCED else CloudSkipReason.ALREADY_DONE
        }
        if (!facts.hasAudioSource) return null to CloudSkipReason.NO_AUDIO
        return CloudSongPlan(facts.songId, tasks, mode, facts.isStreamed, facts.durationMs) to null
    }

    /** A batch in the given order, with the 200-song and 50-streamed caps. */
    fun select(songs: List<CloudSongFacts>, options: CloudSelectionOptions): CloudSelection {
        val plans = mutableListOf<CloudSongPlan>()
        val skipped = mutableListOf<Pair<String, CloudSkipReason>>()
        var streamed = 0
        val seen = HashSet<String>()
        for (facts in songs) {
            if (!seen.add(facts.songId)) continue
            val (plan, reason) = plan(facts, options)
            when {
                plan == null -> skipped += facts.songId to (reason ?: CloudSkipReason.ALREADY_DONE)
                plans.size >= CloudLimits.MAX_SONGS_PER_BATCH -> skipped += facts.songId to CloudSkipReason.BATCH_FULL
                plan.isStreamed && streamed >= CloudLimits.MAX_STREAMED_PER_BATCH ->
                    skipped += facts.songId to CloudSkipReason.TOO_MANY_STREAMED
                else -> {
                    if (plan.isStreamed) streamed++
                    plans += plan
                }
            }
        }
        return CloudSelection(plans, skipped)
    }

    /**
     * `synced: true` only when the lyrics' own timeline matches the audio: their reference duration within ±2 s of
     * the audio's (a YouTube match with an intro or a radio edit would shift every line). No reference = trust them.
     */
    fun syncedHint(hasLineTimes: Boolean, lyricsReferenceDurationMs: Long?, audioDurationMs: Long): Boolean {
        if (!hasLineTimes) return false
        if (lyricsReferenceDurationMs == null || lyricsReferenceDurationMs <= 0 || audioDurationMs <= 0) return true
        return abs(lyricsReferenceDurationMs - audioDurationMs) <= 2_000
    }

    /** `best` falls back to `standard` above 8 minutes. */
    fun quality(wanted: CloudSeparationQuality, durationMs: Long): CloudSeparationQuality =
        if (wanted == CloudSeparationQuality.BEST && durationMs > CloudLimits.BEST_QUALITY_MAX_DURATION_MS) {
            CloudSeparationQuality.STANDARD
        } else wanted
}

// ─── Batch gate ─────────────────────────────────────────────────────────────────────────────────

object CloudBatchGate {
    /** Submit a batch's uploaded jobs when every upload is done, or 2 minutes after the first one finished. */
    fun shouldSubmit(uploadsPending: Int, uploadsDone: Int, firstUploadDoneAtMs: Long?, nowMs: Long): Boolean {
        if (uploadsDone <= 0) return false
        if (uploadsPending == 0) return true
        val first = firstUploadDoneAtMs ?: return false
        return nowMs - first >= CloudTiming.BATCH_GATE_MS
    }
}

// ─── Submit burst ───────────────────────────────────────────────────────────────────────────────

/**
 * One submit pass sends the ready jobs in order, and the last of them carries `policy.last_in_batch`: the worker then
 * stops itself after that job instead of staying up, idle and billed (on 2026-10-08 one sat idle for 7+ minutes past
 * its 10 s idle timeout). Which job is the last is only known once the next one is ready to go (a later one may be
 * skipped, fail to sign or stop at the monthly cap), so each job is held until the next one is ready, or until the
 * pass ends, and only then sent: [ready] hands back the job before it (not the last), [end] the final one (the last).
 * A single song is a burst of one. Ported from the iOS app's `CloudSubmitBurst`.
 */
class CloudSubmitBurst<Item : Any> {
    private var held: Item? = null

    /** The job waiting to be sent: the next budget check counts it as already at RunPod. */
    val holding: Item? get() = held

    /** [item] is ready to send: hold it, and hand back the job held before it, to send as not the last. */
    fun ready(item: Item): Item? {
        val previous = held
        held = item
        return previous
    }

    /** The pass is over (or stopped at the cap): hand back the held job, to send as the burst's last. */
    fun end(): Item? {
        val last = held
        held = null
        return last
    }
}

// ─── Cost ───────────────────────────────────────────────────────────────────────────────────────

/** Estimates and actual costs in micro-dollars (µ$; $1 = 1,000,000 µ$). RunPod Flex prices (§6). */
object CloudCost {
    /** AMPERE_24 (L4 / A5000 / 3090): $0.000192/s — the Settings default. */
    const val DEFAULT_PRICE_PER_SECOND_MICRO_USD: Long = 192
    /** AMPERE_16 (A4000 / A4500 / RTX 4000 Ada): $0.000161/s. */
    const val AMPERE16_PRICE_PER_SECOND_MICRO_USD: Long = 161
    /** ADA_24 (4090): $1.10/h. */
    const val ADA24_PRICE_PER_SECOND_MICRO_USD: Long = 306
    /** The default app cap: $3 a month. */
    const val DEFAULT_MONTHLY_CAP_MICRO_USD: Long = 3_000_000

    /** Billed GPU seconds per warm song (§6): instrumental + aligned lyrics. */
    const val WARM_SECONDS_STANDARD: Long = 20
    const val EXTRA_SECONDS_TRANSCRIBE: Long = 10
    const val EXTRA_SECONDS_BEST: Long = 12
    const val EXTRA_SECONDS_STEMS: Long = 5
    /** One cold start per batch: model load plus the 10 s idle timeout. */
    const val COLD_START_SECONDS: Long = 35
    /** The song length §6's figures are for (4 minutes). */
    const val REFERENCE_SONG_MS: Long = 240_000

    /** The per-second price for a GPU name from a manifest (`worker.gpu`), else [fallback]. */
    fun pricePerSecondMicroUsd(gpu: String?, fallback: Long = DEFAULT_PRICE_PER_SECOND_MICRO_USD): Long {
        val name = gpu?.uppercase()?.takeIf { it.isNotEmpty() } ?: return fallback
        if ("4090" in name) return ADA24_PRICE_PER_SECOND_MICRO_USD
        if ("A4000" in name || "A4500" in name || "RTX 4000" in name || "RTX 2000" in name) {
            return AMPERE16_PRICE_PER_SECOND_MICRO_USD
        }
        if ("L4" in name || "A5000" in name || "3090" in name) return DEFAULT_PRICE_PER_SECOND_MICRO_USD
        return fallback
    }

    /** Estimated GPU seconds for one song's plan (scaled by length: the figures are for a 4-minute song). */
    fun estimatedSeconds(plan: CloudSongPlan, quality: CloudSeparationQuality): Long {
        var seconds = WARM_SECONDS_STANDARD
        if (plan.lyricsMode == CloudLyricsMode.TRANSCRIBE) seconds += EXTRA_SECONDS_TRANSCRIBE
        if (CloudTask.STEMS4 in plan.tasks) seconds += EXTRA_SECONDS_STEMS
        if (CloudSelector.quality(quality, plan.durationMs) == CloudSeparationQuality.BEST) seconds += EXTRA_SECONDS_BEST
        val scale = max(plan.durationMs.toDouble() / REFERENCE_SONG_MS.toDouble(), 0.25)
        return ceil(seconds.toDouble() * scale).toLong()
    }

    /** A batch's estimate: one cold start plus every song warm. */
    fun estimateMicroUsd(plans: List<CloudSongPlan>, quality: CloudSeparationQuality, pricePerSecondMicroUsd: Long): Long {
        if (plans.isEmpty()) return 0
        val seconds = COLD_START_SECONDS + plans.sumOf { estimatedSeconds(it, quality) }
        return seconds * max(pricePerSecondMicroUsd, 0)
    }

    /** What a finished job cost: its billed time (total + its share of a cold start) at its GPU's price. */
    fun actualMicroUsd(timings: CloudTimings?, gpu: String?, fallbackPricePerSecondMicroUsd: Long): Long {
        if (timings == null) return 0
        val ms = max(timings.totalMs ?: 0, 0) + max(timings.coldStartMs ?: 0, 0)
        val price = pricePerSecondMicroUsd(gpu, fallbackPricePerSecondMicroUsd)
        return (ms * price + 999) / 1000
    }

    /** "$0.39", "<$0.01". */
    fun format(microUsd: Long): String {
        if (microUsd in 1 until 10_000) return "<$0.01"
        val cents = (microUsd + 5_000) / 10_000
        val tail = cents % 100
        return "$${cents / 100}.${if (tail < 10) "0" else ""}$tail"
    }
}

/** The monthly cap (§5, app guards): after it, submissions stop. */
object CloudBudget {
    /** Cost recorded by jobs completed since [monthStartMs]. */
    fun spentMicroUsd(records: List<CloudJobRecord>, monthStartMs: Long): Long = records.sumOf { record ->
        val done = record.completedAtMs
        if (done != null && done >= monthStartMs) max(record.costMicroUsd ?: 0, 0) else 0L
    }

    /**
     * What the month has used or will use: the recorded cost of jobs completed this month, plus the estimate of every
     * job RunPod is running or holding now, so a large batch can't overshoot the cap before its first result arrives.
     */
    fun committedMicroUsd(records: List<CloudJobRecord>, monthStartMs: Long, pricePerSecondMicroUsd: Long): Long {
        val price = max(pricePerSecondMicroUsd, 0)
        val inFlight = records.filter { it.isRunningAtRunPod }
            .sumOf { CloudCost.estimatedSeconds(it.plan, it.quality) * price }
        return spentMicroUsd(records, monthStartMs) + inFlight
    }

    fun remainingMicroUsd(capMicroUsd: Long, spentMicroUsd: Long): Long = max(capMicroUsd - spentMicroUsd, 0)

    /** A batch may go out when its estimate fits what is left this month. */
    fun allows(estimateMicroUsd: Long, capMicroUsd: Long, spentMicroUsd: Long): Boolean =
        estimateMicroUsd <= remainingMicroUsd(capMicroUsd, spentMicroUsd)

    /** Start of the month containing [nowMs] in [zone] (the phone's own calendar month). */
    fun monthStartMs(nowMs: Long, zone: ZoneId = ZoneOffset.UTC): Long {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

// ─── Batch estimate (the confirm sheet) ─────────────────────────────────────────────────────────

/**
 * The confirm sheet's figures for a batch (design §7.3): songs, minutes, upload size, estimated cost and what's left
 * of the month's cap.
 */
data class CloudBatchEstimate(
    val songs: Int,
    val streamedSongs: Int,
    val totalDurationMs: Long,
    val uploadBytes: Long,
    val costMicroUsd: Long,
    val remainingMicroUsd: Long,
    val capMicroUsd: Long,
) {
    /** The batch fits what's left of this month's cap. */
    val fitsCap: Boolean get() = costMicroUsd <= remainingMicroUsd
    /** Whole minutes, rounded up. */
    val minutes: Long get() = (totalDurationMs + 59_999) / 60_000
    /** Megabytes, rounded up. */
    val uploadMb: Long get() = (uploadBytes + 1_048_575) / 1_048_576

    companion object {
        fun make(
            plans: List<CloudSongPlan>,
            quality: CloudSeparationQuality,
            pricePerSecondMicroUsd: Long,
            committedMicroUsd: Long,
            capMicroUsd: Long,
        ): CloudBatchEstimate {
            var duration = 0L
            var bytes = 0L
            var streamed = 0
            for (plan in plans) {
                duration += max(plan.durationMs, 0)
                bytes += CloudUploadEstimate.bytes(plan.durationMs)
                if (plan.isStreamed) streamed++
            }
            val cost = CloudCost.estimateMicroUsd(plans, quality, pricePerSecondMicroUsd)
            val remaining = CloudBudget.remainingMicroUsd(capMicroUsd, committedMicroUsd)
            return CloudBatchEstimate(plans.size, streamed, duration, bytes, cost, remaining, capMicroUsd)
        }
    }
}

/**
 * The size of an upload before it is prepared (§1 step 1): 16-bit stereo FLAC of the decoded samples, about 60 % of
 * PCM (25–35 MB for 4 minutes at 44.1 kHz). An AAC-LC song that goes up as it is will be smaller, so the confirm
 * sheet's figure is an upper bound.
 */
object CloudUploadEstimate {
    const val FLAC_FRACTION = 0.6

    fun bytes(durationMs: Long, sampleRate: Int = 44_100): Long {
        val pcm = max(durationMs, 0).toDouble() / 1000.0 * sampleRate * 2 * 2
        return ceil(pcm * FLAC_FRACTION).toLong()
    }
}

// ─── Import checks ──────────────────────────────────────────────────────────────────────────────

object CloudImportCheck {
    /** One AAC frame of slack (priming / remainder). */
    const val SAMPLE_TOLERANCE_FRAMES: Long = 1024
    /** The rate both sides are compared at. */
    const val COMPARE_SAMPLE_RATE: Long = 44_100

    /**
     * A manifest found in the bucket belongs to the file uploaded now: a manifest naming another input (the song
     * was prepared again since) is an earlier attempt's and is not taken. Older workers don't name the input.
     */
    fun manifestDescribesUpload(result: CloudJobResult, uploadedSha256: String?): Boolean {
        val named = result.input?.sha256 ?: return true
        val uploaded = uploadedSha256 ?: return true
        return named.equals(uploaded, ignoreCase = true)
    }

    /** The downloaded file is the one the manifest describes. */
    fun matches(expectedBytes: Long, expectedSha256: String, actualBytes: Long, actualSha256: String): Boolean =
        expectedBytes == actualBytes && expectedSha256.equals(actualSha256, ignoreCase = true)

    /**
     * The result's length equals the phone's own decode of the source within ±1 AAC frame (R13), comparing at a
     * common 44.1 kHz.
     */
    fun samplesMatch(sourceFrames: Long, sourceSampleRate: Double, resultFrames: Long, resultSampleRate: Double): Boolean {
        if (sourceFrames <= 0 || resultFrames <= 0 || sourceSampleRate <= 0 || resultSampleRate <= 0) return false
        val source = sourceFrames.toDouble() * COMPARE_SAMPLE_RATE / sourceSampleRate
        val result = resultFrames.toDouble() * COMPARE_SAMPLE_RATE / resultSampleRate
        return abs(source - result) <= SAMPLE_TOLERANCE_FRAMES + 1
    }
}

// ─── Retention and lost jobs ────────────────────────────────────────────────────────────────────

object CloudRetention {
    /** Job history is kept 30 days after import, then pruned. */
    const val KEEP_IMPORTED_MS: Long = 30L * 86_400_000
    /** Failed / cancelled / expired records go after 30 days too. */
    const val KEEP_FINISHED_MS: Long = 30L * 86_400_000
    /** R2 lifecycle backstops (owner step D8). */
    const val INPUT_LIFECYCLE_DAYS = 7
    const val OUTPUT_LIFECYCLE_DAYS = 30

    fun shouldPrune(record: CloudJobRecord, nowMs: Long): Boolean = when (record.state) {
        CloudJobState.IMPORTED -> nowMs - (record.importedAtMs ?: record.createdAtMs) > KEEP_IMPORTED_MS
        CloudJobState.FAILED, CloudJobState.CANCELLED, CloudJobState.EXPIRED ->
            nowMs - (record.updatedAtMs ?: record.createdAtMs) > KEEP_FINISHED_MS
        else -> false
    }

    /** A submitted job whose ttl (plus its execution time) has passed without a manifest in R2 is lost. */
    fun isPastTtl(submittedAtMs: Long, nowMs: Long): Boolean =
        nowMs - submittedAtMs > CloudTiming.TTL_MS + CloudTiming.EXECUTION_TIMEOUT_MS

    /** The uploaded input is too old to submit (upload again first). */
    fun inputTooOldToSubmit(uploadedAtMs: Long?, nowMs: Long): Boolean =
        uploadedAtMs == null || nowMs - uploadedAtMs > CloudTiming.MAX_INPUT_AGE_AT_SUBMIT_MS
}

/** Paused-endpoint detection (§7.4, risk R5). */
object CloudEndpointWatch {
    /**
     * Jobs have waited more than 30 minutes while the endpoint shows no idle and no running workers: RunPod paused
     * it, or no GPU is free in its pools.
     */
    fun looksPaused(oldestWaitingSinceMs: Long?, nowMs: Long, health: RunPodHealth): Boolean {
        val since = oldestWaitingSinceMs ?: return false
        if (nowMs - since <= CloudTiming.PAUSED_ENDPOINT_AFTER_MS) return false
        return health.idleWorkers == 0 && health.runningWorkers == 0 && health.initializingWorkers == 0
    }

    const val PAUSED_MESSAGE = "RunPod paused this endpoint, or no GPU is free right now. Run the keepalive " +
        "workflow on GitHub, or wait and try again later."
}
