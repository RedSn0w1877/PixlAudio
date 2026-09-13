package com.theveloper.pixelplay.data.premium

/**
 * Capacity switches for additive Plus work. The free values deliberately remain useful; Plus
 * increases capacity rather than disabling any existing player feature.
 */
object PremiumFeatureLimits {
    const val FREE_BACKGROUND_JOBS_PER_WINDOW = 8
    const val PLUS_BACKGROUND_JOBS_PER_WINDOW = 32
    const val FREE_DISCOVERY_CANDIDATES = 48
    const val PLUS_DISCOVERY_CANDIDATES = 240
    const val FREE_EXPORT_MAX_BITRATE_KBPS = 192
    const val PLUS_EXPORT_MAX_BITRATE_KBPS = 320

    fun backgroundJobsPerWindow(entitlement: PremiumEntitlement, nowEpochMillis: Long = System.currentTimeMillis()): Int =
        if (entitlement.isActive(nowEpochMillis)) PLUS_BACKGROUND_JOBS_PER_WINDOW else FREE_BACKGROUND_JOBS_PER_WINDOW

    fun discoveryCandidates(entitlement: PremiumEntitlement, nowEpochMillis: Long = System.currentTimeMillis()): Int =
        if (entitlement.isActive(nowEpochMillis)) PLUS_DISCOVERY_CANDIDATES else FREE_DISCOVERY_CANDIDATES

    fun exportMaxBitrateKbps(entitlement: PremiumEntitlement, nowEpochMillis: Long = System.currentTimeMillis()): Int =
        if (entitlement.isActive(nowEpochMillis)) PLUS_EXPORT_MAX_BITRATE_KBPS else FREE_EXPORT_MAX_BITRATE_KBPS
}
