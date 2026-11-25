package com.theveloper.pixelplay.presentation.lyrics

import com.theveloper.pixelplay.data.model.Lyrics

/**
 * Can the bundled lyrics face (`LyricsDisplayFamily`, res/font/gflex_variable.ttf) draw this
 * text? Decided once per song, never per line, so one song never mixes two typefaces: a song
 * with a single character the face lacks is drawn entirely in the system font.
 *
 * The ranges are the font's own character map (read with fontTools), minus its private-use and
 * math glyphs. It covers Latin-1, Latin Extended-A, Vietnamese and the usual typographic marks,
 * so "señorita", "Beyoncé", "Ð" or curly quotes no longer push a song onto the system font.
 * The old check knew only ASCII and a few quotes (it was written for the previous lyrics face),
 * which is why some songs, often duets with Spanish or accented lines, looked like they
 * "changed font". If the font file changes, regenerate [COVERED_RANGES].
 */
internal object LyricsFontCoverage {

    /** Inclusive `[first, last]` code point pairs, ascending. */
    private val COVERED_RANGES = intArrayOf(
        0x09, 0x0A, 0x0D, 0x0D, 0x20, 0x7E, 0xA0, 0x107, 0x10A, 0x113, 0x116, 0x11B,
        0x11E, 0x123, 0x126, 0x12B, 0x12E, 0x133, 0x136, 0x137, 0x139, 0x148, 0x14C, 0x14D,
        0x150, 0x15B, 0x15E, 0x165, 0x168, 0x17E, 0x181, 0x181, 0x18A, 0x18A, 0x18F, 0x18F,
        0x198, 0x199, 0x1A0, 0x1A1, 0x1AF, 0x1B0, 0x1B3, 0x1B4, 0x1CD, 0x1CE, 0x218, 0x21B,
        0x237, 0x237, 0x253, 0x253, 0x257, 0x257, 0x259, 0x259, 0x2BB, 0x2BC, 0x2C6, 0x2C7,
        0x2D8, 0x2DD, 0x2E3, 0x2E3, 0x300, 0x304, 0x306, 0x30C, 0x312, 0x312, 0x31B, 0x31B,
        0x323, 0x323, 0x326, 0x328, 0x335, 0x335, 0x337, 0x337, 0x3BC, 0x3BC, 0xE3F, 0xE3F,
        0x1E44, 0x1E45, 0x1E62, 0x1E63, 0x1E80, 0x1E85, 0x1E9E, 0x1E9E, 0x1EA0, 0x1EF9,
        0x2002, 0x2006, 0x2009, 0x200B, 0x2013, 0x2014, 0x2018, 0x201A, 0x201C, 0x201E,
        0x2022, 0x2022, 0x2026, 0x2026, 0x202F, 0x202F, 0x2039, 0x203A, 0x2044, 0x2044,
        0x2070, 0x2070, 0x2074, 0x2079, 0x207B, 0x207B, 0x2080, 0x2089, 0x20A9, 0x20A9,
        0x20AC, 0x20AC, 0x20B9, 0x20B9, 0x2122, 0x2122, 0x2212, 0x2212, 0x221A, 0x221A,
        0x2248, 0x2248, 0x2260, 0x2260, 0x2264, 0x2265, 0x25CC, 0x25CC,
    )

    fun isCovered(c: Char): Boolean {
        val code = c.code
        if (code in 0x20..0x7E) return true // fast path: almost every lyric character
        var lo = 0
        var hi = COVERED_RANGES.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                code < COVERED_RANGES[2 * mid] -> hi = mid - 1
                code > COVERED_RANGES[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    fun isCovered(text: CharSequence): Boolean {
        for (i in 0 until text.length) if (!isCovered(text[i])) return false
        return true
    }

    /** Every piece of text the lyrics screen may draw for this song. */
    fun isCovered(lyrics: Lyrics): Boolean {
        if (lyrics.plain?.all(::isCovered) == false) return false
        return lyrics.synced?.all { line ->
            isCovered(line.line) &&
                (line.translation?.let(::isCovered) ?: true) &&
                (line.romanization?.let(::isCovered) ?: true) &&
                (line.words?.all { isCovered(it.word) } ?: true)
        } ?: true
    }
}
