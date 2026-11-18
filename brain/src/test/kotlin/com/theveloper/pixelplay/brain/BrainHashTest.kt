package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrainHashTest {

    @Test
    fun `fnv1a64 matches the published test vectors`() {
        // Reference values from the FNV specification (Fowler, Noll, Vo).
        assertEquals(0xcbf29ce484222325uL.toLong(), BrainHash.fnv1a64(""))
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), BrainHash.fnv1a64("a"))
        assertEquals(0x85944171f73967e8uL.toLong(), BrainHash.fnv1a64("foobar"))
    }

    @Test
    fun `fnv1a64 hashes utf-8 bytes, not utf-16 units`() {
        val viaBytes = BrainHash.fnv1a64(byteArrayOf(0xC4.toByte(), 0x91.toByte())) // U+0111, d with stroke
        assertEquals(viaBytes, BrainHash.fnv1a64("đ"))
    }

    @Test
    fun `splitmix64 matches the reference stream for seed zero`() {
        // First outputs of Vigna's splitmix64.c with x = 0.
        val rng = BrainHash.Rng(0L)
        assertEquals(0xe220a8397b1dcdafuL.toLong(), rng.nextLong())
        assertEquals(0x6e789e6aa1b965f4uL.toLong(), rng.nextLong())
        assertEquals(0x06c45d188009454fuL.toLong(), rng.nextLong())
        assertEquals(0xe220a8397b1dcdafuL.toLong(), BrainHash.splitmix64(0L))
    }

    @Test
    fun `uniform stays in the half-open unit interval and advances once`() {
        val rng = BrainHash.Rng(42L)
        val before = rng.state
        val u = rng.uniform()
        assertTrue(u >= 0.0 && u < 1.0)
        assertEquals(before + 0x9E3779B97F4A7C15uL.toLong(), rng.state)
    }

    @Test
    fun `gaussian12 consumes twelve uniforms and is centred`() {
        val a = BrainHash.Rng(7L)
        val b = BrainHash.Rng(7L)
        var sum = 0.0
        repeat(12) { sum += b.uniform() }
        assertEquals(sum - 6.0, a.gaussian12())
        assertEquals(b.state, a.state)

        val rng = BrainHash.Rng(1234L)
        var mean = 0.0
        repeat(20_000) { mean += rng.gaussian12() }
        mean /= 20_000
        assertTrue(mean > -0.05 && mean < 0.05, "mean was $mean")
    }
}
