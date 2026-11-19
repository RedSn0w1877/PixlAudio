package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.ContextLift
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.data.brain.store.BrainStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The context table of docs/brain-spec.md, 5.10: how much of what the listener plays at each of eight times of the week belongs to each of
 * their islands, from the event log. Worked out in the background when the Home plan needs it (a daypart boundary at most), read once through
 * the log and kept in memory for a few minutes; never at app start and never on the main thread.
 *
 * An imported play carries no timezone: its hour is read with the device's offset now (the spec's default when the listener has set no home
 * timezone), and so is a live play from before the offset was recorded.
 */
@Singleton
class ContextStore @Inject constructor(
    private val store: BrainStore,
    private val islandStore: IslandStore,
    private val clock: BrainClock,
) {
    private class Cached(val atMs: Long, val islandFitAtMs: Long, val table: ContextLift.Table)

    @Volatile private var cached: Cached? = null

    /** The table for [nowMs]; null while there are no islands to count plays of. */
    suspend fun table(nowMs: Long = clock.nowMs()): ContextLift.Table? = withContext(Dispatchers.IO) {
        val islands = islandStore.current() ?: return@withContext null
        if (islands.isEmpty) return@withContext null
        cached?.let { if (nowMs - it.atMs in 0 until CACHE_MS && it.islandFitAtMs == islands.fitAtMs) return@withContext it.table }
        val device = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
        val builder = ContextLift.Builder(nowMs)
        var after = Long.MIN_VALUE
        while (true) {
            val page = store.eventsAfter(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().eventId
            for (e in page) {
                if (e.has(EventFlags.LEARN_EXCLUDED) || e.has(EventFlags.NEVER_AUDIBLE)) continue
                val island = islands.memberOf[e.rid] ?: continue
                val g = Reward.grade(e, Reward.Context())
                builder.add(island, e.startedAtMs, if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) device else e.tzOffsetMin, g.c, g.r)
            }
            if (page.size < PAGE) break
        }
        val table = builder.build()
        cached = Cached(nowMs, islands.fitAtMs, table)
        table
    }

    private companion object {
        const val PAGE = 2_000
        const val CACHE_MS = 30L * 60_000L
    }
}
