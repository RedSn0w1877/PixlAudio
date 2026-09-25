package com.theveloper.pixelplay.data.lyrics.sync

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import timber.log.Timber

/*
 * "Sync it yourself" — the pure core of the tap-to-sync lyrics editor.
 *
 * Everything here is plain Kotlin: no Android framework, no coroutines, no player. The state
 * holder feeds it positions and taps, gets back a new immutable draft plus an optional seek
 * target, and asks it for the finished [LyricsDoc].
 *
 * Time domains:
 *  - "raw" times are what the player reported at the moment of a tap (media ms, already corrected
 *    for the tap's event latency and scaled by the playback speed; see [LyricsTapSync.rawTapPositionMs]).
 *  - "built" times are what ends up in the saved lyrics:
 *        built = raw − reactionOffset × speedAtTap + nudge
 *    Tokens flagged [SyncToken.exact] (loaded from saved lyrics, or timed roughly by
 *    skip/fill) skip the reaction offset because they never came from a finger.
 *  The reaction offset is never baked into the draft, so it can change later without re-tapping.
 */

/** One lyric line of the draft. Its words are `tokens[firstToken until firstToken + tokenCount]`. */
@Serializable
data class SyncLine(
    val text: String,
    val voiceId: String = LyricsTapSync.LEAD_VOICE_ID,
    /** Line start from a line-synced source. Only used for seeking, hints and rough timing. */
    val anchorMs: Long? = null,
    val translation: String? = null,
    val firstToken: Int,
    val tokenCount: Int,
    /** Background line that already had word timing: carried through unchanged, never tapped. */
    val locked: Boolean = false,
    /** Some of the words were timed roughly (skip line, time the rest, gaps) rather than tapped. */
    val skipped: Boolean = false,
) {
    val endToken: Int get() = firstToken + tokenCount
}

/**
 * One tappable unit (a word, or a single CJK character). [text] keeps its trailing space except
 * for the last token of a line, so the tokens of a line always join back to exactly the line text.
 */
@Serializable
data class SyncToken(
    val line: Int,
    val text: String,
    val rawStartMs: Long? = null,
    val startSpeed: Float = 1f,
    /** Only set when the word was held down (or loaded with an explicit end). */
    val rawEndMs: Long? = null,
    val endSpeed: Float = 1f,
    /** The times are final (loaded or roughly spread), not taps: no reaction offset applies. */
    val exact: Boolean = false,
) {
    val isStamped: Boolean get() = rawStartMs != null
}

data class SyncDraft(
    val songId: String,
    val durationMs: Long,
    val lines: PersistentList<SyncLine>,
    val tokens: PersistentList<SyncToken>,
    /** Index of the next token to tap. `tokens.size` means every word has been tapped. */
    val cursor: Int,
    val voices: List<Voice> = listOf(Voice()),
    /** Preview "earlier / later" nudge in media ms, applied to every word. */
    val nudgeMs: Int = 0,
    val version: Int = 1,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
) {
    /** True when there is no word left to tap. */
    val isFinished: Boolean get() = LyricsTapSync.nextTappable(this, cursor) >= tokens.size

    /** Line holding the next word to tap (the last line once finished). */
    val currentLineIndex: Int get() = LyricsTapSync.currentLineIndex(this)

    val tappableCount: Int get() = tokens.count { !lines[it.line].locked }

    val tappedCount: Int get() = tokens.count { it.rawStartMs != null && !lines[it.line].locked }

    /** Tappable words not stamped yet. */
    val remainingCount: Int get() = tappableCount - tappedCount
}

/** Result of a reducer: the new draft, where to seek (if anywhere) and what happened. */
data class SyncStep(
    val draft: SyncDraft,
    val seekToMs: Long? = null,
    /** Number of stamped words whose timing was removed (drives "Removed timing for N words"). */
    val clearedCount: Int = 0,
    /** The tap landed at or past the next line's start and was clamped just before it. */
    val pastNextLine: Boolean = false,
    /** The first tap of a line came more than 3 s before that line's anchor. */
    val tapBeforeAnchor: Boolean = false,
)

enum class SyncDraftOrigin {
    /** Saved by the user before: opens straight into Preview. */
    USER_SYNCED,
    /** Already had word timing from somewhere else: opens in Intro with the "already timed" note. */
    WORD_SYNCED,
    /** Line-synced source: every word is tapped, line times become anchors. */
    LINE_SYNCED,
    /** Plain or pasted text: every word is tapped, no anchors. */
    PLAIN,
    /** Nothing usable: go to NeedWords. [SyncDraftSeed.draft] is null. */
    NONE,
}

data class SyncDraftSeed(val draft: SyncDraft?, val origin: SyncDraftOrigin)

/** The saved document plus which of its lines were (partly) timed roughly, for the "≈" mark. */
data class SyncResult(val doc: LyricsDoc, val roughLineIndices: Set<Int>)

/** Built start/end for every token of a draft. */
class SyncTiming internal constructor(
    val startsMs: LongArray,
    val endsMs: LongArray,
    /** Indices into [SyncDraft.lines] of lines with at least one roughly timed word. */
    val roughLines: Set<Int>,
)

object LyricsTapSync {

    const val LEAD_VOICE_ID = "lead"
    const val SOURCE_USER = "user"

    /** A pointer held at least this long counts as "held"; its release stamps the word end. */
    const val HOLD_THRESHOLD_MS = 350L
    /** A second tap this soon after the previous one is a bounce and is ignored. */
    const val BOUNCE_MS = 60L
    /** Undo presses closer than this are batched; only the last seek runs. */
    const val UNDO_BATCH_WINDOW_MS = 300L
    const val UNDO_SEEK_DELAY_MS = 250L

    const val MIN_TAP_GAP_MS = 10L
    const val MIN_WORD_MS = 40L
    const val DEFAULT_GAP_MS = 450L
    const val SUSTAIN_GAP_MS = 4_000L
    const val UNDO_PREROLL_MS = 2_000L
    const val ANCHOR_PREROLL_MS = 3_000L
    const val FIX_LINE_PREROLL_MS = 2_500L
    const val REWIND_MS = 5_000L
    const val EARLY_TAP_MS = 3_000L
    const val NEXT_LINE_GUARD_MS = 40L
    const val ROUGH_WORD_MAX_MS = 600L
    const val TAIL_MARGIN_MS = 500L
    const val NUDGE_STEP_MS = 20
    const val MAX_NUDGE_MS = 400
    const val MAX_OFFSET_MS = 400
    const val DEFAULT_OFFSET_SPEAKER_MS = 100
    const val DEFAULT_OFFSET_BLUETOOTH_MS = 180
    const val MAX_CHUNK_CHARS = 40

    private const val MAX_DOC_DURATION_MS = 86_400_000L
    private const val MIN_KNOWN_DURATION_MS = 1_000L
    private const val MAX_VOICES = 32
    private val VOICE_ROLES = setOf("lead", "background", "duet")

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Tokenisation
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private val PUNCTUATION_ONLY = Regex("^\\p{P}+$")

    /** Small kana, prolonged-sound and iteration marks: sung together with the character before. */
    private val CJK_ATTACHING: Set<Int> = buildSet {
        "ぁぃぅぇぉゃゅょゎゕゖァィゥェォャュョヮヵヶーゝゞヽヾ゛゜ｧｨｩｪｫｬｭｮｰﾞﾟ".forEach { add(it.code) }
        for (cp in 0x31F0..0x31FF) add(cp) // Katakana phonetic extensions (small ㇰ…ㇿ)
    }

    /** Trims and collapses every run of whitespace (any Unicode space) to a single space. */
    fun normalizeLine(line: String): String {
        val out = StringBuilder(line.length)
        var pendingSpace = false
        for (c in line) {
            if (c.isWhitespace()) {
                if (out.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(c)
        }
        return out.toString()
    }

    /**
     * Splits a lyric line into tappable tokens. `tokenize(x).joinToString("") == normalizeLine(x)`
     * always holds, which is what [LyricsDocCodec.isValid] needs.
     *
     * - Words are split on spaces and keep their trailing space (except the last).
     * - Punctuation-only chunks (`-`, `—`, `…`, `&`) join the previous word (or the next one when first).
     * - Hyphenated and apostrophe words stay whole.
     * - Han / Hiragana / Katakana runs split into single graphemes; small kana and `ー` stay with the
     *   character before; CJK punctuation joins the previous grapheme. Latin inside such a chunk stays whole.
     * - Hangul, Thai and everything else split on spaces only. Chunks over 40 chars stay whole.
     */
    fun tokenize(line: String): List<String> {
        val normalized = normalizeLine(line)
        if (normalized.isEmpty()) return emptyList()
        val chunks = normalized.split(' ')
        val tokens = ArrayList<String>(chunks.size)
        var pendingPrefix = ""
        for ((index, chunk) in chunks.withIndex()) {
            val separator = if (index < chunks.lastIndex) " " else ""
            if (PUNCTUATION_ONLY.matches(chunk)) {
                if (tokens.isNotEmpty()) {
                    tokens[tokens.lastIndex] = tokens[tokens.lastIndex] + chunk + separator
                } else {
                    pendingPrefix += chunk + separator
                }
                continue
            }
            val pieces = splitChunk(chunk)
            for ((pieceIndex, piece) in pieces.withIndex()) {
                var text = piece
                if (pieceIndex == 0 && pendingPrefix.isNotEmpty()) {
                    text = pendingPrefix + text
                    pendingPrefix = ""
                }
                if (pieceIndex == pieces.lastIndex) text += separator
                tokens += text
            }
        }
        if (pendingPrefix.isNotEmpty()) tokens += pendingPrefix
        return tokens
    }

    private fun splitChunk(chunk: String): List<String> {
        if (chunk.length > MAX_CHUNK_CHARS || !containsCjk(chunk)) return listOf(chunk)
        val pieces = ArrayList<String>()
        val pieceIsCjk = ArrayList<Boolean>()
        val run = StringBuilder()
        fun flushRun() {
            if (run.isNotEmpty()) {
                pieces += run.toString()
                pieceIsCjk += false
                run.setLength(0)
            }
        }
        for (grapheme in graphemes(chunk)) {
            val cp = grapheme.codePointAt(0)
            when {
                cp in CJK_ATTACHING && run.isEmpty() && pieceIsCjk.lastOrNull() == true ->
                    pieces[pieces.lastIndex] = pieces[pieces.lastIndex] + grapheme
                cp in CJK_ATTACHING || isCjkCodePoint(cp) -> {
                    flushRun()
                    pieces += grapheme
                    pieceIsCjk += true
                }
                else -> run.append(grapheme)
            }
        }
        flushRun()

        // Punctuation-only pieces (、。！？「」 and friends) join their neighbour.
        val merged = ArrayList<String>(pieces.size)
        var prefix = ""
        for (piece in pieces) {
            if (PUNCTUATION_ONLY.matches(piece)) {
                if (merged.isNotEmpty()) merged[merged.lastIndex] = merged[merged.lastIndex] + piece else prefix += piece
            } else {
                merged += prefix + piece
                prefix = ""
            }
        }
        if (prefix.isNotEmpty()) merged += prefix
        return merged
    }

    private fun containsCjk(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjkCodePoint(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isCjkCodePoint(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** User-perceived characters. Emoji ZWJ sequences, modifiers and marks stay in one piece. */
    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val out = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            if (out.isNotEmpty() && continuesGrapheme(out[out.lastIndex], grapheme)) {
                out[out.lastIndex] = out[out.lastIndex] + grapheme
            } else {
                out += grapheme
            }
            start = end
            end = iterator.next()
        }
        return out
    }

    /** Belt and braces for runtimes whose BreakIterator predates extended grapheme clusters. */
    private fun continuesGrapheme(previous: String, next: String): Boolean {
        if (previous.endsWith('‍')) return true
        val cp = next.codePointAt(0)
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
        ) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun weight(text: String): Long {
        val trimmed = text.trim()
        return max(1, trimmed.codePointCount(0, trimmed.length)).toLong()
    }

    /** Starts for [texts] spread over `[fromMs, toMs)` in proportion to their character counts. */
    private fun spreadByChars(texts: List<String>, fromMs: Long, toMs: Long): LongArray {
        val weights = texts.map(::weight)
        val total = weights.sum().coerceAtLeast(1L)
        val span = (toMs - fromMs).coerceAtLeast(0L)
        var accumulated = 0L
        return LongArray(texts.size) { k ->
            val start = fromMs + span * accumulated / total
            accumulated += weights[k]
            start
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Building a draft
    // ─────────────────────────────────────────────────────────────────────────────────────────

    fun buildDraft(song: Song, lyrics: Lyrics?, pasted: String?): SyncDraftSeed = buildDraft(
        songId = song.id,
        title = song.title,
        artist = song.displayArtist,
        album = song.album,
        durationMs = song.duration,
        lyrics = lyrics,
        pasted = pasted,
    )

    /**
     * Turns whatever lyrics the song has into a draft (spec §3.2):
     * pasted text → plain; a [LyricsDoc] → exact tokens from its syllables (user-synced or word-synced);
     * word-synced [SyncedLine]s → exact tokens; line-synced → anchors; plain → no anchors.
     */
    fun buildDraft(
        songId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        lyrics: Lyrics?,
        pasted: String?,
    ): SyncDraftSeed {
        val assembler = DraftAssembler()
        var voices: List<Voice> = listOf(Voice())
        var duration = durationMs
        val origin: SyncDraftOrigin
        val document = lyrics?.document
        val synced = lyrics?.synced.orEmpty()

        when {
            pasted != null -> {
                pasted.lines().forEach { assembler.addUntapped(tokenize(it)) }
                origin = SyncDraftOrigin.PLAIN
            }
            document != null && document.lines.isNotEmpty() -> {
                voices = document.voices
                if (duration <= 0L) duration = document.metadata.durationMs ?: 0L
                val hasWordTiming = addDocument(assembler, document)
                origin = when {
                    !hasWordTiming -> SyncDraftOrigin.LINE_SYNCED
                    document.metadata.source == SOURCE_USER -> SyncDraftOrigin.USER_SYNCED
                    else -> SyncDraftOrigin.WORD_SYNCED
                }
            }
            synced.any { it.line.isNotBlank() || !it.words.isNullOrEmpty() } -> {
                voices = addSynced(assembler, lyrics!!)
                origin = if (synced.any { !it.words.isNullOrEmpty() }) {
                    SyncDraftOrigin.WORD_SYNCED
                } else {
                    SyncDraftOrigin.LINE_SYNCED
                }
            }
            !lyrics?.plain.isNullOrEmpty() -> {
                // parseLyrics appends romanization / translation after a '\n'; keep the original line only.
                lyrics.plain.forEach { assembler.addUntapped(tokenize(it.substringBefore('\n'))) }
                origin = SyncDraftOrigin.PLAIN
            }
            else -> return SyncDraftSeed(null, SyncDraftOrigin.NONE)
        }

        if (assembler.tokens.isEmpty()) return SyncDraftSeed(null, SyncDraftOrigin.NONE)
        val draft = SyncDraft(
            songId = songId,
            durationMs = duration.coerceAtLeast(0L),
            lines = assembler.lines.toPersistentList(),
            tokens = assembler.tokens.toPersistentList(),
            cursor = 0,
            voices = voices.ifEmpty { listOf(Voice()) },
            title = title,
            artist = artist,
            album = album,
        )
        val alreadyTimed = origin == SyncDraftOrigin.USER_SYNCED || origin == SyncDraftOrigin.WORD_SYNCED
        val cursor = if (alreadyTimed) draft.tokens.size else nextTappable(draft, 0)
        return SyncDraftSeed(draft.copy(cursor = cursor), origin)
    }

    private class DraftAssembler {
        val lines = ArrayList<SyncLine>()
        val tokens = ArrayList<SyncToken>()

        fun addUntapped(
            texts: List<String>,
            voiceId: String = LyricsTapSync.LEAD_VOICE_ID,
            anchorMs: Long? = null,
            translation: String? = null,
        ) = addLine(texts.map { SyncToken(line = 0, text = it) }, voiceId, anchorMs, translation)

        fun addLine(
            lineTokens: List<SyncToken>,
            voiceId: String,
            anchorMs: Long?,
            translation: String?,
            locked: Boolean = false,
            skipped: Boolean = false,
        ) {
            if (lineTokens.isEmpty()) return
            val lineIndex = lines.size
            val first = tokens.size
            lineTokens.forEach { tokens += it.copy(line = lineIndex) }
            lines += SyncLine(
                text = lineTokens.joinToString("") { it.text },
                voiceId = voiceId,
                anchorMs = anchorMs?.coerceAtLeast(0L),
                translation = translation?.takeIf { it.isNotBlank() },
                firstToken = first,
                tokenCount = lineTokens.size,
                locked = locked,
                skipped = skipped,
            )
        }

        /** Line with known start/end but no word timing: spread the words by character count. */
        fun addSpread(texts: List<String>, voiceId: String, startMs: Long, endMs: Long, translation: String?) {
            if (texts.isEmpty()) return
            val starts = LyricsTapSync.spreadByChars(texts, startMs, max(startMs, endMs))
            val lineTokens = texts.mapIndexed { k, text ->
                SyncToken(
                    line = 0,
                    text = text,
                    rawStartMs = starts[k],
                    rawEndMs = if (k < texts.lastIndex) starts[k + 1] else max(startMs, endMs),
                    exact = true,
                )
            }
            addLine(lineTokens, voiceId, startMs, translation, skipped = true)
        }
    }

    /** Returns true when the document carried any word timing. */
    private fun addDocument(assembler: DraftAssembler, doc: LyricsDoc): Boolean {
        val roleOf = doc.voices.associate { it.id to it.role }
        val hasWordTiming = doc.lines.any { it.syllables.isNotEmpty() }
        for (line in doc.lines) {
            val role = roleOf[line.voiceId] ?: "lead"
            val syllables = mergeBlankSyllables(line.syllables)
            if (syllables.isNotEmpty()) {
                val lineTokens = syllables.map { s ->
                    SyncToken(
                        line = 0,
                        text = s.text,
                        rawStartMs = s.startMs,
                        rawEndMs = s.startMs + s.durationMs,
                        exact = true,
                    )
                }
                assembler.addLine(lineTokens, line.voiceId, line.startMs, null, locked = role == "background")
            } else {
                val texts = tokenize(line.text)
                if (hasWordTiming) {
                    assembler.addSpread(texts, line.voiceId, line.startMs, line.endMs, null)
                } else {
                    assembler.addUntapped(texts, line.voiceId, line.startMs)
                }
            }
        }
        return hasWordTiming
    }

    /** Whitespace-only syllables would be untappable: fold them into a neighbour. */
    private fun mergeBlankSyllables(syllables: List<TimedSyllable>): List<TimedSyllable> {
        if (syllables.none { it.text.isBlank() }) return syllables
        val out = ArrayList<TimedSyllable>(syllables.size)
        var prefix = ""
        for (s in syllables) {
            if (s.text.isBlank()) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(text = out.last().text + s.text) else prefix += s.text
                continue
            }
            if (prefix.isNotEmpty()) {
                out += s.copy(text = prefix + s.text)
                prefix = ""
            } else {
                out += s
            }
        }
        return out
    }

    /** Adds [Lyrics.synced] lines and returns the voices they use. */
    private fun addSynced(assembler: DraftAssembler, lyrics: Lyrics): List<Voice> {
        val synced = lyrics.synced.orEmpty().filter { it.line.isNotBlank() || !it.words.isNullOrEmpty() }
        val anyWords = synced.any { !it.words.isNullOrEmpty() }
        val usedRoles = LinkedHashSet<String>()
        for ((index, line) in synced.withIndex()) {
            val role = line.voiceRole.takeIf { it in VOICE_ROLES } ?: "lead"
            val words = line.words.orEmpty().filter { it.word.isNotBlank() }
            val before = assembler.lines.size
            when {
                anyWords && words.isNotEmpty() -> {
                    val lineTokens = words.mapIndexed { k, word ->
                        val next = words.getOrNull(k + 1)
                        SyncToken(
                            line = 0,
                            text = word.word + if (next != null && next.startsNewWord) " " else "",
                            rawStartMs = word.time.toLong().coerceAtLeast(0L),
                            rawEndMs = word.endTime?.toLong(),
                            exact = true,
                        )
                    }
                    assembler.addLine(lineTokens, role, line.time.toLong(), line.translation, locked = role == "background")
                }
                anyWords -> {
                    val texts = tokenize(line.line)
                    val end = line.endTime?.toLong()
                        ?: synced.getOrNull(index + 1)?.time?.toLong()
                        ?: (line.time + texts.size * DEFAULT_GAP_MS)
                    assembler.addSpread(texts, role, line.time.toLong().coerceAtLeast(0L), end, line.translation)
                }
                else -> assembler.addUntapped(tokenize(line.line), role, line.time.toLong(), line.translation)
            }
            if (assembler.lines.size > before) usedRoles += role
        }
        return usedRoles.map { Voice(id = it, role = it) }.ifEmpty { listOf(Voice()) }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Small helpers the state holder / UI also use
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Media position of a tap: the player's position now, minus the time since the touch event. */
    fun rawTapPositionMs(positionMs: Long, nowUptimeMs: Long, eventUptimeMs: Long, speed: Float): Long =
        positionMs - ((nowUptimeMs - eventUptimeMs).coerceAtLeast(0L) * speed.toDouble()).roundToLong()

    /** First tappable (not locked) token index at or after [from]; `tokens.size` if none. */
    fun nextTappable(draft: SyncDraft, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i < draft.tokens.size && draft.lines[draft.tokens[i].line].locked) i++
        return min(i, draft.tokens.size)
    }

    fun currentLineIndex(draft: SyncDraft): Int =
        draft.tokens.getOrNull(nextTappable(draft, draft.cursor))?.line ?: draft.lines.lastIndex

    /** "Skip this line" is offered only when this line and the next both have an anchor. */
    fun canSkipLine(draft: SyncDraft): Boolean {
        val index = nextTappable(draft, draft.cursor)
        if (index >= draft.tokens.size) return false
        val lineIndex = draft.tokens[index].line
        val next = nextOpenLine(draft, lineIndex) ?: return false
        return draft.lines[lineIndex].anchorMs != null && draft.lines[next].anchorMs != null
    }

    fun setNudge(draft: SyncDraft, nudgeMs: Int): SyncDraft =
        draft.copy(nudgeMs = nudgeMs.coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS))

    /** On save: `offset − nudge × 0.5`, clamped to 0..400 (halved so one odd song can't swing it). */
    fun learnedOffsetMs(currentOffsetMs: Int, nudgeMs: Int): Int =
        (currentOffsetMs - nudgeMs * 0.5).roundToInt().coerceIn(0, MAX_OFFSET_MS)

    /** Built start of token [index], or null while it is untapped. */
    fun builtStartMs(draft: SyncDraft, index: Int, offsetMs: Int): Long? {
        val token = draft.tokens[index]
        val raw = token.rawStartMs ?: return null
        return raw - (if (token.exact) 0L else offsetShift(offsetMs, token.startSpeed)) + draft.nudgeMs
    }

    /** Built end of a held word, or null if it wasn't held. */
    fun builtHeldEndMs(draft: SyncDraft, index: Int, offsetMs: Int): Long? {
        val token = draft.tokens[index]
        val raw = token.rawEndMs ?: return null
        return raw - (if (token.exact) 0L else offsetShift(offsetMs, token.endSpeed)) + draft.nudgeMs
    }

    private fun offsetShift(offsetMs: Int, speed: Float): Long = (offsetMs * speed.toDouble()).roundToLong()

    private fun scaled(ms: Long, speed: Float): Long = (ms * speed.toDouble()).roundToLong()

    private fun isLocked(draft: SyncDraft, index: Int): Boolean = draft.lines[draft.tokens[index].line].locked

    /** Last stamped, tappable token strictly before [index] (and at or after [floor]). */
    private fun lastStampedBefore(draft: SyncDraft, index: Int, floor: Int = 0): Int? {
        var i = min(index, draft.tokens.size) - 1
        while (i >= floor.coerceAtLeast(0)) {
            if (!isLocked(draft, i) && draft.tokens[i].rawStartMs != null) return i
            i--
        }
        return null
    }

    /** Next line after [lineIndex] that is tapped by the user (not locked, has words). */
    private fun nextOpenLine(draft: SyncDraft, lineIndex: Int): Int? {
        for (j in lineIndex + 1 until draft.lines.size) {
            val line = draft.lines[j]
            if (!line.locked && line.tokenCount > 0) return j
        }
        return null
    }

    private fun previousOpenLine(draft: SyncDraft, lineIndex: Int): Int? {
        for (j in lineIndex - 1 downTo 0) {
            val line = draft.lines[j]
            if (!line.locked && line.tokenCount > 0) return j
        }
        return null
    }

    private fun SyncToken.cleared(): SyncToken =
        copy(rawStartMs = null, startSpeed = 1f, rawEndMs = null, endSpeed = 1f, exact = false)

    private fun PersistentList<SyncLine>.withSkipped(indices: Collection<Int>, skipped: Boolean): PersistentList<SyncLine> {
        if (indices.none { this[it].skipped != skipped && !this[it].locked }) return this
        return mutate { list ->
            for (i in indices) if (!list[i].locked && list[i].skipped != skipped) list[i] = list[i].copy(skipped = skipped)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Reducers
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * "The next word starts now." Stamps the token at the cursor and moves on.
     * Starts stay strictly increasing (≥ previous + 10 ms) and, when the next line is already
     * timed (Fix a line), stay before it. With [scopeLine] set, taps outside that line are ignored.
     */
    fun tap(
        draft: SyncDraft,
        rawStartMs: Long,
        speed: Float,
        offsetMs: Int,
        scopeLine: Int? = null,
    ): SyncStep {
        val index = nextTappable(draft, draft.cursor)
        if (index >= draft.tokens.size) return SyncStep(draft)
        val lineIndex = draft.tokens[index].line
        if (scopeLine != null && lineIndex != scopeLine) return SyncStep(draft)

        val shift = offsetShift(offsetMs, speed) - draft.nudgeMs // built = raw − shift
        var built = rawStartMs - shift
        val previous = lastStampedBefore(draft, index)
        val floor = previous?.let { builtStartMs(draft, it, offsetMs)!! + MIN_TAP_GAP_MS } ?: 0L
        if (built < floor) built = floor

        var pastNextLine = false
        val nextLine = nextOpenLine(draft, lineIndex)
        val nextLineStart = nextLine?.let { builtStartMs(draft, draft.lines[it].firstToken, offsetMs) }
        if (nextLineStart != null && built >= nextLineStart - NEXT_LINE_GUARD_MS) {
            pastNextLine = true
            built = max(floor, nextLineStart - NEXT_LINE_GUARD_MS - 1)
        }

        val line = draft.lines[lineIndex]
        val anchor = line.anchorMs
        val beforeAnchor = index == line.firstToken && anchor != null && built < anchor - EARLY_TAP_MS

        val stamped = draft.tokens[index].copy(
            rawStartMs = built + shift,
            startSpeed = speed,
            rawEndMs = null,
            endSpeed = 1f,
            exact = false,
        )
        val next = draft.copy(tokens = draft.tokens.replacingAt(index, stamped))
        return SyncStep(
            draft = next.copy(cursor = nextTappable(next, index + 1)),
            pastNextLine = pastNextLine,
            tapBeforeAnchor = beforeAnchor,
        )
    }

    /** Release after a hold (≥ [HOLD_THRESHOLD_MS]): stamps the end of the held word. */
    fun release(draft: SyncDraft, tokenIndex: Int, rawEndMs: Long, speed: Float, offsetMs: Int): SyncDraft {
        val token = draft.tokens.getOrNull(tokenIndex) ?: return draft
        if (token.rawStartMs == null || token.exact || isLocked(draft, tokenIndex)) return draft
        val start = builtStartMs(draft, tokenIndex, offsetMs) ?: return draft
        val builtEnd = rawEndMs - offsetShift(offsetMs, speed) + draft.nudgeMs
        if (builtEnd < start + MIN_WORD_MS) return draft
        return draft.copy(tokens = draft.tokens.replacingAt(tokenIndex, token.copy(rawEndMs = rawEndMs, endSpeed = speed)))
    }

    /**
     * Removes the last tap and seeks a little before it: 2 s (× speed) before the previous
     * stamped word, or 3 s before the line anchor / removed tap when there is none.
     * Undoing into a roughly-timed ("skipped") line undoes the whole skip.
     * With [scopeLine] set (Fix a line), only taps inside that line are undone.
     */
    fun undo(draft: SyncDraft, speed: Float, offsetMs: Int, scopeLine: Int? = null): SyncStep {
        val floor = scopeLine?.let { draft.lines.getOrNull(it)?.firstToken } ?: 0
        val popped = lastStampedBefore(draft, draft.cursor, floor) ?: return SyncStep(draft)
        val lineIndex = draft.tokens[popped].line
        val line = draft.lines[lineIndex]
        val removedStart = builtStartMs(draft, popped, offsetMs)!!
        val undoesSkip = line.skipped && draft.tokens[popped].exact
        // Undoing a rough skip removes the whole roughly-timed run, not just its last word.
        val clearFrom = if (undoesSkip) {
            var j = popped
            val lowest = max(line.firstToken, floor)
            while (j - 1 >= lowest && draft.tokens[j - 1].exact && draft.tokens[j - 1].rawStartMs != null) j--
            j
        } else {
            popped
        }
        val clearUntil = max(draft.cursor, popped + 1).coerceAtMost(draft.tokens.size)

        var cleared = 0
        val tokens = draft.tokens.mutate { list ->
            for (i in clearFrom until clearUntil) {
                if (!isLocked(draft, i) && list[i].rawStartMs != null) {
                    list[i] = list[i].cleared()
                    cleared++
                }
            }
        }
        val lines = if (undoesSkip) draft.lines.withSkipped(listOf(lineIndex), false) else draft.lines
        val base = draft.copy(tokens = tokens, lines = lines)
        val result = base.copy(cursor = nextTappable(base, clearFrom))

        val previous = lastStampedBefore(result, result.cursor)
        val seek = if (previous != null) {
            builtStartMs(result, previous, offsetMs)!! - scaled(UNDO_PREROLL_MS, speed)
        } else {
            (line.anchorMs ?: removedStart) - scaled(ANCHOR_PREROLL_MS, speed)
        }
        return SyncStep(result, seekToMs = seek.coerceAtLeast(0L), clearedCount = cleared)
    }

    /**
     * Back [ms] (default 5 s) from [positionMs]: forgets every tap whose built start is at or
     * after the new position and moves the cursor to the first forgotten word. Also used for
     * backward seeks on the editor's seek bar. With [scopeLine] set, only that line is touched.
     */
    fun rewind(
        draft: SyncDraft,
        positionMs: Long,
        offsetMs: Int,
        ms: Long = REWIND_MS,
        scopeLine: Int? = null,
    ): SyncStep {
        val newPosition = (positionMs - ms).coerceAtLeast(0L)
        val range = scopeLine?.let { draft.lines.getOrNull(it) }?.let { it.firstToken until it.endToken }
            ?: draft.tokens.indices
        var first = -1
        var cleared = 0
        val tokens = draft.tokens.mutate { list ->
            for (i in range) {
                if (isLocked(draft, i)) continue
                val start = builtStartMs(draft, i, offsetMs) ?: continue
                if (start >= newPosition) {
                    list[i] = list[i].cleared()
                    cleared++
                    if (first < 0) first = i
                }
            }
        }
        if (cleared == 0) return SyncStep(draft, seekToMs = newPosition)
        val base = draft.copy(tokens = tokens)
        return SyncStep(
            draft = base.copy(cursor = nextTappable(base, min(draft.cursor, first))),
            seekToMs = newPosition,
            clearedCount = cleared,
        )
    }

    /**
     * Tap on a line at or before the current one: forget line [lineIndex] onwards and start
     * tapping it again, 2 s (× speed) before its first word / anchor / the previous line's end.
     */
    fun jumpToLine(draft: SyncDraft, lineIndex: Int, speed: Float, offsetMs: Int): SyncStep {
        val line = draft.lines.getOrNull(lineIndex) ?: return SyncStep(draft)
        if (line.locked || line.tokenCount == 0 || lineIndex > currentLineIndex(draft)) return SyncStep(draft)
        val target = lineSeekBase(draft, lineIndex, offsetMs) - scaled(UNDO_PREROLL_MS, speed)
        return clearLines(draft, lineIndex until draft.lines.size, target.coerceAtLeast(0L))
    }

    /** Fix a line from Preview: forget only line [lineIndex]; later lines keep their timing. */
    fun fixLine(draft: SyncDraft, lineIndex: Int, speed: Float, offsetMs: Int): SyncStep {
        val line = draft.lines.getOrNull(lineIndex) ?: return SyncStep(draft)
        if (line.locked || line.tokenCount == 0) return SyncStep(draft)
        val target = lineSeekBase(draft, lineIndex, offsetMs) - scaled(FIX_LINE_PREROLL_MS, speed)
        return clearLines(draft, lineIndex..lineIndex, target.coerceAtLeast(0L))
    }

    private fun clearLines(draft: SyncDraft, lineRange: IntRange, seekToMs: Long): SyncStep {
        val firstToken = draft.lines[lineRange.first].firstToken
        val lastToken = draft.lines[lineRange.last].endToken
        var cleared = 0
        val tokens = draft.tokens.mutate { list ->
            for (i in firstToken until lastToken) {
                if (!isLocked(draft, i) && list[i].rawStartMs != null) {
                    list[i] = list[i].cleared()
                    cleared++
                }
            }
        }
        val base = draft.copy(tokens = tokens, lines = draft.lines.withSkipped(lineRange.toList(), false))
        return SyncStep(
            draft = base.copy(cursor = nextTappable(base, firstToken)),
            seekToMs = seekToMs,
            clearedCount = cleared,
        )
    }

    /** First word's start, else the line anchor, else where the previous line ends, else 0. */
    private fun lineSeekBase(draft: SyncDraft, lineIndex: Int, offsetMs: Int): Long {
        val line = draft.lines[lineIndex]
        builtStartMs(draft, line.firstToken, offsetMs)?.let { return it }
        line.anchorMs?.let { return it }
        val previous = previousOpenLine(draft, lineIndex) ?: return 0L
        val timing = resolveTiming(draft, offsetMs) ?: return 0L
        val prevLine = draft.lines[previous]
        return (prevLine.firstToken until prevLine.endToken).maxOf { timing.endsMs[it] }
    }

    /**
     * "Skip this line (time it roughly)": spreads the line's remaining words by character count
     * between the last tap in the line (or its anchor) and the next line's anchor.
     */
    fun skipLine(draft: SyncDraft, offsetMs: Int): SyncStep {
        if (!canSkipLine(draft)) return SyncStep(draft)
        val index = nextTappable(draft, draft.cursor)
        val lineIndex = draft.tokens[index].line
        val line = draft.lines[lineIndex]
        val nextAnchor = draft.lines[nextOpenLine(draft, lineIndex)!!].anchorMs!!
        val lastTapped = lastStampedBefore(draft, index, line.firstToken)
        val from = lastTapped?.let { builtStartMs(draft, it, offsetMs)!! } ?: line.anchorMs!!
        val to = max(nextAnchor, from)

        val indices = (index until line.endToken).toList()
        val leftWeight = lastTapped?.let { weight(draft.tokens[it].text) } ?: 0L
        val total = leftWeight + indices.sumOf { weight(draft.tokens[it].text) }
        var accumulated = leftWeight
        val tokens = draft.tokens.mutate { list ->
            for (i in indices) {
                val start = from + (to - from) * accumulated / total
                accumulated += weight(list[i].text)
                list[i] = list[i].copy(
                    rawStartMs = start - draft.nudgeMs,
                    startSpeed = 1f,
                    rawEndMs = null,
                    endSpeed = 1f,
                    exact = true,
                )
            }
        }
        val base = draft.copy(tokens = tokens, lines = draft.lines.withSkipped(listOf(lineIndex), true))
        return SyncStep(base.copy(cursor = nextTappable(base, line.endToken)))
    }

    /**
     * "Time the rest roughly": spreads every untapped word after the cursor evenly between the
     * last word's end and the song's end − 500 ms, at most 600 ms per word.
     */
    fun fillRest(draft: SyncDraft, offsetMs: Int): SyncStep {
        val start = nextTappable(draft, draft.cursor)
        val remaining = (start until draft.tokens.size).filter {
            !isLocked(draft, it) && draft.tokens[it].rawStartMs == null
        }
        if (remaining.isEmpty()) return SyncStep(draft.copy(cursor = draft.tokens.size))

        val lastStamped = lastStampedBefore(draft, start)
        val from: Long = if (lastStamped != null) {
            resolveTiming(draft, offsetMs)?.endsMs?.get(lastStamped)
                ?: (builtStartMs(draft, lastStamped, offsetMs)!! + DEFAULT_GAP_MS)
        } else {
            draft.lines[draft.tokens[start].line].anchorMs ?: 0L
        }
        val limit = if (hasKnownDuration(draft)) {
            draft.durationMs - TAIL_MARGIN_MS
        } else {
            from + ROUGH_WORD_MAX_MS * remaining.size
        }
        val perWord = ((limit - from) / remaining.size).coerceIn(1L, ROUGH_WORD_MAX_MS)
        val tokens = draft.tokens.mutate { list ->
            remaining.forEachIndexed { k, i ->
                list[i] = list[i].copy(
                    rawStartMs = from + perWord * k - draft.nudgeMs,
                    startSpeed = 1f,
                    rawEndMs = null,
                    endSpeed = 1f,
                    exact = true,
                )
            }
        }
        val lines = draft.lines.withSkipped(remaining.map { draft.tokens[it].line }.distinct(), true)
        return SyncStep(draft.copy(tokens = tokens, lines = lines, cursor = draft.tokens.size))
    }

    /** "Start over" / "Tap Start to redo it": forget every tap (locked lines are kept). */
    fun clearAll(draft: SyncDraft): SyncDraft {
        val tokens = draft.tokens.mutate { list ->
            for (i in list.indices) if (!isLocked(draft, i) && list[i].rawStartMs != null) list[i] = list[i].cleared()
        }
        val base = draft.copy(tokens = tokens, lines = draft.lines.withSkipped(draft.lines.indices.toList(), false))
        return base.copy(cursor = nextTappable(base, 0))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Timing resolution and the finished document
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun hasKnownDuration(draft: SyncDraft): Boolean =
        draft.durationMs in MIN_KNOWN_DURATION_MS..MAX_DOC_DURATION_MS

    private fun durationCap(draft: SyncDraft): Long =
        if (hasKnownDuration(draft)) draft.durationMs else MAX_DOC_DURATION_MS

    /**
     * Built start and end for every token. Untapped words are filled in roughly: a line anchor
     * when it fits, else by character count between the neighbouring taps, else ≤ 600 ms apart.
     * Returns null while no tappable word has been tapped.
     */
    fun resolveTiming(draft: SyncDraft, offsetMs: Int): SyncTiming? {
        val tokens = draft.tokens
        val n = tokens.size
        if (n == 0) return null
        val cap = durationCap(draft)
        val startCeiling = cap - 2
        val endCeiling = cap - 1

        val starts = LongArray(n)
        val known = BooleanArray(n)
        val rough = HashSet<Int>()
        var anyOpenStamped = false
        var anyOpen = false
        for (i in 0 until n) {
            val locked = isLocked(draft, i)
            if (!locked) anyOpen = true
            val start = builtStartMs(draft, i, offsetMs) ?: continue
            starts[i] = start.coerceIn(0L, startCeiling)
            known[i] = true
            if (!locked) anyOpenStamped = true
        }
        if (anyOpen && !anyOpenStamped) return null
        draft.lines.forEachIndexed { index, line -> if (line.skipped) rough += index }
        keepLineOrder(draft, starts, known)

        // Positions that take part in filling: everything except already-timed locked words.
        val order = IntArray(n).let { buffer ->
            var size = 0
            for (i in 0 until n) if (!(known[i] && isLocked(draft, i))) buffer[size++] = i
            buffer.copyOf(size)
        }
        val nextKnownPosition = IntArray(order.size)
        var upcoming = -1
        for (p in order.indices.reversed()) {
            nextKnownPosition[p] = upcoming
            if (known[order[p]]) upcoming = p
        }

        // Anchors of untapped lines, where they fit between the surrounding taps.
        var previousKnown: Long? = null
        for (p in order.indices) {
            val i = order[p]
            if (known[i]) {
                previousKnown = starts[i]
                continue
            }
            val lineIndex = tokens[i].line
            val line = draft.lines[lineIndex]
            val anchor = line.anchorMs ?: continue
            if (i != line.firstToken) continue
            val a = anchor.coerceIn(0L, startCeiling)
            val nextPosition = nextKnownPosition[p]
            val next = if (nextPosition >= 0) starts[order[nextPosition]] else null
            if ((previousKnown == null || a >= previousKnown) && (next == null || a <= next)) {
                starts[i] = a
                known[i] = true
                rough += lineIndex
                previousKnown = a
            }
        }

        // Fill every remaining run of untapped words.
        var p = 0
        while (p < order.size) {
            if (known[order[p]]) {
                p++
                continue
            }
            var q = p
            while (q + 1 < order.size && !known[order[q + 1]]) q++
            val left = if (p > 0) order[p - 1] else -1
            val right = if (q + 1 < order.size) order[q + 1] else -1
            val count = q - p + 1
            when {
                left >= 0 && right >= 0 -> {
                    val from = starts[left]
                    val to = max(starts[right], from)
                    val leftWeight = weight(tokens[left].text)
                    var total = leftWeight
                    for (k in p..q) total += weight(tokens[order[k]].text)
                    var accumulated = leftWeight
                    for (k in p..q) {
                        starts[order[k]] = from + (to - from) * accumulated / total
                        accumulated += weight(tokens[order[k]].text)
                    }
                }
                left >= 0 -> {
                    val from = starts[left]
                    val perWord = ((cap - TAIL_MARGIN_MS - from) / (count + 1)).coerceIn(1L, ROUGH_WORD_MAX_MS)
                    for (k in 0 until count) starts[order[p + k]] = from + perWord * (k + 1)
                }
                right >= 0 -> {
                    val to = starts[right]
                    val perWord = (to / (count + 1)).coerceIn(1L, ROUGH_WORD_MAX_MS)
                    for (k in 0 until count) starts[order[p + k]] = (to - perWord * (count - k)).coerceAtLeast(0L)
                }
                else -> for (k in 0 until count) starts[order[p + k]] = 0L
            }
            for (k in p..q) {
                known[order[k]] = true
                rough += tokens[order[k]].line
            }
            p = q + 1
        }
        for (i in 0 until n) starts[i] = starts[i].coerceIn(0L, startCeiling)
        keepLineOrder(draft, starts, known)

        // Ends.
        val ends = LongArray(n)
        val nextLineStart = arrayOfNulls<Long>(draft.lines.size)
        var following: Long? = null
        for (lineIndex in draft.lines.indices.reversed()) {
            val line = draft.lines[lineIndex]
            nextLineStart[lineIndex] = following
            if (!line.locked && line.tokenCount > 0) following = starts[line.firstToken]
        }
        for ((lineIndex, line) in draft.lines.withIndex()) {
            if (line.tokenCount == 0) continue
            val range = line.firstToken until line.endToken
            val loadedIntact = range.all { tokens[it].exact && tokens[it].rawEndMs != null }
            if (loadedIntact) {
                for (i in range) {
                    val end = builtHeldEndMs(draft, i, offsetMs)!!
                    ends[i] = end.coerceAtMost(endCeiling).coerceAtLeast(starts[i] + 1)
                }
                continue
            }
            val derived = deriveEnds(
                starts = range.map { starts[it] },
                heldEnds = range.map { builtHeldEndMs(draft, it, offsetMs) },
                nextLineStartMs = if (line.locked) null else nextLineStart[lineIndex],
                endCeilingMs = endCeiling,
            )
            range.forEachIndexed { k, i -> ends[i] = derived[k] }
        }
        return SyncTiming(starts, ends, rough)
    }

    /** Word starts never go backwards inside a line. */
    private fun keepLineOrder(draft: SyncDraft, starts: LongArray, known: BooleanArray) {
        for (line in draft.lines) {
            var previous = Long.MIN_VALUE
            for (i in line.firstToken until line.endToken) {
                if (!known[i]) continue
                if (starts[i] < previous) starts[i] = previous
                previous = starts[i]
            }
        }
    }

    /**
     * Word ends for one line (spec §3.4), given non-decreasing [starts] each ≤ [endCeilingMs] − 1.
     * - held word → its held end;
     * - otherwise the next word's start when it follows within 4 s (a continuous sweep),
     *   else `start + clamp(median gap, 250, 1200)`;
     * - last word → `start + clamp(2 × median gap, 400, 2000)`.
     * Then `≤ nextLineStart − 1`, `≥ start + 40`, `≤ endCeiling`, and always `> start`.
     */
    fun deriveEnds(
        starts: List<Long>,
        heldEnds: List<Long?>,
        nextLineStartMs: Long?,
        endCeilingMs: Long,
    ): List<Long> {
        val n = starts.size
        if (n == 0) return emptyList()
        val median = medianGap(starts)
        return List(n) { i ->
            val start = starts[i]
            var end = heldEnds.getOrNull(i) ?: if (i < n - 1) {
                val gap = starts[i + 1] - start
                if (gap <= SUSTAIN_GAP_MS) starts[i + 1] else start + median.coerceIn(250L, 1_200L)
            } else {
                start + (2 * median).coerceIn(400L, 2_000L)
            }
            if (nextLineStartMs != null && nextLineStartMs > starts[0]) end = min(end, nextLineStartMs - 1)
            end = max(end, start + MIN_WORD_MS)
            end = min(end, endCeilingMs)
            max(end, start + 1)
        }
    }

    private fun medianGap(starts: List<Long>): Long {
        if (starts.size < 2) return DEFAULT_GAP_MS
        val gaps = (0 until starts.size - 1).map { starts[it + 1] - starts[it] }.sorted()
        val middle = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2
    }

    /** The finished lyrics, `metadata.source = "user"`, always passing [LyricsDocCodec.isValid]. */
    fun toLyricsDoc(draft: SyncDraft, offsetMs: Int): Result<LyricsDoc> = buildResult(draft, offsetMs).map { it.doc }

    fun buildResult(draft: SyncDraft, offsetMs: Int): Result<SyncResult> {
        val timing = resolveTiming(draft, offsetMs)
            ?: return Result.failure(IllegalStateException("No words have been tapped yet"))
        val (voices, voiceFor) = sanitizeVoices(draft)

        data class Built(val line: TimedLine, val rough: Boolean)
        val built = draft.lines.withIndex().filter { it.value.tokenCount > 0 }.map { (lineIndex, line) ->
            val range = line.firstToken until line.endToken
            val syllables = range.map { i ->
                TimedSyllable(
                    startMs = timing.startsMs[i],
                    durationMs = timing.endsMs[i] - timing.startsMs[i],
                    text = draft.tokens[i].text,
                )
            }
            Built(
                line = TimedLine(
                    startMs = syllables.first().startMs,
                    endMs = syllables.maxOf { it.startMs + it.durationMs },
                    text = syllables.joinToString("") { it.text },
                    voiceId = voiceFor(line.voiceId),
                    syllables = syllables,
                ),
                rough = lineIndex in timing.roughLines,
            )
        }.sortedBy { it.line.startMs } // stable: text order breaks ties
        if (built.isEmpty()) return Result.failure(IllegalStateException("The draft has no words"))

        val doc = LyricsDoc(
            metadata = LyricsMetadata(
                title = draft.title,
                artist = draft.artist,
                album = draft.album,
                durationMs = draft.durationMs.takeIf { hasKnownDuration(draft) },
                source = SOURCE_USER,
            ),
            voices = voices,
            lines = built.map { it.line },
        )
        if (!LyricsDocCodec.isValid(doc)) {
            val badIndex = doc.lines.indexOfFirst { !LyricsDocCodec.isValid(doc.copy(lines = listOf(it))) }
            val message = "Tap-sync produced an invalid lyrics document " +
                "(first invalid line $badIndex: ${doc.lines.getOrNull(badIndex)})"
            Timber.w("%s", message)
            return Result.failure(IllegalStateException(message))
        }
        val roughIndices = built.withIndex().filter { it.value.rough }.map { it.index }.toSet()
        return Result.success(SyncResult(doc, roughIndices))
    }

    private fun sanitizeVoices(draft: SyncDraft): Pair<List<Voice>, (String) -> String> {
        val voices = LinkedHashMap<String, Voice>()
        for (voice in draft.voices) {
            if (voices.size >= MAX_VOICES) break
            if (voice.id.isBlank() || voice.id in voices) continue
            voices[voice.id] = if (voice.role in VOICE_ROLES) voice else voice.copy(role = "lead")
        }
        for (line in draft.lines) {
            if (voices.size >= MAX_VOICES) break
            if (line.tokenCount > 0 && line.voiceId.isNotBlank() && line.voiceId !in voices) {
                val role = line.voiceId.takeIf { it in VOICE_ROLES } ?: "lead"
                voices[line.voiceId] = Voice(id = line.voiceId, role = role)
            }
        }
        if (voices.isEmpty()) voices[LEAD_VOICE_ID] = Voice()
        val fallback = voices.values.firstOrNull { it.role == "lead" }?.id ?: voices.keys.first()
        return voices.values.toList() to { id: String -> if (id in voices) id else fallback }
    }

    /** Structural sanity check, used when a stored draft is read back. */
    fun isConsistent(draft: SyncDraft): Boolean {
        if (draft.lines.isEmpty() || draft.tokens.isEmpty()) return false
        if (draft.cursor !in 0..draft.tokens.size) return false
        var expectedFirst = 0
        for ((lineIndex, line) in draft.lines.withIndex()) {
            if (line.firstToken != expectedFirst || line.tokenCount <= 0) return false
            if (line.endToken > draft.tokens.size) return false
            val text = StringBuilder()
            for (i in line.firstToken until line.endToken) {
                val token = draft.tokens[i]
                if (token.line != lineIndex || token.text.isEmpty()) return false
                if (token.startSpeed.isNaN() || token.startSpeed <= 0f || token.endSpeed.isNaN() || token.endSpeed <= 0f) return false
                if (line.locked && token.rawStartMs == null) return false
                text.append(token.text)
            }
            if (text.toString() != line.text) return false
            expectedFirst = line.endToken
        }
        return expectedFirst == draft.tokens.size
    }
}
