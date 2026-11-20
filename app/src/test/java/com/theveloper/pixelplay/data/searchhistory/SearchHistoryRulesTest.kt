package com.theveloper.pixelplay.data.searchhistory

import com.theveloper.pixelplay.data.database.SearchHistoryEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The search history's rules (docs/test-parity.md, search history row): the constants, the key, the upsert, the cap, the
 * typed-prefix order, the 5-to-6 migration pass and the restore merge. The iOS app pins the same numbers and the same keys.
 */
class SearchHistoryRulesTest {

    private fun row(query: String, timestamp: Long, count: Int = 1, id: Long = 0L, kind: String? = null, targetId: String? = null) =
        SearchHistoryEntity(
            id = id, query = query, timestamp = timestamp,
            key = SearchHistoryRules.keyOf(query) ?: "", kind = kind, targetId = targetId,
            targetTitle = if (kind != null) "Title of $query" else null, count = count,
        )

    // Vietnamese, composed (NFC) and decomposed (NFD), written as escapes so no file encoding can change them.
    private val nguyenComposed = "Nguy\u1ec5n Tr\u1ea7n Trung Qu\u00e2n"
    private val nguyenDecomposed = "Nguye\u0302\u0303n Tra\u0302\u0300n Trung Qua\u0302n"

    @Test
    fun `the parity constants`() {
        assertEquals(2, SearchHistoryRules.MIN_QUERY_LENGTH)
        assertEquals(100, SearchHistoryRules.MAX_QUERY_LENGTH)
        assertEquals(50, SearchHistoryRules.CAP)
        assertEquals(5, SearchHistoryRules.SUGGESTION_LIMIT)
        assertEquals(10, SearchHistoryRules.RECENT_STRIP_LIMIT)
        assertEquals(listOf("query", "song", "album", "artist", "playlist"), SearchHistoryKind.entries.map { it.wire })
    }

    @Test
    fun `a query is trimmed, cut at 100 and needs 2 characters`() {
        assertEquals("ab", SearchHistoryRules.normalise("  ab \n"))
        assertNull(SearchHistoryRules.normalise("a"))
        assertNull(SearchHistoryRules.normalise("   "))
        assertNull(SearchHistoryRules.normalise(" x "))
        assertEquals(100, SearchHistoryRules.normalise("x".repeat(250))!!.length)
        // A cut that would leave a trailing space is trimmed again.
        assertEquals("a".repeat(99), SearchHistoryRules.normalise("a".repeat(99) + " b"))
    }

    @Test
    fun `lengths are UTF-16 units and a cut never splits an emoji`() {
        val emoji = "\uD83D\uDE00"
        // 99 letters then an emoji: the cut at 100 units would split the pair, so the whole emoji goes.
        val cut = SearchHistoryRules.normalise("a".repeat(99) + emoji)!!
        assertEquals("a".repeat(99), cut)
        assertFalse(cut.any { Character.isSurrogate(it) })
        // 98 letters then an emoji fits exactly (100 units) and is kept whole.
        assertEquals("a".repeat(98) + emoji, SearchHistoryRules.normalise("a".repeat(98) + emoji))
        // One emoji is 2 units: it is long enough to store, the same as a Swift utf16 count.
        assertEquals(emoji, SearchHistoryRules.normalise(emoji))
        assertEquals("abc", SearchHistoryRules.cutUnits("abc", 5))
        assertEquals("", SearchHistoryRules.cutUnits(emoji, 1))
    }

    @Test
    fun `the same thing reached from two typed queries is one row in the list`() {
        val rows = listOf(
            row("beyonce", 30, kind = "artist", targetId = "42", id = 3),
            row("plain words", 25, id = 4),
            row("bey", 20, kind = "artist", targetId = "42", id = 2),
            row("bey song", 10, kind = "song", targetId = "42", id = 1),
            row("more words", 5, id = 5),
        )
        val shown = SearchHistoryRules.collapseByTarget(rows) { r ->
            SearchHistoryKind.fromWire(r.kind)?.let { "${it.wire}:${r.targetId}" }
        }
        // The newest row stands for the artist; a song with the same id is another kind; plain queries all stay.
        assertEquals(listOf("beyonce", "plain words", "bey song", "more words"), shown.map { it.query })
    }

    @Test
    fun `spellings of one query share a key`() {
        val keys = listOf("Beyonc\u00e9", "BEYONCE", "beyonce", "  Beyonce  ", "Beyonc\u00e9!").map { SearchHistoryRules.keyOf(it) }
        assertEquals(setOf("beyonce"), keys.toSet())
    }

    @Test
    fun `Vietnamese diacritics fold the same composed or decomposed`() {
        assertEquals("nguyen tran trung quan", SearchHistoryRules.keyOf(nguyenComposed))
        assertEquals("nguyen tran trung quan", SearchHistoryRules.keyOf(nguyenDecomposed))
        assertEquals(SearchHistoryRules.keyOf("S\u01a1n T\u00f9ng M-TP"), SearchHistoryRules.keyOf("son tung m tp"))
        assertEquals("abc def", SearchHistoryRules.keyOf("\uff21\uff22\uff23\u3000\uff24\uff25\uff26"))
    }

    @Test
    fun `a query that folds to nothing has no key`() {
        assertNull(SearchHistoryRules.keyOf("!!"))
        assertNull(SearchHistoryRules.keyOf("?? ..."))
    }

    @Test
    fun `recording again keeps the newest text and bumps the time and the count`() {
        val first = row("beyonce", timestamp = 10L)
        val again = SearchHistoryRules.applyRecord(first.copy(id = 7L), row("Beyonc\u00e9", timestamp = 50L))
        assertEquals(7L, again.id)
        assertEquals("Beyonc\u00e9", again.query)
        assertEquals(50L, again.timestamp)
        assertEquals(2, again.count)
        assertEquals(3, SearchHistoryRules.applyRecord(again, row("BEYONCE", 60L)).count)
    }

    @Test
    fun `the first use is count 1 whatever the incoming row says`() {
        assertEquals(1, SearchHistoryRules.applyRecord(null, row("beyonce", 1L, count = 9)).count)
    }

    @Test
    fun `an opened result sets the target and a plain submit keeps it`() {
        val typed = row("bey", 1L)
        val opened = SearchHistoryRules.applyRecord(typed, row("bey", 2L, kind = "artist", targetId = "42"))
        assertEquals("artist", opened.kind)
        assertEquals("42", opened.targetId)
        val submittedAgain = SearchHistoryRules.applyRecord(opened, row("bey", 3L))
        assertEquals("artist", submittedAgain.kind)
        assertEquals("42", submittedAgain.targetId)
        assertEquals(3, submittedAgain.count)
        val otherTarget = SearchHistoryRules.applyRecord(submittedAgain, row("bey", 4L, kind = "album", targetId = "9"))
        assertEquals("album", otherTarget.kind)
        assertEquals("9", otherTarget.targetId)
    }

    @Test
    fun `the cap keeps the 50 newest and trims the oldest`() {
        val rows = (1..60).map { row("query $it", timestamp = it.toLong(), id = it.toLong()) }
        val kept = SearchHistoryRules.capped(rows)
        assertEquals(50, kept.size)
        assertEquals("query 60", kept.first().query)
        assertEquals("query 11", kept.last().query)
        assertFalse(kept.any { it.query == "query 10" })
        assertEquals(rows.size - 1, SearchHistoryRules.capped(rows, cap = rows.size - 1).size)
    }

    @Test
    fun `suggestions are the rows that start with the typed words, most used first then newest, 5 at most`() {
        val rows = listOf(
            row("beyonce halo", 100L, count = 1, id = 1),
            row("beyonce", 90L, count = 4, id = 2),
            row("beyond", 95L, count = 4, id = 3),
            row("Beyonc\u00e9 formation", 80L, count = 2, id = 4),
            row("be my baby", 70L, count = 9, id = 5),
            row("beyblade", 60L, count = 1, id = 6),
            row("beyonce lemonade", 50L, count = 1, id = 7),
            row("adele", 99L, count = 50, id = 8),
        )
        val out = SearchHistoryRules.suggest(rows, "bey")
        assertEquals(5, out.size)
        // count 4 first (newest of the two first), then count 2, then count 1 newest first.
        assertEquals(listOf("beyond", "beyonce", "Beyonc\u00e9 formation", "beyonce halo", "beyblade"), out.map { it.query })
        assertEquals(
            setOf("beyonce", "Beyonc\u00e9 formation", "beyonce halo", "beyonce lemonade"),
            SearchHistoryRules.suggest(rows, "BEYONC\u00c9").map { it.query }.toSet()
        )
        assertTrue(SearchHistoryRules.suggest(rows, "zzz").isEmpty())
    }

    @Test
    fun `an empty or symbol only prefix suggests nothing`() {
        val rows = listOf(row("beyonce", 1L))
        assertTrue(SearchHistoryRules.suggest(rows, "").isEmpty())
        assertTrue(SearchHistoryRules.suggest(rows, "   ").isEmpty())
        assertTrue(SearchHistoryRules.suggest(rows, "!!").isEmpty())
    }

    @Test
    fun `a Vietnamese prefix matches without its accents and with them`() {
        val rows = listOf(row(nguyenComposed, 1L))
        assertEquals(1, SearchHistoryRules.suggest(rows, "nguyen tr").size)
        assertEquals(1, SearchHistoryRules.suggest(rows, "Nguy\u1ec5n").size)
        assertEquals(1, SearchHistoryRules.suggest(rows, "Nguye\u0302\u0303n").size)
        assertTrue(SearchHistoryRules.suggest(rows, "tran").isEmpty())
    }

    // --- the 5-to-6 migration pass ---------------------------------------------------------------------------

    @Test
    fun `the migration pass collapses spellings, sums counts and keeps the newest text`() {
        val plan = SearchHistoryRules.planMigration(
            listOf(
                LegacySearchRow("beyonce", 10L, id = 1),
                LegacySearchRow("Beyonc\u00e9", 30L, id = 2),
                LegacySearchRow("BEYONCE", 20L, id = 3),
                LegacySearchRow("adele", 15L, id = 4),
                LegacySearchRow("!!", 99L, id = 5),
                LegacySearchRow("x", 98L, id = 6),
            )
        )
        assertEquals(listOf("beyonce", "adele"), plan.survivors.map { it.key })
        val beyonce = plan.survivors.first()
        assertEquals(2L, beyonce.id)
        assertEquals("Beyonc\u00e9", beyonce.query)
        assertEquals(30L, beyonce.timestamp)
        assertEquals(3, beyonce.count)
        assertEquals(setOf(1L, 3L, 5L, 6L), plan.deleteIds.toSet())
        assertEquals(plan.survivors.map { it.id }.toSet() + plan.deleteIds.toSet(), (1L..6L).toSet())
    }

    @Test
    fun `the migration pass cuts to the cap, oldest first`() {
        val plan = SearchHistoryRules.planMigration((1..70).map { LegacySearchRow("query $it", it.toLong(), id = it.toLong()) })
        assertEquals(50, plan.survivors.size)
        assertEquals(20, plan.deleteIds.size)
        assertEquals((1L..20L).toSet(), plan.deleteIds.toSet())
        assertEquals(70L, plan.survivors.first().timestamp)
    }

    @Test
    fun `rows that already look right survive the migration pass untouched`() {
        val plan = SearchHistoryRules.planMigration(listOf(LegacySearchRow("lofi beats", 5L, id = 11)))
        assertEquals(1, plan.survivors.size)
        assertTrue(plan.deleteIds.isEmpty())
        val kept = plan.survivors.single()
        assertEquals(11L, kept.id)
        assertEquals("lofi beats", kept.query)
        assertEquals("lofi beats", kept.key)
        assertEquals(1, kept.count)
    }

    // --- restore merge --------------------------------------------------------------------------------------

    @Test
    fun `restoring an old payload computes keys and defaults the new fields`() {
        val old = listOf(
            SearchHistoryBackupRow(id = 1, query = "Beyonc\u00e9", timestamp = 100L),
            SearchHistoryBackupRow(id = 2, query = "adele", timestamp = 200L),
        )
        val merged = SearchHistoryRules.mergeRestore(emptyList(), old)
        assertEquals(setOf("beyonce", "adele"), merged.map { it.key }.toSet())
        assertTrue(merged.all { it.count == 1 && it.kind == null && it.id == 0L })
        assertEquals("adele", merged.first().query)
    }

    @Test
    fun `restoring merges with what is there and never clears`() {
        val existing = listOf(
            row("beyonce", 500L, count = 3, id = 5),
            row("local only", 400L, count = 1, id = 6),
        )
        val merged = SearchHistoryRules.mergeRestore(
            existing,
            listOf(
                SearchHistoryBackupRow(query = "Beyonc\u00e9", timestamp = 100L, count = 7, kind = "artist", targetId = "42", targetTitle = "Beyonc\u00e9"),
                SearchHistoryBackupRow(query = "from backup", timestamp = 50L),
            )
        )
        assertEquals(3, merged.size)
        val b = merged.first { it.key == "beyonce" }
        assertEquals(5L, b.id)
        assertEquals("beyonce", b.query) // the newer side's text
        assertEquals(500L, b.timestamp)
        assertEquals(7, b.count) // the larger count, not the sum
        assertEquals("artist", b.kind) // the target the other side knew
        assertNotNull(merged.firstOrNull { it.query == "local only" })
        assertNotNull(merged.firstOrNull { it.query == "from backup" })
    }

    @Test
    fun `restoring the same backup twice changes nothing`() {
        val backup = listOf(
            SearchHistoryBackupRow(query = "Beyonc\u00e9", timestamp = 100L, count = 2),
            SearchHistoryBackupRow(query = "adele", timestamp = 200L),
        )
        val once = SearchHistoryRules.mergeRestore(emptyList(), backup).mapIndexed { i, r -> r.copy(id = i + 1L) }
        val twice = SearchHistoryRules.mergeRestore(once, backup)
        assertEquals(once.sortedBy { it.id }, twice.sortedBy { it.id })
    }

    @Test
    fun `one backup with several spellings of a query adds them up`() {
        val merged = SearchHistoryRules.mergeRestore(
            emptyList(),
            listOf(
                SearchHistoryBackupRow(query = "beyonce", timestamp = 1L),
                SearchHistoryBackupRow(query = "BEYONC\u00c9", timestamp = 2L),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().count)
        assertEquals("BEYONC\u00c9", merged.single().query)
    }

    @Test
    fun `a restore is capped at 50 and rows that cannot be kept are skipped`() {
        val many = (1..80).map { SearchHistoryBackupRow(query = "query $it", timestamp = it.toLong()) }
        val junk = listOf(
            SearchHistoryBackupRow(query = null, timestamp = 1L),
            SearchHistoryBackupRow(query = "x", timestamp = 1L),
            SearchHistoryBackupRow(query = "!!", timestamp = 1L),
            SearchHistoryBackupRow(query = "fine", timestamp = 1L, kind = "weird", targetId = "9"),
        )
        val merged = SearchHistoryRules.mergeRestore(emptyList(), many + junk)
        assertEquals(50, merged.size)
        assertEquals("query 80", merged.first().query)
        assertFalse(merged.any { it.key == "" })
        // An unknown kind becomes a plain query row.
        val fine = SearchHistoryRules.mergeRestore(emptyList(), junk).single()
        assertEquals("fine", fine.query)
        assertNull(fine.kind)
        assertNull(fine.targetId)
    }

    @Test
    fun `a target without an id is a plain query row`() {
        val row = SearchHistoryBackupRow(query = "bey", timestamp = 1L, kind = "artist", targetId = "").toEntityOrNull()!!
        assertNull(row.kind)
        assertTrue(!row.hasTarget())
    }
}
