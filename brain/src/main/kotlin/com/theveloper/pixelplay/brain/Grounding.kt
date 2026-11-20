package com.theveloper.pixelplay.brain

/**
 * The grounding rule of Ask Taizo (docs/brain-spec.md, 1 principle 9, 9.4): the assistant's reply can never name a track, album or artist
 * that a tool did not return in the same conversation, and a number about the listener comes only from a tool result.
 *
 * Code cannot tell a title from any other words in free text, so the prompt asks the model to mark every track, album and artist it names
 * as `[[Name]]` (deviation 208). This object finds those marks, and also double-quoted phrases (a title the model forgot to mark), and
 * checks each against what the conversation really held: the names tools returned ([Registry.addName]), free text that tools returned
 * (lyrics, a tool's JSON: [Registry.addText]), and the user's own words. Pure: no regular expressions, folded text only
 * ([BrainText.fold]), so the same text gives the same verdict on both platforms.
 */
object Grounding {

    /** The longest and shortest span that counts as a name. */
    const val MAX_SPAN = 120
    const val MIN_QUOTED = 2
    const val MAX_QUOTED = 80

    /** Everything the conversation really held. */
    class Registry {
        private val names = HashSet<String>()
        private val corpus = ArrayList<String>()
        private val numbers = HashSet<String>()

        /** A name a tool returned (a title, an artist, an album). Its clean-title form counts too ("Song - Remastered" is "Song"). */
        fun addName(raw: String?) {
            if (raw == null) return
            val folded = BrainText.fold(raw).trim()
            if (folded.isEmpty()) return
            names.add(folded)
            val clean = BrainText.cleanTitle(raw).title.trim()
            if (clean.isNotEmpty()) names.add(clean)
        }

        /** Free text the model may quote from, or numbers it may read: a tool result as text, lyrics, what the user said. */
        fun addText(raw: String?) {
            if (raw == null || raw.isEmpty()) return
            corpus.add(BrainText.fold(raw))
            for (n in numbersIn(raw)) numbers.add(n)
        }

        /** True when [folded] is a name a tool returned. */
        fun hasName(folded: String): Boolean = folded in names

        /** True when [folded] is a part of some registered free text. */
        fun inText(folded: String): Boolean {
            if (folded.isEmpty()) return false
            for (t in corpus) if (t.contains(folded)) return true
            return false
        }

        /** A number the app itself vouches for (how many items a tool returned). */
        fun addNumber(n: Int) { numbers.add(n.toString()) }

        fun hasNumber(token: String): Boolean = token in numbers

        val size: Int get() = names.size
    }

    class Span(val start: Int, val end: Int, val inner: String, val marked: Boolean)

    /** The `[[...]]` marks of [text], in order (an unclosed mark, an empty one and a long one are not spans). */
    fun markedSpans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i + 1 < text.length) {
            if (text[i] == '[' && text[i + 1] == '[') {
                var j = i + 2
                var ok = false
                while (j + 1 < text.length && j - i <= MAX_SPAN + 2) {
                    if (text[j] == '\n') break
                    if (text[j] == ']' && text[j + 1] == ']') { ok = true; break }
                    j++
                }
                if (ok) {
                    val inner = text.substring(i + 2, j).trim()
                    if (inner.isNotEmpty()) out.add(Span(i, j + 2, inner, true))
                    i = j + 2
                    continue
                }
            }
            i++
        }
        return out
    }

    private const val LEFT_QUOTE = 0x201C
    private const val RIGHT_QUOTE = 0x201D

    private fun isOpenQuote(c: Char): Boolean = c == '"' || c.code == LEFT_QUOTE
    private fun isCloseQuote(c: Char, open: Char): Boolean = if (open == '"') c == '"' else c.code == RIGHT_QUOTE

    /** Double-quoted phrases of [text] that are not inside a `[[...]]` mark: a title the model quoted without marking it. */
    fun quotedSpans(text: String, marked: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            val inMark = marked.any { i >= it.start && i < it.end }
            if (!inMark && isOpenQuote(text[i])) {
                val open = text[i]
                var j = i + 1
                var closed = -1
                while (j < text.length && j - i <= MAX_QUOTED + 1) {
                    if (text[j] == '\n') break
                    if (isCloseQuote(text[j], open)) { closed = j; break }
                    j++
                }
                if (closed > 0) {
                    val inner = text.substring(i + 1, closed).trim()
                    if (inner.length >= MIN_QUOTED) out.add(Span(i, closed + 1, inner, false))
                    i = closed + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /** Punctuation that joins names in the raw text; fold turns punctuation into a space, so these are split before folding. */
    private val RAW_JOINERS = listOf(", ", " & ", " / ", " - ", " " + 0x2013.toChar() + " ", " " + 0x2014.toChar() + " ", " + ", " " + 0x00D7.toChar() + " ", " " + 0x00B7.toChar() + " ")

    /** Words that join names once folded. */
    private val WORD_JOINERS = listOf(" by ", " and ", " feat ", " ft ", " featuring ", " with ", " x ")

    /**
     * True when [inner] is grounded: a name a tool returned, or a list of such names joined the way a listener writes them
     * ("Espresso by Sabrina Carpenter", "Daft Punk & Pharrell"). A quoted span (not marked) also passes when it is part of free text the
     * conversation held ([quotedOk]: lyrics, a tool result, what the user said).
     */
    fun isGrounded(inner: String, registry: Registry, marked: Boolean, userText: String = ""): Boolean {
        val folded = BrainText.fold(inner).trim()
        if (folded.isEmpty()) return true
        if (registry.hasName(folded)) return true
        val clean = BrainText.cleanTitle(inner).title.trim()
        if (clean.isNotEmpty() && registry.hasName(clean)) return true
        if (!marked) {
            if (registry.inText(folded)) return true
            if (userText.isNotEmpty() && BrainText.fold(userText).contains(folded)) return true
        }
        // Several names in one mark: every part must be a returned name.
        var raw = listOf(inner)
        var split = false
        for (j in RAW_JOINERS) {
            val next = ArrayList<String>()
            for (p in raw) {
                var from = 0
                while (true) {
                    val at = p.indexOf(j, from)
                    if (at < 0) break
                    next.add(p.substring(from, at))
                    from = at + j.length
                    split = true
                }
                next.add(p.substring(from))
            }
            raw = next
        }
        var parts = ArrayList<String>()
        for (p in raw) parts.add(" " + BrainText.fold(p) + " ")
        for (j in WORD_JOINERS) {
            val next = ArrayList<String>()
            for (p in parts) {
                var from = 0
                while (true) {
                    val at = p.indexOf(j, from)
                    if (at < 0) break
                    next.add(p.substring(from, at) + " ")
                    from = at + j.length - 1
                    split = true
                }
                next.add(p.substring(from))
            }
            parts = next
        }
        if (!split) return false
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (!registry.hasName(t)) return false
        }
        return true
    }
    // ---- Numbers ----

    /** Units that make a number a statement about the listener ("212 plays", "9%", "3 hours"). */
    private val STAT_UNITS = listOf(
        "plays", "play", "times", "time", "skips", "skip", "hours", "hour", "minutes", "minute", "days", "day", "weeks", "week",
        "months", "month", "songs", "tracks", "percent", "listens", "sessions",
    )

    /** The numbers written in [text] as plain tokens: digits with an optional decimal part ("1,240" is "1240", "9.5" stays). */
    fun numbersIn(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9' && (i == 0 || !isLetter(text[i - 1]))) {
                val sb = StringBuilder()
                var j = i
                while (j < text.length) {
                    val d = text[j]
                    if (d in '0'..'9') sb.append(d)
                    else if (d == ',' && j + 1 < text.length && text[j + 1] in '0'..'9' && j > i) { /* thousands separator */ }
                    else if (d == '.' && j + 1 < text.length && text[j + 1] in '0'..'9' && j > i) sb.append(d)
                    else break
                    j++
                }
                if (j < text.length && isLetter(text[j])) {
                    // "3rd", "2pac": a number inside a word is not a statistic.
                    while (j < text.length && isLetter(text[j])) j++
                } else {
                    out.add(trimNumber(sb.toString()))
                }
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun trimNumber(s: String): String {
        var t = s
        while (t.length > 1 && t[0] == '0' && t[1] != '.') t = t.substring(1)
        if (t.contains('.')) {
            while (t.endsWith("0")) t = t.substring(0, t.length - 1)
            if (t.endsWith(".")) t = t.substring(0, t.length - 1)
        }
        return t
    }

    /** A letter for the purpose of "a number inside a word": ASCII letters, and everything past ASCII (no Unicode tables in the core). */
    private fun isLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z') || c.code > 0x7F

    class Stat(val token: String, val start: Int, val end: Int)

    /**
     * The numbers of [text] that state something about the listener: one that is followed by a unit word ("212 plays") or by `%`.
     * Years and the numbers of a list's own items are not statistics and are left alone.
     */
    fun listenerNumbers(text: String): List<Stat> {
        val out = ArrayList<Stat>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9' && (i == 0 || !isLetter(text[i - 1]))) {
                var j = i
                val sb = StringBuilder()
                while (j < text.length) {
                    val d = text[j]
                    if (d in '0'..'9') sb.append(d)
                    else if (d == ',' && j + 1 < text.length && text[j + 1] in '0'..'9' && j > i) { /* separator */ }
                    else if (d == '.' && j + 1 < text.length && text[j + 1] in '0'..'9' && j > i) sb.append(d)
                    else break
                    j++
                }
                var k = j
                while (k < text.length && text[k] == ' ') k++
                var stat = false
                if (k < text.length && text[k] == '%') stat = true
                else if (k > j || k < text.length) {
                    var w = k
                    while (w < text.length && isLetter(text[w])) w++
                    if (w > k) {
                        val word = BrainText.fold(text.substring(k, w))
                        if (word in STAT_UNITS) stat = true
                    }
                }
                if (stat) out.add(Stat(trimNumber(sb.toString()), i, j))
                i = j
            } else {
                i++
            }
        }
        return out
    }
    // ---- The verdict ----

    class Verdict(
        /** [text] with the marks taken off, and anything that was not grounded replaced by the placeholder. */
        val cleaned: String,
        val ungroundedNames: List<String>,
        val ungroundedNumbers: List<String>,
    ) {
        val ok: Boolean get() = ungroundedNames.isEmpty() && ungroundedNumbers.isEmpty()
    }

    /**
     * Checks a final reply. [placeholder] replaces a name that was not grounded; a sentence that states a number no tool returned is
     * dropped (when [dropUngroundedNumbers]); marks around grounded names are removed. [userText] is what the user wrote in this
     * conversation (a number or a quoted phrase the user said first is theirs).
     */
    fun check(
        text: String,
        registry: Registry,
        userText: String,
        placeholder: String,
        dropUngroundedNumbers: Boolean,
    ): Verdict {
        val marked = markedSpans(text)
        val quoted = quotedSpans(text, marked)
        val all = ArrayList<Span>(marked.size + quoted.size)
        all.addAll(marked)
        all.addAll(quoted)
        all.sortBy { it.start }
        val badNames = ArrayList<String>()
        val sb = StringBuilder()
        var at = 0
        for (s in all) {
            sb.append(text, at, s.start)
            val grounded = isGrounded(s.inner, registry, s.marked, userText)
            if (grounded) {
                // A mark is only a mark: the name stays, the brackets go. A quote stays as written.
                if (s.marked) sb.append(s.inner) else sb.append(text, s.start, s.end)
            } else {
                if (s.inner !in badNames) badNames.add(s.inner)
                sb.append(placeholder)
            }
            at = s.end
        }
        sb.append(text, at, text.length)
        var cleaned = sb.toString()
        val userNumbers = HashSet<String>()
        for (n in numbersIn(userText)) userNumbers.add(n)
        val badNumbers = ArrayList<String>()
        val stats = listenerNumbers(cleaned)
        for (st in stats) {
            if (!registry.hasNumber(st.token) && st.token !in userNumbers && st.token !in badNumbers) badNumbers.add(st.token)
        }
        if (dropUngroundedNumbers && badNumbers.isNotEmpty()) cleaned = dropSentencesWith(cleaned, badNumbers)
        return Verdict(cleaned, badNames, badNumbers)
    }

    /** [text] without the sentences that state one of [tokens] as a listener number. A sentence ends at `.`, `!`, `?` or a line break. */
    fun dropSentencesWith(text: String, tokens: List<String>): String {
        val sb = StringBuilder()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                // A point between two digits ("9.5") is not the end of a sentence.
                if (c == '.' && i > 0 && i + 1 < text.length && text[i - 1] in '0'..'9' && text[i + 1] in '0'..'9') {
                    i++
                    continue
                }
                appendUnless(sb, text.substring(start, i + 1), tokens)
                start = i + 1
            }
            i++
        }
        if (start < text.length) appendUnless(sb, text.substring(start), tokens)
        return sb.toString().trim()
    }

    private fun appendUnless(sb: StringBuilder, sentence: String, tokens: List<String>) {
        for (st in listenerNumbers(sentence)) if (st.token in tokens) return
        sb.append(sentence)
    }
}
