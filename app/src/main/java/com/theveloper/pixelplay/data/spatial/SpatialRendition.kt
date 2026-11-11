package com.theveloper.pixelplay.data.spatial

import java.io.File

/**
 * Where one song stands for one profile (§6.1: "Per song and profile the app tracks: none → preview → queued →
 * processing → downloading → ready, or failed"), keyed by the YouTube video and the profile; the order is the usual
 * way forward.
 *
 * The spec's `queued` is three cases here, because "a render that is wanted but cannot start yet is `queued` with a
 * reason" (§6.1) and each reason has its own status line (§6.5): [QUEUED] with no reason, [WAITING_FOR_NETWORK] and
 * [CAP_REACHED]. [isQueued] is true for all three.
 *
 * [SpatialLibrary] keeps and reports the cloud's side of it: every case but [PREVIEW], which is the player's fact
 * and is added by [shown], as on iOS.
 */
enum class SpatialRenditionStatus {
    /** Nothing is known, wanted or under way for this song and profile. */
    NONE,

    /**
     * The on-device stage stands in and no cloud work is under way, yet or at all (§6.1 "preview (on-device)").
     * Never reported by the library: see [shown].
     */
    PREVIEW,

    /**
     * Wanted from the cloud and not on a GPU yet: the shared-library check, preparing and uploading the song, or
     * waiting for a worker (§6.2 steps 2-3).
     */
    QUEUED,

    /**
     * `queued` with the reason `waitingForNetwork` (§6.1): wanted, but there is no network the person allows for
     * cloud work. It starts by itself when one comes back. §6.5: "The cloud version waits for Wi-Fi."
     */
    WAITING_FOR_NETWORK,

    /**
     * `queued` with the reason `capReached` (§6.1): wanted, but the month's limit for library jobs
     * (`cloudAudio.monthlyCapUSD`) is reached. §6.5: "This month's cloud limit is reached."
     */
    CAP_REACHED,

    /** The GPU is working on it (§6.2 step 3). */
    PROCESSING,

    /**
     * The render exists, in the shared library or as the result of the job, and is coming down to be verified
     * (§6.2 steps 2 and 4). A state of its own on both platforms (§6.1), with its own line (§6.5: "Getting the cloud
     * version.").
     */
    DOWNLOADING,

    /** A verified render is in the local store (§6.2 "Local store"): every later play starts on it (§6.3). */
    READY,

    /** The last attempt failed. It is tried again later with backoff: 10 minutes, 1 hour, 1 day (§6.1). */
    FAILED;

    /** The spec's `queued` (§6.1), with or without a reason: [QUEUED], [WAITING_FOR_NETWORK] or [CAP_REACHED]. */
    val isQueued: Boolean get() = this == QUEUED || this == WAITING_FOR_NETWORK || this == CAP_REACHED

    companion object {
        /**
         * What the person is told for the playing song (§6.1): the [library]'s own status when the cloud is doing or
         * has done something, else [PREVIEW] while the on-device stage is what plays, else [NONE].
         */
        fun shown(library: SpatialRenditionStatus, onDeviceAudible: Boolean): SpatialRenditionStatus =
            when (library) {
                NONE, PREVIEW -> if (onDeviceAudible) PREVIEW else NONE
                else -> library
            }
    }
}

/** What the current item sounds like (§6.3). */
enum class SpatialRendition {
    /** The normal stereo sound: the stream or its downloaded copy. */
    STEREO,

    /** The stream through the on-device stage of §7. */
    ON_DEVICE,

    /** A cloud render from the local store, with the on-device stage bypassed for it (§6.3: never spatialize twice). */
    CLOUD_RENDER,
}

/**
 * Why Spatial audio is not audible although a profile is selected (§6.3 "Spatial active", §7). When more than one
 * holds, the player reports the first in this order (the route before Sing, as on iOS).
 */
enum class SpatialBlock {
    /**
     * The route rule fails (§6.1, §6.3): `spatial.route` is `headphones` and the sound goes somewhere else, the
     * phone's speaker, a car, or another device (Cast, Spotify Connect). §6.5: "Headphones only. Plug in to hear it."
     */
    ROUTE,

    /** Sing's instrumental mode is on (§6.3 "Spatial active"). §6.5: "Paused while Sing is on." */
    SING,

    /**
     * The song plays on another device, a Cast or Spotify Connect one, and the route rule did not already say no
     * (`spatial.route` is `always`): those devices get the normal sound whatever the mode (§1). §6.5 has no line.
     */
    REMOTE,

    /**
     * The stream's sample rate is not one the on-device stage takes (§6.3, §7: anything but 44.1 or 48 kHz plays
     * without the stage) and no render is playing. The cloud render still works, so the player still asks for it.
     * No status line of its own (§6.3).
     */
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
    /** Why a selected profile is not audible, or null when it is audible or none is selected (§6.3). */
    val blockedBy: SpatialBlock? = null,
    /** The Spotify track the item plays, or null: the key of the sheet's "This song" switch (`spatial.songsOff`). */
    val spotifyId: String? = null,
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
    /**
     * §3.1 `audio.sampleRate`: the rate of the file that was uploaded for it. That may not be the rate this phone
     * streams the song at (§6.2 step 3: 44.1 kHz when an iPhone made the render), so a render and the stream are
     * lined up by media time, never by frame count.
     */
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
    /** §3.1 `render.stemMode`: `stems6`, `stems4` or `mix` (§6.5 diagnostics). */
    val stemMode: String? = null,
    /** §3.1 `loudness.integratedLufs` (§6.5 diagnostics). */
    val integratedLufs: Float? = null,
    /** §3.1 `loudness.sourceLufs` (§6.5 diagnostics). */
    val sourceLufs: Float? = null,
)

/**
 * Settings' "Songs ready: N · X GB on this phone", and what library jobs have used of their own caps for Developer
 * mode's rows (§6.5, §6.1 "Money").
 */
data class SpatialLibraryStats(
    /** Songs with at least one verified render in the local store, of any profile. */
    val songsReady: Int = 0,
    /** What the local store holds, in bytes: audio and manifests. */
    val bytesOnPhone: Long = 0L,
    /**
     * What library jobs spent this month, in millionths of a dollar: their own sum, under
     * `cloudAudio.monthlyCapUSD`, with none of Sing's jobs in it (§6.1 "Money").
     */
    val spentThisMonthMicroUsd: Long = 0L,
    /** Songs the background queue sent today, out of `cloudAudio.dailyAhead` (§6.4). */
    val sentToday: Int = 0,
)

/** What changed in the local store, for the player (§6.3). Hot: only what happens while it listens. */
sealed interface SpatialLibraryEvent {
    /**
     * A render was downloaded and verified (§6.2). If it is for the song that is playing, the player may fade to it
     * (§6.3: at least 20 s left, no seek or track change in flight); otherwise the next play uses it.
     */
    data class RenderReady(val render: SpatialRender) : SpatialLibraryEvent

    /**
     * Files left the local store: every file of [videoId], or all of them when it is null ("Remove downloaded
     * renders", §6.5; the storage budget, §6.1). A player on one of them goes back to the stream (§6.3).
     */
    data class Removed(val videoId: String?) : SpatialLibraryEvent
}

/**
 * The player sheet's status line for the current song (§6.5: "Every state has one"). One case per row of the spec's
 * table, each quoting its row; the words themselves live in the UI's strings.
 */
enum class SpatialStatusLine {
    /**
     * Nothing to say: Spatial audio is off, nothing is loaded, or a state §6.5 has no row for (the song plays on
     * another device while `spatial.route` is `always`, or its format is one the on-device stage does not take).
     */
    NONE,

    /**
     * "On your phone for now. The cloud version takes about a minute." The on-device stage plays and the cloud
     * render is wanted (queued, uploading, processing).
     */
    ON_PHONE_FOR_NOW,

    /** "On your phone for now. The cloud version waits for Wi-Fi." Wanted, but no network the person allows. */
    WAITING_FOR_WIFI,

    /** "On your phone only. This month's cloud limit is reached." Wanted, but the month's limit is reached. */
    CLOUD_LIMIT_REACHED,

    /** "Getting the cloud version." The render is downloading, or is on the phone and the fade to it comes next. */
    GETTING_CLOUD_VERSION,

    /** "Playing the cloud version." */
    CLOUD_VERSION,

    /** "On your phone only. Turn on cloud renders for the full version." A profile is on, cloud renders are off. */
    ON_PHONE_ONLY,

    /** "The cloud version didn't work this time. Still playing on your phone." */
    CLOUD_FAILED,

    /** "Headphones only. Plug in to hear it." The route rule fails. */
    HEADPHONES_ONLY,

    /** "Paused while Sing is on." Sing's instrumental mode is on. */
    PAUSED_FOR_SING;

    companion object {
        /**
         * The line for the current song (§6.5), from the settings, what is audible, the song's [status] for the
         * selected profile ([SpatialRenditionStatus.shown]) and whether cloud renders can happen
         * ([SpatialLibrary.cloudRendersOn], §6.1 "Two switches"):
         * - no profile selected, or nothing loaded: [NONE];
         * - the route rule fails: [HEADPHONES_ONLY]; else Sing's instrumental mode is on: [PAUSED_FOR_SING];
         * - a cloud render is audible: [CLOUD_VERSION];
         * - the on-device stage is audible: [ON_PHONE_ONLY] while cloud renders are off; else [CLOUD_FAILED] after
         *   a failure, [WAITING_FOR_WIFI] and [CLOUD_LIMIT_REACHED] while the render is queued for that reason,
         *   [GETTING_CLOUD_VERSION] while it comes down (or is already there and the fade to it is next), and
         *   [ON_PHONE_FOR_NOW] in every other case;
         * - anything else: [NONE].
         */
        fun of(
            settings: SpatialSettings,
            audible: SpatialAudible,
            status: SpatialRenditionStatus,
            cloudRendersOn: Boolean,
        ): SpatialStatusLine = when {
            settings.profile == null || audible.songId == null -> NONE
            audible.blockedBy == SpatialBlock.ROUTE -> HEADPHONES_ONLY
            audible.blockedBy == SpatialBlock.SING -> PAUSED_FOR_SING
            audible.rendition == SpatialRendition.CLOUD_RENDER -> CLOUD_VERSION
            audible.rendition != SpatialRendition.ON_DEVICE -> NONE
            !cloudRendersOn -> ON_PHONE_ONLY
            else -> when (status) {
                SpatialRenditionStatus.FAILED -> CLOUD_FAILED
                SpatialRenditionStatus.WAITING_FOR_NETWORK -> WAITING_FOR_WIFI
                SpatialRenditionStatus.CAP_REACHED -> CLOUD_LIMIT_REACHED
                SpatialRenditionStatus.DOWNLOADING, SpatialRenditionStatus.READY -> GETTING_CLOUD_VERSION
                SpatialRenditionStatus.NONE,
                SpatialRenditionStatus.PREVIEW,
                SpatialRenditionStatus.QUEUED,
                SpatialRenditionStatus.PROCESSING -> ON_PHONE_FOR_NOW
            }
        }
    }
}
