package com.theveloper.pixelplay.data.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the network facts [StreamPrefetchPolicy] decides on, at decision time (no callback to
 * keep registered). Two binder calls, so callers run it off the main thread.
 *
 * Data Saver is Android's "use less data" switch, the same role as iOS Low Data Mode. A
 * foreground media service is exempt from the system's own background restriction, so the
 * app has to honour it itself.
 */
@Singleton
class StreamNetworkConditions @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(ConnectivityManager::class.java)
    }

    fun current(): StreamPrefetchPolicy.Conditions {
        val manager = connectivityManager
            ?: return StreamPrefetchPolicy.Conditions(StreamPrefetchPolicy.Network.OFFLINE, metered = true, dataSaver = false)
        val dataSaver = runCatching {
            manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
        val capabilities = runCatching { manager.activeNetwork?.let(manager::getNetworkCapabilities) }.getOrNull()
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return StreamPrefetchPolicy.Conditions(StreamPrefetchPolicy.Network.OFFLINE, metered = true, dataSaver = dataSaver)
        }
        val network = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> StreamPrefetchPolicy.Network.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> StreamPrefetchPolicy.Network.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> StreamPrefetchPolicy.Network.CELLULAR
            else -> StreamPrefetchPolicy.Network.OTHER
        }
        // TEMPORARILY_NOT_METERED (API 30, our minSdk): carriers can lift metering, e.g. 5G
        // plans that count as unlimited for a while.
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        return StreamPrefetchPolicy.Conditions(network, metered, dataSaver)
    }
}
