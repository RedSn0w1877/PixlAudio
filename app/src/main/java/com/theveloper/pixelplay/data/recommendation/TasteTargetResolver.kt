package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.text.FoldedText

/** A target the assistant named, matched to how adjustments store it. */
data class ResolvedTarget(val key: String, val altKey: String?, val label: String)

/**
 * Turns "Radiohead", "Creep", "Pop" or a library id into a [ResolvedTarget] (TasteAdjustmentsTest).
 * Artists, genres and albums do not have to be in the library (a mute on an artist also keeps their
 * catalogue discoveries out of Home); a song has to be, because its key is the library id.
 */
object TasteTargetResolver {

    /** @throws IllegalArgumentException with a message fit for the assistant when [target] can't be placed. */
    fun resolve(type: TasteTarget, target: String, library: List<Song>): ResolvedTarget {
        val text = target.trim()
        require(text.isNotEmpty()) { "The target is empty." }
        return when (type) {
            TasteTarget.SONG -> song(text, library)
            TasteTarget.ARTIST -> artist(text, library)
            TasteTarget.GENRE -> named(text, library.mapNotNull { it.genre })
            TasteTarget.ALBUM -> album(text, library)
        }
    }

    private fun song(text: String, library: List<Song>): ResolvedTarget {
        val spotifyId = text.removePrefix("spotify:track:").removePrefix("spotify_")
        val byId = library.firstOrNull { it.id == text } ?: library.firstOrNull { it.spotifyId != null && it.spotifyId == spotifyId }
        val found = byId ?: bestTitleMatch(text, library)
            ?: throw IllegalArgumentException("No song matching \"$text\" in the library. Find it with search_library and pass its id.")
        return ResolvedTarget(found.id, found.spotifyId, "${found.title} - ${found.displayArtist}")
    }

    /** "title" or "title artist" (any order of words): the song whose title and artist hold every word, shortest title first. */
    private fun bestTitleMatch(text: String, library: List<Song>): Song? {
        val words = FoldedText.fold(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val exact = library.filter { FoldedText.fold(it.title) == FoldedText.fold(text) }
        if (exact.size == 1) return exact.first()
        val pool = exact.ifEmpty {
            library.filter { song ->
                val haystack = FoldedText.fold("${song.title} ${song.displayArtist}")
                words.all { it in haystack }
            }
        }
        if (pool.isEmpty()) return null
        if (pool.size > 1 && words.size == 1) {
            // One word that fits several songs is a guess: make the assistant pick an id.
            throw IllegalArgumentException(
                "\"$text\" fits ${pool.size} songs (for example ${pool.take(3).joinToString("; ") { "${it.title} - ${it.displayArtist} [id ${it.id}]" }}). Pass the song's id."
            )
        }
        return pool.minByOrNull { it.title.length }
    }

    private fun artist(text: String, library: List<Song>): ResolvedTarget {
        val id = text.toLongOrNull()
        if (id != null) {
            for (song in library) {
                val ref = song.artists.firstOrNull { it.id == id }
                if (ref != null) return named(ref.name, listOf(ref.name))
                if (song.artistId == id) return named(song.primaryArtist.name.ifBlank { song.artist }, listOf(song.artist))
            }
        }
        val names = library.flatMap { song -> song.artists.map { it.name } + song.artist.split(',') }
        return named(text, names)
    }

    private fun album(text: String, library: List<Song>): ResolvedTarget {
        val id = text.toLongOrNull()
        if (id != null) {
            library.firstOrNull { it.albumId == id }?.let { return named(it.album, listOf(it.album)) }
        }
        return named(text, library.map { it.album })
    }

    /** The key of [text], labelled with the library's own spelling of the same name when there is one. */
    private fun named(text: String, spellings: List<String>): ResolvedTarget {
        val key = TasteAdjustments.nameKey(text)
        require(key.isNotEmpty()) { "The target is empty." }
        val spelled = spellings.asSequence().distinct().firstOrNull { TasteAdjustments.nameKey(it) == key }?.trim()?.removeSuffix(" - Topic")
        return ResolvedTarget(key, null, spelled?.ifBlank { null } ?: text.trim())
    }
}
