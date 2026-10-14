package com.theveloper.pixelplay.data.diagnostics

/** Who built what, on what device: the header of a shared diagnostics file. No personal data. */
data class DiagnosticsInfo(
    val appId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val commit: String,
    val buildDate: String,
    val device: String,
    val android: String,
    val abi: String,
    val locale: String,
    val spotifySignInConfigured: Boolean,
    val cloudKeysBundled: Boolean,
    val generatedAtMs: Long
)

/** Pure text assembly for the shareable file, so the layout and redaction are unit tested. */
object DiagnosticsReport {

    fun render(info: DiagnosticsInfo, exitReasons: List<String>, log: String): String = buildString {
        appendLine("PixlAudio diagnostics")
        appendLine("Generated: ${DiagnosticsTime.iso(info.generatedAtMs)}")
        appendLine()
        appendLine("== Build ==")
        appendLine("App: ${info.appId} ${info.versionName} (${info.versionCode}) ${info.buildType}")
        appendLine("Commit: ${info.commit}")
        appendLine("Built: ${info.buildDate}")
        appendLine("Spotify sign-in configured: ${info.spotifySignInConfigured}")
        appendLine("Cloud keys bundled: ${info.cloudKeysBundled}")
        appendLine()
        appendLine("== Device ==")
        appendLine(info.device)
        appendLine(info.android)
        appendLine("ABI: ${info.abi}")
        appendLine("Locale: ${info.locale}")
        appendLine()
        appendLine("== Last process exits (newest first) ==")
        if (exitReasons.isEmpty()) appendLine("(none recorded)") else exitReasons.forEach { appendLine(it) }
        appendLine()
        appendLine("== Log (oldest first, WARN and above plus key events, redacted) ==")
        if (log.isBlank()) appendLine("(empty)") else append(log.trimEnd('\n')).append('\n')
    }

    /** Names for `ApplicationExitInfo.REASON_*` (API 30). */
    fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    fun importanceName(importance: Int): String = when {
        importance <= 100 -> "FOREGROUND"
        importance <= 125 -> "FOREGROUND_SERVICE"
        importance <= 200 -> "VISIBLE"
        importance <= 300 -> "SERVICE"
        importance <= 400 -> "CACHED"
        else -> "GONE"
    }

    fun describeExit(
        timestampMs: Long,
        reason: Int,
        status: Int,
        importance: Int,
        description: String?,
        rssKb: Long
    ): String {
        val text = description?.takeIf { it.isNotBlank() }
            ?.let { " desc=\"${DiagnosticsRedactor.redact(it.take(200))}\"" }
            .orEmpty()
        return "${DiagnosticsTime.iso(timestampMs)} reason=${exitReasonName(reason)} status=$status " +
            "importance=${importanceName(importance)} rssKb=$rssKb$text"
    }
}

object DiagnosticsTime {
    private val formatter = java.time.format.DateTimeFormatter.ISO_INSTANT

    fun iso(epochMs: Long): String = formatter.format(java.time.Instant.ofEpochMilli(epochMs))
}
