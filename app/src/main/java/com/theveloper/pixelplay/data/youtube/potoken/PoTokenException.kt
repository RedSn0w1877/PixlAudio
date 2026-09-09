package com.theveloper.pixelplay.data.youtube.potoken

class PoTokenException(message: String) : Exception(message)

/** El WebView del sistema no soporta el JavaScript que hace falta. */
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)
