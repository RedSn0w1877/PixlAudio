package com.theveloper.pixelplay.data.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumEntitlementsTest {
    @Test
    fun `free entitlement leaves every premium feature locked`() {
        PremiumFeatureRegistry.allFeatures.forEach { feature ->
            assertFalse(PremiumFeatureRegistry.isUnlocked(feature, PremiumEntitlement.FREE, 10_000L))
        }
    }

    @Test
    fun `permanent plus entitlement unlocks the complete additive catalog`() {
        val plus = PremiumEntitlement(
            tier = PremiumTier.PLUS,
            entitlementId = "supporter-123",
            issuedAtEpochMillis = 10L,
            source = EntitlementSource.VERIFIED_SERVER
        )
        PremiumFeatureRegistry.allFeatures.forEach { feature ->
            assertTrue(PremiumFeatureRegistry.isUnlocked(feature, plus, Long.MAX_VALUE))
        }
        assertEquals(PremiumFeatureRegistry.allFeatures.size, PremiumFeature.entries.size)
    }

    @Test
    fun `temporary entitlement expires without changing tier data`() {
        val plus = PremiumEntitlement(
            tier = PremiumTier.PLUS,
            expiresAtEpochMillis = 1_000L,
            source = EntitlementSource.PLAY_BILLING
        )
        assertTrue(plus.isActive(999L))
        assertFalse(plus.isActive(1_000L))
        assertTrue(PremiumFeatureRegistry.isUnlocked(PremiumFeature.CLOUD_STUDIO, plus, 999L))
        assertFalse(PremiumFeatureRegistry.isUnlocked(PremiumFeature.CLOUD_STUDIO, plus, 1_000L))
        assertEquals(PremiumTier.PLUS, plus.tier)
    }

    @Test
    fun `convenience unlock check does not ignore an expired entitlement`() {
        val expired = PremiumEntitlement(
            tier = PremiumTier.PLUS,
            expiresAtEpochMillis = 1L,
            source = EntitlementSource.LOCAL_DEBUG
        )
        assertFalse(PremiumFeatureRegistry.isUnlocked(PremiumFeature.CLOUD_STUDIO, expired))
    }

    @Test
    fun `support amount enforces fifteen dollar minimum and accepts larger gifts`() {
        assertEquals(1_500, PremiumFeatureRegistry.normalizeSupportAmountCents(0))
        assertEquals(1_500, PremiumFeatureRegistry.normalizeSupportAmountCents(1_500))
        assertEquals(3_000, PremiumFeatureRegistry.normalizeSupportAmountCents(3_000))
    }

    @Test
    fun `catalog keys are stable and unique`() {
        val keys = PremiumFeatureRegistry.allFeatures.map(PremiumFeature::key)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() && it == it.lowercase() })
        assertNotEquals(0, keys.size)
    }

    @Test
    fun `plus increases capacity without changing free defaults`() {
        assertEquals(8, PremiumFeatureLimits.backgroundJobsPerWindow(PremiumEntitlement.FREE, 100L))
        assertEquals(32, PremiumFeatureLimits.backgroundJobsPerWindow(
            PremiumEntitlement(tier = PremiumTier.PLUS), 100L
        ))
        assertEquals(48, PremiumFeatureLimits.discoveryCandidates(PremiumEntitlement.FREE, 100L))
        assertEquals(240, PremiumFeatureLimits.discoveryCandidates(
            PremiumEntitlement(tier = PremiumTier.PLUS), 100L
        ))
        assertEquals(192, PremiumFeatureLimits.exportMaxBitrateKbps(PremiumEntitlement.FREE, 100L))
        assertEquals(320, PremiumFeatureLimits.exportMaxBitrateKbps(
            PremiumEntitlement(tier = PremiumTier.PLUS), 100L
        ))
    }
}
