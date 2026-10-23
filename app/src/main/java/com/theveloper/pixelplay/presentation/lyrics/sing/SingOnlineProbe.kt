package com.theveloper.pixelplay.presentation.lyrics.sing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this phone has a working internet connection right now, for Sing's choice between the
 * cloud and this phone. A thin class so the state holder's tests can stand in for it.
 */
@Singleton
class SingOnlineProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
