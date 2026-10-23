package com.theveloper.pixelplay.presentation.lyrics.sing

import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.Row

/**
 * Where to start the vocals-to-instrumental crossfade of a Sing switch so it lands in a gap between
 * sung words instead of on top of one. Pure: the lyrics' word timing in, a start time and a fade
 * length out; the player side ([com.theveloper.pixelplay.data.service.player.InstrumentalCrossfadeController])
 * does the waiting and the volume ramp.
 *
 * The rule (identical on iOS, `SingGapIndex`): look at the next [MAX_WAIT_MS] + [MAX_FADE_MS] of the
 * song, take the earliest silent window of at least [MIN_GAP_MS] that starts within [MAX_WAIT_MS], and
 * fade over the window (clamped to [MIN_FADE_MS]..[MAX_FADE_MS]). Without such a window, start almost
 * at once with the longest fade.
 *
 * What counts as sung is deliberately cautious, because a gap that is not really a gap only costs a
 * vocal that fades out under a word, while a missed gap costs nothing:
 * - a syllable with an explicit end (document lyrics, cloud-aligned lyrics) is sung for exactly that;
 * - any other syllable is sung until the next syllable of its line starts, and the last one until the
 *   line ends (when the end is explicit) or the next line starts, so inferred ends never open a gap;
 * - a line without word timing is sung from its start to its end (explicit) or to the next line;
 * - an interlude (`Row.Interlude`, a gap of 9 s or more) is silent.
 * Background and duet lines count like any other.
 */
object VocalGapFinder {
    /** The longest the switch may wait for a gap. */
    const val MAX_WAIT_MS = 600L

    /** The shortest silence worth fading in. */
    const val MIN_GAP_MS = 300L

    const val MAX_FADE_MS = 700
    const val MIN_FADE_MS = 300

    /** With no gap to aim for, start this long from now (the plan's own computing time). */
    const val SAFETY_LEAD_MS = 60L

    /**
     * @property startAtLyricsMs when to start fading, in lyrics time (player position plus the lyrics
     *   sync offset; subtract the offset to get the player position).
     * @property inGap the start is inside silence (the vocals have stopped), not a fallback.
     */
    data class Plan(val startAtLyricsMs: Long, val fadeMs: Int, val inGap: Boolean)

    /** [prepared] null (plain lyrics, none, still building) plans a fade a moment from now. */
    fun plan(prepared: PreparedLyrics?, nowLyricsMs: Long): Plan {
        val fallback = Plan(nowLyricsMs + SAFETY_LEAD_MS, MAX_FADE_MS, inGap = false)
        if (prepared == null) return fallback
        val horizon = nowLyricsMs + MAX_WAIT_MS + MAX_FADE_MS
        val sung = voiced(prepared, nowLyricsMs, horizon)
        var cursor = nowLyricsMs
        // Silent windows are the stretches between sung ones (and after the last), up to the horizon.
        val windows = ArrayList<LongRange>(sung.size + 1)
        for (range in sung) {
            if (range.first > cursor) windows += cursor until range.first
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < horizon) windows += cursor until horizon
        for (window in windows) {
            val start = window.first
            if (start > nowLyricsMs + MAX_WAIT_MS) break
            val length = window.last + 1 - start
            if (length < MIN_GAP_MS) continue
            return Plan(start, length.coerceIn(MIN_FADE_MS.toLong(), MAX_FADE_MS.toLong()).toInt(), inGap = true)
        }
        return fallback
    }

    /**
     * The sung time inside `[fromMs, toMs)`: sorted, merged, non-overlapping ranges, each as
     * `start until end` (so `last + 1` is the end).
     */
    internal fun voiced(prepared: PreparedLyrics, fromMs: Long, toMs: Long): List<LongRange> {
        if (toMs <= fromMs) return emptyList()
        val raw = ArrayList<LongRange>(prepared.lines.size * 2)
        val lines = prepared.lines
        for (i in lines.indices) {
            val line = lines[i]
            val nextStart = nextLineStart(prepared, i)
            val syllables = line.syllables
            if (syllables.isNullOrEmpty()) {
                val end = if (line.endIsExplicit) line.endMs else maxOf(nextStart ?: line.endMs, line.startMs + 1L)
                raw.addClipped(line.startMs, end, fromMs, toMs)
                continue
            }
            for (k in syllables.indices) {
                val syllable = syllables[k]
                val end = when {
                    syllable.endIsExplicit -> syllable.endMs
                    k + 1 < syllables.size -> maxOf(syllables[k + 1].startMs, syllable.startMs + 1L)
                    line.endIsExplicit -> maxOf(line.endMs, syllable.endMs)
                    else -> maxOf(nextStart ?: syllable.endMs, syllable.endMs)
                }
                raw.addClipped(syllable.startMs, end, fromMs, toMs)
            }
        }
        val interludes = prepared.rows.filterIsInstance<Row.Interlude>()
        return merge(raw).flatMap { subtract(it, interludes) }
    }

    /** Start of the first line after line [index] that starts later (ties and earlier lines never end it). */
    private fun nextLineStart(prepared: PreparedLyrics, index: Int): Long? {
        val lines = prepared.lines
        val start = lines[index].startMs
        for (j in index + 1 until lines.size) {
            if (lines[j].startMs > start) return lines[j].startMs
        }
        return null
    }

    private fun MutableList<LongRange>.addClipped(start: Long, end: Long, fromMs: Long, toMs: Long) {
        val s = maxOf(start, fromMs)
        val e = minOf(end, toMs)
        if (e > s) add(s until e)
    }

    private fun merge(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<LongRange>(sorted.size)
        var start = sorted[0].first
        var end = sorted[0].last + 1
        for (k in 1 until sorted.size) {
            val range = sorted[k]
            if (range.first <= end) {
                end = maxOf(end, range.last + 1)
            } else {
                out += start until end
                start = range.first
                end = range.last + 1
            }
        }
        out += start until end
        return out
    }

    private fun subtract(range: LongRange, interludes: List<Row.Interlude>): List<LongRange> {
        var pieces = listOf(range)
        for (gap in interludes) {
            pieces = pieces.flatMap { piece ->
                val pieceEnd = piece.last + 1
                if (gap.endMs <= piece.first || gap.startMs >= pieceEnd) {
                    listOf(piece)
                } else {
                    buildList {
                        if (gap.startMs > piece.first) add(piece.first until gap.startMs)
                        if (gap.endMs < pieceEnd) add(gap.endMs until pieceEnd)
                    }
                }
            }
        }
        return pieces
    }
}
