package com.theveloper.pixelplay.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * All preferences share one DataStore, so it emits on every write. These tests pin that a write to
 * an UNRELATED key no longer makes a preference flow emit the same value again (that used to
 * restart the albums Pager / albums query / folder tree / ReplayGain pipelines on every library
 * tab switch, sync and track change), while a real change still comes through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceFlowsDistinctTest {

    /** Collects [flow] from now on and returns the live list of everything it emitted. */
    private fun <T> TestScope.collectAll(flow: Flow<T>): List<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        return seen
    }

    private fun TestScope.newRepository(tempDir: java.nio.file.Path) = UserPreferencesRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
        ),
        json = Json
    )

    @Test
    fun `unrelated writes do not re-emit the library and playback preference flows`() = runTest {
        val tempDir = Files.createTempDirectory("preference-flows-distinct-test")
        try {
            val repository = newRepository(tempDir)
            val minTracks = collectAll(repository.minTracksPerAlbumFlow)
            val folderFilter = collectAll(repository.isFolderFilterActiveFlow)
            val foldersSource = collectAll(repository.foldersSourceFlow)
            val replayGain = collectAll(repository.replayGainEnabledFlow)
            val replayGainAlbum = collectAll(repository.replayGainUseAlbumGainFlow)
            val mockGenres = collectAll(repository.mockGenresEnabledFlow)
            val genreGrid = collectAll(repository.isGenreGridViewFlow)
            val albumsList = collectAll(repository.isAlbumsListViewFlow)
            val dailyMix = collectAll(repository.dailyMixSongIdsFlow)
            val minDuration = collectAll(repository.minSongDurationFlow)
            // Control: a flow whose key IS written, so we know the writes below really reached DataStore.
            val lastTab = collectAll(repository.lastLibraryTabIndexFlow)
            testScheduler.advanceUntilIdle()

            // The writes the app really does all the time.
            repository.saveLastLibraryTabIndex(3)
            repository.setLastSyncTimestamp(42L)
            repository.setNavBarStyle("compact")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(0, 3), lastTab)
            assertEquals(1, minTracks.size)
            assertEquals(1, folderFilter.size)
            assertEquals(1, foldersSource.size)
            assertEquals(1, replayGain.size)
            assertEquals(1, replayGainAlbum.size)
            assertEquals(1, mockGenres.size)
            assertEquals(1, genreGrid.size)
            assertEquals(1, albumsList.size)
            assertEquals(1, dailyMix.size)
            assertEquals(1, minDuration.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a real change still emits once and writing the same value again emits nothing`() = runTest {
        val tempDir = Files.createTempDirectory("preference-flows-distinct-test")
        try {
            val repository = newRepository(tempDir)
            val minTracks = collectAll(repository.minTracksPerAlbumFlow)
            testScheduler.advanceUntilIdle()

            repository.setMinTracksPerAlbum(3)
            testScheduler.advanceUntilIdle()
            repository.setMinTracksPerAlbum(3)
            repository.saveLastLibraryTabIndex(2)
            testScheduler.advanceUntilIdle()
            repository.setMinTracksPerAlbum(1)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(1, 3, 1), minTracks)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
