package com.theveloper.pixelplay.data.spatial

import java.io.File

/**
 * Where one song stands for one profile (§6.1: "Per song and profile the app tracks"). Kept by [SpatialLibrary],
 * keyed by the YouTube video and the profile; the order is the usual way forward.
 */
enum class SpatialRenditionStatus {
    /** Nothing is known or was asked for this song and profile. */
    NONE,

    /**
     * Asked for (the song played with this profile), and only the on-device version exists so far: the cloud is
     * switched off or can't take it now, or the shared-library check is still under way (§6.2 steps 1-2).
     */
    PREVIEW,

    /** A cloud job for it exists and has not started on a GPU yet: preparing, uploading or waiting (§6.2 step 3). */
    QUEUED,

    /** The GPU is working on it, or its result is being downloaded and verified (§6.2 steps 3-4). */
    PROCESSING,

    /** A verified render is in the local store (§6.2 "Local store"): every later play starts on it (§6.3). */
    READY,

    /** The last attempt failed. It is tried again later with backoff: 10 minutes, 1 hour, 1 day (§6.1). */
    FAILED,
}

/** What the current item sounds like (§6.3). */
enum class SpatialRendition {
    /** The normal stereo sound: the stream, its downloaded copy, or the Enhanced file. */
    STEREO,

    /** The stream (or the Enhanced file) through the on-device stage of §7. */
    ON_DEVICE,

    /** A cloud render from the local store, with the on-device stage bypassed for it (§6.3: never spatialize twice). */
    CLOUD_RENDER,
}

/** Why Spatial audio is not audible although a profile is selected (§6.3 "Spatial active", §7). */
enum class SpatialBlock {
    /** `spatial.route` is `headphones` and the sound goes somewhere else (§6.1, §6.3). */
    ROUTE,

    /** Sing's instrumental is playing (§6.3: Spatial is active only while Sing's instrumental mode is not on). */
    SING,

    /** The song plays on another device, a Cast or Spotify Connect one: those get the normal sound (§1). */
    REMOTE,

    /** The stream's format is not one the on-device stage handles (§7: any rate but 44.1 or 48 kHz is bypassed). */
    FORMAT,
}

/**
 * What the listener hears for the current item, as [SpatialPlayback] reports it (§6.3). The default value is "nothing
 * is loaded".
 */
data class SpatialAudible(
    /** The media id of the item this describes (the library's `Song.id`), or null when nothing is loaded. */
    val songId: String? = null,
    /**
     * The YouTube video the item plays, the library key (§2), or null when it has none (unmatched, or not a
     * streamed song). The UI asks [SpatialLibrary.status] with it.
     */
    val videoId: String? = null,
    /** What it sounds like now (§6.3). */
    val rendition: SpatialRendition = SpatialRendition.STEREO,
    /** The profile that is audible: the on-device preset or the render's own. Null while [rendition] is stereo. */
    val profile: SpatialProfile? = null,
    /** The Enhanced file stands in for the stream (§6.3 "Enhanced"); a cloud render already contains it. */
    val enhanced: Boolean = false,
    /** Why a selected profile is not audible, or null when it is audible or none is selected (§6.3). */
    val blockedBy: SpatialBlock? = null,
) {
    /** A spatial rendition is audible: the on-device stage or a cloud render (§6.3 "Level" applies while true). */
    val isSpatial: Boolean get() = rendition != SpatialRendition.STEREO

    companion object {
        /** Nothing is loaded, or nothing was reported yet. */
        val NOTHING = SpatialAudible()
    }
}

/**
 * A verified cloud render in the local store (§6.2 "Local store", §3.1): the file and the manifest facts the player
 * and Developer mode's diagnostics need. Its presence means the bytes and the SHA-256 matched the manifest.
 */
data class SpatialRender(
    /** The YouTube video it was rendered from, the library key (§2). */
    val videoId: String,
    /** The profile it was rendered for (§3.1 `profile`). */
    val profile: SpatialProfile,
    /** `filesDir/cloud_audio/<videoId>/sp-<profile>.flac` (§6.2): FLAC 16-bit, ready to play. */
    val file: File,
    /** §3.1 `audio.sampleRate`: the source's own rate. */
    val sampleRate: Int,
    /** §3.1 `audio.samples`: the source's frame count, so the render keeps the song's timeline with no added delay. */
    val samples: Long,
    /** §3.1 `audio.durationMs`. */
    val durationMs: Long,
    /**
     * §3.1 `loudness.headroomDb`: how far under the source's loudness the render sits (4.0 today). The player gives
     * it back whenever it has the room (§6.3 "Level").
     */
    val headroomDb: Float,
    /** §3.1 `source.codec` (Developer mode's diagnostics, §6.5). */
    val sourceCodec: String? = null,
    /** §3.1 `source.cutoffHz`: where the source's highs end (§6.5 diagnostics). */
    val cutoffHz: Int? = null,
    /** §3.1 `render.stemMode`: `stems6`, `stems4` or `mix` (§6.5 diagnostics). */
    val stemMode: String? = null,
    /** §3.1 `loudness.integratedLufs` (§6.5 diagnostics). */
    val integratedLufs: Float? = null,
    /** §3.1 `loudness.sourceLufs` (§6.5 diagnostics). */
    val sourceLufs: Float? = null,
)

/**
 * A verified Enhanced file in the local store (§3 `enhanced.flac`, §6.3 "Enhanced"): the restored stereo song, at the
 * source's loudness. Only band-limited songs have one; for the others the library just remembers there is none.
 */
data class EnhancedAudio(
    /** The YouTube video it was made from, the library key (§2). */
    val videoId: String,
    /** `filesDir/cloud_audio/<videoId>/enhanced.flac` (§6.2). */
    val file: File,
    /** §3.1 `audio.sampleRate`: the source's own rate. */
    val sampleRate: Int,
    /** §3.1 `audio.samples`: the source's frame count. */
    val samples: Long,
    /** §3.1 `audio.durationMs`. */
    val durationMs: Long,
)

/** Settings' "Songs ready: N · X GB on this phone" (§6.5). */
data class SpatialLibraryStats(
    /** Songs with at least one verified render in the local store (a spatial render of any profile, or Enhanced). */
    val songsReady: Int = 0,
    /** What the local store holds, in bytes: audio and manifests. */
    val bytesOnPhone: Long = 0L,
)

/** What changed in the local store, for the player (§6.3). Hot: only what happens while it listens. */
sealed interface SpatialLibraryEvent {
    /**
     * A render was downloaded and verified (§6.2). If it is for the song that is playing, the player may fade to it
     * (§6.3: at least 20 s left, no seek or track change in flight); otherwise the next play uses it.
     */
    data class RenderReady(val render: SpatialRender) : SpatialLibraryEvent

    /** An Enhanced file was downloaded and verified (§6.3 "Enhanced"): the next play uses it. */
    data class EnhancedReady(val audio: EnhancedAudio) : SpatialLibraryEvent

    /**
     * Files left the local store: every file of [videoId], or all of them when it is null ("Remove downloaded
     * renders", §6.5; the storage budget, §6.1). A player on one of them goes back to the stream (§6.3).
     */
    data class Removed(val videoId: String?) : SpatialLibraryEvent
}

/** The player sheet's status line for the current song (§6.5). The words live in the UI's strings. */
enum class SpatialStatusLine {
    /** Nothing to say: Spatial audio is off, nothing plays, or none of the three lines is true. */
    NONE,

    /** "On your phone for now. The cloud version takes about a minute." */
    ON_PHONE_FOR_NOW,

    /** "Playing the cloud version." */
    CLOUD_VERSION,

    /** "Headphones only. Plug in to hear it." */
    HEADPHONES_ONLY;

    companion object {
        /**
         * The line for the current song (§6.5), from the settings, what is audible and the song's [status] for the
         * selected profile:
         * - no profile selected, or nothing loaded: [NONE];
         * - the route rule fails (§6.3): [HEADPHONES_ONLY];
         * - a cloud render is audible: [CLOUD_VERSION];
         * - the on-device stage is audible, `spatial.cloud` is on and the render is on its way
         *   ([SpatialRenditionStatus.PREVIEW], `QUEUED` or `PROCESSING`): [ON_PHONE_FOR_NOW];
         * - anything else (the cloud is off, the render failed, Sing or another device plays): [NONE], because the
         *   spec has no line that would be true there.
         */
        fun of(settings: SpatialSettings, audible: SpatialAudible, status: SpatialRenditionStatus): SpatialStatusLine =
            when {
                settings.profile == null || audible.songId == null -> NONE
                audible.blockedBy == SpatialBlock.ROUTE -> HEADPHONES_ONLY
                audible.rendition == SpatialRendition.CLOUD_RENDER -> CLOUD_VERSION
                audible.rendition == SpatialRendition.ON_DEVICE && settings.cloud && (
                    status == SpatialRenditionStatus.PREVIEW ||
                        status == SpatialRenditionStatus.QUEUED ||
                        status == SpatialRenditionStatus.PROCESSING
                    ) -> ON_PHONE_FOR_NOW
                else -> NONE
            }
    }
}
