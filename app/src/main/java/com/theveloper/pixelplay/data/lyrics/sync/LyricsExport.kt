package com.theveloper.pixelplay.data.lyrics.sync

import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable

/**
 * "Share lyrics file": turns a [LyricsDoc] into files other apps understand.
 *
 * - [toEnhancedLrc]: enhanced LRC — a line tag, a `<mm:ss.xx>` tag before every syllable and a
 *   closing bare tag with the last word's end. Reads back through `LyricsUtils.parseLyrics`.
 * - [toTtml]: word-timed TTML with begin and end on every syllable, duet agents and nested
 *   background vocals.
 */
object LyricsExport {

    const val CREDIT = "PixlAudio"

    private const val TTML_NS = "http://www.w3.org/ns/ttml"
    private const val TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata"
    private const val ITUNES_NS = "http://music.apple.com/lyric-ttml-internal"

    // ── Enhanced LRC ─────────────────────────────────────────────────────────────────────────

    fun toEnhancedLrc(doc: LyricsDoc): String = buildString {
        val meta = doc.metadata
        appendHeader("ti", meta.title)
        appendHeader("ar", meta.artist)
        appendHeader("al", meta.album)
        meta.durationMs?.takeIf { it > 0 }?.let { append("[length:").append(formatLength(it)).append("]\n") }
        append("[by:").append(CREDIT).append("]\n")
        for (line in doc.lines) {
            append('[').append(lrcTime(line.startMs)).append(']')
            if (line.syllables.isEmpty()) {
                append(singleLine(line.text))
            } else {
                for (syllable in line.syllables) {
                    append('<').append(lrcTime(syllable.startMs)).append('>').append(singleLine(syllable.text))
                }
                val last = line.syllables.last()
                append('<').append(lrcTime(last.startMs + last.durationMs)).append('>')
            }
            append('\n')
        }
    }

    private fun StringBuilder.appendHeader(tag: String, value: String) {
        val clean = singleLine(value).trim()
        if (clean.isNotEmpty()) append('[').append(tag).append(':').append(clean).append("]\n")
    }

    /** `mm:ss.xx`, rounded to the nearest 10 ms; minutes grow to 3 digits when needed. */
    internal fun lrcTime(ms: Long): String {
        val centis = (ms.coerceAtLeast(0L) + 5) / 10
        val minutes = centis / 6_000
        val seconds = (centis / 100) % 60
        val hundredths = centis % 100
        return "${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(hundredths, 2)}"
    }

    private fun formatLength(ms: Long): String {
        val totalSeconds = (ms + 500) / 1_000
        return "${pad(totalSeconds / 60, 2)}:${pad(totalSeconds % 60, 2)}"
    }

    private fun singleLine(text: String): String =
        if (text.none { it == '\n' || it == '\r' }) text else text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')

    // ── TTML ─────────────────────────────────────────────────────────────────────────────────

    private class Paragraph(val line: TimedLine, val agent: String) {
        val background = ArrayList<TimedLine>(1)
        val beginMs: Long get() = minOf(line.startMs, background.minOfOrNull { it.startMs } ?: line.startMs)
        val endMs: Long get() = maxOf(line.endMs, background.maxOfOrNull { it.endMs } ?: line.endMs)
    }

    fun toTtml(doc: LyricsDoc): String {
        val roleOf = doc.voices.associate { it.id to it.role }
        val paragraphs = ArrayList<Paragraph>(doc.lines.size)
        var lastMain: Paragraph? = null
        for (line in doc.lines) {
            when (roleOf[line.voiceId] ?: "lead") {
                "background" -> {
                    val host = lastMain
                    if (host != null) {
                        host.background += line
                    } else {
                        paragraphs += Paragraph(line, "v1").also { it.background += line }
                    }
                }
                "duet" -> Paragraph(line, "v2").also { paragraphs += it; lastMain = it }
                else -> Paragraph(line, "v1").also { paragraphs += it; lastMain = it }
            }
        }
        val agents = paragraphs.map { it.agent }.distinct().sorted()
        val wordTimed = doc.lines.any { it.syllables.isNotEmpty() }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<tt xmlns=\"").append(TTML_NS)
                .append("\" xmlns:ttm=\"").append(TTML_METADATA_NS)
                .append("\" xmlns:itunes=\"").append(ITUNES_NS)
                .append("\" itunes:timing=\"").append(if (wordTimed) "Word" else "Line").append("\">\n")
            append("  <head>\n    <metadata>\n")
            for (agent in agents) {
                append("      <ttm:agent type=\"person\" xml:id=\"").append(agent).append("\"/>\n")
            }
            append("    </metadata>\n  </head>\n")
            append("  <body")
            doc.metadata.durationMs?.takeIf { it > 0 }?.let { append(" dur=\"").append(ttmlTime(it)).append('"') }
            append(">\n")
            if (paragraphs.isNotEmpty()) {
                append("    <div begin=\"").append(ttmlTime(paragraphs.minOf { it.beginMs }))
                    .append("\" end=\"").append(ttmlTime(paragraphs.maxOf { it.endMs })).append("\">\n")
                for (paragraph in paragraphs) {
                    append("      ")
                    appendParagraph(paragraph)
                    append('\n')
                }
                append("    </div>\n")
            }
            append("  </body>\n</tt>\n")
        }
    }

    /** One `<p>` on a single line: whitespace inside it is significant. */
    private fun StringBuilder.appendParagraph(paragraph: Paragraph) {
        append("<p begin=\"").append(ttmlTime(paragraph.beginMs))
            .append("\" end=\"").append(ttmlTime(paragraph.endMs))
            .append("\" ttm:agent=\"").append(paragraph.agent).append("\">")
        val backgroundOnly = paragraph.background.singleOrNull() === paragraph.line
        if (!backgroundOnly) appendLineContent(paragraph.line)
        for ((index, background) in paragraph.background.withIndex()) {
            if (!backgroundOnly || index > 0) append(' ')
            append("<span ttm:role=\"x-bg\" begin=\"").append(ttmlTime(background.startMs))
                .append("\" end=\"").append(ttmlTime(background.endMs)).append("\">")
            appendLineContent(background)
            append("</span>")
        }
        append("</p>")
    }

    private fun StringBuilder.appendLineContent(line: TimedLine) {
        if (line.syllables.isEmpty()) {
            appendEscaped(line.text.trim())
            return
        }
        line.syllables.forEachIndexed { index, syllable -> appendSyllable(syllable, index == line.syllables.lastIndex) }
    }

    private fun StringBuilder.appendSyllable(syllable: TimedSyllable, last: Boolean) {
        val core = syllable.text.trim()
        if (core.isNotEmpty()) {
            append("<span begin=\"").append(ttmlTime(syllable.startMs))
                .append("\" end=\"").append(ttmlTime(syllable.startMs + syllable.durationMs)).append("\">")
            appendEscaped(core)
            append("</span>")
        }
        // A trailing space ends a word; syllables of the same word sit flush against each other.
        if (!last && syllable.text.lastOrNull()?.isWhitespace() == true) append(' ')
    }

    /** `h:mm:ss.fff`. */
    internal fun ttmlTime(ms: Long): String {
        val value = ms.coerceAtLeast(0L)
        val hours = value / 3_600_000
        val minutes = (value / 60_000) % 60
        val seconds = (value / 1_000) % 60
        val millis = value % 1_000
        return "$hours:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(millis, 3)}"
    }

    /** XML-escapes text and drops code points XML 1.0 cannot carry (control chars, lone surrogates). */
    private fun StringBuilder.appendEscaped(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> append("&amp;")
                cp == '<'.code -> append("&lt;")
                cp == '>'.code -> append("&gt;")
                cp == '"'.code -> append("&quot;")
                cp == '\''.code -> append("&apos;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> append(' ')
                cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF -> appendCodePoint(cp)
                else -> Unit
            }
        }
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
}
