package com.theveloper.pixelplay.data.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de emparejamiento de cada pista de Spotify, por `spotifyId`.
 *
 * Antes, si una canción no sonaba, la única pista visible era el contador total del panel
 * de Spotify ("143 listas, 132 buscando, 5 sin encontrar") — no había forma de saber, mirando
 * la lista, CUÁL de esas 280 canciones era la que fallaba. Esto lo expone fila por fila.
 *
 * Objeto de proceso, como [com.theveloper.pixelplay.data.service.PlaybackErrorReporter]:
 * [SpotifyRepository] lo rellena al arrancar, y cualquier fila de canción lo lee sin
 * necesitar inyección de Hilt.
 */
object SpotifyMatchStateCache {

    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    fun update(map: Map<String, Int>) {
        _states.value = map
    }
}
