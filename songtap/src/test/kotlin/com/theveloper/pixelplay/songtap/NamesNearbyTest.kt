package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamesNearbyTest {

    // ── Names ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a name is trimmed, collapsed and at most 20 characters`() {
        assertEquals("Hoa's Pixel", SongTapNames.clean("   Hoa's    Pixel  "))
        assertEquals("A".repeat(20), SongTapNames.clean("A".repeat(40)))
        assertEquals("", SongTapNames.clean("   "))
        assertEquals(SongTapNames.FALLBACK, SongTapNames.orFallback(""))
        // The cut can fall on a space: it is not left trailing.
        assertEquals("x".repeat(19), SongTapNames.clean("x".repeat(19) + " y"))
    }

    @Test
    fun `control, zero width and bidirectional characters are stripped`() {
        assertEquals("evil", SongTapNames.clean("‮evil‬"))
        assertEquals("zerowidth", SongTapNames.clean("zero​width"))
        assertEquals("bellx", SongTapNames.clean("bell\u0007x"))
        assertEquals("tab here", SongTapNames.clean("tab\there"))
        assertEquals("bom", SongTapNames.clean("﻿bom"))
    }

    @Test
    fun `characters are scalars, not UTF-16 units`() {
        val party = "🎊"
        assertEquals(party.repeat(20), SongTapNames.clean(party.repeat(40)))
        assertTrue(SongTapNames.isValid(party.repeat(20)))
        assertFalse(SongTapNames.isValid(party.repeat(21)))
    }

    @Test
    fun `clean is idempotent and valid names are fixed points`() {
        for (raw in listOf("Pixel 10 Pro", "  a  b  ", "é".repeat(25), "🎊 x".repeat(8), "line\nbreak")) {
            val once = SongTapNames.clean(raw)
            assertEquals(once, SongTapNames.clean(once))
            if (once.isNotEmpty()) assertTrue(SongTapNames.isValid(once))
        }
        assertFalse(SongTapNames.isValid(""))
        assertFalse(SongTapNames.isValid(" x"))
    }

    @Test
    fun `the advertised name is cut at a scalar boundary to 24 bytes`() {
        assertEquals("Pixel 10 Pro", SongTapNames.advertName("Pixel 10 Pro"))
        assertEquals("é".repeat(12), SongTapNames.advertName("é".repeat(20)))
        val emoji = "🎊".repeat(10)
        assertEquals("🎊".repeat(6), SongTapNames.advertName(emoji))
        assertEquals(24, SongTapText.utf8Length(SongTapText.toCodePoints(SongTapNames.advertName(emoji))))
        assertNull(SongTapNames.fromAdvert("  "))
        assertEquals("Bo", SongTapNames.fromAdvert(" Bo "))
    }

    // ── The nearby list ──────────────────────────────────────────────────────────────

    private fun q(dbm: Int) = dbm * RSSI_SCALE

    @Test
    fun `a phone is listed inside the list gate and not outside it`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-70), false, 1_000)
        book.update(2, "Bo", Role.RECEIVE, q(-80), false, 1_000)
        assertEquals(listOf(1), book.visible(Role.RECEIVE, 1_000).map { it.id })
        // The list gate is broader than the strict near gate and never needs a connection.
        assertTrue(SongTapTuning.LIST_ENTER_DBM < SongTapTuning.NEAR_ENTER_DBM)
        assertFalse(book.visible(Role.RECEIVE, 1_000)[0].near)
    }

    @Test
    fun `a listed phone leaves the list only below the exit level`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-77), false, 1_000)
        book.update(1, "Ann", Role.RECEIVE, q(-84), false, 1_100)
        assertEquals(1, book.visible(Role.RECEIVE, 1_100).size)
        book.update(1, "Ann", Role.RECEIVE, q(-86), false, 1_200)
        assertTrue(book.visible(Role.RECEIVE, 1_200).isEmpty())
        // And needs the enter level again to come back.
        book.update(1, "Ann", Role.RECEIVE, q(-80), false, 1_300)
        assertTrue(book.visible(Role.RECEIVE, 1_300).isEmpty())
    }

    @Test
    fun `the list is strongest first, ties by number, and only the asked role`() {
        val book = NearbyBook()
        book.update(3, "Cy", Role.RECEIVE, q(-60), false, 1_000)
        book.update(1, "Ann", Role.RECEIVE, q(-70), false, 1_000)
        book.update(2, "Bo", Role.RECEIVE, q(-60), false, 1_000)
        book.update(4, "Di", Role.SEND, q(-40), false, 1_000)
        assertEquals(listOf(2, 3, 1), book.visible(Role.RECEIVE, 1_000).map { it.id })
        assertEquals(listOf(4), book.visible(Role.SEND, 1_000).map { it.id })
    }

    @Test
    fun `stale entries expire`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-60), false, 1_000)
        assertEquals(1, book.visible(Role.RECEIVE, 1_000 + NearbyBook.STALE_MS).size)
        assertTrue(book.visible(Role.RECEIVE, 1_001 + NearbyBook.STALE_MS).isEmpty())
        book.remove(1)
        assertTrue(book.visible(Role.RECEIVE, 1_000).isEmpty())
    }

    @Test
    fun `one phone is one row, also when it changes address`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-70), false, 1_000)
        book.update(1, "Ann", Role.RECEIVE, q(-60), false, 1_500)
        assertEquals(1, book.visible(Role.RECEIVE, 1_500).size)
        // The same name under a new number while the old one has gone quiet: only the newer shows.
        book.update(2, "Ann", Role.RECEIVE, q(-60), false, 3_100)
        assertEquals(listOf(2), book.visible(Role.RECEIVE, 3_100).map { it.id })
        // Both heard at the same time: two phones that happen to share a name.
        book.update(1, "Ann", Role.RECEIVE, q(-60), false, 3_200)
        assertEquals(2, book.visible(Role.RECEIVE, 3_200).size)
    }

    @Test
    fun `the prompt is for the strongest phone inside the strict gate that was not told no`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-60), false, 1_000)
        assertNull(book.promptFor(Role.RECEIVE, 1_000))
        book.update(1, "Ann", Role.RECEIVE, q(-52), true, 1_100)
        book.update(2, "Bo", Role.RECEIVE, q(-50), true, 1_100)
        assertEquals(2, book.promptFor(Role.RECEIVE, 1_100)?.id)
        book.ignore(2, 1_100)
        assertEquals(1, book.promptFor(Role.RECEIVE, 1_100)?.id)
        book.ignore(1, 1_100)
        assertNull(book.promptFor(Role.RECEIVE, 1_100))
    }

    @Test
    fun `no silences a phone for exactly a minute`() {
        val book = NearbyBook()
        book.update(1, "Ann", Role.RECEIVE, q(-52), true, 1_000)
        book.ignore(1, 1_000)
        book.update(1, "Ann", Role.RECEIVE, q(-52), true, 1_000 + NearbyBook.IGNORE_MS - 1)
        assertNull(book.promptFor(Role.RECEIVE, 1_000 + NearbyBook.IGNORE_MS - 1))
        book.update(1, "Ann", Role.RECEIVE, q(-52), true, 1_000 + NearbyBook.IGNORE_MS)
        assertEquals(1, book.promptFor(Role.RECEIVE, 1_000 + NearbyBook.IGNORE_MS)?.id)
    }
}
