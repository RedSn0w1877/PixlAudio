package com.theveloper.pixelplay.brain

/**
 * What the listener sees under an Ask Taizo answer (docs/brain-spec.md, 9.3 "What the listener gets", stage 4 milestone 2): the chips under a
 * mix card, their words, and the one-tap "Less X" that ends an explanation. All of it is a plain function of its inputs, so a chip is a delta
 * on the stored request applied on the device with no model call, and the Swift port reproduces `assistantcards.jsonl` bit for bit.
 *
 * The chips themselves ([AssistantRules.Chip], [AssistantRules.applyChip], [AssistantRules.chipsFor]) are milestone 1's and are unchanged;
 * this adds the chips beyond the first three and the words every chip wears.
 */
object AssistantCards {

    /** At most this many chips under one card. */
    const val MAX_CHIPS = 6

    /** A name in a chip longer than this is cut (a chip is one line). */
    const val MAX_NAME = 22

    /** The share of new music at which "More new" stops doing anything (the planner caps it at [MixPlanner.NU_MAX]). */
    const val SHARE_TOP = 60

    /**
     * The chips under a mix card, in this order: [AssistantRules.chipsFor] (Less artist, More genre, Shorter or Longer), then one chip
     * for the pace (Slower, or Calmer when tempo is not known for this listener's tracks, none when neither filter can be applied), then
     * More new and More familiar, each only when it can change the request. [unsupported] is the list of filters the mix could not apply
     * (`AssistantRules.unsupportedFilters`): a chip for a filter nobody can answer is not offered.
     */
    fun allChips(q: AssistantRules.Query, topArtistRef: String, topGenreId: Int, unsupported: List<String>): List<AssistantRules.Chip> {
        val out = ArrayList<AssistantRules.Chip>(MAX_CHIPS)
        out.addAll(AssistantRules.chipsFor(q, topArtistRef, topGenreId))
        val tempoOk = AssistantRules.FILTER_TEMPO !in unsupported
        val energyOk = AssistantRules.FILTER_ENERGY !in unsupported
        if (tempoOk && q.tempo != AssistantRules.SLOW) out.add(AssistantRules.Chip(AssistantRules.CHIP_SLOWER))
        else if (energyOk && q.energy != AssistantRules.LOWER) out.add(AssistantRules.Chip(AssistantRules.CHIP_LOWER_ENERGY))
        val share = if (q.noveltySharePct >= 0) q.noveltySharePct else AssistantRules.SHARE_BASE
        if (share < SHARE_TOP) out.add(AssistantRules.Chip(AssistantRules.CHIP_MORE_NEW))
        if (share > 0) out.add(AssistantRules.Chip(AssistantRules.CHIP_MORE_FAMILIAR))
        return if (out.size > MAX_CHIPS) ArrayList(out.subList(0, MAX_CHIPS)) else out
    }

    /**
     * The words of a chip. "Less Sabrina Carpenter", "More R&B", "Shorter". A chip that is about a name has no label without one ("" is
     * "do not show it"): the label never guesses a name.
     */
    fun chipLabel(chip: AssistantRules.Chip, artistName: String, genreName: String): String = when (chip.kind) {
        AssistantRules.CHIP_LESS_ARTIST -> withName("Less ", artistName)
        AssistantRules.CHIP_MORE_GENRE -> withName("More ", genreName)
        AssistantRules.CHIP_LESS_GENRE -> withName("Less ", genreName)
        AssistantRules.CHIP_SHORTER -> "Shorter"
        AssistantRules.CHIP_LONGER -> "Longer"
        AssistantRules.CHIP_MORE_NEW -> "More new"
        AssistantRules.CHIP_MORE_FAMILIAR -> "More familiar"
        AssistantRules.CHIP_SLOWER -> "Slower"
        AssistantRules.CHIP_FASTER -> "Faster"
        AssistantRules.CHIP_LOWER_ENERGY -> "Calmer"
        AssistantRules.CHIP_HIGHER_ENERGY -> "Livelier"
        else -> ""
    }

    private fun withName(prefix: String, rawName: String): String {
        val name = shortName(rawName)
        return if (name.isEmpty()) "" else prefix + name
    }

    /** [raw] on one line and at most [MAX_NAME] characters; a longer name ends in an ellipsis. */
    fun shortName(raw: String): String {
        val t = AssistantRules.cleanTitle(raw)
        if (t.length <= MAX_NAME) return t
        return t.substring(0, MAX_NAME - 1).trim() + "…"
    }

    // ── "Less X": the one tap that ends an explanation ───────────────────────

    const val NAMED_GENRE = 0
    const val NAMED_ARTIST = 1

    /** A genre or an artist that a tool result held (so it is real) and that a "Less X" could be about. */
    class Named(val kind: Int, val name: String)

    /** Words that, just before a name, say the listener likes it: no "Less X" is offered for a name they praised. */
    private val LIKING = setOf("love", "loves", "like", "likes", "enjoy", "enjoys", "adore", "favourite", "favorite", "into", "want")

    /** How many words before a name are read for a liking word. */
    private const val LIKING_WINDOW = 3

    /**
     * Which of [named] the listener asked about in [userText] ("Why do I keep getting trap?" names trap). Exactly one name must be a
     * whole-word run of what they wrote (a name inside a longer one that also matches, "rock" in "indie rock", does not count), and no
     * liking word may stand in the three words before it ("I love rock though"): a listener who names two things, or praises the thing they
     * named, gets no chip, because a "Less X" for the wrong one would be a guess. Names shorter than two characters never match.
     */
    fun lessTarget(userText: String, named: List<Named>): Named? {
        val text = " " + BrainText.fold(userText).trim() + " "
        class Hit(val named: Named, val at: Int, val end: Int)
        val hits = ArrayList<Hit>()
        for (n in named) {
            val f = BrainText.fold(n.name).trim()
            if (f.length < 2) continue
            var from = 0
            while (true) {
                val at = indexOfWords(text, f, from)
                if (at < 0) break
                hits.add(Hit(n, at, at + f.length))
                from = at + 1
            }
        }
        // A hit inside a longer hit is the same mention.
        val outer = hits.filter { h -> hits.none { o -> o !== h && o.at <= h.at && o.end >= h.end && (o.end - o.at) > (h.end - h.at) } }
        val liked = HashSet<String>()
        val wanted = LinkedHashMap<String, Named>()
        for (h in outer) {
            val key = h.named.kind.toString() + ":" + BrainText.fold(h.named.name)
            val before = text.substring(0, h.at).trim().split(' ').filter { it.isNotEmpty() }.takeLast(LIKING_WINDOW)
            if (before.any { it in LIKING }) liked.add(key) else wanted.putIfAbsent(key, h.named)
        }
        for (k in liked) wanted.remove(k)
        return if (wanted.size == 1) wanted.values.first() else null
    }

    /** The index of [needle] in [haystack] with no word character on either side, from [from]; -1 when there is none. */
    private fun indexOfWords(haystack: String, needle: String, from: Int): Int {
        var start = from
        while (true) {
            val at = haystack.indexOf(needle, start)
            if (at < 0) return -1
            val before = if (at > 0) haystack[at - 1] else ' '
            val afterIndex = at + needle.length
            val after = if (afterIndex < haystack.length) haystack[afterIndex] else ' '
            if (!isWordChar(before) && !isWordChar(after)) return at
            start = at + 1
        }
    }

    private fun isWordChar(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || c.code > 0x7F

    /** The words of the one-tap chip: "Less trap". An empty name has no chip. */
    fun lessLabel(name: String): String = withName("Less ", name)
}
