package com.theveloper.pixelplay.data.ai.local

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where the model bytes come from (OkHttp in the app, a fake in tests). */
fun interface RangedSource {
    /** Requests the file from byte [offset] (a `Range: bytes=offset-` header when > 0). */
    fun open(offset: Long): RangedResponse
}

class RangedResponse(
    val code: Int,
    /** Bytes in this response body, or -1 when the server didn't say. */
    val contentLength: Long,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

enum class DownloadFailureReason { NOT_ENOUGH_SPACE, HTTP, CORRUPT, IO }

sealed interface DownloadOutcome {
    data object Done : DownloadOutcome
    /** The worker was stopped; the `.part` file stays for the next attempt to resume. */
    data object Stopped : DownloadOutcome
    data class Failed(val reason: DownloadFailureReason, val retryable: Boolean, val detail: String? = null) : DownloadOutcome
}

/**
 * Downloads [spec] into [partFile], resuming with an HTTP Range request, verifies the size and
 * SHA-256 of the whole file, and only then renames it to [finalFile]. Pure JVM (no Android), so
 * the resume, verify and failure paths are unit-tested with a fake [RangedSource].
 *
 * - A server that answers a Range request with 200 (the whole file) instead of 206 restarts the
 *   `.part` from zero: Hugging Face's CDN advertises `Accept-Ranges: bytes`, but it can't be
 *   assumed for every edge.
 * - A size or checksum mismatch deletes the `.part`, so a corrupt file is never kept or resumed.
 */
class ModelFileDownloader(
    private val spec: DownloadedModelSpec,
    private val source: RangedSource,
    private val partFile: File,
    private val finalFile: File,
    private val freeBytes: () -> Long,
) {
    fun run(
        onProgress: (bytes: Long, total: Long) -> Unit,
        onVerifying: () -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): DownloadOutcome {
        if (finalFile.exists() && finalFile.length() == spec.sizeBytes) return DownloadOutcome.Done
        partFile.parentFile?.mkdirs()

        var existing = if (partFile.exists()) partFile.length() else 0L
        if (existing > spec.sizeBytes) {
            partFile.delete()
            existing = 0L
        }
        if (freeBytes() < requiredFreeBytes(spec.sizeBytes, existing)) {
            return DownloadOutcome.Failed(DownloadFailureReason.NOT_ENOUGH_SPACE, retryable = false)
        }

        if (existing < spec.sizeBytes) {
            val outcome = try {
                download(existing, onProgress, isStopped)
            } catch (e: IOException) {
                // Network drop mid-file: keep the .part so the retry resumes where this stopped.
                DownloadOutcome.Failed(DownloadFailureReason.IO, retryable = true, detail = e.message)
            }
            if (outcome != null) return outcome
        }

        val downloadedBytes = partFile.length()
        if (downloadedBytes < spec.sizeBytes) {
            // The body ended early without an error: keep what arrived and resume next attempt.
            return DownloadOutcome.Failed(DownloadFailureReason.IO, retryable = true, detail = "short body $downloadedBytes")
        }
        onVerifying()
        if (downloadedBytes != spec.sizeBytes) {
            partFile.delete()
            return DownloadOutcome.Failed(DownloadFailureReason.CORRUPT, retryable = true, detail = "size $downloadedBytes")
        }
        val actual = try {
            sha256Of(partFile)
        } catch (e: IOException) {
            return DownloadOutcome.Failed(DownloadFailureReason.IO, retryable = true, detail = e.message)
        }
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            partFile.delete()
            return DownloadOutcome.Failed(DownloadFailureReason.CORRUPT, retryable = true, detail = "sha256 $actual")
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            return DownloadOutcome.Failed(DownloadFailureReason.IO, retryable = true, detail = "rename failed")
        }
        return DownloadOutcome.Done
    }

    /** Returns null when the body was fully written, or the outcome that ended it early. */
    private fun download(
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit,
        isStopped: () -> Boolean,
    ): DownloadOutcome? {
        val total = spec.sizeBytes
        source.open(resumeFrom).use { response ->
            val append = when {
                resumeFrom > 0L && response.code == 206 -> true
                response.code == 200 -> false
                resumeFrom > 0L && response.code == 416 -> {
                    // "Range not satisfiable": the .part can't be continued; start over next time.
                    partFile.delete()
                    return DownloadOutcome.Failed(DownloadFailureReason.HTTP, retryable = true, detail = "HTTP 416")
                }
                else -> return DownloadOutcome.Failed(
                    DownloadFailureReason.HTTP,
                    retryable = response.code == 429 || response.code >= 500,
                    detail = "HTTP ${response.code}",
                )
            }
            var written = if (append) resumeFrom else 0L
            var lastPercent = -1
            FileOutputStream(partFile, append).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    if (isStopped()) {
                        output.flush()
                        return DownloadOutcome.Stopped
                    }
                    val read = response.body.read(buffer)
                    if (read < 0) break
                    if (written + read > total) {
                        // More bytes than the pinned file has: not the file we asked for.
                        output.close()
                        partFile.delete()
                        return DownloadOutcome.Failed(DownloadFailureReason.CORRUPT, retryable = true, detail = "oversized body")
                    }
                    output.write(buffer, 0, read)
                    written += read
                    val percent = (written * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(written, total)
                    }
                }
                output.fd.sync()
            }
        }
        return null
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024

        /** What must be free before starting: what's left to fetch plus a 10 % margin of the file. */
        fun requiredFreeBytes(sizeBytes: Long, alreadyDownloaded: Long): Long =
            (sizeBytes - alreadyDownloaded).coerceAtLeast(0L) + sizeBytes / 10

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(BUFFER_BYTES).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
