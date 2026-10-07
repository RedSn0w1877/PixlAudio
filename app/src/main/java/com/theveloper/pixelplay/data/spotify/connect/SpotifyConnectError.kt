package com.theveloper.pixelplay.data.spotify.connect

import com.google.gson.Gson
import com.theveloper.pixelplay.data.network.spotify.SpotifyErrorEnvelope

/**
 * Why a Spotify Connect call failed, and what the user is told. Same cases, order and texts as
 * the iOS port (PixlNet `SpotifyConnectError`), so both apps say the same thing.
 *
 * The regular error object is `{ "error": { "status", "message", "reason"? } }`. Today's docs only
 * list `QUOTA_EXCEEDED` for `reason`; the player reasons (`PREMIUM_REQUIRED`, `NO_ACTIVE_DEVICE`,
 * `DEVICE_NOT_CONTROLLABLE`, …) come from the older "Player Error Reasons" and are matched together
 * with the message text. The user object lost `product` in February 2026, so a 403 is the only way
 * to learn the account isn't Premium.
 */
sealed class SpotifyConnectError {
    abstract val userMessage: String

    /** No session, or the refresh token is dead. */
    data object NotSignedIn : SpotifyConnectError() {
        override val userMessage = "Sign in to Spotify again to use Connect"
    }

    /** The token predates `user-read-playback-state` / `user-modify-playback-state`. */
    data object MissingScope : SpotifyConnectError() {
        override val userMessage = "Reconnect Spotify to use Connect"
    }

    data object PremiumRequired : SpotifyConnectError() {
        override val userMessage = "Spotify Connect needs Spotify Premium"
    }

    /** 404 `NO_ACTIVE_DEVICE`: transfer first. */
    data object NoActiveDevice : SpotifyConnectError() {
        override val userMessage = "That device isn't available. Open Spotify on it and try again"
    }

    /** The device refuses Web API commands (`is_restricted`, `DEVICE_NOT_CONTROLLABLE`, …). */
    data object DeviceRestricted : SpotifyConnectError() {
        override val userMessage = "This device can't be controlled from other apps"
    }

    data object VolumeNotSupported : SpotifyConnectError() {
        override val userMessage = "This device's volume can't be changed from here"
    }

    /** 429: wait this long (Retry-After, clamped to 1…60 s, 5 s when absent). */
    data class RateLimited(val retryAfterMs: Long) : SpotifyConnectError() {
        override val userMessage: String
            get() = "Spotify is busy. Try again in ${maxOf(1L, (retryAfterMs + 999) / 1000)} s"
    }

    /** 5xx. */
    data class Unavailable(val status: Int) : SpotifyConnectError() {
        override val userMessage = "Spotify isn't responding. Try again in a moment"
    }

    /** No HTTP response at all. */
    data class Network(val detail: String?) : SpotifyConnectError() {
        override val userMessage = "Couldn't reach Spotify"
    }

    data class Failed(val status: Int, val detail: String?) : SpotifyConnectError() {
        override val userMessage: String get() = "Spotify Connect failed (HTTP $status)"
    }

    /** The errors after which a running session can't go on. */
    val endsSession: Boolean
        get() = this == MissingScope || this == NotSignedIn || this == PremiumRequired ||
            this == NoActiveDevice || this == DeviceRestricted
}

/** Pure HTTP-status-plus-body to [SpotifyConnectError] mapping (mirrors iOS `SpotifyConnectError.from`). */
object SpotifyConnectErrors {
    private val gson = Gson()

    data class ParsedError(val status: Int?, val message: String?, val reason: String?)

    fun parseBody(body: String?): ParsedError? {
        if (body.isNullOrBlank()) return null
        return try {
            gson.fromJson(body, SpotifyErrorEnvelope::class.java)?.error?.let {
                ParsedError(it.status, it.message, it.reason)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun map(httpStatus: Int, body: String?, retryAfterHeader: String? = null): SpotifyConnectError {
        if (httpStatus == 429) return SpotifyConnectError.RateLimited(retryAfterMs(retryAfterHeader))
        val parsed = parseBody(body)
        val reason = parsed?.reason.orEmpty()
        val message = parsed?.message.orEmpty()
        val lower = message.lowercase()
        return when {
            reason == "PREMIUM_REQUIRED" || "premium" in lower -> SpotifyConnectError.PremiumRequired
            reason == "NO_ACTIVE_DEVICE" || (httpStatus == 404 && ("device" in lower || message.isEmpty())) ->
                SpotifyConnectError.NoActiveDevice
            "scope" in lower || "permissions missing" in lower -> SpotifyConnectError.MissingScope
            reason == "VOLUME_CONTROL_DISALLOW" -> SpotifyConnectError.VolumeNotSupported
            reason == "DEVICE_NOT_CONTROLLABLE" || reason == "REMOTE_CONTROL_DISALLOW" || "restricted" in lower ->
                SpotifyConnectError.DeviceRestricted
            httpStatus == 401 -> SpotifyConnectError.NotSignedIn
            httpStatus >= 500 -> SpotifyConnectError.Unavailable(httpStatus)
            else -> SpotifyConnectError.Failed(httpStatus, message.ifEmpty { null })
        }
    }

    /**
     * `Retry-After` "normally" comes with a 429, in seconds. Missing or garbled values fall back to
     * 5 s; huge ones are clamped to a minute so a bad header can't freeze the session.
     */
    fun retryAfterMs(header: String?): Long =
        (header?.trim()?.toLongOrNull()?.coerceIn(1L, MAX_RETRY_AFTER_SECONDS) ?: DEFAULT_RETRY_AFTER_SECONDS) * 1000L

    const val DEFAULT_RETRY_AFTER_SECONDS = 5L
    const val MAX_RETRY_AFTER_SECONDS = 60L
}
