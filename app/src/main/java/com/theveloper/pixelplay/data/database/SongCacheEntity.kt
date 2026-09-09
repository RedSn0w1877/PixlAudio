package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Estado de una copia local de audio, por `spotifyId`.
 *
 * Dos formas de llegar aquí, misma tabla:
 * - `isPermanent = false`: caché automática — se llenó sola la primera vez que sonó la
 *   canción, y [com.theveloper.pixelplay.data.cache.AudioCacheManager] puede borrarla sin
 *   avisar cuando el hueco total se llena (ver `evictIfNeeded`).
 * - `isPermanent = true`: descarga explícita del usuario — nunca se borra automáticamente.
 */
@Entity(tableName = "song_cache")
data class SongCacheEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "is_permanent") val isPermanent: Boolean,
    @ColumnInfo(name = "is_complete", defaultValue = "0") val isComplete: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long
)

/** Valores que puede tomar el indicador por canción (ver `SongCacheStateCache`). */
object SongCacheState {
    /** Copia temporal: se llenó sola al escucharla, puede desaparecer si hace falta hueco. */
    const val CACHED = 1
    /** Descarga explícita del usuario: no se borra sola. */
    const val DOWNLOADED = 2
}

data class SongIdAndCacheFlags(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "is_permanent") val isPermanent: Boolean
)
