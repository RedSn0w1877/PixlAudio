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
    /** A song that needed more than [MAX_WORK_DURATION_MS] is cut, thrown away and would be redone: not again for hours. */
    const val TIMEOUT_COOLDOWN_MS = 6 * 60 * 60_000L
    /** The background sweep only wakes the process on a charger; every few hours is plenty for a library backlog. */
    const val SWEEP_INTERVAL_HOURS = 3L
    /** After a full library walk finds nothing to schedule, don't repeat it for this long unless something relevant changes. */
    const val IDLE_WALK_TTL_MS = 10 * 60_000L

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

    /**
     * Whether the 30 s re-check loop has anything to do. While music plays nothing can be scheduled, with
     * the app on screen nothing is started (unattended DSP competes with the UI for the same cores) and with
     * both switches off there is nothing to schedule; the real triggers (playback stopping, leaving the app,
     * a setting changing, a job finishing) still start a scan.
     */
    fun shouldPoll(enabled: Boolean, playbackActive: Boolean, appVisible: Boolean): Boolean =
        enabled && !playbackActive && !appVisible

    /**
     * Whether a scan has to ask WorkManager anything. A scan that cannot schedule (disabled, music playing,
     * app on screen) only needs the job list to clean up an automatic job that may still be running.
     */
    fun scanNeedsWorkQuery(enabled: Boolean, playbackActive: Boolean, appVisible: Boolean, automaticMayBeRunning: Boolean): Boolean =
        automaticMayBeRunning || shouldPoll(enabled, playbackActive, appVisible)

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

/**
 * How many unattended jobs were started in the current [AutomaticStudioPolicy.BACKGROUND_WINDOW_MS] window.
 * Persisted through [Store], because every WorkManager sweep may run in a fresh process: an in-memory
 * counter gave each of them a new, full budget.
 */
internal class AutomaticStudioBudget(
    private val store: Store,
    private val windowMs: Long = AutomaticStudioPolicy.BACKGROUND_WINDOW_MS
) {
    interface Store {
        /** `windowStartedMs to jobs`, or null when nothing was saved yet. */
        fun load(): Pair<Long, Int>?
        fun save(windowStartedMs: Long, jobs: Int)
    }

    private var windowStartedMs = 0L
    private var jobs = 0

    init {
        store.load()?.let { (started, count) ->
            windowStartedMs = started
            jobs = count.coerceAtLeast(0)
        }
    }

    @Synchronized
    fun jobsInWindow(nowMs: Long): Int {
        rollIfExpired(nowMs)
        return jobs
    }

    @Synchronized
    fun recordJob(nowMs: Long) {
        rollIfExpired(nowMs)
        jobs++
        store.save(windowStartedMs, jobs)
    }

    private fun rollIfExpired(nowMs: Long) {
        // A clock that moved backwards also starts a fresh window rather than freezing the budget.
        if (windowStartedMs == 0L || nowMs < windowStartedMs || nowMs - windowStartedMs >= windowMs) {
            windowStartedMs = nowMs
            jobs = 0
            store.save(windowStartedMs, jobs)
        }
    }
}

/** Remembers that a whole-library walk found nothing to schedule, so idle scans stop repeating it. */
internal class IdleWalkMemo(private val ttlMs: Long = AutomaticStudioPolicy.IDLE_WALK_TTL_MS) {
    @Volatile private var nothingToDoUntilMs = 0L

    fun shouldSkipWalk(nowMs: Long): Boolean = nowMs < nothingToDoUntilMs
    fun markNothingToDo(nowMs: Long) { nothingToDoUntilMs = nowMs + ttlMs }
    fun invalidate() { nothingToDoUntilMs = 0L }
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
