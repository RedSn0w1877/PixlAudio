package com.theveloper.pixelplay.data.spatial

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which [SpatialLibrary] the app runs with. Its own module, apart from [SpatialPlaybackModule], so the cloud side and
 * the player side each swap their binding without touching the other's file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpatialLibraryModule {
    /** The cloud side replaces [NoOpSpatialLibrary] here with its real library (§6.2, §6.4). */
    @Provides
    @Singleton
    fun provideSpatialLibrary(): SpatialLibrary = NoOpSpatialLibrary
}
