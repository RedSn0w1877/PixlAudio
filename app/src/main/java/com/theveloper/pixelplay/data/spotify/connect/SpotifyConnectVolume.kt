package com.theveloper.pixelplay.data.spotify.connect

/**
 * The phone's volume keys drive the Spotify Connect device (owner decision 2026-10-07, shared with
 * iOS `SpotifyConnectVolumeKeys`): 5 % per press, only devices that take volume commands, never a
 * Smartphone/Tablet target. On Android the keys reach the device through the MediaSession's remote
 * volume ([com.theveloper.pixelplay.data.service.player.SpotifyConnectSessionPlayer]); everything
 * decided here is pure and unit-tested.
 */
object SpotifyConnectVolume {
    /** Percent per key press (iOS `SpotifyConnectVolumeKeys.step`). */
    const val STEP_PERCENT = 5

    /**
     * The MediaSession's remote volume scale: 0…20, one unit per press. Android's volume panel
     * shows `current + 1 unit` for a second after each press (MediaSessionRecord's optimistic
     * volume); with the old 0…100 scale and 5-point presses it showed 51, 52, 53… while the device
     * was at 70, then jumped.
     */
    const val REMOTE_STEPS = 100 / STEP_PERCENT

    /** What a press steps from when the device never reported its volume. */
    const val DEFAULT_PERCENT = 50

    /** After a local change, polls keep the local value this long (the device may not have it yet). */
    const val HOLD_MS = 3_000L

    /** After the device accepted a volume `PUT`, polls keep the local value at least this much longer. */
    const val HOLD_AFTER_SEND_MS = 1_500L

    /** At most one volume `PUT` this often; a held key changes the volume about 20 times a second. */
    const val MIN_SEND_INTERVAL_MS = 300L

    /** A 429 shorter than this waits silently; a longer one says so once. */
    const val RATE_LIMIT_TOAST_MIN_MS = 3_000L

    /**
     * Whether the volume keys drive the device. A phone or tablet target is usually this phone's
     * own Spotify app (or another phone), whose audio already follows that phone's own volume, so
     * the keys keep changing this phone's media volume there.
     */
    fun keysControlDevice(type: String?, supportsVolume: Boolean): Boolean {
        if (!supportsVolume) return false
        val kind = SpotifyDeviceKind.fromApiType(type)
        return kind != SpotifyDeviceKind.SMARTPHONE && kind != SpotifyDeviceKind.TABLET
    }

    /** Percent → remote volume units (round half up, so `toSteps(p + 5) == toSteps(p) + 1`). */
    fun percentToSteps(percent: Int?): Int =
        ((percent ?: DEFAULT_PERCENT).coerceIn(0, 100) + STEP_PERCENT / 2) / STEP_PERCENT

    /** Remote volume units → percent (the system panel's slider). */
    fun stepsToPercent(steps: Int): Int = steps.coerceIn(0, REMOTE_STEPS) * STEP_PERCENT

    /** The volume after [presses] key presses (negative = down) from [percent]. */
    fun adjusted(percent: Int?, presses: Int): Int =
        ((percent ?: DEFAULT_PERCENT) + presses * STEP_PERCENT).coerceIn(0, 100)

    /** What the MediaSession reports for the device's volume. */
    data class Remote(val maxVolume: Int, val volume: Int, val adjustable: Boolean)

    /**
     * Null for a phone or tablet target: the session keeps the local player's DeviceInfo, so the
     * keys change this phone's media volume. A device without volume control is a FIXED remote
     * (max 0, today's behaviour); otherwise the 0…[REMOTE_STEPS] scale.
     */
    fun remoteFor(deviceType: String?, supportsVolume: Boolean, percent: Int?): Remote? {
        val kind = SpotifyDeviceKind.fromApiType(deviceType)
        if (kind == SpotifyDeviceKind.SMARTPHONE || kind == SpotifyDeviceKind.TABLET) return null
        if (!supportsVolume) return Remote(maxVolume = 0, volume = 0, adjustable = false)
        return Remote(maxVolume = REMOTE_STEPS, volume = percentToSteps(percent), adjustable = true)
    }
}

/**
 * When a device volume `PUT` goes out (same policy as iOS `SpotifyConnectVolumeLane`): the first
 * change at once (leading edge), then at most one request every [SpotifyConnectVolume.MIN_SEND_INTERVAL_MS]
 * with the latest value, one at a time, and nothing before a Retry-After gate opens. Volume has its
 * own lane: it never waits behind a window send's searches in the command queue.
 */
data class SpotifyConnectVolumeLane(
    /** The latest value not sent yet. */
    val pending: Int? = null,
    /** The value of the request in flight. */
    val inFlight: Int? = null,
    val lastSentAtMs: Long? = null,
    val notBeforeMs: Long = 0L,
) {
    sealed interface Action {
        /** Send this percent now. */
        data class Send(val percent: Int) : Action
        /** Something is waiting: ask again after this many milliseconds. */
        data class Wait(val ms: Long) : Action
        /** Nothing to send, or a request is in flight. */
        data object Idle : Action
    }

    val isIdle: Boolean get() = pending == null && inFlight == null

    fun enqueue(percent: Int): SpotifyConnectVolumeLane = copy(pending = percent.coerceIn(0, 100))

    /** The next step at [nowMs]: the new lane, and what to do. */
    fun next(nowMs: Long): Pair<SpotifyConnectVolumeLane, Action> {
        val value = pending
        if (inFlight != null || value == null) return this to Action.Idle
        var earliest = notBeforeMs
        lastSentAtMs?.let { earliest = maxOf(earliest, it + SpotifyConnectVolume.MIN_SEND_INTERVAL_MS) }
        if (nowMs < earliest) return this to Action.Wait(earliest - nowMs)
        return copy(pending = null, inFlight = value, lastSentAtMs = nowMs) to Action.Send(value)
    }

    /** The request went through. */
    fun completed(): SpotifyConnectVolumeLane = copy(inFlight = null)

    /** Any other failure: that value is dropped (a newer one still goes). */
    fun failed(): SpotifyConnectVolumeLane = copy(inFlight = null)

    /** 429: the value goes again once the gate opens, unless a newer one is already waiting. */
    fun rateLimited(retryAfterMs: Long, nowMs: Long): SpotifyConnectVolumeLane =
        copy(pending = pending ?: inFlight, inFlight = null).gate(nowMs + retryAfterMs.coerceAtLeast(0L))

    /** A Retry-After gate another request hit (the poller): no send before it opens. */
    fun gate(untilMs: Long): SpotifyConnectVolumeLane = copy(notBeforeMs = maxOf(notBeforeMs, untilMs))
}
