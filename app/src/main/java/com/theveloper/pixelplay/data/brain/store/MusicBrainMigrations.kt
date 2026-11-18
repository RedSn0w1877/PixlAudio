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

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in V1_TO_V2) db.execSQL(statement)
        }
    }

    /** Every migration, in order. A new version adds its step here and its schema file next to the others. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
