package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeedsTest {

    private fun group(g: Int, from: Int, n: Int, artistOf: (Int) -> Long = { it.toLong() }) =
        (from until from + n).map { Seeds.Seed(it.toLong() + 1, artistOf(it), g) }

    @Test
    fun `each group takes its quota and the day never passes 24`() {
        val seeds = Seeds.select(
            listOf(group(0, 0, 20), group(1, 100, 20), group(2, 200, 20), group(3, 300, 20)),
        )
        assertEquals(24, seeds.size)
        assertEquals(listOf(8, 8, 4, 4), (0..3).map { g -> seeds.count { it.group == g } })
    }

    @Test
    fun `at most two seeds per artist across the groups`() {
        val seeds = Seeds.select(listOf(group(0, 0, 10) { 7L }, group(1, 100, 10) { 7L }, group(2, 200, 5) { it.toLong() }))
        assertEquals(2, seeds.count { it.aid == 7L })
        assertEquals(listOf(0, 0), seeds.filter { it.aid == 7L }.map { it.group })
    }

    @Test
    fun `a recording is a seed once and a skipped one never`() {
        val a = listOf(Seeds.Seed(5, 1, 0), Seeds.Seed(6, 2, 0))
        val b = listOf(Seeds.Seed(5, 1, 1), Seeds.Seed(7, 3, 1))
        val seeds = Seeds.select(listOf(a, b), skip = setOf(6L))
        assertEquals(listOf(5L, 7L), seeds.map { it.rid })
    }

    @Test
    fun `an unknown artist has no cap`() {
        val seeds = Seeds.select(listOf((1..8).map { Seeds.Seed(it.toLong(), 0L, 0) }))
        assertEquals(8, seeds.size)
    }

    @Test
    fun `the pseudo random order is the day's and changes with the day`() {
        val rids = LongArray(50) { it + 1L }
        val a = Seeds.pseudoRandomOrder(rids, 99L, 20_000L)
        assertEquals(a.toList(), Seeds.pseudoRandomOrder(rids, 99L, 20_000L).toList())
        assertTrue(a.toList() != Seeds.pseudoRandomOrder(rids, 99L, 20_001L).toList())
        assertEquals(rids.toList(), a.sorted())
    }
}
