package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongCacheDao {

    @Query("SELECT * FROM song_cache WHERE song_id = :songId LIMIT 1")
    suspend fun get(songId: String): SongCacheEntity?

    @Query("SELECT * FROM song_cache")
    suspend fun getAllEntries(): List<SongCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SongCacheEntity)

    @Query("UPDATE song_cache SET last_accessed_at = :timestamp WHERE song_id = :songId")
    suspend fun touch(songId: String, timestamp: Long)

    @Query("DELETE FROM song_cache WHERE song_id = :songId")
    suspend fun delete(songId: String)

    /**
     * Para el indicador por canción. Incluye a propósito las filas incompletas: una descarga
     * pedida por el usuario existe como `is_permanent = 1, is_complete = 0` mientras baja, y
     * filtrarlas aquí es lo que hacía que "descargando" y "falló" fueran invisibles en pantalla.
     * Quien lo consume decide el estado final (ver [SongCacheState]).
     */
    @Query("SELECT song_id, is_permanent, is_complete FROM song_cache")
    fun getAllCacheStatesFlow(): Flow<List<SongIdAndCacheFlags>>

    /** Candidatas a desalojo cuando la caché automática se pasa del cupo: las más viejas primero. */
    @Query(
        "SELECT * FROM song_cache WHERE is_permanent = 0 AND is_complete = 1 ORDER BY last_accessed_at ASC"
    )
    suspend fun getAutoCacheEntriesOldestFirst(): List<SongCacheEntity>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM song_cache WHERE is_permanent = 0 AND is_complete = 1")
    suspend fun getAutoCacheTotalBytes(): Long

    /** Lo que ocupan las descargas explícitas, que nunca se desalojan solas. */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM song_cache WHERE is_permanent = 1 AND is_complete = 1")
    suspend fun getDownloadsTotalBytes(): Long

    @Query("SELECT COUNT(*) FROM song_cache WHERE is_permanent = 1 AND is_complete = 1")
    suspend fun getDownloadsCount(): Int
}
