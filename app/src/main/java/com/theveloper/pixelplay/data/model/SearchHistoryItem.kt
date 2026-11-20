package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.database.SearchHistoryEntity
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryKind
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryOrdered

/**
 * A remembered search as the screen shows it. [kind] is null for a query that was only submitted; otherwise the row points
 * at [targetId] (a song, album, artist or playlist id) with [targetTitle] and [imageUrl] to draw it without a lookup.
 */
@Immutable
data class SearchHistoryItem(
    val id: Long? = null,
    val query: String,
    override val timestamp: Long,
    override val key: String = "",
    val kind: String? = null,
    val targetId: String? = null,
    val targetTitle: String? = null,
    val imageUrl: String? = null,
    override val count: Int = 1,
) : SearchHistoryOrdered {
    /** The kind a row opens, or null for a plain query row. */
    val openKind: SearchHistoryKind?
        get() = SearchHistoryKind.fromWire(kind)?.takeIf { it != SearchHistoryKind.QUERY && !targetId.isNullOrBlank() }

    /** What a row calls itself: the thing it opens, or the words that were typed. */
    val title: String get() = if (openKind != null) targetTitle?.takeIf { it.isNotBlank() } ?: query else query
}

fun SearchHistoryEntity.toSearchHistoryItem() = SearchHistoryItem(
    id = id,
    query = query,
    timestamp = timestamp,
    key = key,
    kind = kind,
    targetId = targetId,
    targetTitle = targetTitle,
    imageUrl = imageUrl,
    count = count,
)

fun SearchHistoryItem.toEntity() = SearchHistoryEntity(
    id = id ?: 0L,
    query = query,
    timestamp = timestamp,
    key = key,
    kind = kind,
    targetId = targetId,
    targetTitle = targetTitle,
    imageUrl = imageUrl,
    count = count,
)
