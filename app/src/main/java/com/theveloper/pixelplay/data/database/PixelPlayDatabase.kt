package com.theveloper.pixelplay.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.theveloper.pixelplay.data.searchhistory.LegacySearchRow
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRules

@Database(
    entities = [
        AlbumArtThemeEntity::class,
        SearchHistoryEntity::class,
        SongEntity::class,
        SongSearchFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TransitionRuleEntity::class,
        SongArtistCrossRef::class,
        SongEngagementEntity::class,
        FavoritesEntity::class,
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SpotifySongEntity::class,
        SpotifyPlaylistEntity::class,
        AiCacheEntity::class,
        AiUsageEntity::class,
        SongCacheEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class PixelPlayDatabase : RoomDatabase() {
    abstract fun albumArtThemeDao(): AlbumArtThemeDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun musicDao(): MusicDao
    abstract fun transitionDao(): TransitionDao
    abstract fun engagementDao(): EngagementDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun spotifyDao(): SpotifyDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun songCacheDao(): SongCacheDao

    companion object {
        /** Primera migración real del proyecto — solo añade la tabla de caché/descargas local. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS `song_cache` (
                            `song_id` TEXT NOT NULL,
                            `file_path` TEXT NOT NULL,
                            `size_bytes` INTEGER NOT NULL,
                            `is_permanent` INTEGER NOT NULL,
                            `is_complete` INTEGER NOT NULL DEFAULT 0,
                            `created_at` INTEGER NOT NULL,
                            `last_accessed_at` INTEGER NOT NULL,
                            PRIMARY KEY(`song_id`)
                        )
                    """.trimIndent()
                )
            }
        }
        /**
         * Adds `genre` to the FTS index (and its sync triggers) so searching a genre/mood term
         * — or a "category" tap that should look inside the local library, not just at Spotify —
         * can actually find matching songs. Drops and rebuilds `songs_fts` from scratch since
         * FTS4 virtual tables can't ALTER TABLE ADD COLUMN.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
                db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
                db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")
                db.execSQL("DROP TABLE IF EXISTS songs_fts")
                installSongsSearchSyncTriggers(db)
                rebuildSongsSearchIndex(db)
            }
        }
        /**
         * `spotify_songs` gets its own `genre` column, separate from `songs.genre` — Spotify
         * imports live in this table until they're mirrored into the unified `songs` table by
         * [com.theveloper.pixelplay.data.spotify.SpotifyRepository.syncUnifiedLibrarySongsFromSpotify],
         * which now copies this value across instead of hardcoding null. A plain nullable
         * TEXT column needs no backfill — existing rows just read genre = NULL until the next
         * sync backfills it via a batch artist lookup.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spotify_songs ADD COLUMN genre TEXT")
            }
        }
        /**
         * Lets playlists themselves be drag-reordered (separate from `playlist_songs.sort_order`,
         * which orders songs *within* one playlist). Backfilled from `last_modified DESC` — the
         * order the Playlists tab was already showing under its default sort — so turning on
         * "Custom order" for the first time doesn't visibly reshuffle anything.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                        UPDATE playlists SET sort_order = (
                            SELECT COUNT(*) FROM playlists p2
                            WHERE p2.last_modified > playlists.last_modified
                        )
                    """.trimIndent()
                )
            }
        }
        /** The 5-to-6 column additions, exactly as the exported schema 6 declares them (held against 6.json by a unit test). */
        val SEARCH_HISTORY_ADD_COLUMNS: List<String> = listOf(
            "ALTER TABLE `search_history` ADD COLUMN `key` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `search_history` ADD COLUMN `kind` TEXT",
            "ALTER TABLE `search_history` ADD COLUMN `target_id` TEXT",
            "ALTER TABLE `search_history` ADD COLUMN `target_title` TEXT",
            "ALTER TABLE `search_history` ADD COLUMN `image_url` TEXT",
            "ALTER TABLE `search_history` ADD COLUMN `count` INTEGER NOT NULL DEFAULT 1",
        )
        const val SEARCH_HISTORY_KEY_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_key` ON `search_history` (`key`)"

        /**
         * Search history grows a fold key, what was opened from a search (kind, target, title, image) and a use
         * count. Existing rows survive: each gets its key (`BrainText.fold`, the same rule as the iOS app), rows
         * that now share a key collapse into the newest one (counts add up), rows that fold to nothing and rows
         * beyond the cap of 50 go, then the unique index on the key is created. The index comes last: the
         * collapse has to happen before two rows may no longer share a key.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SEARCH_HISTORY_ADD_COLUMNS.forEach { db.execSQL(it) }

                val rows = ArrayList<LegacySearchRow>()
                db.query("SELECT `id`, `query`, `timestamp` FROM search_history").use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += LegacySearchRow(
                            id = cursor.getLong(0),
                            query = cursor.getString(1) ?: "",
                            timestamp = cursor.getLong(2),
                        )
                    }
                }
                val plan = SearchHistoryRules.planMigration(rows)
                for (id in plan.deleteIds) {
                    db.execSQL("DELETE FROM search_history WHERE `id` = ?", arrayOf<Any>(id))
                }
                for (row in plan.survivors) {
                    db.execSQL(
                        "UPDATE search_history SET `query` = ?, `key` = ?, `timestamp` = ?, `count` = ? WHERE `id` = ?",
                        arrayOf<Any>(row.query, row.key, row.timestamp, row.count, row.id)
                    )
                }
                db.execSQL(SEARCH_HISTORY_KEY_INDEX)
            }
        }
        /**
         * Triggers y tablas virtuales que Room no genera solo.
         *
         * Room crea `songs_fts` a partir de [SongSearchFtsEntity], pero mantenerla
         * sincronizada con `songs` y propagar `favorites.isFavorite` a `songs.is_favorite`
         * se hace con triggers SQL — más barato que replicar la escritura desde Kotlin.
         */
        fun createRuntimeArtifactsCallback(): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installFavoriteSyncTriggers(db)
                    installSongsSearchSyncTriggers(db)
                }
            }
        }

        fun installFavoriteSyncTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_insert_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_update_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_delete_sync_song")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_insert_sync_song
                    AFTER INSERT ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_update_sync_song
                    AFTER UPDATE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_delete_sync_song
                    AFTER DELETE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = 0 WHERE id = OLD.songId;
                    END
                """.trimIndent()
            )
        }

        fun installSongsSearchSyncTriggers(db: SupportSQLiteDatabase) {
            createSongsSearchVirtualTable(db)

            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_insert
                    AFTER INSERT ON songs
                    BEGIN
                        INSERT INTO songs_fts(rowid, title, artist_name, genre)
                        VALUES (NEW.id, NEW.title, NEW.artist_name, COALESCE(NEW.genre, ''));
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_update
                    AFTER UPDATE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                        INSERT INTO songs_fts(rowid, title, artist_name, genre)
                        VALUES (NEW.id, NEW.title, NEW.artist_name, COALESCE(NEW.genre, ''));
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_delete
                    AFTER DELETE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                    END
                """.trimIndent()
            )
        }

        /**
         * Reconstruye el índice de búsqueda desde cero. Room ya crea la tabla, pero esto
         * sirve para el "rebuild database" manual de ajustes.
         */
        fun rebuildSongsSearchIndex(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM songs_fts")
            db.execSQL(
                """
                    INSERT INTO songs_fts(rowid, title, artist_name, genre)
                    SELECT id, title, artist_name, COALESCE(genre, '')
                    FROM songs
                """.trimIndent()
            )
        }

        private fun createSongsSearchVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                    CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts
                    USING fts4(
                        title,
                        artist_name,
                        genre,
                        tokenize=unicode61
                    )
                """.trimIndent()
            )
        }
    }
}
