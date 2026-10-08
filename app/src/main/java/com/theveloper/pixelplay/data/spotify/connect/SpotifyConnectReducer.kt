package com.theveloper.pixelplay.data.spotify.connect

import kotlin.math.abs

/**
 * What PixlAudio knows about the device it plays on. Times are on the local monotonic clock
 * (SystemClock.elapsedRealtime in the app). Mirrors iOS `SpotifyConnectSessionState`.
 */
data class SpotifyConnectSessionState(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    /** The `uris` last sent, and the queue index each plays. */
    val window: SpotifyConnectWindow,
    /** The window position playing now (as last seen or as a command made it). */
    val windowPosition: Int = 0,
    val isPlaying: Boolean = true,
    /** The progress at [anchorMs]; interpolated in between polls while playing. */
    val progressMs: Long = 0L,
    val anchorMs: Long,
    /** The remote item's duration (0 = unknown). */
    val durationMs: Long = 0L,
    val volumePercent: Int? = null,
    val supportsVolume: Boolean = false,
    /**
     * Until then polls may still show the state from before our last command (the Web API doesn't
     * guarantee the order of player calls): they are ignored instead of read as a change or a takeover.
     */
    val graceUntilMs: Long = 0L,
    /** Whether PixlAudio's queue has more to play after the window. */
    val hasMoreAfterWindow: Boolean = false,
    /** The URI for which the next window was already requested (once per arrival at the window's end). */
    val extendedAtUri: String? = null,
    /**
     * Until then polls keep the volume PixlAudio set: the device may not have applied the last
     * `PUT` yet, and a burst of key presses steps from the local value, not from a stale poll.
     */
    val volumeHoldUntilMs: Long = 0L,
    /**
     * The device refused a volume command (`VOLUME_CONTROL_DISALLOW`) although it reports
     * `supports_volume`: no volume control for the rest of the session, whatever later polls and
     * device lists say (else every poll would offer it again and every press would fail again).
     */
    val volumeRefused: Boolean = false
) {
    /** The queue index playing now. */
    val queueIndex: Int? get() = window.queueIndices.getOrNull(windowPosition)

    val currentUri: String? get() = window.uris.getOrNull(windowPosition)

    /** The position, interpolated from the last poll (never past the duration). */
    fun positionAt(nowMs: Long): Long {
        if (!isPlaying) return progressMs.coerceAtLeast(0L)
        val position = progressMs + (nowMs - anchorMs).coerceAtLeast(0L)
        return if (durationMs > 0) minOf(position, durationMs) else position
    }
}

/** Who or what ended the session. */
sealed interface SpotifyConnectTakeover {
    /** Another device became the active one. */
    data class OtherDevice(val name: String?) : SpotifyConnectTakeover
    /** The device plays something PixlAudio didn't send (another app, the Spotify app). */
    data object OtherContent : SpotifyConnectTakeover
    /** Nothing plays anywhere any more (204). */
    data object Stopped : SpotifyConnectTakeover

    fun message(deviceName: String): String = when (this) {
        is OtherDevice -> name?.let { "Playback moved to $it" } ?: "Playback moved to another device"
        OtherContent -> "Spotify started playing something else on $deviceName"
        Stopped -> "Playback stopped on $deviceName"
    }
}

/** What one poll means. */
data class SpotifyConnectPollOutcome(
    val change: Change,
    /** The device is on the window's last entry and the queue has more: send the next window. */
    val needsNextWindow: Boolean = false
) {
    sealed interface Change {
        /** Nothing visible changed: write nothing. */
        data object None : Change
        /** Play state, progress (beyond interpolation), duration or volume changed. */
        data object Updated : Change
        /** Another queue entry plays now. */
        data class TrackChanged(val queueIndex: Int) : Change
        /** The session is over. */
        data class TakenOver(val takeover: SpotifyConnectTakeover) : Change
        /** The device finished the last entry of PixlAudio's queue. */
        data object ReachedEnd : Change
    }
}

/** The result of folding a poll in: the new state and what it means. */
data class SpotifyConnectReduction(val state: SpotifyConnectSessionState, val outcome: SpotifyConnectPollOutcome)

/**
 * The pure reducer behind a Spotify Connect session: which queue entry plays, whether someone took
 * over, when to send the next window, and whether anything visible changed (no state write when
 * nothing did). Same rules and constants as iOS `SpotifyConnectReducer`.
 */
object SpotifyConnectReducer {
    /** A poll's progress within this of the interpolated one is not a change. */
    const val PROGRESS_TOLERANCE_MS = 1_500L
    /** Grace after a command. */
    const val COMMAND_GRACE_MS = 2_500L
    /** Grace after starting a session (the device may have to wake up and buffer). */
    const val START_GRACE_MS = 6_000L

    /** Folds a poll (`null` = 204, nothing playing) into [state]. */
    fun apply(poll: SpotifyPlaybackSnapshot?, state: SpotifyConnectSessionState, nowMs: Long): SpotifyConnectReduction {
        val inGrace = nowMs < state.graceUntilMs
        fun result(change: SpotifyConnectPollOutcome.Change, s: SpotifyConnectSessionState = state, next: Boolean = false) =
            SpotifyConnectReduction(s, SpotifyConnectPollOutcome(change, next))

        if (poll == null) {
            return result(if (inGrace) SpotifyConnectPollOutcome.Change.None
            else SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.Stopped))
        }
        val pollDeviceId = poll.device?.deviceId
        if (pollDeviceId != null && pollDeviceId != state.deviceId) {
            return result(if (inGrace) SpotifyConnectPollOutcome.Change.None
            else SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.OtherDevice(poll.device?.name)))
        }
        val uri = poll.itemUri
        val position = uri?.let { state.window.positionOf(it, state.windowPosition) }
        if (uri == null || position == null) {
            if (inGrace) return result(SpotifyConnectPollOutcome.Change.None)
            // After the last entry Spotify may go on with its own suggestions (autoplay): that is the
            // end, not a takeover.
            if (state.windowPosition == state.window.count - 1 && !state.hasMoreAfterWindow && uri != null) {
                return result(SpotifyConnectPollOutcome.Change.ReachedEnd)
            }
            return result(SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.OtherContent))
        }
        // Inside the grace period only a poll that already shows what the last command asked for counts.
        if (inGrace && position != state.windowPosition) return result(SpotifyConnectPollOutcome.Change.None)

        var s = state
        var change: SpotifyConnectPollOutcome.Change = SpotifyConnectPollOutcome.Change.None
        val wasPlaying = state.isPlaying
        if (position != s.windowPosition) {
            s = s.copy(windowPosition = position, extendedAtUri = null)
            s.queueIndex?.let { change = SpotifyConnectPollOutcome.Change.TrackChanged(it) }
        }
        val progress = poll.progressMs ?: 0L
        val duration = poll.itemDurationMs ?: s.durationMs
        val expected = s.positionAt(nowMs)
        if (poll.isPlaying != s.isPlaying || abs(progress - expected) > PROGRESS_TOLERANCE_MS ||
            duration != s.durationMs || change != SpotifyConnectPollOutcome.Change.None
        ) {
            if (change == SpotifyConnectPollOutcome.Change.None) change = SpotifyConnectPollOutcome.Change.Updated
        }
        val volume = poll.device?.volumePercent
        // Not over a value PixlAudio just set (the poll may predate the last `PUT`).
        if (volume != null && volume != s.volumePercent && nowMs >= s.volumeHoldUntilMs) {
            s = s.copy(volumePercent = volume)
            if (change == SpotifyConnectPollOutcome.Change.None) change = SpotifyConnectPollOutcome.Change.Updated
        }
        val supports = poll.device?.supportsVolume
        if (supports != null && !s.volumeRefused && supports != s.supportsVolume) {
            s = s.copy(supportsVolume = supports)
            if (change == SpotifyConnectPollOutcome.Change.None) change = SpotifyConnectPollOutcome.Change.Updated
        }
        if (change != SpotifyConnectPollOutcome.Change.None) {
            s = s.copy(isPlaying = poll.isPlaying, progressMs = progress, anchorMs = nowMs, durationMs = duration)
        }

        // The last entry stopped by itself at its start or its end: the queue is done.
        val atLast = position == s.window.count - 1
        if (atLast && !s.hasMoreAfterWindow && wasPlaying && !poll.isPlaying && !inGrace &&
            (progress <= 1_000L || (duration > 0 && progress >= duration - PROGRESS_TOLERANCE_MS))
        ) {
            return result(SpotifyConnectPollOutcome.Change.ReachedEnd, s)
        }

        var needsNext = false
        if (atLast && s.hasMoreAfterWindow && s.extendedAtUri != uri) {
            s = s.copy(extendedAtUri = uri)
            needsNext = true
        }
        return result(change, s, needsNext)
    }

    // ─── Optimistic updates (what a command will do, shown at once) ───────────────────────────

    /** A new window was sent: the device starts its first entry at [positionMs]. */
    fun sent(
        window: SpotifyConnectWindow,
        positionMs: Long,
        durationMs: Long,
        hasMore: Boolean,
        state: SpotifyConnectSessionState,
        nowMs: Long,
        grace: Long = COMMAND_GRACE_MS
    ): SpotifyConnectSessionState = state.copy(
        window = window,
        windowPosition = 0,
        isPlaying = true,
        progressMs = positionMs,
        anchorMs = nowMs,
        durationMs = durationMs,
        hasMoreAfterWindow = hasMore,
        extendedAtUri = null,
        graceUntilMs = nowMs + grace
    )

    /** A local volume change: shown at once and held against polls for [SpotifyConnectVolume.HOLD_MS]. */
    fun setVolume(percent: Int, state: SpotifyConnectSessionState, nowMs: Long): SpotifyConnectSessionState =
        state.copy(volumePercent = percent.coerceIn(0, 100), volumeHoldUntilMs = nowMs + SpotifyConnectVolume.HOLD_MS)

    /** The device accepted a volume `PUT`: polls keep the value a little longer (never shorter). */
    fun volumeSent(state: SpotifyConnectSessionState, nowMs: Long): SpotifyConnectSessionState =
        state.copy(volumeHoldUntilMs = maxOf(state.volumeHoldUntilMs, nowMs + SpotifyConnectVolume.HOLD_AFTER_SEND_MS))

    /** The device refused a volume command: no volume control until the session ends. */
    fun refuseVolume(state: SpotifyConnectSessionState): SpotifyConnectSessionState =
        state.copy(volumeRefused = true, supportsVolume = false)

    /** Whether a device that reports `supports_volume` takes volume commands in this session. */
    fun supportsVolume(reported: Boolean, state: SpotifyConnectSessionState?): Boolean =
        reported && state?.volumeRefused != true

    fun setPlaying(playing: Boolean, state: SpotifyConnectSessionState, nowMs: Long): SpotifyConnectSessionState =
        state.copy(
            progressMs = state.positionAt(nowMs),
            anchorMs = nowMs,
            isPlaying = playing,
            graceUntilMs = nowMs + COMMAND_GRACE_MS
        )

    fun seek(positionMs: Long, state: SpotifyConnectSessionState, nowMs: Long): SpotifyConnectSessionState =
        state.copy(progressMs = positionMs.coerceAtLeast(0L), anchorMs = nowMs, graceUntilMs = nowMs + COMMAND_GRACE_MS)

    /** `POST next` inside the window. */
    fun advance(state: SpotifyConnectSessionState, durationMs: Long, nowMs: Long): SpotifyConnectSessionState {
        if (state.windowPosition + 1 >= state.window.count) return state
        return state.copy(
            windowPosition = state.windowPosition + 1,
            progressMs = 0L,
            anchorMs = nowMs,
            durationMs = durationMs,
            isPlaying = true,
            extendedAtUri = null,
            graceUntilMs = nowMs + COMMAND_GRACE_MS
        )
    }

    // ─── Transport decisions ──────────────────────────────────────────────────────────────────

    /** How a skip reaches the device. */
    sealed interface Skip {
        /** `POST next`: the target is the window's next entry. */
        data object Next : Skip
        /** `PUT play` with a window starting at this queue index. */
        data class Play(val fromQueueIndex: Int) : Skip
        /** Nothing after the current entry. */
        data object None : Skip
        /** `PUT seek` to 0 (previous more than 3 s in). */
        data object Restart : Skip
    }

    /** PixlAudio's "next": the following queue entry that is on Spotify (repeat-all wraps to the first). */
    fun next(state: SpotifyConnectSessionState, slots: List<SpotifyConnectSlot>, repeatAll: Boolean): Skip {
        val current = state.queueIndex ?: return Skip.None
        if (state.windowPosition + 1 < state.window.count) return Skip.Next
        firstPlayable(slots, after = current)?.let { return Skip.Play(it) }
        if (repeatAll) firstPlayable(slots, after = -1)?.let { return Skip.Play(it) }
        return Skip.None
    }

    /**
     * PixlAudio's "previous": restart when more than 3 s in (as the engine does), else the previous
     * queue entry that is on Spotify (or not looked up yet).
     */
    fun previous(
        state: SpotifyConnectSessionState,
        slots: List<SpotifyConnectSlot>,
        nowMs: Long,
        restartThresholdMs: Long = 3_000L
    ): Skip {
        val current = state.queueIndex ?: return Skip.None
        if (state.positionAt(nowMs) > restartThresholdMs) return Skip.Restart
        var index = current - 1
        while (index >= 0) {
            val slot = slots.getOrNull(index)
            if (slot is SpotifyConnectSlot.Uri || slot == SpotifyConnectSlot.Pending) return Skip.Play(index)
            index--
        }
        return Skip.Restart
    }

    /** The first entry after [after] that is on Spotify or not looked up yet. */
    fun firstPlayable(slots: List<SpotifyConnectSlot>, after: Int): Int? {
        for (i in (after + 1) until slots.size) if (slots[i] != SpotifyConnectSlot.Skipped) return i
        return null
    }

    /**
     * The device's repeat state for PixlAudio's mode. Repeat-all isn't Spotify's `context` (the `uris`
     * start at the current entry, so `context` would loop from there): PixlAudio wraps itself.
     */
    fun remoteRepeat(repeatOne: Boolean): String = if (repeatOne) "track" else "off"
}
