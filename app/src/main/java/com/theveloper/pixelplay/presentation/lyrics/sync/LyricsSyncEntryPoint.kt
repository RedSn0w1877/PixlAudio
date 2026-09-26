package com.theveloper.pixelplay.presentation.lyrics.sync

import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * For composables outside the player that reach the "sync it yourself" editor (Edit song):
 * `EntryPointAccessors.fromApplication(context, LyricsSyncEntryPoint::class.java)`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LyricsSyncEntryPoint {
    fun lyricsSyncEditor(): LyricsSyncEditorStateHolder
    fun lyricsRepository(): LyricsRepository
}
