package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.brain.store.FeedbackDao
import com.theveloper.pixelplay.data.brain.store.FeedbackEntity
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * "Don't count this" (docs/brain-spec.md, 8.5, 5.2 "Undo"): plays that should not shape the picks. The
 * plays are flagged `learnExcluded`, what they added to the affinity tables is taken out at once
 * (`Affinity.subtract`), and a full rebuild from the log follows in the background, which makes the
 * tables exact. Nothing is deleted: the plays stay in the log and in Recently Played.
 *
 * The label the listener sees must not say "learn" (decision D15): the control reads "Don't count this".
 */
@Singleton
class LearnExclusion @Inject constructor(
    private val engine: BrainEngine,
    private val store: BrainStore,
    private val feedback: FeedbackDao,
    private val tracker: dagger.Lazy<ListeningStatsTracker>,
    private val clock: BrainClock,
) {
    /** What was excluded: the events, and the `feedback` row that records it. */
    class Excluded(val feedbackId: Long, val eventIds: List<Long>) {
        val count: Int get() = eventIds.size
    }

    /** All the plays of the recordings in [rids]: a song, or the songs of an album or a playlist. */
    suspend fun excludeRecordings(rids: Collection<Long>, targetKind: Int, targetId: Long, playingSongId: String?): Excluded? {
        if (rids.isEmpty()) return null
        playingSongId?.let(::flagPlaying)
        return exclude(targetKind, targetId) { store.eventsOfRecordings(rids) }
    }

    /** Every live play of the last [windowMs] (an hour by default). */
    suspend fun excludeLastHour(playingSongId: String?, windowMs: Long = HOUR_MS): Excluded? {
        playingSongId?.let(::flagPlaying)
        val since = clock.nowMs() - windowMs
        return exclude(FeedbackKinds.TARGET_LAST_HOUR, since) { store.eventsStartedSince(since) }
    }

    /** Takes [excluded] back: the plays count again, from the rebuild that follows. */
    suspend fun undo(excluded: Excluded) {
        try {
            engine.withState {
                store.transaction { store.setEventFlag(excluded.eventIds, EventFlags.LEARN_EXCLUDED, on = false) }
            }
            feedback.undo(excluded.feedbackId, clock.nowMs())
            engine.requestRebuild(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: undoing don't-count failed")
        }
    }

    private suspend fun exclude(targetKind: Int, targetId: Long, find: () -> List<PlayEvent>): Excluded? {
        val now = clock.nowMs()
        val ids = engine.withState { state ->
            val fresh = find().filter { !it.has(EventFlags.LEARN_EXCLUDED) }
            if (fresh.isEmpty()) return@withState emptyList<Long>()
            for (e in fresh) {
                val g = Reward.grade(e, Reward.Context(0, 0))
                Affinity.subtract(state.affinity, e, g, if (e.startedAtMs > now) now else e.startedAtMs)
            }
            val rows = ArrayList<com.theveloper.pixelplay.data.brain.store.AffinityEntity>()
            val seen = HashSet<Pair<Int, Long>>()
            state.affinity.drainTouched { family, key ->
                if (seen.add(family to key)) BrainStore.rowOf(state.affinity, family, key)?.let(rows::add)
            }
            store.transaction {
                store.upsertAffinity(rows)
                store.setEventFlag(fresh.map { it.eventId }, EventFlags.LEARN_EXCLUDED, on = true)
            }
            fresh.map { it.eventId }
        }
        val id = now * 8 + FeedbackKinds.DONT_COUNT
        feedback.insert(FeedbackEntity(id, FeedbackKinds.DONT_COUNT, targetKind, targetId, now, null))
        // The quick subtraction is an approximation (the grade was made without its context); the rebuild is exact.
        engine.requestRebuild(force = true)
        return Excluded(id, ids)
    }

    /** The play in flight is flagged too, so it is logged as excluded when it ends. */
    private fun flagPlaying(songId: String) {
        runCatching { tracker.get().markFlag(songId, EventFlags.LEARN_EXCLUDED) }
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
