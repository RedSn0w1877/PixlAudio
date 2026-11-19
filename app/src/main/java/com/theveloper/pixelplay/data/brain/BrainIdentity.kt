package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.brain.store.ArtistAliasEntity
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import com.theveloper.pixelplay.data.brain.store.TrackAliasEntity
import com.theveloper.pixelplay.data.brain.store.TrackMergeEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrackRequest(val keys: Ids.TrackKeys, val durationMs: Int)

/** What the brain's orchestration needs from identity; the Room tables in the app, a plain map in tests. */
interface TrackResolver {
    /** Rids for [requests], in order; 0 for a request with no usable key. */
    suspend fun resolveTracks(requests: List<TrackRequest>): LongArray

    suspend fun resolveTrack(keys: Ids.TrackKeys, durationMs: Int): Long =
        resolveTracks(listOf(TrackRequest(keys, durationMs)))[0]

    /** The other YouTube audio ids known for [rid] (never [exceptVideoId]). */
    suspend fun otherVideoIds(rid: Long, exceptVideoId: String?): List<String>

    /**
     * Aids for artist [names] (ONE artist each), in order; 0 for a name that folds to nothing. Stage 1
     * joins artists by folded name (3.1). The default mints straight from the name key, which is what
     * the alias table does for a name it has not seen.
     */
    suspend fun resolveArtistNames(names: List<String>): LongArray = LongArray(names.size) { i ->
        Ids.ArtistKeys(name = names[i]).na?.let { Ids.mint(it) { false } } ?: 0L
    }

    /** The recording [rid] was merged into, or [rid] itself. */
    suspend fun survivorOf(rid: Long): Long = rid

    /** Every merge, `from` to `into`: a rebuild folds a merged recording's plays into its survivor. */
    suspend fun merges(): Map<Long, Long> = emptyMap()
}

/**
 * The alias tables behind [Ids] (docs/brain-spec.md, 3.1): turns the keys a caller has for a track or
 * an artist into the recording id or artist id, minting and storing what is new.
 *
 * The decision is `Ids.resolveTrack` / `Ids.resolveArtist` in `:brain`; this class only reads and
 * writes the rows. Each batch is one transaction on an IO thread.
 */
@Singleton
class BrainIdentity @Inject constructor(
    private val db: MusicBrainDatabase,
    private val dao: AliasDao,
) : TrackResolver, ArtistKeySink {

    private val trackLookup = object : Ids.AliasLookup {
        override fun idOf(key: String): Long? = dao.trackId(key)
        override fun isTaken(id: Long): Boolean = dao.trackIdTaken(id)
        override fun survivorOf(id: Long): Long = dao.mergedInto(id) ?: id
        override fun durationMsOf(key: String): Int = dao.trackDurationMs(key) ?: 0
    }

    private val artistLookup = object : Ids.AliasLookup {
        override fun idOf(key: String): Long? = dao.artistId(key)
        override fun isTaken(id: Long): Boolean = dao.artistIdTaken(id)
        override fun survivorOf(id: Long): Long = id
        override fun durationMsOf(key: String): Int = 0
    }

    override suspend fun resolveTracks(requests: List<TrackRequest>): LongArray = withContext(Dispatchers.IO) {
        val out = LongArray(requests.size)
        if (requests.isEmpty()) return@withContext out
        db.runInTransaction {
            for (i in requests.indices) out[i] = resolveTrackInTransaction(requests[i])
        }
        out
    }

    /** Aids for [artists], in order; 0 for one with no usable key. */
    suspend fun resolveArtists(artists: List<Ids.ArtistKeys>): LongArray = withContext(Dispatchers.IO) {
        val out = LongArray(artists.size)
        if (artists.isEmpty()) return@withContext out
        db.runInTransaction {
            for (i in artists.indices) {
                val resolution = Ids.resolveArtist(artists[i], artistLookup) ?: continue
                if (resolution.aliases.isNotEmpty()) {
                    dao.insertArtistAliases(resolution.aliases.map { ArtistAliasEntity(it.key, it.id) })
                }
                out[i] = resolution.id
            }
        }
        out
    }

    /** Stores an artist's Spotify, Deezer and MusicBrainz ids next to its name key (3.1). */
    override suspend fun register(keys: Ids.ArtistKeys) {
        resolveArtists(listOf(keys))
    }

    override suspend fun resolveArtistNames(names: List<String>): LongArray =
        resolveArtists(names.map { Ids.ArtistKeys(name = it) })

    override suspend fun survivorOf(rid: Long): Long = withContext(Dispatchers.IO) { dao.mergedInto(rid) ?: rid }

    override suspend fun merges(): Map<Long, Long> = withContext(Dispatchers.IO) {
        dao.allMerges().associate { it.rid to it.into }
    }

    override suspend fun otherVideoIds(rid: Long, exceptVideoId: String?): List<String> = withContext(Dispatchers.IO) {
        dao.trackKeys(rid).asSequence()
            .filter { it.startsWith("y:") }
            .map { it.substring(2) }
            .filter { it != exceptVideoId }
            .toList()
    }

    private fun resolveTrackInTransaction(request: TrackRequest): Long {
        val resolution = Ids.resolveTrack(request.keys, request.durationMs, trackLookup) ?: return 0L
        if (resolution.aliases.isNotEmpty()) {
            dao.insertTrackAliases(resolution.aliases.map { TrackAliasEntity(it.key, it.id, it.durationMs) })
        }
        for (merge in resolution.merges) {
            // Flatten at write time: whatever pointed at the merged rid now points at the survivor.
            dao.repointMerges(merge.from, merge.into)
            dao.insertMerge(TrackMergeEntity(merge.from, merge.into))
        }
        return resolution.id
    }
}
