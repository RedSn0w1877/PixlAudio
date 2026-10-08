package com.theveloper.pixelplay.presentation.screens.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudEndpointWatch
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudLyricsMode
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudProgress
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import kotlin.math.max

/**
 * The words of the Cloud Studio screens, ported verbatim from the iOS app (`CloudProcessingCopy`, `CloudJobRowText`,
 * `CloudStudio.Notice`), with "iPhone" read as "phone" and "cellular" as "mobile data". English only, like the other
 * developer screens under Experimental. Pure functions: rows build their text once per change, never in a loop.
 */
object CloudStudioCopy {
    /** What the UI promises about the app being closed (design §7.4), in Android's terms. */
    const val PROMISE = "Songs are processed on your RunPod account even when PixlAudio is closed. Results come back " +
        "the next time PixlAudio runs in the background or on screen, and are kept for 30 days. Uploads continue in " +
        "the background; force-stopping PixlAudio pauses them until it runs again."

    fun notice(notice: CloudNotice): String = when (notice) {
        CloudNotice.OFF -> "Cloud processing is off. Nothing leaves this phone until you switch it on."
        CloudNotice.NOT_CONFIGURED -> "Fill in the RunPod and storage fields in Cloud processing first."
        CloudNotice.KEYS_MISSING -> "Cloud keys missing — paste them again in Cloud processing."
        CloudNotice.SECURE_STORAGE_UNAVAILABLE ->
            "This phone's secure storage can't be opened, so the cloud keys can't be read. Paste them again in Cloud processing."
        CloudNotice.RUNPOD_KEY_REFUSED -> "RunPod refused the key. Check the Restricted key in Cloud processing."
        CloudNotice.ENDPOINT_NOT_FOUND -> "RunPod doesn't know this Endpoint ID. Check it in Cloud processing."
        CloudNotice.RATE_LIMITED -> "RunPod asked to slow down. Sending continues in a minute."
        CloudNotice.CAP_REACHED -> "This month's cloud budget is used up. Raise the monthly cap to send more."
        CloudNotice.ENDPOINT_PAUSED -> CloudEndpointWatch.PAUSED_MESSAGE
        CloudNotice.WAITING_FOR_WIFI -> "Uploads and downloads wait for Wi-Fi (Use mobile data is off)."
    }

    /** Notices that point at the settings screen (the others aren't fixed there). */
    fun noticeOpensSettings(notice: CloudNotice): Boolean = notice != CloudNotice.OFF &&
        notice != CloudNotice.CAP_REACHED && notice != CloudNotice.ENDPOINT_PAUSED && notice != CloudNotice.RATE_LIMITED

    /** The softer tint (tertiary) for notices that aren't errors. */
    fun noticeIsSoft(notice: CloudNotice): Boolean = notice == CloudNotice.CAP_REACHED ||
        notice == CloudNotice.ENDPOINT_PAUSED || notice == CloudNotice.WAITING_FOR_WIFI || notice == CloudNotice.RATE_LIMITED

    fun batchTitle(kind: CloudBatchKind): String = when (kind) {
        CloudBatchKind.CURRENT -> "Current song"
        CloudBatchKind.MISSING_LYRICS -> "Songs without word-timed lyrics"
        CloudBatchKind.MISSING_INSTRUMENTAL -> "Songs without an instrumental"
    }

    fun dollars(microUsd: Long): String {
        val cents = (max(microUsd, 0) + 5_000) / 10_000
        val tail = cents % 100
        return "${cents / 100}.${if (tail < 10) "0" else ""}$tail"
    }

    /** µ$ per second as dollars with six decimals ("0.000192"). */
    fun priceText(microUsd: Long): String {
        val whole = microUsd / 1_000_000
        val fraction = (microUsd % 1_000_000).toString()
        return "$whole." + "0".repeat(max(0, 6 - fraction.length)) + fraction
    }

    /** "3", "3.5", "0.000192", "$1.20" → µ$; null for anything else. */
    fun parseMicroUsd(text: String): Long? {
        val trimmed = text.trim().replace("$", "").replace(",", ".")
        if (trimmed.isEmpty()) return null
        val value = trimmed.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0 || value >= 1_000) return null
        return Math.round(value * 1_000_000)
    }

    fun monthLine(committed: Long, cap: Long): String =
        "This month: ${CloudCost.format(committed)} of ${CloudCost.format(cap)} used or on its way."

    // ─── Queue rows ─────────────────────────────────────────────────────────────────────────

    fun status(record: CloudJobRecord, progress: Float?): String = when (record.state) {
        CloudJobState.UPLOADING, CloudJobState.DOWNLOADING ->
            if (progress != null) "${record.state.label} ${(progress * 100).toInt()}%" else record.state.label
        CloudJobState.RUNNING -> {
            val stage = record.progressStage
            if (stage != null) {
                val label = CloudProgress(stage, record.progressPercent).label
                record.progressPercent?.let { "$label $it%" } ?: label
            } else record.state.label
        }
        CloudJobState.FAILED -> record.lastErrorCode?.let { "Failed · $it" } ?: record.state.label
        CloudJobState.IMPORTED -> {
            val parts = mutableListOf<String>()
            if (record.importedInstrumental) parts += "instrumental"
            if (record.importedLyrics) parts += if (record.lyricsTranscribed) "AI-written lyrics" else "word-timed lyrics"
            if (parts.isEmpty()) "Done" else "Done · " + parts.joinToString(" + ")
        }
        else -> if (record.nextAttemptAtMs != null && !record.lastError.isNullOrEmpty()) "Trying again soon" else record.state.label
    }

    fun detail(record: CloudJobRecord): String? {
        val lines = mutableListOf<String>()
        val error = record.lastError
        if ((record.state == CloudJobState.FAILED || record.state == CloudJobState.EXPIRED || record.nextAttemptAtMs != null) &&
            !error.isNullOrEmpty()
        ) lines += error
        if (record.lowQualitySource) lines += "Low-quality source: only a low-bitrate stream was on offer."
        if (record.state != CloudJobState.IMPORTED && record.lyricsMode == CloudLyricsMode.TRANSCRIBE &&
            CloudTask.LYRICS in record.tasks
        ) lines += "No lyrics yet: the cloud will write them (AI-written lyrics)."
        if (record.state == CloudJobState.IMPORTED) {
            val cost = listOfNotNull(record.costMicroUsd?.let(CloudCost::format), record.gpu)
            if (cost.isNotEmpty()) lines += cost.joinToString(" · ")
            lines += record.warnings
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun sendLabel(count: Int): String = when (count) {
        0 -> "Nothing to send"
        1 -> "Send 1 song"
        else -> "Send $count songs"
    }
}
