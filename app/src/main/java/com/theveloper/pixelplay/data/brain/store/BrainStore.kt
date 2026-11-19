package com.theveloper.pixelplay.data.brain.store

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.PlayEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the brain's orchestration reads and writes (docs/brain-spec.md, 3.8): the event log, the
 * affinity rows and a few small state values. Every call blocks and must run off the main thread.
 * Room in the app; a plain map in unit tests.
 */
interface BrainStore {
    fun <T> transaction(block: () -> T): T

    /** `PlayEvent.identityOf(origin, keyAtWrite)` of the row under [eventId], or null when the id is free. */
    fun eventIdentityAt(eventId: Long): String?

    /** False when an event with this id is already stored (the insert is ignored). */
    fun insertEvent(event: PlayEvent): Boolean

    /** How many of [events] were new. */
    fun insertEvents(events: List<PlayEvent>): Int

    /** Events in replay order (ascending id), after [afterEventId]. */
    fun eventsAfter(afterEventId: Long, limit: Int): List<PlayEvent>

    fun countEvents(origin: Int): Int

    /** Every stored play of the recordings in [rids], in replay order. */
    fun eventsOfRecordings(rids: Collection<Long>): List<PlayEvent>

    /** Live plays that started at or after [sinceMs], in replay order. */
    fun eventsStartedSince(sinceMs: Long): List<PlayEvent>

    /** Sets (or clears) [flag] on the events under [eventIds]; returns how many ids were given. */
    fun setEventFlag(eventIds: List<Long>, flag: Int, on: Boolean): Int

    /** Reads every affinity row into [into]. */
    fun loadAffinity(into: Affinity.State)

    fun upsertAffinity(rows: List<AffinityEntity>)

    /** Replaces the whole table (after a rebuild). */
    fun replaceAffinity(rows: List<AffinityEntity>)

    fun getState(key: String): String?

    /** Null deletes the key. */
    fun putState(key: String, value: String?)

    companion object {
        /** The moment the brain was first used on this install: history before it is copied, after it is recorded live. */
        const val KEY_FIRST_USE_MS = "first_use_ms"

        /** Set once the `playback_history` copy ran (4.4, step 2). */
        const val KEY_HISTORY_MIGRATED = "history_migrated_v1"

        /** `Brain.SPEC_VERSION` the affinity rows were built with. */
        const val KEY_SPEC_VERSION = "affinity_spec_version"

        /** The highest `imported_plays` row id folded into the affinity rows. */
        const val KEY_IMPORT_MAX_ID = "import_max_id"

        /** The play in flight, so a process death still leaves a `killed` event. */
        const val KEY_OPEN_PLAY = "open_play"

        /** One stored row for a counter of [state]; [storedFamily] is the `affinity.family` code. */
        fun rowOf(state: Affinity.State, storedFamily: Int, key: Long): AffinityEntity? = when {
            storedFamily >= 0 && storedFamily < Family.COUNT ->
                state.counter(storedFamily, key)?.let { AffinityEntity(storedFamily, key, it.posQ, it.negQ, it.lastMs, it.posRQ, it.negRQ, it.lastRMs) }
            storedFamily >= Family.SUM_BASE && storedFamily < Family.SUM_BASE + Family.COUNT ->
                state.sum(storedFamily - Family.SUM_BASE).let { AffinityEntity(storedFamily, key, it.posQ, it.negQ, it.lastMs, it.posRQ, it.negRQ, it.lastRMs) }
            storedFamily >= Family.PRIOR_BASE && storedFamily < Family.PRIOR_BASE + Family.COUNT ->
                state.prior(storedFamily - Family.PRIOR_BASE, key)?.let { AffinityEntity(storedFamily, key, posQ = it.posQ, negQ = it.negQ) }
            storedFamily == Family.COMPLETIONS ->
                state.completions[key]?.let { AffinityEntity(storedFamily, key, posQ = it.toLong()) }
            else -> null
        }

        /** Every row of [state], for a full rewrite after a rebuild. */
        fun allRows(state: Affinity.State): List<AffinityEntity> {
            val out = ArrayList<AffinityEntity>()
            for (family in 0 until Family.COUNT) {
                for (key in state.sortedKeys(family)) rowOf(state, family, key)?.let(out::add)
                val sum = state.sum(family)
                if (sum.posQ != 0L || sum.negQ != 0L || sum.lastMs != 0L) rowOf(state, Family.SUM_BASE + family, Family.ALL_KEY)?.let(out::add)
                for (key in state.sortedPriorKeys(family)) rowOf(state, Family.PRIOR_BASE + family, key)?.let(out::add)
            }
            for (rid in state.completions.keys) rowOf(state, Family.COMPLETIONS, rid)?.let(out::add)
            return out
        }

        fun entityOf(e: PlayEvent): PlayEventEntity = PlayEventEntity(
            eventId = e.eventId,
            schema = e.schema,
            origin = e.origin,
            rid = e.rid,
            primaryAid = e.primaryAid,
            otherAids = e.otherAids.joinToString(","),
            keyAtWrite = e.keyAtWrite,
            startedAtMs = e.startedAtMs,
            tzOffsetMin = e.tzOffsetMin.takeIf { it != PlayEvent.TZ_UNKNOWN },
            playedMs = e.playedMs,
            durationMs = e.durationMs,
            startReason = e.startReason,
            endReason = e.endReason,
            flags = e.flags,
            surface = e.surface,
            surfaceId = e.surfaceId,
            recId = e.recId,
            slot = e.slot,
            arm = e.arm,
            sessionId = e.sessionId,
            posInSession = e.posInSession,
            passiveRun = e.passiveRun,
            route = e.route,
            retrQ = e.retrQ,
            radioRank = e.radioRank,
            pServedQ = e.pServedQ,
            modelVersion = e.modelVersion,
        )

        fun eventOf(row: PlayEventEntity): PlayEvent = PlayEvent(
            eventId = row.eventId,
            schema = row.schema,
            origin = row.origin,
            rid = row.rid,
            primaryAid = row.primaryAid,
            otherAids = if (row.otherAids.isEmpty()) PlayEvent.NO_AIDS
            else row.otherAids.split(',').mapNotNull { it.toLongOrNull() }.toLongArray(),
            keyAtWrite = row.keyAtWrite,
            startedAtMs = row.startedAtMs,
            tzOffsetMin = row.tzOffsetMin ?: PlayEvent.TZ_UNKNOWN,
            playedMs = row.playedMs,
            durationMs = row.durationMs,
            startReason = row.startReason,
            endReason = row.endReason,
            flags = row.flags,
            surface = row.surface,
            surfaceId = row.surfaceId,
            recId = row.recId,
            slot = row.slot,
            arm = row.arm,
            sessionId = row.sessionId,
            posInSession = row.posInSession,
            passiveRun = row.passiveRun,
            route = row.route,
            retrQ = row.retrQ,
            radioRank = row.radioRank,
            pServedQ = row.pServedQ,
            modelVersion = row.modelVersion,
        )
    }
}

/** [BrainStore] over `brain.db`. */
@Singleton
class RoomBrainStore @Inject constructor(
    private val db: MusicBrainDatabase,
    private val events: PlayEventDao,
    private val affinity: AffinityDao,
    private val state: BrainStateDao,
) : BrainStore {

    override fun <T> transaction(block: () -> T): T = db.runInTransaction<T> { block() }

    override fun eventIdentityAt(eventId: Long): String? =
        events.identityAt(eventId)?.let { PlayEvent.identityOf(it.origin, it.keyAtWrite) }

    override fun insertEvent(event: PlayEvent): Boolean = events.insertBlocking(BrainStore.entityOf(event)) != -1L

    override fun insertEvents(events: List<PlayEvent>): Int {
        var inserted = 0
        for (chunk in events.chunked(CHUNK)) {
            for (id in this.events.insertAllBlocking(chunk.map(BrainStore::entityOf))) if (id != -1L) inserted++
        }
        return inserted
    }

    override fun eventsAfter(afterEventId: Long, limit: Int): List<PlayEvent> =
        events.pageBlocking(afterEventId, limit).map(BrainStore::eventOf)

    override fun countEvents(origin: Int): Int = events.countByOrigin(origin)

    override fun eventsOfRecordings(rids: Collection<Long>): List<PlayEvent> {
        val out = ArrayList<PlayEvent>()
        for (chunk in rids.distinct().chunked(CHUNK)) out.addAll(events.byRecordingsBlocking(chunk).map(BrainStore::eventOf))
        out.sortBy { it.eventId }
        return out
    }

    override fun eventsStartedSince(sinceMs: Long): List<PlayEvent> =
        events.liveSinceBlocking(sinceMs).map(BrainStore::eventOf)

    override fun setEventFlag(eventIds: List<Long>, flag: Int, on: Boolean): Int {
        for (chunk in eventIds.chunked(CHUNK)) {
            if (on) events.setFlagBlocking(chunk, flag) else events.maskFlagsBlocking(chunk, flag.inv())
        }
        return eventIds.size
    }

    override fun loadAffinity(into: Affinity.State) {
        for (row in affinity.allBlocking()) {
            into.load(row.family, row.key, row.posQ, row.negQ, row.lastMs, row.posRQ, row.negRQ, row.lastRMs)
        }
    }

    override fun upsertAffinity(rows: List<AffinityEntity>) {
        for (chunk in rows.chunked(CHUNK)) affinity.upsertBlocking(chunk)
    }

    override fun replaceAffinity(rows: List<AffinityEntity>) {
        db.runInTransaction {
            affinity.clearBlocking()
            for (chunk in rows.chunked(CHUNK)) affinity.upsertBlocking(chunk)
        }
    }

    override fun getState(key: String): String? = state.getBlocking(key)?.let { String(it, Charsets.UTF_8) }

    override fun putState(key: String, value: String?) {
        if (value == null) state.deleteBlocking(key) else state.putBlocking(BrainStateEntity(key, value.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val CHUNK = 500
    }
}
