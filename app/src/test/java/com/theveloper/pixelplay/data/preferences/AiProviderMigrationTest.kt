package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AiProviderMigrationTest {

    private val providerKey = stringPreferencesKey("ai_provider")
    private val geminiKey = stringPreferencesKey("gemini_api_key")
    private val cloudKey = stringPreferencesKey(AiPreferencesRepository.CLOUD_PROVIDER_KEY)
    private val flagKey = booleanPreferencesKey(AiPreferencesRepository.PROVIDER_MIGRATED_KEY)

    private inline fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("ai-provider-migration-test")
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
    fun `the default provider is on-device`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = AiPreferencesRepository(store)
            assertEquals(AiProvider.ON_DEVICE.name, repository.aiProvider.first())
            assertFalse(repository.aiDownloadedModelEnabled.first())
        }
    }

    @Test
    fun `Gemini without a key moves to on-device once and remembers Gemini for the cloud switch`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            store.edit { it[providerKey] = "GEMINI" }
            val repository = AiPreferencesRepository(store)

            assertTrue(repository.migrateProviderIfNeeded())

            val prefs = store.data.first()
            assertEquals("ON_DEVICE", prefs[providerKey])
            assertEquals("GEMINI", prefs[cloudKey])
            assertEquals(true, prefs[flagKey])

            // A second run is a no-op, even if the user then picks Gemini again without a key.
            store.edit { it[providerKey] = "GEMINI" }
            assertFalse(repository.migrateProviderIfNeeded())
            assertEquals("GEMINI", store.data.first()[providerKey])
        }
    }

    @Test
    fun `Gemini with a key stays on Gemini`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            store.edit {
                it[providerKey] = "GEMINI"
                it[geminiKey] = "secret"
            }
            val repository = AiPreferencesRepository(store)

            assertFalse(repository.migrateProviderIfNeeded())
            val prefs = store.data.first()
            assertEquals("GEMINI", prefs[providerKey])
            assertEquals("GEMINI", prefs[cloudKey])
        }
    }

    @Test
    fun `never picked but a Gemini key saved keeps the old default working`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            store.edit { it[geminiKey] = "secret" }
            val repository = AiPreferencesRepository(store)

            repository.migrateProviderIfNeeded()
            assertEquals("GEMINI", repository.aiProvider.first())
        }
    }

    @Test
    fun `a restore that clears the flag and brings back key-less Gemini migrates again`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = AiPreferencesRepository(store)
            store.edit { it[providerKey] = "ON_DEVICE" }
            repository.migrateProviderIfNeeded()

            // What GlobalSettingsModuleHandler.restore does with an old backup.
            store.edit { prefs ->
                prefs.remove(flagKey)
                prefs[providerKey] = "GEMINI"
            }
            assertTrue(repository.migrateProviderIfNeeded())
            assertEquals("ON_DEVICE", repository.aiProvider.first())
        }
    }

    @Test
    fun `on-device stays untouched and Ollama without a URL moves`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = AiPreferencesRepository(store)
            store.edit { it[providerKey] = "ON_DEVICE" }
            assertFalse(repository.migrateProviderIfNeeded())
            assertEquals("ON_DEVICE", repository.aiProvider.first())
            assertNull(store.data.first()[cloudKey])
        }
        val decision = AiProviderMigrationRules.decide("OLLAMA", apiKeyFor = { "" }, baseUrlFor = { "" })
        assertEquals("ON_DEVICE", decision.provider)
        assertEquals("OLLAMA", decision.cloudProvider)
        val working = AiProviderMigrationRules.decide("OLLAMA", apiKeyFor = { "" }, baseUrlFor = { "http://10.0.0.2:11434/v1" })
        assertEquals("OLLAMA", working.provider)
        assertEquals("ON_DEVICE", AiProviderMigrationRules.decide("BOGUS", { "" }, { "" }).provider)
    }

    @Test
    fun `the cloud switch restores the last cloud provider and off returns to on-device`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = AiPreferencesRepository(store)
            repository.setCloudAssistantEnabled(true)
            assertEquals("GEMINI", repository.aiProvider.first())

            repository.setCloudProvider(AiProvider.GROQ)
            repository.setCloudAssistantEnabled(false)
            assertEquals("ON_DEVICE", repository.aiProvider.first())

            repository.setCloudAssistantEnabled(true)
            assertEquals("GROQ", repository.aiProvider.first())
        }
    }

    @Test
    fun `the downloaded-model switch stays out of backups and survives a restore`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val ai = AiPreferencesRepository(store)
            val user = UserPreferencesRepository(dataStore = store, json = Json)
            ai.setDownloadedModelEnabled(true)
            ai.setAiTemperature(0.4f)

            val exported = user.exportPreferencesForBackup()
            assertFalse(exported.any { it.key == AiPreferencesRepository.DOWNLOADED_MODEL_ENABLED_KEY })
            assertTrue(exported.any { it.key == "ai_temperature" })

            // A restore clears everything it owns, then imports — the switch is neither.
            user.clearPreferencesExceptKeys(emptySet())
            assertTrue(ai.aiDownloadedModelEnabled.first())

            user.importPreferencesFromBackup(
                listOf(
                    PreferenceBackupEntry(
                        key = AiPreferencesRepository.DOWNLOADED_MODEL_ENABLED_KEY,
                        type = "boolean",
                        booleanValue = false
                    )
                ),
                clearExisting = true
            )
            assertTrue(ai.aiDownloadedModelEnabled.first())
        }
    }

    @Test
    fun `the switch key does not use a per-provider suffix`() {
        // iOS's backup catalogue treats these suffixes as portable per-provider AI keys.
        val key = AiPreferencesRepository.DOWNLOADED_MODEL_ENABLED_KEY
        for (suffix in listOf("_api_key", "_model", "_system_prompt", "_base_url")) {
            assertFalse(key.endsWith(suffix), "$key ends with $suffix")
        }
    }
}
