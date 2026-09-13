package com.theveloper.pixelplay.data.premium

/**
 * The one-time supporter tier is deliberately modeled separately from billing.
 * A future Play Billing or server verifier can map its result to this value
 * without making the rest of the app depend on a billing SDK.
 */
enum class PremiumTier {
    FREE,
    PLUS
}

enum class EntitlementSource {
    VERIFIED_SERVER,
    PLAY_BILLING,
    RESTORED_BACKUP,
    LOCAL_DEBUG
}

data class PremiumEntitlement(
    val tier: PremiumTier = PremiumTier.FREE,
    val entitlementId: String? = null,
    val issuedAtEpochMillis: Long? = null,
    /** Null means a permanent one-time entitlement. */
    val expiresAtEpochMillis: Long? = null,
    val source: EntitlementSource? = null
) {
    fun isActive(nowEpochMillis: Long): Boolean =
        tier == PremiumTier.PLUS &&
            (expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis)

    companion object {
        val FREE = PremiumEntitlement()
    }
}

/** Additive features suitable for a supporter tier; existing playback remains free. */
enum class PremiumFeature(
    val key: String,
    val title: String,
    val description: String
) {
    CLOUD_STUDIO(
        "cloud_studio",
        "Cloud Studio",
        "Use a hosted high-capacity model for faster instrumental and lyric processing."
    ),
    DEEP_DISCOVERY(
        "deep_discovery",
        "Deep Discovery",
        "Generate richer mixes using a larger recommendation candidate pool."
    ),
    SMART_PLAYLIST_TOOLS(
        "smart_playlist_tools",
        "Smart Playlist Tools",
        "Create adaptive playlists from mood, energy, era, and listening context."
    ),
    PRO_BACKGROUND_STUDIO(
        "pro_background_studio",
        "Pro Background Studio",
        "Opt into a larger quiet-processing budget when the device is idle and charging."
    ),
    ADVANCED_AUDIO_EXPORTS(
        "advanced_audio_exports",
        "Advanced Audio Exports",
        "Export higher-quality instrumental and vocal stems with detailed metadata."
    ),
    INSIGHT_LAB(
        "insight_lab",
        "Insight Lab",
        "See detailed playback, recommendation, and processing diagnostics."
    ),
    SUPPORTER_THEMES(
        "supporter_themes",
        "Supporter Themes",
        "Unlock animated visual themes and supporter-only player accents."
    )
}

object PremiumFeatureRegistry {
    const val MIN_SUPPORT_AMOUNT_CENTS = 1_500

    val allFeatures: List<PremiumFeature> = PremiumFeature.entries

    fun isUnlocked(feature: PremiumFeature, entitlement: PremiumEntitlement, nowEpochMillis: Long): Boolean =
        entitlement.isActive(nowEpochMillis)

    fun isUnlocked(feature: PremiumFeature, entitlement: PremiumEntitlement): Boolean =
        entitlement.isActive(System.currentTimeMillis())

    /** Keeps payment UI honest: $15 is the minimum, while larger support is allowed. */
    fun normalizeSupportAmountCents(amountCents: Int): Int =
        amountCents.coerceAtLeast(MIN_SUPPORT_AMOUNT_CENTS)
}
