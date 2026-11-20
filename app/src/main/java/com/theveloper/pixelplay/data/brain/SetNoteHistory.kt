package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which songs of the queue the listener has really played, for the set notes ("Three from your V-pop side, then something new"): a note may
 * say "your side" only for tracks with a recorded play, and "something new" only for a track that has none. A library row is not history by
 * itself (a First Listen queue is made of library tracks nobody has played), and an engine track is not new by itself (it may have played
 * through a radio).
 *
 * Reads the alias table and the in-memory affinity state; off the main thread; never throws (a song it cannot place is simply not heard).
 */
@Singleton
class SetNoteHistory @Inject constructor(
    private val brain: BrainEngine,
    private val aliasDao: AliasDao,
    private val playableStore: PlayableStore,
) {
    /** The ids of those of [songs] that have a recorded play. */
    suspend fun heardSongIds(songs: List<Song>): Set<String> = withContext(Dispatchers.IO) {
        try {
            val ridOf = HashMap<String, Long>(songs.size * 2)
            for (s in songs) {
                val rid = if (EngineTrack.isEngineOnly(s)) {
                    playableStore.bySongId(s.id)?.rid
                } else {
                    LibraryTrackCatalog.factsOf(s)?.keys?.let { k -> k.s?.let { aliasDao.trackId(it) } ?: k.n?.let { aliasDao.trackId(it) } }
                }
                if (rid != null && rid != 0L) ridOf[s.id] = rid
            }
            if (ridOf.isEmpty()) return@withContext emptySet()
            val heardRids = brain.withState { st -> ridOf.values.filterTo(HashSet()) { Affinity.isHeard(st.affinity, Family.TRACK, it) } }
            ridOf.filterValues { it in heardRids }.keys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }
}
