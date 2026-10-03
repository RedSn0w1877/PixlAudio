package com.theveloper.pixelplay.data.spotify.connect

import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.data.network.spotify.SpotifyDeviceDto
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaybackStateDto
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Spotify Connect output: PixlAudio tells a Spotify Connect device (an Echo, a TV, a speaker, the
 * desktop app) what to play through the Web API's Player endpoints and acts as its remote. The
 * device streams from Spotify itself, so the phone's own audio output (Bluetooth included) is left
 * alone. Shared spec with the iOS port (PixlNet `SpotifyConnect`): same constants, same rules.
 */
object SpotifyConnect {
    const val READ_PLAYBACK_SCOPE = "user-read-playback-state"
    const val MODIFY_PLAYBACK_SCOPE = "user-modify-playback-state"
    val REQUIRED_SCOPES: List<String> = listOf(READ_PLAYBACK_SCOPE, MODIFY_PLAYBACK_SCOPE)

    /**
     * Upper bound of `uris` in one `PUT /me/player/play`. The reference documents no limit, but 850
     * answered 413 (spotify/web-api#1483); 100 is the Web API's usual batch size, and the next window
     * is sent when the device reaches the last of these.
     */
    const val MAX_URIS_PER_PLAY = 100

    private const val TRACK_URI_PREFIX = "spotify:track:"

    /** The Connect scopes a granted scope set lacks. Null (unknown) reports nothing missing. */
    fun missingScopes(granted: Set<String>?): List<String> =
        if (granted == null) emptyList() else REQUIRED_SCOPES.filterNot { it in granted }

    fun trackUri(id: String): String = TRACK_URI_PREFIX + id

    fun trackIdFromUri(uri: String?): String? =
        uri?.takeIf { it.startsWith(TRACK_URI_PREFIX) }?.removePrefix(TRACK_URI_PREFIX)?.takeIf(::isTrackId)

    /**
     * A real Spotify track id: 22 base62 characters. YouTube Music rows imported into the Spotify
     * tables carry a synthetic id of 22 lowercase hex characters
     * (`SpotifyRepository.youTubeMusicSyntheticId`) that Spotify doesn't know; those resolve through
     * search like local files. (A real id being all-lowercase-hex has odds of about 1e-10.)
     */
    fun isTrackId(id: String?): Boolean {
        if (id == null || id.length != 22) return false
        var allLowerHex = true
        for (c in id) {
            val digit = c in '0'..'9'
            val upper = c in 'A'..'Z'
            val lower = c in 'a'..'z'
            if (!digit && !upper && !lower) return false
            if (!(digit || c in 'a'..'f')) allLowerHex = false
        }
        return !allLowerHex
    }

    /** The URI of a song that already carries a real Spotify id. */
    fun directUri(spotifyId: String?): String? = spotifyId?.takeIf(::isTrackId)?.let(::trackUri)
}

/**
 * One entry of PixlAudio's queue, reduced to what resolution needs. Built from the local player's
 * [MediaItem]s, which are the queue's single source of truth.
 */
data class ConnectTrack(
    /** The PixlAudio song id (the resolution cache key). */
    val songId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val spotifyId: String?,
    /** A local file whose tags may hold an ISRC (read only on a cache miss). */
    val filePath: String?
) {
    /** Changes when the tags that decide the match change: a cached answer for other tags is stale. */
    val fingerprint: String
        get() = "${TrackMatcher.normalize(title)}|${TrackMatcher.normalize(artist)}|${durationMs / 1000}"

    /** The URI without any lookup (a real Spotify id). */
    val directUri: String? get() = SpotifyConnect.directUri(spotifyId)

    companion object {
        fun from(item: MediaItem): ConnectTrack {
            val metadata = item.mediaMetadata
            val extras = metadata.extras
            val spotifyId = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_SPOTIFY_ID)
                ?: item.localConfiguration?.uri?.takeIf { it.scheme == "spotify" }?.let { uri ->
                    uri.toString().removePrefix("spotify://").removePrefix("spotify:").substringBefore('?').trim('/')
                }
            return ConnectTrack(
                songId = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                durationMs = extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L) ?: 0L,
                spotifyId = spotifyId?.takeIf { it.isNotBlank() },
                filePath = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** Icon family for a Connect device, from the Web API's free-form `type` string. */
enum class SpotifyDeviceKind {
    SPEAKER, TV, COMPUTER, SMARTPHONE, TABLET, AVR, STB, CAST_AUDIO, CAST_VIDEO, AUTOMOBILE,
    GAME_CONSOLE, AUDIO_DONGLE, SMARTWATCH, UNKNOWN;

    companion object {
        fun fromApiType(type: String?): SpotifyDeviceKind = when (type?.trim()?.lowercase()) {
            "speaker" -> SPEAKER
            "tv" -> TV
            "computer" -> COMPUTER
            "smartphone" -> SMARTPHONE
            "tablet" -> TABLET
            "avr" -> AVR
            "stb" -> STB
            "castaudio", "cast_audio" -> CAST_AUDIO
            "castvideo", "cast_video" -> CAST_VIDEO
            "automobile" -> AUTOMOBILE
            "gameconsole", "game_console" -> GAME_CONSOLE
            "audiodongle", "audio_dongle" -> AUDIO_DONGLE
            "smartwatch" -> SMARTWATCH
            else -> UNKNOWN
        }
    }
}

/** A `DeviceObject` from `GET /me/player/devices` (or the `device` of `GET /me/player`). */
data class SpotifyConnectDevice(
    /** Nullable in the reference, and not guaranteed to stay the same; a device without one can't be targeted. */
    val deviceId: String?,
    val name: String,
    val type: String,
    val isActive: Boolean = false,
    val isPrivateSession: Boolean = false,
    /** "No Web API commands will be accepted by this device." */
    val isRestricted: Boolean = false,
    val volumePercent: Int? = null,
    val supportsVolume: Boolean = false
) {
    /** Stable list key. */
    val key: String get() = deviceId ?: "name:$name"

    val kind: SpotifyDeviceKind get() = SpotifyDeviceKind.fromApiType(type)

    /** Whether PixlAudio can start a session on it. */
    val isControllable: Boolean get() = deviceId != null && !isRestricted

    companion object {
        fun from(dto: SpotifyDeviceDto): SpotifyConnectDevice = SpotifyConnectDevice(
            deviceId = dto.id?.takeIf { it.isNotBlank() },
            name = dto.name?.takeIf { it.isNotBlank() } ?: "Spotify device",
            type = dto.type ?: "Unknown",
            isActive = dto.isActive == true,
            isPrivateSession = dto.isPrivateSession == true,
            isRestricted = dto.isRestricted == true,
            volumePercent = dto.volumePercent?.coerceIn(0, 100),
            supportsVolume = dto.supportsVolume == true
        )

        /** The order the sheet lists them: the active device first, then controllable ones, each by name. */
        fun sortedForDisplay(devices: List<SpotifyConnectDevice>): List<SpotifyConnectDevice> =
            devices.withIndex().sortedWith(
                compareByDescending<IndexedValue<SpotifyConnectDevice>> { it.value.isActive }
                    .thenByDescending { it.value.isControllable }
                    .thenBy { it.value.name.lowercase() }
                    .thenBy { it.index }
            ).map { it.value }
    }
}

/** `GET /me/player`: what plays where. Null in the poller means 204 (nothing plays anywhere). */
data class SpotifyPlaybackSnapshot(
    val device: SpotifyConnectDevice?,
    val isPlaying: Boolean,
    val progressMs: Long?,
    /**
     * The URI PixlAudio asked for. When Spotify relinked the track to a copy playable in the
     * account's market, `item.uri` is the copy and `linked_from.uri` is what was sent.
     */
    val itemUri: String?,
    val itemDurationMs: Long?,
    /** `track`, `episode`, `ad`, `unknown`. */
    val currentlyPlayingType: String? = "track"
) {
    companion object {
        fun from(dto: SpotifyPlaybackStateDto): SpotifyPlaybackSnapshot = SpotifyPlaybackSnapshot(
            device = dto.device?.let(SpotifyConnectDevice::from),
            isPlaying = dto.isPlaying == true,
            progressMs = dto.progressMs,
            itemUri = dto.item?.linkedFrom?.uri?.takeIf { it.isNotBlank() } ?: dto.item?.uri,
            itemDurationMs = dto.item?.durationMs,
            currentlyPlayingType = dto.currentlyPlayingType
        )
    }
}

/** Whether the Spotify Connect section is shown, and how. */
enum class SpotifyConnectAvailability {
    /** No Spotify account linked (or no client id): the section is hidden. */
    HIDDEN,
    /** Linked before the playback scopes existed: only the reconnect row. */
    NEEDS_RECONNECT,
    READY
}

/** The device a session plays on, as the UI shows it. */
data class SpotifyConnectActiveDevice(
    val deviceId: String,
    val name: String,
    val type: String,
    val supportsVolume: Boolean,
    val volumePercent: Int?
) {
    val kind: SpotifyDeviceKind get() = SpotifyDeviceKind.fromApiType(type)
}

/** Everything the devices sheet and the player chips render. */
data class SpotifyConnectUiState(
    val availability: SpotifyConnectAvailability = SpotifyConnectAvailability.HIDDEN,
    val devices: ImmutableList<SpotifyConnectDevice> = persistentListOf(),
    val isRefreshing: Boolean = false,
    /** A device list was loaded at least once (the empty hint shows only then). */
    val hasLoadedDevices: Boolean = false,
    val deviceListError: String? = null,
    /** The device a session is being started on. */
    val connectingDeviceId: String? = null,
    /** Null while this phone plays. */
    val active: SpotifyConnectActiveDevice? = null,
    /** The remote's play state, for the hero's "Spotify Connect • Playing". */
    val isRemotePlaying: Boolean = false,
    /** "Stop playing" is reading the device's position. */
    val isStopping: Boolean = false
) {
    val isSessionActive: Boolean get() = active != null
    val isConnecting: Boolean get() = connectingDeviceId != null
}
