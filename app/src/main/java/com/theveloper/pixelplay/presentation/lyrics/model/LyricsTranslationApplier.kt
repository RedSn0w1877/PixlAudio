package com.theveloper.pixelplay.presentation.lyrics.model

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine

/** What the lyrics screen shows: the karaoke (synced) view, the plain list, or the empty state. */
enum class LyricsDisplay { SYNCED, PLAIN, NONE }

/**
 * Synced vs plain is picked automatically: synced lines when there are any, else plain text.
 * [plainOverride] is the More sheet's "Show as plain text" switch; with it on, synced-only lyrics
 * still show as plain text (their line texts, see [plainLinesFor]).
 */
fun resolveLyricsDisplay(lyrics: Lyrics?, plainOverride: Boolean): LyricsDisplay {
    if (lyrics == null) return LyricsDisplay.NONE
    val hasSynced = !lyrics.synced.isNullOrEmpty()
    return when {
        hasSynced && !plainOverride -> LyricsDisplay.SYNCED
        !lyrics.plain.isNullOrEmpty() -> LyricsDisplay.PLAIN
        hasSynced -> LyricsDisplay.PLAIN
        else -> LyricsDisplay.NONE
    }
}

/** The lines of the plain view: the plain text, or the synced line texts when there is none. */
fun plainLinesFor(lyrics: Lyrics): List<String> =
    lyrics.plain?.takeIf { it.isNotEmpty() } ?: lyrics.synced.orEmpty().map { it.line }

/**
 * The synced lines that still need a translation, as source line → the text to translate.
 * Lines that already carry one (an AI translation, a dual-language LRC) are left alone, and so are
 * blank lines. Keys are the exact [com.theveloper.pixelplay.data.model.SyncedLine.line] texts that
 * [withTranslations] matches on.
 */
fun Lyrics.untranslatedLines(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    synced.orEmpty().forEach { line ->
        if (!line.translation.isNullOrBlank()) return@forEach
        val text = sanitizeLyricLineText(line.line).trim()
        if (text.isNotEmpty()) out.putIfAbsent(line.line, text)
    }
    return out
}

/**
 * These lyrics with [byLine] (source line text → translation) set as each matching synced line's
 * translation. Lines that already have a translation keep it; nothing else changes.
 *
 * `plain` is rebuilt from the synced lines exactly as LyricsUtils builds it (line, then
 * romanization, then translation, newline-separated), so "Show as plain text" shows the
 * translations too. `document` is kept: on the LyricsDoc path PreparedLyricsBuilder takes each
 * line's translation from the synced line with the same start time.
 *
 * Returns this same instance when nothing matched, so callers can tell nothing changed.
 */
fun Lyrics.withTranslations(byLine: Map<String, String>): Lyrics {
    val lines = synced
    if (lines.isNullOrEmpty() || byLine.isEmpty()) return this
    var changed = false
    val updated = lines.map { line ->
        val translation = byLine[line.line]
        if (translation.isNullOrBlank() || !line.translation.isNullOrBlank()) {
            line
        } else {
            changed = true
            line.copy(translation = translation)
        }
    }
    if (!changed) return this
    return copy(synced = updated, plain = plainFromSynced(updated))
}

/**
 * The inverse of [withTranslations]: these lyrics with the translations that came from [byLine]
 * taken off again (a line whose translation is anything else keeps it). Paths that save the lyrics
 * or translate them another way start from this, so on-device translations, which live only in
 * memory, are never written into the user's lyrics and never pass for a translation they already
 * have.
 *
 * Returns this same instance when no line carried one of [byLine]'s translations.
 */
fun Lyrics.withoutTranslationsFrom(byLine: Map<String, String>): Lyrics {
    val lines = synced
    if (lines.isNullOrEmpty() || byLine.isEmpty()) return this
    var changed = false
    val updated = lines.map { line ->
        val translation = line.translation
        if (translation != null && translation == byLine[line.line]) {
            changed = true
            line.copy(translation = null)
        } else {
            line
        }
    }
    if (!changed) return this
    return copy(synced = updated, plain = plainFromSynced(updated))
}

/** Plain lines built from synced ones exactly as LyricsUtils builds them: line, romanization, translation. */
private fun plainFromSynced(lines: List<SyncedLine>): List<String> =
    lines.map { line ->
        buildString {
            append(line.line)
            if (!line.romanization.isNullOrEmpty()) append("\n").append(line.romanization)
            if (!line.translation.isNullOrEmpty()) append("\n").append(line.translation)
        }
    }

/** True when [byLine] would add a translation to at least one of these lyrics' synced lines. */
fun Lyrics.lacksTranslationsFrom(byLine: Map<String, String>): Boolean =
    synced.orEmpty().any { line -> line.translation.isNullOrBlank() && !byLine[line.line].isNullOrBlank() }
