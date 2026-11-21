package com.theveloper.pixelplay.di

import com.theveloper.pixelplay.data.songtap.LibrarySongTapSongIds
import com.theveloper.pixelplay.data.songtap.ble.AndroidBleRadio
import com.theveloper.pixelplay.data.songtap.ble.BleRadio
import com.theveloper.pixelplay.data.songtap.ble.ImpulseSource
import com.theveloper.pixelplay.data.songtap.ble.SensorImpulseSource
import com.theveloper.pixelplay.data.songtap.ble.SongTapClock
import com.theveloper.pixelplay.data.songtap.ble.SystemSongTapClock
import com.theveloper.pixelplay.data.songtap.SongTapLibrary
import com.theveloper.pixelplay.data.songtap.SongTapSongIds
import com.theveloper.pixelplay.data.songtap.SpotifySongTapLibrary
import com.theveloper.pixelplay.data.songtap.ring.InnerTubeRingCodeResolver
import com.theveloper.pixelplay.data.songtap.ring.RingCodeResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Song Tap's seams: the library it saves into, the ids it reads from the song that is playing, and the Bluetooth radio, accelerometer and clocks. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SongTapModule {
    @Binds @Singleton abstract fun library(impl: SpotifySongTapLibrary): SongTapLibrary
    @Binds @Singleton abstract fun songIds(impl: LibrarySongTapSongIds): SongTapSongIds
    @Binds @Singleton abstract fun ringResolver(impl: InnerTubeRingCodeResolver): RingCodeResolver
    @Binds @Singleton abstract fun radio(impl: AndroidBleRadio): BleRadio
    @Binds @Singleton abstract fun impulses(impl: SensorImpulseSource): ImpulseSource
    @Binds @Singleton abstract fun clock(impl: SystemSongTapClock): SongTapClock
}
