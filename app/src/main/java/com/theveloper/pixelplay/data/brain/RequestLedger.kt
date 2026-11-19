package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.HostBudget
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
 * The YouTube requests of the last 24 hours, one timestamp each, kept in `brain_state` so a process
 * restart cannot give the day a new budget (docs/brain-spec.md, 2.1, 3.7). It counts REQUESTS (every
 * token the bucket gives out), not stored lists: an artist radio is two requests, a station start up to
 * three, a discography or a channel lookup one each.
 *
 * Nothing is read until the first request is about to go out (`brain.db` stays closed at app start); the
 * rows are then merged with what was spent in this process.
 */
@Singleton
class RequestLedger @Inject constructor(
    private val states: BrainStateDao,
    private val clock: BrainClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loaded = false
    private val stamps = ArrayList<Long>()

    /** Requests taken from the bucket and not yet merged: counted at once, saved a moment later. */
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /** One request is about to go out. Not suspending: called from the budget's token take. */
    fun record(nowMs: Long = clock.nowMs()) {
        pending.add(nowMs)
        scope.launch {
            lock.withLock {
                loadLocked()
                drainLocked()
                trimLocked(nowMs)
                save()
            }
        }
    }

    /** Requests made at or after [sinceMs]. */
    suspend fun countSince(sinceMs: Long): Int = lock.withLock {
        loadLocked()
        drainLocked()
        stamps.count { it >= sinceMs }
    }

    /** True while the day still has requests left (the daily limit of the host, whoever spent them). */
    suspend fun hasRoom(): Boolean = countSince(clock.nowMs() - HostBudget.DAY_MS) < HostBudget.DAILY_LIMIT

    private suspend fun loadLocked() {
        if (loaded) return
        try {
            val text = states.get(KEY)?.toString(Charsets.UTF_8).orEmpty()
            val saved = if (text.isEmpty()) emptyList() else text.split(',').mapNotNull { it.toLongOrNull() }
            // What this process spent before the read is already in [stamps]; the rest comes from the row.
            stamps.addAll(saved)
            stamps.sort()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: the request ledger could not be read")
        }
        loaded = true
    }

    private fun drainLocked() {
        while (true) stamps.add(pending.poll() ?: break)
    }

    private fun trimLocked(nowMs: Long) {
        val keepAfter = nowMs - HostBudget.DAY_MS
        stamps.removeAll { it < keepAfter }
        while (stamps.size > MAX_KEPT) stamps.removeAt(0)
    }

    private suspend fun save() {
        try {
            states.put(BrainStateEntity(KEY, stamps.joinToString(",").toByteArray(Charsets.UTF_8)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: the request ledger could not be saved")
        }
    }

    private companion object {
        const val KEY = "request_ledger"
        const val MAX_KEPT = 240
    }
}
