package com.theveloper.pixelplay.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guarda el último fallo de reproducción con detalle suficiente para diagnosticarlo.
 *
 * `PlaybackException.message` solo dice "Source error": es la categoría, no la causa. Lo
 * que hace falta es la excepción encadenada debajo — no es lo mismo un 403 del servidor
 * que un formato que ExoPlayer no reconoce, y desde fuera los dos se ven idénticos.
 *
 * Es un objeto de proceso, como [PlaybackActivityTracker]: el servicio escribe y la
 * pantalla de Spotify lee, sin necesidad de inyectar nada en el reproductor.
 */
object PlaybackErrorReporter {

    private val _lastError = MutableStateFlow<PlaybackErrorInfo?>(null)
    val lastError: StateFlow<PlaybackErrorInfo?> = _lastError.asStateFlow()

    fun report(info: PlaybackErrorInfo) {
        _lastError.value = info
    }

    fun clear() {
        _lastError.value = null
    }
}

data class PlaybackErrorInfo(
    val trackTitle: String,
    /** Nombre del código de ExoPlayer, p. ej. `ERROR_CODE_IO_BAD_HTTP_STATUS`. */
    val errorCodeName: String,
    /** Cadena de causas, de fuera adentro. La última suele ser la que importa. */
    val causeChain: String,
    /** Esquema y host de lo que se intentó abrir; delata una URI sin resolver. */
    val failingUri: String?
) {
    fun asPlainText(): String = buildString {
        appendLine(trackTitle)
        appendLine(errorCodeName)
        if (failingUri != null) appendLine("URI: $failingUri")
        append(causeChain)
    }
}
