package com.theveloper.pixelplay.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Porcentaje 0..100 de las descargas en curso, por `spotifyId`.
 *
 * Vive en memoria a propósito, al contrario que el estado y el motivo de fallo (que sí se
 * persisten): un porcentaje solo tiene sentido mientras la descarga está viva. Si el proceso
 * muere a mitad, la descarga tampoco sobrevive, así que restaurar un "47%" al arrancar sería
 * mentir sobre algo que ya no está pasando.
 *
 * Mismo patrón de objeto de proceso que [SongCacheStateCache], para que una fila de canción
 * pueda leerlo sin inyección de Hilt.
 */
object DownloadProgressCache {

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    fun update(songId: String, percent: Int) {
        _progress.value = _progress.value + (songId to percent.coerceIn(0, 100))
    }

    /** Al terminar (bien o mal): sin descarga viva no hay porcentaje que enseñar. */
    fun clear(songId: String) {
        if (!_progress.value.containsKey(songId)) return
        _progress.value = _progress.value - songId
    }
}
