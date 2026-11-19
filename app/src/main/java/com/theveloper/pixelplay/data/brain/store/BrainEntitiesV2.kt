package com.theveloper.pixelplay.data.brain.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * The tables version 2 of `brain.db` adds (docs/brain-spec.md, 3.8, stage 2a): the persisted
 * similarity graph, the served log and the feedback records. `graph_edge` and `served_list` are
 * caches; `feedback` is truth and must survive every upgrade.
 */

/**
 * cache. One edge of the similarity graph (3.6): [src] and [dst] are recording ids, [wQ16] the weight
 * times 65536, [rank] the row's place in the list that produced it (1 for the first non-seed row).
 * Edges older than the radio TTL are dropped; each node keeps its 64 heaviest when the graph is built.
 */
@Entity(
    tableName = "graph_edge",
    primaryKeys = ["src", "dst", "type"],
    indices = [Index("dst"), Index("fetchedAt")],
)
data class GraphEdgeEntity(
    val src: Long,
    val dst: Long,
    /** [TYPE_RADIO] or [TYPE_RELATED]; the other types of 3.6 arrive with the stages that fetch them. */
    val type: Int,
    val wQ16: Int,
    val rank: Int,
    val fetchedAt: Long,
) {
    companion object {
        const val TYPE_RADIO = 1
        const val TYPE_RELATED = 2

        /** A list tuned with a chip (and a continuation page): its own type, so it never replaces the first page's edges. */
        const val TYPE_CHIP = 3
        const val UNIT = 65_536.0
    }
}

/**
 * cache, 60 days. One generated list the app served (6.5, 8.2): the repetition budget reads which
 * recordings it held, and the saved Daily Mix and Your Mix are the newest row of their surface for the
 * local day. [itemsBlob] is `MixCodec`'s gzip of the list.
 */
@Entity(tableName = "served_list", indices = [Index("builtAt")])
data class ServedListEntity(
    @PrimaryKey val recId: Long,
    /** `Surface` code: 1 daily mix, 2 your mix. */
    val surface: Int,
    val surfaceId: String,
    val builtAt: Long,
    val seed: Long,
    val modelVersion: Int,
    /** The novelty share the list was built with, times 1000. */
    val nuQ: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val itemsBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ServedListEntity && other.recId == recId && other.builtAt == builtAt && other.itemsBlob.contentEquals(itemsBlob)

    override fun hashCode(): Int = recId.hashCode()
}

/**
 * truth. A thing the listener told the brain that is not a taste adjustment (3.8): "Don't learn from
 * this" and the spans of a private session arrive in the next milestone of stage 2; the table is here
 * now so that milestone needs no second change to the file. "Not for me" is a `TasteAdjustments` mute,
 * not a row here.
 */
@Entity(tableName = "feedback", indices = [Index("targetKind", "targetId")])
data class FeedbackEntity(
    @PrimaryKey val id: Long,
    val kind: Int,
    val targetKind: Int,
    val targetId: Long,
    val createdAtMs: Long,
    val undoneAtMs: Long?,
)

@Dao
interface GraphEdgeDao {
    @Upsert
    suspend fun upsert(rows: List<GraphEdgeEntity>)

    /** A refreshed list replaces what the earlier fetch of the same seed left. */
    @Query("DELETE FROM graph_edge WHERE src = :src AND type = :type")
    suspend fun deleteFrom(src: Long, type: Int)

    /** What a seed's earlier fetch left and the new one did not rewrite: removed after the new rows are in, so a kill in between leaves more edges, never none. */
    @Query("DELETE FROM graph_edge WHERE src = :src AND type = :type AND fetchedAt < :before")
    suspend fun deleteStale(src: Long, type: Int, before: Long)


    @Query("SELECT * FROM graph_edge WHERE src IN (:nodes) OR dst IN (:nodes)")
    suspend fun touching(nodes: List<Long>): List<GraphEdgeEntity>

    @Query("DELETE FROM graph_edge WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM graph_edge")
    suspend fun count(): Int

    /** Every edge, for the embeddings (stage 2b): a few tens of thousands at most (500 lists of 50). */
    @Query("SELECT * FROM graph_edge")
    suspend fun all(): List<GraphEdgeEntity>
}

@Dao
interface ServedListDao {
    @Upsert
    suspend fun upsert(row: ServedListEntity)

    @Query("SELECT * FROM served_list WHERE builtAt >= :since ORDER BY builtAt")
    suspend fun since(since: Long): List<ServedListEntity>

    @Query("SELECT * FROM served_list WHERE surface = :surface AND builtAt >= :since ORDER BY builtAt DESC LIMIT 1")
    suspend fun latest(surface: Int, since: Long): ServedListEntity?

    @Query("SELECT COUNT(*) FROM served_list WHERE surface = :surface AND builtAt >= :since")
    suspend fun countSince(surface: Int, since: Long): Int

    /** Stage 2b: several lists share one surface (Daily Mix 1..K, the named mixes); `surfaceId` tells them apart. */
    @Query("SELECT * FROM served_list WHERE surface = :surface AND surfaceId = :surfaceId AND builtAt >= :since ORDER BY builtAt DESC LIMIT 1")
    suspend fun latestById(surface: Int, surfaceId: String, since: Long): ServedListEntity?

    @Query("SELECT COUNT(*) FROM served_list WHERE surface = :surface AND surfaceId = :surfaceId AND builtAt >= :since")
    suspend fun countSinceById(surface: Int, surfaceId: String, since: Long): Int

    @Query("DELETE FROM served_list WHERE builtAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: FeedbackEntity)

    @Query("UPDATE feedback SET undoneAtMs = :nowMs WHERE id = :id")
    suspend fun undo(id: Long, nowMs: Long)

    @Query("SELECT * FROM feedback WHERE undoneAtMs IS NULL ORDER BY createdAtMs")
    suspend fun active(): List<FeedbackEntity>

    /** Every row, undone or not (the backup keeps the whole record). */
    @Query("SELECT * FROM feedback ORDER BY createdAtMs")
    suspend fun all(): List<FeedbackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FeedbackEntity>)

    @Query("DELETE FROM feedback")
    suspend fun clear()
}

/** What a seed pick reads from the event log and the radio cache (3.6, "Stage 2 before islands exist"). */
data class RecentTrackRow(val rid: Long, val aid: Long, val lastMs: Long)

data class RadioAgeRow(val seedRid: Long, val fetchedAt: Long)

@Dao
interface SeedDao {
    /**
     * The tracks the listener chose and finished since [since], newest first: an event that no
     * recommendation produced, or an engine pick the listener liked, saved or replayed (5.12 rule 10).
     * A play that never sounded (flag 8) is not one. `endReason` 0 is "track done".
     */
    @Query(
        "SELECT rid AS rid, primaryAid AS aid, MAX(startedAtMs) AS lastMs FROM play_event " +
            "WHERE startedAtMs >= :since AND endReason = 0 AND (flags & 4104) = 0 AND playedMs >= 30000 " +
            "AND (arm = 9 OR (flags & 1216) != 0) GROUP BY rid ORDER BY lastMs DESC LIMIT :limit"
    )
    suspend fun recentChosen(since: Long, limit: Int): List<RecentTrackRow>

    /** Tracks the listener finished (30 s or more, sounded) since [since], newest first: "More like <album>" (8.1). */
    @Query(
        "SELECT rid AS rid, primaryAid AS aid, MAX(startedAtMs) AS lastMs FROM play_event " +
            "WHERE startedAtMs >= :since AND endReason = 0 AND (flags & 4104) = 0 AND playedMs >= 30000 " +
            "GROUP BY rid ORDER BY lastMs DESC LIMIT :limit"
    )
    suspend fun recentCompleted(since: Long, limit: Int): List<RecentTrackRow>

    /** Tracks liked or saved while they played since [since] (flags 64 and 128), newest first. */
    @Query(
        "SELECT rid AS rid, primaryAid AS aid, MAX(startedAtMs) AS lastMs FROM play_event " +
            "WHERE startedAtMs >= :since AND (flags & 192) != 0 AND (flags & 4096) = 0 GROUP BY rid ORDER BY lastMs DESC LIMIT :limit"
    )
    suspend fun recentlySaved(since: Long, limit: Int): List<RecentTrackRow>

    /** The primary artist id each of [rids] was last logged with. */
    @Query("SELECT rid AS rid, primaryAid AS aid, MAX(startedAtMs) AS lastMs FROM play_event WHERE rid IN (:rids) AND primaryAid != 0 GROUP BY rid")
    suspend fun artistsOf(rids: List<Long>): List<RecentTrackRow>

    /** Which of [rids] have a healthy cached list, and when it was fetched (the newest of its kinds). */
    @Query("SELECT seedRid AS seedRid, MAX(fetchedAt) AS fetchedAt FROM radio_list WHERE healthy = 1 AND seedRid IN (:rids) GROUP BY seedRid")
    suspend fun cachedLists(rids: List<Long>): List<RadioAgeRow>

    @Query("SELECT * FROM playable WHERE rid IN (:rids)")
    suspend fun playableByRid(rids: List<Long>): List<PlayableEntity>

    /** Every healthy list of the library's own tracks: what a mix reads. */
    @Query("SELECT * FROM radio_list WHERE healthy = 1 AND seedRid IN (:rids)")
    suspend fun listsOf(rids: List<Long>): List<RadioListEntity>

    /** One page of every healthy list, for the one-time conversion of stage 1's lists into edges (3.6). */
    @Query("SELECT * FROM radio_list WHERE healthy = 1 ORDER BY seedRid, kind, chip LIMIT :limit OFFSET :offset")
    suspend fun healthyPage(limit: Int, offset: Int): List<RadioListEntity>

    /** Plays the listener finished (track done, 30 s or more, sounded) since [since]: the islands refit after 200 (5.4). */
    @Query("SELECT COUNT(*) FROM play_event WHERE startedAtMs >= :since AND endReason = 0 AND playedMs >= 30000 AND (flags & 4104) = 0")
    suspend fun qualifiedPlaysSince(since: Long): Int

    /** How many healthy radio lists are cached (the "Getting to know your music" strip, 8.10). */
    @Query("SELECT COUNT(*) FROM radio_list WHERE healthy = 1")
    suspend fun healthyListCount(): Int

    /** All radios fetched since [since]: the daily request budget the worker shares with the foreground. */
    @Query("SELECT COUNT(*) FROM radio_list WHERE fetchedAt >= :since")
    suspend fun fetchedSince(since: Long): Int
}
