package com.theveloper.pixelplay.data.spatial

import com.theveloper.pixelplay.data.spatial.playback.RealSpatialPlayback
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
    /** The player side (§6.3, §7): the on-device stage, the renditions, the route and level rules. */
    @Provides
    @Singleton
    fun provideSpatialPlayback(real: RealSpatialPlayback): SpatialPlayback = real
}
