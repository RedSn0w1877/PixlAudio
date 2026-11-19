package com.theveloper.pixelplay.data.brain.source

import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * The requests of the last 24 hours to each open-data host, one timestamp each, in `brain_state` (`request_ledger_<host>`), so a
 * process restart cannot give a host's day a new ceiling (docs/brain-spec.md, 3.7). The same idea as `RequestLedger`, which keeps
 * YouTube's. Nothing is read until a request is about to go out or the Developer screen asks.
 */
@Singleton
class KnowledgeLedger @Inject constructor(
    private val states: BrainStateDao,
    private val clock: BrainClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val loaded = HashSet<KnowledgeHost>()
    private val stamps = HashMap<KnowledgeHost, ArrayList<Long>>()
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pair<KnowledgeHost, Long>>()

    /** One request to [host] is about to go out. Not suspending: called as the budget gives out its token. */
    fun record(host: KnowledgeHost, nowMs: Long = clock.nowMs()) {
        pending.add(host to nowMs)
        scope.launch {
            lock.withLock {
                loadLocked(host)
                drainLocked()
                trimLocked(host, nowMs)
                save(host)
            }
        }
    }

    suspend fun countSince(host: KnowledgeHost, sinceMs: Long): Int = lock.withLock {
        loadLocked(host)
        drainLocked()
        stamps[host].orEmpty().count { it >= sinceMs }
    }

    /** The day's requests per host, newest 24 hours, for the Developer screen. */
    suspend fun today(): Map<KnowledgeHost, Int> {
        val since = clock.nowMs() - HostBudget.DAY_MS
        return KnowledgeHost.entries.associateWith { countSince(it, since) }
    }

    suspend fun hasRoom(host: KnowledgeHost): Boolean = countSince(host, clock.nowMs() - HostBudget.DAY_MS) < host.dailyCeiling

    /** When the host was last asked, or 0. */
    suspend fun lastRequestMs(host: KnowledgeHost): Long = lock.withLock {
        loadLocked(host)
        drainLocked()
        stamps[host]?.maxOrNull() ?: 0L
    }

    private suspend fun loadLocked(host: KnowledgeHost) {
        if (!loaded.add(host)) return
        try {
            val text = states.get(key(host))?.toString(Charsets.UTF_8).orEmpty()
            val saved = if (text.isEmpty()) emptyList() else text.split(',').mapNotNull { it.toLongOrNull() }
            val list = stamps.getOrPut(host) { ArrayList() }
            list.addAll(saved)
            list.sort()
        } catch (e: CancellationException) {
            loaded.remove(host)
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: the request ledger of %s could not be read", host.key)
        }
    }

    private fun drainLocked() {
        while (true) {
            val (host, at) = pending.poll() ?: break
            stamps.getOrPut(host) { ArrayList() }.add(at)
        }
    }

    private fun trimLocked(host: KnowledgeHost, nowMs: Long) {
        val list = stamps[host] ?: return
        val keepAfter = nowMs - HostBudget.DAY_MS
        list.removeAll { it < keepAfter }
        while (list.size > MAX_KEPT) list.removeAt(0)
    }

    private suspend fun save(host: KnowledgeHost) {
        try {
            states.put(BrainStateEntity(key(host), stamps[host].orEmpty().joinToString(",").toByteArray(Charsets.UTF_8)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: the request ledger of %s could not be saved", host.key)
        }
    }

    private fun key(host: KnowledgeHost) = "request_ledger_${host.key}"

    private companion object {
        const val MAX_KEPT = 700
    }
}
