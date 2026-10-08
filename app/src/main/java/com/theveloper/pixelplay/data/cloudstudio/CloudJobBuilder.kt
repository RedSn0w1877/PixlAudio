package com.theveloper.pixelplay.data.cloudstudio

// Builds the `/run` body for one job (design §1 step 3, §2.3), ported from the iOS app's `CloudJobBuilder.swift`:
// every worker URL is presigned at the moment of submission, valid for `ttl + executionTimeout + 1 h`, so an expired
// worker URL always means a job that never ran. The phone's own upload PUT is signed separately, right before each
// upload ([uploadUrl]).

/** Signs one object URL: (method, key, seconds valid) → URL. [CloudObjectStoring.presignedUrl] in the app. */
typealias CloudPresign = (method: String, key: String, expiresSeconds: Int) -> String?

object CloudJobBuilder {
    /**
     * The `output.put` slots a job needs: its tasks' audio slots, `lyrics` when lyrics were asked for, and always
     * `manifest`.
     */
    fun outputSlots(tasks: List<CloudTask>): List<String> {
        val slots = mutableListOf<String>()
        for (task in tasks) for (slot in task.outputSlots) if (slot !in slots) slots += slot
        slots += "manifest"
        return slots
    }

    /** `out/<jobKey>/<slot>.<ext>`: audio slots in the output codec's extension, `lyrics` and `manifest` as JSON. */
    fun outputKey(jobKey: String, slot: String, codec: CloudOutputCodec): String {
        val ext = if (slot == "lyrics" || slot == "manifest") "json" else codec.fileExtension
        return CloudKeys.output(jobKey, slot, ext)
    }

    /** The upload PUT the phone uses itself (24 h). */
    fun uploadUrl(jobKey: String, ext: String, presign: CloudPresign): String? =
        presign("PUT", CloudKeys.input(jobKey, ext), CloudTiming.UPLOAD_PRESIGN_SECONDS)

    /**
     * `input.lyrics` for a job: the known lines (when the job aligns), the language hint and whether the line
     * times can be trusted. A job set to align whose lyrics disappeared since selection asks for `auto`.
     */
    fun lyricsRequest(
        mode: CloudLyricsMode,
        lines: List<CloudLyricsInputLine>?,
        hasLineTimes: Boolean,
        language: String?,
        lyricsReferenceDurationMs: Long?,
        audioDurationMs: Long,
    ): CloudLyricsRequest {
        val known = lines?.takeIf { it.isNotEmpty() }
        val effectiveMode = if (mode == CloudLyricsMode.ALIGN && known == null) CloudLyricsMode.AUTO else mode
        val synced = known != null &&
            CloudSelector.syncedHint(hasLineTimes, lyricsReferenceDurationMs, audioDurationMs)
        return CloudLyricsRequest(
            mode = effectiveMode.wire,
            language = normalizedLanguage(language),
            synced = synced,
            lines = if (effectiveMode == CloudLyricsMode.TRANSCRIBE) null else known,
        )
    }

    /**
     * The language hint as the worker's schema wants it (`^[a-z]{2,3}([-_][A-Za-z0-9]{2,8})*$`): the primary subtag
     * lower-cased, the rest kept; null for anything else ("und", empty, malformed).
     */
    fun normalizedLanguage(language: String?): String? {
        val raw = language?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = raw.split('-', '_').toMutableList()
        val primary = parts.first().lowercase()
        if (primary.length !in 2..3 || !primary.all { it in 'a'..'z' } || primary == "und") return null
        parts[0] = primary
        for (part in parts.drop(1)) {
            if (part.length !in 2..8 || !part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return primary
        }
        return parts.joinToString("-")
    }

    /**
     * The whole `/run` body for a prepared and uploaded job, or null when its input isn't known or a URL can't be
     * signed. [lyrics] is required when the job has the lyrics task. [lastInBatch] marks the last job of a submit
     * burst ([CloudSubmitBurst]; [CloudJobRequest.markingLastInBatch] sets it on a body built earlier).
     */
    fun request(
        record: CloudJobRecord,
        build: String,
        lyrics: CloudLyricsRequest?,
        lastInBatch: Boolean = false,
        presign: CloudPresign,
    ): CloudJobRequest? {
        if (!CloudKeys.isValidJobKey(record.jobKey)) return null
        val ext = record.inputExt ?: return null
        val inputKey = record.inputKey ?: return null
        val sha = record.sha256 ?: return null
        val bytes = record.bytes ?: return null
        val durationMs = record.durationMs ?: return null
        val seconds = CloudTiming.WORKER_PRESIGN_SECONDS
        val jobKey = record.jobKey
        val get = presign("GET", inputKey, seconds) ?: return null
        val delete = presign("DELETE", inputKey, seconds) ?: return null
        val put = LinkedHashMap<String, String>()
        for (slot in outputSlots(record.tasks)) {
            put[slot] = presign("PUT", outputKey(jobKey, slot, record.outputCodec), seconds) ?: return null
        }
        val manifestKey = CloudKeys.manifest(jobKey)
        val attemptKey = CloudKeys.attempt(jobKey)
        val manifestGet = presign("GET", manifestKey, seconds) ?: return null
        val attemptGet = presign("GET", attemptKey, seconds) ?: return null
        val attemptPut = presign("PUT", attemptKey, seconds) ?: return null
        val wantsLyrics = CloudTask.LYRICS in record.tasks
        if (wantsLyrics && lyrics == null) return null
        val quality = CloudSelector.quality(record.quality, durationMs)
        val input = CloudJobInput(
            jobKey = jobKey,
            client = CloudClientInfo(build = build),
            storage = CloudStorageMode.PRESIGNED.wire,
            audio = CloudAudioInput(get = get, delete = delete, ext = ext, bytes = bytes, sha256 = sha.lowercase(),
                durationMs = durationMs),
            tasks = record.tasks.map { it.wire },
            // Lyrics alone still separate first (the aligner listens to the isolated vocals).
            separation = CloudSeparation(quality.wire),
            lyrics = if (wantsLyrics) lyrics else null,
            output = CloudOutputRequest(
                codec = record.outputCodec.wire,
                kbps = if (record.outputCodec == CloudOutputCodec.AAC) CloudLimits.OUTPUT_KBPS else null,
                put = put,
            ),
            guard = CloudJobGuard(manifestGet, attemptGet, attemptPut),
        )
        return CloudJobRequest(input, CloudJobPolicy(CloudTiming.TTL_MS, CloudTiming.EXECUTION_TIMEOUT_MS))
            .markingLastInBatch(lastInBatch)
    }

    /**
     * Every object a job may leave in the bucket (deleted after import or on cancel): its input and outputs, the
     * lyrics, the manifest and the guard's attempt marker.
     */
    fun objectKeys(record: CloudJobRecord): List<String> {
        val keys = mutableListOf<String>()
        record.inputKey?.let { keys += it }
        for (slot in outputSlots(record.tasks)) keys += outputKey(record.jobKey, slot, record.outputCodec)
        // Keys a manifest named count only inside this job's own folder.
        val prefix = CloudKeys.outputPrefix(record.jobKey)
        for (file in record.outputs.orEmpty().values.sortedBy { it.key }) {
            if (file.key.startsWith(prefix) && ".." !in file.key && file.key !in keys) keys += file.key
        }
        record.lyricsKey?.let { lyrics ->
            if (lyrics.startsWith(prefix) && ".." !in lyrics && lyrics !in keys) keys += lyrics
        }
        keys += CloudKeys.attempt(record.jobKey)
        return keys
    }
}

/**
 * The same body with `input.policy.last_in_batch` set (true) or left out (false: the worker's default, so the jobs
 * before a burst's last send exactly what they sent before the flag existed).
 */
fun CloudJobRequest.markingLastInBatch(last: Boolean): CloudJobRequest =
    copy(input = input.copy(policy = if (last) CloudJobInputPolicy(lastInBatch = true) else null))
