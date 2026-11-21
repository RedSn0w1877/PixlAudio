package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.database.SearchHistoryDao
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryBackupRow
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRules
import com.theveloper.pixelplay.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SEARCH_HISTORY module. Export writes every column (id, query, timestamp, key, kind, targetId, targetTitle, imageUrl,
 * count); an older reader that only knows the first three ignores the rest. Restore reads rows from any app version (the
 * new fields default), recomputes every key, merges with what is already there by key and keeps the 50 newest. It never
 * clears the table.
 */
@Singleton
class SearchHistoryModuleHandler @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.SEARCH_HISTORY

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(searchHistoryDao.getAll().map { SearchHistoryBackupRow.from(it) })
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        searchHistoryDao.count()
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val incoming = parse(gson, payload)
        val merged = SearchHistoryRules.mergeRestore(searchHistoryDao.getAll(), incoming)
        searchHistoryDao.writeMerged(merged)
    }

    /** A rollback puts the snapshot back as it was, so it replaces instead of merging (the snapshot is the full prior table). */
    override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
        val rows = parse(gson, snapshot).mapNotNull { it.toEntityOrNull() }
        searchHistoryDao.replaceAll(SearchHistoryRules.capped(rows))
    }

    companion object {
        /** The rows of a payload; an element that is not an object or cannot be read is skipped, never fatal. */
        fun parse(gson: Gson, payload: String): List<SearchHistoryBackupRow> {
            val root = JsonParser.parseString(payload)
            if (!root.isJsonArray) return emptyList()
            val rows = ArrayList<SearchHistoryBackupRow>(root.asJsonArray.size())
            for (element in root.asJsonArray) {
                if (!element.isJsonObject) continue
                try {
                    gson.fromJson(element, SearchHistoryBackupRow::class.java)?.let { rows += it }
                } catch (e: JsonParseException) {
                    // One unreadable row does not cost the others.
                }
            }
            return rows
        }
    }
}
