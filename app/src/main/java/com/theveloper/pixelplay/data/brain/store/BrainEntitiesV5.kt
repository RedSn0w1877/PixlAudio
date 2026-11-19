package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Graph embeddings (docs/brain-spec.md, 5.3): one Int8[64] vector per node of the similarity graph, found by
 * FastRP. cache: the vectors are recomputed from `graph_edge` and the track-artist edges whenever the graph
 * has grown by more than 5% and at least weekly; nothing here is the listener's history. The spec keeps the
 * vector in `track_dna.emb`; a node can also be an artist, or a track with no DNA row, so the vectors live
 * here (deviation 152).
 */
@Entity(tableName = "node_embedding")
data class NodeEmbeddingEntity(
    @PrimaryKey val nodeId: Long,
    val embVersion: Int,
    /** 64 signed bytes. */
    val vec: ByteArray,
)

/**
 * A taste island (5.4): one side of the listener's taste. cache: refit weekly or after 200 qualified plays; the
 * `islandId` is stable across refits (the matching of `Islands.matchIds`), so "Daily Mix 2" stays the same island.
 */
@Entity(tableName = "island")
data class IslandEntity(
    @PrimaryKey val islandId: Int,
    /** 96 doubles, 8 bytes each, big endian bit patterns. */
    val centroid: ByteArray,
    val medoidRid: Long,
    /** Up to 5 atlas ids, 4 bytes each, by weighted share. */
    val topTags: ByteArray,
    /** Up to 5 atlas ids, 4 bytes each, by lift over the listener's average (the island's name). */
    val liftTags: ByteArray,
    /** Up to 3 artist ids, 8 bytes each. */
    val topArtists: ByteArray,
    /** `W_c` and the 21-day weight, as Q20 integers. */
    val weightQ: Long,
    val recentWeightQ: Long,
    val size: Int,
    val fitAtMs: Long,
)

/** Which island each placed track belongs to, by recording id. cache. */
@Entity(tableName = "island_member", indices = [Index("islandId")])
data class IslandMemberEntity(
    @PrimaryKey val rid: Long,
    val islandId: Int,
)

@Dao
interface EmbeddingDao {
    @Upsert
    suspend fun upsertAll(rows: List<NodeEmbeddingEntity>)

    @Query("SELECT * FROM node_embedding")
    suspend fun all(): List<NodeEmbeddingEntity>

    @Query("SELECT * FROM node_embedding WHERE nodeId IN (:ids)")
    suspend fun get(ids: List<Long>): List<NodeEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM node_embedding")
    suspend fun count(): Int

    @Query("DELETE FROM node_embedding")
    suspend fun clear()
}

@Dao
interface IslandDao {
    @Upsert
    suspend fun upsertIslands(rows: List<IslandEntity>)

    @Upsert
    suspend fun upsertMembers(rows: List<IslandMemberEntity>)

    @Query("SELECT * FROM island ORDER BY islandId")
    suspend fun islands(): List<IslandEntity>

    @Query("SELECT * FROM island_member")
    suspend fun members(): List<IslandMemberEntity>

    @Query("SELECT * FROM island_member WHERE rid IN (:rids)")
    suspend fun membersOf(rids: List<Long>): List<IslandMemberEntity>

    @Query("SELECT COUNT(*) FROM island")
    suspend fun count(): Int

    @Query("DELETE FROM island")
    suspend fun clearIslands()

    @Query("DELETE FROM island_member")
    suspend fun clearMembers()
}
