package com.theveloper.pixelplay.data.lyrics.sync

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LyricsTapSyncTest {

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun pasted(vararg lines: String, durationMs: Long = 240_000L): SyncDraft =
        LyricsTapSync.buildDraft("song-1", "Title", "Artist", "Album", durationMs, null, lines.joinToString("\n")).draft!!

    private fun lineSynced(vararg lines: Pair<Int, String>, durationMs: Long = 240_000L): SyncDraft =
        LyricsTapSync.buildDraft(
            "song-2", "Title", "Artist", "Album", durationMs,
            Lyrics(synced = lines.map { SyncedLine(it.first, it.second) }), null,
        ).draft!!

    private fun tapAll(
        draft: SyncDraft,
        startMs: Long = 1_000L,
        stepMs: Long = 400L,
        speed: Float = 1f,
        offsetMs: Int = 0,
    ): SyncDraft {
        var d = draft
        var t = startMs
        while (!d.isFinished) {
            d = LyricsTapSync.tap(d, t, speed, offsetMs).draft
            t += stepMs
        }
        return d
    }

    private fun taps(draft: SyncDraft, vararg rawMs: Long, speed: Float = 1f, offsetMs: Int = 0): SyncDraft =
        rawMs.fold(draft) { d, t -> LyricsTapSync.tap(d, t, speed, offsetMs).draft }

    private fun start(d: SyncDraft, i: Int, offsetMs: Int = 0) = LyricsTapSync.builtStartMs(d, i, offsetMs)

    // ── tokenize ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun tokenize_splitsOnSpacesAndKeepsTrailingSpace() {
        assertEquals(listOf("Hello ", "world"), LyricsTapSync.tokenize("Hello world"))
        assertEquals(listOf("Hello ", "big ", "world"), LyricsTapSync.tokenize("  Hello   big\tworld  "))
        assertEquals(emptyList<String>(), LyricsTapSync.tokenize("   \t "))
        assertEquals(listOf("Hi"), LyricsTapSync.tokenize("Hi"))
    }

    @Test
    fun tokenize_mergesPunctuationOnlyChunks() {
        assertEquals(listOf("wait - ", "what"), LyricsTapSync.tokenize("wait - what"))
        assertEquals(listOf("— hey ", "you"), LyricsTapSync.tokenize("— hey you"))
        assertEquals(listOf("(oh ", "yeah, ", "baby)"), LyricsTapSync.tokenize("(oh yeah, baby)"))
        assertEquals(listOf("rock & ", "roll…"), LyricsTapSync.tokenize("rock & roll…"))
        assertEquals(listOf("… …"), LyricsTapSync.tokenize("… …"))
        assertEquals(listOf("so… — ", "yes"), LyricsTapSync.tokenize("so… — yes"))
    }

    @Test
    fun tokenize_keepsHyphenatedAndApostropheWordsWhole() {
        assertEquals(listOf("twenty-one ", "rock'n'roll"), LyricsTapSync.tokenize("twenty-one rock'n'roll"))
        assertEquals(listOf("don't ", "night-time"), LyricsTapSync.tokenize("don't night-time"))
    }

    @Test
    fun tokenize_splitsCjkIntoGraphemes() {
        assertEquals(listOf("君", "が", "好", "き"), LyricsTapSync.tokenize("君が好き"))
        // Small kana and the prolonged sound mark stay with the character before.
        assertEquals(listOf("東", "京", "ラー", "メ", "ン"), LyricsTapSync.tokenize("東京ラーメン"))
        assertEquals(listOf("きょ", "う", "は"), LyricsTapSync.tokenize("きょうは"))
        // CJK punctuation joins its neighbour.
        assertEquals(listOf("「愛", "し", "て", "る」、"), LyricsTapSync.tokenize("「愛してる」、"))
        // Latin inside a CJK chunk stays whole; spaces still separate chunks.
        assertEquals(listOf("私", "は", "OK ", "で", "す。"), LyricsTapSync.tokenize("私はOK です。"))
        assertEquals(listOf("Love", "し", "て", "る"), LyricsTapSync.tokenize("Loveしてる"))
    }

    @Test
    fun tokenize_leavesHangulAndLongChunksOnWhitespace() {
        assertEquals(listOf("사랑해 ", "너를"), LyricsTapSync.tokenize("사랑해 너를"))
        val long = "あ".repeat(41)
        assertEquals(listOf(long), LyricsTapSync.tokenize(long))
        assertEquals(40, LyricsTapSync.tokenize("あ".repeat(40)).size)
    }

    @Test
    fun tokenize_keepsEmojiGraphemesIntact() {
        val family = "👨‍👩‍👧"
        val heart = "❤️"
        val thumbs = "👍🏽"
        assertEquals(listOf("君", family, "と"), LyricsTapSync.tokenize("君${family}と"))
        assertEquals(listOf("愛", heart), LyricsTapSync.tokenize("愛$heart"))
        assertEquals(listOf("$thumbs ", "yes"), LyricsTapSync.tokenize("$thumbs yes"))
        assertEquals(listOf("Café ", "ok"), LyricsTapSync.tokenize("Café ok"))
    }

    @Test
    fun tokenize_joinAlwaysEqualsNormalizedLine() {
        val samples = listOf(
            "Hello world", "  spaced   out  ", "— lead", "trail —", "a - b — c … d & e",
            "君が好き", "「愛してる」、", "私はOK です。", "사랑해 너를", "twenty-one rock'n'roll",
            "👨‍👩‍👧 君と", "ゃ小さい", "ーラ", "(oh", "…",
        )
        val pool = listOf(
            "love", "君", "が", "ラー", "ょ", "—", "&", "…", "、", "。", "「", "」", "a-b", "it's", " ", "  ", "\t",
            "❤️", "👨‍👩", "사랑", "OK", "(", ")", "é", "　",
        )
        val random = Random(7)
        val fuzz = List(400) { (0 until random.nextInt(1, 14)).joinToString("") { pool.random(random) } }
        for (line in samples + fuzz) {
            val tokens = LyricsTapSync.tokenize(line)
            assertEquals(LyricsTapSync.normalizeLine(line), tokens.joinToString(""), "join for <$line>")
            assertTrue(tokens.none { it.isEmpty() }, "empty token for <$line>")
            assertTrue(tokens.none { it.startsWith(" ") }, "leading space for <$line>")
            assertFalse(tokens.lastOrNull()?.endsWith(" ") ?: false, "last token has no trailing space for <$line>")
        }
    }

    // ── building drafts ──────────────────────────────────────────────────────────────────────

    @Test
    fun buildDraft_lineSyncedKeepsAnchorsAndTranslations() {
        val lyrics = Lyrics(
            synced = listOf(
                SyncedLine(10_000, "Hello world", translation = "Hola mundo"),
                SyncedLine(15_000, "   "),
                SyncedLine(20_000, "Bye"),
            ),
        )
        val seed = LyricsTapSync.buildDraft("id", "T", "A", "Al", 60_000L, lyrics, null)
        assertEquals(SyncDraftOrigin.LINE_SYNCED, seed.origin)
        val draft = seed.draft!!
        assertEquals(listOf("Hello world", "Bye"), draft.lines.map { it.text })
        assertEquals(listOf(10_000L, 20_000L), draft.lines.map { it.anchorMs })
        assertEquals("Hola mundo", draft.lines[0].translation)
        assertEquals(0, draft.cursor)
        assertEquals(3, draft.tappableCount)
        assertEquals(0, draft.tappedCount)
    }

    @Test
    fun buildDraft_plainDropsRomanizationAndPastedDropsBlankLines() {
        val plain = LyricsTapSync.buildDraft("id", "", "", "", 0L, Lyrics(plain = listOf("こんにちは\nKonnichiwa", "Next")), null)
        assertEquals(SyncDraftOrigin.PLAIN, plain.origin)
        assertEquals(listOf("こんにちは", "Next"), plain.draft!!.lines.map { it.text })

        val paste = pasted("  first   line ", "", "   ", "second")
        assertEquals(listOf("first line", "second"), paste.lines.map { it.text })
        assertNull(paste.lines[0].anchorMs)

        val none = LyricsTapSync.buildDraft("id", "", "", "", 0L, null, null)
        assertEquals(SyncDraftOrigin.NONE, none.origin)
        assertNull(none.draft)
        assertNull(LyricsTapSync.buildDraft("id", "", "", "", 0L, null, " \n \n").draft)
    }

    @Test
    fun buildDraft_wordSyncedOpensFinishedWithExactTimes() {
        val lyrics = Lyrics(
            synced = listOf(
                SyncedLine(
                    1_000, "Hello there",
                    words = listOf(SyncedWord(1_000, "Hel", true), SyncedWord(1_200, "lo", false), SyncedWord(1_500, "there", true)),
                ),
            ),
        )
        val seed = LyricsTapSync.buildDraft("id", "", "", "", 60_000L, lyrics, null)
        assertEquals(SyncDraftOrigin.WORD_SYNCED, seed.origin)
        val draft = seed.draft!!
        assertTrue(draft.isFinished)
        assertEquals(listOf("Hel", "lo ", "there"), draft.tokens.map { it.text })
        val doc = LyricsTapSync.toLyricsDoc(draft, offsetMs = 300).getOrThrow()
        assertEquals(listOf(1_000L, 1_200L, 1_500L), doc.lines.single().syllables.map { it.startMs })
        assertEquals("Hello there", doc.lines.single().text)
    }

    @Test
    fun buildDraft_userDocRoundTripsExactly() {
        val original = LyricsTapSync.toLyricsDoc(
            tapAll(pasted("Hello world", "Second line here"), startMs = 2_000L, stepMs = 530L, offsetMs = 120),
            offsetMs = 120,
        ).getOrThrow()
        val seed = LyricsTapSync.buildDraft("song-1", "Title", "Artist", "Album", 240_000L, original.toLyrics(), null)
        assertEquals(SyncDraftOrigin.USER_SYNCED, seed.origin)
        val draft = seed.draft!!
        assertTrue(draft.isFinished)
        val again = LyricsTapSync.toLyricsDoc(draft, offsetMs = 250).getOrThrow()
        assertEquals(original.lines, again.lines)
        assertEquals("user", again.metadata.source)
    }

    @Test
    fun buildDraft_locksTimedBackgroundLinesAndCarriesThemThrough() {
        val doc = LyricsDoc(
            metadata = LyricsMetadata(durationMs = 60_000L, source = "amll"),
            voices = listOf(Voice("lead", "lead"), Voice("bg", "background")),
            lines = listOf(
                TimedLine(1_000, 1_900, "Hi there", "lead", listOf(TimedSyllable(1_000, 400, "Hi "), TimedSyllable(1_500, 400, "there"))),
                TimedLine(1_200, 1_800, "(yeah)", "bg", listOf(TimedSyllable(1_200, 600, "(yeah)"))),
                TimedLine(3_000, 5_000, "Next line", "lead"),
            ),
        )
        val seed = LyricsTapSync.buildDraft("id", "", "", "", 60_000L, Lyrics(document = doc), null)
        assertEquals(SyncDraftOrigin.WORD_SYNCED, seed.origin)
        val cleared = LyricsTapSync.clearAll(seed.draft!!)
        assertTrue(cleared.lines[1].locked)
        assertTrue(cleared.lines[2].skipped.not())
        assertEquals(0, cleared.cursor)
        assertEquals(4, cleared.tappableCount)

        var d = taps(cleared, 1_000L, 1_500L)
        assertEquals(3, d.cursor) // skips the locked background word
        d = taps(d, 3_000L, 3_500L)
        assertTrue(d.isFinished)
        val out = LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()
        assertTrue(LyricsDocCodec.isValid(out))
        val background = out.lines.single { it.voiceId == "bg" }
        assertEquals(listOf(TimedSyllable(1_200, 600, "(yeah)")), background.syllables)
        assertEquals(listOf(1_000L, 1_200L, 3_000L), out.lines.map { it.startMs })
        // Lead line: continuous sweep into word 2, last word 2 × 500 ms (the next open line is at 3 s).
        assertEquals(listOf(500L, 1_000L), out.lines[0].syllables.map { it.durationMs })
    }

    // ── tapping ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun tap_startsAreStrictlyIncreasing() {
        var d = pasted("one two three four")
        d = LyricsTapSync.tap(d, 1_000L, 1f, 100).draft
        assertEquals(900L, start(d, 0, 100))
        d = LyricsTapSync.tap(d, 950L, 1f, 100).draft // earlier than the previous tap
        assertEquals(910L, start(d, 1, 100))
        assertEquals(1_010L, d.tokens[1].rawStartMs)
        assertEquals(2, d.cursor)

        val random = Random(3)
        var r = pasted((1..60).joinToString(" ") { "w$it" })
        var position = 5_000L
        while (!r.isFinished) {
            position += random.nextLong(-500L, 800L)
            val index = r.cursor
            r = LyricsTapSync.tap(r, position, listOf(0.5f, 0.75f, 1f).random(random), 150).draft
            if (index > 0) assertTrue(start(r, index, 150)!! >= start(r, index - 1, 150)!! + 10)
        }
    }

    @Test
    fun tap_offsetIsScaledBySpeed() {
        assertEquals(9_900L, LyricsTapSync.rawTapPositionMs(10_000L, 1_200L, 1_000L, 0.5f))
        assertEquals(9_800L, LyricsTapSync.rawTapPositionMs(10_000L, 1_200L, 1_000L, 1f))
        assertEquals(10_000L, LyricsTapSync.rawTapPositionMs(10_000L, 1_000L, 1_200L, 1f))

        // A 100 ms reaction lets the media run on by 100 ms × speed; the built start lands on the onset.
        val onset = 20_000L
        for (speed in listOf(0.5f, 0.75f, 1f)) {
            val raw = onset + (100 * speed).toLong()
            val d = LyricsTapSync.tap(pasted("word"), raw, speed, offsetMs = 100).draft
            assertEquals(onset, start(d, 0, 100), "speed $speed")
            assertEquals(speed, d.tokens[0].startSpeed)
        }
    }

    @Test
    fun tap_afterTheLastWordDoesNothing() {
        val done = tapAll(pasted("a b"))
        val step = LyricsTapSync.tap(done, 99_000L, 1f, 0)
        assertSame(done, step.draft)
    }

    @Test
    fun tap_warnsWhenFarAheadOfTheLineAnchor() {
        val d = lineSynced(10_000 to "hello there")
        assertTrue(LyricsTapSync.tap(d, 6_000L, 1f, 0).tapBeforeAnchor)
        assertFalse(LyricsTapSync.tap(d, 7_500L, 1f, 0).tapBeforeAnchor)
    }

    @Test
    fun release_stampsHeldEndsOnlyAfterTheStart() {
        var d = LyricsTapSync.tap(pasted("long note"), 1_000L, 1f, 0).draft
        assertSame(d, LyricsTapSync.release(d, 0, 1_020L, 1f, 0)) // shorter than 40 ms
        d = LyricsTapSync.release(d, 0, 2_600L, 1f, 0)
        assertEquals(2_600L, LyricsTapSync.builtHeldEndMs(d, 0, 0))
        d = LyricsTapSync.tap(d, 3_000L, 1f, 0).draft
        val doc = LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()
        assertEquals(1_600L, doc.lines.single().syllables[0].durationMs)
    }

    // ── mistakes ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun undo_popsOneTapAndSeeksBeforeThePreviousWord() {
        var d = taps(lineSynced(10_000 to "a b c", 20_000 to "d e"), 10_000L, 10_500L, 11_000L, 20_000L)
        assertEquals(4, d.cursor)

        var step = LyricsTapSync.undo(d, 1f, 0)
        d = step.draft
        assertEquals(3, d.cursor)
        assertNull(d.tokens[3].rawStartMs)
        assertEquals(9_000L, step.seekToMs)
        assertEquals(1, step.clearedCount)

        step = LyricsTapSync.undo(d, 1f, 0)
        d = step.draft
        assertEquals(8_500L, step.seekToMs)

        step = LyricsTapSync.undo(d, 0.5f, 0) // pre-roll scales with speed
        d = step.draft
        assertEquals(9_000L, step.seekToMs)

        step = LyricsTapSync.undo(d, 1f, 0) // nothing before: anchor − 3 s
        d = step.draft
        assertEquals(0, d.cursor)
        assertEquals(7_000L, step.seekToMs)

        step = LyricsTapSync.undo(d, 1f, 0)
        assertSame(d, step.draft)
        assertNull(step.seekToMs)
    }

    @Test
    fun undo_withoutAnchorFallsBackToTheRemovedTapAndClampsAtZero() {
        val d = taps(pasted("a b"), 1_000L)
        val step = LyricsTapSync.undo(d, 1f, 0)
        assertEquals(0L, step.seekToMs)
        assertEquals(0, step.draft.tappedCount)
    }

    @Test
    fun undo_afterSkipUndoesTheWholeRoughRun() {
        var d = lineSynced(10_000 to "one two three", 20_000 to "four", 30_000 to "five")
        d = taps(d, 10_000L)
        assertTrue(LyricsTapSync.canSkipLine(d))
        d = LyricsTapSync.skipLine(d, 0).draft
        assertTrue(d.lines[0].skipped)
        assertEquals(3, d.cursor)

        val step = LyricsTapSync.undo(d, 1f, 0)
        assertEquals(1, step.draft.cursor)
        assertEquals(2, step.clearedCount)
        assertFalse(step.draft.lines[0].skipped)
        assertEquals(10_000L, step.draft.tokens[0].rawStartMs)
        assertEquals(8_000L, step.seekToMs)
    }

    @Test
    fun rewind_clearsOnlyTapsAfterTheNewPosition() {
        val d = taps(pasted("a b c d e"), 1_000L, 2_000L, 3_000L, 4_000L)
        val step = LyricsTapSync.rewind(d, positionMs = 8_000L, offsetMs = 0)
        assertEquals(3_000L, step.seekToMs)
        assertEquals(2, step.clearedCount)
        assertEquals(2, step.draft.cursor)
        assertEquals(listOf(1_000L, 2_000L, null, null, null), step.draft.tokens.map { it.rawStartMs })

        val withOffset = LyricsTapSync.rewind(d, positionMs = 8_000L, offsetMs = 100)
        assertEquals(1, withOffset.clearedCount) // built starts are 900/1900/2900/3900
        assertEquals(3, withOffset.draft.cursor)

        val nothing = LyricsTapSync.rewind(d, positionMs = 20_000L, offsetMs = 0)
        assertSame(d, nothing.draft)
        assertEquals(15_000L, nothing.seekToMs)
        assertEquals(0L, LyricsTapSync.rewind(d, 2_000L, 0).seekToMs)
    }

    @Test
    fun jumpToLine_clearsFromThatLineAndSeeksBeforeIt() {
        val d = taps(pasted("a b", "c d"), 1_000L, 2_000L, 3_000L)
        assertEquals(3, d.cursor)

        val toCurrent = LyricsTapSync.jumpToLine(d, 1, 1f, 0)
        assertEquals(1_000L, toCurrent.seekToMs)
        assertEquals(1, toCurrent.clearedCount)
        assertEquals(2, toCurrent.draft.cursor)
        assertEquals(2_000L, LyricsTapSync.jumpToLine(d, 1, 0.5f, 0).seekToMs)

        val toFirst = LyricsTapSync.jumpToLine(d, 0, 1f, 0)
        assertEquals(0L, toFirst.seekToMs)
        assertEquals(3, toFirst.clearedCount)
        assertEquals(0, toFirst.draft.cursor)
        assertEquals(0, toFirst.draft.tappedCount)

        val fresh = pasted("a b", "c d")
        val ahead = LyricsTapSync.jumpToLine(fresh, 1, 1f, 0)
        assertSame(fresh, ahead.draft)
        assertNull(ahead.seekToMs)
    }

    @Test
    fun jumpToLine_usesTheAnchorWhenTheLineIsUntapped() {
        val d = taps(lineSynced(10_000 to "a b", 20_000 to "c d"), 10_000L, 10_400L)
        val step = LyricsTapSync.jumpToLine(d, 1, 1f, 0)
        assertEquals(18_000L, step.seekToMs)
        assertEquals(0, step.clearedCount)
    }

    @Test
    fun fixLine_rewritesOneLineAndKeepsTheRest() {
        val done = taps(pasted("a b", "c d"), 1_000L, 2_000L, 3_000L, 4_000L)
        val step = LyricsTapSync.fixLine(done, 0, 1f, 0)
        assertEquals(0L, step.seekToMs)
        assertEquals(0, step.draft.cursor)
        assertEquals(listOf(null, null, 3_000L, 4_000L), step.draft.tokens.map { it.rawStartMs })

        var d = LyricsTapSync.tap(step.draft, 1_200L, 1f, 0, scopeLine = 0).draft
        val past = LyricsTapSync.tap(d, 5_000L, 1f, 0, scopeLine = 0)
        assertTrue(past.pastNextLine)
        assertEquals(2_959L, start(past.draft, 1))
        d = LyricsTapSync.tap(d, 2_100L, 1f, 0, scopeLine = 0).draft
        assertEquals(2, d.cursor)
        assertSame(d, LyricsTapSync.tap(d, 2_500L, 1f, 0, scopeLine = 0).draft) // next line is out of scope
        assertTrue(LyricsDocCodec.isValid(LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()))
    }

    @Test
    fun skipLine_spreadsWordsByCharacterCountBetweenAnchors() {
        val d = lineSynced(10_000 to "one two three", 20_000 to "four", 30_000 to "five")
        val skipped = LyricsTapSync.skipLine(d, 0).draft
        assertEquals(listOf(10_000L, 12_727L, 15_454L), skipped.tokens.take(3).map { it.rawStartMs })
        assertTrue(skipped.tokens.take(3).all { it.exact })
        assertTrue(skipped.lines[0].skipped)
        assertEquals(3, skipped.cursor)
        assertTrue(LyricsTapSync.canSkipLine(skipped))
        val last = LyricsTapSync.skipLine(skipped, 0).draft
        assertFalse(LyricsTapSync.canSkipLine(last)) // no next line

        val plain = pasted("a b", "c")
        assertFalse(LyricsTapSync.canSkipLine(plain))
        assertSame(plain, LyricsTapSync.skipLine(plain, 0).draft)
    }

    @Test
    fun fillRest_timesTheRemainingWordsAtMost600msApart() {
        val d = taps(pasted("a b c d", durationMs = 10_000L), 1_000L, 1_500L)
        val filled = LyricsTapSync.fillRest(d, 0).draft
        assertTrue(filled.isFinished)
        assertEquals(listOf(1_000L, 1_500L, 2_100L, 2_700L), filled.tokens.map { it.rawStartMs })
        assertTrue(filled.lines[0].skipped)
        val result = LyricsTapSync.buildResult(filled, 0).getOrThrow()
        assertTrue(LyricsDocCodec.isValid(result.doc))
        assertEquals(setOf(0), result.roughLineIndices)
    }

    @Test
    fun nudgeAndOffsetLearning() {
        val d = tapAll(pasted("a b c"), startMs = 5_000L)
        val base = LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()
        val later = LyricsTapSync.toLyricsDoc(LyricsTapSync.setNudge(d, 40), 0).getOrThrow()
        assertEquals(
            base.lines.single().syllables.map { it.startMs + 40 },
            later.lines.single().syllables.map { it.startMs },
        )
        assertEquals(400, LyricsTapSync.setNudge(d, 999).nudgeMs)
        assertEquals(-400, LyricsTapSync.setNudge(d, -999).nudgeMs)

        assertEquals(80, LyricsTapSync.learnedOffsetMs(100, 40))
        assertEquals(120, LyricsTapSync.learnedOffsetMs(100, -40))
        assertEquals(0, LyricsTapSync.learnedOffsetMs(10, 100))
        assertEquals(400, LyricsTapSync.learnedOffsetMs(390, -100))
    }

    // ── ends ─────────────────────────────────────────────────────────────────────────────────

    private fun ends(starts: List<Long>, held: List<Long?> = starts.map { null }, next: Long? = null, ceiling: Long = 1_000_000L) =
        LyricsTapSync.deriveEnds(starts, held, next, ceiling)

    @Test
    fun deriveEnds_continuousSweepAndLastWord() {
        assertEquals(listOf(500L, 1_000L, 2_000L), ends(listOf(0L, 500L, 1_000L)))
        assertEquals(listOf(1_900L), ends(listOf(1_000L))) // no gaps: median 450 → 900
    }

    @Test
    fun deriveEnds_pauseInsideALine() {
        assertEquals(listOf(1_200L, 7_000L), ends(listOf(0L, 5_000L)))
    }

    @Test
    fun deriveEnds_heldEndsWin() {
        assertEquals(listOf(300L, 1_500L), ends(listOf(0L, 500L), held = listOf(300L, null)))
        assertEquals(listOf(40L), ends(listOf(0L), held = listOf(10L)))
    }

    @Test
    fun deriveEnds_clamps() {
        assertEquals(listOf(500L, 1_199L), ends(listOf(0L, 500L), next = 1_200L))
        assertEquals(listOf(40L, 50L, 420L), ends(listOf(0L, 10L, 20L)))
        assertEquals(listOf(500L, 900L), ends(listOf(0L, 500L), ceiling = 900L))
        // A "next line" that starts before this one (duet overlap) does not cut it short.
        assertEquals(listOf(5_500L, 6_500L), ends(listOf(5_000L, 5_500L), next = 4_000L))
    }

    // ── the finished document ────────────────────────────────────────────────────────────────

    @Test
    fun toLyricsDoc_buildsUserDocument() {
        val d = tapAll(pasted("Hello world", "Second line"), startMs = 1_000L, stepMs = 400L)
        val doc = LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()
        assertEquals(
            listOf(
                TimedLine(
                    1_000L, 1_799L, "Hello world", "lead",
                    listOf(TimedSyllable(1_000L, 400L, "Hello "), TimedSyllable(1_400L, 399L, "world")),
                ),
                TimedLine(
                    1_800L, 3_000L, "Second line", "lead",
                    listOf(TimedSyllable(1_800L, 400L, "Second "), TimedSyllable(2_200L, 800L, "line")),
                ),
            ),
            doc.lines,
        )
        assertEquals(LyricsMetadata("Title", "Artist", "Album", 240_000L, "user"), doc.metadata)
        assertNotNull(LyricsDocCodec.decode(LyricsDocCodec.encode(doc)))
    }

    @Test
    fun toLyricsDoc_failsWithoutTaps() {
        assertTrue(LyricsTapSync.toLyricsDoc(pasted("a b"), 0).isFailure)
    }

    @Test
    fun toLyricsDoc_japanesePasteIsSplitPerCharacterAndValid() {
        val d = tapAll(pasted("君が好きだよ", "東京ラーメン。"), stepMs = 250L)
        val doc = LyricsTapSync.toLyricsDoc(d, 100).getOrThrow()
        assertTrue(LyricsDocCodec.isValid(doc))
        assertEquals(listOf("君", "が", "好", "き", "だ", "よ"), doc.lines[0].syllables.map { it.text })
        assertEquals(listOf("東", "京", "ラー", "メ", "ン。"), doc.lines[1].syllables.map { it.text })
    }

    @Test
    fun toLyricsDoc_tapsPastTheSongEndAreClamped() {
        val d = tapAll(pasted("a b c"), startMs = 9_900L, stepMs = 400L).copy(durationMs = 10_000L)
        val doc = LyricsTapSync.toLyricsDoc(d, 0).getOrThrow()
        assertTrue(LyricsDocCodec.isValid(doc))
        assertTrue(doc.lines.all { it.endMs <= 10_000L })
    }

    @Test
    fun toLyricsDoc_isAlwaysValidForRandomSessions() {
        for (seed in 0 until 500) {
            val random = Random(seed)
            val offset = random.nextInt(0, 401)
            var draft = randomDraft(random)
            var position = random.nextLong(0L, 5_000L)
            val actions = random.nextInt(1, 120)
            repeat(actions) { step ->
                position = (position + random.nextLong(-400L, 2_000L)).coerceAtLeast(0L)
                val speed = listOf(0.5f, 0.75f, 1f).random(random)
                val scope = if (random.nextInt(8) == 0) draft.currentLineIndex else null
                draft = when (random.nextInt(100)) {
                    in 0..59 -> {
                        val index = LyricsTapSync.nextTappable(draft, draft.cursor)
                        val next = LyricsTapSync.tap(draft, position, speed, offset, scope).draft
                        if (next !== draft && index < draft.tokens.size) {
                            val previous = (index - 1 downTo 0).firstOrNull {
                                !next.lines[next.tokens[it].line].locked && next.tokens[it].rawStartMs != null
                            }
                            if (previous != null) {
                                assertTrue(
                                    start(next, index, offset)!! >= start(next, previous, offset)!! + 10,
                                    "seed $seed step $step: tap not after the previous one",
                                )
                            }
                        }
                        next
                    }
                    in 60..64 -> {
                        val last = (draft.cursor - 1 downTo 0).firstOrNull { draft.tokens.getOrNull(it)?.rawStartMs != null }
                        if (last == null) draft else LyricsTapSync.release(draft, last, position + random.nextLong(0L, 3_000L), speed, offset)
                    }
                    in 65..71 -> LyricsTapSync.undo(draft, speed, offset, scope).draft
                    in 72..76 -> LyricsTapSync.rewind(draft, position, offset, scopeLine = scope).draft
                    in 77..80 -> LyricsTapSync.jumpToLine(draft, random.nextInt(draft.lines.size), speed, offset).draft
                    in 81..83 -> LyricsTapSync.fixLine(draft, random.nextInt(draft.lines.size), speed, offset).draft
                    in 84..87 -> LyricsTapSync.skipLine(draft, offset).draft
                    in 88..89 -> LyricsTapSync.fillRest(draft, offset).draft
                    in 90..96 -> LyricsTapSync.setNudge(draft, random.nextInt(-500, 501))
                    else -> if (random.nextInt(4) == 0) LyricsTapSync.clearAll(draft) else draft
                }
                assertTrue(LyricsTapSync.isConsistent(draft), "seed $seed step $step: inconsistent draft")
                assertBuildsValid(draft, offset, "seed $seed step $step")
            }
            assertBuildsValid(tapAll(draft, startMs = position, stepMs = random.nextLong(1L, 900L), offsetMs = offset), offset, "seed $seed final")
        }
    }

    private fun assertBuildsValid(draft: SyncDraft, offsetMs: Int, context: String) {
        val result = LyricsTapSync.buildResult(draft, offsetMs)
        if (draft.tappedCount == 0 && draft.tappableCount > 0) {
            assertTrue(result.isFailure, "$context: expected failure without taps")
            return
        }
        val doc = result.getOrThrow().doc
        assertTrue(LyricsDocCodec.isValid(doc), "$context: invalid doc $doc")
        assertEquals(draft.lines.map { it.text }.sorted(), doc.lines.map { it.text }.sorted(), "$context: lines lost")
        assertNotNull(LyricsDocCodec.decode(LyricsDocCodec.encode(doc)), "$context: does not decode")
    }

    private val wordPool = listOf(
        "love", "you", "twenty-one", "rock'n'roll", "(oh", "yeah,", "—", "&", "…", "君が", "好き", "ラーメン",
        "きょう", "「愛」", "사랑해", "👨‍👩‍👧", "❤️", "OK。", "a",
        "night-time", "don't", "x".repeat(45),
    )

    private fun randomText(random: Random): String =
        (0 until random.nextInt(1, 9)).joinToString(" ") { wordPool.random(random) }

    private fun randomDraft(random: Random): SyncDraft {
        val duration = listOf(0L, 500L, 20_000L, 60_000L, 240_000L).random(random)
        val texts = List(random.nextInt(1, 12)) { randomText(random) }
        val roles = listOf("lead", "background", "duet")
        var time = random.nextLong(0L, 8_000L)
        val lyrics: Lyrics?
        val paste: String?
        when (random.nextInt(4)) {
            0 -> {
                lyrics = null
                paste = texts.joinToString("\n")
            }
            1 -> {
                paste = null
                lyrics = Lyrics(
                    synced = texts.map { text ->
                        time += random.nextLong(500L, 9_000L)
                        SyncedLine(time.toInt(), text, translation = if (random.nextBoolean()) "tr" else null, voiceRole = roles.random(random))
                    },
                )
            }
            2 -> {
                paste = null
                lyrics = Lyrics(
                    synced = texts.map { text ->
                        time += random.nextLong(500L, 6_000L)
                        val lineStart = time
                        val tokens = LyricsTapSync.tokenize(text)
                        val words = if (random.nextInt(5) == 0) null else tokens.mapIndexed { k, token ->
                            time += random.nextLong(0L, 700L)
                            SyncedWord(
                                time = time.toInt(),
                                word = token.trim(),
                                startsNewWord = k == 0 || tokens[k - 1].endsWith(" "),
                                endTime = if (random.nextBoolean()) (time + random.nextLong(-100L, 900L)).toInt() else null,
                            )
                        }
                        SyncedLine(lineStart.toInt(), text, words = words, voiceRole = roles.random(random))
                    },
                )
            }
            else -> {
                paste = null
                val voices = listOf(Voice("lead", "lead"), Voice("bg", "background"), Voice("v2", "duet"))
                val lines = texts.map { text ->
                    time += random.nextLong(300L, 6_000L)
                    val lineStart = time
                    val tokens = LyricsTapSync.tokenize(text)
                    val syllables = if (random.nextInt(4) == 0) emptyList() else tokens.map { token ->
                        val s = time
                        val length = random.nextLong(1L, 800L)
                        time += random.nextLong(0L, 700L)
                        TimedSyllable(s, length, token)
                    }
                    val end = maxOf(lineStart + 1, syllables.maxOfOrNull { it.startMs + it.durationMs } ?: (lineStart + 2_000L))
                    TimedLine(lineStart, end, tokens.joinToString(""), voices.random(random).id, syllables)
                }
                lyrics = Lyrics(
                    document = LyricsDoc(
                        metadata = LyricsMetadata(source = if (random.nextBoolean()) "user" else null),
                        voices = voices,
                        lines = lines,
                    ),
                )
            }
        }
        return LyricsTapSync.buildDraft("seed", "T", "A", "Al", duration, lyrics, paste).draft!!
    }
}
