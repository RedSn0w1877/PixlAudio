package com.theveloper.pixelplay.data.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Ejecuta JavaScript arbitrario en un [WebView] fuera de pantalla.
 *
 * Portado desde el `QQSignGenerator` original (que resolvía firmas ofuscadas de QQ Music)
 * y generalizado: el patrón WebView + `evaluateJavascript` nos evita depender de Rhino o
 * QuickJS (~1 MB) solo para descifrar las firmas de YouTube.
 *
 * La instancia se mantiene caliente entre llamadas — crear un WebView es caro — y un
 * [Mutex] serializa los accesos porque un mismo WebView no admite evaluaciones solapadas.
 */
@Singleton
class JsEvaluator @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val appContext = context.applicationContext
    private val evaluationMutex = Mutex()
    private val released = AtomicBoolean(false)

    @Volatile
    private var webView: WebView? = null

    /**
     * Evalúa [script] y devuelve su resultado como String, o null si falla o expira.
     *
     * Es `suspend` a propósito: el original bloqueaba con `CountDownLatch` y tenía que
     * rechazar llamadas desde el hilo principal. Aquí el salto a Main lo hacemos nosotros,
     * así que se puede llamar desde cualquier dispatcher sin ese cuidado.
     */
    suspend fun evaluate(
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        if (released.get()) {
            Timber.w("JsEvaluator ya fue liberado; se ignora la evaluación")
            return null
        }

        return evaluationMutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val view = ensureWebView()
                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine { continuation ->
                            view.evaluateJavascript(script) { raw ->
                                continuation.resumeIfActive(decodeEvaluateResult(raw))
                            }
                        }
                    }
                }.onFailure { error ->
                    Timber.e(error, "Fallo al evaluar JavaScript")
                }.getOrNull()
            }
        }
    }

    /** Libera el WebView. Llamar cuando el proceso ya no vaya a resolver más streams. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        val view = webView ?: return
        webView = null
        // destroy() exige el hilo principal.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { view.destroy() }
        }
    }

    // ─── Internos ──────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView {
        webView?.let { return it }

        return withContext(Dispatchers.Main.immediate) {
            // Otra corrutina pudo haberlo creado mientras cambiábamos de dispatcher.
            webView?.let { return@withContext it }

            val instance = WebView(appContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        safeBrowsingEnabled = true
                    }
                }
            }

            // base.js de YouTube toca `window`/`document`, así que cargamos el origen real
            // en lugar de about:blank antes de evaluar nada.
            awaitPageLoad(instance, ORIGIN_URL)

            webView = instance
            instance
        }
    }

    private suspend fun awaitPageLoad(view: WebView, url: String) {
        val loaded = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        continuation.resumeIfActive(Unit)
                    }
                }
                view.loadUrl(url)
            }
        }

        if (loaded == null) {
            // No es fatal: el WebView sigue pudiendo evaluar scripts autocontenidos,
            // solo perdemos el contexto del origen.
            Timber.w("Timeout cargando $url en JsEvaluator; se continúa igualmente")
        }
    }

    /**
     * `evaluateJavascript` devuelve JSON, así que un String llega entrecomillado y con
     * escapes. Lo desenvolvemos para que quien llame reciba el valor crudo.
     */
    private fun decodeEvaluateResult(raw: String?): String? {
        if (raw == null || raw == "null" || raw.isBlank()) return null
        return try {
            if (raw.startsWith('"')) JSONArray("[$raw]").getString(0) else raw
        } catch (_: Exception) {
            raw
        }
    }

    private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val PAGE_LOAD_TIMEOUT_MS = 8_000L
        const val ORIGIN_URL = "https://www.youtube.com/"
    }
}
