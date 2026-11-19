package com.theveloper.pixelplay.data.brain

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import com.theveloper.pixelplay.data.brain.store.MusicBrainMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `brain.db` 1 to 2, and 2 to 3, on a device: a version 1 file with a listener's real rows in the tables that must
 * survive is migrated, validated against the exported schema 2, and still holds every row; the three new
 * tables are there and empty. (The same statements are held against the schema JSON on the JVM by
 * `MusicBrainMigrationsTest`; this is the part only a device can do.)
 *
 * Needs a device or emulator: `./gradlew :app:connectedDebugAndroidTest --tests "*MusicBrainMigrationInstrumentedTest"`.
 */
@RunWith(AndroidJUnit4::class)
class MusicBrainMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MusicBrainDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates1To2KeepingTheHistory() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO play_event (eventId, schema, origin, rid, primaryAid, otherAids, keyAtWrite, startedAtMs, tzOffsetMin, " +
                    "playedMs, durationMs, startReason, endReason, flags, surface, surfaceId, recId, slot, arm, sessionId, posInSession, " +
                    "passiveRun, route, retrQ, radioRank, pServedQ, modelVersion) VALUES (1, 1, 0, 42, 7, '', 'n:x', 1000, 0, " +
                    "190000, 200000, 0, 0, 0, 7, '', 0, -1, 9, 1, 0, 0, 0, -1, -1, 0, 0)"
            )
            execSQL("INSERT INTO track_alias (`key`, rid, durationMs) VALUES ('n:x', 42, 200000)")
            execSQL("INSERT INTO artist_alias (`key`, aid) VALUES ('na:y', 7)")
            execSQL("INSERT INTO brain_state (`key`, blob) VALUES ('salt', x'3132')")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, *MusicBrainMigrations.ALL)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("play_event"))
        assertEquals(1, count("track_alias"))
        assertEquals(1, count("artist_alias"))
        assertEquals(1, count("brain_state"))
        for (table in listOf("graph_edge", "served_list", "feedback")) assertEquals(table, 0, count(table))
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_graph_edge_%'").use {
            assertTrue(it.count == 2)
        }
        db.close()
    }

    @Test
    fun migrates2To3KeepingEverythingAndAddingSpotifyFacts() {
        helper.createDatabase(DB_V2, 2).apply {
            execSQL(
                "INSERT INTO play_event (eventId, schema, origin, rid, primaryAid, otherAids, keyAtWrite, startedAtMs, tzOffsetMin, " +
                    "playedMs, durationMs, startReason, endReason, flags, surface, surfaceId, recId, slot, arm, sessionId, posInSession, " +
                    "passiveRun, route, retrQ, radioRank, pServedQ, modelVersion) VALUES (1, 1, 0, 42, 7, '', 'n:x', 1000, 0, " +
                    "190000, 200000, 0, 0, 0, 7, '', 0, -1, 9, 1, 0, 0, 0, -1, -1, 0, 0)"
            )
            execSQL("INSERT INTO feedback (id, kind, targetKind, targetId, createdAtMs, undoneAtMs) VALUES (9, 2, 1, 42, 5, NULL)")
            execSQL("INSERT INTO graph_edge (src, dst, type, wQ16, rank, fetchedAt) VALUES (1, 2, 0, 100, 1, 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_V2, 3, true, *MusicBrainMigrations.ALL)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("play_event"))
        assertEquals(1, count("feedback"))
        assertEquals(1, count("graph_edge"))
        assertEquals(0, count("spotify_fact"))
        db.execSQL(
            "INSERT INTO spotify_fact (spotifyId, isrc, artistIds, albumId, releaseDate, releasePrecision, explicit, seenAtMs) " +
                "VALUES ('t', NULL, 'a,b', 'alb', '2024-05', 'month', 1, 7)"
        )
        assertEquals(1, count("spotify_fact"))
        db.close()
    }

    @Test
    fun migrates3To4KeepingEverythingAndAddingTheDnaTables() {
        helper.createDatabase(DB_V3, 3).apply {
            execSQL(
                "INSERT INTO play_event (eventId, schema, origin, rid, primaryAid, otherAids, keyAtWrite, startedAtMs, tzOffsetMin, " +
                    "playedMs, durationMs, startReason, endReason, flags, surface, surfaceId, recId, slot, arm, sessionId, posInSession, " +
                    "passiveRun, route, retrQ, radioRank, pServedQ, modelVersion) VALUES (1, 1, 0, 42, 7, '', 'n:x', 1000, 0, " +
                    "190000, 200000, 0, 0, 0, 7, '', 0, -1, 9, 1, 0, 0, 0, -1, -1, 0, 0)"
            )
            execSQL("INSERT INTO spotify_fact (spotifyId, isrc, artistIds, albumId, releaseDate, releasePrecision, explicit, seenAtMs) VALUES ('t', 'ISRC1', 'a', 'alb', '2024', 'year', 0, 7)")
            execSQL("INSERT INTO track_alias (`key`, rid, durationMs) VALUES ('n:x', 42, 200000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_V3, 4, true, *MusicBrainMigrations.ALL)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("play_event"))
        assertEquals(1, count("spotify_fact"))
        assertEquals(1, count("track_alias"))
        for (table in listOf("track_dna", "artist_dna", "source_miss")) assertEquals(table, 0, count(table))
        db.execSQL(
            "INSERT INTO source_miss (entity, source, retryAfter, misses) VALUES ('t:42', 1, 99, 1)"
        )
        assertEquals(1, count("source_miss"))
        db.close()
    }

    @Test
    fun migrates4To5KeepingEverythingAndAddingEmbeddingsAndIslands() {
        helper.createDatabase(DB_V4, 4).apply {
            execSQL(
                "INSERT INTO play_event (eventId, schema, origin, rid, primaryAid, otherAids, keyAtWrite, startedAtMs, tzOffsetMin, " +
                    "playedMs, durationMs, startReason, endReason, flags, surface, surfaceId, recId, slot, arm, sessionId, posInSession, " +
                    "passiveRun, route, retrQ, radioRank, pServedQ, modelVersion) VALUES (1, 1, 0, 42, 7, '', 'n:x', 1000, 0, " +
                    "190000, 200000, 0, 0, 0, 7, '', 0, -1, 9, 1, 0, 0, 0, -1, -1, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_V4, 5, true, *MusicBrainMigrations.ALL)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("play_event"))
        for (table in listOf("node_embedding", "island", "island_member")) assertEquals(table, 0, count(table))
        db.execSQL("INSERT INTO island_member (rid, islandId) VALUES (42, 1)")
        assertEquals(1, count("island_member"))
        db.close()
    }

    @Test
    fun migrates5To6KeepingEverythingAndAddingImpressions() {
        helper.createDatabase(DB_V5, 5).apply {
            execSQL("INSERT INTO island_member (rid, islandId) VALUES (42, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_V5, 6, true, *MusicBrainMigrations.ALL)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("island_member"))
        assertEquals(0, count("impression"))
        db.execSQL("INSERT INTO impression (visitId, position, shelf, daypart, shownAtMs, seen, activatedAtMs, settled) VALUES (1, 0, 0, 1, 1, 0, 0, 0)")
        assertEquals(1, count("impression"))
        db.close()
    }

    private companion object {
        const val DB_V4 = "brain-migration-test-v4.db"
        const val DB_V5 = "brain-migration-test-v5.db"
        const val DB = "brain-migration-test.db"
        const val DB_V3 = "brain-migration-test-v3.db"
        const val DB_V2 = "brain-migration-test-v2.db"
    }
}
