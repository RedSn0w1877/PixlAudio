package com.theveloper.pixelplay.data.blocking

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.text.FoldedText

/** An artist the owner chose never to hear automatically. [name] is kept for the Settings list. */
data class BlockedArtist(val id: Long, val name: String)

/** Stores a [BlockedArtist] as one DataStore string-set entry: `<id>|<name>` (the id never contains `|`). */
object BlockedArtistCodec {
    fun encode(artist: BlockedArtist): String = "${artist.id}|${artist.name}"

    fun decode(raw: String): BlockedArtist? {
        val cut = raw.indexOf('|')
        if (cut <= 0) return null
        val id = raw.substring(0, cut).toLongOrNull() ?: return null
        val name = raw.substring(cut + 1)
        return BlockedArtist(id, name)
    }
}

/**
 * The set of blocked artists, ready for fast lookups. Matching is by artist id first and falls back
 * to the folded (case/diacritic-insensitive) name, because the same artist can carry different ids
 * (a Spotify-derived id vs a library id) and a queue restored from disk or a controller item has
 * only the display names.
 */
class BlockedArtistIndex(artists: Collection<BlockedArtist>) {
    val artists: List<BlockedArtist> = artists.sortedBy { FoldedText.fold(it.name) }
    private val ids: Set<Long> = artists.mapTo(HashSet()) { it.id }.apply { removeAll(UNKNOWN_IDS) }
    private val names: Set<String> = artists.mapNotNullTo(HashSet()) { FoldedText.fold(it.name).ifEmpty { null } }

    val isEmpty: Boolean get() = ids.isEmpty() && names.isEmpty()
    val size: Int get() = artists.size

    /** One credited artist: id first, then name. */
    fun isBlockedCredit(id: Long?, name: String?): Boolean {
        if (isEmpty) return false
        if (id != null && id in ids) return true
        if (!name.isNullOrBlank() && FoldedText.fold(name) in names) return true
        return false
    }

    /** True when ANY credited artist of the song is blocked. */
    fun isBlocked(song: Song): Boolean {
        if (isEmpty) return false
        if (song.artists.isNotEmpty()) {
            for (credit in song.artists) if (isBlockedCredit(credit.id, credit.name)) return true
        }
        return isBlockedCredit(song.artistId, song.artist) ||
            namesFromDisplay(song.displayArtist).any { isBlockedCredit(null, it) }
    }

    /** A player item only knows the display string ("A, B") and optionally artist ids. */
    fun isBlockedItem(artistIds: LongArray?, displayArtist: String?): Boolean {
        if (isEmpty) return false
        if (artistIds != null) for (id in artistIds) if (isBlockedCredit(id, null)) return true
        return namesFromDisplay(displayArtist).any { isBlockedCredit(null, it) }
    }

    /** True when any of the given credited names is blocked (catalog tracks carry names only). */
    fun isBlockedAnyName(names: Iterable<String?>): Boolean {
        if (isEmpty) return false
        for (n in names) if (isBlockedCredit(null, n)) return true
        return false
    }

    fun contains(artistId: Long, name: String?): Boolean = isBlockedCredit(artistId, name)

    fun filterAllowed(songs: List<Song>): List<Song> =
        if (isEmpty) songs else songs.filterNot { isBlocked(it) }

    companion object {
        private val UNKNOWN_IDS = setOf(0L, -1L)
        val EMPTY = BlockedArtistIndex(emptyList())

        /** "A, B" -> [A, B] plus the whole string (a name may itself contain a comma). */
        fun namesFromDisplay(display: String?): List<String> {
            if (display.isNullOrBlank()) return emptyList()
            val parts = display.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
            return if (parts.size <= 1) listOf(display.trim()) else parts + display.trim()
        }
    }
}

/**
 * What the transport layer does when the current item belongs to a blocked artist. Pure so the
 * loop protection (every item blocked, queue wrapping with repeat-all) is unit-tested.
 */
class BlockedSkipGuard {
    enum class Action { NONE, SKIP_FORWARD, SKIP_BACKWARD, PAUSE }

    private var consecutiveSkips = 0

    fun decide(
        blocked: Boolean,
        allowedByUser: Boolean,
        forward: Boolean,
        hasNeighbour: Boolean,
        queueSize: Int,
    ): Action {
        if (!blocked || allowedByUser) {
            consecutiveSkips = 0
            return Action.NONE
        }
        consecutiveSkips++
        if (!hasNeighbour || consecutiveSkips > queueSize.coerceAtLeast(1)) {
            consecutiveSkips = 0
            return Action.PAUSE
        }
        return if (forward) Action.SKIP_FORWARD else Action.SKIP_BACKWARD
    }

    fun reset() { consecutiveSkips = 0 }
}
