package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.SeedDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * "Getting to know your music" (docs/brain-spec.md, 8.10): the first minute of a listener with a sizeable library. Eight radios, for
 * the day's seeds of the listener's own music (at most two by one artist), are fetched once so the first mixes have something to
 * be built from; Home shows how far it is ("3 of 8"). It is the lane 1 of the refresh worker run in the foreground, the same code
 * under the same budget and switches, and it does nothing once eight lists are cached.
 *
 * Not a second background job: it runs while Home is on screen, in the process's own scope, and ends by itself.
 */
@Singleton
class ColdStart @Inject constructor(
    private val work: AppRefreshWork,
    private val seedDao: SeedDao,
    private val switch: BrainSwitch,
    private val states: com.theveloper.pixelplay.data.brain.store.BrainStateDao,
) {
    /** What the strip shows; null while there is nothing to show. */
    data class Progress(val done: Int, val total: Int)

    private val mutable = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = mutable.asStateFlow()

    @Volatile private var running = false

    /** Runs once per process at most, and only while fewer than [TARGET] lists are cached. Safe to call again. */
    suspend fun run() {
        if (running) return
        running = true
        try {
            if (!switch.refreshAllowed()) return
            val have = seedDao.healthyListCount()
            if (have >= TARGET) return
            if (work.requestsLeft() <= 0) return
            val targets = work.radioSeeds().take(TARGET)
            if (targets.isEmpty()) return
            val total = targets.size
            var done = 0
            mutable.value = Progress(0, total)
            val gate = Semaphore(PARALLEL)
            coroutineScope {
                targets.map { target ->
                    async {
                        gate.withPermit {
                            if (switch.refreshAllowed() && work.requestsLeft() > 0) work.radio(target)
                        }
                        synchronized(this@ColdStart) { done++; mutable.value = Progress(done, total) }
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: getting to know the music failed")
        } finally {
            // Once finished the strip goes; what was fetched stays cached.
            mutable.value = null
            running = false
        }
    }

    companion object {
        /** Eight radios (8.10). */
        const val TARGET = 8

        /** The spec runs 6 in parallel; the YouTube budget of stage 1 allows 2 (deviation 156). */
        const val PARALLEL = 2
    }
}
