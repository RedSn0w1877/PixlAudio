package com.theveloper.pixelplay.presentation.lyrics

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyricsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class LyricsEngineTest {

    private var positionMs = 0L
    private var frameNanos = 1_000_000_000L
    private lateinit var clock: LyricsClock
    private lateinit var engine: LyricsEngine

    private val viewport = 1_000f
    private val anchor = 250f
    private val rowH = 50f

    /** 30 lead lines, one every 2 s from 1 s: row i == line i (no interludes). */
    private fun lyrics(): PreparedLyrics =
        PreparedLyricsBuilder.build(Lyrics(synced = List(30) { SyncedLine(1_000 + it * 2_000, "line $it") }))!!

    @Before
    fun setUp() {
        clock = LyricsClock(positionProvider = { positionMs })
        engine = LyricsEngine(clock)
        engine.setConfig(LyricsEngineConfig(density = 1f, blurSupported = true, blurEnabled = true, blurStrength = 1f))
    }

    private fun install(animateIn: Boolean = false) {
        val p = lyrics()
        engine.setLyrics(p, animateIn)
        engine.setViewport(viewport, anchor)
        for (r in p.rows.indices) engine.setRowHeight(r, rowH)
    }

    private fun frame() {
        clock.tick(frameNanos)
        engine.step(frameNanos)
    }

    /** Advances [ms] in 16 ms frames; the player advances with it while playing. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val d = minOf(16L, left)
            frameNanos += d * 1_000_000L
            if (clock.isPlaying) positionMs += d
            frame()
            left -= d
        }
    }

    @Test
    fun firstLayout_putsTheHotLineOnTheAnchor() {
        install()
        clock.isPlaying = true
        positionMs = 3_100 // line 1 is hot: [2750, 5000)
        frame()
        assertTrue(engine.isLaidOut)
        assertEquals(1, engine.scrollTargetRow)
        assertEquals(anchor, engine.rows[1].y, 0.01f)
        assertEquals(anchor - rowH, engine.rows[0].y, 0.01f)
        assertEquals(anchor + rowH, engine.rows[2].y, 0.01f)
        assertTrue(engine.rows[1].hot)
        assertFalse(engine.rows[0].hot)
        assertFalse(engine.rows[2].hot)
        assertEquals("a rebuild snaps activeness", 1f, engine.rows[1].activeness, 0f)
    }

    @Test
    fun hotSet_usesTheLeadIn() {
        install()
        clock.isPlaying = true
        positionMs = 4_749
        frame()
        assertFalse(engine.isLineHot(2))
        advance(1)
        assertTrue("hot at start - 250", engine.isLineHot(2))
        assertTrue("line 1 still hot until its end", engine.isLineHot(1))
        assertEquals("lowest-index hot lead line wins", 1, engine.scrollTargetRow)
        advance(251)
        assertFalse(engine.isLineHot(1))
        assertEquals(2, engine.scrollTargetRow)
    }

    @Test
    fun targetChange_cascadesWithStaggeredDelays() {
        install()
        clock.isPlaying = true
        positionMs = 3_100
        frame()
        // Jump exactly as far as the frame clock moves: normal playback, not a seek.
        val jump = 5_001L - positionMs
        frameNanos += jump * 1_000_000L
        positionMs += jump
        frame()
        val start = frameNanos
        assertEquals(2, engine.scrollTargetRow)
        assertEquals("row 0 moves at once", -1L, engine.rowPendingAtNanos(0))
        assertEquals((start + 50_000_000L).toDouble(), engine.rowPendingAtNanos(1).toDouble(), 1e6)
        assertEquals((start + 100_000_000L).toDouble(), engine.rowPendingAtNanos(2).toDouble(), 1e6)
        // The target row (2) still adds a full 50 ms step; the 1.05 decay starts after it.
        assertEquals((start + 150_000_000L).toDouble(), engine.rowPendingAtNanos(3).toDouble(), 1e6)
        assertEquals((start + 197_619_000L).toDouble(), engine.rowPendingAtNanos(4).toDouble(), 1e6)

        val row5Before = engine.rows[5].y
        advance(20)
        assertEquals("row 5 has not started yet", row5Before, engine.rows[5].y, 0f)

        advance(3_000)
        val target = engine.scrollTargetRow
        assertEquals(anchor, engine.rows[target].y, 0.3f)
        assertTrue(engine.isAtRest || clock.isPlaying)
    }

    @Test
    fun seek_retargetsWithoutStagger() {
        install()
        clock.isPlaying = true
        positionMs = 3_100
        frame()
        frameNanos += 16_000_000L
        positionMs = 21_100 // far away: a seek
        frame()
        for (r in 0 until 30) assertEquals("row $r", -1L, engine.rowPendingAtNanos(r))
        assertEquals(10, engine.scrollTargetRow)
    }

    @Test
    fun depthBlur_distancesFromTheHotLine() {
        install()
        clock.isPlaying = true
        positionMs = 5_100 // only line 2 hot
        frame()
        assertEquals(2, engine.scrollTargetRow)
        assertEquals(0f, engine.rowSigmaTargetDp(2), 0f)
        assertEquals("just above", 2.4f, engine.rowSigmaTargetDp(1), 1e-5f)
        assertEquals(3.2f, engine.rowSigmaTargetDp(0), 1e-5f)
        assertEquals("next", 1.6f, engine.rowSigmaTargetDp(3), 1e-5f)
        assertEquals(2.4f, engine.rowSigmaTargetDp(4), 1e-5f)
        assertEquals(5.0f, engine.rowSigmaTargetDp(20), 1e-5f)
    }

    @Test
    fun api30_usesTheAlphaFalloffInsteadOfBlur() {
        engine.setConfig(LyricsEngineConfig(density = 1f, blurSupported = false))
        install()
        clock.isPlaying = true
        positionMs = 5_100
        frame()
        advance(600)
        assertEquals(0f, engine.rows[3].blurRadiusPx, 0f)
        assertEquals(0.94f, engine.rows[3].depthAlpha, 1e-3f)
        assertEquals(1f, engine.rows[2].depthAlpha, 0f)
    }

    @Test
    fun scale_inactiveLinesShrinkOnlyWhilePlaying() {
        install()
        clock.isPlaying = true
        positionMs = 5_100
        frame()
        advance(1_000) // t = 6100: line 2 is still the only hot line
        assertEquals(2, engine.scrollTargetRow)
        assertEquals(1f, engine.rows[2].scale, 1e-3f)
        assertEquals(LyricsEngine.INACTIVE_SCALE, engine.rows[5].scale, 1e-3f)

        clock.isPlaying = false
        advance(3_000)
        assertEquals(1f, engine.rows[5].scale, 1e-3f)
    }

    @Test
    fun userScroll_dragClampsRemovesBlurAndSnapsBackAfterIdle() {
        install()
        clock.isPlaying = false
        positionMs = 5_100
        frame()
        advance(500)
        assertTrue(engine.rowSigmaTargetDp(0) > 0f)

        engine.onDragStart()
        engine.onDrag(-100f)
        assertEquals(-100f, engine.scrollOffset, 0f)
        assertTrue(engine.isUserScrolling)
        engine.onDrag(10_000f) // the first line may not go below the anchor
        assertEquals(2 * rowH, engine.scrollOffset, 0.01f)
        engine.onDragEnd(0f)

        advance(300)
        assertEquals("no blur while scrolling", 0f, engine.rowSigmaTargetDp(0), 0f)
        assertEquals(0f, engine.rows[0].blurRadiusPx, 0f)

        advance(4_000)
        assertTrue("still waiting before 4.5 s", engine.isUserScrolling)
        advance(400)
        assertFalse("snapped back after 4.5 s", engine.isUserScrolling)
        assertEquals(0f, engine.scrollOffset, 0f)
        // The folded offset animates back with the slow spring.
        advance(3_000)
        assertEquals(anchor, engine.rows[2].y, 0.3f)
        assertTrue(engine.rowSigmaTargetDp(0) > 0f)
    }

    @Test
    fun fling_coastsAndStopsAtTheClamp() {
        install()
        clock.isPlaying = false
        positionMs = 5_100
        frame()
        engine.onDragStart()
        engine.onDrag(-10f)
        engine.onDragEnd(-5_000f)
        advance(32) // the fling starts on the first frame and has moved by the second
        val early = engine.scrollOffset
        assertTrue(early < -10f)
        advance(3_000)
        val minOffset = 0.5f * viewport - (anchor - 2 * rowH + 30 * rowH)
        assertTrue(engine.scrollOffset >= minOffset - 0.01f)
        assertTrue(engine.scrollOffset < early)
    }

    @Test
    fun seek_endsUserScroll() {
        install()
        clock.isPlaying = true
        positionMs = 5_100
        frame()
        engine.onDragStart()
        engine.onDrag(-200f)
        engine.onDragEnd(0f)
        advance(100)
        assertTrue(engine.isUserScrolling)
        frameNanos += 16_000_000L
        positionMs = 40_000
        frame()
        assertFalse(engine.isUserScrolling)
        assertEquals(0f, engine.scrollOffset, 0f)
    }

    @Test
    fun animateIn_startsBelowAndCascadesUp() {
        install(animateIn = true)
        clock.isPlaying = true
        positionMs = 3_100
        frame()
        assertEquals("starts at 2 × viewport", 2 * viewport, engine.rows[1].y, 1f)
        assertTrue(engine.rows[1].isPlaced)
        advance(3_000)
        assertEquals(anchor, engine.rows[engine.scrollTargetRow].y, 0.3f)
    }

    @Test
    fun reducedMotion_snaps() {
        engine.setConfig(LyricsEngineConfig(density = 1f, reducedMotion = true))
        install()
        clock.isPlaying = true
        positionMs = 3_100
        frame()
        val jump = 5_001L - positionMs
        frameNanos += jump * 1_000_000L
        positionMs += jump
        frame()
        assertEquals(anchor, engine.rows[2].y, 0.01f)
        assertEquals(-1L, engine.rowPendingAtNanos(5))
    }

    @Test
    fun pausedAndSettled_isAtRest() {
        install()
        clock.isPlaying = false
        positionMs = 5_100
        frame()
        advance(1_000)
        assertTrue(engine.isAtRest)
        assertFalse(engine.needsFrame)
    }

    // ---- window-first measuring (the view measures the rows around the anchor first) ----------

    @Test
    fun predictedTarget_matchesTheFirstLayoutsTarget() {
        val p = lyrics()
        engine.setLyrics(p, animateIn = true)
        engine.setViewport(viewport, anchor)
        clock.isPlaying = true
        positionMs = 25_100 // line 12 hot
        assertEquals(-1, engine.layoutAnchorRow)
        val predicted = engine.predictScrollTargetRow(clock.peekMs())
        for (r in p.rows.indices) engine.setRowHeight(r, rowH)
        frame()
        assertEquals(12, predicted)
        assertEquals(predicted, engine.scrollTargetRow)
        assertEquals(predicted, engine.layoutAnchorRow)
    }

    @Test
    fun estimatedHeightsOutsideTheWindow_leaveTheVisibleCascadeUnchanged() {
        val margin = LyricsEngine.SNAP_MARGIN_DP // density 1
        val real = FloatArray(30) { 40f + (it * 37 % 60) }
        val target = 12
        // The view's window: down past the viewport bottom (+ the anchor row), up past -margin.
        val inWindow = BooleanArray(30)
        var y = anchor
        var r = target
        while (r < 30 && y <= viewport + real[target]) { inWindow[r] = true; y += real[r]; r++ }
        y = anchor
        r = target - 1
        while (r >= 0 && y >= -margin) { inWindow[r] = true; y -= real[r]; r-- }
        assertTrue("rows are left out above and below", !inWindow[0] && !inWindow[29])

        fun run(heights: (Int) -> Float): LyricsEngine {
            var pos = 25_100L
            val c = LyricsClock(positionProvider = { pos })
            c.isPlaying = true
            val e = LyricsEngine(c)
            e.setConfig(LyricsEngineConfig(density = 1f))
            e.setLyrics(lyrics(), animateIn = true)
            e.setViewport(viewport, anchor)
            for (i in 0 until 30) e.setRowHeight(i, heights(i))
            var nanos = 1_000_000_000L
            c.tick(nanos)
            e.step(nanos)
            return e.also {
                // A few frames into the cascade, still on the estimates.
                repeat(20) {
                    nanos += 16_000_000L
                    pos += 16
                    c.tick(nanos)
                    e.step(nanos)
                }
            }
        }
        val exact = run { real[it] }
        val estimated = run { if (inWindow[it]) real[it] else 55f }
        assertEquals(target, estimated.scrollTargetRow)
        for (i in 0 until 30) {
            if (inWindow[i]) {
                assertEquals("row $i y", exact.rowSpringY(i), estimated.rowSpringY(i), 0.001f)
                assertEquals("row $i delay", exact.rowPendingAtNanos(i), estimated.rowPendingAtNanos(i))
            } else {
                // Never on screen in either run.
                for (e in listOf(exact, estimated)) {
                    val top = e.rowSpringY(i)
                    assertTrue("row $i off-screen", top + real[i] < 0f || top > viewport)
                }
            }
        }
    }

    // ---- clock ---------------------------------------------------------------------------------

    @Test
    fun clock_monotonicGuardAndSeekDetection() {
        var pos = 10_000L
        var offset = 0L
        val c = LyricsClock(positionProvider = { pos }, offsetMsProvider = { offset })
        c.isPlaying = true
        var f = 0L
        assertFalse(c.tick(f))
        assertEquals(10_000L, c.nowMs)

        f += 16_000_000L; pos = 9_960L // 40 ms backwards: jitter, rejected
        assertFalse(c.tick(f))
        assertEquals(10_000L, c.nowMs)

        f += 16_000_000L; pos = 9_900L // 100 ms backwards: accepted, not a seek
        assertFalse(c.tick(f))
        assertEquals(9_900L, c.nowMs)

        f += 16_000_000L; pos = 20_000L
        assertTrue(c.tick(f))
        assertTrue(c.consumeSeek())
        assertFalse(c.consumeSeek())
        assertEquals(1, c.seekCount)

        c.isPlaying = false
        f += 16_000_000L; pos = 19_980L // paused: no guard
        c.tick(f)
        assertEquals(19_980L, c.nowMs)

        offset = 300L
        f += 16_000_000L
        c.tick(f)
        assertEquals(20_280L, c.nowMs)
    }

    @Test
    fun clock_predictionFollowsElapsedTimeWhilePlaying() {
        var pos = 0L
        val c = LyricsClock(positionProvider = { pos })
        c.isPlaying = true
        c.tick(0L)
        // 3 s of frames later the player is 3 s ahead: not a seek.
        pos = 3_000L
        assertFalse(c.tick(3_000_000_000L))
        assertTrue(abs(c.nowMs - 3_000L) == 0L)
    }
}
