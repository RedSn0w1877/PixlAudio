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
    /** Pedida por el usuario pero el archivo aún no está entero: la descarga sigue en marcha. */
    const val DOWNLOADING = 3
    /**
     * La descarga se rindió tras agotar los reintentos. Sin este estado la fila se queda en
     * `is_permanent = 1, is_complete = 0` para siempre y en pantalla no se distingue de una
     * canción que nunca se pidió: el usuario toca Descargar, falla en silencio y no queda nada
     * que reintentar. El motivo va aparte, en [SongCacheStateCache.failureReasons].
     */
    const val FAILED = 4
}

data class SongIdAndCacheFlags(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "is_permanent") val isPermanent: Boolean,
    @ColumnInfo(name = "is_complete") val isComplete: Boolean
)
