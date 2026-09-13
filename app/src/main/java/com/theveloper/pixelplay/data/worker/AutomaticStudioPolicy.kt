package com.theveloper.pixelplay.data.worker

internal enum class AutomaticStudioKind { LYRICS, INSTRUMENTAL }

/** Limits apply only to unattended work; manual controls keep their existing behavior. */
internal object AutomaticStudioPolicy {
    const val MAX_SONG_DURATION_MS = 6 * 60_000L
    const val MAX_WORK_DURATION_MS = 8 * 60_000L
    const val MIN_FREE_BYTES = 1_073_741_824L
    /** Unattended work is deliberately metered, but the budget is shared by a rolling window
     * rather than tied to the foreground process. This lets a large library make progress over
     * multiple charging/background windows without starting a burst of DSP work. */
    const val MAX_JOBS_PER_WINDOW = 8
    const val BACKGROUND_WINDOW_MS = 6 * 60 * 60_000L
    const val FAILURE_COOLDOWN_MS = 6 * 60 * 60_000L
    const val CATALOG_COOLDOWN_MS = 24 * 60 * 60_000L
    const val DEFERRED_COOLDOWN_MS = 2 * 60_000L

    data class Conditions(
        val appVisible: Boolean,
        val lyricsEnabled: Boolean,
        val instrumentalsEnabled: Boolean,
        val batteryPercent: Int,
        val charging: Boolean,
        val thermalStatus: Int,
        val freeBytes: Long,
        val validatedNetwork: Boolean = true
    )

    fun blockedReason(conditions: Conditions, kind: AutomaticStudioKind): String? = when {
        kind == AutomaticStudioKind.LYRICS && !conditions.lyricsEnabled -> "Automatic lyrics are off"
        kind == AutomaticStudioKind.INSTRUMENTAL && !conditions.instrumentalsEnabled -> "Automatic instrumentals are off"
        kind == AutomaticStudioKind.LYRICS && !conditions.validatedNetwork -> "Waiting for internet to find synced lyrics"
        !conditions.charging && conditions.batteryPercent < 40 -> "Waiting for charging or at least 40% battery"
        conditions.thermalStatus >= 2 -> "Waiting for the phone to cool down"
        conditions.freeBytes < MIN_FREE_BYTES -> "Waiting for at least 1 GB of free storage"
        else -> null
    }

    fun canProcessDuration(durationMs: Long): Boolean = durationMs in 1..MAX_SONG_DURATION_MS

    fun orderedIds(current: String?, recent: List<String>, favorites: List<String>, local: List<String>): List<String> =
        (listOfNotNull(current) + recent + favorites + local).filter(String::isNotBlank).distinct().take(40)

    /** Stable ranking for unattended work. Recent/played/favorite songs are useful immediately;
     * never-played songs remain eligible but intentionally sort behind them. */
    fun priority(
        songId: String,
        currentId: String?,
        favorite: Boolean,
        playCount: Int,
        lastPlayedMs: Long,
        nowMs: Long
    ): Long {
        if (songId == currentId) return 1_000_000L
        val age = (nowMs - lastPlayedMs).coerceAtLeast(0L)
        val recency = when {
            lastPlayedMs <= 0L -> 0L
            age <= 7 * 24 * 60 * 60_000L -> 50_000L
            age <= 30 * 24 * 60 * 60_000L -> 25_000L
            else -> 10_000L
        }
        return recency + (if (playCount > 0) 10_000L else 0L) +
            (if (favorite) 5_000L else 0L) + playCount.coerceIn(0, 100)
    }

    fun canSchedule(kind: AutomaticStudioKind, hasLocalAudio: Boolean, alreadyComplete: Boolean, cooldownUntil: Long, now: Long): Boolean =
        !alreadyComplete && cooldownUntil <= now && (kind == AutomaticStudioKind.LYRICS || hasLocalAudio)
}

/** A small persistent retry ledger, independent of WorkManager's retained job history. */
internal class AutomaticStudioCooldowns(initial: Map<String, Long> = emptyMap(), private val capacity: Int = 256) {
    private val deadlines = LinkedHashMap(initial.entries.sortedBy { it.value }.associate { it.toPair() })
    init {
        require(capacity > 0)
        while (deadlines.size > capacity) deadlines.remove(deadlines.keys.first())
    }
    fun until(key: String): Long = deadlines[key] ?: 0L
    fun record(key: String, deadline: Long) {
        deadlines.remove(key)
        deadlines[key] = deadline
        while (deadlines.size > capacity) deadlines.remove(deadlines.keys.first())
    }
    fun snapshot(): Map<String, Long> = deadlines.toMap()
}
