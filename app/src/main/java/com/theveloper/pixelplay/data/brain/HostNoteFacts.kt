package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.model.Song
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The facts the host of Endless Listening may put in a note beyond what the queue itself says: the name of the taste island a library song sits
 * in, and the atlas' one-line description of a genre. Both are read from what the engine already stored; a fact that is not there is absent, and
 * the note that needed it is not made (nothing is guessed).
 *
 * Read only while the Endless Listening station is the queue and the host's notes are on, off the main thread, for the few songs a note looks at.
 */
@Singleton
class HostNoteFacts @Inject constructor(
    private val aliasDao: AliasDao,
    private val islandStore: IslandStore,
    private val atlasProvider: AtlasProvider,
    private val mixes: dagger.Lazy<BrainMixes>,
) {
    // The name of an island, kept for as long as the fit it came from is the current one.
    @Volatile private var namesFit = 0L
    private val names = ConcurrentHashMap<Int, String>()

    /** The island name of each of [songs] the listener's library has a placed track for, by song id. Library songs only; an unnamed island gives none. */
    suspend fun islandNames(songs: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val set = islandStore.current() ?: return@withContext emptyMap()
            if (!set.hasMany) return@withContext emptyMap()
            if (set.fitAtMs != namesFit) {
                names.clear()
                namesFit = set.fitAtMs
            }
            val out = HashMap<String, String>()
            for (song in songs) {
                if (EngineTrack.isEngineOnly(song)) continue
                val keys = LibraryTrackCatalog.factsOf(song)?.keys ?: continue
                val rid = keys.s?.let { aliasDao.trackId(it) } ?: keys.n?.let { aliasDao.trackId(it) } ?: continue
                val islandId = set.memberOf[rid] ?: continue
                val name = names.getOrPut(islandId) {
                    val island = set.islands.firstOrNull { it.id == islandId } ?: return@getOrPut ""
                    mixes.get().islandGenreName(island)
                }
                if (name.isNotBlank()) out[song.id] = name
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** The atlas' description of [genre], or "" when the atlas is not loaded yet or does not describe it. Never loads anything. */
    fun genreFact(genre: String): String {
        val atlas = atlasProvider.loadedOrNull() ?: return ""
        val id = atlas.idForName(genre)
        if (id == GenreAtlas.NONE) return ""
        return atlas.genre(id)?.description.orEmpty()
    }
}
