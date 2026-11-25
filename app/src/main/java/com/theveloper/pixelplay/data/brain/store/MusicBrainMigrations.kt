package com.theveloper.pixelplay.data.brain.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The real migrations of `brain.db` (docs/brain-spec.md, 3.8). There is no destructive fallback in any
 * build type: `play_event`, the alias tables and `feedback` are the listener's history and must
 * survive every upgrade. Each step lists its statements as plain strings, so a unit test can hold
 * them against the exported schema (`app/schemas/.../MusicBrainDatabase/N.json`) without a device.
 */
object MusicBrainMigrations {

    /**
     * Version 1 to 2 (stage 2a): `graph_edge`, `served_list` and `feedback`. Nothing existing is
     * touched. The statements are what Room itself writes for the entities of [BrainEntitiesV2.kt]:
     * the schema check on open fails loudly if they ever drift.
     */
    val V1_TO_V2: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `graph_edge` (`src` INTEGER NOT NULL, `dst` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
            "`wQ16` INTEGER NOT NULL, `rank` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`src`, `dst`, `type`))",
        "CREATE INDEX IF NOT EXISTS `index_graph_edge_dst` ON `graph_edge` (`dst`)",
        "CREATE INDEX IF NOT EXISTS `index_graph_edge_fetchedAt` ON `graph_edge` (`fetchedAt`)",
        "CREATE TABLE IF NOT EXISTS `served_list` (`recId` INTEGER NOT NULL, `surface` INTEGER NOT NULL, `surfaceId` TEXT NOT NULL, " +
            "`builtAt` INTEGER NOT NULL, `seed` INTEGER NOT NULL, `modelVersion` INTEGER NOT NULL, `nuQ` INTEGER NOT NULL, " +
            "`itemsBlob` BLOB NOT NULL, PRIMARY KEY(`recId`))",
        "CREATE INDEX IF NOT EXISTS `index_served_list_builtAt` ON `served_list` (`builtAt`)",
        "CREATE TABLE IF NOT EXISTS `feedback` (`id` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `targetKind` INTEGER NOT NULL, " +
            "`targetId` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `undoneAtMs` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_feedback_targetKind_targetId` ON `feedback` (`targetKind`, `targetId`)",
    )

    /**
     * Version 2 to 3 (stage 2a, milestone 2): `spotify_fact`, the facts the Spotify sync already returns
     * (artist ids, release date, explicit). Nothing existing is touched.
     */
    val V2_TO_V3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `spotify_fact` (`spotifyId` TEXT NOT NULL, `isrc` TEXT, `artistIds` TEXT NOT NULL, " +
            "`albumId` TEXT, `releaseDate` TEXT, `releasePrecision` TEXT, `explicit` INTEGER, `seenAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`spotifyId`))",
    )

    /** Version 3 to 4 (stage 2b): `track_dna`, `artist_dna` and `source_miss`. Nothing existing is touched. */
    val V3_TO_V4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `track_dna` (`rid` INTEGER NOT NULL, `spotifyId` TEXT, `isrc` TEXT, `deezerId` INTEGER, `mbRecordingId` TEXT, `ytAudioIds` TEXT NOT NULL, `artistIds` TEXT NOT NULL, `albumKey` INTEGER, `title` TEXT NOT NULL, `artistDisplay` TEXT NOT NULL, `versionFlags` INTEGER NOT NULL, `firstYear` INTEGER NOT NULL, `yearSource` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `explicit` INTEGER NOT NULL, `popPermille` INTEGER NOT NULL, `tempoBpmX10` INTEGER NOT NULL, `tempoSource` INTEGER NOT NULL, `gainDbX10` INTEGER NOT NULL, `scriptLang` INTEGER NOT NULL, `tags` BLOB NOT NULL, `moods` BLOB NOT NULL, `energy` INTEGER NOT NULL, `valence` INTEGER NOT NULL, `embVersion` INTEGER NOT NULL, `confidence` INTEGER NOT NULL, `fetchedMask` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `tagsAtMs` INTEGER NOT NULL, `popAtMs` INTEGER NOT NULL, `atlasVersion` INTEGER NOT NULL, `tagInputs` BLOB NOT NULL, `yearClaims` TEXT NOT NULL, `popClaims` TEXT NOT NULL, PRIMARY KEY(`rid`))",
        "CREATE INDEX IF NOT EXISTS `index_track_dna_spotifyId` ON `track_dna` (`spotifyId`)",
        "CREATE INDEX IF NOT EXISTS `index_track_dna_updatedAtMs` ON `track_dna` (`updatedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `artist_dna` (`aid` INTEGER NOT NULL, `spotifyId` TEXT, `ytChannelId` TEXT, `deezerId` INTEGER, `mbid` TEXT, `wikidataId` TEXT, `name` TEXT NOT NULL, `country` TEXT NOT NULL, `tags` BLOB NOT NULL, `popPermille` INTEGER NOT NULL, `releaseIds` TEXT NOT NULL, `fetchedMask` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `tagsAtMs` INTEGER NOT NULL, `atlasVersion` INTEGER NOT NULL, `tagInputs` BLOB NOT NULL, PRIMARY KEY(`aid`))",
        "CREATE INDEX IF NOT EXISTS `index_artist_dna_spotifyId` ON `artist_dna` (`spotifyId`)",
        "CREATE INDEX IF NOT EXISTS `index_artist_dna_mbid` ON `artist_dna` (`mbid`)",
        "CREATE TABLE IF NOT EXISTS `source_miss` (`entity` TEXT NOT NULL, `source` INTEGER NOT NULL, `retryAfter` INTEGER NOT NULL, `misses` INTEGER NOT NULL, PRIMARY KEY(`entity`, `source`))",
    )
    /**
     * Version 4 to 5 (stage 2b, milestone 2): `node_embedding`, `island` and `island_member`. Nothing existing is
     * touched. The statements are what Room itself writes for the entities of `BrainEntitiesV5.kt`.
     */
    val V4_TO_V5: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `node_embedding` (`nodeId` INTEGER NOT NULL, `embVersion` INTEGER NOT NULL, `vec` BLOB NOT NULL, PRIMARY KEY(`nodeId`))",
        "CREATE TABLE IF NOT EXISTS `island` (`islandId` INTEGER NOT NULL, `centroid` BLOB NOT NULL, `medoidRid` INTEGER NOT NULL, `topTags` BLOB NOT NULL, `liftTags` BLOB NOT NULL, `topArtists` BLOB NOT NULL, `weightQ` INTEGER NOT NULL, `recentWeightQ` INTEGER NOT NULL, `size` INTEGER NOT NULL, `fitAtMs` INTEGER NOT NULL, PRIMARY KEY(`islandId`))",
        "CREATE TABLE IF NOT EXISTS `island_member` (`rid` INTEGER NOT NULL, `islandId` INTEGER NOT NULL, PRIMARY KEY(`rid`))",
        "CREATE INDEX IF NOT EXISTS `index_island_member_islandId` ON `island_member` (`islandId`)",
    )

    /**
     * Version 5 to 6 (stage 3): `impression`, the compact shelf impressions the Home shelf bandit learns from. Nothing existing is touched.
     */
    val V5_TO_V6: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `impression` (`visitId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `shelf` INTEGER NOT NULL, " +
            "`daypart` INTEGER NOT NULL, `shownAtMs` INTEGER NOT NULL, `seen` INTEGER NOT NULL, `activatedAtMs` INTEGER NOT NULL, " +
            "`settled` INTEGER NOT NULL, PRIMARY KEY(`visitId`, `position`))",
        "CREATE INDEX IF NOT EXISTS `index_impression_shownAtMs` ON `impression` (`shownAtMs`)",
    )

    /**
     * Version 6 to 7 (stage 4): `play_event.servedX`, the feature vector a pick was served with (`ServedVector`, 102 bytes, nullable). One column added,
     * nothing rewritten: every row written before keeps NULL and the learner reads it with the reduced vector it always used.
     */
    val V6_TO_V7: List<String> = listOf(
        "ALTER TABLE `play_event` ADD COLUMN `servedX` BLOB",
    )


    /**
     * Version 7 to 8 (stage 5): `audio_measure`, tempo and loudness measured on the phone from decoded audio. A new cache table; nothing
     * existing is touched. The statement is what Room itself writes for `AudioMeasureEntity` (BrainEntitiesV8.kt).
     */
    val V7_TO_V8: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `audio_measure` (`rid` INTEGER NOT NULL, `version` INTEGER NOT NULL, `bpmX10` INTEGER NOT NULL, " +
            "`tempoConf` INTEGER NOT NULL, `lufsX10` INTEGER NOT NULL, `onsetX1000` INTEGER NOT NULL, `analysedMs` INTEGER NOT NULL, " +
            "`windowStartMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `measuredAtMs` INTEGER NOT NULL, `failures` INTEGER NOT NULL, " +
            "PRIMARY KEY(`rid`))",
    )

    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V7_TO_V8) db.execSQL(statement)
        }
    }
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V6_TO_V7) db.execSQL(statement)
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V5_TO_V6) db.execSQL(statement)
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V4_TO_V5) db.execSQL(statement)
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V3_TO_V4) db.execSQL(statement)
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V2_TO_V3) db.execSQL(statement)
        }
    }

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V1_TO_V2) db.execSQL(statement)
        }
    }

    /** Every migration, in order. A new version adds its step here and its schema file next to the others. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
}
