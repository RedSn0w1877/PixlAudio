package com.theveloper.pixelplay.data.service.wear

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.shared.WearCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Answers "is a watch running PixelPlay reachable?" cheaply, so the service can skip building and
 * publishing Wear state (a DataStore read, a lyrics DB read, an O(queue) revision hash and a Data
 * Layer IPC per play/pause or skip) when there is no watch at all.
 *
 * The answer is cached. A capability listener keeps it current and calls [onWatchAppeared] when
 * a watch becomes reachable, so the owner can publish the current state right away instead of
 * waiting for the next player event. The cache is also re-checked at most once a minute in case a
 * listener callback was missed.
 */
internal class WearPresenceMonitor(
    context: Context,
    private val onWatchAppeared: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }

    @Volatile
    private var reachable: Boolean? = null

    @Volatile
    private var checkedAtMs = 0L

    private var listening = false

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        val nowReachable = info.nodes.isNotEmpty()
        val wasReachable = reachable
        reachable = nowReachable
        checkedAtMs = SystemClock.elapsedRealtime()
        if (nowReachable && wasReachable != true) {
            onWatchAppeared()
        }
    }

    fun start() {
        if (listening) return
        listening = true
        runCatching {
            capabilityClient.addListener(capabilityListener, WearCapabilities.PIXELPLAY_WEAR_APP)
        }.onFailure { error ->
            listening = false
            Timber.tag(TAG).d(error, "Wear capability listener unavailable")
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching {
            capabilityClient.removeListener(capabilityListener, WearCapabilities.PIXELPLAY_WEAR_APP)
        }
    }

    suspend fun isWatchReachable(): Boolean {
        val cached = reachable
        if (cached != null && SystemClock.elapsedRealtime() - checkedAtMs < RECHECK_INTERVAL_MS) {
            return cached
        }
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                capabilityClient
                    .getCapability(WearCapabilities.PIXELPLAY_WEAR_APP, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
                    .isNotEmpty()
            }.getOrElse { error ->
                // No Wear OS / Play services Wearable API on this phone: there is no watch.
                Timber.tag(TAG).v(error, "Wear capability check failed")
                false
            }
        }
        reachable = fresh
        checkedAtMs = SystemClock.elapsedRealtime()
        return fresh
    }

    private companion object {
        const val TAG = "WearPresenceMonitor"
        const val RECHECK_INTERVAL_MS = 60_000L
    }
}
