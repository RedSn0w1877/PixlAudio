package com.theveloper.pixelplay.data.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The change of direction after three skipped picks (docs/brain-spec.md, 7.3): what turns the engine,
 * what it follows meanwhile, and what ends the turn.
 */
class DirectionTest {

    private val a = 11L
    private val b = 22L
    private val c = 33L
    private val anchors = longArrayOf(a, b, c) // newest first

    private fun Direction.engineSkip(anchors: LongArray = this@DirectionTest.anchors) =
        onEvent(enginePick = true, station = false, hardSkip = true, positive = false, anchors = anchors)

    private fun Direction.engineDone(anchors: LongArray = this@DirectionTest.anchors) =
        onEvent(enginePick = true, station = false, hardSkip = false, positive = true, anchors = anchors)

    private fun Direction.ownTrack(hardSkip: Boolean, anchors: LongArray = this@DirectionTest.anchors) =
        onEvent(enginePick = false, station = false, hardSkip = hardSkip, positive = !hardSkip, anchors = anchors)

    @Test
    fun `three engine picks skipped in a row turn the engine to the next anchor`() {
        val direction = Direction()
        assertFalse(direction.engineSkip())
        assertFalse(direction.engineSkip())
        assertEquals(0, direction.turn)
        assertArrayEquals(anchors, direction.follow(anchors))
        assertTrue(direction.engineSkip())
        assertEquals(1, direction.turn)
        assertArrayEquals(longArrayOf(b), direction.follow(anchors))
    }

    @Test
    fun `skipping through one's own playlist never turns the engine`() {
        val direction = Direction()
        repeat(6) { assertFalse(direction.ownTrack(hardSkip = true)) }
        assertEquals(0, direction.turn)
        // And the listener's skips do not count towards the engine's three.
        assertFalse(direction.engineSkip())
        assertFalse(direction.engineSkip())
        assertFalse(direction.ownTrack(hardSkip = true))
        assertFalse(direction.engineSkip())
        assertFalse(direction.engineSkip())
        assertEquals(0, direction.turn)
    }

    @Test
    fun `a pick that is heard out breaks the run of skips`() {
        val direction = Direction()
        direction.engineSkip()
        direction.engineSkip()
        direction.engineDone()
        assertFalse(direction.engineSkip())
        assertFalse(direction.engineSkip())
        assertTrue(direction.engineSkip())
    }

    @Test
    fun `the turn lasts while the engine's picks play and ends with the listener's next own track`() {
        val direction = Direction()
        repeat(3) { direction.engineSkip() }
        // Picks of the new direction play through: still turned, still the one anchor.
        direction.engineDone()
        direction.engineDone()
        assertEquals(1, direction.turn)
        assertArrayEquals(longArrayOf(b), direction.follow(anchors))
        // The listener plays a song of their own (even one they then skip): the detour is over.
        direction.ownTrack(hardSkip = true)
        assertEquals(0, direction.turn)
        assertArrayEquals(anchors, direction.follow(anchors))
        // A later turn starts from scratch.
        repeat(2) { assertFalse(direction.engineSkip()) }
        assertTrue(direction.engineSkip())
        assertEquals(2, direction.turn)
        assertArrayEquals(longArrayOf(b), direction.follow(anchors))
    }

    @Test
    fun `turning again goes to the next anchor, and then to the library when none is left`() {
        val direction = Direction()
        repeat(3) { direction.engineSkip() }
        assertArrayEquals(longArrayOf(b), direction.follow(anchors))
        repeat(3) { direction.engineSkip() }
        assertEquals(2, direction.turn)
        assertArrayEquals(longArrayOf(c), direction.follow(anchors))
        repeat(3) { direction.engineSkip() }
        assertEquals(3, direction.turn)
        // Every anchor was tried: nothing of the session is left to follow.
        assertEquals(0, direction.follow(anchors).size)
    }

    @Test
    fun `with one anchor or none there is nothing to turn to`() {
        val one = Direction()
        repeat(3) { one.engineSkip(longArrayOf(a)) }
        assertEquals(1, one.turn)
        assertEquals(0, one.follow(longArrayOf(a)).size)

        val none = Direction()
        repeat(3) { none.engineSkip(LongArray(0)) }
        assertEquals(1, none.turn)
        assertEquals(0, none.follow(LongArray(0)).size)
    }

    @Test
    fun `a pick liked after the turn leads the new direction`() {
        val direction = Direction()
        repeat(3) { direction.engineSkip() }
        // The liked pick became an anchor (newest first); the oldest anchor fell off the list.
        val liked = 44L
        val after = longArrayOf(liked, a, b)
        direction.engineDone(after)
        assertArrayEquals(longArrayOf(liked, b), direction.follow(after))
    }

    @Test
    fun `a station stays on its seed`() {
        val direction = Direction()
        repeat(5) {
            assertFalse(direction.onEvent(enginePick = true, station = true, hardSkip = true, positive = false, anchors = anchors))
        }
        assertEquals(0, direction.turn)
    }
}
