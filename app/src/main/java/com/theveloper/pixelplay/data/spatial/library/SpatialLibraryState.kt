package com.theveloper.pixelplay.data.spatial.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The library's small pieces of state outside the job list: the failures behind the §6.1 backoff (kept across
// restarts, so a song that fails isn't sent again and again), and the network as it changes.

/**
 * Failures per song and profile, for the backoff of §6.1 (10 minutes, 1 hour, 1 day). A success clears them. Persisted
 * through [load]/[save] (a small preference file in the app, nothing in tests), kept small: at most [MAX_ENTRIES], and
 * none older than [FORGET_AFTER_MS].
 */
class SpatialFailureLedger(
    private val load: () -> Map<String, String> = { emptyMap() },
    private val save: (Map<String, String>) -> Unit = {},
) {
    private val failures: MutableMap<String, SpatialFailure> by lazy {
        HashMap<String, SpatialFailure>().apply {
            for ((name, value) in load()) decode(value)?.let { put(name, it) }
        }
    }

    private val _version = MutableStateFlow(0L)
    /** Changes with every write, so status flows read the ledger again. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun failure(key: SpatialRenderKey): SpatialFailure? = failures[name(key)]

    /**
     * Records a failure of [key] that happened at [atMs]. One that is not newer than the last one recorded is already
     * counted (a failed cloud job's record is read again and again, also after a restart), so this is idempotent.
     * Returns what is recorded now.
     */
    @Synchronized
    fun recordFailure(key: SpatialRenderKey, atMs: Long): SpatialFailure {
        val name = name(key)
        val before = failures[name]
        if (before != null && atMs <= before.lastFailedAtMs) return before
        val after = SpatialFailure((before?.count ?: 0) + 1, atMs)
        failures[name] = after
        prune(atMs)
        persist()
        return after
    }

    /** [key] worked at [atMs]: a failure from before that is forgotten, and the next one starts the ladder again. */
    @Synchronized
    fun clear(key: SpatialRenderKey, atMs: Long = Long.MAX_VALUE) {
        val name = name(key)
        val before = failures[name] ?: return
        if (before.lastFailedAtMs >= atMs) return
        failures.remove(name)
        persist()
    }

    private fun prune(nowMs: Long) {
        failures.entries.removeAll { nowMs - it.value.lastFailedAtMs > FORGET_AFTER_MS }
        if (failures.size > MAX_ENTRIES) {
            failures.entries.sortedBy { it.value.lastFailedAtMs }.take(failures.size - MAX_ENTRIES)
                .map { it.key }.forEach { failures.remove(it) }
        }
    }

    private fun persist() {
        save(failures.mapValues { (_, failure) -> "${failure.count},${failure.lastFailedAtMs}" })
        _version.value = _version.value + 1
    }

    companion object {
        const val MAX_ENTRIES = 300
        const val FORGET_AFTER_MS = 30L * 86_400_000

        fun name(key: SpatialRenderKey): String = "${key.videoId}/${key.profile.id}"

        fun key(name: String): SpatialRenderKey? {
            val profile = SpatialProfile.fromId(name.substringAfter('/', "")) ?: return null
            return SpatialRenderKey(name.substringBefore('/'), profile)
        }

        private fun decode(value: String): SpatialFailure? {
            val parts = value.split(',')
            if (parts.size < 2) return null
            val count = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            return SpatialFailure(count, at)
        }

        /** The ledger in the app's `spatial_library` preference file (kept out of backups). */
        fun inPreferences(context: Context): SpatialFailureLedger {
            val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            return SpatialFailureLedger(
                load = {
                    prefs.all.mapNotNull { (name, value) ->
                        if (name.startsWith(PREFIX) && value is String) name.removePrefix(PREFIX) to value else null
                    }.toMap()
                },
                save = { entries ->
                    val editor = prefs.edit()
                    for (name in prefs.all.keys) if (name.startsWith(PREFIX)) editor.remove(name)
                    for ((name, value) in entries) editor.putString(PREFIX + name, value)
                    editor.apply()
                },
            )
        }

        /** Excluded from Android backups in `backup_rules.xml` / `data_extraction_rules.xml`. */
        const val PREFS_NAME = "spatial_library"
        private const val PREFIX = "fail:"
    }
}

/** The network as it changes, for the library's status and for starting what waited for it. */
interface SpatialNetworkSource {
    val network: StateFlow<SpatialNetwork>
}

/**
 * [SpatialNetworkSource] over Android's default-network callback. The callback runs on ConnectivityManager's own
 * thread; each change reads the facts once ([StreamNetworkConditions], Data Saver included).
 */
class LiveSpatialNetworkSource(
    context: Context,
    private val conditions: StreamNetworkConditions,
) : SpatialNetworkSource {
    private val _network = MutableStateFlow(SpatialNetwork())
    override val network: StateFlow<SpatialNetwork> = _network.asStateFlow()

    init {
        refresh()
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = refresh()
                }
            )
        }
    }

    private fun refresh() {
        val current = runCatching { conditions.current() }.getOrNull() ?: return
        _network.value = SpatialNetwork(
            connected = current.network != StreamPrefetchPolicy.Network.OFFLINE,
            unmetered = !current.metered,
            dataSaver = current.dataSaver,
        )
    }
}
