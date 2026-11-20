package com.theveloper.pixelplay.brain

/**
 * Set notes (docs/brain-spec.md, 9.3 "What the listener gets", stage 4 milestone 2): one line in the queue, and under the player title, that
 * says what the next few tracks are: "Three from your V-pop side, then something new from HIEUTHUHAI." No voice and no model call: the
 * line is a template filled from the queue itself, so it is true of the queue it describes, and it is gone the moment the queue changes
 * (the caller recomputes it from the queue it has).
 *
 * A note describes a group: a run of two to five tracks the listener already has, then, when the next track is one they do not have, that
 * track. At most one note per five tracks. Everything here is a plain function of its inputs: the Swift port reproduces `setnotes.jsonl`.
 */
object SetNotes {

    /** The least distance between the starts of two notes: at most one note per five tracks. */
    const val MIN_GAP = 5

    /** The longest run of known tracks a note describes. */
    const val MAX_GROUP = 5

    const val MIN_GROUP = 2

    /**
     * One upcoming track, as the queue shows it. [known] is true for a track in the listener's own library; [genre] is the name of its
     * first genre, "" when unknown; [artist] is its primary artist, "" when unknown.
     */
    class Slot(val artist: String, val genre: String, val known: Boolean)

    const val KIND_GENRE_THEN_NEW = 0
    const val KIND_ARTIST_THEN_NEW = 1
    const val KIND_KNOWN_THEN_NEW = 2
    const val KIND_GENRE_ONLY = 3

    /**
     * [index] is the slot the note sits on (its first track); [count] the tracks it counts; [genre] and [artist] what the group shares
     * (kind 0 and 3: the genre; kind 1: the artist); [newArtist] the artist of the new track that follows (kinds 0 to 2).
     */
    class Note(val index: Int, val kind: Int, val count: Int, val genre: String, val artist: String, val newArtist: String)

    /** The notes of [slots], in order, each at least [MIN_GAP] slots after the one before. Never throws. */
    fun notes(slots: List<Slot>): List<Note> {
        val out = ArrayList<Note>()
        var i = 0
        val n = slots.size
        while (i < n) {
            var k = 0
            while (i + k < n && k < MAX_GROUP && slots[i + k].known) k++
            if (k < MIN_GROUP) {
                i++
                continue
            }
            val next = if (i + k < n) slots[i + k] else null
            val newNext = next != null && !next.known && next.artist.isNotBlank()
            val genre = sharedGenre(slots, i, k)
            val artist = sharedArtist(slots, i, k)
            var made: Note? = null
            var span = k
            if (newNext) {
                span = k + 1
                made = if (genre.isNotEmpty()) Note(i, KIND_GENRE_THEN_NEW, k, genre, "", next!!.artist.trim())
                else if (artist.isNotEmpty()) Note(i, KIND_ARTIST_THEN_NEW, k, "", artist, next!!.artist.trim())
                else Note(i, KIND_KNOWN_THEN_NEW, k, "", "", next!!.artist.trim())
            } else if (k >= 3 && genre.isNotEmpty()) {
                made = Note(i, KIND_GENRE_ONLY, k, genre, "", "")
            }
            if (made == null) {
                i++
                continue
            }
            out.add(made)
            i += if (span > MIN_GAP) span else MIN_GAP
        }
        return out
    }

    /** The genre all [k] slots from [from] share (compared folded), or "" when any has none or they differ. The first slot's spelling is kept. */
    private fun sharedGenre(slots: List<Slot>, from: Int, k: Int): String {
        val first = slots[from].genre.trim()
        if (first.isEmpty()) return ""
        val key = BrainText.fold(first)
        for (j in 1 until k) {
            val g = slots[from + j].genre.trim()
            if (g.isEmpty() || BrainText.fold(g) != key) return ""
        }
        return first
    }

    private fun sharedArtist(slots: List<Slot>, from: Int, k: Int): String {
        val first = slots[from].artist.trim()
        if (first.isEmpty()) return ""
        val key = BrainText.fold(first)
        for (j in 1 until k) {
            val a = slots[from + j].artist.trim()
            if (a.isEmpty() || BrainText.fold(a) != key) return ""
        }
        return first
    }

    /** "Two", "Three", "Four", "Five". */
    fun countWord(count: Int): String = when (count) {
        2 -> "Two"
        3 -> "Three"
        4 -> "Four"
        5 -> "Five"
        else -> count.toString()
    }

    /** The first genre of a genre field: Spotify joins several with a comma, a semicolon or a slash. "" for none. */
    fun firstGenre(raw: String?): String {
        if (raw == null) return ""
        var end = raw.length
        for (c in charArrayOf(',', ';', '/', '|')) {
            val at = raw.indexOf(c)
            if (at in 0 until end) end = at
        }
        return raw.substring(0, end).trim()
    }

    /** The sentence of [note]. */
    fun text(note: Note): String {
        val c = countWord(note.count)
        return when (note.kind) {
            KIND_GENRE_THEN_NEW -> "$c from your ${note.genre} side, then something new from ${note.newArtist}."
            KIND_ARTIST_THEN_NEW -> "$c from ${note.artist}, then something new from ${note.newArtist}."
            KIND_KNOWN_THEN_NEW -> "$c you know, then something new from ${note.newArtist}."
            KIND_GENRE_ONLY -> "$c from your ${note.genre} side."
            else -> ""
        }
    }
}
