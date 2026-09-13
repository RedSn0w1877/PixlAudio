package com.theveloper.pixelplay.data.premium

import android.content.Context
import java.security.SecureRandom

/**
 * Local developer-only license fixture. It is useful for exercising Plus UI and feature gates
 * without a payment. It never verifies a real payment and must not be used by a server.
 */
class PlusLicenseManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun createLicense(): String {
        var key: String
        do {
            key = "PP-PLUS-${token(8)}-${token(8)}"
        } while (allKeys().contains(key))
        preferences.edit().putStringSet(KEY_LICENSES, allKeys() + key).apply()
        return key
    }

    fun activate(input: String): Boolean {
        val key = normalize(input)
        if (key.isEmpty() || key !in allKeys() || key in revokedKeys()) return false
        preferences.edit()
            .putString(KEY_ACTIVE, key)
            .putLong(KEY_ISSUED_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun deactivate(input: String = activeKey().orEmpty()): Boolean {
        val key = normalize(input)
        if (key.isEmpty() || key !in allKeys()) return false
        preferences.edit()
            .putStringSet(KEY_REVOKED, revokedKeys() + key)
            .remove(KEY_ACTIVE)
            .remove(KEY_ISSUED_AT)
            .apply()
        return true
    }

    fun activeKey(): String? = preferences.getString(KEY_ACTIVE, null)

    /** Maps the local fixture into the same shape a verified server would return. */
    fun activeEntitlement(): PremiumEntitlement = activeKey()?.let { key ->
        if (key in revokedKeys()) {
            PremiumEntitlement.FREE
        } else {
            PremiumEntitlement(
                tier = PremiumTier.PLUS,
                entitlementId = key,
                issuedAtEpochMillis = preferences.getLong(KEY_ISSUED_AT, 0L).takeIf { it > 0L },
                source = EntitlementSource.LOCAL_DEBUG
            )
        }
    } ?: PremiumEntitlement.FREE

    fun allKeys(): Set<String> = preferences.getStringSet(KEY_LICENSES, emptySet()).orEmpty()

    fun revokedKeys(): Set<String> = preferences.getStringSet(KEY_REVOKED, emptySet()).orEmpty()

    private fun normalize(input: String): String = input.trim().uppercase()

    private fun token(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private companion object {
        const val PREFERENCES = "plus_license_debug"
        const val KEY_LICENSES = "licenses"
        const val KEY_REVOKED = "revoked"
        const val KEY_ACTIVE = "active"
        const val KEY_ISSUED_AT = "issued_at"
    }
}
