package com.theveloper.pixelplay.data.spatial

/** `spatial.route` (§6.1): on which outputs of this phone Spatial audio may be heard. */
enum class SpatialRouteMode(val id: String) {
    /**
     * Headphones only, the default (§6.1): wired, Bluetooth A2DP/LE, a USB headset; a Bluetooth speaker counts.
     * On anything else the on-device stage is bypassed and no render plays (§6.3).
     */
    HEADPHONES("headphones"),

    /** Every output of this phone. Offered in Developer mode only (§6.1, §6.5). */
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
     * `spatial.cloud` (§6.1): ask the cloud for the real render (§6.2 steps 2-4). Off = on-device only. It also
     * gates asking for the Enhanced version (`enhanced.enabled`). Cloud Studio's own consent switch still applies
     * to anything that is sent.
     */
    val cloud: Boolean,
    /** `spatial.prepareAhead` (§6.1, §6.4): prepare favourites and most-played ahead, on Wi-Fi, battery not low. */
    val prepareAhead: Boolean = DEFAULT_PREPARE_AHEAD,
    /** `enhanced.enabled` (§6.1, §6.3): use the Enhanced version of band-limited songs. */
    val enhanced: Boolean = DEFAULT_ENHANCED,
    /** `cloudAudio.storageMB` (§6.1): the local budget for downloaded renders; least-recently-played go first. */
    val storageMb: Int = DEFAULT_STORAGE_MB,
    /**
     * `cloudAudio.monthlyCapUSD` (§6.1): what library jobs may spend in a month, in whole dollars
     * (0..[MAX_MONTHLY_CAP_USD]); null is Developer mode's "No limit". Separate from Sing's cap, which is unchanged.
     */
    val monthlyCapUsd: Int? = DEFAULT_MONTHLY_CAP_USD,
    /** `cloudAudio.dailyAhead` (§6.1, §6.4): songs a day the background queue may send. */
    val dailyAhead: Int = DEFAULT_DAILY_AHEAD,
) {
    /** A profile is selected (§6.1). Not the same as audible: the route rule and Sing can still hold it back (§6.3). */
    val isOn: Boolean get() = profile != null

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

        /** §6.1: `enhanced.enabled` is on by default. */
        const val DEFAULT_ENHANCED = true

        /** §6.1: `cloudAudio.storageMB` defaults to 3072. */
        const val DEFAULT_STORAGE_MB = 3072

        /** §6.1: `cloudAudio.monthlyCapUSD` defaults to 30. */
        const val DEFAULT_MONTHLY_CAP_USD = 30

        /** §6.1: the highest cap Developer mode offers short of "No limit". */
        const val MAX_MONTHLY_CAP_USD = 200

        /** §6.1: `cloudAudio.dailyAhead` defaults to 25. */
        const val DEFAULT_DAILY_AHEAD = 25

        private const val BYTES_PER_MB = 1_048_576L
        private const val MICRO_USD_PER_USD = 1_000_000L

        /**
         * The defaults of §6.1. `spatial.cloud` is "on when built-in cloud keys exist", so it follows the build:
         * [builtInCloudKeys] is whether this build carries PixlAudio's own cloud keys.
         */
        fun defaults(builtInCloudKeys: Boolean): SpatialSettings = SpatialSettings(cloud = builtInCloudKeys)
    }
}
