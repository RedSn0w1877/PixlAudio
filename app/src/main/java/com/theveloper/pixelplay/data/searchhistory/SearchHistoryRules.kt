package com.theveloper.pixelplay.data.searchhistory

import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.data.database.SearchHistoryEntity

/**
 * What a search history row points at. The stored value is the lower-case [wire] string; `null`
 * (or the legacy "query") means "typed and submitted, nothing opened from it".
 *
 * Same five words on iOS (docs/test-parity.md, search history row).
 */
enum class SearchHistoryKind(val wire: String) {
    QUERY("query"),
    SONG("song"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist");

    companion object {
        fun fromWire(value: String?): SearchHistoryKind? = entries.firstOrNull { it.wire == value }
    }
}

/** What the ordering and prefix rules need of a row, so they run on an entity and on the screen's item alike. */
interface SearchHistoryOrdered {
    val key: String
    val count: Int
    val timestamp: Long
}

/** True when the row points at something that was opened (a song, album, artist or playlist), not just a typed query. */
fun SearchHistoryEntity.hasTarget(): Boolean =
    SearchHistoryKind.fromWire(kind).let { it != null && it != SearchHistoryKind.QUERY }

/** A row of the pre-6 table: what a migration or an old backup knows about. */
data class LegacySearchRow(val query: String, val timestamp: Long, val count: Int = 1, val id: Long = 0L)

/** What one migration pass keeps: [survivors] are updated in place, every other id is deleted. */
class SearchHistoryMigrationPlan(
    val survivors: List<Survivor>,
    val deleteIds: List<Long>,
) {
    class Survivor(val id: Long, val query: String, val key: String, val timestamp: Long, val count: Int)
}

/**
 * The search history's rules, with no Android in them (docs/test-parity.md, search history row; the iOS twin
 * uses the same constants and the same key).
 *
 *  - A row is the query as typed (trimmed, 100 characters at most), 2 characters at least.
 *  - Its identity is the key: `BrainText.fold(query)`, the same fold that both apps pin to the golden file, so
 *    "Beyonce", "BEYONCE" and "Beyoncé" are one row and Vietnamese diacritics fold identically on both apps.
 *    A query that folds to nothing ("!!") has no key and is not stored.
 *  - Recording again keeps the newest display text, bumps the time and the count.
 *  - 50 rows at most: the oldest go first.
 */
object SearchHistoryRules {
    const val MIN_QUERY_LENGTH = 2
    const val MAX_QUERY_LENGTH = 100
    const val CAP = 50
    const val SUGGESTION_LIMIT = 5
    const val RECENT_STRIP_LIMIT = 10

    /** The query as it will be stored: trimmed, cut to [MAX_QUERY_LENGTH], or null when it is too short to keep. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        val cut = if (trimmed.length > MAX_QUERY_LENGTH) trimmed.substring(0, MAX_QUERY_LENGTH).trimEnd() else trimmed
        return cut.takeIf { it.length >= MIN_QUERY_LENGTH }
    }

    /** The de-dupe key of an already normalised query, or null when it folds to nothing. */
    fun keyOf(query: String): String? = BrainText.fold(query).takeIf { it.isNotEmpty() }

    /** What is typed so far, folded for a prefix match; empty when there is nothing to match yet. */
    fun prefixOf(typed: String): String = BrainText.fold(typed)

    /**
     * The row after recording [incoming] on top of [existing] (null = first time). The newest display text wins, the
     * time moves up, the count goes up by one. A recorded result (kind != null) replaces the target; a plain submit
     * keeps the one already there.
     */
    fun applyRecord(existing: SearchHistoryEntity?, incoming: SearchHistoryEntity): SearchHistoryEntity {
        if (existing == null) return incoming.copy(count = 1)
        val hasTarget = incoming.hasTarget()
        return existing.copy(
            query = incoming.query,
            timestamp = maxOf(existing.timestamp, incoming.timestamp),
            count = existing.count + 1,
            kind = if (hasTarget) incoming.kind else existing.kind,
            targetId = if (hasTarget) incoming.targetId else existing.targetId,
            targetTitle = if (hasTarget) incoming.targetTitle else existing.targetTitle,
            imageUrl = if (hasTarget) incoming.imageUrl else existing.imageUrl,
        )
    }

    /** Newest first, then the most used, then the newest id: a total order, so two runs agree. */
    private val recentOrder: Comparator<SearchHistoryEntity> =
        compareByDescending<SearchHistoryEntity> { it.timestamp }
            .thenByDescending { it.count }
            .thenByDescending { it.id }

    /** The [cap] newest rows. */
    fun capped(rows: List<SearchHistoryEntity>, cap: Int = CAP): List<SearchHistoryEntity> =
        rows.sortedWith(recentOrder).take(cap)

    /**
     * The typed-prefix suggestions: rows whose key starts with the folded [typed], most used first, then newest,
     * [limit] at most. Nothing for a blank or symbol-only prefix. Runs over the in-memory list (50 rows), never the
     * database, so typing costs no query.
     */
    fun <T : SearchHistoryOrdered> suggest(rows: List<T>, typed: String, limit: Int = SUGGESTION_LIMIT): List<T> {
        val prefix = prefixOf(typed)
        if (prefix.isEmpty()) return emptyList()
        return rows.asSequence()
            .filter { it.key.startsWith(prefix) }
            // Stable: rows with the same count and time stay in the order they came in (newest first from the table).
            .sortedWith(compareByDescending<T> { it.count }.thenByDescending { it.timestamp })
            .take(limit)
            .toList()
    }

    /**
     * The migration pass over the rows the 5-to-6 table already holds: compute each key, collapse the rows that share
     * one (newest text and time win, the counts add up) and drop the ones that fold to nothing. Rows beyond the cap are
     * dropped, oldest first.
     */
    fun planMigration(rows: List<LegacySearchRow>): SearchHistoryMigrationPlan {
        val groups = LinkedHashMap<String, MutableList<LegacySearchRow>>()
        val deletes = ArrayList<Long>()
        for (row in rows.sortedWith(compareByDescending<LegacySearchRow> { it.timestamp }.thenByDescending { it.id })) {
            val normal = normalise(row.query)
            val key = normal?.let { keyOf(it) }
            if (normal == null || key == null) {
                deletes += row.id
            } else {
                groups.getOrPut(key) { ArrayList() } += row.copy(query = normal)
            }
        }
        val survivors = ArrayList<SearchHistoryMigrationPlan.Survivor>(groups.size)
        for ((key, group) in groups) {
            val newest = group.first()
            survivors += SearchHistoryMigrationPlan.Survivor(
                id = newest.id,
                query = newest.query,
                key = key,
                timestamp = newest.timestamp,
                count = group.sumOf { it.count.coerceAtLeast(1) },
            )
            for (i in 1 until group.size) deletes += group[i].id
        }
        survivors.sortWith(
            compareByDescending<SearchHistoryMigrationPlan.Survivor> { it.timestamp }.thenByDescending { it.id },
        )
        while (survivors.size > CAP) deletes += survivors.removeAt(survivors.size - 1).id
        return SearchHistoryMigrationPlan(survivors, deletes)
    }

    /**
     * What the table holds after restoring [incoming] on top of [existing]: the keys are recomputed (an old payload has
     * none), rows of one key are merged, the table is never emptied. The merge is idempotent: restoring the same backup
     * twice changes nothing (counts take the larger side, they do not add), so the result is safe to re-run.
     *
     * Returns the rows to write: existing rows that changed keep their id, new ones carry id 0 for the database to number.
     */
    fun mergeRestore(existing: List<SearchHistoryEntity>, incoming: List<SearchHistoryBackupRow>): List<SearchHistoryEntity> {
        val byKey = LinkedHashMap<String, SearchHistoryEntity>()
        for (row in existing) byKey[row.key] = row
        // One backup can itself hold several spellings of a query; they are separate uses, so they add up.
        val fromBackup = LinkedHashMap<String, SearchHistoryEntity>()
        for (raw in incoming.sortedBy { it.timestamp ?: 0L }) {
            val row = raw.toEntityOrNull() ?: continue
            val before = fromBackup[row.key]
            fromBackup[row.key] = if (before == null) row else applyRecord(before, row).copy(count = before.count + row.count)
        }
        for ((key, theirs) in fromBackup) {
            val mine = byKey[key]
            byKey[key] = if (mine == null) {
                theirs
            } else {
                val theirsNewer = theirs.timestamp > mine.timestamp
                val winner = if (theirsNewer) theirs else mine
                val withTarget = if (winner.hasTarget()) winner else listOf(theirs, mine).firstOrNull { it.hasTarget() } ?: winner
                mine.copy(
                    query = winner.query,
                    timestamp = maxOf(mine.timestamp, theirs.timestamp),
                    count = maxOf(mine.count, theirs.count),
                    kind = withTarget.kind,
                    targetId = withTarget.targetId,
                    targetTitle = withTarget.targetTitle,
                    imageUrl = withTarget.imageUrl,
                )
            }
        }
        return capped(byKey.values.toList())
    }
}
