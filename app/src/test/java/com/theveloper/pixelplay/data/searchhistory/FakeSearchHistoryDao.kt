package com.theveloper.pixelplay.data.searchhistory

import com.theveloper.pixelplay.data.database.SearchHistoryDao
import com.theveloper.pixelplay.data.database.SearchHistoryEntity

/**
 * An in-memory [SearchHistoryDao]: the same primitives the SQL gives (unique key, newest first, trim to a cap), so the
 * default methods of the interface (upsert, writeMerged, replaceAll) run unchanged on the JVM. The real SQL is held by
 * the instrumented `SearchHistoryDaoTest`.
 */
class FakeSearchHistoryDao : SearchHistoryDao {
    val rows = ArrayList<SearchHistoryEntity>()
    private var nextId = 1L
    var writes = 0
        private set

    private fun ordered() = rows.sortedWith(
        compareByDescending<SearchHistoryEntity> { it.timestamp }.thenByDescending { it.count }.thenByDescending { it.id }
    )

    override suspend fun insert(item: SearchHistoryEntity): Long {
        writes++
        val id = if (item.id == 0L) nextId++ else item.id
        rows.removeAll { it.id == id || it.key == item.key }
        rows += item.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    override suspend fun insertAll(items: List<SearchHistoryEntity>) {
        items.forEach { insert(it) }
    }

    override suspend fun update(item: SearchHistoryEntity) {
        writes++
        rows.replaceAll { if (it.id == item.id) item else it }
    }

    override suspend fun getRecentSearches(limit: Int) = ordered().take(limit)
    override suspend fun findByKey(key: String) = rows.firstOrNull { it.key == key }
    override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
    override suspend fun deleteByKey(key: String) { rows.removeAll { it.key == key } }
    override suspend fun clearAll() { rows.clear() }
    override suspend fun getAll() = ordered()
    override suspend fun count() = rows.size
    override suspend fun trimToCap(cap: Int) {
        val keep = ordered().take(cap).map { it.id }.toSet()
        rows.removeAll { it.id !in keep }
    }
}
