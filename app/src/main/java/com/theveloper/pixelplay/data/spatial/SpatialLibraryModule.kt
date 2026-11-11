package com.theveloper.pixelplay.data.spatial

import android.content.Context
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import com.theveloper.pixelplay.data.spatial.library.CloudSpatialLibrary
import com.theveloper.pixelplay.data.spatial.library.EngineSpatialCloud
import com.theveloper.pixelplay.data.spatial.library.LiveSpatialNetworkSource
import com.theveloper.pixelplay.data.spatial.library.SpatialAheadScheduler
import com.theveloper.pixelplay.data.spatial.library.SpatialFailureLedger
import com.theveloper.pixelplay.data.spatial.library.SpatialRenderStore
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Which [SpatialLibrary] the app runs with. Its own module, apart from [SpatialPlaybackModule], so the cloud side and
 * the player side each swap their binding without touching the other's file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpatialLibraryModule {
    /** The phone's renders, `filesDir/cloud_audio/` (§6.2 "Local store"), under `cloudAudio.storageMB`. */
    @Provides
    @Singleton
    fun provideSpatialRenderStore(
        @ApplicationContext context: Context,
        settings: SpatialSettingsRepository,
    ): SpatialRenderStore = SpatialRenderStore(
        root = File(context.filesDir, SpatialRenderStore.DIRECTORY),
        budgetBytes = { settings.current().storageBytes },
        scope = libraryScope(),
    )

    /** The cloud side over Cloud Studio: the shared library, library jobs and the background queue (§6.2, §6.4). */
    @Provides
    @Singleton
    fun provideCloudSpatialLibrary(
        @ApplicationContext context: Context,
        engine: CloudStudioEngine,
        store: SpatialRenderStore,
        settings: SpatialSettingsRepository,
        cloudSettings: CloudStudioSettings,
        networkConditions: StreamNetworkConditions,
        aheadScheduler: SpatialAheadScheduler,
    ): CloudSpatialLibrary = CloudSpatialLibrary(
        engine = EngineSpatialCloud(engine),
        store = store,
        settings = settings.settings,
        cloudSettings = cloudSettings.settings,
        networkSource = LiveSpatialNetworkSource(context, networkConditions),
        ledger = SpatialFailureLedger.inPreferences(context),
        aheadSchedule = aheadScheduler::apply,
        // Downloads from the shared library land here (no backup) before the store checks and moves them.
        stagingDir = File(context.noBackupFilesDir, "cloud_audio_staging"),
        scope = libraryScope(),
    )

    @Provides
    @Singleton
    fun provideSpatialLibrary(library: CloudSpatialLibrary): SpatialLibrary = library

    /** The library's own scope: IO, and an unexpected failure is logged (its class only), never a crash. */
    private fun libraryScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> Timber.w("Spatial audio library: %s", error.javaClass.simpleName) }
    )
}
