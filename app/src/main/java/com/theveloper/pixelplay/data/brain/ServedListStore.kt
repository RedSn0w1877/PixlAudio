package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Brain
import com.theveloper.pixelplay.brain.Repetition
import com.theveloper.pixelplay.data.brain.store.ServedListDao
import com.theveloper.pixelplay.data.brain.store.ServedListEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The served log (docs/brain-spec.md, 3.8 `served_list`, 6.4, 6.5): every generated list the app
 * served, kept 60 days. Three things read it:
 *  - the repetition budget: a recording is not in two mixes built on the same local day, and one served
 *    often in the last 30 days costs value in the next list ([Repetition]);
 *  - the saved mixes: the newest list of a surface built on today's local day IS today's mix, so a mix
 *    is stable for the day and comes back after a restart;
 *  - "Regenerate": how many lists a surface already had today.
 *
 * A log that cannot be read is an empty log, never a failed mix.
 */
@Singleton
class ServedListStore @Inject constructor(
    private val dao: ServedListDao,
    private val clock: BrainClock,
) {
    /** The mix of [surface] built on the local day of [nowMs], newest build, or null. */
    suspend fun todays(surface: Int, nowMs: Long, tzOffsetMin: Int): StoredMix? {
        val row = try {
            dao.latest(surface, dayStart(nowMs, tzOffsetMin)) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading the served log failed")
            return null
        }
        // A list built on today's day by a clock that was later set back is not today's.
        if (row.builtAt > nowMs + CLOCK_SLACK_MS) return null
        return MixCodec.decode(row.itemsBlob)
    }

    /** How many lists [surface] already got today: the draw number of the next "Regenerate". */
    suspend fun buildsToday(surface: Int, nowMs: Long, tzOffsetMin: Int): Int =
        runCatching { dao.countSince(surface, dayStart(nowMs, tzOffsetMin)) }.getOrDefault(0)

    /** Every list of the last [Repetition.RETENTION_MS], as the budget reads them. */
    suspend fun recent(nowMs: Long): List<Repetition.Served> {
        val rows = try {
            dao.since(Repetition.keepAfter(nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading the served log failed")
            return emptyList()
        }
        val out = ArrayList<Repetition.Served>(rows.size)
        for (row in rows) {
            val mix = MixCodec.decode(row.itemsBlob) ?: continue
            out.add(Repetition.Served(row.surface, row.builtAt, LongArray(mix.items.size) { mix.items[it].rid }))
        }
        return out
    }

    /** Stores [mix] under [surface] and forgets what is older than the retention. Returns its `recId`. */
    suspend fun put(surface: Int, surfaceId: String, mix: StoredMix, nuQ: Int): Long {
        val recId = recIdOf(mix.builtAtMs, surface)
        dao.upsert(
            ServedListEntity(
                recId = recId,
                surface = surface,
                surfaceId = surfaceId,
                builtAt = mix.builtAtMs,
                seed = mix.seed,
                modelVersion = Brain.SPEC_VERSION,
                nuQ = nuQ,
                itemsBlob = MixCodec.encode(mix),
            )
        )
        runCatching { dao.deleteOlderThan(Repetition.keepAfter(clock.nowMs())) }
        return recId
    }

    companion object {
        private const val CLOCK_SLACK_MS = 10L * 60 * 1000

        /** The start of the local day [nowMs] is in; a day starts at 04:00 (10.3, rule 12). */
        fun dayStart(nowMs: Long, tzOffsetMin: Int): Long =
            Repetition.mixDay(nowMs, tzOffsetMin) * Repetition.DAY_MS - tzOffsetMin * 60_000L + 14_400_000L

        /** `(builtAtMs << 8) | surface`: unique per build, time-ordered, like an event id. */
        fun recIdOf(builtAtMs: Long, surface: Int): Long = (builtAtMs shl 8) or (0xA0 + surface).toLong()
    }
}
