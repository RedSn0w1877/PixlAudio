package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** What identity needs to know about one track: its keys, its artists (one name each), its length. */
class TrackFacts(
    val keys: Ids.TrackKeys,
    /** The key an event stores as `keyAtWrite`: the strongest one. */
    val keyAtWrite: String,
    val title: String,
    val primaryArtist: String,
    val otherArtists: List<String>,
    val durationMs: Long,
    val inLibrary: Boolean,
    val spotifyId: String?,
)

/** Looks a media id up: the library first, then the `playable` table, then what the item itself says. */
interface TrackCatalog {
    suspend fun factsFor(songId: String, context: PlayContext?): TrackFacts?

    /** True when [songId] is a track from outside the library with no library row. */
    suspend fun isOutsideLibrary(songId: String): Boolean
}

@Singleton
class LibraryTrackCatalog @Inject constructor(
    private val musicRepository: dagger.Lazy<MusicRepository>,
    private val playableStore: PlayableStore,
) : TrackCatalog {

    override suspend fun factsFor(songId: String, context: PlayContext?): TrackFacts? {
        val song = musicRepository.get().getSong(songId).first()
        if (song != null) return factsOf(song)
        val row = playableStore.bySongId(songId)
        if (row != null) {
            // The row keeps the artists as one display string; the item's own extra names the first one.
            val primary = context?.primaryArtist?.takeIf { it.isNotBlank() } ?: row.artistDisplay
            val keys = Ids.TrackKeys(videoId = row.videoId, nameKey = Ids.nameKey(primary, row.title))
            return TrackFacts(keys, keys.y ?: return null, row.title, primary, emptyList(), row.durationMs, inLibrary = false, spotifyId = row.synthId)
        }
        context ?: return null
        val primary = context.primaryArtist?.takeIf { it.isNotBlank() } ?: context.artistDisplay
        val keys = Ids.TrackKeys(
            spotifyId = context.spotifyId?.takeUnless(::isSyntheticId),
            nameKey = Ids.nameKey(primary, context.title),
        )
        val key = keys.s ?: keys.n ?: return null
        return TrackFacts(keys, key, context.title, primary, emptyList(), 0L, inLibrary = false, spotifyId = context.spotifyId)
    }

    override suspend fun isOutsideLibrary(songId: String): Boolean =
        playableStore.bySongId(songId) != null && musicRepository.get().getSong(songId).first() == null

    companion object {
        /** A YouTube Music import carries a synthetic id (22 hex characters), not a Spotify track id. */
        fun isSyntheticId(id: String): Boolean = id.length == 22 && id.all { it in '0'..'9' || it in 'a'..'f' }

        fun factsOf(song: Song): TrackFacts? {
            val primary = LibraryIndex.primaryArtistName(song)
            val keys = Ids.TrackKeys(
                spotifyId = song.spotifyId?.takeUnless(::isSyntheticId),
                nameKey = Ids.nameKey(primary, song.title),
            )
            val key = keys.s ?: keys.n ?: return null
            val others = song.artists.asSequence().map { it.name }.filter { it.isNotBlank() && it != primary }.distinct().take(4).toList()
            return TrackFacts(keys, key, song.title, primary, others, song.duration, inLibrary = true, spotifyId = song.spotifyId)
        }
    }
}
