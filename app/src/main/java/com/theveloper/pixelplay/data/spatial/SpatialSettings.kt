package com.theveloper.pixelplay.data.spatial

/** `spatial.route` (§6.1): on which outputs of this phone Spatial audio may be heard. */
enum class SpatialRouteMode(val id: String) {
    /**
     * Headphones only, the default (§6.1): wired, Bluetooth A2DP/LE, a USB headset; a Bluetooth speaker counts.
     * On anything else the on-device stage is bypassed and no render plays (§6.3).
     */
    HEADPHONES("headphones"),

    /**
     * Every output of this phone. Offered in Developer mode only (§6.1, §6.5); a stored `always` is honoured whether
     * or not Developer mode is on, only its row is hidden (§6.1 "Stored forms").
     */
    ALWAYS("always");

    companion object {
        /** The mode with the wire [id]; null and anything unknown read as the default, [HEADPHONES]. */
        fun fromId(id: String?): SpatialRouteMode = entries.firstOrNull { it.id == id } ?: HEADPHONES
    }
}

/**
 * The settings of §6.1 as one immutable snapshot, read from [SpatialSettingsRepository] (the only writer). Each
 * property names its key in the settings DataStore; the keys are the spec's, the same strings the iOS app uses.
 *
 * [cloud] has no default here on purpose: the setting's default depends on the build (see [defaults]).
 */
data class SpatialSettings(
    /**
     * `spatial.profile` (§6.1): the selected profile, or null for `off`. Picking a profile turns Spatial audio on;
     * whether it is audible right now is §6.3's "Spatial active" ([SpatialAudible]).
     */
    val profile: SpatialProfile? = null,
    /** `spatial.route` (§6.1). */
    val routeMode: SpatialRouteMode = SpatialRouteMode.HEADPHONES,
    /**
     * `spatial.cloud` (§6.1): ask the cloud for the real render (§6.2 steps 2-4). Off = on-device only. This is
     * Spatial's own switch, under a master (§6.1 "Two switches"): Cloud Studio's "Process songs in the cloud" switch
     * off means no cloud activity of any kind, lookups and downloads included. A library job, lookup or download needs
     * both on; [SpatialLibrary.cloudRendersOn] is the two together.
     */
    val cloud: Boolean,
    /** `spatial.prepareAhead` (§6.1, §6.4): prepare favourites and most-played ahead, on Wi-Fi, battery not low. */
    val prepareAhead: Boolean = DEFAULT_PREPARE_AHEAD,
    /**
     * `cloudAudio.storageMB` (§6.1): the local budget for downloaded renders, kept within
     * [MIN_STORAGE_MB]..[MAX_STORAGE_MB]; least-recently-played go first.
     */
    val storageMb: Int = DEFAULT_STORAGE_MB,
    /**
     * `cloudAudio.monthlyCapUSD` (§6.1): what library jobs may spend in a month, in whole dollars
     * (0..[MAX_MONTHLY_CAP_USD]); null is Developer mode's "No limit". Library jobs have their own monthly sum under
     * this cap (§6.1 "Money"): they are not counted against Sing's cap, Sing's jobs are not counted against this one,
     * and Sing's $3 rule with built-in keys does not clamp it.
     */
    val monthlyCapUsd: Int? = DEFAULT_MONTHLY_CAP_USD,
    /**
     * `cloudAudio.dailyAhead` (§6.1, §6.4): songs a day the background queue may send, kept within
     * [MIN_DAILY_AHEAD]..[MAX_DAILY_AHEAD].
     */
    val dailyAhead: Int = DEFAULT_DAILY_AHEAD,
    /**
     * `spatial.songsOff`: the Spotify ids of the songs Spatial audio is turned off for, from the sheet's "This song"
     * switch (Hoa, 2026-11-11). Such a song plays its original at full quality, as with Spatial audio off: no stage, no
     * render, nothing asked of the cloud.
     */
    val songsOff: Set<String> = emptySet(),
) {
    /** A profile is selected (§6.1). Not the same as audible: the route rule and Sing can still hold it back (§6.3). */
    val isOn: Boolean get() = profile != null

    /** The profile [spotifyId]'s song plays with: [profile], or null when it is off or Spatial audio is turned off for that song. */
    fun profileFor(spotifyId: String?): SpatialProfile? =
        profile?.takeUnless { spotifyId != null && spotifyId in songsOff }

    /** Spatial audio is turned off for [spotifyId]'s song in particular. */
    fun isOffFor(spotifyId: String?): Boolean = spotifyId != null && spotifyId in songsOff

    /** [storageMb] in bytes, for the local store's eviction (§6.1, §6.2 "Local store"). */
    val storageBytes: Long get() = storageMb.toLong() * BYTES_PER_MB

    /**
     * [monthlyCapUsd] in the unit Cloud Studio's money guards use (millionths of a dollar); null when there is no
     * limit (§6.1).
     */
    val monthlyCapMicroUsd: Long? get() = monthlyCapUsd?.let { it.toLong() * MICRO_USD_PER_USD }

    companion object {
        /** §6.1: `spatial.prepareAhead` is on by default. */
        const val DEFAULT_PREPARE_AHEAD = true

        /** §6.1: `cloudAudio.storageMB` defaults to 3072. */
        const val DEFAULT_STORAGE_MB = 3072

        /** §6.1 "Stored forms": the smallest storage budget, 256 MB (the same on both platforms). */
        const val MIN_STORAGE_MB = 256

        /** §6.1 "Stored forms": the largest storage budget, 64 GB (the same on both platforms). */
        const val MAX_STORAGE_MB = 65_536

        /** §6.1: `cloudAudio.monthlyCapUSD` defaults to 30. */
        const val DEFAULT_MONTHLY_CAP_USD = 30

        /** §6.1: the highest cap Developer mode offers short of "No limit". */
        const val MAX_MONTHLY_CAP_USD = 200

        /** §6.1: `cloudAudio.dailyAhead` defaults to 25. */
        const val DEFAULT_DAILY_AHEAD = 25

        /** §6.1 "Stored forms": the background queue sends at least this many songs a day when it is on. */
        const val MIN_DAILY_AHEAD = 1

        /** §6.1 "Stored forms": and at most this many. */
        const val MAX_DAILY_AHEAD = 100

        private const val BYTES_PER_MB = 1_048_576L
        private const val MICRO_USD_PER_USD = 1_000_000L

        /**
         * The defaults of §6.1. `spatial.cloud` is "on when built-in cloud keys exist", so it follows the build:
         * [builtInCloudKeys] is whether this build carries PixlAudio's own cloud keys.
         */
        fun defaults(builtInCloudKeys: Boolean): SpatialSettings = SpatialSettings(cloud = builtInCloudKeys)

        /** [megabytes] brought inside the range §6.1 keeps `cloudAudio.storageMB` in. */
        fun clampStorageMb(megabytes: Int): Int = megabytes.coerceIn(MIN_STORAGE_MB, MAX_STORAGE_MB)

        /** [songs] brought inside the range §6.1 keeps `cloudAudio.dailyAhead` in. */
        fun clampDailyAhead(songs: Int): Int = songs.coerceIn(MIN_DAILY_AHEAD, MAX_DAILY_AHEAD)
    }
}
