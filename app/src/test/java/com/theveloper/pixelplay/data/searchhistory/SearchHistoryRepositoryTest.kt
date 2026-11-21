package com.theveloper.pixelplay.data.searchhistory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The one door to the history: the private-session gate, the key de-dupe, the cap and the undo path. */
class SearchHistoryRepositoryTest {

    private val dao = FakeSearchHistoryDao()
    private var privateOn = false
    private var now = 1_000L
    private val repo = SearchHistoryRepository(dao, { privateOn }, { now++ })

    @Test
    fun `a query is stored with its key, time and count`() = runTest {
        assertTrue(repo.record("  Beyonc\u00e9 "))
        val row = dao.rows.single()
        assertEquals("Beyonc\u00e9", row.query)
        assertEquals("beyonce", row.key)
        assertEquals(1, row.count)
        assertNull(row.kind)
    }

    @Test
    fun `the same words typed another way are one row with a higher count`() = runTest {
        repo.record("beyonce")
        repo.record("BEYONC\u00c9")
        repo.record("Beyonce")
        val row = dao.rows.single()
        assertEquals("Beyonce", row.query)
        assertEquals(3, row.count)
        assertTrue(row.timestamp > 1_000L)
    }

    @Test
    fun `Vietnamese spellings with and without marks are one row`() = runTest {
        repo.record("Nguy\u1ec5n Tr\u1ea7n")
        repo.record("nguyen tran")
        assertEquals(1, dao.rows.size)
        assertEquals(2, dao.rows.single().count)
    }

    @Test
    fun `nothing is recorded in a private session, not even a count bump`() = runTest {
        repo.record("beyonce")
        val before = dao.rows.single()
        val writesBefore = dao.writes
        privateOn = true
        assertFalse(repo.record("beyonce"))
        assertFalse(repo.record("a brand new query"))
        assertFalse(repo.record("Beyonc\u00e9", SearchHistoryKind.ARTIST, "42", "Beyonc\u00e9", null))
        assertEquals(before, dao.rows.single())
        assertEquals(writesBefore, dao.writes)
        privateOn = false
        assertTrue(repo.record("beyonce"))
        assertEquals(2, dao.rows.single().count)
    }

    @Test
    fun `too short, blank and symbol only queries are not stored`() = runTest {
        assertFalse(repo.record("a"))
        assertFalse(repo.record("   "))
        assertFalse(repo.record("!!"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a query longer than 100 characters is cut`() = runTest {
        repo.record("q".repeat(300))
        assertEquals(100, dao.rows.single().query.length)
    }

    @Test
    fun `an opened result is stored with its kind and target`() = runTest {
        repo.record("bey", SearchHistoryKind.ARTIST, "42", "Beyonc\u00e9", "https://img/1")
        val row = dao.rows.single()
        assertEquals("artist", row.kind)
        assertEquals("42", row.targetId)
        assertEquals("Beyonc\u00e9", row.targetTitle)
        assertEquals("https://img/1", row.imageUrl)
        // A result with no id is only a query.
        repo.record("halo", SearchHistoryKind.SONG, null, "Halo", null)
        assertNull(dao.rows.first { it.key == "halo" }.kind)
    }

    @Test
    fun `the table never holds more than 50 rows and the oldest go first`() = runTest {
        for (i in 1..65) repo.record("query number $i")
        assertEquals(50, dao.rows.size)
        val recent = repo.recent()
        assertEquals("query number 65", recent.first().query)
        assertEquals("query number 16", recent.last().query)
        assertFalse(dao.rows.any { it.query == "query number 15" })
    }

    @Test
    fun `using an old query again moves it to the top and spares it from the trim`() = runTest {
        for (i in 1..50) repo.record("query number $i")
        repo.record("query number 1")
        repo.record("one more")
        assertEquals(50, dao.rows.size)
        assertTrue(dao.rows.any { it.query == "query number 1" })
        assertFalse(dao.rows.any { it.query == "query number 2" })
        assertEquals("one more", repo.recent().first().query)
        assertEquals("query number 1", repo.recent()[1].query)
    }

    @Test
    fun `removing several rows at once takes them all and put back restores them`() = runTest {
        repo.record("bey", SearchHistoryKind.ARTIST, "42", "Beyonce")
        repo.record("beyonce", SearchHistoryKind.ARTIST, "42", "Beyonce")
        repo.record("other")
        val all = repo.recent()
        repo.remove(all.filter { it.targetId == "42" }.map { it.id })
        assertEquals(listOf("other"), repo.recent().map { it.query })
        repo.putBack(all)
        assertEquals(3, repo.recent().size)
    }

    @Test
    fun `remove and clear take rows out, put back brings them back as they were`() = runTest {
        repo.record("alpha")
        repo.record("beta")
        repo.record("beta")
        val all = repo.recent()
        repo.remove(all.first { it.query == "beta" }.id)
        assertEquals(listOf("alpha"), repo.recent().map { it.query })
        repo.clear()
        assertTrue(repo.recent().isEmpty())
        repo.putBack(all)
        val back = repo.recent()
        assertEquals(all.map { it.query }.toSet(), back.map { it.query }.toSet())
        assertEquals(2, back.first { it.query == "beta" }.count)
    }
}
