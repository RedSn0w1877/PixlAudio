package com.theveloper.pixelplay.data.text

import com.theveloper.pixelplay.data.model.Song

/**
 * Live "find in this list" over one page of songs. The folded (lower-case, accent-free) text of each
 * song's title, artists and album is built ONCE when the list changes, off the main thread, so a
 * keystroke only runs a substring test per song and allocates nothing per row. Every word the user
 * typed has to appear somewhere in the title / artist / album (any order), and the original order of
 * the list is kept.
 */
class SongSearchIndex(private val songs: List<Song>) {

    private val haystacks: Array<String> = Array(songs.size) { i ->
        val s = songs[i]
        buildString {
            append(FoldedText.fold(s.title))
            append('\u0000')
            append(FoldedText.fold(s.displayArtist))
            append('\u0000')
            append(FoldedText.fold(s.album))
        }
    }

    val size: Int get() = songs.size

    /** The songs matching [query], in the original order; the full list for a blank query. */
    fun filter(query: String): List<Song> {
        val tokens = FoldedText.fold(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return songs
        val out = ArrayList<Song>()
        for (i in haystacks.indices) {
            val hay = haystacks[i]
            var all = true
            for (t in tokens) {
                if (!hay.contains(t)) { all = false; break }
            }
            if (all) out.add(songs[i])
        }
        return out
    }
}
