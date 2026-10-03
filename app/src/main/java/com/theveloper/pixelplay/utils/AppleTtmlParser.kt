package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import java.math.BigDecimal
import java.math.RoundingMode
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parses Apple Music-shaped TTML (as served by BiniLyrics) straight into a [LyricsDoc], keeping
 * what the enhanced-LRC route of [TtmlLyricsParser] flattens away:
 *
 * - word timing, with syllables merged into words: spans with no whitespace between them
 *   (`<span>e</span><span>nough</span>`) are one word, a literal space ends the word;
 * - background vocals (`<span ttm:role="x-bg">`) as separate `background` lines, which the
 *   karaoke view groups with the line they overlap;
 * - duet agents: the first `person` agent (and any `group`/`other` agent) is the lead, every
 *   other person is a `duet` voice, drawn opposite;
 * - line-timed files (`<p>` without timed spans) and unsynced files (no timing at all);
 * - songwriter credits, returned alongside (the lyrics model has no credits slot yet).
 *
 * Translations, transliterations and `<audio lyricOffset>` (a spatial-audio master offset)
 * are ignored. Untrusted input: parsed with [SecureXml], bounded in size and line count.
 */
internal object AppleTtmlParser {

    const val MAX_TTML_CHARS = 4 * 1024 * 1024
    private const val MAX_PARAGRAPHS = 5_000
    private const val MAX_TIME_MS = 86_400_000L
    /** End given to a line-timed line whose `end` is missing and that has no successor. */
    private const val FALLBACK_LINE_MS = 5_000L

    private const val LEAD = "lead"
    private const val DUET = "duet"
    private const val BACKGROUND = "background"

    data class Result(
        /** Timed lyrics; `null` when the file carries no usable timing (then see [plain]). */
        val document: LyricsDoc?,
        /** Every lyric line as plain text, in order (background vocals included). */
        val plain: List<String>,
        val songwriters: List<String>,
        /** True when at least one line has word (syllable) timing. */
        val wordTimed: Boolean,
    ) {
        fun toLyrics(): Lyrics = document?.toLyrics() ?: Lyrics(plain = plain)
    }

    fun parse(ttml: String, metadata: LyricsMetadata = LyricsMetadata()): Result? = try {
        parseInternal(ttml, metadata)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun parseInternal(raw: String, metadata: LyricsMetadata): Result? {
        if (raw.length > MAX_TTML_CHARS) return null
        val text = raw.trimStart { it.isWhitespace() || it == '﻿' }
        val document = SecureXml.parse(text) ?: return null
        val root = document.documentElement ?: return null
        if (localName(root) != "tt") return null

        val voiceOfAgent = agentVoices(root)
        val songwriters = descendants(root, "songwriter")
            .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(50)
        val body = descendants(root, "body").firstOrNull() ?: return null
        val paragraphs = descendants(body, "p")
        if (paragraphs.isEmpty() || paragraphs.size > MAX_PARAGRAPHS) return null

        val drafts = ArrayList<DraftLine>(paragraphs.size * 2)
        val plain = ArrayList<String>(paragraphs.size)
        for (p in paragraphs) {
            val voice = voiceOfAgent(inheritedAttr(p, "agent"))
            val pBegin = parseTime(attr(p, "begin"))
            val pEnd = parseTime(attr(p, "end"))
            val main = Collector()
            val backgrounds = ArrayList<Collector>(1)
            collect(p, main, backgrounds)

            main.plainText().takeIf { it.isNotEmpty() }?.let(plain::add)
            backgrounds.forEach { bg -> bg.plainText().takeIf { it.isNotEmpty() }?.let(plain::add) }

            main.toDraft(voice, pBegin, pEnd)?.let(drafts::add)
            backgrounds.forEach { bg ->
                // Timed background words carry their own span; an untimed one rides its line.
                bg.toDraft(BACKGROUND, bg.begin, bg.end, fallbackBegin = pBegin, fallbackEnd = pEnd)
                    ?.let(drafts::add)
            }
        }
        if (plain.isEmpty()) return null

        val timed = drafts.sortedBy { it.startMs } // stable: document order on equal starts
        if (timed.isEmpty()) return Result(null, plain, songwriters, wordTimed = false)
        val lines = timed.mapIndexed { i, d ->
            val end = d.endMs ?: run {
                // Line-timed paragraphs may omit `end`: run to the next lead/duet line.
                val next = timed.subList(i + 1, timed.size)
                    .firstOrNull { it.voice != BACKGROUND && it.startMs > d.startMs }?.startMs
                next ?: (d.startMs + FALLBACK_LINE_MS)
            }
            TimedLine(d.startMs, end.coerceIn(d.startMs + 1, MAX_TIME_MS), d.text, d.voice, d.syllables)
        }
        val used = lines.map { it.voiceId }.toSet()
        val voices = listOf(LEAD, DUET, BACKGROUND).filter { it in used }.map { Voice(id = it, role = it) }
        val doc = LyricsDoc(metadata = metadata.copy(durationMs = null), voices = voices, lines = lines)
        if (!LyricsDocCodec.isValid(doc)) return null
        return Result(doc, plain, songwriters, wordTimed = lines.any { it.syllables.isNotEmpty() })
    }

    // ── Agents ───────────────────────────────────────────────────────────────────────────────

    private fun agentVoices(root: Element): (String?) -> String {
        val agents = descendants(root, "agent").mapNotNull { agent ->
            val id = attr(agent, "id") ?: return@mapNotNull null
            id to (attr(agent, "type")?.lowercase() ?: "person")
        }
        val firstPerson = agents.firstOrNull { it.second == "person" }?.first
        val roles = agents.associate { (id, type) ->
            id to if (type == "person" && id != firstPerson) DUET else LEAD
        }
        return { id -> id?.let { roles[it] } ?: LEAD }
    }

    // ── Paragraph walking ───────────────────────────────────────────────────────────────────

    private sealed interface Piece {
        data class Gap(val text: String) : Piece
        data class Timed(val startMs: Long, val endMs: Long?, val text: String) : Piece
    }

    private class Collector {
        val pieces = ArrayList<Piece>()
        var begin: Long? = null
        var end: Long? = null

        fun plainText(): String = pieces.joinToString("") {
            when (it) {
                is Piece.Gap -> it.text
                is Piece.Timed -> it.text
            }
        }.replace(WHITESPACE, " ").trim()

        fun toDraft(
            voice: String,
            lineBegin: Long?,
            lineEnd: Long?,
            fallbackBegin: Long? = null,
            fallbackEnd: Long? = null,
        ): DraftLine? {
            val text = plainText()
            if (text.isEmpty()) return null
            val syllables = syllables()
            if (syllables.isEmpty()) {
                val start = lineBegin ?: fallbackBegin ?: return null
                val end = (lineEnd ?: fallbackEnd)?.takeIf { it > start }
                return DraftLine(start, end, text, voice, emptyList())
            }
            val first = syllables.first().startMs
            val lastEnd = syllables.maxOf { it.startMs + it.durationMs }
            val start = minOf(lineBegin ?: first, first)
            val end = maxOf(lineEnd ?: lastEnd, lastEnd)
            return DraftLine(start, end, syllables.joinToString("") { it.text }, voice, syllables)
        }

        /**
         * Words are separated by whitespace (between or inside spans); adjacent spans with no
         * whitespace are syllables of one word. Untimed non-space text (stray punctuation)
         * joins the neighbouring syllable so no lyric text is lost.
         */
        private fun syllables(): List<TimedSyllable> {
            class Word(val startMs: Long, val endMs: Long?, var text: String, var spaceAfter: Boolean = false)
            val words = ArrayList<Word>()
            var pendingPrefix = ""
            for (piece in pieces) {
                when (piece) {
                    is Piece.Gap -> {
                        val gap = piece.text
                        val core = gap.trim()
                        if (core.isEmpty()) {
                            if (gap.isNotEmpty()) words.lastOrNull()?.spaceAfter = true
                        } else {
                            val last = words.lastOrNull()
                            if (last != null) {
                                if (gap.first().isWhitespace()) last.text += " "
                                last.text += core.replace(WHITESPACE, " ")
                                if (gap.last().isWhitespace()) last.spaceAfter = true
                            } else {
                                pendingPrefix += core + if (gap.last().isWhitespace()) " " else ""
                            }
                        }
                    }
                    is Piece.Timed -> {
                        val rawText = piece.text
                        val core = rawText.trim().replace(WHITESPACE, " ")
                        if (rawText.firstOrNull()?.isWhitespace() == true) words.lastOrNull()?.spaceAfter = true
                        if (core.isEmpty()) continue
                        words += Word(piece.startMs, piece.endMs, pendingPrefix + core,
                            spaceAfter = rawText.last().isWhitespace())
                        pendingPrefix = ""
                    }
                }
            }
            if (words.isEmpty()) return emptyList()
            val out = ArrayList<TimedSyllable>(words.size)
            var previousStart = 0L
            words.forEachIndexed { i, w ->
                val start = maxOf(w.startMs, previousStart)
                val nextStart = words.getOrNull(i + 1)?.startMs
                val end = (w.endMs ?: nextStart ?: (start + 1)).coerceAtLeast(start + 1)
                val text = if (w.spaceAfter && i < words.lastIndex) w.text + " " else w.text
                out += TimedSyllable(start, end - start, text)
                previousStart = start
            }
            return out
        }
    }

    private class DraftLine(
        val startMs: Long,
        val endMs: Long?,
        val text: String,
        val voice: String,
        val syllables: List<TimedSyllable>,
    )

    private fun collect(node: Node, target: Collector, backgrounds: MutableList<Collector>) {
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    child.nodeValue?.let { target.pieces += Piece.Gap(cleanText(it)) }
                Node.ELEMENT_NODE -> {
                    val element = child as Element
                    when (localName(element)) {
                        "br" -> target.pieces += Piece.Gap(" ")
                        "span" -> collectSpan(element, target, backgrounds)
                        else -> collect(element, target, backgrounds)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun collectSpan(span: Element, target: Collector, backgrounds: MutableList<Collector>) {
        when (attr(span, "role")?.lowercase()) {
            "x-bg" -> {
                val bg = Collector().apply {
                    begin = parseTime(attr(span, "begin"))
                    end = parseTime(attr(span, "end"))
                }
                backgrounds += bg
                // A background span sits inside the lead line; nested background vocals stay in it.
                collect(span, bg, backgrounds)
                return
            }
            "x-translation", "x-roman", "x-transliteration", "x-pronunciation" -> return
        }
        val begin = parseTime(attr(span, "begin"))
        val hasElementChildren = generateSequence(span.firstChild) { it.nextSibling }
            .any { it.nodeType == Node.ELEMENT_NODE }
        if (begin != null && !hasElementChildren) {
            target.pieces += Piece.Timed(begin, parseTime(attr(span, "end")), cleanText(span.textContent.orEmpty()))
        } else {
            collect(span, target, backgrounds)
        }
    }

    // ── Time expressions ─────────────────────────────────────────────────────────────────────

    private val OFFSET_TIME = Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms)?$""")
    private val CLOCK_TIME = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2}(?:\.\d+)?)$""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * TTML times as Apple and BiniLyrics write them: `27.395`, `27.395s`, `1500ms`, `3:21.570`
     * (m:ss.mmm) and `1:02:03.456` (h:mm:ss.mmm). Frame/tick forms are not used and give `null`.
     */
    internal fun parseTime(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ms = OFFSET_TIME.matchEntire(v)?.let { m ->
            val amount = BigDecimal(m.groupValues[1])
            val factor = when (m.groupValues[2]) {
                "h" -> 3_600_000L
                "m" -> 60_000L
                "ms" -> 1L
                else -> 1_000L
            }
            amount.multiply(BigDecimal.valueOf(factor))
        } ?: CLOCK_TIME.matchEntire(v)?.let { m ->
            val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            val minutes = m.groupValues[2].toLong()
            val seconds = BigDecimal(m.groupValues[3])
            if (m.groupValues[1].isNotEmpty() && minutes >= 60) return null
            if (seconds >= BigDecimal(60)) return null
            BigDecimal.valueOf(hours * 3_600_000L + minutes * 60_000L).add(seconds.multiply(BigDecimal(1_000)))
        } ?: return null
        if (ms > BigDecimal.valueOf(MAX_TIME_MS)) return null
        return ms.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    // ── DOM helpers ─────────────────────────────────────────────────────────────────────────

    private fun localName(element: Element): String =
        (element.localName ?: element.tagName.substringAfterLast(':')).lowercase()

    /** Attribute by local name, whatever its prefix (`ttm:agent`, `xml:id`, `itunes:timing`...). */
    private fun attr(element: Element, name: String): String? {
        val attributes = element.attributes ?: return null
        for (i in 0 until attributes.length) {
            val a = attributes.item(i)
            val local = a.localName ?: a.nodeName.substringAfterLast(':')
            if (local.equals(name, ignoreCase = true) && !a.nodeName.startsWith("xmlns")) {
                return a.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun inheritedAttr(element: Element, name: String): String? {
        var node: Node? = element
        while (node is Element) {
            attr(node, name)?.let { return it }
            node = node.parentNode
        }
        return null
    }

    private fun descendants(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", name)
        val out = ArrayList<Element>(nodes.length)
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        if (out.isEmpty()) {
            // Namespace-unaware DOMs only answer by qualified name.
            val plain = root.getElementsByTagName(name)
            for (i in 0 until plain.length) (plain.item(i) as? Element)?.let(out::add)
        }
        return out
    }

    /** Drops control/format characters; keeps whitespace so word boundaries survive. */
    private fun cleanText(raw: String): String = raw.filterNot { c ->
        Character.getType(c).toByte() == Character.FORMAT ||
            (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r')
    }
}
