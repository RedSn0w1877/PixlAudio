package com.theveloper.pixelplay.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainIdentity
import com.theveloper.pixelplay.data.brain.TrackResolver
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.source.EphemeralTrackLookup
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.source.RadioSource
import com.theveloper.pixelplay.data.brain.source.YouTubeRadioSource
import com.theveloper.pixelplay.data.brain.store.AffinityDao
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.PlayableDao
import com.theveloper.pixelplay.data.brain.store.RadioListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The music brain's graph (docs/brain-spec.md, 2.1 and 3.8).
 *
 * `brain.db` is its own Room database. Building it here does not open the file: Room opens a database
 * on its first query, and nothing queries the brain at app start. There is no destructive fallback,
 * in any build type: `play_event` and the alias tables are the listener's history and must survive
 * every upgrade, so a missing migration has to fail loudly in testing rather than wipe them.
 */
@Module
@InstallIn(SingletonComponent::class)
object BrainModule {

    @Provides
    @Singleton
    fun provideMusicBrainDatabase(@ApplicationContext context: Context): MusicBrainDatabase =
        Room.databaseBuilder(context.applicationContext, MusicBrainDatabase::class.java, MusicBrainDatabase.FILE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun providePlayableDao(db: MusicBrainDatabase): PlayableDao = db.playableDao()
    @Provides fun provideAliasDao(db: MusicBrainDatabase): AliasDao = db.aliasDao()
    @Provides fun provideRadioListDao(db: MusicBrainDatabase): RadioListDao = db.radioListDao()
    @Provides fun providePlayEventDao(db: MusicBrainDatabase): PlayEventDao = db.playEventDao()
    @Provides fun provideAffinityDao(db: MusicBrainDatabase): AffinityDao = db.affinityDao()
    @Provides fun provideBrainStateDao(db: MusicBrainDatabase): BrainStateDao = db.brainStateDao()

    @Provides
    @Singleton
    fun provideEphemeralTrackLookup(store: PlayableStore): EphemeralTrackLookup = store

    @Provides
    @Singleton
    fun provideRadioSource(source: YouTubeRadioSource): RadioSource = source

    @Provides
    @Singleton
    fun provideTrackResolver(identity: BrainIdentity): TrackResolver = identity

    /** The YouTube Music bucket: the only host the brain talks to in stage 1. */
    @Provides
    @Singleton
    fun provideYouTubeHostBudget(): HostBudget = HostBudget()

    @Provides
    @Singleton
    fun provideBrainClock(): BrainClock = BrainClock { System.currentTimeMillis() }
}
