package com.theveloper.pixelplay.data.premium

import android.net.Uri
import com.theveloper.pixelplay.BuildConfig
import java.util.Locale

/**
 * Configuration for the direct/GitHub distribution. The URL is injected at build time with
 * -PplusCheckoutUrl and is intentionally empty in source builds. No payment secret belongs in
 * the application; the hosted service must validate the amount and issue a signed entitlement.
 */
object PlusCheckoutConfig {
    val isConfigured: Boolean
        get() = checkoutBaseUrl != null

    private val checkoutBaseUrl: Uri?
        get() = BuildConfig.PLUS_CHECKOUT_URL
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }

    fun urlForSupportAmount(amountCents: Int): String? {
        val base = checkoutBaseUrl ?: return null
        val amount = PremiumFeatureRegistry.normalizeSupportAmountCents(amountCents)
        return base.buildUpon()
            .appendQueryParameter("amount", String.format(Locale.US, "%.2f", amount / 100.0))
            .appendQueryParameter("currency", "USD")
            .build()
            .toString()
    }
}
