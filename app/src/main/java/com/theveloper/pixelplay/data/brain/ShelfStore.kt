package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.HomePlanner
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.ShelfBandit
import com.theveloper.pixelplay.brain.ShelfBanditCodec
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.brain.store.ImpressionDao
import com.theveloper.pixelplay.data.brain.store.ImpressionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The Home shelf bandit's side of the app (docs/brain-spec.md, 5.10, 8.1): the compact impressions of each visit to the page, the cascade
 * bandit's posteriors, and the order the Home planner takes from it.
 *
 * Nothing here runs on the main thread or at app start. The page reports what happened (shown, a shelf on screen for a second, something
 * started from a shelf); a visit is given to the bandit later ([settle]), when it is over and the log shows whether what the listener started
 * was played for 30 seconds. The order is used only while the gate has the learned weights on, and "Reset what I've learned" clears all of it.
 */
@Singleton
class ShelfStore @Inject constructor(
    private val dao: ImpressionDao,
    private val store: BrainStore,
    private val engine: BrainEngine,
    private val clock: BrainClock,
) {
    private val lock = Mutex()
    private var state: ShelfBandit.State? = null

    /** The visit on screen now: its id, the shelf ids by position and the deepest position known to be seen. */
    private var visitId = 0L
    private var visitShelves: List<String> = emptyList()
    private var seenDepth = -1
    private val activated = HashSet<Int>()

    private suspend fun loadedState(): ShelfBandit.State {
        state?.let { return it }
        val loaded = store.getState(KEY_SHELF_BANDIT)?.let(ShelfBanditCodec::decode) ?: ShelfBandit.newState()
        state = loaded
        return loaded
    }

    private fun save(s: ShelfBandit.State) {
        store.putState(KEY_SHELF_BANDIT, ShelfBanditCodec.encode(s))
    }

    /**
     * The page was shown with [types] (the shelf type of each shelf, in page order) whose ids are [ids]. The same page shown again within
     * [SAME_VISIT_MS] is the same visit. Gives earlier visits to the bandit first. Never throws.
     */
    suspend fun onPlanShown(ids: List<String>, types: IntArray, daypart: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (ids.isEmpty() || ids.size != types.size) return@withLock
                val now = clock.nowMs()
                if (visitId != 0L && now - visitId in 0 until SAME_VISIT_MS && visitShelves == ids) return@withLock
                settleLocked(now)
                visitId = now
                visitShelves = ids
                seenDepth = -1
                activated.clear()
                dao.insertAll(ids.indices.map { i -> ImpressionEntity(now, i, types[i], daypart, now, 0, 0L, 0) })
                dao.pruneOlderThan(now - ImpressionDao.KEEP_DAYS * BrainMath.DAY_MS)
                dao.keepNewestVisits(ImpressionDao.KEEP_VISITS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: recording a Home visit failed")
            }
        }
    }

    /** The shelf [shelfId] was on screen for at least a second: the page was looked at down to it. */
    suspend fun onShelfSeen(shelfId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val position = visitShelves.indexOf(shelfId)
                if (visitId == 0L || position < 0 || position <= seenDepth) return@withLock
                seenDepth = position
                dao.markSeenThrough(visitId, position)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: recording a seen shelf failed")
            }
        }
    }

    /** Something was started from the shelf [shelfId] (a card or a song): it is the shelf of this visit, unless the visit already has one. */
    suspend fun onShelfActivated(shelfId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val position = visitShelves.indexOf(shelfId)
                if (visitId == 0L || position < 0 || !activated.add(position)) return@withLock
                dao.markActivated(visitId, position, clock.nowMs())
                if (position > seenDepth) {
                    seenDepth = position
                    dao.markSeenThrough(visitId, position)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: recording a started shelf failed")
            }
        }
    }

    /** Gives every finished visit to the bandit. Safe to call any time (the refresh worker calls it); never throws. */
    suspend fun settle(nowMs: Long = clock.nowMs()) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                settleLocked(nowMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: settling Home visits failed")
            }
        }
    }

    private suspend fun settleLocked(nowMs: Long) {
        // A visit is over once the page has been left alone for a while; the one on screen now is not.
        val rows = dao.unsettled(nowMs - SAME_VISIT_MS)
        if (rows.isEmpty()) return
        val s = loadedState()
        val visits = ArrayList<Long>()
        var i = 0
        var moved = false
        while (i < rows.size) {
            var j = i
            while (j < rows.size && rows[j].visitId == rows[i].visitId) j++
            val group = rows.subList(i, j)
            visits.add(group[0].visitId)
            val shown = IntArray(group.size) { group[it].shelf }
            var lastSeen = -1
            var used = -1
            for (k in group.indices) {
                if (group[k].seen == 1) lastSeen = k
                if (group[k].activatedAtMs > 0L && used < 0 && wasPlayedThirtySeconds(group[k].activatedAtMs)) used = k
                // Started but not played for 30 seconds: the listener looked at it, no reward.
                if (group[k].activatedAtMs > 0L && k > lastSeen) lastSeen = k
            }
            val today = BrainMath.localDay(group[0].shownAtMs, java.util.TimeZone.getDefault().getOffset(group[0].shownAtMs) / 60_000)
            if (ShelfBandit.visit(s, group[0].daypart, shown, lastSeen, used, today) > 0) moved = true
            i = j
        }
        dao.settle(visits)
        if (moved) save(s)
    }

    /** True when the first play that started within two minutes of [activatedAtMs] was heard for at least 30 seconds (or to its end). */
    private fun wasPlayedThirtySeconds(activatedAtMs: Long): Boolean {
        val after = (activatedAtMs shl 16) - 1L
        for (e in store.eventsAfter(after, FIRST_EVENTS)) {
            if (e.startedAtMs < activatedAtMs) continue
            if (e.startedAtMs > activatedAtMs + PLAY_WINDOW_MS) return false
            return e.endReason == EndReason.TRACK_DONE || e.playedMs.toLong() >= ShelfBandit.REWARD_PLAY_MS
        }
        return false
    }

    /**
     * The shelf types [types] (in stage 2's fixed order) in the order the bandit draws for this daypart, or null to keep the fixed order: no
     * data yet, or the gate has the prior on. The draw is frozen for the daypart ([ShelfBandit.order]).
     */
    suspend fun order(types: IntArray, planKey: Long, daypart: Int, salt: Long, nowMs: Long): IntArray? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val learned = engine.withState { it.learner.useLearned }
                if (!learned) return@withLock null
                val s = loadedState()
                val tz = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
                val today = BrainMath.localDay(nowMs, tz)
                val before = s.frozenKey
                val order = ShelfBandit.order(s, planKey, daypart, types, Ranker.listSeed(salt, "shelves:$planKey", today), today)
                if (s.frozenKey != before) save(s)
                order
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: ordering the Home shelves failed; the fixed order stays")
                null
            }
        }
    }

    /** "Reset what I've learned": the posteriors go back to their priors and the impressions are forgotten. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                dao.clear()
                state = ShelfBandit.newState()
                store.putState(KEY_SHELF_BANDIT, null)
                visitId = 0L
                visitShelves = emptyList()
                seenDepth = -1
                activated.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: clearing the shelf bandit failed")
            }
        }
    }

    /** The posteriors, for a screen that wants to show them and for tests. */
    suspend fun snapshot(): ShelfBandit.State = lock.withLock { loadedState().copy() }

    companion object {
        const val KEY_SHELF_BANDIT = "shelf_bandit_v1"

        /** A page left alone this long is a finished visit; the same page shown again within it is the same visit. */
        const val SAME_VISIT_MS = 30L * 60_000L

        /** A play that starts within this long of a tap is the one the tap started. */
        const val PLAY_WINDOW_MS = 2L * 60_000L
        private const val FIRST_EVENTS = 8
    }
}

