package com.theveloper.pixelplay.presentation.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface YouTubeAuthManagerEntryPoint {
    fun youTubeAuthManager(): YouTubeAuthManager
}

/**
 * Pantalla de inicio de sesión de YouTube: un WebView cargando el login real de Google.
 *
 * A diferencia del flujo OAuth de dispositivo (un código que se teclea en otra pantalla),
 * esto captura la cookie de la sesión real de music.youtube.com una vez el usuario termina
 * de iniciar sesión — es lo que de verdad acepta InnerTube en cualquier cliente (ver
 * YouTubeAuthManager.sapisidHashAuthorization), a diferencia del token OAuth, que solo
 * servía para un perfil y encima no evitaba tener que descifrar la firma del vídeo.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val authManager = remember {
        EntryPointAccessors.fromApplication(context, YouTubeAuthManagerEntryPoint::class.java)
            .youTubeAuthManager()
    }

    var webView: WebView? = null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect YouTube") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { _ ->
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            if (!url.startsWith("https://music.youtube.com")) return
                            val cookie = CookieManager.getInstance().getCookie(url)
                            if (cookie.isNullOrBlank()) return
                            // La primera vez que la navegación toca music.youtube.com puede
                            // ser un salto intermedio (comprobación de seguridad, "¿eres
                            // tú?"...) antes de que Google termine de montar la sesión — una
                            // cookie capturada ahí no trae SAPISID todavía, y esa es
                            // justamente la que hace falta para firmar las peticiones
                            // (ver YouTubeAuthManager.sapisidHashAuthorization). Se espera a
                            // que sí la traiga en vez de aceptar la primera que llegue.
                            if (!cookie.contains("SAPISID=")) {
                                Timber.d("YouTubeLoginScreen: cookie en $url todavía sin SAPISID, se sigue esperando")
                                return
                            }
                            Timber.d("YouTubeLoginScreen: sesión completa capturada (${cookie.length} chars)")
                            authManager.saveCookie(cookie)
                            onBackClick()
                        }

                        // InnerTube espera un visitorData atado a la propia sesión del
                        // navegador que puso la cookie (ver YouTubeAuthManager) — sin él
                        // las peticiones autenticadas volvían con "Request contains an
                        // invalid argument" pese a tener cookie y origen correctos.
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (!newVisitorData.isNullOrBlank()) {
                                authManager.saveVisitorData(newVisitorData)
                            }
                        }
                    }, "Android")
                    webView = this
                    loadUrl(
                        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
                            "&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin" +
                            "%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
                    )
                }
            }
        )
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
