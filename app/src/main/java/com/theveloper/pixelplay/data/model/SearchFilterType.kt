package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class SearchFilterType {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,

    /**
     * Resultados del catálogo de Spotify que no están en la biblioteca. No es un filtro
     * que el usuario pueda elegir: existe para que la lista pueda agruparlos en su propia
     * sección al final, debajo de lo que ya tiene.
     */
    CATALOG,

    /**
     * Resultados de buscar directamente en YouTube Music. Tampoco es un filtro elegible por
     * el usuario, mismo motivo que [CATALOG].
     */
    YOUTUBE_MUSIC
}
