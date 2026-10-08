package com.theveloper.pixelplay.data.ai.local

/**
 * Token arithmetic for the on-device models, without a tokenizer. Pure.
 *
 * Gemini Nano's Prompt API wants input "under 4000 tokens" and LiteRT-LM's Gemma engine is
 * built with a 4,096-token window (input + output), so every on-device prompt is sized against
 * [MAX_INPUT_TOKENS] / [MAX_OUTPUT_TOKENS] with this estimator rather than the cloud prompts'
 * 4k-32k-character digests.
 *
 * The estimate is deliberately pessimistic for scripts that tokenize badly: CJK, kana, Hangul,
 * Thai and Vietnamese letters with diacritics cost about one token each, everything else about
 * one token per [LATIN_CHARS_PER_TOKEN] characters.
 */
object TokenBudget {
    /** Nano's documented ceiling is 4,000; leave room for the instruction and the chat template. */
    const val MAX_INPUT_TOKENS = 3_000
    const val MAX_OUTPUT_TOKENS = 1_024
    /** ML Kit recommends system instructions under 150 words. */
    const val MAX_INSTRUCTION_WORDS = 150

    private const val LATIN_CHARS_PER_TOKEN = 3.2

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var dense = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isDense(cp)) dense++ else other++
            i += Character.charCount(cp)
        }
        return dense + kotlin.math.ceil(other / LATIN_CHARS_PER_TOKEN).toInt()
    }

    fun wordCount(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    /**
     * The longest prefix of [items] whose rendered text (joined by [separator]) stays within
     * [limitTokens]. At least one item is kept when the first one alone is over the limit, so a
     * caller always has something to send (and the engine's own TOO_LONG answers the rest).
     */
    fun <T> shrinkToFit(
        items: List<T>,
        limitTokens: Int,
        separator: String = "\n",
        render: (T) -> String,
    ): List<T> {
        if (items.isEmpty()) return items
        val separatorCost = estimate(separator)
        var used = 0
        var count = 0
        for (item in items) {
            val cost = estimate(render(item)) + if (count > 0) separatorCost else 0
            if (count > 0 && used + cost > limitTokens) break
            used += cost
            count++
            if (used > limitTokens) break
        }
        return items.take(count.coerceAtLeast(1))
    }

    /** [text] cut (at a word boundary when possible) so its estimate stays within [limitTokens]. */
    fun clamp(text: String, limitTokens: Int): String {
        if (estimate(text) <= limitTokens) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (estimate(text.substring(0, mid)) <= limitTokens) low = mid else high = mid - 1
        }
        val cut = text.substring(0, low)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > low / 2) cut.substring(0, lastSpace) else cut
    }

    private fun isDense(cp: Int): Boolean {
        val block = Character.UnicodeBlock.of(cp) ?: return false
        if (block in DENSE_BLOCKS) return true
        // Vietnamese: the precomposed letters with diacritics live in Latin Extended Additional
        // (ạ ả ấ ầ ẩ … ỹ) and the combining marks below; plain ASCII Vietnamese stays "other".
        return block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL ||
            block == Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS ||
            cp in VIETNAMESE_EXTRA
    }

    private val WHITESPACE = Regex("\\s+")

    private val DENSE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        Character.UnicodeBlock.THAI,
        Character.UnicodeBlock.BOPOMOFO,
    )

    // Vietnamese letters outside Latin Extended Additional: ă â đ ê ô ơ ư (and capitals).
    private val VIETNAMESE_EXTRA = setOf(
        0x0102, 0x0103, 0x00C2, 0x00E2, 0x0110, 0x0111, 0x00CA, 0x00EA,
        0x00D4, 0x00F4, 0x01A0, 0x01A1, 0x01AF, 0x01B0,
    )
}
