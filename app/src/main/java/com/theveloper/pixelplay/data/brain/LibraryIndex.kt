package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.youtube.RadioRow

/**
 * The library by name (docs/brain-spec.md, 3.1 "Stage 1 joins artists by name"): tells whether a radio
 * row is a recording the listener already has, and lists a library artist's tracks.
 *
 * Built off the main thread, once per library change, from the songs alone: no database write. The
 * match is the names-only step of `Ids.resolveTrack`: same folded primary artist, same cleaned title
 * and version flags, and a length within 3 seconds when both lengths are known.
 */
class LibraryIndex(songs: List<Song>) {

    private val byNameKey = HashMap<String, ArrayList<Song>>(songs.size * 2)
    private val byArtistKey = HashMap<String, ArrayList<Song>>()

    val size: Int = songs.size

    init {
        for (song in songs) {
            val primary = primaryArtistName(song)
            val artistKey = BrainText.fold(primary)
            if (artistKey.isNotEmpty()) byArtistKey.getOrPut(artistKey) { ArrayList(4) }.add(song)
            val key = Ids.nameKey(primary, song.title) ?: continue
            byNameKey.getOrPut(key) { ArrayList(1) }.add(song)
        }
    }

    /** The library song that is the same recording as [row], or null. */
    fun match(row: RadioRow): Song? {
        val primary = row.artists.firstOrNull()?.name ?: return null
        val key = Ids.nameKey(primary, row.title) ?: return null
        val candidates = byNameKey[key] ?: return null
        val rowMs = row.durationSec * 1000L
        for (song in candidates) {
            if (rowMs <= 0L || song.duration <= 0L) return song
            val delta = if (rowMs > song.duration) rowMs - song.duration else song.duration - rowMs
            if (delta <= Ids.NAME_MATCH_MAX_DURATION_DELTA_MS) return song
        }
        return null
    }

    /** Every folded primary-artist key of the library. */
    fun artistKeys(): Set<String> = byArtistKey.keys

    /** Library songs whose primary artist folds to [artistKey], in library order. */
    fun byPrimaryArtist(artistKey: String): List<Song> = byArtistKey[artistKey].orEmpty()

    companion object {
        /**
         * The primary artist of a library track: the first entry of the library's own split artist list,
         * falling back to the legacy field. `Song.artist` can be a ", "-joined display string
         * ("Tyler, The Creator" would break), so it is only the fallback and is never split here.
         */
        fun primaryArtistName(song: Song): String =
            (song.artists.firstOrNull { it.isPrimary } ?: song.artists.firstOrNull())?.name
                ?.takeIf { it.isNotBlank() }
                ?: song.artist

        fun artistKeyOf(song: Song): String = BrainText.fold(primaryArtistName(song))
    }
}
