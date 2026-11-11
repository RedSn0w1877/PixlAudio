package com.theveloper.pixelplay.data.spatial.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The library's small pieces of state outside the job list: the failures behind the §6.1 backoff (kept across
// restarts, so a song that fails isn't sent again and again), and the network as it changes.

/**
 * Failures per song and profile, for the backoff of §6.1 (10 minutes, 1 hour, 1 day). A success clears them and is
 * remembered, so a failure from before it (a failed cloud job's record, read again and again, also after a restart) is
 * never counted again. Persisted through [load]/[save] (a small preference file in the app, nothing in tests), kept
 * small: at most [MAX_ENTRIES], and nothing older than [FORGET_AFTER_MS].
 */
class SpatialFailureLedger(
    private val load: () -> Map<String, String> = { emptyMap() },
    private val save: (Map<String, String>) -> Unit = {},
) {
    /** One song and profile: the failures in a row (0 after a success), the last one's time, and the last success's. */
    private data class Entry(val count: Int, val lastFailedAtMs: Long, val clearedAtMs: Long) {
        val failure: SpatialFailure? get() = if (count > 0) SpatialFailure(count, lastFailedAtMs) else null
        val latestMs: Long get() = maxOf(lastFailedAtMs, clearedAtMs)
    }

    private val entries: MutableMap<String, Entry> by lazy {
        HashMap<String, Entry>().apply {
            for ((name, value) in load()) decode(value)?.let { put(name, it) }
        }
    }

    private val _version = MutableStateFlow(0L)
    /** Changes with every write, so status flows read the ledger again. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun failure(key: SpatialRenderKey): SpatialFailure? = entries[name(key)]?.failure

    /**
     * Records a failure of [key] that happened at [atMs]. One not newer than the last failure (already counted) or than
     * the last success (superseded by it) changes nothing, so reading the same job's record again is harmless. Returns
     * the failure recorded now, or null when there is none.
     */
    @Synchronized
    fun recordFailure(key: SpatialRenderKey, atMs: Long): SpatialFailure? {
        val name = name(key)
        val before = entries[name]
        if (before != null && (atMs <= before.lastFailedAtMs || atMs <= before.clearedAtMs)) return before.failure
        val after = Entry((before?.count ?: 0) + 1, atMs, before?.clearedAtMs ?: 0L)
        // A new entry a full ledger would drop at once changes nothing: no write.
        if (before == null && wouldDropAtOnce(atMs)) return after.failure
        entries[name] = after
        prune(atMs)
        persist()
        return after.failure
    }

    /**
     * [key] worked at [atMs]: a failure from before that is forgotten (the next one starts the ladder again), and so is
     * any failure from before it reported later.
     */
    @Synchronized
    fun clear(key: SpatialRenderKey, atMs: Long) {
        val name = name(key)
        val before = entries[name]
        if (before != null && (before.lastFailedAtMs >= atMs || before.clearedAtMs >= atMs)) return
        // The job list keeps delivered jobs 30 days and is read on every change: a success older than everything a full
        // ledger keeps would be dropped at once, so it isn't written (and the whole file isn't rewritten for it).
        if (before == null && wouldDropAtOnce(atMs)) return
        entries[name] = Entry(0, before?.lastFailedAtMs ?: 0L, atMs)
        prune(atMs)
        persist()
    }

    /**
     * A new entry at [atMs] would be the first [prune] drops: the ledger is full and every entry in it is at least as
     * new (none of them is old enough to forget then, so nothing else would change either).
     */
    private fun wouldDropAtOnce(atMs: Long): Boolean =
        entries.size >= MAX_ENTRIES && entries.values.all { atMs <= it.latestMs }

    private fun prune(nowMs: Long) {
        entries.entries.removeAll { nowMs - it.value.latestMs > FORGET_AFTER_MS }
        if (entries.size > MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.latestMs }.take(entries.size - MAX_ENTRIES)
                .map { it.key }.forEach { entries.remove(it) }
        }
    }

    private fun persist() {
        save(entries.mapValues { (_, entry) -> "${entry.count},${entry.lastFailedAtMs},${entry.clearedAtMs}" })
        _version.value = _version.value + 1
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val FORGET_AFTER_MS = 30L * 86_400_000

        fun name(key: SpatialRenderKey): String = "${key.videoId}/${key.profile.id}"

        fun key(name: String): SpatialRenderKey? {
            val profile = SpatialProfile.fromId(name.substringAfter('/', "")) ?: return null
            return SpatialRenderKey(name.substringBefore('/'), profile)
        }

        private fun decode(value: String): Entry? {
            val parts = value.split(',')
            if (parts.size < 2) return null
            val count = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            return Entry(count, at, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
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
 * thread; each change reads the facts once ([StreamNetworkConditions], Data Saver included). The first read and the
 * registration happen on [scope] (binder calls stay off the thread that created the library).
 */
class LiveSpatialNetworkSource(
    context: Context,
    private val conditions: StreamNetworkConditions,
    scope: CoroutineScope,
) : SpatialNetworkSource {
    private val _network = MutableStateFlow(SpatialNetwork())
    override val network: StateFlow<SpatialNetwork> = _network.asStateFlow()

    init {
        scope.launch {
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
