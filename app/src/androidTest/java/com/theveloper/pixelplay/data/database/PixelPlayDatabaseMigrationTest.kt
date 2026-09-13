package com.theveloper.pixelplay.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real migration chain exists again (MIGRATION_1_2, MIGRATION_2_3) but this test still
 * only opens a fresh database at the latest version — it isn't exercising `Migration.migrate`.
 * What it verifies is that the exported schema opens cleanly and that the startup callback
 * creates its runtime artifacts (FTS and favorites sync triggers), which Room doesn't generate
 * on its own.
 */
@RunWith(AndroidJUnit4::class)
class PixelPlayDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: PixelPlayDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun createsDatabaseAndRuntimeArtifacts() = runBlocking {
        context.deleteDatabase(DB_NAME)

        val db = Room.databaseBuilder(context, PixelPlayDatabase::class.java, DB_NAME)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .build()
        database = db

        // Una consulta cualquiera fuerza la apertura real del fichero.
        assertNotNull(db.musicDao().getAllSongIds())

        val triggers = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger'"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue("Se esperaban triggers de sincronización, había: $triggers", triggers.isNotEmpty())
    }

    @Test
    fun spotifyTablesExist() = runBlocking {
        context.deleteDatabase(DB_NAME)

        val db = Room.databaseBuilder(context, PixelPlayDatabase::class.java, DB_NAME)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .build()
        database = db

        assertTrue(db.spotifyDao().getAllPlaylistsList().isEmpty())
        assertTrue(db.spotifyDao().getAllSpotifySongsList().isEmpty())
    }

    private companion object {
        const val DB_NAME = "pixelplay_schema_smoke_test.db"
    }
}
