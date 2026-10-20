package com.theveloper.pixelplay.data.youtube.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.time.Instant

/**
 * Genera `poToken`s ejecutando el propio BotGuard de Google dentro de un WebView.
 *
 * Portado de la app oficial de NewPipe (`util/potoken/PoTokenWebView.kt`), que usa RxJava;
 * aquí se ha rehecho con corrutinas para no meter una dependencia nueva solo por esto. La
 * lógica es la misma: cargar `po_token.html` (un intérprete de BotGuard embebido), pedirle a
 * YouTube el "programa" a ejecutar, correrlo dentro del WebView, y usar el resultado para
 * fabricar un token por cada vídeo que se pida.
 *
 * Por qué hace falta: sin esto, `getAudioStreams()` de NewPipeExtractor puede devolver una
 * lista vacía — YouTube exige esta prueba de que el cliente es un navegador real antes de
 * entregar el audio a algunos formatos.
 */
class PoTokenWebView private constructor(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val webView = WebView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initDeferred = CompletableDeferred<Unit>()
    private val poTokenDeferreds = mutableMapOf<String, CompletableDeferred<String>>()
    private lateinit var expirationInstant: Instant

    init {
        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
        }
        settings.userAgentString = USER_AGENT
        // El WebView no necesita acceso a internet: solo ejecuta el JS que ya se le pasó.
        settings.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.message().contains("Uncaught")) {
                    // No debería haber errores sin capturar: todo lo que puede fallar va en
                    // try-catch. Que aparezca uno significa que este WebView del sistema
                    // solo soporta un JavaScript demasiado antiguo.
                    val detail = "\"${message.message()}\", source: ${message.sourceId()} (${message.lineNumber()})"
                    Timber.e("PoTokenWebView roto: $detail")
                    onInitializationError(BadWebViewException(detail))
                }
                return super.onConsoleMessage(message)
            }
        }
    }

    private suspend fun loadHtmlAndObtainBotguard(context: Context) {
        val html = withContext(Dispatchers.IO) {
            context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        }
        withContext(Dispatchers.Main) {
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                html.replaceFirst(
                    "</script>",
                    "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                ),
                "text/html",
                "utf-8",
                null
            )
        }
    }

    /** Llamado desde el JS de `po_token.html` en cuanto termina de cargar la página. */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        scope.launch {
            try {
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]"
                )
                val parsedChallengeData = parseChallengeData(responseBody)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        """try {
                            data = $parsedChallengeData
                            runBotGuard(data).then(function (result) {
                                this.webPoSignalOutput = result.webPoSignalOutput
                                $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                            }, function (error) {
                                $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                            })
                        } catch (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        }""",
                        null
                    )
                }
            } catch (e: Exception) {
                onInitializationError(e)
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Timber.e("Error de inicialización desde JS: $error")
        onInitializationError(buildExceptionForJsError(error))
    }

    /** Llamado desde el JS con la salida de `runBotGuard`; falta un último paso con YouTube. */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        scope.launch {
            try {
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
                )
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                // 10 minutos de margen para no servir nunca un token a punto de caducar.
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                        initDeferred.complete(Unit)
                    }
                }
            } catch (e: Exception) {
                onInitializationError(e)
            }
        }
    }

    /** Pide un poToken para [identifier] (el videoId, o el visitorData para el de streaming). */
    fun generatePoToken(identifier: String): String = runBlocking {
        val deferred = CompletableDeferred<String>()
        synchronized(poTokenDeferreds) { poTokenDeferreds[identifier] = deferred }

        withContext(Dispatchers.Main) {
            val u8Identifier = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }"""
            ) {}
        }
        deferred.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        popPoTokenDeferred(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val deferred = popPoTokenDeferred(identifier) ?: return
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            return
        }
        deferred.complete(poToken)
    }

    fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)

    private fun popPoTokenDeferred(identifier: String): CompletableDeferred<String>? =
        synchronized(poTokenDeferreds) { poTokenDeferreds.remove(identifier) }

    private fun popAllPoTokenDeferreds(): List<CompletableDeferred<String>> =
        synchronized(poTokenDeferreds) {
            val result = poTokenDeferreds.values.toList()
            poTokenDeferreds.clear()
            result
        }

    private suspend fun makeBotguardServiceRequest(url: String, data: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(data.toRequestBody("application/json+protobuf".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw PoTokenException("Invalid response code: ${response.code}")
                }
                response.body.string()
            }
        }

    private fun onInitializationError(error: Throwable) {
        scope.launch(Dispatchers.Main) {
            closeInternal()
            initDeferred.completeExceptionally(error)
            popAllPoTokenDeferreds().forEach { it.completeExceptionally(error) }
        }
    }

    fun close() {
        runBlocking(Dispatchers.Main) { closeInternal() }
    }

    private fun closeInternal() {
        scope.cancel()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        // Clave pública de la API de BotGuard — se ve en cualquier petición al reproductor
        // web de YouTube, no es un secreto de esta app.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        /** Crea e inicializa un generador. Bloquea el hilo llamante hasta terminar. */
        fun newPoTokenGenerator(context: Context, okHttpClient: OkHttpClient): PoTokenWebView =
            runBlocking(Dispatchers.Main) {
                val instance = PoTokenWebView(context, okHttpClient)
                instance.loadHtmlAndObtainBotguard(context)
                instance.initDeferred.await()
                instance
            }
    }
}
