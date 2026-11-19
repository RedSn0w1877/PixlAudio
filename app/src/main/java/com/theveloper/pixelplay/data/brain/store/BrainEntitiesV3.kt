package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * What the Spotify Web API already returns with a track and the app used to throw away (docs/brain-spec.md,
 * 3.3 B, 13.1 `BrainFactsSink`): the artists' Spotify ids, the album and its release date, whether the track is
 * explicit. Kept for stage 2b's track facts (year, explicit, artist ids). cache: it can be dropped and read
 * again at the next library sync.
 *
 * These facts shape picks (a year, an artist id). They are not used to train anything, and the setting
 * "Use my Spotify music to shape picks" switches the sink off (owner question 1, 15).
 */
@Entity(tableName = "spotify_fact")
data class SpotifyFactEntity(
    @PrimaryKey val spotifyId: String,
    val isrc: String?,
    /** Spotify artist ids, the primary artist first, joined by ','. */
    val artistIds: String,
    val albumId: String?,
    /** `YYYY`, `YYYY-MM` or `YYYY-MM-DD`, as Spotify gave it; null when it gave none. */
    val releaseDate: String?,
    /** `year`, `month` or `day`. */
    val releasePrecision: String?,
    /** Null when Spotify did not say. */
    val explicit: Boolean?,
    val seenAtMs: Long,
)

@Dao
interface SpotifyFactDao {
    @Upsert
    suspend fun upsert(rows: List<SpotifyFactEntity>)

    @Query("SELECT * FROM spotify_fact WHERE spotifyId IN (:ids)")
    suspend fun get(ids: List<String>): List<SpotifyFactEntity>

    @Query("SELECT COUNT(*) FROM spotify_fact")
    suspend fun count(): Int

    /** The listener switched "Use my Spotify music to shape picks" off: what was kept goes. */
    @Query("DELETE FROM spotify_fact")
    suspend fun clear()
}
