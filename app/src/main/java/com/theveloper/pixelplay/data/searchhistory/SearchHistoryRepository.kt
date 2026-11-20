package com.theveloper.pixelplay.data.searchhistory

import com.theveloper.pixelplay.data.brain.PrivateSession
import com.theveloper.pixelplay.data.database.SearchHistoryDao
import com.theveloper.pixelplay.data.database.SearchHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one door to the search history. Writes go through [record], which is a no-op while a private session is on: nothing
 * is inserted and no count moves, so what was searched in private leaves no trace (the rows from before are left alone).
 * Reads are one 50-row query ([recent]); filtering while typing happens in memory (`SearchHistoryRules.suggest`).
 */
@Singleton
class SearchHistoryRepository(
    private val dao: SearchHistoryDao,
    private val isPrivate: () -> Boolean,
    private val clock: () -> Long,
) {
    @Inject
    constructor(dao: SearchHistoryDao, privateSession: PrivateSession) :
        this(dao, { privateSession.active.value }, System::currentTimeMillis)

    /**
     * Remembers one use of [rawQuery]. Returns true when a row was written; false for a private session, a query that is
     * too short or folds to nothing.
     */
    suspend fun record(
        rawQuery: String,
        kind: SearchHistoryKind? = null,
        targetId: String? = null,
        targetTitle: String? = null,
        imageUrl: String? = null,
    ): Boolean {
        if (isPrivate()) return false
        val query = SearchHistoryRules.normalise(rawQuery) ?: return false
        val key = SearchHistoryRules.keyOf(query) ?: return false
        val opened = kind != null && kind != SearchHistoryKind.QUERY && !targetId.isNullOrBlank()
        val entry = SearchHistoryEntity(
            query = query,
            timestamp = clock(),
            key = key,
            kind = if (opened) kind!!.wire else null,
            targetId = if (opened) targetId else null,
            targetTitle = if (opened) targetTitle?.trim()?.take(SearchHistoryRules.MAX_QUERY_LENGTH * 2) else null,
            imageUrl = if (opened) imageUrl else null,
        )
        withContext(Dispatchers.IO) { dao.upsert(entry) }
        return true
    }

    /** The whole history (50 rows at most), newest use first. */
    suspend fun recent(): List<SearchHistoryEntity> = withContext(Dispatchers.IO) {
        dao.getRecentSearches(SearchHistoryRules.CAP)
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clearAll() }

    /** Puts rows removed a moment ago back (the Undo of a remove or Clear all), ids and counts as they were. */
    suspend fun putBack(rows: List<SearchHistoryEntity>) = withContext(Dispatchers.IO) {
        dao.writeMerged(rows)
    }
}
