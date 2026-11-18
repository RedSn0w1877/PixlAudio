package com.theveloper.pixelplay.brain

/**
 * Text folding and title cleaning (docs/brain-spec.md, 10.5).
 *
 * No regular expression, no locale API, no `String` comparison by order: text is handled as Unicode
 * scalar values on the way in and as folded strings (or their UTF-8 hash) afterwards. The tables are
 * compiled in ([FoldTable], [TitleRules]); nothing is opened at run time.
 */
object BrainText {

    /** Bits of `versionFlags`, in the order of the spec. */
    const val FLAG_LIVE = 1
    const val FLAG_REMIX = 1 shl 1
    const val FLAG_ACOUSTIC = 1 shl 2
    const val FLAG_INSTRUMENTAL = 1 shl 3
    const val FLAG_REMASTER = 1 shl 4
    const val FLAG_COVER = 1 shl 5
    const val FLAG_KARAOKE = 1 shl 6
    const val FLAG_SPED_UP = 1 shl 7
    const val FLAG_SLOWED = 1 shl 8
    const val FLAG_RE_RECORDED = 1 shl 9

    /** A cleaned title: the folded title and the version flags its dropped segments set. */
    class CleanTitle(val title: String, val versionFlags: Int) {
        /** The flags that make two titles different recordings: all of them except "remaster" (3.1). */
        val identityFlags: Int get() = versionFlags and FLAG_REMASTER.inv()

        override fun toString(): String = "CleanTitle($title, $versionFlags)"
    }

    private const val VALUE_SEPARATOR = '\u0001'

    /** Start offset of each row's replacement inside [FoldTable.VALUES]; one extra entry closes the last row. */
    private val valueOffsets: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val values = FoldTable.VALUES
        val offsets = IntArray(FoldTable.ROWS + 1)
        var row = 0
        var i = 0
        offsets[0] = 0
        while (i < values.length) {
            if (values[i] == VALUE_SEPARATOR) {
                row++
                offsets[row] = i + 1
            }
            i++
        }
        offsets[FoldTable.ROWS] = values.length + 1
        offsets
    }

    /** Index of [unit] in [FoldTable.KEYS], or -1. Binary search over UTF-16 units compared as numbers. */
    private fun rowOf(unit: Int): Int {
        val keys = FoldTable.KEYS
        var lo = 0
        var hi = keys.length - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = keys[mid].code
            if (k < unit) lo = mid + 1 else if (k > unit) hi = mid - 1 else return mid
        }
        return -1
    }

    /**
     * Folds [s] for comparison: ASCII lower-cased, diacritics removed (Vietnamese included, composed or
     * decomposed), punctuation to a space, runs of spaces collapsed, trimmed. Scripts without a rule
     * (Hangul, kana, CJK, Thai, ...) pass through unchanged.
     */
    fun fold(s: String): String {
        val out = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            val code = c.code
            if (code < 0x80) {
                i++
                val mapped = when {
                    code >= 'a'.code && code <= 'z'.code -> code
                    code >= '0'.code && code <= '9'.code -> code
                    code >= 'A'.code && code <= 'Z'.code -> code + 32
                    else -> -1
                }
                if (mapped < 0) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(mapped.toChar())
                }
                continue
            }
            if (code in 0xD800..0xDFFF) {
                val isPair = code <= 0xDBFF && i + 1 < n && s[i + 1].code in 0xDC00..0xDFFF
                if (isPair) {
                    // A supplementary scalar: no rule, unchanged.
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(c).append(s[i + 1])
                    i += 2
                } else {
                    // A lone surrogate: a space.
                    pendingSpace = true
                    i++
                }
                continue
            }
            i++
            val row = rowOf(code)
            if (row < 0) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(c)
                continue
            }
            val offsets = valueOffsets
            val from = offsets[row]
            val to = offsets[row + 1] - 1
            var k = from
            while (k < to) {
                val r = FoldTable.VALUES[k]
                if (r == ' ') {
                    pendingSpace = true
                } else {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(r)
                }
                k++
            }
        }
        return out.toString()
    }

    private fun isOpener(c: Char): Boolean = c == '(' || c == '[' || c == '（' || c == '［'

    private fun isCloser(c: Char): Boolean = c == ')' || c == ']' || c == '）' || c == '］'

    private fun isDash(c: Char): Boolean = c == '-' || c == '–' || c == '—'

    /** Index of the closer that ends the group opened at [open], counting nested groups; -1 if it never closes. */
    private fun matchingCloser(raw: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < raw.length) {
            val c = raw[i]
            if (isOpener(c)) depth++ else if (isCloser(c)) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** True when [tokens] contains the tokens of [sequence] in a row. Both are single-space separated. */
    private fun containsTokens(tokens: List<String>, sequence: List<String>): Boolean {
        if (sequence.isEmpty() || sequence.size > tokens.size) return false
        var start = 0
        while (start + sequence.size <= tokens.size) {
            var k = 0
            while (k < sequence.size && tokens[start + k] == sequence[k]) k++
            if (k == sequence.size) return true
            start++
        }
        return false
    }

    private fun startsWithTokens(tokens: List<String>, sequence: List<String>): Boolean {
        if (sequence.isEmpty() || sequence.size > tokens.size) return false
        var k = 0
        while (k < sequence.size && tokens[k] == sequence[k]) k++
        return k == sequence.size
    }

    private fun splitTokens(folded: String): List<String> {
        if (folded.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(6)
        var start = 0
        var i = 0
        while (i <= folded.length) {
            if (i == folded.length || folded[i] == ' ') {
                if (i > start) tokens.add(folded.substring(start, i))
                start = i + 1
            }
            i++
        }
        return tokens
    }

    private val flagSequences: Array<List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Array(TitleRules.FLAG_TOKENS.size) { splitTokens(TitleRules.FLAG_TOKENS[it]) }
    }
    private val neutralPrefixes: Array<List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Array(TitleRules.NEUTRAL_PREFIX.size) { splitTokens(TitleRules.NEUTRAL_PREFIX[it]) }
    }
    private val neutralEquals: Array<List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Array(TitleRules.NEUTRAL_EQUALS.size) { splitTokens(TitleRules.NEUTRAL_EQUALS[it]) }
    }

    /**
     * The verdict on one segment: a non-negative value is the flag bits to set (the segment is dropped),
     * [SEGMENT_NEUTRAL] drops it without a flag, [SEGMENT_KEEP] leaves it in the title.
     */
    private const val SEGMENT_NEUTRAL = 0
    private const val SEGMENT_KEEP = -1

    private fun classify(segment: String): Int {
        val tokens = splitTokens(fold(segment))
        if (tokens.isEmpty()) return SEGMENT_NEUTRAL
        var flags = 0
        val sequences = flagSequences
        for (r in sequences.indices) {
            if (containsTokens(tokens, sequences[r])) flags = flags or (1 shl TitleRules.FLAG_BITS[r])
        }
        if (flags != 0) return flags
        for (prefix in neutralPrefixes) if (startsWithTokens(tokens, prefix)) return SEGMENT_NEUTRAL
        for (whole in neutralEquals) if (whole.size == tokens.size && startsWithTokens(tokens, whole)) return SEGMENT_NEUTRAL
        return SEGMENT_KEEP
    }

    /**
     * Splits a raw title into the song's own name and its version flags.
     *
     * Segments are each `( ... )` and `[ ... ]` group (also full-width) and the text after the last
     * `" - "` (hyphen, en dash or em dash, a space on both sides). A segment that names a version
     * ("Live", "Remastered 2011", "Taylor's Version") is dropped and sets its flag; one that is only
     * packaging ("feat. X", "Radio Edit") is dropped; anything else stays ("(Part 2)").
     */
    fun cleanTitle(raw: String): CleanTitle {
        val head = StringBuilder(raw.length)
        val kept = StringBuilder()
        var flags = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (isOpener(c)) {
                val close = matchingCloser(raw, i)
                if (close > i) {
                    val verdict = classify(raw.substring(i + 1, close))
                    if (verdict == SEGMENT_KEEP) {
                        kept.append(' ').append(raw, i + 1, close)
                    } else {
                        flags = flags or verdict
                    }
                    head.append(' ')
                    i = close + 1
                    continue
                }
            }
            head.append(c)
            i++
        }

        // The text after the last " - " of what is left.
        var cut = -1
        var k = head.length - 2
        while (k >= 1) {
            if (isDash(head[k]) && head[k - 1] == ' ' && head[k + 1] == ' ') {
                cut = k
                break
            }
            k--
        }
        var headText = head.toString()
        if (cut >= 0) {
            val tail = headText.substring(cut + 1)
            val verdict = classify(tail)
            if (verdict != SEGMENT_KEEP) {
                flags = flags or verdict
                headText = headText.substring(0, cut)
            }
        }

        var title = fold(headText + kept)
        if (title.isEmpty()) title = fold(raw)
        return CleanTitle(title, flags)
    }
}
