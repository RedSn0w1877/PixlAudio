package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface PlayableDao {
    @Upsert
    suspend fun upsert(rows: List<PlayableEntity>)

    @Query("SELECT * FROM playable WHERE synthId = :synthId")
    suspend fun get(synthId: String): PlayableEntity?

    @Query("SELECT videoId FROM playable WHERE synthId = :synthId")
    suspend fun videoIdFor(synthId: String): String?

    @Query("SELECT * FROM playable WHERE synthId IN (:synthIds)")
    suspend fun getAll(synthIds: List<String>): List<PlayableEntity>

    @Query("SELECT * FROM playable WHERE songId IN (:songIds)")
    suspend fun getBySongIds(songIds: List<Long>): List<PlayableEntity>

    @Query("SELECT * FROM playable WHERE songId = :songId")
    suspend fun getBySongId(songId: Long): PlayableEntity?

    @Query("UPDATE playable SET lastUsedAt = :nowMs WHERE synthId IN (:synthIds)")
    suspend fun touch(synthIds: List<String>, nowMs: Long)

    /** Rows nobody used since [olderThanMs], except [keep] (the saved queue, recent history). */
    @Query("DELETE FROM playable WHERE lastUsedAt < :olderThanMs AND synthId NOT IN (:keep)")
    suspend fun deleteUnused(olderThanMs: Long, keep: List<String>): Int

    @Query("DELETE FROM playable WHERE synthId = :synthId")
    suspend fun delete(synthId: String)

    @Query("SELECT COUNT(*) FROM playable")
    suspend fun count(): Int
}

@Dao
interface AliasDao {
    @Query("SELECT rid FROM track_alias WHERE `key` = :key")
    fun trackId(key: String): Long?

    @Query("SELECT durationMs FROM track_alias WHERE `key` = :key")
    fun trackDurationMs(key: String): Int?

    @Query("SELECT EXISTS(SELECT 1 FROM track_alias WHERE rid = :rid)")
    fun trackIdTaken(rid: Long): Boolean

    @Query("SELECT `into` FROM track_merge WHERE rid = :rid")
    fun mergedInto(rid: Long): Long?

    @Query("SELECT * FROM track_merge")
    fun allMerges(): List<TrackMergeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTrackAliases(rows: List<TrackAliasEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMerge(row: TrackMergeEntity)

    /** Flattens chains: whatever pointed at [from] now points at [into]. */
    @Query("UPDATE track_merge SET `into` = :into WHERE `into` = :from")
    fun repointMerges(from: Long, into: Long)

    @Query("SELECT aid FROM artist_alias WHERE `key` = :key")
    fun artistId(key: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM artist_alias WHERE aid = :aid)")
    fun artistIdTaken(aid: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertArtistAliases(rows: List<ArtistAliasEntity>)

    @Query("SELECT COUNT(*) FROM track_alias")
    fun trackAliasCount(): Int

    /** Every key stored for [rid]; used to find another audio id of a recording whose radio was junk. */
    @Query("SELECT `key` FROM track_alias WHERE rid = :rid")
    fun trackKeys(rid: Long): List<String>
}

@Dao
interface RadioListDao {
    @Query("SELECT * FROM radio_list WHERE seedRid = :seedRid AND kind = :kind AND chip = :chip")
    suspend fun get(seedRid: Long, kind: Int, chip: String = ""): RadioListEntity?

    @Upsert
    suspend fun upsert(row: RadioListEntity)

    @Query("UPDATE radio_list SET lastUsedAt = :nowMs WHERE seedRid = :seedRid AND kind = :kind AND chip = :chip")
    suspend fun touch(seedRid: Long, kind: Int, chip: String, nowMs: Long)

    @Query("SELECT COUNT(*) FROM radio_list")
    suspend fun count(): Int

    /** Keeps the [keep] most recently used lists. */
    @Query(
        "DELETE FROM radio_list WHERE rowid IN (" +
            "SELECT rowid FROM radio_list ORDER BY lastUsedAt DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun trimTo(keep: Int)

    /** How many radios were fetched from the network since [sinceMs]: the daily request budget. */
    @Query("SELECT COUNT(*) FROM radio_list WHERE fetchedAt >= :sinceMs")
    suspend fun fetchedSince(sinceMs: Long): Int
}

@Dao
interface PlayEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PlayEventEntity): Long

    @Query("SELECT COUNT(*) FROM play_event")
    suspend fun count(): Int

    /**
     * Recordings the listener ignored three times (6.1), the last time at or after [sinceMs]: so a
     * song stays away for the 60 days that follow its third skip, not the 60 that follow its first.
     *
     * An ignore is an engine-served play (`arm` 9 is NONE) that sounded for under 30 s and that the
     * listener ended: `endReason` 1 next, 3 pickedOther, 5 queueEdit. A play that never sounded (flag
     * 8, an error, a blocked artist stepped over) is not one. A song they ever heard for 30 s or more,
     * from anywhere, was not ignored.
     */
    @Query(
        "SELECT rid FROM play_event WHERE playedMs >= 0 AND (flags & 4096) = 0 GROUP BY rid HAVING " +
            "SUM(CASE WHEN arm != 9 AND playedMs > 0 AND playedMs < 30000 AND (flags & 8) = 0 " +
            "AND endReason IN (1, 3, 5) THEN 1 ELSE 0 END) >= 3 " +
            "AND SUM(CASE WHEN playedMs >= 30000 THEN 1 ELSE 0 END) = 0 " +
            "AND MAX(CASE WHEN arm != 9 AND playedMs > 0 AND playedMs < 30000 AND (flags & 8) = 0 " +
            "AND endReason IN (1, 3, 5) THEN startedAtMs ELSE 0 END) >= :sinceMs"
    )
    suspend fun ignoredThreeTimes(sinceMs: Long): List<Long>

    /** The synchronous insert the recorder uses inside its own transaction. -1 when the id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBlocking(event: PlayEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(events: List<PlayEventEntity>): List<Long>

    /** What makes the row under [eventId] the play it is; null when the id is free. */
    @Query("SELECT origin, keyAtWrite FROM play_event WHERE eventId = :eventId")
    fun identityAt(eventId: Long): EventIdentity?

    /** Keyset paging in replay order. */
    @Query("SELECT * FROM play_event WHERE eventId > :afterEventId ORDER BY eventId LIMIT :limit")
    fun pageBlocking(afterEventId: Long, limit: Int): List<PlayEventEntity>

    @Query("SELECT COUNT(*) FROM play_event WHERE origin = :origin")
    fun countByOrigin(origin: Int): Int

    /** Every play of the recordings in [rids] (at most a few hundred): "Don't count this" on a song, album or playlist. */
    @Query("SELECT * FROM play_event WHERE rid IN (:rids) ORDER BY eventId")
    fun byRecordingsBlocking(rids: List<Long>): List<PlayEventEntity>

    /** Live plays that started at or after [sinceMs]: "Don't count" the last hour. */
    @Query("SELECT * FROM play_event WHERE origin = 0 AND startedAtMs >= :sinceMs ORDER BY eventId")
    fun liveSinceBlocking(sinceMs: Long): List<PlayEventEntity>

    @Query("UPDATE play_event SET flags = flags | :flag WHERE eventId IN (:ids)")
    fun setFlagBlocking(ids: List<Long>, flag: Int)

    /** [mask] is the flags to keep: `flag.inv()`. */
    @Query("UPDATE play_event SET flags = flags & :mask WHERE eventId IN (:ids)")
    fun maskFlagsBlocking(ids: List<Long>, mask: Int)

    /** The last [limit] live plays by [aid], newest first, as what the "why" sheet needs: how each ended. */
    @Query(
        "SELECT CASE WHEN endReason = 0 OR (durationMs > 0 AND playedMs >= 0.8 * durationMs) THEN 1 ELSE 0 END AS finished " +
            "FROM play_event WHERE origin = 0 AND primaryAid = :aid AND (flags & 8) = 0 AND (flags & 4096) = 0 " +
            "ORDER BY startedAtMs DESC LIMIT :limit"
    )
    suspend fun recentFinishedByArtist(aid: Long, limit: Int): List<Int>

    /**
     * The plays of the recordings in [rids] since [sinceMs], oldest first, without the ones that never sounded or were
     * excluded from the picks: what repeat activation (5.6) is computed from.
     */
    @Query(
        "SELECT rid, startedAtMs, playedMs, durationMs, endReason, flags, origin, surface, arm FROM play_event " +
            "WHERE rid IN (:rids) AND startedAtMs >= :sinceMs AND (flags & 4104) = 0 ORDER BY startedAtMs"
    )
    suspend fun stampsOf(rids: List<Long>, sinceMs: Long): List<PlayStamp>

    /** Recordings with any play since [sinceMs] (the 4-hour rule of 6.4). */
    @Query("SELECT DISTINCT rid FROM play_event WHERE startedAtMs >= :sinceMs")
    suspend fun playedSince(sinceMs: Long): List<Long>
}

@Dao
interface AffinityDao {
    @Upsert
    suspend fun upsert(rows: List<AffinityEntity>)

    @Query("SELECT * FROM affinity WHERE family = :family AND `key` IN (:keys)")
    suspend fun get(family: Int, keys: List<Long>): List<AffinityEntity>

    @Query("DELETE FROM affinity")
    suspend fun clear()

    /** Every row: the brain's whole affinity state, read once per process, off the main thread. */
    @Query("SELECT * FROM affinity")
    fun allBlocking(): List<AffinityEntity>

    @Upsert
    fun upsertBlocking(rows: List<AffinityEntity>)

    @Query("DELETE FROM affinity")
    fun clearBlocking()
}

/** What repeat activation needs of one past play. */
data class PlayStamp(
    val rid: Long,
    val startedAtMs: Long,
    val playedMs: Int,
    val durationMs: Int,
    val endReason: Int,
    val flags: Int,
    val origin: Int,
    val surface: Int,
    val arm: Int,
)

/** `origin` and `keyAtWrite` of one stored event. */
data class EventIdentity(val origin: Int, val keyAtWrite: String)

@Dao
interface BrainStateDao {
    @Query("SELECT blob FROM brain_state WHERE `key` = :key")
    suspend fun get(key: String): ByteArray?

    @Upsert
    suspend fun put(row: BrainStateEntity)

    @Query("SELECT blob FROM brain_state WHERE `key` = :key")
    fun getBlocking(key: String): ByteArray?

    @Upsert
    fun putBlocking(row: BrainStateEntity)

    @Query("DELETE FROM brain_state WHERE `key` = :key")
    fun deleteBlocking(key: String)
}
