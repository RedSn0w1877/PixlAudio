package com.theveloper.pixelplay.data.text

import java.text.Normalizer
import java.util.Locale

/**
 * Case- and diacritic-insensitive text used for matching names and for the in-page song search:
 * "Beyoncé" and "BEYONCE" fold to the same string. Pure and allocation-light enough to run once per
 * song on a background thread (never per keystroke per row on the main thread).
 */
object FoldedText {
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")

    fun fold(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val lowered = value.lowercase(Locale.ROOT)
        // Plain ASCII needs no normalisation pass.
        var ascii = true
        for (ch in lowered) if (ch.code > 127) { ascii = false; break }
        val base = if (ascii) lowered else COMBINING_MARKS.replace(Normalizer.normalize(lowered, Normalizer.Form.NFD), "")
        return base.trim().replace(WHITESPACE, " ")
    }
}
