package com.theveloper.pixelplay.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UserPreferencesRepositoryTest {

    @Test
    fun `default artist delimiters avoid common characters inside artist names`() {
        assertEquals(listOf(";"), UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
    }

    @Test
    fun `artistDelimitersFlow normalizes stored legacy defaults`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setArtistDelimiters(listOf("/", ";", ",", "+", "&"))

            assertEquals(
                UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                repository.artistDelimitersFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearPreferencesExceptKeys preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.clearPreferencesExceptKeys(emptySet())

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals(NavBarStyle.DEFAULT, repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `importPreferencesFromBackup clearExisting preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_style",
                        type = "string",
                        stringValue = "restored"
                    )
                ),
                clearExisting = true
            )

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals("restored", repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `old backups with the retired keep-screen-on setting restore everything else`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
            )
            val repository = UserPreferencesRepository(dataStore = dataStore, json = Json)

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(key = "keep_screen_on_lyrics", type = "boolean", booleanValue = true),
                    PreferenceBackupEntry(key = "nav_bar_style", type = "string", stringValue = "restored"),
                ),
                clearExisting = true
            )

            val stored = dataStore.data.first().asMap().keys.map { it.name }
            assertTrue("keep_screen_on_lyrics" !in stored)
            assertEquals("restored", repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retired settings never reach a new backup and are removed from the phone`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
            )
            val repository = UserPreferencesRepository(dataStore = dataStore, json = Json)
            // A value stored by an older version of the app.
            dataStore.edit { it[booleanPreferencesKey("keep_screen_on_lyrics")] = true }
            repository.setNavBarStyle("compact")

            assertTrue(repository.exportPreferencesForBackup().none { it.key == "keep_screen_on_lyrics" })

            repository.removeRetiredPreferences()

            val stored = dataStore.data.first().asMap().keys.map { it.name }
            assertTrue("keep_screen_on_lyrics" !in stored)
            assertEquals("compact", repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `navBarCornerRadiusFlow clamps values outside the supported UI range`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setNavBarCornerRadius(-1)
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(999)
            assertEquals(MAX_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_corner_radius",
                        type = "int",
                        intValue = -1
                    )
                ),
                clearExisting = false
            )
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
