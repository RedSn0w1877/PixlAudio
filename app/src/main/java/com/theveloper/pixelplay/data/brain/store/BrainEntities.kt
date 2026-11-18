package com.theveloper.pixelplay.data.brain.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The stage 1 tables of the music brain (docs/brain-spec.md, 3.8). They live in their own database
 * file, `brain.db`; the main database and `listening_history.db` are not touched.
 *
 * "truth" tables must survive every upgrade (real migrations only). "cache" tables may be rebuilt.
 */

/** truth. One append-only row per play attempt (4.1). Raw facts only; reward is derived at replay. */
@Entity(tableName = "play_event", indices = [Index("rid"), Index("startedAtMs")])
data class PlayEventEntity(
    /** `(startedAtMs << 16) | seq`: time-ordered. */
    @PrimaryKey val eventId: Long,
    val schema: Int = 1,
    /** 0 live, 1 importSpotify, 2 importYtm, 3 migratedHistory. */
    val origin: Int,
    val rid: Long,
    val primaryAid: Long,
    /** Up to 4 further credited artists, decimal ids joined by ','. */
    val otherAids: String = "",
    val keyAtWrite: String,
    val startedAtMs: Long,
    val tzOffsetMin: Int?,
    /** Audible milliseconds; -1 unknown. */
    val playedMs: Int,
    /** 0 unknown. */
    val durationMs: Int,
    val startReason: Int,
    val endReason: Int,
    val flags: Int = 0,
    val surface: Int,
    val surfaceId: String = "",
    val recId: Long = 0L,
    val slot: Int = -1,
    val arm: Int,
    val sessionId: Long = 0L,
    val posInSession: Int = 0,
    val passiveRun: Int = 0,
    val route: Int = 0,
    val retrQ: Int = -1,
    val radioRank: Int = -1,
    val pServedQ: Int = 0,
    val modelVersion: Int = 0,
)

/**
 * truth. Every key of every recording (3.1).
 *
 * [durationMs] is an addition to the spec's `(key, rid)`: step 2 of the resolve compares "the two
 * durations", and stage 1 has no `track_dna` to read the stored one from. It is the length the
 * caller knew when the key was stored, 0 when unknown.
 */
@Entity(tableName = "track_alias", indices = [Index("rid")])
data class TrackAliasEntity(
    @PrimaryKey val key: String,
    val rid: Long,
    @ColumnInfo(defaultValue = "0") val durationMs: Int = 0,
)

/** truth. `rid` is the same recording as `into`; chains are flattened at write time. */
@Entity(tableName = "track_merge")
data class TrackMergeEntity(
    @PrimaryKey val rid: Long,
    val into: Long,
)

/** truth. Every key of every artist. */
@Entity(tableName = "artist_alias", indices = [Index("aid")])
data class ArtistAliasEntity(
    @PrimaryKey val key: String,
    val aid: Long,
)

/** cache. The decayed counters of 5.2, one row per key; rebuilt by replaying `play_event`. */
@Entity(tableName = "affinity", primaryKeys = ["family", "key"])
data class AffinityEntity(
    val family: Int,
    val key: Long,
    val posQ: Long = 0L,
    val negQ: Long = 0L,
    val lastMs: Long = 0L,
    val posRQ: Long = 0L,
    val negRQ: Long = 0L,
    val lastRMs: Long = 0L,
)

/**
 * cache. One fetched radio: parsed rows, never the raw response (a raw radio is over 500 KB).
 * At most [MusicBrainDatabase.MAX_RADIO_LISTS] lists are kept, least recently used out first.
 */
@Entity(tableName = "radio_list", primaryKeys = ["seedRid", "kind", "chip"])
data class RadioListEntity(
    val seedRid: Long,
    /** [KIND_SONG], [KIND_RELATED] or [KIND_ARTIST]. */
    val kind: Int,
    /** The tuning chip the list was fetched under; "" for the plain radio. */
    val chip: String = "",
    val fetchedAt: Long,
    val lastUsedAt: Long,
    /** False when the junk detector rejected it; kept so the seed is not asked again at once. */
    val healthy: Boolean,
    /** Gzip of the JSON list of rows (see RadioRowCodec). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val rowsBlob: ByteArray,
    /** Gzip of the JSON list of chips; labels are kept as text for stage 2's tag source. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val chipsBlob: ByteArray,
) {
    companion object {
        const val KIND_SONG = 0
        const val KIND_RELATED = 1
        const val KIND_ARTIST = 2
    }

    // Arrays compare by identity in a data class; rows are identified by their key and fetch time.
    override fun equals(other: Any?): Boolean =
        other is RadioListEntity && other.seedRid == seedRid && other.kind == kind && other.chip == chip &&
            other.fetchedAt == fetchedAt

    override fun hashCode(): Int = (seedRid xor fetchedAt).toInt() * 31 + kind
}

/**
 * cache. How a track that is not in the library is played (7.2): `spotify://<synthId>` resolves to
 * [videoId] through this row when `spotify_songs` has no match. Also what Recently Played shows for it.
 */
@Entity(tableName = "playable", indices = [Index("lastUsedAt"), Index("songId")])
data class PlayableEntity(
    /** `SpotifyRepository.youTubeMusicSyntheticId(videoId)`: the id a later Save produces too. */
    @PrimaryKey val synthId: String,
    /**
     * `SpotifyRepository.unifiedSongId(synthId)`: the media id the track plays under, and the id the
     * history stores. An addition to the spec's columns: that id is a one-way hash of [synthId], so
     * Recently Played could not find this row without it.
     */
    val songId: Long,
    val rid: Long,
    val videoId: String,
    val title: String,
    val artistDisplay: String,
    val album: String?,
    val durationMs: Long,
    val artUrl: String?,
    val lastUsedAt: Long,
)

/** cache. The small parts of the brain's state (versions, salt, ...). Never the affinity counters. */
@Entity(tableName = "brain_state")
data class BrainStateEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BrainStateEntity && other.key == key && other.blob.contentEquals(blob)

    override fun hashCode(): Int = key.hashCode()
}
