package com.theveloper.pixelplay.presentation.viewmodel

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Shuffle and the engine's picks at the end of the queue (docs/brain-spec.md, 7.1 "Ownership"). */
class EngineTailTest {

    private val reverseAroundCurrent: suspend (List<String>, Int) -> List<String> = { own, index ->
        // A stand-in for the anchored shuffle: the current track stays where it is, the rest is reversed.
        val rest = own.filterIndexed { i, _ -> i != index }.reversed().toMutableList()
        rest.add(index, own[index])
        rest
    }

    @Test
    fun `the engine's tail is the unbroken run of its picks at the end`() {
        val engine = listOf(false, true, false, true, true)
        assertEquals(2, EngineTail.count(engine.size) { engine[it] })
        assertEquals(0, EngineTail.count(3) { false })
        assertEquals(3, EngineTail.count(3) { true })
        assertEquals(0, EngineTail.count(0) { true })
    }

    @Test
    fun `shuffle mixes the listener's tracks and leaves the picks where they are`() = runTest {
        val queue = listOf("a", "b", "c", "d", "pick1", "pick2")
        val shuffled = EngineTail.shuffleOwnPart(queue, currentIndex = 1, tailCount = 2, shuffle = reverseAroundCurrent)
        assertEquals(listOf("d", "b", "c", "a", "pick1", "pick2"), shuffled)
        // No picks in the queue: exactly the plain shuffle.
        assertEquals(listOf("d", "b", "c", "a"), EngineTail.shuffleOwnPart(queue.take(4), 1, 0, reverseAroundCurrent))
    }

    @Test
    fun `with a pick playing nothing of the listener's is left to shuffle`() = runTest {
        val queue = listOf("a", "b", "pick1", "pick2")
        assertSame(queue, EngineTail.shuffleOwnPart(queue, currentIndex = 2, tailCount = 2, shuffle = reverseAroundCurrent))
    }

    @Test
    fun `switching shuffle off keeps the picks autoplay appended since the queue was started`() {
        // The stored original order is the queue as it was started; the picks came later.
        val original = listOf("a", "b", "c", "d")
        val restored = EngineTail.restoreOrder(original, tail = listOf("pick1", "pick2")) { it }
        assertEquals(listOf("a", "b", "c", "d", "pick1", "pick2"), restored)
        // An original order that was stored with picks in it does not double them.
        val withPicks = listOf("a", "pick1", "b", "pick2")
        assertEquals(listOf("a", "b", "pick1", "pick2"), EngineTail.restoreOrder(withPicks, listOf("pick1", "pick2")) { it })
        assertSame(original, EngineTail.restoreOrder(original, emptyList()) { it })
    }
}
