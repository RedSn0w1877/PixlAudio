package com.theveloper.pixelplay.brain

/**
 * Host notes of Endless Listening: one short line about a track that is coming, built from facts the app really holds about it (an island's
 * name, the atlas' one-line description of a genre, a release year, "new from an artist you play", "a step sideways"). The stage 4 set
 * notes ([SetNotes]) stay what they were; these are the host-only kinds, and the two never share a track.
 *
 * Rules, all pure so the Swift port reproduces `endless.jsonl`:
 *  - a note is a template with slots filled from real data; when a slot has nothing to fill it, that kind is not used for the track, and a
 *    track nothing can be said about has no note (nothing is invented);
 *  - at most one note per [MIN_GAP] tracks, counting the set notes already in the queue ([Taken]): a host note starts at least [MIN_GAP]
 *    tracks away from the start of any other note, and never inside the tracks a set note describes;
 *  - every sentence is short (at most [MAX_WORDS] words, two sentences at most) so a spoken line can say it as it stands, and a text that
 *    holds a word the app never says ([WeeklyNote.FORBIDDEN]) is dropped.
 */
object HostNotes {
    const val MIN_GAP = SetNotes.MIN_GAP

    const val MAX_WORDS = 12
    const val MAX_SENTENCES = 2
    const val MAX_FACT_WORDS = 9
    const val MAX_FACT_CHARS = 80

    const val KIND_NEW_FROM_ARTIST = 10
    const val KIND_SIDEWAYS = 11
    const val KIND_ISLAND = 12
    const val KIND_GENRE_FACT = 13
    const val KIND_YEAR = 14

    /**
     * One upcoming track as the host sees it; "" and 0 are unknown. [genre] is the track's first genre; [genreFact] the atlas' description of
     * that genre; [island] the name of the taste island the track sits in; [newFromPlayedArtist] says the track is a new release by an artist the
     * listener plays; [sideways] says the engine picked it from a genre next to the listener's own.
     */
    class Slot(
        val artist: String,
        val genre: String,
        val genreFact: String,
        val year: Int,
        val island: String,
        val newFromPlayedArtist: Boolean,
        val sideways: Boolean,
    )

    /** A note already in the queue: the set note that starts at [index] and describes [span] tracks. */
    class Taken(val index: Int, val span: Int)

    /** [a] and [b] are what the template says: see [text]. */
    class Note(val index: Int, val kind: Int, val a: String, val b: String)

    /**
     * The host notes of [slots] (the current track first), in order. Never throws. [sinceLast] is how many tracks ago the last note was shown or
     * spoken before the current track (1 = the track just before it; [MIN_GAP] or more = none close enough): the window is made again at every
     * track, so without it the current track would always be free for a note and one would land on almost every track (see [tracksSinceNote]).
     */
    fun notes(slots: List<Slot>, taken: List<Taken> = emptyList(), sinceLast: Int = MIN_GAP): List<Note> {
        val out = ArrayList<Note>()
        var last = -(if (sinceLast < 1) 1 else if (sinceLast > MIN_GAP) MIN_GAP else sinceLast)
        for (i in slots.indices) {
            if (i - last < MIN_GAP) continue
            if (!free(i, taken)) continue
            val note = candidate(slots, i) ?: continue
            out.add(note)
            last = i
        }
        return out
    }

    /**
     * How many tracks before [index] (1 to [MIN_GAP] - 1) the nearest earlier queue position sits whose song is in [noted] (the songs whose
     * note was shown or spoken); [MIN_GAP] when none of the [MIN_GAP] - 1 tracks before [index] is one. The queue is [ids] in order.
     */
    fun tracksSinceNote(ids: List<String>, index: Int, noted: Set<String>): Int {
        var d = 1
        while (d < MIN_GAP) {
            val j = index - d
            if (j >= 0 && j < ids.size && noted.contains(ids[j])) return d
            d++
        }
        return MIN_GAP
    }

    /** True when a note at [index] is at least [MIN_GAP] tracks from every taken note and outside the tracks a taken note describes. */
    private fun free(index: Int, taken: List<Taken>): Boolean {
        for (t in taken) {
            val after = t.index + (if (t.span > MIN_GAP) t.span else MIN_GAP)
            if (index >= after) continue
            if (index <= t.index - MIN_GAP) continue
            return false
        }
        return true
    }

    private fun candidate(slots: List<Slot>, i: Int): Note? {
        val s = slots[i]
        val artist = s.artist.trim()
        val genre = s.genre.trim()
        var note: Note? = null
        if (s.newFromPlayedArtist && artist.isNotEmpty()) note = checked(Note(i, KIND_NEW_FROM_ARTIST, artist, ""))
        // The engine only says this pick comes from a genre next to the listener's own: the note says that and nothing more.
        if (note == null && s.sideways && genre.isNotEmpty()) note = checked(Note(i, KIND_SIDEWAYS, genre, ""))
        if (note == null && s.island.trim().isNotEmpty()) note = checked(Note(i, KIND_ISLAND, s.island.trim(), ""))
        if (note == null && genre.isNotEmpty()) {
            val fact = factOf(s.genreFact)
            if (fact.isNotEmpty()) note = checked(Note(i, KIND_GENRE_FACT, genre, fact))
        }
        if (note == null && s.year in 1900..2100) note = checked(Note(i, KIND_YEAR, s.year.toString(), ""))
        return note
    }

    /**
     * The first sentence of an atlas description, cut to a short clause and ended with a full stop; "" when it is empty, too long, or holds a
     * line break (a long sentence is not shortened, because a shortened one can say something else).
     */
    fun factOf(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        var end = text.length
        var k = 0
        while (k < text.length) {
            val c = text[k]
            if ((c == '.' || c == '!' || c == '?') && (k + 1 >= text.length || text[k + 1] == ' ')) {
                end = k
                break
            }
            k++
        }
        text = text.substring(0, end).trim()
        if (text.isEmpty() || text.length > MAX_FACT_CHARS || text.indexOf('\n') >= 0) return ""
        if (wordCount(text) > MAX_FACT_WORDS) return ""
        return text
    }

    /** The sentence of [note]. */
    fun text(note: Note): String = when (note.kind) {
        KIND_NEW_FROM_ARTIST -> "New from ${note.a}, an artist you play."
        KIND_SIDEWAYS -> "A step sideways: ${note.a}."
        KIND_ISLAND -> "From your ${note.a} side."
        KIND_GENRE_FACT -> "${note.a}: ${note.b}."
        KIND_YEAR -> "Released in ${note.a}."
        else -> ""
    }

    /**
     * [note] when its text is short and clean, else null (the kind is then not used for the track). The words the app never says are looked for
     * in what the app wrote (the template and the atlas' sentence), not in a name: an artist called Claude Debussy or a group called Atmosphere is
     * real data, not copy.
     */
    private fun checked(note: Note): Note? {
        val text = text(note)
        if (text.isEmpty() || !isShort(text)) return null
        val ours = text(Note(note.index, note.kind, "", if (note.kind == KIND_GENRE_FACT) note.b else ""))
        return if (isClean(ours)) note else null
    }

    /** True when [text] is short (see [isShort]) and has none of the words the app never says (whole words, see [isClean]). */
    fun isSpeakable(text: String): Boolean = isShort(text) && isClean(text)

    /**
     * True when no word or phrase of [WeeklyNote.FORBIDDEN] is in [text] as whole words: "Atmosphere" and "learner" are not "atmos" and "learn".
     * Compared folded, so "Hi-Res" is "hi res" on both sides.
     */
    fun isClean(text: String): Boolean {
        val padded = " " + BrainText.fold(text) + " "
        for (w in WeeklyNote.FORBIDDEN) if (padded.contains(" " + BrainText.fold(w) + " ")) return false
        return true
    }

    /** True when [text] has at most [MAX_SENTENCES] sentences of at most [MAX_WORDS] words. */
    fun isShort(text: String): Boolean {
        var sentences = 0
        var words = 0
        var inWord = false
        var any = false
        for (c in text) {
            if (c == ' ') {
                if (inWord) words++
                inWord = false
            } else if (c == '.' || c == '!' || c == '?') {
                if (inWord) words++
                inWord = false
                if (any) {
                    sentences++
                    if (words > MAX_WORDS) return false
                }
                words = 0
                any = false
            } else {
                inWord = true
                any = true
            }
        }
        if (inWord) words++
        if (any) {
            sentences++
            if (words > MAX_WORDS) return false
        }
        return sentences in 1..MAX_SENTENCES
    }

    /** The number of words of [text]: runs of non-space characters. */
    fun wordCount(text: String): Int {
        var words = 0
        var inWord = false
        for (c in text) {
            if (c == ' ') {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
        }
        return words
    }
}
