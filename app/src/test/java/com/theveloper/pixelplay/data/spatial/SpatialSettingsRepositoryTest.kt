package com.theveloper.pixelplay.data.spatial

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.cloudstudio.CloudBuiltInConfig
import com.theveloper.pixelplay.data.cloudstudio.CloudConfigInput
import com.theveloper.pixelplay.data.cloudstudio.NoBuiltInCloudConfig
import com.theveloper.pixelplay.data.preferences.PreferenceBackupEntry
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The settings of cloud-audio-spec §6.1: their defaults, their key names in the shared store, and what a bad value reads as. */
class SpatialSettingsRepositoryTest {

    /** A build that carries PixlAudio's own cloud keys. */
    private object BuiltInKeys : CloudBuiltInConfig {
        override val isBundled: Boolean = true
        override suspend fun load(): CloudConfigInput? = null
    }

    private inline fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("spatial-settings-test")
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

    private suspend fun DataStore<Preferences>.stored(): Map<String, Any> = data.first().asMap().mapKeys { it.key.name }

    @Test
    fun `the defaults are the spec's`() = runTest {
        withTempDir { dir ->
            val settings = SpatialSettingsRepository(newStore(dir), NoBuiltInCloudConfig).settings.first()

            assertNull(settings.profile) // spatial.profile = off
            assertFalse(settings.isOn)
            assertEquals(SpatialRouteMode.HEADPHONES, settings.routeMode)
            assertFalse(settings.cloud) // no built-in cloud keys in this build
            assertTrue(settings.prepareAhead)
            assertTrue(settings.enhanced)
            assertEquals(3072, settings.storageMb)
            assertEquals(30, settings.monthlyCapUsd)
            assertEquals(25, settings.dailyAhead)

            assertEquals(3072L * 1_048_576L, settings.storageBytes)
            assertEquals(30_000_000L, settings.monthlyCapMicroUsd)
            assertEquals(SpatialSettings.defaults(builtInCloudKeys = false), settings)
        }
    }

    @Test
    fun `the cloud switch defaults to on when the build has built-in cloud keys`() = runTest {
        withTempDir { dir ->
            val repository = SpatialSettingsRepository(newStore(dir), BuiltInKeys)

            assertTrue(repository.settings.first().cloud)
            assertTrue(repository.defaults.cloud)
            assertEquals(SpatialSettings.defaults(builtInCloudKeys = true), repository.current())

            // Once the person touches the switch, their choice holds whatever the build has.
            repository.setCloud(false)
            assertFalse(repository.settings.first().cloud)
        }
    }

    @Test
    fun `a build without built-in keys can still switch the cloud on`() = runTest {
        withTempDir { dir ->
            val repository = SpatialSettingsRepository(newStore(dir), NoBuiltInCloudConfig)
            assertFalse(repository.defaults.cloud)

            repository.setCloud(true)
            assertTrue(repository.settings.first().cloud)
        }
    }

    @Test
    fun `every setting is stored under the spec's key`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)

            repository.setProfile(SpatialProfile.HALL)
            repository.setRouteMode(SpatialRouteMode.ALWAYS)
            repository.setCloud(true)
            repository.setPrepareAhead(false)
            repository.setEnhanced(false)
            repository.setStorageMb(1024)
            repository.setMonthlyCapUsd(75)
            repository.setDailyAhead(10)

            assertEquals(
                mapOf<String, Any>(
                    "spatial.profile" to "hall",
                    "spatial.route" to "always",
                    "spatial.cloud" to true,
                    "spatial.prepareAhead" to false,
                    "enhanced.enabled" to false,
                    "cloudAudio.storageMB" to 1024,
                    "cloudAudio.monthlyCapUSD" to 75,
                    "cloudAudio.dailyAhead" to 10,
                ),
                store.stored()
            )
            assertEquals(
                SpatialSettings(
                    profile = SpatialProfile.HALL,
                    routeMode = SpatialRouteMode.ALWAYS,
                    cloud = true,
                    prepareAhead = false,
                    enhanced = false,
                    storageMb = 1024,
                    monthlyCapUsd = 75,
                    dailyAhead = 10,
                ),
                repository.settings.first()
            )
        }
    }

    @Test
    fun `picking a profile turns Spatial audio on and Off stores off`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)

            for (profile in SpatialProfile.entries) {
                repository.setProfile(profile)
                val settings = repository.settings.first()
                assertEquals(profile, settings.profile)
                assertTrue(settings.isOn)
                assertEquals(profile.id, store.stored()["spatial.profile"])
            }

            repository.setProfile(null)
            assertNull(repository.settings.first().profile)
            assertEquals("off", store.stored()["spatial.profile"])
        }
    }

    @Test
    fun `no limit is stored apart from a cap of zero`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)

            repository.setMonthlyCapUsd(null)
            assertNull(repository.settings.first().monthlyCapUsd)
            assertNull(repository.settings.first().monthlyCapMicroUsd)
            assertEquals(SpatialSettingsRepository.NO_MONTHLY_CAP, store.stored()["cloudAudio.monthlyCapUSD"])

            // A cap of 0 spends nothing; it is not "no limit".
            repository.setMonthlyCapUsd(0)
            assertEquals(0, repository.settings.first().monthlyCapUsd)
            assertEquals(0L, repository.settings.first().monthlyCapMicroUsd)
        }
    }

    @Test
    fun `values outside their range are brought back inside it`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)

            repository.setMonthlyCapUsd(5_000)
            assertEquals(200, repository.settings.first().monthlyCapUsd)
            assertEquals(200, store.stored()["cloudAudio.monthlyCapUSD"])
            // Only null asks for "No limit": a negative dollar amount handed to the setter is a cap of nothing.
            repository.setMonthlyCapUsd(-7)
            assertEquals(0, repository.settings.first().monthlyCapUsd)

            // §6.1 "Stored forms": the storage budget stays within 256..65536 MB, the daily count within 1..100.
            repository.setStorageMb(-1)
            assertEquals(256, repository.settings.first().storageMb)
            assertEquals(256, store.stored()["cloudAudio.storageMB"])
            repository.setStorageMb(1_000_000)
            assertEquals(65_536, repository.settings.first().storageMb)
            assertEquals(65_536, store.stored()["cloudAudio.storageMB"])
            repository.setDailyAhead(-3)
            assertEquals(1, repository.settings.first().dailyAhead)
            assertEquals(1, store.stored()["cloudAudio.dailyAhead"])
            repository.setDailyAhead(5_000)
            assertEquals(100, repository.settings.first().dailyAhead)
            assertEquals(100, store.stored()["cloudAudio.dailyAhead"])

            // Written by something else (a hand-edited backup): read back inside the range too.
            store.edit {
                it[intPreferencesKey("cloudAudio.monthlyCapUSD")] = 900
                it[intPreferencesKey("cloudAudio.storageMB")] = -50
                it[intPreferencesKey("cloudAudio.dailyAhead")] = 0
            }
            val low = repository.settings.first()
            assertEquals(200, low.monthlyCapUsd)
            assertEquals(256, low.storageMb)
            assertEquals(1, low.dailyAhead)

            store.edit {
                it[intPreferencesKey("cloudAudio.storageMB")] = 100_000
                it[intPreferencesKey("cloudAudio.dailyAhead")] = 101
            }
            val high = repository.settings.first()
            assertEquals(65_536, high.storageMb)
            assertEquals(100, high.dailyAhead)
        }
    }

    @Test
    fun `any negative stored cap reads as no limit`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)

            for (stored in listOf(-1, -5, Int.MIN_VALUE)) {
                store.edit { it[intPreferencesKey("cloudAudio.monthlyCapUSD")] = stored }
                assertNull(repository.settings.first().monthlyCapUsd, "stored $stored")
            }
            // The limits themselves are caps, not "no limit".
            store.edit { it[intPreferencesKey("cloudAudio.monthlyCapUSD")] = 0 }
            assertEquals(0, repository.settings.first().monthlyCapUsd)
            store.edit { it[intPreferencesKey("cloudAudio.monthlyCapUSD")] = 200 }
            assertEquals(200, repository.settings.first().monthlyCapUsd)
        }
    }

    @Test
    fun `the limits of the stored forms are the spec's`() {
        assertEquals(256, SpatialSettings.MIN_STORAGE_MB)
        assertEquals(65_536, SpatialSettings.MAX_STORAGE_MB)
        assertEquals(1, SpatialSettings.MIN_DAILY_AHEAD)
        assertEquals(100, SpatialSettings.MAX_DAILY_AHEAD)
        assertEquals(200, SpatialSettings.MAX_MONTHLY_CAP_USD)
        assertEquals(-1, SpatialSettingsRepository.NO_MONTHLY_CAP)
        // The defaults sit inside their own ranges.
        assertEquals(SpatialSettings.DEFAULT_STORAGE_MB, SpatialSettings.clampStorageMb(SpatialSettings.DEFAULT_STORAGE_MB))
        assertEquals(SpatialSettings.DEFAULT_DAILY_AHEAD, SpatialSettings.clampDailyAhead(SpatialSettings.DEFAULT_DAILY_AHEAD))
    }

    @Test
    fun `junk and wrongly typed values read as the defaults instead of crashing`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            store.edit {
                it[stringPreferencesKey("spatial.profile")] = "cinema"
                it[stringPreferencesKey("spatial.route")] = "everywhere"
                it[stringPreferencesKey("spatial.cloud")] = "yes"
                it[intPreferencesKey("spatial.prepareAhead")] = 1
                it[stringPreferencesKey("enhanced.enabled")] = "true"
                it[stringPreferencesKey("cloudAudio.storageMB")] = "3 GB"
                it[booleanPreferencesKey("cloudAudio.monthlyCapUSD")] = true
                it[stringPreferencesKey("cloudAudio.dailyAhead")] = "25"
            }

            assertEquals(
                SpatialSettings.defaults(builtInCloudKeys = true),
                SpatialSettingsRepository(store, BuiltInKeys).settings.first()
            )
        }
    }

    @Test
    fun `the settings travel in the settings backup`() = runTest {
        withTempDir { dir ->
            val store = newStore(dir)
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)
            repository.setProfile(SpatialProfile.NIGHT)
            repository.setEnhanced(false)
            repository.setMonthlyCapUsd(null)

            val exported = UserPreferencesRepository(dataStore = store, json = Json).exportPreferencesForBackup()

            assertTrue(exported.contains(PreferenceBackupEntry(key = "spatial.profile", type = "string", stringValue = "night")))
            assertTrue(exported.contains(PreferenceBackupEntry(key = "enhanced.enabled", type = "boolean", booleanValue = false)))
            assertTrue(exported.contains(PreferenceBackupEntry(key = "cloudAudio.monthlyCapUSD", type = "int", intValue = -1)))
        }
    }
}
