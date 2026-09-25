package com.theveloper.pixelplay.presentation.lyrics.model

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.utils.LyricsUtils
import java.text.BreakIterator
import kotlinx.collections.immutable.toPersistentList

/**
 * Builds the immutable [PreparedLyrics] model (spec §6.1) from the app's [Lyrics] /
 * [LyricsDoc] / [SyncedLine] types. Pure and allocation-heavy by design: run it off the main
 * thread, once per lyrics change.
 *
 * Source preference: [Lyrics.document] when present (explicit syllable durations, voices and
 * line ends), otherwise [Lyrics.synced] with [SyncedWord.startsNewWord] for word grouping.
 */
object PreparedLyricsBuilder {

    /** A gap at least this long between two groups gets interlude dots (Apple web value). */
    const val INTERLUDE_MIN_GAP_MS = 9_000L

    /** A background vocal joins the lead whose `[start - this, end]` window holds its start. */
    const val BACKGROUND_GROUP_LEAD_IN_MS = 1_000L

    /** Inferred end of a line's final syllable: at most this long after it starts. */
    const val LAST_SYLLABLE_MAX_MS = 1_200L

    /** The final line of a song stays active at least this long when its end is inferred. */
    const val LAST_LINE_MIN_MS = 4_000L

    /** Emphasis ("glow") needs a word held at least this long, with an explicit end. */
    const val EMPHASIS_MIN_DURATION_MS = 1_000L
    const val EMPHASIS_MIN_GRAPHEMES = 2
    const val EMPHASIS_MAX_GRAPHEMES = 7

    /** Returns `null` when there is nothing time-synced to show (plain lyrics or none at all). */
    fun build(lyrics: Lyrics?): PreparedLyrics? {
        if (lyrics == null) return null
        val doc = lyrics.document
        if (doc != null && doc.lines.any { it.text.isNotBlank() }) {
            return buildFromDoc(doc, lyrics.synced)
        }
        val synced = lyrics.synced
        if (synced.isNullOrEmpty()) return null
        return buildFromSynced(synced)
    }

    fun build(doc: LyricsDoc): PreparedLyrics? = buildFromDoc(doc, null)

    // ---------------------------------------------------------------------------------------
    // Drafts: mutable working copies, frozen into Prepared* at the end.
    // ---------------------------------------------------------------------------------------

    private class DraftSyllable(
        val charStart: Int,
        val charEnd: Int,
        val startMs: Long,
        var endMs: Long,
        val endIsExplicit: Boolean,
        /** `true` when this syllable begins a new word (whitespace or a CJK boundary before it). */
        val startsWord: Boolean,
        var wordIndex: Int = 0,
        var emphasis: Boolean = false,
    )

    private class DraftLine(
        val startMs: Long,
        var endMs: Long,
        var endIsExplicit: Boolean,
        val text: String,
        val role: VoiceRole,
        val syllables: MutableList<DraftSyllable>?,
        val translation: String?,
        val romanization: String?,
        /** Only for the LRC path: the source line, for [resolveLineEndTimeMs]. */
        val source: SyncedLine? = null,
        /** Only for the LRC path: end set by a following empty LRC line. */
        var markerEndMs: Long? = null,
    ) {
        var estimatedEndMs: Long = 0L
        var groupLead: Int = -1
        var bgAbove: Boolean = false
    }

    // ---------------------------------------------------------------------------------------
    // LyricsDoc path
    // ---------------------------------------------------------------------------------------

    private fun buildFromDoc(doc: LyricsDoc, synced: List<SyncedLine>?): PreparedLyrics? {
        val extrasByStart = HashMap<Long, SyncedLine>()
        synced?.forEach { line -> extrasByStart.putIfAbsent(line.time.toLong(), line) }

        val drafts = ArrayList<DraftLine>(doc.lines.size)
        // sortedBy is stable, so lines sharing a start keep their document order.
        doc.lines
            .sortedBy { it.startMs }
            .forEach { line ->
                if (line.text.isBlank()) return@forEach
                val role = VoiceRole.fromRole(doc.voices.find { it.id == line.voiceId }?.role)
                val first = line.text.indexOfFirst { !it.isWhitespace() }
                val last = line.text.indexOfLast { !it.isWhitespace() }
                val text = line.text.substring(first, last + 1)

                val syllables = if (line.syllables.isEmpty()) null else {
                    val out = ArrayList<DraftSyllable>(line.syllables.size)
                    var offset = 0
                    var boundary = true
                    // Document order: the syllable texts concatenate to the line text, in order.
                    for (s in line.syllables) {
                        val sText = s.text
                        val rawStart = offset
                        offset += sText.length
                        val lead = sText.indexOfFirst { !it.isWhitespace() }
                        if (lead < 0) { // whitespace-only token: a word boundary, nothing to time
                            boundary = true
                            continue
                        }
                        val trail = sText.indexOfLast { !it.isWhitespace() }
                        val cs = (rawStart + lead - first).coerceIn(0, text.length)
                        val ce = (rawStart + trail + 1 - first).coerceIn(0, text.length)
                        val startsWord = out.isEmpty() || boundary || lead > 0 || isCjk(sText[lead])
                        boundary = trail < sText.length - 1
                        if (ce <= cs) continue
                        out += DraftSyllable(
                            charStart = cs,
                            charEnd = ce,
                            startMs = s.startMs,
                            endMs = s.startMs + s.durationMs.coerceAtLeast(1L),
                            endIsExplicit = s.durationMs > 0L,
                            startsWord = startsWord,
                        )
                    }
                    out.takeIf { it.isNotEmpty() }
                }

                val extras = extrasByStart[line.startMs]
                drafts += DraftLine(
                    startMs = line.startMs,
                    endMs = line.endMs.coerceAtLeast(line.startMs + 1L),
                    endIsExplicit = line.endMs > line.startMs,
                    text = text,
                    role = role,
                    syllables = syllables,
                    translation = extras?.translation?.takeIf { it.isNotBlank() },
                    romanization = extras?.romanization?.takeIf { it.isNotBlank() },
                )
            }
        if (drafts.isEmpty()) return null
        return finish(drafts)
    }

    // ---------------------------------------------------------------------------------------
    // SyncedLine (LRC / enhanced LRC / transpiled) path
    // ---------------------------------------------------------------------------------------

    private fun buildFromSynced(synced: List<SyncedLine>): PreparedLyrics? {
        val drafts = ArrayList<DraftLine>(synced.size)
        for (line in synced.sortedBy { it.time }) {
            val text = sanitizeLyricLineText(line.line).trimEnd()
            val words = line.words?.let(::sanitizeSyncedWords).orEmpty()
            if (text.isBlank() && words.isEmpty()) {
                // An empty LRC line is the explicit end of the line before it.
                val previous = drafts.lastOrNull()
                if (previous != null && line.time.toLong() > previous.startMs && previous.markerEndMs == null) {
                    previous.markerEndMs = line.time.toLong()
                }
                continue
            }

            val (lineText, syllables) = mapWordsIntoText(text, words)
            drafts += DraftLine(
                startMs = line.time.toLong(),
                endMs = 0L, // resolved below, once every start is known
                endIsExplicit = false,
                text = lineText,
                role = VoiceRole.fromRole(line.voiceRole),
                syllables = syllables,
                translation = line.translation?.takeIf { it.isNotBlank() },
                romanization = line.romanization?.takeIf { it.isNotBlank() },
                source = line,
            )
        }
        if (drafts.isEmpty()) return null

        for (i in drafts.indices) {
            val d = drafts[i]
            val src = d.source ?: continue
            val explicitLineEnd = src.endTime?.takeIf { it > src.time }?.toLong()
            val marker = d.markerEndMs
            when {
                explicitLineEnd != null -> { d.endMs = explicitLineEnd; d.endIsExplicit = true }
                marker != null -> { d.endMs = marker; d.endIsExplicit = true }
                else -> {
                    val nextLeadStart = (i + 1 until drafts.size)
                        .firstOrNull { j -> drafts[j].role != VoiceRole.BACKGROUND && drafts[j].startMs > d.startMs }
                        ?.let { drafts[it].startMs }
                    val maxExplicitSyllableEnd = d.syllables
                        ?.filter { it.endIsExplicit }
                        ?.maxOfOrNull { it.endMs } ?: 0L
                    val inferred = if (nextLeadStart != null) {
                        resolveLineEndTimeMs(src, nextLeadStart.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    } else {
                        val lastSyllableStart = d.syllables?.maxOfOrNull { it.startMs } ?: d.startMs
                        maxOf(
                            d.startMs + maxOf(LAST_LINE_MIN_MS, estimatedDurationMs(d)),
                            lastSyllableStart + 1L,
                        )
                    }
                    d.endMs = maxOf(inferred, maxExplicitSyllableEnd, d.startMs + 1L)
                    d.endIsExplicit = false
                }
            }
        }
        return finish(drafts)
    }

    /**
     * Finds each (trimmed) word inside [text] in order, so the syllable ranges index into the
     * line exactly as the source wrote it. If the words cannot be found (the line text and the
     * word tokens disagree), the line text is rebuilt from the words instead.
     */
    private fun mapWordsIntoText(
        text: String,
        words: List<SyncedWord>,
    ): Pair<String, MutableList<DraftSyllable>?> {
        if (words.isEmpty()) return text to null
        val clusters = clusterSyncedWords(words)
        val startsWord = BooleanArray(words.size)
        clusters.forEach { startsWord[it.startIndex] = true }

        val ranges = IntArray(words.size * 2)
        var cursor = 0
        var found = true
        for ((k, w) in words.withIndex()) {
            val at = text.indexOf(w.word, cursor)
            if (at < 0) { found = false; break }
            ranges[2 * k] = at
            ranges[2 * k + 1] = at + w.word.length
            cursor = at + w.word.length
        }
        val lineText = if (found) text else buildString {
            for ((k, w) in words.withIndex()) {
                if (k > 0 && w.startsNewWord) append(' ')
                ranges[2 * k] = length
                append(w.word)
                ranges[2 * k + 1] = length
            }
        }

        val out = ArrayList<DraftSyllable>(words.size)
        for ((k, w) in words.withIndex()) {
            val explicitEnd = w.endTime?.takeIf { it > w.time }?.toLong()
            out += DraftSyllable(
                charStart = ranges[2 * k],
                charEnd = ranges[2 * k + 1],
                startMs = w.time.toLong(),
                endMs = explicitEnd ?: 0L, // inferred ends are filled in by finish()
                endIsExplicit = explicitEnd != null,
                startsWord = startsWord[k],
            )
        }
        return lineText to out
    }

    // ---------------------------------------------------------------------------------------
    // Shared: estimates, grouping, interludes, syllable ends, emphasis, freeze.
    // ---------------------------------------------------------------------------------------

    private fun finish(drafts: List<DraftLine>): PreparedLyrics {
        // Syllable ends that depend only on the next syllable (the last one needs the line end).
        drafts.forEach { d -> d.syllables?.let { inferInnerSyllableEnds(it) } }

        drafts.forEach { d -> d.estimatedEndMs = estimatedEndMs(d) }
        groupBackgroundVocals(drafts)

        val leaders = drafts.indices.filter { drafts[it].groupLead == it }
        val members = HashMap<Int, MutableList<Int>>()
        drafts.indices.forEach { i ->
            val lead = drafts[i].groupLead
            if (lead != i) members.getOrPut(lead) { ArrayList() } += i
        }

        val rows = ArrayList<Row>(drafts.size + 4)
        val interludes = ArrayList<LongArray>()
        var prevEnd = 0L
        for (leader in leaders) {
            val group = members[leader].orEmpty()
            val groupStart = group.fold(drafts[leader].startMs) { acc, i -> minOf(acc, drafts[i].startMs) }
            // prevEnd is 0 before the first group, so this also catches an intro of >= 9 s.
            if (groupStart - prevEnd >= INTERLUDE_MIN_GAP_MS) {
                rows += Row.Interlude(
                    startMs = prevEnd,
                    endMs = groupStart,
                    alignEnd = drafts[leader].role == VoiceRole.DUET,
                )
                interludes += longArrayOf(prevEnd, groupStart)
            }
            group.filter { drafts[it].bgAbove }.forEach { rows += Row.Line(it) }
            rows += Row.Line(leader)
            group.filter { !drafts[it].bgAbove }.forEach { rows += Row.Line(it) }

            prevEnd = maxOf(prevEnd, drafts[leader].estimatedEndMs)
            group.forEach { prevEnd = maxOf(prevEnd, drafts[it].estimatedEndMs) }
        }

        // A line whose end was only inferred must not stay active into the interlude after it.
        if (interludes.isNotEmpty()) {
            drafts.forEach { d ->
                if (d.endIsExplicit) return@forEach
                for (gap in interludes) {
                    val g0 = gap[0]
                    if (d.startMs < g0 && d.endMs > g0) d.endMs = maxOf(g0, d.startMs + 1L)
                }
            }
        }

        drafts.forEach { d ->
            val syllables = d.syllables ?: return@forEach
            inferLastSyllableEnd(syllables, d.endMs)
            assignWordIndices(syllables)
            markEmphasis(d.text, syllables)
        }

        val lines = drafts.mapIndexed { i, d ->
            PreparedLine(
                index = i,
                startMs = d.startMs,
                endMs = d.endMs,
                endIsExplicit = d.endIsExplicit,
                text = d.text,
                role = d.role,
                groupLeadIndex = d.groupLead,
                bgAbove = d.groupLead != i && d.bgAbove,
                syllables = d.syllables?.map { s ->
                    PreparedSyllable(
                        charStart = s.charStart,
                        charEnd = s.charEnd,
                        startMs = s.startMs,
                        endMs = s.endMs,
                        endIsExplicit = s.endIsExplicit,
                        wordIndex = s.wordIndex,
                        emphasis = s.emphasis,
                    )
                }?.toPersistentList(),
                translation = d.translation,
                romanization = d.romanization,
            )
        }.toPersistentList()

        return PreparedLyrics(
            lines = lines,
            rows = rows.toPersistentList(),
            hasWordTiming = lines.any { it.hasWordTiming },
            hasDuet = lines.any { it.role == VoiceRole.DUET },
            startsSorted = LongArray(lines.size) { lines[it].startMs },
        )
    }

    /** Explicit ends stay; otherwise a syllable ends where the next one starts. */
    private fun inferInnerSyllableEnds(syllables: List<DraftSyllable>) {
        for (k in 0 until syllables.size - 1) {
            val s = syllables[k]
            if (!s.endIsExplicit) s.endMs = maxOf(syllables[k + 1].startMs, s.startMs + 1L)
        }
    }

    /** The last syllable's inferred end: `min(lineEnd, start + 1200)`. */
    private fun inferLastSyllableEnd(syllables: List<DraftSyllable>, lineEndMs: Long) {
        val last = syllables.lastOrNull() ?: return
        if (!last.endIsExplicit) {
            last.endMs = maxOf(minOf(lineEndMs, last.startMs + LAST_SYLLABLE_MAX_MS), last.startMs + 1L)
        }
    }

    private fun assignWordIndices(syllables: List<DraftSyllable>) {
        var word = -1
        syllables.forEachIndexed { k, s ->
            if (k == 0 || s.startsWord) word++
            s.wordIndex = word
        }
    }

    /**
     * §1.4: a word glows when its **explicit** duration is ≥ 1000 ms and its trimmed length is
     * 2–7 graphemes (CJK: duration only). Syllables with `startsNewWord == false` are merged into
     * one word first.
     */
    private fun markEmphasis(text: String, syllables: List<DraftSyllable>) {
        var k = 0
        while (k < syllables.size) {
            var end = k
            while (end + 1 < syllables.size && syllables[end + 1].wordIndex == syllables[k].wordIndex) end++
            val first = syllables[k]
            val last = syllables[end]
            val explicit = (k..end).all { syllables[it].endIsExplicit }
            val durationMs = last.endMs - first.startMs
            val word = text.substring(first.charStart, last.charEnd.coerceAtLeast(first.charStart)).trim()
            val qualifies = explicit && durationMs >= EMPHASIS_MIN_DURATION_MS && word.isNotEmpty() &&
                (word.any(::isCjk) || graphemeCount(word) in EMPHASIS_MIN_GRAPHEMES..EMPHASIS_MAX_GRAPHEMES)
            if (qualifies) for (i in k..end) syllables[i].emphasis = true
            k = end + 1
        }
    }

    /**
     * §1.7: a background vocal belongs to the lead line whose `[start - 1000, end]` window holds
     * its start. A lead already playing when the vocal starts wins over one about to start.
     */
    private fun groupBackgroundVocals(drafts: List<DraftLine>) {
        drafts.forEachIndexed { i, d -> d.groupLead = i; d.bgAbove = false }
        drafts.forEachIndexed { i, d ->
            if (d.role != VoiceRole.BACKGROUND) return@forEachIndexed
            var playing = -1
            var upcoming = -1
            drafts.forEachIndexed { j, lead ->
                if (lead.role == VoiceRole.BACKGROUND) return@forEachIndexed
                val inWindow = d.startMs >= lead.startMs - BACKGROUND_GROUP_LEAD_IN_MS && d.startMs <= lead.endMs
                if (!inWindow) return@forEachIndexed
                if (lead.startMs <= d.startMs) {
                    if (playing < 0 || lead.startMs >= drafts[playing].startMs) playing = j
                } else if (upcoming < 0) {
                    upcoming = j
                }
            }
            val lead = if (playing >= 0) playing else upcoming
            if (lead >= 0) {
                d.groupLead = lead
                d.bgAbove = d.startMs < drafts[lead].startMs
            }
        }
    }

    /**
     * Estimated end used for interlude detection: the explicit end when there is one, otherwise
     * `start + clamp(words × 450 + 800, 1500, 6000)` — but never before the last syllable could
     * plausibly finish, and never after the resolved end.
     */
    private fun estimatedEndMs(d: DraftLine): Long {
        if (d.endIsExplicit) return d.endMs
        val base = d.startMs + estimatedDurationMs(d)
        val syllableFloor = d.syllables?.maxOfOrNull { s ->
            if (s.endIsExplicit) s.endMs else s.startMs + LAST_SYLLABLE_MAX_MS
        } ?: 0L
        return minOf(d.endMs, maxOf(base, syllableFloor)).coerceAtLeast(d.startMs + 1L)
    }

    private fun estimatedDurationMs(d: DraftLine): Long {
        val words = d.syllables?.let { syl -> syl.count { it.startsWord }.coerceAtLeast(1) }
            ?: estimateWordCount(d.text)
        return (words * 450L + 800L).coerceIn(1_500L, 6_000L)
    }

    internal fun estimateWordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            when {
                isCjk(c) -> { count++; inWord = false }
                c.isWhitespace() -> inWord = false
                !inWord -> { count++; inWord = true }
            }
        }
        return count
    }

    internal fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}

internal fun isCjk(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
    else -> false
}

// -------------------------------------------------------------------------------------------
// Ported from presentation/components/LyricsSheet.kt (copied verbatim in behaviour; the
// originals are removed from LyricsSheet by the integration stage).
// -------------------------------------------------------------------------------------------

private val LeadingTagRegex = Regex("^v\\d+:\\s*", RegexOption.IGNORE_CASE)

internal fun sanitizeLyricLineText(raw: String): String =
    LyricsUtils.stripLrcTimestamps(raw).replace(LeadingTagRegex, "").trimStart()

internal fun sanitizeSyncedWords(words: List<SyncedWord>): List<SyncedWord> =
    buildList {
        words.forEachIndexed { index, word ->
            val sanitized = if (index == 0) LeadingTagRegex.replace(word.word, "") else word.word
            val normalized = sanitized.trim()
            if (normalized.isEmpty()) return@forEachIndexed

            add(
                word.copy(
                    word = normalized,
                    startsNewWord = if (isEmpty()) true else word.startsNewWord
                )
            )
        }
    }

internal data class SyncedWordCluster(
    val startIndex: Int,
    val words: List<SyncedWord>
)

internal fun clusterSyncedWords(words: List<SyncedWord>): List<SyncedWordCluster> {
    if (words.isEmpty()) return emptyList()

    val clusters = mutableListOf<SyncedWordCluster>()
    var currentWords = mutableListOf<SyncedWord>()
    var currentStartIndex = 0

    words.forEachIndexed { index, word ->
        val canBreakBefore = word.startsNewWord || word.word.firstOrNull()?.let(::isCjk) == true
        if (canBreakBefore && currentWords.isNotEmpty()) {
            clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
            currentWords = mutableListOf()
            currentStartIndex = index
        } else if (currentWords.isEmpty()) {
            currentStartIndex = index
        }

        currentWords += word
    }

    if (currentWords.isNotEmpty()) {
        clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
    }

    return clusters
}

internal fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    line.endTime?.takeIf { it > line.time }?.let { return it.toLong() }
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}
