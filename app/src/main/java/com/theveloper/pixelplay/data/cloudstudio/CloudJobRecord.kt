package com.theveloper.pixelplay.data.cloudstudio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What started a job: the person's own batch, the Sing button, or Smart auto-prepare. A record without one is a batch. */
@Serializable
enum class CloudJobOrigin {
    @SerialName("manual") MANUAL,
    @SerialName("sing") SING,
    @SerialName("auto") AUTO,
}

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

    // Lane (who started it, who goes first, who stepped aside; optional, so an older file still decodes)
    val origin: CloudJobOrigin = CloudJobOrigin.MANUAL,
    /** Set on a job that goes ahead of the rest (a Sing, a one-song "Process in cloud"); null on the others. */
    val priorityAtMs: Long? = null,
    /** The priority job this one stepped aside for: it is back at `uploaded`, keeping its upload. */
    val pausedByJobKey: String? = null,
    val pausedAtMs: Long? = null,
    /** The run RunPod cancelled never began (no `out/<jobKey>/` folder), so there is nothing stale to delete. */
    val pausedNeverStarted: Boolean? = null,
    /** The RunPod job a pause was begun for: on disk before the status check and the cancel, so a lost reply is recoverable. */
    val pauseIntentRunpodJobId: String? = null,

    // Preview clip (a Sing's first piece; optional, so an older file still decodes). To the worker a clip is just a
    // short song: the phone cuts it out of the song's own decode and plays it from where it sits in the song.
    /** Where the clip starts in the song; null on every job that isn't a clip. */
    val clipStartMs: Long? = null,
    /** Where it ends in the song (exclusive): the planned end, then the decoded one once prepared. */
    val clipEndMs: Long? = null,
    /** The song's full job the clip stands in for until that one is imported. */
    val clipOfJobKey: String? = null,

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
            CloudJobEvent.RESUBMIT, CloudJobEvent.PAUSE ->
                record.copy(runpodJobId = null, progressStage = null, progressPercent = null)
            else -> record
        }
        // The pause fields only mean something while the job waits: at the phone (`uploaded`, paused) or in RunPod's
        // queue (`submitted`, with an intent). Any other event ends them, so none outlives the wait.
        if (event != CloudJobEvent.PAUSE && hasPauseFields) record = record.releasingPause().copy(pausedNeverStarted = null)
        return record
    }

    // ─── The priority lane ──────────────────────────────────────────────────────────────────

    /** A job that goes ahead of the rest: a Sing, or a one-song "Process in cloud". */
    val isPriority: Boolean get() = origin == CloudJobOrigin.SING || priorityAtMs != null

    /** The order work is picked in on the phone: priority jobs, then the person's batches, then automatic ones. */
    val lane: Int
        get() = when {
            isPriority -> 0
            origin == CloudJobOrigin.MANUAL -> 1
            else -> 2
        }

    /** Stepped aside for a priority job and waiting, uploaded, to be sent again behind it. */
    val isPaused: Boolean get() = pausedByJobKey != null

    /**
     * A Sing's preview clip: a short instrumental-only job played until the song's full instrumental arrives. Never the
     * song's instrumental, never a row of its own on the queue, and never counted as the song's pending job.
     */
    val isClip: Boolean get() = clipStartMs != null

    private val hasPauseFields: Boolean
        get() = pausedByJobKey != null || pausedAtMs != null || pausedNeverStarted != null || pauseIntentRunpodJobId != null

    /** A pause is under way for the job's current RunPod run (its status and cancel are still to be confirmed). */
    val hasPauseIntent: Boolean
        get() = pauseIntentRunpodJobId != null && pauseIntentRunpodJobId == runpodJobId

    /** The intent to pause, to be saved before RunPod is asked anything. */
    fun beginningPause(): CloudJobRecord = copy(pauseIntentRunpodJobId = runpodJobId)

    /** The pause is off (the job started, finished, or the priority job went away): back to a plain submitted job. */
    fun clearingPauseIntent(): CloudJobRecord = copy(pauseIntentRunpodJobId = null)

    /**
     * RunPod confirmed the cancel: `submitted` goes back to `uploaded`, keeping the upload. [waitingFor] is the priority job it
     * waits for (null: no one is waiting, it just goes out again), [neverStarted] that nothing of the run reached the
     * bucket. Null when the job isn't `submitted`.
     */
    fun completingPause(waitingFor: String?, neverStarted: Boolean, nowMs: Long): CloudJobRecord? =
        applying(CloudJobEvent.PAUSE, nowMs)?.copy(
            pausedByJobKey = waitingFor, pausedAtMs = if (waitingFor != null) nowMs else null, pausedNeverStarted = neverStarted,
            pauseIntentRunpodJobId = null,
        )

    /** The wait is over (the priority job is on its way, or gone): no longer paused, but still the same unsent job. */
    fun releasingPause(): CloudJobRecord = copy(pausedByJobKey = null, pausedAtMs = null, pauseIntentRunpodJobId = null)

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
