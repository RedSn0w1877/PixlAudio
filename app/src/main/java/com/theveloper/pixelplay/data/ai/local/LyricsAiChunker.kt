package com.theveloper.pixelplay.data.ai.local

/**
 * "Translate via AI" on the on-device route, without asking a small model to echo a whole LRC.
 *
 * The cloud prompt sends every line with its `[mm:ss.xx]` stamp and wants each one back twice
 * (original + translation) — output twice the input, and timestamps a small model mangles. Here
 * the app keeps the stamps: each distinct text line is translated once, in numbered chunks that
 * fit the model ([TokenBudget]; CJK and Vietnamese lyrics get fewer lines per chunk), and the
 * stamps are put back by the app ([rebuild]), producing exactly the two-lines-per-timestamp LRC
 * LyricsStateHolder stores for the cloud path. Pure.
 */
object LyricsAiChunker {
    const val ALREADY_IN_TARGET = "ALREADY_IN_TARGET_LANGUAGE"
    private const val MAX_LINES_PER_CHUNK = 40

    /** One line of the source: [stamps] is the leading `[..]` timestamps (null for tags/blank lines). */
    data class LrcLine(val raw: String, val stamps: String?, val text: String)

    fun parse(lyrics: String): List<LrcLine> = lyrics.lines().map { raw ->
        val match = STAMPED_LINE.find(raw)
        if (match == null) {
            LrcLine(raw, null, raw.trim())
        } else {
            val stamps = match.groupValues[1].trim()
            val text = match.groupValues[2].replace(WORD_TAG, "").replace(SPACES, " ").trim()
            LrcLine(raw, stamps, text)
        }
    }

    /** The distinct text lines worth translating, in first-seen order. */
    fun translatableTexts(lines: List<LrcLine>): List<String> =
        lines.asSequence()
            .filter { it.stamps != null && it.text.isNotEmpty() && it.text.any(Char::isLetter) }
            .map { it.text }
            .distinct()
            .toList()

    /** Groups [texts] so each chunk's numbered prompt stays within [budgetTokens]. */
    fun chunk(texts: List<String>, budgetTokens: Int = OnDevicePrompts.TRANSLATION_CHUNK_TOKENS): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0
        for (text in texts) {
            val cost = TokenBudget.estimate("${current.size + 1}. $text\n")
            if (current.isNotEmpty() && (used + cost > budgetTokens || current.size >= MAX_LINES_PER_CHUNK)) {
                chunks += current
                current = mutableListOf()
                used = 0
            }
            current += text
            used += cost
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    fun prompt(chunk: List<String>): String =
        chunk.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")

    /**
     * The translations in [reply] by chunk position, or null when the model says the lyrics are
     * already in the target language. Numbers it skipped simply have no entry (those lines keep
     * only the original); text that just repeats the original isn't a translation.
     */
    fun parseReply(reply: String, chunk: List<String>): Map<Int, String>? {
        if (reply.contains(ALREADY_IN_TARGET)) return null
        val result = HashMap<Int, String>()
        for (line in reply.lines()) {
            val match = NUMBERED.find(line) ?: continue
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: continue
            if (index !in chunk.indices || result.containsKey(index)) continue
            val translation = match.groupValues[2]
                .replace(STAMP_ANYWHERE, "")
                .trim()
                .trim('"', '“', '”')
                .trim()
            if (translation.isEmpty()) continue
            if (translation.equals(chunk[index], ignoreCase = true)) continue
            result[index] = translation
        }
        return result
    }

    /** The LRC with each translated line followed by its translation under the same stamps. */
    fun rebuild(lines: List<LrcLine>, translations: Map<String, String>): String = buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            append(line.raw)
            val stamps = line.stamps ?: return@forEachIndexed
            val translation = translations[line.text] ?: return@forEachIndexed
            append('\n').append(stamps).append(translation)
        }
    }

    // [00:12.34] / [00:12:34] / [1:02.5], repeated for lines sung more than once.
    private val STAMPED_LINE = Regex("^\\s*((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?])+)(.*)$")
    private val STAMP_ANYWHERE = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]")
    // Enhanced-LRC word stamps: <00:12.34>
    private val WORD_TAG = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val NUMBERED = Regex("^\\s*(\\d{1,3})\\s*[.):：-]\\s*(.*)$")
    private val SPACES = Regex("\\s+")
}
