package com.theveloper.pixelplay.brain

/**
 * The weekly note on First Listen (docs/brain-spec.md, 9.3 and 9.4, stage 4 milestone 2): two or three sentences about this week's list.
 * The words come from one cloud call a week over a handful of evidence rows, with the template here as the fallback when the call is not
 * made (offline, over budget, switched off) or when its answer is not clearly true of the evidence ([check]).
 *
 * Nothing here ranks and nothing invents: every name and every number in a note is in the evidence rows or the note is not shown.
 * A plain function of its inputs, no clock and no I/O, so the Swift port reproduces `weeklynote.jsonl`.
 */
object WeeklyNote {

    /** Bump when the instruction text or the evidence rows change: the cache is keyed by it. */
    const val PROMPT_VERSION = 1

    const val MAX_CHARS = 420
    const val MIN_SENTENCES = 2
    const val MAX_SENTENCES = 3

    /** Words the app never says, in any user-visible text (the copy rule), and the one stage 3 allows only where it is true. */
    val FORBIDDEN: List<String> = listOf(
        "lossless", "hi-res", "hires", "dolby", "atmos", "360 reality", "claude", "anthropic", "binilyrics", "enhanced", "learn",
    )

    /**
     * What is known about this week's list. -1 and "" are unknown, and an unknown row is left out of the note and the evidence.
     * [artists] are the first artists of the list in order (at most three are used); [genre] is the name of the adjacent genre the list
     * leans on; [island] the taste island that genre sits next to; [newSharePct] the share of the last 7 days' plays that were new to the listener.
     */
    class Evidence(
        val tracks: Int,
        val newSongs: Int,
        val artists: List<String>,
        val genre: String,
        val island: String,
        val newSharePct: Int,
    )

    private fun artistsOf(e: Evidence): List<String> {
        val out = ArrayList<String>(3)
        for (a in e.artists) {
            val t = a.trim()
            if (t.isNotEmpty() && t !in out) out.add(t)
            if (out.size == 3) break
        }
        return out
    }

    /** "a", "a and b", "a, b and c". */
    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> names[0] + " and " + names[1]
        else -> names[0] + ", " + names[1] + " and " + names[2]
    }

    /**
     * The note when no model wrote it: up to three sentences, each one true of the evidence. "" when there is nothing to say (no tracks).
     */
    fun template(e: Evidence): String {
        if (e.tracks < 1) return ""
        val sb = StringBuilder()
        val songs = if (e.tracks == 1) "1 song" else "${e.tracks} songs"
        sb.append("This week's First Listen has ").append(songs)
        if (e.newSongs in 1..e.tracks) {
            sb.append(", and ").append(e.newSongs).append(if (e.newSongs == 1) " of them is not in your library yet." else " of them are not in your library yet.")
        } else {
            sb.append('.')
        }
        val genre = e.genre.trim()
        val island = e.island.trim()
        val artists = artistsOf(e)
        if (genre.isNotEmpty() && island.isNotEmpty()) {
            sb.append(" Some of it is $genre, which sits next to $island.")
        } else if (genre.isNotEmpty()) {
            sb.append(" Some of it is $genre, a genre next to what you play.")
        } else if (artists.isNotEmpty()) {
            sb.append(" It opens with songs by ").append(joinNames(artists)).append('.')
        }
        if (e.newSharePct in 0..100) {
            sb.append(" In the last 7 days, ").append(e.newSharePct).append("% of what you played was new to you.")
        }
        return sb.toString()
    }

    /** The evidence as the lines the model is given (and the text its answer is checked against). Unknown rows are left out. */
    fun evidenceLines(e: Evidence): String {
        val sb = StringBuilder()
        sb.append("songs in this week's list: ").append(e.tracks).append('\n')
        if (e.newSongs >= 0) sb.append("songs in the list that are not in the listener's library yet: ").append(e.newSongs).append('\n')
        val artists = artistsOf(e)
        if (artists.isNotEmpty()) sb.append("first artists in the list: ").append(joinNames(artists)).append('\n')
        if (e.genre.isNotBlank()) sb.append("the genre the list leans on: ").append(e.genre.trim()).append('\n')
        if (e.island.isNotBlank()) sb.append("the taste it sits next to: ").append(e.island.trim()).append('\n')
        if (e.newSharePct >= 0) sb.append("share of the listener's plays in the last 7 days that were new to them: ").append(e.newSharePct).append("%\n")
        return sb.toString()
    }

    /** Hash of the evidence and the prompt version: one cloud call per week, and none when nothing changed. */
    fun evidenceHash(e: Evidence): Long = BrainHash.fnv1a64("weekly|$PROMPT_VERSION|" + evidenceLines(e))

    /** The registry a note is checked against: the names in the evidence, and its numbers. */
    fun registry(e: Evidence): Grounding.Registry {
        val r = Grounding.Registry()
        for (a in artistsOf(e)) r.addName(a)
        r.addName(e.genre)
        r.addName(e.island)
        r.addText(evidenceLines(e))
        r.addNumber(e.tracks)
        if (e.newSongs >= 0) r.addNumber(e.newSongs)
        if (e.newSharePct >= 0) r.addNumber(e.newSharePct)
        return r
    }

    /**
     * [cloud] if it is a note we may show, else null (the caller shows [template]). It must be two or three sentences of at most
     * [MAX_CHARS], with no word of [FORBIDDEN], no line break or list, and every name and number in it must come from [e] (a name is
     * marked `[[Name]]` by the model, as in the chat; the marks are taken off).
     */
    fun check(cloud: String, e: Evidence): String? {
        val text = oneLine(cloud)
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        val folded = BrainText.fold(text)
        for (w in FORBIDDEN) if (folded.contains(w)) return null
        val sentences = sentenceCount(text)
        if (sentences < MIN_SENTENCES || sentences > MAX_SENTENCES) return null
        val verdict = Grounding.check(text, registry(e), "", "\u0000", false)
        if (!verdict.ok) return null
        for (c in verdict.cleaned) if (c == '\u0000' || c == '[' || c == ']') return null
        return verdict.cleaned.trim()
    }

    /** [raw] with its line breaks and runs of spaces turned into single spaces. */
    fun oneLine(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastSpace = true
        for (c in raw) {
            val space = c == ' ' || c == '\n' || c == '\r' || c == '\t'
            if (space) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** Sentences are ended by `.`, `!` or `?` followed by a space or the end; a number's decimal point is not an end. */
    fun sentenceCount(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                if (next == ' ') n++
                while (i + 1 < text.length && (text[i + 1] == '.' || text[i + 1] == '!' || text[i + 1] == '?')) i++
            }
            i++
        }
        // A last sentence with no ending mark still counts.
        val last = text.trimEnd()
        if (last.isNotEmpty() && last.last() != '.' && last.last() != '!' && last.last() != '?') n++
        return n
    }
}
