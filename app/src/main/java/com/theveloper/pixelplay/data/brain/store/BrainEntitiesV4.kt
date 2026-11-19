package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Track DNA (docs/brain-spec.md, 3.2): what the brain knows about one recording beyond how it was played. cache: every
 * column can be fetched again; nothing here is the listener's history. One row per recording id.
 *
 * Integers only, so both apps store and compare it identically. Unknown is a value, never a guess: year 0, popularity -1,
 * energy and valence -1, tempo 0, gain -32768, explicit -1, an empty tag blob.
 *
 * Differences from the spec's list (written down in the stage 2b handoff): `emb` (the graph embedding) arrives with the
 * embeddings and is added by that migration; `tagsAtMs`, `popAtMs` and `atlasVersion` are new, because tags expire after 90
 * days and popularity after 7 (3.7) and a rebuilt atlas needs the rows it must remap.
 */
@Entity(tableName = "track_dna", indices = [Index("spotifyId"), Index("updatedAtMs")])
data class TrackDnaEntity(
    @PrimaryKey val rid: Long,
    val spotifyId: String?,
    val isrc: String?,
    val deezerId: Long?,
    val mbRecordingId: String?,
    /** Known official-audio video ids, joined by ','. */
    val ytAudioIds: String,
    /** Artist ids (decimal), primary first, joined by ','. */
    val artistIds: String,
    val albumKey: Long?,
    val title: String,
    val artistDisplay: String,
    val versionFlags: Int,
    val firstYear: Int,
    val yearSource: Int,
    val durationMs: Int,
    val explicit: Int,
    val popPermille: Int,
    val tempoBpmX10: Int,
    val tempoSource: Int,
    val gainDbX10: Int,
    val scriptLang: Int,
    /** `Tags.encode`: 3 bytes a tag (atlas id, weight). Empty means unknown. */
    val tags: ByteArray,
    /** Up to 4 mood ids, one byte each. */
    val moods: ByteArray,
    val energy: Int,
    val valence: Int,
    val embVersion: Int,
    val confidence: Int,
    /** One bit per source tried: see [DnaSource]. */
    val fetchedMask: Int,
    val updatedAtMs: Long,
    val tagsAtMs: Long,
    val popAtMs: Long,
    /** The atlas version the tag ids were written with. */
    val atlasVersion: Int,
    /**
     * What each source said, so a refresh of one source re-merges the others without asking them again: 4 bytes a claim (source
     * number, atlas id high byte, atlas id low byte, confidence 0..255).
     */
    val tagInputs: ByteArray,
    /** Year claims, `source:year` joined by ','. */
    val yearClaims: String,
    /** Popularity claims, `d:<permille>`, `y:` and `l:` joined by ','. */
    val popClaims: String,
)

/** The artist-level facts (3.2, `ArtistDna`). cache. */
@Entity(tableName = "artist_dna", indices = [Index("spotifyId"), Index("mbid")])
data class ArtistDnaEntity(
    @PrimaryKey val aid: Long,
    val spotifyId: String?,
    val ytChannelId: String?,
    val deezerId: Long?,
    val mbid: String?,
    val wikidataId: String?,
    val name: String,
    /** ISO 3166 code, or empty. */
    val country: String,
    val tags: ByteArray,
    val popPermille: Int,
    /** The last seen `MPREb_` ids for the release diff (6.3), joined by ','. */
    val releaseIds: String,
    val fetchedMask: Int,
    val updatedAtMs: Long,
    val tagsAtMs: Long,
    val atlasVersion: Int,
    /** As [TrackDnaEntity.tagInputs], for the artist-level sources only. */
    val tagInputs: ByteArray,
)

/**
 * The negative cache (3.7): a source that had nothing for an entity is not asked again until [retryAfter]. 14 days, doubling
 * to 120 with every further miss. [entity] is `t:<rid>` or `a:<aid>`; [source] is a [DnaSource] bit's number.
 */
@Entity(tableName = "source_miss", primaryKeys = ["entity", "source"])
data class SourceMissEntity(
    val entity: String,
    val source: Int,
    val retryAfter: Long,
    val misses: Int,
)

/** The bits of `fetchedMask`: which sources have been tried for a row. */
object DnaSource {
    const val DEEZER = 1
    const val MUSICBRAINZ = 2
    const val LISTENBRAINZ = 4
    const val WIKIDATA = 8
    const val ITUNES = 16
    const val SPOTIFY_GENRES = 32
    const val YOUTUBE_CHIPS = 64
    const val YOUTUBE_ROW = 128

    /** The number a source has in `source_miss.source` and the ledger: the bit's position. */
    fun numberOf(bit: Int): Int = Integer.numberOfTrailingZeros(bit)
}

/** What a coverage count reports (the Developer screen and the handoff). */
data class DnaCoverage(
    val tracks: Int,
    val withTags: Int,
    val withYear: Int,
    val withPopularity: Int,
    val withEnergy: Int,
    val withTempo: Int,
)

@Dao
interface TrackDnaDao {
    @Upsert
    suspend fun upsert(row: TrackDnaEntity)

    @Upsert
    suspend fun upsertAll(rows: List<TrackDnaEntity>)

    @Query("SELECT * FROM track_dna WHERE rid = :rid")
    suspend fun get(rid: Long): TrackDnaEntity?

    @Query("SELECT * FROM track_dna WHERE rid IN (:rids)")
    suspend fun getAll(rids: List<Long>): List<TrackDnaEntity>

    @Query("SELECT * FROM track_dna WHERE spotifyId IN (:spotifyIds)")
    suspend fun bySpotifyIds(spotifyIds: List<String>): List<TrackDnaEntity>

    /** Spotify ids that already have a row, so the library scan can leave them alone. */
    @Query("SELECT spotifyId FROM track_dna WHERE spotifyId IN (:spotifyIds)")
    suspend fun knownSpotifyIds(spotifyIds: List<String>): List<String>

    @Query("SELECT rid, updatedAtMs, tagsAtMs, popAtMs, fetchedMask FROM track_dna WHERE rid IN (:rids)")
    suspend fun freshnessOf(rids: List<Long>): List<DnaFreshness>

    /**
     * Rows lane 5 should look at again (3.7), the stalest first: tags older than 90 days, popularity older than 7, or a row that has
     * not yet been answered by all three of Deezer, MusicBrainz and iTunes (a source that was off, over its budget for the day or
     * offline when the row was made) and was last looked at before [retryBefore]. A source that answered "nothing" has its bit
     * set and is left to the negative cache.
     */
    @Query(
        "SELECT * FROM track_dna WHERE (tagsAtMs > 0 AND tagsAtMs < :tagsBefore) OR (popAtMs > 0 AND popAtMs < :popBefore) " +
            "OR ((fetchedMask & 19) != 19 AND updatedAtMs < :retryBefore) ORDER BY updatedAtMs ASC LIMIT :limit"
    )
    suspend fun expired(tagsBefore: Long, popBefore: Long, retryBefore: Long, limit: Int): List<TrackDnaEntity>

    @Query("SELECT COUNT(*) FROM track_dna")
    suspend fun count(): Int

    @Query(
        "SELECT COUNT(*) AS tracks, " +
            "COALESCE(SUM(CASE WHEN length(tags) > 0 THEN 1 ELSE 0 END), 0) AS withTags, " +
            "COALESCE(SUM(CASE WHEN firstYear > 0 THEN 1 ELSE 0 END), 0) AS withYear, " +
            "COALESCE(SUM(CASE WHEN popPermille >= 0 THEN 1 ELSE 0 END), 0) AS withPopularity, " +
            "COALESCE(SUM(CASE WHEN energy >= 0 THEN 1 ELSE 0 END), 0) AS withEnergy, " +
            "COALESCE(SUM(CASE WHEN tempoBpmX10 > 0 THEN 1 ELSE 0 END), 0) AS withTempo FROM track_dna"
    )
    suspend fun coverage(): DnaCoverage

    /** How many rows have each source's bit in `fetchedMask`, for the Developer screen. */
    @Query("SELECT COUNT(*) FROM track_dna WHERE (fetchedMask & :bit) != 0")
    suspend fun countFetchedBy(bit: Int): Int

    @Query("DELETE FROM track_dna")
    suspend fun clear()
}

data class DnaFreshness(val rid: Long, val updatedAtMs: Long, val tagsAtMs: Long, val popAtMs: Long, val fetchedMask: Int)

@Dao
interface ArtistDnaDao {
    @Upsert
    suspend fun upsert(row: ArtistDnaEntity)

    @Query("SELECT * FROM artist_dna WHERE aid = :aid")
    suspend fun get(aid: Long): ArtistDnaEntity?

    @Query("SELECT * FROM artist_dna WHERE aid IN (:aids)")
    suspend fun getAll(aids: List<Long>): List<ArtistDnaEntity>

    @Query("SELECT * FROM artist_dna WHERE spotifyId = :spotifyId LIMIT 1")
    suspend fun bySpotifyId(spotifyId: String): ArtistDnaEntity?

    @Query("SELECT COUNT(*) FROM artist_dna")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM artist_dna WHERE length(tags) > 0")
    suspend fun countWithTags(): Int

    @Query("DELETE FROM artist_dna")
    suspend fun clear()
}

@Dao
interface SourceMissDao {
    @Query("SELECT * FROM source_miss WHERE entity = :entity AND source = :source")
    suspend fun get(entity: String, source: Int): SourceMissEntity?

    @Upsert
    suspend fun put(row: SourceMissEntity)

    /** Entities of [entities] that [source] must not be asked about yet. */
    @Query("SELECT entity FROM source_miss WHERE source = :source AND retryAfter > :nowMs AND entity IN (:entities)")
    suspend fun blocked(source: Int, nowMs: Long, entities: List<String>): List<String>

    @Query("DELETE FROM source_miss WHERE entity = :entity AND source = :source")
    suspend fun forget(entity: String, source: Int)

    @Query("SELECT COUNT(*) FROM source_miss WHERE retryAfter > :nowMs")
    suspend fun countActive(nowMs: Long): Int

    @Query("DELETE FROM source_miss WHERE retryAfter < :nowMs - 15552000000")
    suspend fun pruneOld(nowMs: Long)
}
