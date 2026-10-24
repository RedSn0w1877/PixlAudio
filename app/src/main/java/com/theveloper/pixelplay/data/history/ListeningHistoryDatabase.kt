package com.theveloper.pixelplay.data.history

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Imported plays, one row each. A database of its own (not a table in PixelPlayDatabase) so the main
 * schema stays untouched and clearing imported history is one statement. `dedupe_key` is unique: inserting
 * the same play twice is ignored.
 */
@Entity(
    tableName = "imported_plays",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["track_key"]),
        Index(value = ["artist_key"]),
        Index(value = ["played_at"]),
    ]
)
data class ImportedPlayEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
    /** [HistorySource.key]. */
    @ColumnInfo(name = "source") val source: String,
    /** [HistoryFormat]. */
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @ColumnInfo(name = "track_key") val trackKey: String,
    @ColumnInfo(name = "artist_key") val artistKey: String,
    @ColumnInfo(name = "track_name") val trackName: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String?,
    @ColumnInfo(name = "spotify_track_id") val spotifyTrackId: String?,
    @ColumnInfo(name = "video_id") val videoId: String?,
    @ColumnInfo(name = "ms_played") val msPlayed: Long?,
    /** [PlayOutcome.code]. */
    @ColumnInfo(name = "outcome") val outcome: Int,
    /** The source weight at import time (Spotify 1.0, YouTube Music 0.35). */
    @ColumnInfo(name = "weight") val weight: Double,
    /** Heard and not an early skip: counts in top lists. */
    @ColumnInfo(name = "qualified") val qualified: Boolean,
    @ColumnInfo(name = "voluntary") val voluntary: Boolean,
) {
    fun toRow(): HistoryRow = HistoryRow(
        trackKey = trackKey,
        artistKey = artistKey,
        trackName = trackName,
        artistName = artistName,
        spotifyTrackId = spotifyTrackId,
        videoId = videoId,
        playedAtMs = playedAt,
        msPlayed = msPlayed,
        outcome = PlayOutcome.fromCode(outcome),
        weight = weight,
        qualified = qualified,
        voluntary = voluntary,
    )

    companion object {
        fun from(play: ImportedPlay): ImportedPlayEntity = ImportedPlayEntity(
            dedupeKey = play.dedupeKey,
            source = play.source.key,
            format = play.format,
            playedAt = play.playedAtMs,
            trackKey = play.trackKey,
            artistKey = play.artistKey,
            trackName = play.trackName.take(MAX_TEXT),
            artistName = HistoryText.cleanArtist(play.artistName).take(MAX_TEXT),
            albumName = play.albumName?.take(MAX_TEXT),
            spotifyTrackId = play.spotifyTrackId,
            videoId = play.videoId,
            msPlayed = play.msPlayed,
            outcome = play.outcome.code,
            weight = play.source.weight,
            qualified = play.qualified,
            voluntary = play.voluntary,
        )

        private const val MAX_TEXT = 300
    }
}

/** Spotify's top artists/tracks and followed artists, replaced on each sync. */
@Entity(tableName = "taste_seeds")
data class TasteSeedEntity(
    /** `kind|time_range|spotify id or position`. */
    @PrimaryKey @ColumnInfo(name = "seed_key") val seedKey: String,
    /** [SeedKind.key]. */
    @ColumnInfo(name = "kind") val kind: String,
    /** `short_term` / `medium_term` / `long_term`, "" for followed artists. */
    @ColumnInfo(name = "time_range") val timeRange: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "list_size") val listSize: Int,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "track_name") val trackName: String?,
    @ColumnInfo(name = "spotify_id") val spotifyId: String?,
    /** Comma-separated genres of a top/followed artist. */
    @ColumnInfo(name = "genres") val genres: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toSeed(): TasteSeed? {
        val seedKind = SeedKind.fromKey(kind) ?: return null
        return TasteSeed(seedKind, timeRange, position, listSize, artistName, trackName, spotifyId)
    }
}

/** `GROUP BY artist_key` over qualified plays. */
data class ArtistAggregateRow(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "artistKey") val artistKey: String,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "listenedMs") val listenedMs: Long,
)

/** `GROUP BY track_key` over qualified plays. */
data class TrackAggregateRow(
    @ColumnInfo(name = "trackKey") val trackKey: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "spotifyTrackId") val spotifyTrackId: String?,
    @ColumnInfo(name = "videoId") val videoId: String?,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "listenedMs") val listenedMs: Long,
)

@Dao
abstract class ListeningHistoryDao {

    /** Returns the new row ids; -1 for each play that was already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlays(plays: List<ImportedPlayEntity>): List<Long>

    /** Keyset paging over every play (the taste rebuild reads them in chunks, never all at once). */
    @Query("SELECT * FROM imported_plays WHERE id > :afterId ORDER BY id LIMIT :limit")
    abstract suspend fun playsAfter(afterId: Long, limit: Int): List<ImportedPlayEntity>

    @Query("SELECT COUNT(*) FROM imported_plays")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM imported_plays WHERE source IN (:sources)")
    abstract suspend fun countBySources(sources: List<String>): Int

    @Query("SELECT COUNT(*) FROM imported_plays WHERE format = :format")
    abstract suspend fun countByFormat(format: String): Int

    @Query("SELECT COUNT(DISTINCT artist_key) FROM imported_plays")
    abstract suspend fun distinctArtists(): Int

    @Query("SELECT COUNT(DISTINCT track_key) FROM imported_plays")
    abstract suspend fun distinctTracks(): Int

    @Query("SELECT MIN(played_at) FROM imported_plays")
    abstract suspend fun firstPlayedAt(): Long?

    @Query("SELECT MAX(played_at) FROM imported_plays")
    abstract suspend fun lastPlayedAt(): Long?

    @Query("SELECT COALESCE(SUM(ms_played), 0) FROM imported_plays")
    abstract suspend fun totalMsPlayed(): Long

    @Query("SELECT * FROM imported_plays ORDER BY played_at DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<ImportedPlayEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM imported_plays WHERE source = :source AND video_id = :videoId)")
    abstract suspend fun hasVideoFrom(source: String, videoId: String): Boolean

    @Query(
        """
        SELECT MAX(artist_name) AS name, artist_key AS artistKey, SUM(weight) AS score, COUNT(*) AS plays,
               COALESCE(SUM(ms_played), 0) AS listenedMs
        FROM imported_plays
        WHERE qualified = 1 AND played_at >= :sinceMs AND artist_key != ''
        GROUP BY artist_key
        ORDER BY score DESC
        LIMIT :limit
        """
    )
    abstract suspend fun topArtists(sinceMs: Long, limit: Int): List<ArtistAggregateRow>

    @Query(
        """
        SELECT track_key AS trackKey, MAX(track_name) AS title, MAX(artist_name) AS artist,
               MAX(spotify_track_id) AS spotifyTrackId, MAX(video_id) AS videoId, SUM(weight) AS score,
               COUNT(*) AS plays, COALESCE(SUM(ms_played), 0) AS listenedMs
        FROM imported_plays
        WHERE qualified = 1 AND played_at >= :sinceMs
        GROUP BY track_key
        ORDER BY score DESC
        LIMIT :limit
        """
    )
    abstract suspend fun topTracks(sinceMs: Long, limit: Int): List<TrackAggregateRow>

    /**
     * A play the Web API reported that the export also holds (timestamps a second or so apart): keep the
     * export's row, which knows how long it was played and how it ended.
     */
    @Query(
        """
        DELETE FROM imported_plays
        WHERE format = 'api' AND EXISTS (
            SELECT 1 FROM imported_plays AS e
            WHERE e.format = 'extended' AND e.track_key = imported_plays.track_key
              AND e.played_at BETWEEN imported_plays.played_at - 120000 AND imported_plays.played_at + 120000
        )
        """
    )
    abstract suspend fun deleteApiPlaysCoveredByExport(): Int

    /** The basic export is a subset of the extended one: where both cover a period, keep the extended rows. */
    @Query(
        """
        DELETE FROM imported_plays
        WHERE format = 'basic'
          AND played_at >= (SELECT MIN(played_at) FROM imported_plays WHERE format = 'extended')
          AND played_at <= (SELECT MAX(played_at) FROM imported_plays WHERE format = 'extended')
        """
    )
    abstract suspend fun deleteBasicPlaysCoveredByExtended(): Int

    /** YouTube Music's own history page only knows the day; Takeout knows the second. Keep Takeout's row. */
    @Query(
        """
        DELETE FROM imported_plays
        WHERE format = 'ytm_api' AND EXISTS (
            SELECT 1 FROM imported_plays AS t
            WHERE t.format = 'takeout' AND t.video_id = imported_plays.video_id
              AND t.played_at BETWEEN imported_plays.played_at - 172800000 AND imported_plays.played_at + 172800000
        )
        """
    )
    abstract suspend fun deleteYouTubeMusicPlaysCoveredByTakeout(): Int

    @Query("DELETE FROM imported_plays")
    abstract suspend fun clearPlays()

    // ─── Seeds ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM taste_seeds")
    abstract suspend fun allSeeds(): List<TasteSeedEntity>

    @Query("DELETE FROM taste_seeds WHERE kind = :kind AND time_range = :timeRange")
    abstract suspend fun deleteSeeds(kind: String, timeRange: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSeeds(seeds: List<TasteSeedEntity>)

    @Transaction
    open suspend fun replaceSeeds(kind: String, timeRange: String, seeds: List<TasteSeedEntity>) {
        deleteSeeds(kind, timeRange)
        if (seeds.isNotEmpty()) insertSeeds(seeds)
    }

    @Query("DELETE FROM taste_seeds")
    abstract suspend fun clearSeeds()
}

@Database(
    entities = [ImportedPlayEntity::class, TasteSeedEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ListeningHistoryDatabase : RoomDatabase() {
    abstract fun dao(): ListeningHistoryDao

    companion object {
        const val NAME = "listening_history.db"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ListeningHistoryModule {

    @Provides
    @Singleton
    fun provideListeningHistoryDatabase(@ApplicationContext context: Context): ListeningHistoryDatabase =
        Room.databaseBuilder(context.applicationContext, ListeningHistoryDatabase::class.java, ListeningHistoryDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Imported history can always be imported again: never block the app on a schema change.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideListeningHistoryDao(database: ListeningHistoryDatabase): ListeningHistoryDao = database.dao()
}

/** Bound so the AI assistant reads listening history through the small [ListeningInsights] API. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ListeningInsightsModule {
    @dagger.Binds
    abstract fun bindListeningInsights(impl: ListeningHistoryRepository): ListeningInsights
}
