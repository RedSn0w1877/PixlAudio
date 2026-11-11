package com.theveloper.pixelplay.data.spatial

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which [SpatialPlayback] the app runs with. Its own module, apart from [SpatialLibraryModule], so the player side and
 * the cloud side each swap their binding without touching the other's file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpatialPlaybackModule {
    /** The player side replaces [NoOpSpatialPlayback] here with its real implementation (§6.3, §7). */
    @Provides
    @Singleton
    fun provideSpatialPlayback(): SpatialPlayback = NoOpSpatialPlayback
}
