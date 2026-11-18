package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Brain
import com.theveloper.pixelplay.brain.GradeTracker
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.SessionState
import com.theveloper.pixelplay.data.brain.store.AffinityEntity
import com.theveloper.pixelplay.data.brain.store.BrainStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A track of this session the engine may seed a radio from, with what a radio request needs. */
data class AnchorInfo(
    val rid: Long,
    val mediaId: String,
    val title: String,
    /** ONE artist name. */
    val primaryArtist: String,
    val spotifyId: String?,
    val durationMs: Long,
)

/** Rebuilds the affinity tables from the log, the imported history and the library facts (4.4). */
interface StateRebuilder {
    /** True when the stored rows are missing, were built by other logic, or the import changed. */
    suspend fun isNeeded(): Boolean

    suspend fun rebuild(nowMs: Long): Rebuilt

    class Rebuilt(
        val state: Affinity.State,
        /** Ids of the stored events the rebuild read that started in its last day: see [BrainEngine]. */
        val recentEventIds: Set<Long>,
        /** Written to `brain_state` together with the rows. */
        val stateValues: Map<String, String>,
    )
}

/**
 * The brain's in-memory state and the one lock that guards it (docs/brain-spec.md, 2.3, 5.13).
 *
 * Holds the affinity tables (loaded from `brain.db` the first time the brain is needed, never at app
 * start), the grading context and the session. Everything that reads or changes them goes through
 * [withState], on a background thread, one caller at a time.
 *
 * The tables are a cache of the event log. When they have to be rebuilt (first use: the one-shot
 * migration; a new spec version; a changed import; a clock that jumped) the rebuild runs beside the
 * live state, which keeps serving; plays recorded meanwhile are applied to the new tables before they
 * replace the old ones.
 */
@Singleton
class BrainEngine @Inject constructor(
    private val store: BrainStore,
    private val rebuilder: dagger.Lazy<StateRebuilder>,
    private val clock: BrainClock,
) {
    class Loaded internal constructor(affinity: Affinity.State) {
        var affinity: Affinity.State = affinity
            internal set
        var tracker: GradeTracker = GradeTracker(affinity.completions)
            internal set
        var session: SessionState = SessionState()
            internal set

        /** What a radio request needs for each of the session's anchors, by rid. */
        val anchors: HashMap<Long, AnchorInfo> = HashMap()

        /** Which way three skipped picks turned the engine, for this session. */
        var direction: Direction = Direction()
            private set

        /** Starts a new session: the old one's lists, blocks, anchors and direction are gone. */
        fun newSession() {
            session = SessionState()
            anchors.clear()
            direction = Direction()
        }
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded: Loaded? = null
    private var rebuildJob: Job? = null
    private var recordedDuringRebuild: ArrayList<PlayEvent>? = null

    /** True once the state is in memory. A caller on a hot path may use this to avoid being the first load. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /** Runs [block] with the state, off the main thread, one caller at a time. Loads the state on first use. */
    suspend fun <T> withState(block: suspend (Loaded) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(ensureLoadedLocked()) }
    }

    private fun ensureLoadedLocked(): Loaded {
        loaded?.let { return it }
        val startedNanos = System.nanoTime()
        val state = Affinity.State()
        store.loadAffinity(state)
        state.drainTouched { _, _ -> }
        // How long the first use of the brain costs (never at app start: see the class comment).
        com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics.recordTiming(
            "brain_state_load", (System.nanoTime() - startedNanos) / 1_000_000
        )
        if (store.getState(BrainStore.KEY_FIRST_USE_MS) == null) {
            store.putState(BrainStore.KEY_FIRST_USE_MS, clock.nowMs().toString())
        }
        val fresh = Loaded(state)
        loaded = fresh
        isLoaded = true
        requestRebuild(force = false)
        return fresh
    }

    /**
     * One graded play into the state and the log, in one transaction: the event row and the affinity
     * rows it touched. Returns null when the event was already stored (nothing changes then). Must be
     * called inside [withState].
     */
    fun applyLocked(state: Loaded, event: PlayEvent): Reward.Graded? {
        if (store.eventIdentityAt(event.eventId) != null) return null
        val graded = Brain.onEvent(state.affinity, state.tracker, state.session, event)
        val rows = ArrayList<AffinityEntity>(12)
        val seen = HashSet<Pair<Int, Long>>()
        state.affinity.drainTouched { family, key ->
            if (seen.add(family to key)) BrainStore.rowOf(state.affinity, family, key)?.let(rows::add)
        }
        store.transaction {
            store.insertEvent(event)
            store.upsertAffinity(rows)
        }
        recordedDuringRebuild?.add(event)
        if (Affinity.clockJumpedBack(state.affinity, clock.nowMs())) requestRebuild(force = true)
        return graded
    }

    /** Asks for a rebuild in the background. Without [force] it only runs when [StateRebuilder.isNeeded]. */
    @Synchronized
    fun requestRebuild(force: Boolean) {
        if (rebuildJob?.isActive == true) return
        rebuildJob = scope.launch {
            try {
                val rebuilder = rebuilder.get()
                if (!force && !rebuilder.isNeeded()) return@launch
                mutex.withLock { recordedDuringRebuild = ArrayList() }
                val rebuilt = rebuilder.rebuild(clock.nowMs())
                mutex.withLock {
                    val live = recordedDuringRebuild.orEmpty()
                    recordedDuringRebuild = null
                    val state = loaded ?: return@withLock
                    // Plays that ended while the rebuild ran and that it did not read from the log.
                    val tracker = GradeTracker(rebuilt.state.completions)
                    for (event in live) {
                        if (event.eventId in rebuilt.recentEventIds) continue
                        Affinity.applyEvent(rebuilt.state, event, tracker.grade(event), event.startedAtMs)
                    }
                    rebuilt.state.drainTouched { _, _ -> }
                    store.transaction {
                        store.replaceAffinity(BrainStore.allRows(rebuilt.state))
                        for ((key, value) in rebuilt.stateValues) store.putState(key, value)
                    }
                    state.affinity = rebuilt.state
                    state.tracker = tracker
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordedDuringRebuild = null
                Timber.w(e, "brain: rebuilding the state failed; the stored state keeps serving")
            }
        }
    }

    /** Waits for a rebuild that is running. For tests and for the debug screen; nothing in the app blocks on it. */
    suspend fun awaitRebuild() {
        rebuildJob?.join()
    }
}
