package com.theveloper.pixelplay.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real SQL behind the search history: the unique key, the upsert, the 50-row cap and the order. (The rules
 * themselves run on the JVM in `SearchHistoryRulesTest`; the in-memory fake there mirrors what is asserted here.)
 */
@RunWith(AndroidJUnit4::class)
class SearchHistoryDaoTest {

    private lateinit var db: PixelPlayDatabase
    private lateinit var dao: SearchHistoryDao

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayDatabase::class.java).build()
        dao = db.searchHistoryDao()
    }

    @After
    fun close() = db.close()

    private fun entry(query: String, ts: Long, kind: String? = null, target: String? = null) = SearchHistoryEntity(
        query = query,
        timestamp = ts,
        key = SearchHistoryRules.keyOf(query)!!,
        kind = kind,
        targetId = target,
    )

    @Test
    fun upsertFoldsSpellingsIntoOneRow() = runBlocking {
        dao.upsert(entry("beyonce", 1))
        dao.upsert(entry("Beyonc\u00e9", 5))
        dao.upsert(entry("BEYONCE", 9))
        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("BEYONCE", rows.single().query)
        assertEquals(9L, rows.single().timestamp)
        assertEquals(3, rows.single().count)
    }

    @Test
    fun theTableIsCappedAtFiftyAndTheOldestGo() = runBlocking {
        for (i in 1..70) dao.upsert(entry("query number $i", i.toLong()))
        val rows = dao.getAll()
        assertEquals(50, rows.size)
        assertEquals("query number 70", rows.first().query)
        assertEquals("query number 21", rows.last().query)
    }

    @Test
    fun anOpenedResultKeepsItsTargetAcrossAPlainSubmit() = runBlocking {
        dao.upsert(entry("bey", 1, kind = "artist", target = "42"))
        dao.upsert(entry("bey", 2))
        val row = dao.findByKey("bey")!!
        assertEquals("artist", row.kind)
        assertEquals("42", row.targetId)
        assertEquals(2, row.count)
    }

    @Test
    fun writeMergedNeverClearsAndStaysCapped() = runBlocking {
        dao.upsert(entry("kept", 1_000))
        dao.writeMerged((1..60).map { entry("restored $it", it.toLong()) })
        val rows = dao.getAll()
        assertEquals(50, rows.size)
        assertTrue(rows.any { it.query == "kept" })
    }

    @Test
    fun deleteByIdAndClearAll() = runBlocking {
        dao.upsert(entry("one", 1))
        dao.upsert(entry("two", 2))
        dao.deleteById(dao.findByKey("one")!!.id)
        assertEquals(listOf("two"), dao.getAll().map { it.query })
        dao.clearAll()
        assertEquals(0, dao.count())
    }
}
