package com.theveloper.pixelplay.brain

/**
 * The checks on what the optional batch jobs bring back (docs/brain-spec.md, 9.4 "Batch jobs", stage 4 milestone 2). The jobs run only with
 * the switch "Let Taizo help the engine", which is off by default, inside the monthly budget, and their answers are cached forever by
 * (model id, prompt version, input hash). Whatever the model writes is data: a plain function here decides what of it may be used, and an
 * invalid id, an unknown word or a name the island does not hold is dropped, never shown and never repaired.
 *
 * The Swift port reproduces `assistbatch.jsonl`.
 */
object AssistBatch {

    /** Bump when a prompt or a rule here changes: the caches are keyed by it. */
    const val PROMPT_VERSION = 1

    /** The cache key of one job: the model, the prompt version, the kind of job and the exact input text. */
    fun cacheKey(modelId: String, kind: String, input: String): Long =
        BrainHash.fnv1a64("assist|$modelId|$PROMPT_VERSION|$kind|$input")

    // ── Island names ─────────────────────────────────────────────────────────

    const val MIN_NAME = 3
    const val MAX_NAME = 28
    const val MAX_NAME_WORDS = 4
    const val MAX_LINE = 90

    /** What an island holds: its id, the names of its genres in the atlas, and its top artists. Only these words may name it. */
    class IslandIn(val id: Int, val genres: List<String>, val artists: List<String>)

    class IslandName(val id: Int, val name: String, val line: String)

    /**
     * The names of [outs] that may be shown. An answer is kept when its id is one of [ins]; its name is 3 to 28 characters, at most four
     * words, made of letters, digits, spaces and `& ' -`; it shares a whole word (three letters or more) with the names of that island's own
     * genres, so it cannot be about something else; it is not an artist's name; and neither it nor the one-line description holds a word the
     * app never says. The description is at most 90 characters with no digit, quote or bracket. A description that fails is dropped alone:
     * the name is kept with an empty line. Each island is named once (the first valid answer wins).
     */
    fun validateIslandNames(ins: List<IslandIn>, outs: List<IslandName>): List<IslandName> {
        val byId = HashMap<Int, IslandIn>()
        for (i in ins) byId[i.id] = i
        val done = HashSet<Int>()
        val kept = ArrayList<IslandName>()
        for (o in outs) {
            val island = byId[o.id] ?: continue
            if (o.id in done) continue
            val name = WeeklyNote.oneLine(o.name)
            if (!nameOk(name, island)) continue
            done.add(o.id)
            val line = WeeklyNote.oneLine(o.line)
            kept.add(IslandName(o.id, name, if (lineOk(line)) line else ""))
        }
        return kept
    }

    private fun nameOk(name: String, island: IslandIn): Boolean {
        if (name.length < MIN_NAME || name.length > MAX_NAME) return false
        var words = 0
        var inWord = false
        for (c in name) {
            val letter = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c.code > 0x7F
            if (!(letter || c == ' ' || c == '&' || c == '\'' || c == '-')) return false
            if (c == ' ') inWord = false else if (!inWord) { inWord = true; words++ }
        }
        if (words == 0 || words > MAX_NAME_WORDS) return false
        val folded = BrainText.fold(name)
        for (w in WeeklyNote.FORBIDDEN) if (folded.contains(w)) return false
        for (a in island.artists) if (BrainText.fold(a).trim() == folded.trim()) return false
        // One whole word in common with the island's own genres (a word of three letters or more).
        val own = HashSet<String>()
        for (g in island.genres) for (w in wordsOf(BrainText.fold(g))) if (w.length >= 3) own.add(w)
        for (w in wordsOf(folded)) if (w.length >= 3 && w in own) return true
        return false
    }

    private fun lineOk(line: String): Boolean {
        if (line.isEmpty() || line.length > MAX_LINE) return false
        for (c in line) if ((c in '0'..'9') || c == '"' || c == '[' || c == ']' || c == '{' || c == '}') return false
        val folded = BrainText.fold(line)
        for (w in WeeklyNote.FORBIDDEN) if (folded.contains(w)) return false
        return true
    }

    /** The words of [folded]: runs of letters and digits. */
    fun wordsOf(folded: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in folded) {
            if ((c in 'a'..'z') || (c in '0'..'9') || c.code > 0x7F) sb.append(c)
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ── Constrained genre tags (tag source 10) ───────────────────────────────

    /** Tracks a tagging call holds, and atlas ids it may choose among for each. */
    const val TAG_BATCH = 50
    const val SHORTLIST = 30
    const val MAX_TAGS_PER_TRACK = 3

    /** A claim must be at least this sure to be kept (the source's own trust is 0.25 in `Tags`; this is its own confidence). */
    const val MIN_CONFIDENCE = 0.5

    class TagOut(val index: Int, val atlasId: Int, val confidence: Double)

    /**
     * The claims of [outs] that may be used. [shortlists] holds, for each track of the call, the atlas ids it was allowed to answer with. A
     * claim is kept when its track index exists, its id is on THAT track's shortlist, and its confidence is within 0.5 to 1; a track keeps
     * at most three, the most confident first (ties to the lower id), each id once. The rest are dropped.
     */
    fun validateTags(shortlists: List<IntArray>, outs: List<TagOut>): List<TagOut> {
        val perTrack = HashMap<Int, ArrayList<TagOut>>()
        for (o in outs) {
            if (o.index < 0 || o.index >= shortlists.size) continue
            if (!(o.confidence >= MIN_CONFIDENCE && o.confidence <= 1.0)) continue
            var onList = false
            for (id in shortlists[o.index]) if (id == o.atlasId) { onList = true; break }
            if (!onList) continue
            val list = perTrack.getOrPut(o.index) { ArrayList() }
            var dup = false
            for (x in list) if (x.atlasId == o.atlasId) { dup = true; break }
            if (!dup) list.add(o)
        }
        val kept = ArrayList<TagOut>()
        for (index in perTrack.keys.sorted()) {
            val list = perTrack[index]!!
            list.sortWith(compareByDescending<TagOut> { it.confidence }.thenBy { it.atlasId })
            for (k in 0 until minOf(list.size, MAX_TAGS_PER_TRACK)) kept.add(list[k])
        }
        return kept
    }

    // ── "Artists this listener likely does not know" (ASK arm) ───────────────

    const val ASK_PER_ISLAND = 20

    /**
     * The names of [raw] worth looking up: trimmed, 2 to 60 characters, no digits-only, not already one of [knownFolded] (the listener's own
     * artists, folded), each once, at most [ASK_PER_ISLAND]. A name is still only a name: it becomes a suggestion only if a search finds
     * a real track by it, so nothing here is ever shown by itself.
     */
    fun validateArtistNames(raw: List<String>, knownFolded: Set<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (r in raw) {
            val t = WeeklyNote.oneLine(r)
            if (t.length < 2 || t.length > 60) continue
            val f = BrainText.fold(t).trim()
            if (f.isEmpty() || f in knownFolded || !seen.add(f)) continue
            var allDigits = true
            for (c in f) if (c !in '0'..'9') { allDigits = false; break }
            if (allDigits) continue
            out.add(t)
            if (out.size >= ASK_PER_ISLAND) break
        }
        return out
    }
}
