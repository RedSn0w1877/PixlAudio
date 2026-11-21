package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.brain.HostVoice

/**
 * One decision of the spoken host, for the developer log ("Host voice log"): [spoke] says the line was said to the end, [cancelled] that it was
 * started and stopped early; otherwise it was skipped. [reason] is the stable word of [HostVoice] ("transition", "too_soon", "skip", ...),
 * "speak" for a line said, "test" for the settings' test. [text] is the line (cut for display only).
 */
data class HostVoiceLogEntry(
    val atMs: Long,
    val spoke: Boolean,
    val cancelled: Boolean,
    val reason: String,
    val text: String,
)

/**
 * The last [HostVoice.LOG_SIZE] decisions, newest first. Immutable: [add] returns the next list, so a flow of it never needs a copy.
 * Nothing is stored on disk and nothing leaves the phone; it is for calibrating the host on a real phone.
 */
object HostVoiceLog {
    fun add(log: List<HostVoiceLogEntry>, entry: HostVoiceLogEntry): List<HostVoiceLogEntry> {
        val out = ArrayList<HostVoiceLogEntry>(minOf(log.size + 1, HostVoice.LOG_SIZE))
        out.add(entry)
        for (e in log) {
            if (out.size >= HostVoice.LOG_SIZE) break
            out.add(e)
        }
        return out
    }

    /** One line of the log as the developer row shows it, without the time. */
    fun describe(e: HostVoiceLogEntry): String {
        val what = when {
            e.spoke -> "spoke"
            e.cancelled -> "stopped: ${e.reason}"
            else -> "skipped: ${e.reason}"
        }
        return if (e.text.isEmpty()) what else "$what, \"${e.text}\""
    }
}
