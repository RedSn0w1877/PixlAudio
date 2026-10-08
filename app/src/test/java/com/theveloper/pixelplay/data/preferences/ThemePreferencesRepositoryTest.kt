package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.GsonBuilder
import com.theveloper.pixelplay.data.backup.module.GlobalSettingsModuleHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings › Appearance › Accent Color storage: `accent_color_v1` in the shared `settings` store,
 * normalised, and carried by the global-settings backup module (old backups without it restore to
 * the default).
 */
class ThemePreferencesRepositoryTest {

    private val accentKey = stringPreferencesKey("accent_color_v1")

    private fun CoroutineScope.store(dir: Path, name: String = "settings"): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { dir.resolve("$name.preferences_pb").toFile() }
        )

    private fun handler(store: DataStore<Preferences>) = GlobalSettingsModuleHandler(
        userPreferencesRepository = UserPreferencesRepository(dataStore = store, json = Json),
        gson = GsonBuilder().create()
    )

    @Test
    fun `accent defaults to Dynamic and is stored normalised`() = runTest {
        val dir = Files.createTempDirectory("theme-preferences-test")
        try {
            val store = backgroundScope.store(dir)
            val repository = ThemePreferencesRepository(store)

            assertEquals(AccentColor.DEFAULT, repository.accentColorFlow.first())

            repository.setAccentColor("#ff453a")
            assertEquals("#FF453A", repository.accentColorFlow.first())
            assertEquals("#FF453A", store.data.first()[accentKey])

            // Dynamic (or anything that isn't a colour) removes the key instead of storing "".
            repository.setAccentColor(AccentColor.DEFAULT)
            assertEquals(AccentColor.DEFAULT, repository.accentColorFlow.first())
            assertFalse(store.data.first().asMap().keys.any { it.name == "accent_color_v1" })

            repository.setAccentColor("#00C7BE")
            repository.setAccentColor("not a colour")
            assertFalse(store.data.first().asMap().keys.any { it.name == "accent_color_v1" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a junk stored value reads as the default`() = runTest {
        val dir = Files.createTempDirectory("theme-preferences-test")
        try {
            val store = backgroundScope.store(dir)
            store.edit { it[accentKey] = "#GG0000" }
            assertEquals(AccentColor.DEFAULT, ThemePreferencesRepository(store).accentColorFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the global settings backup carries the accent to a fresh install`() = runTest {
        val dir = Files.createTempDirectory("theme-preferences-test")
        try {
            val source = backgroundScope.store(dir, "source")
            ThemePreferencesRepository(source).setAccentColor("#FF453A")

            val exported = UserPreferencesRepository(dataStore = source, json = Json).exportPreferencesForBackup()
            assertTrue(
                exported.contains(
                    PreferenceBackupEntry(key = "accent_color_v1", type = "string", stringValue = "#FF453A")
                )
            )

            val payload = handler(source).export()
            val target = backgroundScope.store(dir, "target")
            handler(target).restore(payload)

            assertEquals("#FF453A", ThemePreferencesRepository(target).accentColorFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an old backup without the accent restores cleanly to Dynamic`() = runTest {
        val dir = Files.createTempDirectory("theme-preferences-test")
        try {
            val store = backgroundScope.store(dir)
            val themes = ThemePreferencesRepository(store)
            themes.setAccentColor("#34C759")
            themes.setPlayerThemePreference(ThemePreference.ALBUM_ART)

            // A pre-accent backup: Player Theme stored as "dynamic" (once "System Dynamic", now
            // "Accent Color"), no accent key.
            val oldPayload = """
                [
                  {"key":"player_theme_preference_v2","type":"string","stringValue":"dynamic"},
                  {"key":"app_theme_mode","type":"string","stringValue":"dark"}
                ]
            """.trimIndent()
            handler(store).restore(oldPayload)

            assertEquals(AccentColor.DEFAULT, themes.accentColorFlow.first())
            assertEquals(ThemePreference.DYNAMIC, themes.playerThemePreferenceFlow.first())
            assertEquals(AppThemeMode.DARK, themes.appThemeModeFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a backup with a junk accent restores without breaking the theme`() = runTest {
        val dir = Files.createTempDirectory("theme-preferences-test")
        try {
            val store = backgroundScope.store(dir)
            handler(store).restore(
                """[{"key":"accent_color_v1","type":"string","stringValue":"magenta"}]"""
            )
            assertEquals(AccentColor.DEFAULT, ThemePreferencesRepository(store).accentColorFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
