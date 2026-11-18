package com.theveloper.pixelplay.data.brain.store

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The music brain's own database, file `brain.db` (docs/brain-spec.md, 3.8).
 *
 * Version 1 holds the stage 1 tables only. Every later table arrives with the stage that first uses
 * it, as a real Room migration: `play_event`, `track_alias`, `track_merge` and `artist_alias` are the
 * listener's history and identity and must survive every upgrade. Nothing here is opened at app
 * start; the first query (a non-library track resolving, an autoplay pool) opens the file.
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicBrainDatabase : RoomDatabase() {
    abstract fun playableDao(): PlayableDao
    abstract fun aliasDao(): AliasDao
    abstract fun radioListDao(): RadioListDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun affinityDao(): AffinityDao
    abstract fun brainStateDao(): BrainStateDao

    companion object {
        const val FILE_NAME = "brain.db"
        const val MAX_RADIO_LISTS = 500
    }
}
