package com.theveloper.pixelplay.data.searchhistory

import com.google.gson.annotations.SerializedName
import com.theveloper.pixelplay.data.database.SearchHistoryEntity

/**
 * A row of the SEARCH_HISTORY backup module. Every field is nullable on purpose: Gson builds the object without running
 * Kotlin defaults, so a payload from before the key/kind/count columns existed (or one from an older app, or the iOS
 * app) reads with those fields null instead of a zero that looks real.
 *
 * The JSON names are the same on both apps: id, query, timestamp, key, kind, targetId, targetTitle, imageUrl, count.
 * An older reader that only knows id/query/timestamp ignores the rest. The key is written for readers' convenience but is
 * always recomputed on restore (`SearchHistoryRules.keyOf`), so it can never be trusted wrong.
 */
data class SearchHistoryBackupRow(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("query") val query: String? = null,
    @SerializedName("timestamp") val timestamp: Long? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("targetId") val targetId: String? = null,
    @SerializedName("targetTitle") val targetTitle: String? = null,
    @SerializedName("imageUrl") val imageUrl: String? = null,
    @SerializedName("count") val count: Int? = null,
) {
    /**
     * The row as it will be stored (id 0, key recomputed), or null when it cannot be kept: no text, too short, a query that
     * folds to nothing. An unknown kind is dropped to a plain query row.
     */
    fun toEntityOrNull(): SearchHistoryEntity? {
        val text = SearchHistoryRules.normalise(query ?: return null) ?: return null
        val foldedKey = SearchHistoryRules.keyOf(text) ?: return null
        val knownKind = SearchHistoryKind.fromWire(kind)
        val opened = knownKind != null && knownKind != SearchHistoryKind.QUERY && !targetId.isNullOrBlank()
        return SearchHistoryEntity(
            id = 0L,
            query = text,
            timestamp = timestamp ?: 0L,
            key = foldedKey,
            kind = if (opened) knownKind!!.wire else null,
            targetId = if (opened) targetId else null,
            targetTitle = if (opened) targetTitle?.let { SearchHistoryRules.cutUnits(it, SearchHistoryRules.MAX_TARGET_TITLE_LENGTH) } else null,
            imageUrl = if (opened) imageUrl else null,
            count = (count ?: 1).coerceIn(1, MAX_COUNT),
        )
    }

    companion object {
        /** A guard against a hand-edited backup, nothing a listener reaches. */
        const val MAX_COUNT = 1_000_000

        fun from(entity: SearchHistoryEntity) = SearchHistoryBackupRow(
            id = entity.id,
            query = entity.query,
            timestamp = entity.timestamp,
            key = entity.key,
            kind = entity.kind,
            targetId = entity.targetId,
            targetTitle = entity.targetTitle,
            imageUrl = entity.imageUrl,
            count = entity.count,
        )
    }
}
