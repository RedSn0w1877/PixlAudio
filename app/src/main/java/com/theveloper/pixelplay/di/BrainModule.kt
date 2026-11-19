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
import com.theveloper.pixelplay.data.brain.store.FeedbackDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeDao
import com.theveloper.pixelplay.data.brain.store.ImpressionDao
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import com.theveloper.pixelplay.data.brain.store.MusicBrainMigrations
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.brain.store.ServedListDao
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
            .addMigrations(*MusicBrainMigrations.ALL)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun providePlayableDao(db: MusicBrainDatabase): PlayableDao = db.playableDao()
    @Provides fun provideImpressionDao(db: MusicBrainDatabase): ImpressionDao = db.impressionDao()
    @Provides fun provideAliasDao(db: MusicBrainDatabase): AliasDao = db.aliasDao()
    @Provides fun provideRadioListDao(db: MusicBrainDatabase): RadioListDao = db.radioListDao()
    @Provides fun providePlayEventDao(db: MusicBrainDatabase): PlayEventDao = db.playEventDao()
    @Provides fun provideAffinityDao(db: MusicBrainDatabase): AffinityDao = db.affinityDao()
    @Provides fun provideBrainStateDao(db: MusicBrainDatabase): BrainStateDao = db.brainStateDao()
    @Provides fun provideGraphEdgeDao(db: MusicBrainDatabase): GraphEdgeDao = db.graphEdgeDao()
    @Provides fun provideServedListDao(db: MusicBrainDatabase): ServedListDao = db.servedListDao()
    @Provides fun provideFeedbackDao(db: MusicBrainDatabase): FeedbackDao = db.feedbackDao()
    @Provides fun provideSeedDao(db: MusicBrainDatabase): SeedDao = db.seedDao()
    @Provides fun provideSpotifyFactDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.SpotifyFactDao = db.spotifyFactDao()

    @Provides fun provideTrackDnaDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.TrackDnaDao = db.trackDnaDao()
    @Provides fun provideArtistDnaDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.ArtistDnaDao = db.artistDnaDao()
    @Provides fun provideSourceMissDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.SourceMissDao = db.sourceMissDao()
    @Provides fun provideEmbeddingDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.EmbeddingDao = db.embeddingDao()
    @Provides fun provideIslandDao(db: MusicBrainDatabase): com.theveloper.pixelplay.data.brain.store.IslandDao = db.islandDao()

    /** Every stored radio also becomes edges of the persisted graph (3.6). */
    @Provides
    @Singleton
    fun provideRadioEdges(store: com.theveloper.pixelplay.data.brain.GraphEdgeStore): com.theveloper.pixelplay.data.brain.RadioEdges = store

    @Provides
    @Singleton
    fun provideEphemeralTrackLookup(store: PlayableStore): EphemeralTrackLookup = store

    @Provides
    @Singleton
    fun provideRadioSource(source: YouTubeRadioSource): RadioSource = source

    @Provides
    @Singleton
    fun provideTrackResolver(identity: BrainIdentity): TrackResolver = identity

    @Provides
    @Singleton
    fun provideBrainStore(store: com.theveloper.pixelplay.data.brain.store.RoomBrainStore): com.theveloper.pixelplay.data.brain.store.BrainStore = store

    @Provides
    @Singleton
    fun provideTrackCatalog(catalog: com.theveloper.pixelplay.data.brain.LibraryTrackCatalog): com.theveloper.pixelplay.data.brain.TrackCatalog = catalog

    /** The one-shot migration and every later rebuild of the affinity rows (4.4). */
    @Provides
    @Singleton
    fun provideStateRebuilder(rebuilder: com.theveloper.pixelplay.data.brain.AppStateRebuilder): com.theveloper.pixelplay.data.brain.StateRebuilder = rebuilder

    /** Where the listening tracker sends every play: the brain's event recorder. */
    @Provides
    @Singleton
    fun providePlaySink(recorder: com.theveloper.pixelplay.data.brain.BrainEventRecorder): com.theveloper.pixelplay.data.brain.PlaySink = recorder

    /** The YouTube Music bucket: the only host the brain talks to in stage 1. */
    @Provides
    @Singleton
    fun provideYouTubeHostBudget(ledger: com.theveloper.pixelplay.data.brain.RequestLedger): HostBudget =
        HostBudget(onSpend = { ledger.record(it) }, dayHasRoom = { ledger.hasRoom() })

    /** "New from artists you play": one small file, read by Home without the database. */
    @Provides
    @Singleton
    fun provideReleaseFileStore(store: com.theveloper.pixelplay.data.brain.AtomicReleaseFileStore): com.theveloper.pixelplay.data.brain.ReleaseFileStore = store

    /** What the Spotify sync returns beyond what it stores in the main database goes to `spotify_fact`. */
    @Provides
    @Singleton
    fun provideBrainFactsSink(sink: com.theveloper.pixelplay.data.brain.RoomBrainFactsSink): com.theveloper.pixelplay.data.brain.BrainFactsSink = sink

    @Provides
    @Singleton
    fun provideArtistKeySink(identity: BrainIdentity): com.theveloper.pixelplay.data.brain.ArtistKeySink = identity

    @Provides
    @Singleton
    fun provideKnowledgeSwitch(switch: com.theveloper.pixelplay.data.brain.BrainSwitch): com.theveloper.pixelplay.data.brain.source.KnowledgeSwitch = switch

    /** The open-data hosts' one door (off switches, budgets, ledger, spacing, back-off). */
    @Provides
    @Singleton
    fun provideKnowledgeHttp(http: com.theveloper.pixelplay.data.brain.source.OkHttpKnowledgeHttp): com.theveloper.pixelplay.data.brain.source.KnowledgeHttp = http

    /** The genre atlas, read from the asset the first time something needs a genre. */
    @Provides
    @Singleton
    fun provideAtlasProvider(store: com.theveloper.pixelplay.data.brain.GenreAtlasStore): com.theveloper.pixelplay.data.brain.AtlasProvider = store

    @Provides
    @Singleton
    fun provideBrainClock(): BrainClock = BrainClock { System.currentTimeMillis() }
}
