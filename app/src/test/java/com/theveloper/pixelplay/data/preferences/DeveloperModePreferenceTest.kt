package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DeveloperModePreferenceTest {

    private val developerModeKey = "developer_mode_enabled"

    private inline fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("developer-mode-test")
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun TestScope.newStore(dir: Path): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { dir.resolve("settings.preferences_pb").toFile() }
    )

    @Test
    fun `developer mode is off until it is switched on`() = runTest {
        withTempDir { dir ->
            val user = UserPreferencesRepository(dataStore = newStore(dir), json = Json)
            assertFalse(user.developerModeFlow.first())

            user.setDeveloperMode(true)
            assertTrue(user.developerModeFlow.first())

            user.setDeveloperMode(false)
            assertFalse(user.developerModeFlow.first())
        }
    }

    @Test
    fun `developer mode stays out of backups and survives a restore`() = runTest {
        withTempDir { dir ->
            val user = UserPreferencesRepository(dataStore = newStore(dir), json = Json)
            user.setDeveloperMode(true)
            user.setHapticsEnabled(false)

            val exported = user.exportPreferencesForBackup()
            assertFalse(exported.any { it.key == developerModeKey })
            assertTrue(exported.any { it.key == "haptics_enabled" })

            // A restore clears everything it owns, then imports: developer mode is neither.
            user.clearPreferencesExceptKeys(emptySet())
            assertTrue(user.developerModeFlow.first())

            user.importPreferencesFromBackup(
                listOf(PreferenceBackupEntry(key = developerModeKey, type = "boolean", booleanValue = false)),
                clearExisting = true
            )
            assertTrue(user.developerModeFlow.first())
        }
    }
}
