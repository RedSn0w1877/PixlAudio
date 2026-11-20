package com.theveloper.pixelplay.data.backup.module

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.searchhistory.FakeSearchHistoryDao
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What an older app reads: the three fields it knows. (Top level: Gson skips local classes.) */
private class OldRow(val id: Long, val query: String, val timestamp: Long)

/** The SEARCH_HISTORY backup module: new fields written, old payloads still restore, restore merges and never clears. */
class SearchHistoryModuleHandlerTest {

    // The app's backup Gson (BackupModule): pretty, nulls written.
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val dao = FakeSearchHistoryDao()
    private val handler = SearchHistoryModuleHandler(dao, gson)
    private var now = 1_700_000_000_000L
    private val repo = SearchHistoryRepository(dao, { false }, { now++ })

    @Test
    fun `the module is the search history section`() {
        assertEquals(BackupSection.SEARCH_HISTORY, handler.section)
    }

    @Test
    fun `export writes the old three fields and the new ones under stable names`() = runTest {
        repo.record("bey", com.theveloper.pixelplay.data.searchhistory.SearchHistoryKind.ARTIST, "42", "Beyonc\u00e9", "https://img/1")
        val json = JsonParser.parseString(handler.export()).asJsonArray.single().asJsonObject
        assertEquals(
            setOf("id", "query", "timestamp", "key", "kind", "targetId", "targetTitle", "imageUrl", "count"),
            json.keySet()
        )
        assertEquals("bey", json["query"].asString)
        assertEquals("bey", json["key"].asString)
        assertEquals("artist", json["kind"].asString)
        assertEquals("42", json["targetId"].asString)
        assertEquals(1, json["count"].asInt)
    }

    @Test
    fun `a payload from before the new fields restores and gets keys`() = runTest {
        val old = """
            [
              {"id": 3, "query": "Beyonc\u00e9", "timestamp": 1700000000000},
              {"id": 4, "query": "lofi beats", "timestamp": 1700000001000}
            ]
        """.trimIndent()

        handler.restore(old)

        val rows = dao.getAll()
        assertEquals(listOf("lofi beats", "Beyonc\u00e9"), rows.map { it.query })
        assertEquals(listOf("lofi beats", "beyonce"), rows.map { it.key })
        assertTrue(rows.all { it.count == 1 && it.kind == null })
    }

    @Test
    fun `restore merges with what is already there and never clears`() = runTest {
        repo.record("already here")
        handler.restore("""[{"id": 1, "query": "from the backup", "timestamp": 5}]""")
        assertEquals(setOf("already here", "from the backup"), dao.rows.map { it.query }.toSet())
    }

    @Test
    fun `a round trip keeps every field`() = runTest {
        repo.record("bey", com.theveloper.pixelplay.data.searchhistory.SearchHistoryKind.ARTIST, "42", "Beyonc\u00e9", "https://img/1")
        repo.record("bey")
        repo.record("Nguy\u1ec5n")
        val exported = handler.export()
        val before = dao.getAll().map { it.copy(id = 0L) }

        dao.rows.clear()
        handler.restore(exported)

        assertEquals(before.sortedBy { it.key }, dao.getAll().map { it.copy(id = 0L) }.sortedBy { it.key })
        assertEquals(2, dao.rows.first { it.key == "bey" }.count)
        assertEquals(before.size, handler.countEntries())
    }

    @Test
    fun `an unreadable row costs only itself and a stale key is recomputed`() = runTest {
        handler.restore(
            """
            [
              7,
              {"query": 12, "timestamp": "soon"},
              {"id": 1, "query": "kept", "timestamp": 9, "key": "WRONG KEY", "count": -4},
              {"id": 2, "query": "x", "timestamp": 9}
            ]
            """.trimIndent()
        )
        val row = dao.rows.single()
        assertEquals("kept", row.query)
        assertEquals("kept", row.key)
        assertEquals(1, row.count)
    }

    @Test
    fun `restore is capped at 50 rows`() = runTest {
        val many = (1..80).joinToString(prefix = "[", postfix = "]") { """{"query": "query $it", "timestamp": $it}""" }
        handler.restore(many)
        assertEquals(50, dao.rows.size)
        assertFalse(dao.rows.any { it.query == "query 30" })
        assertTrue(dao.rows.any { it.query == "query 80" })
    }

    @Test
    fun `an old reader that only knows id query and timestamp still reads the new export`() = runTest {
        repo.record("beyonce")
        val rows = gson.fromJson(handler.export(), Array<OldRow>::class.java)
        assertEquals("beyonce", rows.single().query)
    }

    @Test
    fun `rollback puts the snapshot back as the whole table`() = runTest {
        repo.record("before")
        val snapshot = handler.snapshot()
        repo.record("after")
        handler.rollback(snapshot)
        assertEquals(listOf("before"), dao.rows.map { it.query })
    }
}
