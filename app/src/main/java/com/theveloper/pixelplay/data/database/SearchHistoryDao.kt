package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRules

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SearchHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchHistoryEntity>)

    @Update
    suspend fun update(item: SearchHistoryEntity)

    /** Newest use first. At most [SearchHistoryRules.CAP] rows exist, so this is the whole table. */
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC, `count` DESC, id DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): SearchHistoryEntity?

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC, `count` DESC, id DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int

    /** Drops everything but the [cap] newest rows. */
    @Query(
        "DELETE FROM search_history WHERE id NOT IN " +
            "(SELECT id FROM search_history ORDER BY timestamp DESC, `count` DESC, id DESC LIMIT :cap)"
    )
    suspend fun trimToCap(cap: Int)

    /**
     * One use of [entry]: a new row, or the row of the same key moved up (newest text, newest time, count + 1), then the
     * table cut to the cap. One transaction, so the cap and the unique key hold after every write.
     */
    @Transaction
    suspend fun upsert(entry: SearchHistoryEntity) {
        val merged = SearchHistoryRules.applyRecord(findByKey(entry.key), entry)
        insert(merged)
        trimToCap(SearchHistoryRules.CAP)
    }

    /** Writes [rows] (a restore's merged set) on top of what is there, never clearing, then cuts to the cap. */
    @Transaction
    suspend fun writeMerged(rows: List<SearchHistoryEntity>) {
        if (rows.isNotEmpty()) insertAll(rows)
        trimToCap(SearchHistoryRules.CAP)
    }

    /** Replaces the whole table: only a rollback to a snapshot of this same table does this. */
    @Transaction
    suspend fun replaceAll(items: List<SearchHistoryEntity>) {
        clearAll()
        if (items.isNotEmpty()) insertAll(items)
    }
}
