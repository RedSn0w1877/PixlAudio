package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.data.brain.store.AffinityEntity
import com.theveloper.pixelplay.data.brain.store.BrainStore

/** `brain.db` as plain maps: the event log in id order, the affinity rows, the state values. */
class MemoryBrainStore : BrainStore {
    // Concurrent: the recorder's worker writes while a test thread looks.
    val events = java.util.concurrent.ConcurrentSkipListMap<Long, PlayEvent>()
    val affinity = java.util.concurrent.ConcurrentHashMap<Pair<Int, Long>, AffinityEntity>()
    val state = java.util.concurrent.ConcurrentHashMap<String, String>()
    var affinityRewrites = 0

    override fun <T> transaction(block: () -> T): T = block()

    override fun eventIdentityAt(eventId: Long): String? =
        events[eventId]?.let { PlayEvent.identityOf(it.origin, it.keyAtWrite) }

    override fun insertEvent(event: PlayEvent): Boolean {
        if (events.containsKey(event.eventId)) return false
        events[event.eventId] = event
        return true
    }

    override fun insertEvents(events: List<PlayEvent>): Int = events.count { insertEvent(it) }

    override fun eventsAfter(afterEventId: Long, limit: Int): List<PlayEvent> =
        events.tailMap(afterEventId, false).values.take(limit)

    override fun countEvents(origin: Int): Int = events.values.count { it.origin == origin }

    override fun loadAffinity(into: Affinity.State) {
        for (row in affinity.values) into.load(row.family, row.key, row.posQ, row.negQ, row.lastMs, row.posRQ, row.negRQ, row.lastRMs)
    }

    override fun upsertAffinity(rows: List<AffinityEntity>) {
        for (row in rows) affinity[row.family to row.key] = row
    }

    override fun replaceAffinity(rows: List<AffinityEntity>) {
        affinityRewrites++
        affinity.clear()
        upsertAffinity(rows)
    }

    override fun getState(key: String): String? = state[key]

    override fun putState(key: String, value: String?) {
        if (value == null) state.remove(key) else state[key] = value
    }
}

/** The alias tables as `Ids.MemoryAliasTable`: the same resolve the Room tables run. */
class MemoryTrackResolver : TrackResolver {
    val tracks = Ids.MemoryAliasTable()
    private val artists = Ids.MemoryAliasTable()
    val merged = HashMap<Long, Long>()

    override suspend fun resolveTracks(requests: List<TrackRequest>): LongArray =
        LongArray(requests.size) { tracks.resolveTrack(requests[it].keys, requests[it].durationMs) ?: 0L }

    override suspend fun otherVideoIds(rid: Long, exceptVideoId: String?): List<String> = emptyList()

    override suspend fun resolveArtistNames(names: List<String>): LongArray =
        LongArray(names.size) { artists.resolveArtist(Ids.ArtistKeys(name = names[it])) ?: 0L }

    override suspend fun merges(): Map<Long, Long> = merged

    suspend fun rid(artist: String, title: String): Long =
        resolveTrack(Ids.TrackKeys(nameKey = Ids.nameKey(artist, title)), 0)

    suspend fun aid(name: String): Long = resolveArtistNames(listOf(name))[0]
}

/** A catalog of known songs: library tracks and tracks from outside it. */
class FakeTrackCatalog : TrackCatalog {
    private val facts = HashMap<String, TrackFacts>()

    fun add(songId: String, artist: String, title: String, durationMs: Long = 200_000L, inLibrary: Boolean = true, others: List<String> = emptyList()) {
        val keys = Ids.TrackKeys(nameKey = Ids.nameKey(artist, title))
        facts[songId] = TrackFacts(keys, keys.n!!, title, artist, others, durationMs, inLibrary, spotifyId = null)
    }

    override suspend fun factsFor(songId: String, context: PlayContext?): TrackFacts? = facts[songId]

    override suspend fun isOutsideLibrary(songId: String): Boolean = facts[songId]?.inLibrary == false
}
