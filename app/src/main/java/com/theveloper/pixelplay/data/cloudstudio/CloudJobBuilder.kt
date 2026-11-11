package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.spatial.SpatialContract
import com.theveloper.pixelplay.data.spatial.SpatialProfile

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
        // A job with library tasks runs up to 30 minutes, and its URLs live that much longer (spec §6.2 step 3).
        val seconds = CloudTiming.workerPresignSeconds(record.tasks)
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
        val library = if (CloudTask.SPATIAL in record.tasks) {
            libraryRequest(record.library ?: return null, seconds, presign) ?: return null
        } else null
        // The codec is about the audio slots: a job with none (Spatial audio only) sends none (spec §4).
        val hasAudioSlots = record.tasks.any { task -> task.outputSlots.any { it != "lyrics" } }
        val quality = CloudSelector.quality(record.quality, durationMs)
        val input = CloudJobInput(
            jobKey = jobKey,
            client = CloudClientInfo(build = build),
            storage = CloudStorageMode.PRESIGNED.wire,
            audio = CloudAudioInput(get = get, delete = delete, ext = ext, bytes = bytes, sha256 = sha.lowercase(),
                durationMs = durationMs),
            tasks = record.tasks.map { it.wire },
            // Lyrics alone still separate first (the aligner listens to the isolated vocals). The six-stem separator of
            // a Spatial audio render follows the same setting.
            separation = CloudSeparation(quality.wire),
            lyrics = if (wantsLyrics) lyrics else null,
            library = library,
            output = CloudOutputRequest(
                codec = record.outputCodec.wire.takeIf { hasAudioSlots },
                kbps = if (hasAudioSlots && record.outputCodec == CloudOutputCodec.AAC) CloudLimits.OUTPUT_KBPS else null,
                put = put,
            ),
            guard = CloudJobGuard(manifestGet, attemptGet, attemptPut),
        )
        return CloudJobRequest(input, CloudJobPolicy(CloudTiming.TTL_MS, CloudTiming.executionTimeoutMs(record.tasks)))
            .markingLastInBatch(lastInBatch)
    }

    /**
     * `input.library` of a Spatial audio job (cloud-audio-spec §4): one render entry per profile, each with presigned
     * URLs for its canonical keys (`audioPut`, `manifestPut`, and `manifestGet` for the worker's "already there" check),
     * and `source` naming the stream the audio came from. Null when the job's facts are not valid (a bad video id, no
     * profile, an unknown or repeated one, more than six) or a URL can't be signed.
     */
    fun libraryRequest(job: CloudLibraryJob, seconds: Int, presign: CloudPresign): CloudLibraryRequest? {
        val videoId = job.videoId
        if (!CloudKeys.isValidVideoId(videoId)) return null
        val profiles = job.profiles
        if (profiles.isEmpty() || profiles.size > MAX_LIBRARY_RENDERS || profiles.toSet().size != profiles.size) return null
        if (profiles.any { SpatialProfile.fromId(it) == null }) return null
        val renders = profiles.map { profile ->
            val audioKey = CloudKeys.libraryAudio(videoId, profile)
            val manifestKey = CloudKeys.libraryManifest(videoId, profile)
            CloudLibraryRenderTarget(
                profile = profile,
                audioPut = presign("PUT", audioKey, seconds) ?: return null,
                manifestPut = presign("PUT", manifestKey, seconds) ?: return null,
                manifestGet = presign("GET", manifestKey, seconds) ?: return null,
            )
        }
        val codec = job.sourceCodec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(MAX_SOURCE_CODEC_CHARS)
        val itag = job.sourceItag?.takeIf { it >= 0 }
        return CloudLibraryRequest(
            v = SpatialContract.LIB_V,
            videoId = videoId,
            source = if (itag != null || codec != null) CloudLibrarySource(itag, codec) else null,
            renders = renders,
        )
    }

    /** The worker takes 1 to 6 render entries, one per profile. */
    const val MAX_LIBRARY_RENDERS = 6

    /** `library.source.codec` is at most 16 characters. */
    private const val MAX_SOURCE_CODEC_CHARS = 16

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
