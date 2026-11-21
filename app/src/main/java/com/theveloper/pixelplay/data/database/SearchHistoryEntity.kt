package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryOrdered

/**
 * One remembered search. [query] is what was typed (trimmed, 100 characters at most) and the text the row shows;
 * [key] is `BrainText.fold(query)`, the identity of the row (unique), so spellings of the same words are one row.
 * [kind]/[targetId]/[targetTitle]/[imageUrl] describe what was opened from the search (a song, album, artist or
 * playlist) and are null for a query that was only submitted. [timestamp] is the last use, [count] the number of uses.
 * The rules live in `SearchHistoryRules`.
 */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["key"], unique = true)]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "timestamp")
    override val timestamp: Long,
    @ColumnInfo(name = "key", defaultValue = "''")
    override val key: String = "",
    @ColumnInfo(name = "kind")
    val kind: String? = null,
    @ColumnInfo(name = "target_id")
    val targetId: String? = null,
    @ColumnInfo(name = "target_title")
    val targetTitle: String? = null,
    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,
    @ColumnInfo(name = "count", defaultValue = "1")
    override val count: Int = 1,
) : SearchHistoryOrdered
