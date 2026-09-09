package com.theveloper.pixelplay.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de la copia local de cada canción, por `spotifyId`. Mismo patrón que
 * [com.theveloper.pixelplay.data.spotify.SpotifyMatchStateCache]: objeto de proceso, lo rellena
 * [AudioCacheManager] al construirse, y cualquier fila de canción lo lee sin necesitar
 * inyección de Hilt.
 */
object SongCacheStateCache {

    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    fun update(map: Map<String, Int>) {
        _states.value = map
    }
}
