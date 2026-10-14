package com.theveloper.pixelplay.data.diagnostics

import java.io.File
import java.io.FileOutputStream

/**
 * A size-capped, rotating text log on disk: two files, `diagnostics-current.log` and
 * `diagnostics-previous.log`. Lines go to the current file; when it would grow past half of
 * [maxBytes] it becomes the previous file (the older previous is deleted) and a fresh current file
 * starts. So the total on disk never exceeds [maxBytes] and the newest ~[maxBytes]/2..[maxBytes]
 * of history is always available. Every append is flushed, so a line written just before a crash
 * survives it.
 *
 * Thread-safe (all access is synchronized) and free of Android types, so it is unit tested on the
 * JVM. Callers that must not block (the UI thread) hand lines to a background executor first; see
 * [DiagnosticsLog]. I/O failures are swallowed: logging must never take the app down.
 */
class RingBufferLog(
    private val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS
) {
    private val lock = Any()
    private val current = File(directory, CURRENT_NAME)
    private val previous = File(directory, PREVIOUS_NAME)
    private var stream: FileOutputStream? = null
    private var currentSize = 0L

    init {
        require(maxBytes >= 1024) { "maxBytes too small" }
    }

    private val rotateAt: Long get() = maxBytes / 2

    /** Appends one entry (a trailing newline is added; embedded newlines are kept). */
    fun append(entry: String) {
        synchronized(lock) {
            runCatching {
                val text = (if (entry.length > maxLineChars) entry.take(maxLineChars) + " …[truncated]" else entry) + "\n"
                val bytes = text.toByteArray(Charsets.UTF_8)
                openIfNeeded()
                if (currentSize > 0 && currentSize + bytes.size > rotateAt) rotate()
                stream?.let {
                    it.write(bytes)
                    it.flush()
                    currentSize += bytes.size
                }
            }
        }
    }

    /** Everything still retained, oldest first. */
    fun readAll(): String = synchronized(lock) {
        runCatching {
            closeStream()
            buildString {
                if (previous.exists()) append(previous.readText(Charsets.UTF_8))
                if (current.exists()) append(current.readText(Charsets.UTF_8))
            }
        }.getOrDefault("")
    }

    /** Bytes currently on disk across both files. */
    fun sizeOnDisk(): Long = synchronized(lock) {
        (if (previous.exists()) previous.length() else 0L) + (if (current.exists()) current.length() else 0L)
    }

    fun clear() {
        synchronized(lock) {
            runCatching {
                closeStream()
                previous.delete()
                current.delete()
                currentSize = 0L
            }
        }
    }

    private fun openIfNeeded() {
        if (stream != null) return
        directory.mkdirs()
        currentSize = if (current.exists()) current.length() else 0L
        stream = FileOutputStream(current, true)
    }

    private fun rotate() {
        closeStream()
        previous.delete()
        current.renameTo(previous)
        currentSize = 0L
        stream = FileOutputStream(current, false)
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }

    companion object {
        const val CURRENT_NAME = "diagnostics-current.log"
        const val PREVIOUS_NAME = "diagnostics-previous.log"
        const val DEFAULT_MAX_BYTES = 1_000_000L
        const val DEFAULT_MAX_LINE_CHARS = 6_000
    }
}
