package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One shelf of one visit to the Home page (docs/brain-spec.md, 8.1, 5.10): the compact impression the shelf bandit learns from. A visit is
 * the plan being shown; its rows are the shelves in page order. [seen] says the shelf was on screen for at least a second, [activatedAtMs]
 * when the listener started something from it (0 for never), [settled] that the visit has been given to the bandit. Raw facts only. The reward (a play of 30 seconds or more
 * from the shelf) is worked out from the event log when the visit is settled and is not stored here, so the bandit's posteriors (kept in
 * `brain_state`) cannot be made again from these rows alone; [com.theveloper.pixelplay.brain.ShelfBandit.rebuild] is for tests.
 *
 * Kept small: at most [ImpressionDao.KEEP_VISITS] visits and [ImpressionDao.KEEP_DAYS] days, pruned when a visit is added. Nothing in a row
 * names a track or the listener.
 */
@Entity(tableName = "impression", primaryKeys = ["visitId", "position"], indices = [Index("shownAtMs")])
data class ImpressionEntity(
    /** The time the page was shown, in ms; one value per visit. */
    val visitId: Long,
    /** The shelf's place on the page, 0 at the top. */
    val position: Int,
    /** `HomePlanner` shelf type (or the daypart mix). */
    val shelf: Int,
    /** `HomePlanner.daypart` of the visit. */
    val daypart: Int,
    val shownAtMs: Long,
    /** 1 when the shelf was on screen for at least a second. */
    val seen: Int,
    val activatedAtMs: Long,
    val settled: Int,
)

@Dao
interface ImpressionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ImpressionEntity>)

    /** Marks the shelves from the top down to [position] as seen: the listener looked down the page that far. */
    @Query("UPDATE impression SET seen = 1 WHERE visitId = :visitId AND position <= :position")
    suspend fun markSeenThrough(visitId: Long, position: Int)

    @Query("UPDATE impression SET activatedAtMs = :atMs WHERE visitId = :visitId AND position = :position AND activatedAtMs = 0")
    suspend fun markActivated(visitId: Long, position: Int, atMs: Long)

    /** Visits not yet given to the bandit that were shown before [before]. Rows in visit and page order. */
    @Query("SELECT * FROM impression WHERE settled = 0 AND visitId < :before ORDER BY visitId, position")
    suspend fun unsettled(before: Long): List<ImpressionEntity>

    @Query("UPDATE impression SET settled = 1 WHERE visitId IN (:visits)")
    suspend fun settle(visits: List<Long>)

    @Query("SELECT * FROM impression ORDER BY visitId, position")
    suspend fun all(): List<ImpressionEntity>

    @Query("DELETE FROM impression WHERE shownAtMs < :before")
    suspend fun pruneOlderThan(before: Long)

    /** Keeps only the newest [keep] visits. */
    @Query("DELETE FROM impression WHERE visitId NOT IN (SELECT DISTINCT visitId FROM impression ORDER BY visitId DESC LIMIT :keep)")
    suspend fun keepNewestVisits(keep: Int)

    @Query("DELETE FROM impression")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM impression")
    suspend fun count(): Int

    companion object {
        const val KEEP_VISITS = 200
        const val KEEP_DAYS = 90
    }
}
