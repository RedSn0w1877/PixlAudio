package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Shares a signed URL between speculative preparation and the actual playback request. */
internal class StreamUrlCache<K : Any>(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val signedExpiryMs: (String) -> Long? = { null }
) {
    private data class Entry(val url: String, val expiresAtMs: Long)
    private class ResolutionLock(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val entries = ConcurrentHashMap<K, Entry>()
    private val locks = mutableMapOf<K, ResolutionLock>()

    suspend fun getOrResolve(id: K, maxAgeMs: Long, resolve: suspend () -> String?): String? {
        cached(id)?.let { return it }
        // Locks live only while a caller uses/waits for them. Unrelated tracks never
        // wait behind a speculative request just because their hash codes collide.
        val lock = synchronized(locks) { locks.getOrPut(id) { ResolutionLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock {
                cached(id)?.let { return@withLock it }
                val url = resolve()?.takeIf { it.isNotBlank() } ?: return@withLock null
                val now = clockMs()
                val expiresAt = minOf(now + maxAgeMs, signedExpiryMs(url) ?: Long.MAX_VALUE)
                if (expiresAt > now) {
                    if (entries.size >= 128) entries.entries.removeIf { it.value.expiresAtMs <= now }
                    if (entries.size >= 256) entries.keys.firstOrNull()?.let(entries::remove)
                    entries[id] = Entry(url, expiresAt)
                }
                url
            }
        } finally {
            synchronized(locks) {
                lock.users--
                if (lock.users == 0) locks.remove(id)
            }
        }
    }

    fun invalidate(id: K) { entries.remove(id) }
    fun clear() { entries.clear() }

    private fun cached(id: K): String? = entries[id]?.takeIf { it.expiresAtMs > clockMs() }?.url
}
