package com.theveloper.pixelplay.presentation.lyrics.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList

/**
 * The immutable, render-ready shape of a song's synced lyrics, built once per lyrics change by
 * [PreparedLyricsBuilder] (off the main thread) and handed to `KaraokeLyricsView` and
 * `LyricsEngine`. Nothing in here changes per frame: every per-frame value lives in the engine.
 *
 * - [lines] are sorted by [PreparedLine.startMs] and [PreparedLine.index] is the position in this
 *   list. [startsSorted] mirrors their start times for the engine's binary search.
 * - [rows] is the visual order: every line appears exactly once, background vocals sit directly
 *   above or below the line they belong to, and interlude pseudo-rows sit before the group they
 *   lead into.
 */
@Immutable
data class PreparedLyrics(
    val lines: PersistentList<PreparedLine>,
    val rows: PersistentList<Row>,
    val hasWordTiming: Boolean,
    val hasDuet: Boolean,
    val startsSorted: LongArray,
) {
    /** Longest `endMs - startMs` of any line; bounds the backward scan of the hot-set lookup. */
    val maxLineDurationMs: Long = lines.maxOfOrNull { it.endMs - it.startMs }?.coerceAtLeast(0L) ?: 0L

    /** Latest end of any line: once playback passes it the song's lyrics are over. */
    val lastEndMs: Long = lines.maxOfOrNull { it.endMs } ?: 0L

    // LongArray has identity equality; compare contents so equal models stay equal (and Compose
    // can skip) after a rebuild that produced the same result.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedLyrics) return false
        return hasWordTiming == other.hasWordTiming &&
            hasDuet == other.hasDuet &&
            lines == other.lines &&
            rows == other.rows &&
            startsSorted.contentEquals(other.startsSorted)
    }

    override fun hashCode(): Int {
        var result = lines.hashCode()
        result = 31 * result + rows.hashCode()
        result = 31 * result + hasWordTiming.hashCode()
        result = 31 * result + hasDuet.hashCode()
        result = 31 * result + startsSorted.contentHashCode()
        return result
    }
}

/** Voice of a line. Parsed from the `voiceRole` strings `"lead"`, `"background"` and `"duet"`. */
enum class VoiceRole {
    LEAD,
    BACKGROUND,
    DUET;

    companion object {
        fun fromRole(role: String?): VoiceRole = when (role?.trim()?.lowercase()) {
            "background", "bg", "x-bg" -> BACKGROUND
            "duet" -> DUET
            else -> LEAD
        }
    }
}

/**
 * One lyric line.
 *
 * @param endMs exclusive end. Explicit when the source said so ([endIsExplicit]); otherwise
 *   inferred (next lead line's start, or an estimate for the last line / a line before an
 *   interlude).
 * @param groupLeadIndex index of the line this one is grouped under. A background vocal points
 *   at its lead line; every other line (and a background vocal with no lead to attach to)
 *   points at itself.
 * @param bgAbove for a grouped background vocal, whether it is drawn above its lead line.
 * @param syllables word timing, or `null` for a line-synced-only line. Character ranges index
 *   into [text] exactly. Characters not covered by any syllable (spaces, untimed trailing text)
 *   are drawn with the line's unsung colour.
 */
@Immutable
data class PreparedLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val endIsExplicit: Boolean,
    val text: String,
    val role: VoiceRole,
    val groupLeadIndex: Int,
    val bgAbove: Boolean,
    val syllables: PersistentList<PreparedSyllable>?,
    val translation: String?,
    val romanization: String?,
) {
    /** True for the line that owns its group: every lead and duet line, and orphan background vocals. */
    val isGroupLead: Boolean get() = groupLeadIndex == index

    /** A background vocal grouped under another line: collapsed until its group turns hot. */
    val isGroupedBackground: Boolean get() = role == VoiceRole.BACKGROUND && groupLeadIndex != index

    val hasWordTiming: Boolean get() = !syllables.isNullOrEmpty()

    /** `wordIndex` of the final word, for the "last word of the line" emphasis boost; -1 if none. */
    val lastWordIndex: Int get() = syllables?.lastOrNull()?.wordIndex ?: -1
}

/**
 * One timed syllable (or whole word) of a line.
 *
 * @param charStart inclusive index into [PreparedLine.text] of the first glyph (no whitespace).
 * @param charEnd exclusive end index; whitespace around the syllable is left out of the range.
 * @param endIsExplicit whether the source gave this syllable's end (a `LyricsDoc` duration or a
 *   `SyncedWord.endTime`). Inferred ends never drive emphasis.
 * @param wordIndex syllables sharing a `wordIndex` form one word (e.g. "su" + "gar").
 * @param emphasis the word this syllable belongs to qualifies for the long-word glow (§1.4).
 */
@Immutable
data class PreparedSyllable(
    val charStart: Int,
    val charEnd: Int,
    val startMs: Long,
    val endMs: Long,
    val endIsExplicit: Boolean,
    val wordIndex: Int,
    val emphasis: Boolean,
)

/** A visual row of the lyrics list. */
@Immutable
sealed interface Row {
    /** A lyric line, by index into [PreparedLyrics.lines]. */
    @Immutable
    data class Line(val lineIndex: Int) : Row

    /**
     * Interlude dots for the instrumental gap `[startMs, endMs)`; [endMs] is the start of the
     * group that follows. [alignEnd] right-aligns the dots when that group is a duet line.
     */
    @Immutable
    data class Interlude(val startMs: Long, val endMs: Long, val alignEnd: Boolean) : Row
}
