package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every allowed library track with the ids the brain knows it by (docs/brain-spec.md, 3.1). The knowledge lanes already
 * resolve the whole library once; the island fit, Rediscover, On Repeat and the adjacent-genre pool read it here. Resolved
 * off the main thread, remembered while the library and the listener's taste changes stay the same.
 */
@Singleton
class LibraryRids @Inject constructor(
    private val musicRepository: MusicRepository,
    private val identity: TrackResolver,
    private val constraints: TasteConstraintAdapter,
) {
    private class Cache(val key: String, val tracks: List<LibraryTrack>)

    @Volatile private var cache: Cache? = null
    private val lock = Mutex()

    suspend fun all(nowMs: Long): List<LibraryTrack> = lock.withLock {
        val songs = musicRepository.getAllSongsOnce()
        val favourites = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val key = "${songs.size}:${constraints.signature()}:${favourites.size}"
        cache?.let { if (it.key == key) return@withLock it.tracks }
        val taste = constraints.snapshot(nowMs)
        val allowed = songs.filter { taste.allows(it) }
        val requests = ArrayList<TrackRequest>(allowed.size)
        val kept = ArrayList<com.theveloper.pixelplay.data.model.Song>(allowed.size)
        for (song in allowed) {
            val facts = LibraryTrackCatalog.factsOf(song) ?: continue
            requests.add(TrackRequest(facts.keys, song.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
            kept.add(song)
        }
        val rids = identity.resolveTracks(requests)
        val aids = identity.resolveArtistNames(kept.map { LibraryIndex.primaryArtistName(it) })
        val out = ArrayList<LibraryTrack>(kept.size)
        for (i in kept.indices) {
            if (rids[i] == 0L) continue
            val song = kept[i]
            out.add(LibraryTrack(song, rids[i], aids[i], liked = song.isFavorite || song.id in favourites, boostLn = taste.boostLn(song)))
        }
        cache = Cache(key, out)
        out
    }
}
