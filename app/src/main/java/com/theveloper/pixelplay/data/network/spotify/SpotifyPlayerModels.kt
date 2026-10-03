package com.theveloper.pixelplay.data.network.spotify

import com.google.gson.annotations.SerializedName

/**
 * DTOs for the Web API Player endpoints (Spotify Connect).
 *
 * Shapes follow developer.spotify.com/documentation/web-api/reference:
 * - `GET /v1/me/player/devices` returns `{ "devices": [DeviceObject] }`.
 * - `GET /v1/me/player` returns the playback state, or **204 with no body** when nothing is
 *   playing on any device.
 * - Errors come back as `{ "error": { "status", "message", "reason"? } }`. Player commands add a
 *   `reason` such as `PREMIUM_REQUIRED` or `NO_ACTIVE_DEVICE`.
 */

data class SpotifyDevicesResponse(
    @SerializedName("devices") val devices: List<SpotifyDeviceDto>? = null
)

data class SpotifyDeviceDto(
    /** Nullable, and not guaranteed to stay the same forever. */
    @SerializedName("id") val id: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("is_private_session") val isPrivateSession: Boolean? = null,
    /** When true, the device accepts no Web API commands at all. */
    @SerializedName("is_restricted") val isRestricted: Boolean? = null,
    @SerializedName("name") val name: String? = null,
    /** "computer", "smartphone", "speaker", "tv", "avr", "stb", "audio_dongle", "castaudio"… */
    @SerializedName("type") val type: String? = null,
    @SerializedName("volume_percent") val volumePercent: Int? = null,
    @SerializedName("supports_volume") val supportsVolume: Boolean? = null
)

data class SpotifyPlaybackStateDto(
    @SerializedName("device") val device: SpotifyDeviceDto? = null,
    /** "off", "track" or "context". */
    @SerializedName("repeat_state") val repeatState: String? = null,
    @SerializedName("shuffle_state") val shuffleState: Boolean? = null,
    @SerializedName("timestamp") val timestamp: Long? = null,
    @SerializedName("progress_ms") val progressMs: Long? = null,
    @SerializedName("is_playing") val isPlaying: Boolean? = null,
    @SerializedName("item") val item: SpotifyPlayerItemDto? = null,
    /** "track", "episode", "ad" or "unknown". */
    @SerializedName("currently_playing_type") val currentlyPlayingType: String? = null
)

data class SpotifyPlayerItemDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("duration_ms") val durationMs: Long? = null,
    /** Set when Spotify relinked the requested track to a market-playable copy. */
    @SerializedName("linked_from") val linkedFrom: SpotifyLinkedFromDto? = null
)

data class SpotifyLinkedFromDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null
)

data class SpotifyPlayRequest(
    @SerializedName("uris") val uris: List<String>,
    @SerializedName("offset") val offset: SpotifyPlayOffset? = null,
    @SerializedName("position_ms") val positionMs: Long? = null
)

data class SpotifyPlayOffset(
    @SerializedName("position") val position: Int
)

data class SpotifyTransferRequest(
    /** The API takes an array but only supports a single id. */
    @SerializedName("device_ids") val deviceIds: List<String>,
    @SerializedName("play") val play: Boolean
)

data class SpotifyErrorEnvelope(
    @SerializedName("error") val error: SpotifyErrorBody? = null
)

data class SpotifyErrorBody(
    @SerializedName("status") val status: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("reason") val reason: String? = null
)
