package com.theveloper.pixelplay.di

import com.theveloper.pixelplay.data.songtap.LibrarySongTapSongIds
import com.theveloper.pixelplay.data.songtap.SongTapLibrary
import com.theveloper.pixelplay.data.songtap.SongTapSongIds
import com.theveloper.pixelplay.data.songtap.SpotifySongTapLibrary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Song Tap's two seams: the library it saves into and the ids it reads from the song that is playing. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SongTapModule {
    @Binds @Singleton abstract fun library(impl: SpotifySongTapLibrary): SongTapLibrary
    @Binds @Singleton abstract fun songIds(impl: LibrarySongTapSongIds): SongTapSongIds
}
