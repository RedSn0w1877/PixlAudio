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
 * `brain.db` 1 to 2 on a device: a version 1 file with a listener's real rows in the tables that must
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

    private companion object {
        const val DB = "brain-migration-test.db"
    }
}
