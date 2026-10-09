package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import com.theveloper.pixelplay.data.model.PlaybackQueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The saved playback queue (about 1.3 MB for 5,000 songs) lives in its own DataStore file, so settings
 * edits no longer rewrite it and the settings file the splash waits for stays small. A queue saved by an
 * earlier version (under the `settings` key) must be carried over, never lost.
 */
class PlaybackQueueStoreTest {

    private val legacyKey = stringPreferencesKey("playback_queue_snapshot_v1")
    private val json = Json

    private fun CoroutineScope.store(dir: Path, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = this, produceFile = { dir.resolve("$name.preferences_pb").toFile() })

    private fun snapshot(size: Int, current: Int = 7) = PlaybackQueueSnapshot(
        items = List(size) {
            PlaybackQueueItemSnapshot(
                mediaId = "-30000000${it}",
                uri = "content://media/external/audio/media/$it",
                title = "A reasonably long song title number $it",
                artist = "Some Artist ${it % 97}",
                albumTitle = "An album called ${it % 311}",
                artworkUri = "content://com.theveloper.pixelplay.provider/albumart/${it % 311}",
                durationMs = 180_000L + it
            )
        },
        currentMediaId = "-30000000$current",
        currentIndex = current,
        currentPositionMs = 42_000L,
        playWhenReady = true,
        repeatMode = 2,
        shuffleEnabled = true,
        savedAtEpochMs = 1_800_000_000_000L
    )

    private fun File.sizeOrZero() = if (exists()) length() else 0L

    @Test
    fun `a big queue no longer fattens the settings file`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val settings = backgroundScope.store(dir, "settings")
            val queue = backgroundScope.store(dir, "playback_queue")
            val repository = UserPreferencesRepository(settings, json, queue)

            repository.setPlaybackQueueSnapshot(snapshot(5_000))
            repository.setNavBarStyle("compact") // some unrelated settings write

            val settingsBytes = dir.resolve("settings.preferences_pb").toFile().sizeOrZero()
            val queueBytes = dir.resolve("playback_queue.preferences_pb").toFile().sizeOrZero()
            println("settings.preferences_pb = $settingsBytes B, playback_queue.preferences_pb = $queueBytes B")
            assertTrue(settingsBytes < 32 * 1024, "settings file is $settingsBytes B")
            assertTrue(queueBytes > 500 * 1024, "queue file is only $queueBytes B")
            assertNull(settings.data.first()[legacyKey])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the queue round trips exactly and an empty or null queue clears it`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val repository = UserPreferencesRepository(
                backgroundScope.store(dir, "settings"), json, backgroundScope.store(dir, "playback_queue")
            )
            assertNull(repository.getPlaybackQueueSnapshotOnce())

            val saved = snapshot(300)
            repository.setPlaybackQueueSnapshot(saved)
            assertEquals(saved, repository.getPlaybackQueueSnapshotOnce())

            repository.setPlaybackQueueSnapshot(saved.copy(items = emptyList()))
            assertNull(repository.getPlaybackQueueSnapshotOnce())

            repository.setPlaybackQueueSnapshot(saved)
            repository.setPlaybackQueueSnapshot(null)
            assertNull(repository.getPlaybackQueueSnapshotOnce())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a queue saved by an earlier version is moved over once and nothing is lost`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val settings = backgroundScope.store(dir, "settings")
            val queue = backgroundScope.store(dir, "playback_queue")
            val old = snapshot(2_000, current = 1_234)
            settings.edit { it[legacyKey] = json.encodeToString(old) }

            val repository = UserPreferencesRepository(settings, json, queue)
            assertEquals(old, repository.getPlaybackQueueSnapshotOnce())

            // Moved: gone from settings, present in the queue store, and still readable.
            assertNull(settings.data.first()[legacyKey])
            assertNotNull(queue.data.first()[legacyKey])
            assertEquals(old, UserPreferencesRepository(settings, json, queue).getPlaybackQueueSnapshotOnce())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `saving before the first read also migrates, and a newer queue in the new store wins`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val settings = backgroundScope.store(dir, "settings")
            val queue = backgroundScope.store(dir, "playback_queue")
            settings.edit { it[legacyKey] = json.encodeToString(snapshot(50)) }
            val newer = snapshot(10, current = 3)

            val repository = UserPreferencesRepository(settings, json, queue)
            repository.setPlaybackQueueSnapshot(newer)

            assertNull(settings.data.first()[legacyKey], "the stale copy in settings must be removed")
            assertEquals(newer, repository.getPlaybackQueueSnapshotOnce())

            // Both present from a crash between the two writes: the new store wins and the old key is dropped.
            settings.edit { it[legacyKey] = json.encodeToString(snapshot(5)) }
            val afterCrash = UserPreferencesRepository(settings, json, queue)
            assertEquals(newer, afterCrash.getPlaybackQueueSnapshotOnce())
            assertNull(settings.data.first()[legacyKey])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an unreadable queue is treated as no queue instead of failing`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val queue = backgroundScope.store(dir, "playback_queue")
            queue.edit { it[legacyKey] = "{not json" }
            val repository = UserPreferencesRepository(backgroundScope.store(dir, "settings"), json, queue)
            assertNull(repository.getPlaybackQueueSnapshotOnce())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a settings reset or a clearing restore also clears the queue, and keeping the key keeps it`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val settings = backgroundScope.store(dir, "settings")
            val queue = backgroundScope.store(dir, "playback_queue")
            val repository = UserPreferencesRepository(settings, json, queue)

            repository.setPlaybackQueueSnapshot(snapshot(20))
            repository.clearPreferencesExceptKeys(setOf("playback_queue_snapshot_v1"))
            assertNotNull(repository.getPlaybackQueueSnapshotOnce(), "an excluded key must survive")

            repository.clearPreferencesExceptKeys(emptySet())
            assertNull(repository.getPlaybackQueueSnapshotOnce())

            repository.setPlaybackQueueSnapshot(snapshot(20))
            repository.importPreferencesFromBackup(emptyList(), clearExisting = true)
            assertNull(repository.getPlaybackQueueSnapshotOnce())

            repository.setPlaybackQueueSnapshot(snapshot(20))
            repository.importPreferencesFromBackup(emptyList(), clearExisting = false)
            assertNotNull(repository.getPlaybackQueueSnapshotOnce())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `with a single store the queue stays where it always was`() = runTest {
        val dir = Files.createTempDirectory("queue-store-test")
        try {
            val settings = backgroundScope.store(dir, "settings")
            val repository = UserPreferencesRepository(settings, json)
            val saved = snapshot(25)

            repository.setPlaybackQueueSnapshot(saved)

            assertNotNull(settings.data.first()[legacyKey])
            assertEquals(saved, repository.getPlaybackQueueSnapshotOnce())
            assertFalse(dir.resolve("playback_queue.preferences_pb").toFile().exists())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
