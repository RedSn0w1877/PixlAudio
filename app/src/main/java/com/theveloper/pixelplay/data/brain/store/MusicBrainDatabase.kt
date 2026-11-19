package com.theveloper.pixelplay.data.brain.store

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The music brain's own database, file `brain.db` (docs/brain-spec.md, 3.8).
 *
 * Version 1 held the stage 1 tables. Version 2 (stage 2a) adds `graph_edge`, `served_list` and
 * `feedback`, version 3 (stage 2a, milestone 2) `spotify_fact`, version 4 (stage 2b) `track_dna`, `artist_dna` and `source_miss`, version 5 `node_embedding`, `island` and `island_member`, version 6 (stage 3) `impression`, each by a real migration ([MusicBrainMigrations]); `play_event`, `track_alias`, `track_merge`,
 * `artist_alias` and `feedback` are the listener's history and identity and must survive every
 * upgrade. Nothing here is opened at app start; the first query (a non-library track resolving, an
 * autoplay pool, a saved mix) opens the file.
 */
@Database(
    entities = [
        PlayEventEntity::class,
        TrackAliasEntity::class,
        TrackMergeEntity::class,
        ArtistAliasEntity::class,
        AffinityEntity::class,
        RadioListEntity::class,
        PlayableEntity::class,
        BrainStateEntity::class,
        GraphEdgeEntity::class,
        ServedListEntity::class,
        FeedbackEntity::class,
        SpotifyFactEntity::class,
        TrackDnaEntity::class,
        ArtistDnaEntity::class,
        SourceMissEntity::class,
        NodeEmbeddingEntity::class,
        IslandEntity::class,
        IslandMemberEntity::class,
        ImpressionEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class MusicBrainDatabase : RoomDatabase() {
    abstract fun playableDao(): PlayableDao
    abstract fun aliasDao(): AliasDao
    abstract fun radioListDao(): RadioListDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun affinityDao(): AffinityDao
    abstract fun brainStateDao(): BrainStateDao
    abstract fun graphEdgeDao(): GraphEdgeDao
    abstract fun servedListDao(): ServedListDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun seedDao(): SeedDao
    abstract fun spotifyFactDao(): SpotifyFactDao
    abstract fun trackDnaDao(): TrackDnaDao
    abstract fun artistDnaDao(): ArtistDnaDao
    abstract fun sourceMissDao(): SourceMissDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun islandDao(): IslandDao
    abstract fun impressionDao(): ImpressionDao

    companion object {
        const val FILE_NAME = "brain.db"
        const val MAX_RADIO_LISTS = 500
    }
}
