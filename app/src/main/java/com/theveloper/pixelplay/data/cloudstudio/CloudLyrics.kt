package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable

// Cloud Studio lyrics both ways (design §2.3 `lyrics.json`, §7.1 `CloudLyrics`, §7.5 step 3), ported from the iOS
// app's `CloudLyrics.swift`:
// - the known lyrics a song already has, as the request's `lyrics.lines` (capped like the worker caps them);
// - the worker's `pixl.cloudstudio.lyrics` v1 as a PixelPlay [LyricsDoc]. Words are rebuilt from their UTF-16
//   offsets `[c0, c1)` into the ORIGINAL line text (the aligner strips punctuation), so a line's syllables always join
//   to exactly its text; any line whose offsets or times don't hold together falls back to line timing.
//
// Android's [LyricsDocCodec.isValid] is stricter than the iOS store (every syllable inside its line, every line inside
// the song), and a document that fails it would load as no lyrics at all, so [lyricsDoc] clamps to those rules and
// the result is checked with the codec before anything is saved.

object CloudLyrics {
    /** [LyricsMetadata.source] of aligned results, and of machine-written (transcribed) ones. */
    const val SOURCE = "cloud"
    const val TRANSCRIBED_SOURCE = "cloud-ai"

    /** How good a set of lyrics is, for "does the result improve on what is stored?". */
    enum class Level { NONE, PLAIN, LINE_SYNCED, WORD_SYNCED }

    // ─── Request ────────────────────────────────────────────────────────────────────────────

    /** The request's lines and whether they carry line times. */
    data class RequestLines(val lines: List<CloudLyricsInputLine>, val hasLineTimes: Boolean)

    /**
     * The request's lines from a song's lyrics: synced lines with their times (end = the line's own end, else the
     * next line's start), else plain lines without times. Blank lines are dropped; the worker's caps apply.
     */
    fun requestLines(lyrics: Lyrics?): RequestLines? {
        if (lyrics == null) return null
        val synced = lyrics.synced.orEmpty().filter { it.line.isNotBlank() }
        if (synced.isNotEmpty()) {
            // The worker wants synced lines sorted by start.
            val timed = synced.sortedBy { it.time }
            val lines = timed.mapIndexed { index, line ->
                val start = line.time.coerceAtLeast(0).toLong()
                val next = timed.getOrNull(index + 1)?.time?.toLong()
                val end = line.endTime?.toLong() ?: next
                CloudLyricsInputLine(startMs = start, endMs = end?.coerceAtLeast(start + 1), text = line.line)
            }
            return RequestLines(capped(lines), true)
        }
        // Parsed plain lines may carry an automatic romanization after a line break; only the original goes out.
        val plain = lyrics.plain.orEmpty().map { it.substringBefore('\n') }.filter { it.isNotBlank() }
        if (plain.isEmpty()) return null
        return RequestLines(capped(plain.map { CloudLyricsInputLine(text = it) }), false)
    }

    internal fun capped(lines: List<CloudLyricsInputLine>): List<CloudLyricsInputLine> {
        val out = mutableListOf<CloudLyricsInputLine>()
        var chars = 0
        for (line in lines.take(CloudLimits.MAX_LYRICS_LINES)) {
            val text = if (line.text.length > CloudLimits.MAX_LYRICS_LINE_CHARS) {
                // Never split a surrogate pair (an emoji) at the cut.
                line.text.take(CloudLimits.MAX_LYRICS_LINE_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            } else line.text
            chars += text.length
            if (chars > CloudLimits.MAX_LYRICS_CHARS) break
            out += line.copy(text = text)
        }
        return out
    }

    // ─── Levels ─────────────────────────────────────────────────────────────────────────────

    /** The level of stored [Lyrics]. */
    fun level(lyrics: Lyrics?): Level {
        if (lyrics == null) return Level.NONE
        val synced = lyrics.synced.orEmpty().filter { it.line.isNotBlank() }
        if (synced.isNotEmpty()) {
            val wordTimed = synced.all { !it.words.isNullOrEmpty() } &&
                !synced.all { line -> line.words.orEmpty().all { it.time == 0 } }
            return if (wordTimed) Level.WORD_SYNCED else Level.LINE_SYNCED
        }
        return if (lyrics.plain.orEmpty().any { it.isNotBlank() }) Level.PLAIN else Level.NONE
    }

    /** The level of a converted document. */
    fun level(doc: LyricsDoc): Level = when {
        doc.lines.isEmpty() -> Level.NONE
        doc.lines.any { it.syllables.isNotEmpty() } -> Level.WORD_SYNCED
        else -> Level.LINE_SYNCED
    }

    /** Import only what improves on the stored lyrics, and never over the person's own sync unless they asked. */
    fun shouldImport(stored: Level, incoming: Level, storedIsUserSynced: Boolean, replaceUserSynced: Boolean): Boolean {
        if (storedIsUserSynced && !replaceUserSynced) return false
        if (storedIsUserSynced && replaceUserSynced) return incoming >= Level.LINE_SYNCED
        return incoming > stored
    }

    // ─── Result ─────────────────────────────────────────────────────────────────────────────

    /**
     * The document for `lyrics.json`, or null when nothing usable is in it. [durationMs] is the song's length (line
     * ends are clamped to it); title, artist and album go into the metadata.
     */
    fun lyricsDoc(
        cloud: CloudLyricsDocument,
        durationMs: Long,
        title: String = "",
        artist: String = "",
        album: String = "",
    ): LyricsDoc? {
        if (cloud.schema != CloudSchema.LYRICS || cloud.v != CloudSchema.VERSION) return null
        val duration = if (durationMs > 0) durationMs else (cloud.lines.maxOfOrNull { it.endMs } ?: 0L) + 1
        if (duration <= 1) return null
        val lines = mutableListOf<TimedLine>()
        var previousStart = 0L
        val ordered = cloud.lines.sortedWith(compareBy<CloudLyricsLine> { it.startMs }.thenBy { it.i ?: 0 })
        for (line in ordered) {
            if (line.text.isBlank()) continue
            val start = maxOf(line.startMs, previousStart, 0L).coerceAtMost(duration - 1)
            var end = maxOf(line.endMs, start + 1).coerceAtMost(duration)
            if (end <= start) end = start + 1
            var syllables: List<TimedSyllable> = emptyList()
            if (line.isWordTimed) {
                val rebuilt = syllablesFor(line, start, end)
                if (rebuilt != null) {
                    val lastEnd = rebuilt.last().startMs + rebuilt.last().durationMs
                    val widened = maxOf(end, lastEnd).coerceAtMost(duration)
                    fitted(rebuilt, start, widened)?.let {
                        syllables = it
                        end = widened
                    }
                }
            }
            lines += TimedLine(startMs = start, endMs = end, text = line.text, syllables = syllables)
            previousStart = start
        }
        if (lines.isEmpty()) return null
        val doc = LyricsDoc(
            metadata = LyricsMetadata(
                title = title, artist = artist, album = album, durationMs = duration,
                source = if (cloud.isTranscribed) TRANSCRIBED_SOURCE else SOURCE,
            ),
            lines = lines,
        )
        // Never hand the store a document it would read back as no lyrics: drop word timing that still breaks a rule,
        // and give up only if even line timing doesn't hold.
        if (LyricsDocCodec.isValid(doc)) return doc
        val lineOnly = doc.copy(lines = doc.lines.map { it.copy(syllables = emptyList()) })
        return lineOnly.takeIf(LyricsDocCodec::isValid)
    }

    /**
     * The syllables of a word-timed line: each covers its word plus the text up to the next word (leading text
     * joins the first word), timed by the word. Null when the offsets or times don't hold together.
     */
    internal fun syllablesFor(line: CloudLyricsLine, lineStart: Long, lineEnd: Long): List<TimedSyllable>? {
        val text = line.text
        val words = line.words?.takeIf { it.isNotEmpty() } ?: return null
        // Offsets: inside the line, increasing, non-overlapping, never splitting a surrogate pair.
        var previousEnd = 0
        for (word in words) {
            if (word.c0 < previousEnd || word.c1 <= word.c0 || word.c1 > text.length) return null
            if (splitsSurrogatePair(text, word.c0) || splitsSurrogatePair(text, word.c1)) return null
            previousEnd = word.c1
        }
        val syllables = mutableListOf<TimedSyllable>()
        var lastStart = lineStart
        words.forEachIndexed { k, word ->
            val from = if (k == 0) 0 else word.c0
            val to = if (k + 1 < words.size) words[k + 1].c0 else text.length
            val start = maxOf(word.startMs, lastStart)
            if (start >= lineEnd + 5_000) return null // a word far outside its line: distrust the line
            val end = maxOf(word.endMs, start + 1)
            syllables += TimedSyllable(startMs = start, durationMs = end - start, text = text.substring(from, to))
            lastStart = start
        }
        if (syllables.joinToString("") { it.text } != text) return null
        return syllables
    }

    /** Syllables clamped inside `[lineStart, lineEnd)` as the codec requires, or null when one can't fit. */
    private fun fitted(syllables: List<TimedSyllable>, lineStart: Long, lineEnd: Long): List<TimedSyllable>? =
        syllables.map { syllable ->
            val start = syllable.startMs.coerceAtLeast(lineStart)
            if (start >= lineEnd) return null
            val end = (syllable.startMs + syllable.durationMs).coerceAtMost(lineEnd)
            syllable.copy(startMs = start, durationMs = (end - start).coerceAtLeast(1))
        }

    private fun splitsSurrogatePair(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        return Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(text[index - 1])
    }

    /**
     * The import precondition for word timing (the same as [com.theveloper.pixelplay.data.tais.lyrics.TaisLyricsAligner.persistAligned]):
     * some word timing, some after 0, none negative, starts non-decreasing. Line-only documents pass when they have
     * lines.
     */
    fun isUsable(doc: LyricsDoc): Boolean {
        val syllables = doc.lines.flatMap { it.syllables }
        if (syllables.isEmpty()) return doc.lines.isNotEmpty() && doc.lines.any { it.startMs > 0 || it.endMs > 1 }
        val starts = syllables.map { it.startMs }
        return starts.any { it > 0 } && starts.all { it >= 0 } && starts.zipWithNext().all { (a, b) -> a <= b }
    }
}
