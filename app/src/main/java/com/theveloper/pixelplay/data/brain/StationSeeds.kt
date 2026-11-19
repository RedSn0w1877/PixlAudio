package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.model.Song

/**
 * Which songs seed a station from an album or a playlist (docs/brain-spec.md, 6.2): up to 3 for an album
 * and 5 for a playlist, "most played, else the first", at most 2 per artist. The first of them plays.
 */
object StationSeeds {
    const val ALBUM_SEEDS = 3
    const val PLAYLIST_SEEDS = 5
    const val PER_ARTIST = 2

    /**
     * [songs] are in the source's own order; [plays] is the play count of each song id the listener has
     * played. Songs with plays come first, most played first (ties by the source's order), then the
     * unplayed ones in the source's order.
     */
    fun pick(songs: List<Song>, plays: Map<String, Int>, max: Int): List<Song> {
        val order = songs.indices.sortedWith(
            compareByDescending<Int> { (plays[songs[it].id] ?: 0) > 0 }
                .thenByDescending { plays[songs[it].id] ?: 0 }
                .thenBy { it }
        )
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Song>(max)
        for (i in order) {
            if (out.size >= max) break
            val song = songs[i]
            val artist = LibraryIndex.artistKeyOf(song)
            val used = perArtist[artist] ?: 0
            if (artist.isNotEmpty() && used >= PER_ARTIST) continue
            perArtist[artist] = used + 1
            out.add(song)
        }
        return out
    }
}
