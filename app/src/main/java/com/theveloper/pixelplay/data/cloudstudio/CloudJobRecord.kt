package com.theveloper.pixelplay.data.cloudstudio

import kotlinx.serialization.Serializable

/**
 * One Cloud Studio job as the phone remembers it (design §7.1 `CloudJobRecord`): identity, request, state, retries,
 * telemetry and results. A plain serializable value, so the state rules are unit-tested and [CloudJobStore] keeps the
 * list as one JSON file (no Room table: the main database stays untouched and needs no migration).
 *
 * Every field after the identity has a default, so a record written by an older build still decodes after a field
 * is added (iOS drops a stale file instead; here an unknown or missing field never costs the person their queue).
 */
@Serializable
data class CloudJobRecord(
    // Identity
    val jobKey: String,
    val songId: String,
    val title: String = "",
    val artist: String = "",
    /** The YouTube video the audio came from (streamed and Spotify songs); re-checked on import. */
    val videoId: String? = null,
    /** The library's length of the song (cost estimates before the audio is prepared). */
    val songDurationMs: Long? = null,
    /** Songs sent together (the submission gate works per batch). */
    val batchId: String = "",
    val isStreamed: Boolean = false,

    // Request
    val tasks: List<CloudTask> = emptyList(),
    val lyricsMode: CloudLyricsMode? = null,
    val language: String? = null,
    val quality: CloudSeparationQuality = CloudSeparationQuality.STANDARD,
    val outputCodec: CloudOutputCodec = CloudOutputCodec.AAC,
    val replaceUserSynced: Boolean = false,

    // State
    val state: CloudJobState = CloudJobState.QUEUED,
    val runpodJobId: String? = null,
    val inputExt: String? = null,
    val sha256: String? = null,
    val bytes: Long? = null,
    val durationMs: Long? = null,
    /** The phone's own decoded sample frames of the uploaded audio, and their rate (the import's length check). */
    val decodedFrames: Long? = null,
    val sampleRate: Int? = null,
    /** The source was HE-AAC, below 32 kHz, or muxed video (only a low-bitrate stream was on offer). */
    val lowQualitySource: Boolean = false,
    /** Worker progress while running (`stage`, percent). */
    val progressStage: String? = null,
    val progressPercent: Int? = null,

    // Retries
    val attempts: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val lastError: String? = null,
    val lastErrorCode: String? = null,
    /** A job lost at RunPod is resubmitted once with the same key. */
    val resubmits: Int = 0,
    /** A length mismatch is redone once with FLAC output. */
    val flacRedone: Boolean = false,

    // Telemetry
    val timings: CloudTimings? = null,
    val gpu: String? = null,
    val coldStartMs: Long? = null,
    val costMicroUsd: Long? = null,
    val warnings: List<String> = emptyList(),

    // Results
    val resultStatus: String? = null,
    val outputs: Map<String, CloudOutputFile>? = null,
    val lyricsKey: String? = null,
    /** The manifest's `lyrics` entry (size and SHA-256 of `lyrics.json`, its mode and counts). */
    val lyricsFile: CloudLyricsSummary? = null,
    val lyricsTranscribed: Boolean = false,
    val importedInstrumental: Boolean = false,
    val importedLyrics: Boolean = false,

    // Timestamps (Unix ms)
    val createdAtMs: Long = 0,
    val updatedAtMs: Long? = null,
    val uploadedAtMs: Long? = null,
    val submittedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val importedAtMs: Long? = null,
    /** Last `/status` call, for the 15 s polling floor. */
    val lastPolledAtMs: Long? = null,
) {
    /** `in/<jobKey>.<ext>` once prepared. */
    val inputKey: String? get() = inputExt?.let { CloudKeys.input(jobKey, it) }

    /** The record after [event] through the state machine, or null when the event doesn't apply. */
    fun applying(event: CloudJobEvent, nowMs: Long): CloudJobRecord? {
        val next = CloudJobMachine.next(state, event) ?: return null
        var record = copy(state = next, updatedAtMs = nowMs)
        record = when (event) {
            // A step that went through ends any retry wait (the row stops saying "Trying again soon").
            CloudJobEvent.PREPARED -> record.copy(nextAttemptAtMs = null)
            CloudJobEvent.UPLOAD_FINISHED -> record.copy(uploadedAtMs = nowMs, nextAttemptAtMs = null)
            CloudJobEvent.SUBMITTED -> record.copy(submittedAtMs = nowMs)
            CloudJobEvent.IMPORTED -> record.copy(importedAtMs = nowMs)
            CloudJobEvent.REQUEUE -> record.copy(
                runpodJobId = null, progressStage = null, progressPercent = null, nextAttemptAtMs = null
            )
            CloudJobEvent.RESUBMIT -> record.copy(runpodJobId = null, progressStage = null, progressPercent = null)
            else -> record
        }
        return record
    }

    /** [applying], or this record unchanged when the event doesn't apply. */
    fun on(event: CloudJobEvent, nowMs: Long): CloudJobRecord = applying(event, nowMs) ?: this

    /** Records a failure: retryable ones wait on the backoff ladder (up to the automatic limit), others stop. */
    fun recordingFailure(message: String, code: String?, retryable: Boolean, nowMs: Long): CloudJobRecord {
        val tried = attempts + 1
        val base = copy(attempts = tried, lastError = message, lastErrorCode = code, updatedAtMs = nowMs)
        return if (retryable && tried < CloudTiming.MAX_AUTOMATIC_ATTEMPTS) {
            base.copy(nextAttemptAtMs = nowMs + CloudTiming.backoffMs(tried))
        } else {
            base.copy(nextAttemptAtMs = null).on(CloudJobEvent.FAILED, nowMs).copy(updatedAtMs = nowMs)
        }
    }

    /** A retry is due (no wait pending, or the wait is over). */
    fun isDue(nowMs: Long): Boolean = (nextAttemptAtMs ?: 0) <= nowMs

    /** Takes a finished manifest's results and telemetry. */
    fun takingResult(result: CloudJobResult, fallbackPricePerSecondMicroUsd: Long, nowMs: Long): CloudJobRecord {
        // A duplicate answer cost a few hundred ms; the first delivery's cost was already recorded. A redo (a FLAC
        // redo, a re-upload) adds its own run to what the job already cost.
        val cost = if (!result.isDuplicate) {
            (costMicroUsd ?: 0) + CloudCost.actualMicroUsd(result.timings, result.worker?.gpu, fallbackPricePerSecondMicroUsd)
        } else costMicroUsd ?: 0
        return copy(
            resultStatus = result.status,
            outputs = result.outputs,
            lyricsKey = result.lyrics?.key,
            lyricsFile = result.lyrics,
            lyricsTranscribed = result.lyrics?.mode == "transcribed",
            warnings = result.warnings.orEmpty().filter { it != "duplicate" },
            timings = result.timings,
            gpu = result.worker?.gpu,
            coldStartMs = result.timings?.coldStartMs,
            costMicroUsd = cost,
            completedAtMs = nowMs,
            updatedAtMs = nowMs,
            progressStage = null,
            progressPercent = null,
        )
    }

    /** The job as a selection plan (cost estimates): its tasks at its prepared, else library, length. */
    val plan: CloudSongPlan
        get() = CloudSongPlan(songId, tasks, lyricsMode, isStreamed, durationMs ?: songDurationMs ?: CloudCost.REFERENCE_SONG_MS)

    /** RunPod holds this job now and hasn't finished the run (a redo after an earlier result counts again). */
    val isRunningAtRunPod: Boolean
        get() {
            if (!state.isAtRunPod) return false
            val completed = completedAtMs ?: return true
            return (submittedAtMs ?: 0) > completed
        }

    /** Every result this job asked for has been imported. */
    val isFullyImported: Boolean
        get() = (CloudTask.INSTRUMENTAL !in tasks || importedInstrumental || outputs?.get("instrumental") == null) &&
            (CloudTask.LYRICS !in tasks || importedLyrics || lyricsKey == null)
}
