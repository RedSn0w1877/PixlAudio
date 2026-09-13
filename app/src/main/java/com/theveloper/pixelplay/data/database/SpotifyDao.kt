package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyDao {

    // ─── Playlists ─────────────────────────────────────────────────────

    @Query("SELECT * FROM spotify_playlists ORDER BY name COLLATE NOCASE ASC")
    fun getAllPlaylists(): Flow<List<SpotifyPlaylistEntity>>

    @Query("SELECT * FROM spotify_playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllPlaylistsList(): List<SpotifyPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: SpotifyPlaylistEntity)

    @Query("DELETE FROM spotify_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM spotify_playlists")
    suspend fun clearAllPlaylists()

    // ─── Canciones ─────────────────────────────────────────────────────

    @Query("SELECT * FROM spotify_songs WHERE playlist_id = :playlistId")
    fun getSongsByPlaylist(playlistId: String): Flow<List<SpotifySongEntity>>

    @Query("SELECT * FROM spotify_songs")
    fun getAllSpotifySongs(): Flow<List<SpotifySongEntity>>

    @Query("SELECT * FROM spotify_songs")
    suspend fun getAllSpotifySongsList(): List<SpotifySongEntity>

    /**
     * Una fila por pista, sin importar en cuántas playlists aparezca. La biblioteca
     * unificada no debe duplicar la misma canción por estar en varias listas.
     */
    @Query("SELECT * FROM spotify_songs GROUP BY spotify_id")
    suspend fun getDistinctSpotifySongsList(): List<SpotifySongEntity>

    @Query("SELECT * FROM spotify_songs WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun getSongBySpotifyId(spotifyId: String): SpotifySongEntity?

    /**
     * Resuelve varias pistas de golpe. `GROUP BY` porque la misma pista puede estar en
     * varias playlists y aquí solo interesa la canción, no dónde vive.
     */
    @Query("SELECT * FROM spotify_songs WHERE spotify_id IN (:spotifyIds) GROUP BY spotify_id")
    suspend fun getSongsBySpotifyIds(spotifyIds: List<String>): List<SpotifySongEntity>

    /** Solo los ids, para comprobar pertenencia sin traerse la tabla entera a memoria. */
    @Query("SELECT DISTINCT spotify_id FROM spotify_songs")
    suspend fun getAllSpotifyIds(): List<String>

    @Query("SELECT * FROM spotify_songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun searchSongs(query: String): Flow<List<SpotifySongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SpotifySongEntity>)

    /** Publish a fully fetched playlist atomically; cancellation cannot expose an empty gap. */
    @Transaction
    suspend fun replaceSongsForPlaylist(playlistId: String, songs: List<SpotifySongEntity>) {
        deleteSongsByPlaylist(playlistId)
        if (songs.isNotEmpty()) insertSongs(songs)
    }

    @Query("DELETE FROM spotify_songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsByPlaylist(playlistId: String)

    @Query("DELETE FROM spotify_songs")
    suspend fun clearAllSongs()

    /** En qué playlists aparece esta pista, para saber si es sólo del catálogo explorado. */
    @Query("SELECT DISTINCT playlist_id FROM spotify_songs WHERE spotify_id = :spotifyId")
    suspend fun getPlaylistIdsForSong(spotifyId: String): List<String>

    /** Borra únicamente las filas de esta pista en esa playlist concreta. */
    @Query("DELETE FROM spotify_songs WHERE spotify_id = :spotifyId AND playlist_id = :playlistId")
    suspend fun deleteSongFromPlaylist(spotifyId: String, playlistId: String)

    // ─── Emparejamiento con YouTube ────────────────────────────────────

    /**
     * Pistas que aún hay que buscar en YouTube. Excluye las marcadas a mano
     * ([SpotifyMatchState.MANUAL]) para no pisar la elección del usuario.
     */
    @Query(
        """
        SELECT * FROM spotify_songs
        WHERE match_state = :pendingState
        GROUP BY spotify_id
        LIMIT :limit
        """
    )
    suspend fun getUnmatchedSongs(
        limit: Int,
        pendingState: Int = SpotifyMatchState.PENDING
    ): List<SpotifySongEntity>

    /** Keyset paging lets later songs progress even when an early page has network failures. */
    @Query("""
        SELECT * FROM spotify_songs
        WHERE match_state = :pendingState AND spotify_id > :afterSpotifyId
        GROUP BY spotify_id ORDER BY spotify_id ASC LIMIT :limit
    """)
    suspend fun getUnmatchedSongsAfter(
        afterSpotifyId: String,
        limit: Int,
        pendingState: Int = SpotifyMatchState.PENDING
    ): List<SpotifySongEntity>

    /** A slow background lookup must never replace a manual or on-demand match. */
    @Query("""
        UPDATE spotify_songs
        SET matched_video_id = :videoId, match_score = :score, match_state = :state
        WHERE spotify_id = :spotifyId AND match_state != :manualState
          AND (matched_video_id IS NULL OR matched_video_id = '')
    """)
    suspend fun updateAutomaticMatch(
        spotifyId: String, videoId: String?, score: Float?, state: Int,
        manualState: Int = SpotifyMatchState.MANUAL
    )

    /** El emparejamiento es por pista, así que actualiza todas sus filas a la vez. */
    @Query(
        """
        UPDATE spotify_songs
        SET matched_video_id = :videoId, match_score = :score, match_state = :state
        WHERE spotify_id = :spotifyId
        """
    )
    suspend fun updateMatch(spotifyId: String, videoId: String?, score: Float?, state: Int)

    @Query("SELECT matched_video_id FROM spotify_songs WHERE spotify_id = :spotifyId AND matched_video_id IS NOT NULL LIMIT 1")
    suspend fun getMatchedVideoId(spotifyId: String): String?

    /**
     * Devuelve a la cola las pistas que se dieron por perdidas.
     *
     * Sin esto, un fallo de red o un cambio en la API de YouTube marcaba la pista como
     * [SpotifyMatchState.UNMATCHED] para siempre: [getUnmatchedSongs] solo mira las
     * PENDING, así que arreglar la causa no servía de nada. No toca las MANUAL.
     */
    @Query(
        """
        UPDATE spotify_songs
        SET match_state = :pendingState, matched_video_id = NULL, match_score = NULL
        WHERE match_state = :unmatchedState
        """
    )
    suspend fun requeueUnmatchedSongs(
        pendingState: Int = SpotifyMatchState.PENDING,
        unmatchedState: Int = SpotifyMatchState.UNMATCHED
    ): Int

    /** Vuelve a emparejar absolutamente todo salvo lo que el usuario eligió a mano. */
    @Query(
        """
        UPDATE spotify_songs
        SET match_state = :pendingState, matched_video_id = NULL, match_score = NULL
        WHERE match_state != :manualState
        """
    )
    suspend fun requeueAllSongs(
        pendingState: Int = SpotifyMatchState.PENDING,
        manualState: Int = SpotifyMatchState.MANUAL
    ): Int

    /** Una pista cualquiera para la prueba de diagnóstico. */
    @Query("SELECT * FROM spotify_songs GROUP BY spotify_id LIMIT 1")
    suspend fun getAnySong(): SpotifySongEntity?

    @Query("SELECT COUNT(DISTINCT spotify_id) FROM spotify_songs WHERE match_state = :state")
    suspend fun countByMatchState(state: Int): Int

    /**
     * Un estado por pista, para pintar un indicador junto a cada canción en la biblioteca.
     * Sin esto, el único sitio donde se ve si una pista está lista es el contador total del
     * panel de Spotify — nada dice, canción por canción, cuál va a sonar y cuál no.
     */
    @Query("SELECT spotify_id, match_state FROM spotify_songs GROUP BY spotify_id")
    fun getAllMatchStatesFlow(): Flow<List<SpotifyIdAndMatchState>>

    /**
     * El resultado del emparejamiento de cada pista, para poder conservarlo al reimportar.
     *
     * Reimportar una playlist borra sus filas y las vuelve a insertar; sin esto, cada
     * sincronización tiraba a la basura todos los vídeos ya encontrados y dejaba la
     * biblioteca entera en PENDING otra vez — horas de búsqueda perdidas por pulsar
     * "sincronizar".
     */
    @Query(
        """
        SELECT spotify_id, matched_video_id, match_score, match_state
        FROM spotify_songs
        WHERE match_state != :pendingState
        GROUP BY spotify_id
        """
    )
    suspend fun getKnownMatches(
        pendingState: Int = SpotifyMatchState.PENDING
    ): List<SpotifyMatchRow>
}

data class SpotifyIdAndMatchState(
    @androidx.room.ColumnInfo(name = "spotify_id") val spotifyId: String,
    @androidx.room.ColumnInfo(name = "match_state") val matchState: Int
)

data class SpotifyMatchRow(
    @androidx.room.ColumnInfo(name = "spotify_id") val spotifyId: String,
    @androidx.room.ColumnInfo(name = "matched_video_id") val matchedVideoId: String?,
    @androidx.room.ColumnInfo(name = "match_score") val matchScore: Float?,
    @androidx.room.ColumnInfo(name = "match_state") val matchState: Int
)
