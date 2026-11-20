package com.theveloper.pixelplay.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `pixelplay_database` 5 to 6 on a device: a version 5 file whose search history holds spellings of one query, junk and
 * a few ordinary rows is migrated and validated against the exported schema 6. Every ordinary row survives, the
 * spellings collapse into the newest one (counts add up), the junk goes, and the unique index on the key exists.
 * (The same pass is covered on the JVM by `SearchHistoryRulesTest` and the statements by `SearchHistorySchemaTest`.)
 *
 * Needs a device or emulator: `./gradlew :app:connectedDebugAndroidTest --tests "*SearchHistoryMigrationTest"`.
 */
@RunWith(AndroidJUnit4::class)
class SearchHistoryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PixelPlayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates5To6KeepingTheRowsAndFoldingTheKeys() {
        helper.createDatabase(DB, 5).apply {
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (1, 'beyonce', 10)")
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (2, 'Beyonc\u00e9', 30)")
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (3, 'BEYONCE', 20)")
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (4, 'Nguy\u1ec5n Tr\u1ea7n', 15)")
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (5, 'lofi beats', 12)")
            execSQL("INSERT INTO search_history (id, `query`, timestamp) VALUES (6, '!!', 99)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, PixelPlayDatabase.MIGRATION_5_6)

        val rows = db.query("SELECT id, `query`, `key`, timestamp, `count`, kind FROM search_history ORDER BY timestamp DESC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(listOf(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), c.getString(5)))
                }
            }
        }
        assertEquals(
            listOf(
                listOf(2L, "Beyonc\u00e9", "beyonce", 30L, 3, null),
                listOf(4L, "Nguy\u1ec5n Tr\u1ea7n", "nguyen tran", 15L, 1, null),
                listOf(5L, "lofi beats", "lofi beats", 12L, 1, null),
            ),
            rows
        )
        val indexes = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'search_history'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(true, "index_search_history_key" in indexes)
        db.close()
    }

    private companion object {
        const val DB = "search_history_migration_test.db"
    }
}
