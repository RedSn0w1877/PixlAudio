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

/** The Endless Listening switches: what they are before anyone touches them, and that each one keeps its own value. */
class EndlessSettingsPreferenceTest {

    private inline fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("endless-settings-test")
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
    fun `before anyone touches them the host writes its notes and does not speak, and the intro is not seen`() = runTest {
        withTempDir { dir ->
            val user = UserPreferencesRepository(dataStore = newStore(dir), json = Json)
            assertTrue(user.endlessHostNotesFlow.first())
            assertFalse(user.endlessSpokenNotesFlow.first())
            assertFalse(user.endlessIntroSeenFlow.first())
        }
    }

    @Test
    fun `spoken notes are switched on and off on their own`() = runTest {
        withTempDir { dir ->
            val user = UserPreferencesRepository(dataStore = newStore(dir), json = Json)
            user.setEndlessSpokenNotes(true)
            assertTrue(user.endlessSpokenNotesFlow.first())
            assertTrue(user.endlessHostNotesFlow.first())
            user.setEndlessHostNotes(false)
            assertTrue(user.endlessSpokenNotesFlow.first())
            user.setEndlessSpokenNotes(false)
            assertFalse(user.endlessSpokenNotesFlow.first())
            assertFalse(user.endlessHostNotesFlow.first())
        }
    }
}
