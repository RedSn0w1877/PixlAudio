package com.theveloper.pixelplay.presentation.screens.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudEndpointWatch
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudKeyChoice
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudLyricsMode
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudProgress
import com.theveloper.pixelplay.data.cloudstudio.CloudSkipReason
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import kotlin.math.max

/**
 * The words of the Cloud Studio screens, ported verbatim from the iOS app (`CloudProcessingCopy`, `CloudJobRowText`,
 * `CloudStudio.Notice`), with "iPhone" read as "phone" and "cellular" as "mobile data". English only, like the other
 * developer screens under Experimental. Pure functions: rows build their text once per change, never in a loop.
 */
object CloudStudioCopy {
    /** What the UI promises about the app being closed (design §7.4), in Android's terms, with the person's own RunPod account. */
    const val PROMISE = "Songs are processed on your RunPod account even when PixlAudio is closed. Results come back " +
        "the next time PixlAudio runs in the background or on screen, and are kept for 30 days. Uploads continue in " +
        "the background; force-stopping PixlAudio pauses them until it runs again."

    /** The same promise with PixlAudio's built-in keys (the GPU is PixlAudio's, not the person's). */
    const val BUILT_IN_PROMISE = "Songs are processed in the cloud even when PixlAudio is closed. Results come back " +
        "the next time PixlAudio runs in the background or on screen, and are kept for 30 days. Uploads continue in " +
        "the background; force-stopping PixlAudio pauses them until it runs again."

    fun promise(builtIn: Boolean): String = if (builtIn) BUILT_IN_PROMISE else PROMISE

    /** The Keys panel with the built-in keys in use. */
    val BUILT_IN_KEYS_DETAIL: String = "Nothing to fill in. Songs you send go to PixlAudio's own RunPod endpoint and R2 " +
        "bucket, up to ${CloudCost.format(CloudKeyChoice.BUILT_IN_MONTHLY_CAP_MICRO_USD)} a month from this phone."

    /** What "Process in cloud" says while Cloud processing is off (the switch lives under Sing). */
    const val OFF_MESSAGE = "Cloud processing is off. Turn it on in Settings › Sing › Cloud processing."

    /**
     * The banner on the queue. [developerMode] keeps the exact wording that points at the keys and RunPod; without it the
     * ones a listener can't act on say [UNAVAILABLE_MESSAGE] (or [CAP_MESSAGE]) instead.
     */
    fun notice(notice: CloudNotice, developerMode: Boolean = false): String = when {
        !developerMode && notice in KEY_NOTICES -> UNAVAILABLE_MESSAGE
        !developerMode && notice == CloudNotice.CAP_REACHED -> CAP_MESSAGE
        else -> developerNotice(notice)
    }

    private val KEY_NOTICES = setOf(
        CloudNotice.NOT_CONFIGURED,
        CloudNotice.KEYS_MISSING,
        CloudNotice.SECURE_STORAGE_UNAVAILABLE,
        CloudNotice.RUNPOD_KEY_REFUSED,
        CloudNotice.ENDPOINT_NOT_FOUND,
        CloudNotice.ENDPOINT_PAUSED,
    )

    private fun developerNotice(notice: CloudNotice): String = when (notice) {
        CloudNotice.OFF -> OFF_MESSAGE
        CloudNotice.NOT_CONFIGURED -> "Fill in the RunPod and storage fields in Cloud processing first."
        CloudNotice.KEYS_MISSING -> "Cloud keys missing — paste them again in Cloud processing."
        CloudNotice.SECURE_STORAGE_UNAVAILABLE ->
            "This phone's secure storage can't be opened, so the cloud keys can't be read. Paste them again in Cloud processing."
        CloudNotice.RUNPOD_KEY_REFUSED -> "RunPod refused the key. Check the Restricted key in Cloud processing."
        CloudNotice.ENDPOINT_NOT_FOUND -> "RunPod doesn't know this Endpoint ID. Check it in Cloud processing."
        CloudNotice.RATE_LIMITED -> "The cloud asked to slow down. Sending continues in a minute."
        CloudNotice.CAP_REACHED -> "This month's cloud budget is used up. Raise the monthly cap to send more."
        CloudNotice.ENDPOINT_PAUSED -> CloudEndpointWatch.PAUSED_MESSAGE
        CloudNotice.WAITING_FOR_WIFI -> "Uploads and downloads wait for Wi-Fi (Use mobile data is off)."
    }

    /**
     * Notices that point at the settings screen (the others aren't fixed there). Without [developerMode] the screen only
     * holds the Wi-Fi switch, so only that notice sends the listener there.
     */
    fun noticeOpensSettings(notice: CloudNotice, developerMode: Boolean = false): Boolean =
        if (!developerMode) notice == CloudNotice.WAITING_FOR_WIFI
        else notice != CloudNotice.OFF && notice != CloudNotice.CAP_REACHED &&
            notice != CloudNotice.ENDPOINT_PAUSED && notice != CloudNotice.RATE_LIMITED

    /** The softer tint (tertiary) for notices that aren't errors. */
    fun noticeIsSoft(notice: CloudNotice): Boolean = notice == CloudNotice.CAP_REACHED ||
        notice == CloudNotice.ENDPOINT_PAUSED || notice == CloudNotice.WAITING_FOR_WIFI || notice == CloudNotice.RATE_LIMITED

    fun batchTitle(kind: CloudBatchKind): String = when (kind) {
        CloudBatchKind.CURRENT -> "Current song"
        CloudBatchKind.MISSING_LYRICS -> "Songs without word-timed lyrics"
        CloudBatchKind.MISSING_INSTRUMENTAL -> "Songs without an instrumental"
    }

    // ─── Starting straight from a button ────────────────────────────────────────────────────

    /** The line under "Process on-device" / "Process in cloud" and on the playlist menu's cloud item. */
    const val WIFI_HINT = "Cloud yields better results but requires a Wi-Fi connection."

    const val PLAY_A_SONG_MESSAGE = "Play a song first."

    /** The month's budget is spent; with the built-in keys the cap can't be raised, so no one is sent to a setting. */
    const val CAP_MESSAGE = "This month's cloud budget is used up. Try again next month."

    const val START_FAILED_MESSAGE = "Couldn't start cloud processing. Please try again."

    /** Any problem with the keys or the cloud itself, for someone who has no keys to fix. */
    const val UNAVAILABLE_MESSAGE = "Cloud processing isn't available right now. Try again later."

    /** What a failed job says under its name when the cloud's own explanation is for developers. */
    const val FAILED_DETAIL = "Something went wrong in the cloud. Tap Retry to try again."

    fun startedMessage(count: Int, waitsForWifi: Boolean): String {
        val base = if (count == 1) "Processing 1 song in the cloud" else "Processing $count songs in the cloud"
        return if (waitsForWifi) "$base — it uploads when you're on Wi-Fi." else base
    }

    /** Why nothing was started: the first skip reason, in the engine's words ("Already waiting in the cloud queue"). */
    fun nothingToProcessMessage(reason: CloudSkipReason?): String =
        if (reason == null) "Nothing to process."
        else "Nothing to process: ${reason.label.replaceFirstChar { it.lowercase() }}."

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

    private val STEM_STAGES = setOf("stems4", "stems")

    /** [developerMode] adds what only a developer reads (the failure code, the GPU wait); the default is plain words. */
    fun status(record: CloudJobRecord, progress: Float?, developerMode: Boolean = false): String = when (record.state) {
        CloudJobState.UPLOADING, CloudJobState.DOWNLOADING ->
            if (progress != null) "${record.state.label} ${(progress * 100).toInt()}%" else record.state.label
        CloudJobState.RUNNING -> {
            val stage = record.progressStage
            if (stage != null) {
                val label = when {
                    // "Splitting stems" and "Encoding" are the worker's words, not a listener's.
                    !developerMode && stage in STEM_STAGES -> "Separating the music"
                    !developerMode && stage == "encode" -> "Finishing up"
                    else -> CloudProgress(stage, record.progressPercent).label
                }
                record.progressPercent?.let { "$label $it%" } ?: label
            } else record.state.label
        }
        CloudJobState.FAILED ->
            record.lastErrorCode?.takeIf { developerMode }?.let { "Failed · $it" } ?: record.state.label
        CloudJobState.IMPORTED -> {
            val parts = mutableListOf<String>()
            if (record.importedInstrumental) parts += "instrumental"
            if (record.importedLyrics) parts += if (record.lyricsTranscribed) "AI-written lyrics" else "word-timed lyrics"
            if (parts.isEmpty()) "Done" else "Done · " + parts.joinToString(" + ")
        }
        else -> when {
            record.nextAttemptAtMs != null && !record.lastError.isNullOrEmpty() -> "Trying again soon"
            record.state == CloudJobState.SUBMITTED && !developerMode -> "Waiting in the cloud"
            else -> record.state.label
        }
    }

    /** The cost, the GPU and the cloud's own warnings are for [developerMode] only. */
    fun detail(record: CloudJobRecord, developerMode: Boolean = false): String? {
        val lines = mutableListOf<String>()
        val error = record.lastError
        val gaveUp = record.state == CloudJobState.FAILED || record.state == CloudJobState.EXPIRED
        if (!error.isNullOrEmpty()) {
            // The cloud's own explanation names RunPod, codes and keys: developers only.
            if (developerMode) {
                if (gaveUp || record.nextAttemptAtMs != null) lines += error
            } else if (gaveUp) {
                lines += FAILED_DETAIL
            }
        }
        if (record.lowQualitySource) lines += "Low-quality source: only low-quality audio was on offer."
        if (record.state != CloudJobState.IMPORTED && record.lyricsMode == CloudLyricsMode.TRANSCRIBE &&
            CloudTask.LYRICS in record.tasks
        ) lines += "No lyrics yet: the cloud will write them (AI-written lyrics)."
        if (record.state == CloudJobState.IMPORTED && developerMode) {
            val cost = listOfNotNull(record.costMicroUsd?.let(CloudCost::format), record.gpu)
            if (cost.isNotEmpty()) lines += cost.joinToString(" · ")
            lines += record.warnings
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
