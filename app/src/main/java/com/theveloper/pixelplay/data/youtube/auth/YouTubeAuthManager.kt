package com.theveloper.pixelplay.data.youtube.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.data.network.youtube.DeviceCodeResponse
import com.theveloper.pixelplay.data.network.youtube.GoogleOAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cuenta de Google/YouTube del usuario, para autenticar las peticiones a la API interna.
 *
 * Usa el flujo OAuth de dispositivo (el de las TVs): la contraseña se teclea en la web de
 * Google, nunca en esta app. A cambio se guarda un token que se adjunta a las peticiones de
 * YouTube. Sin él, YouTube empezó a responder "confirma que no eres un robot" a todo.
 *
 * Las credenciales de cliente son las de la app de YouTube para TV — públicas y usadas por
 * herramientas como yt-dlp; no son un secreto de esta app ni de una cuenta concreta.
 */
@Singleton
class YouTubeAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val oauthService: GoogleOAuthService
) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Timber.e(e, "No se pudo crear EncryptedSharedPreferences de YouTube; usando prefs planas")
        context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
    }

    private val refreshMutex = Mutex()

    private val _isSignedIn = MutableStateFlow(
        prefs.getString(KEY_REFRESH_TOKEN, null) != null || prefs.getString(KEY_COOKIE, null) != null
    )
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    // ─── Sesión por cookie (flujo real de inicio de sesión) ────────────
    //
    // El flujo OAuth de dispositivo de más abajo autentica bien la llamada, pero solo el
    // perfil TVHTML5 acepta ese token — el resto de clientes (ANDROID_MUSIC, IOS...)
    // responden 400 en cuanto llega una cabecera Authorization de tipo Bearer, y TVHTML5
    // obliga a descifrar la firma ejecutando base.js, la parte más frágil de toda la
    // cadena. La cookie de una sesión real de music.youtube.com, en cambio, la aceptan
    // TODOS los clientes con el mismo mecanismo (SAPISIDHASH, el que usa cualquier
    // producto de Google en el navegador) — y con ANDROID_MUSIC/IOS el vídeo llega con
    // URL ya firmada, sin cipher que descifrar. Confirmado contra una app de código
    // abierto que autentica así y sí reproduce.
    private val _cookie = MutableStateFlow(prefs.getString(KEY_COOKIE, null))
    val cookie: String? get() = _cookie.value

    // InnerTube espera este dato atado a la sesión del navegador que puso la cookie —
    // mandar la petición autenticada sin él (o con uno inventado) es otra pieza de por qué
    // "Request contains an invalid argument" salía siempre con la cookie adjunta. Se
    // captura durante el login (ver YouTubeLoginScreen, igual que hace InnerTune) leyendo
    // `window.yt.config_.VISITOR_DATA` de la propia página. El valor de abajo es solo un
    // relleno razonable para el rato entre "hay cookie" y "ya se capturó" — no una cuenta
    // ni una sesión de nadie, es el mismo con el que arranca InnerTune antes de loguearse.
    private var visitorData: String
        get() = prefs.getString(KEY_VISITOR_DATA, null) ?: DEFAULT_VISITOR_DATA
        set(value) { prefs.edit().putString(KEY_VISITOR_DATA, value).apply() }

    fun currentVisitorData(): String = visitorData

    fun saveVisitorData(newVisitorData: String) {
        if (newVisitorData.isNotBlank()) visitorData = newVisitorData
    }

    /** Guarda la cookie de una sesión de music.youtube.com recién iniciada. */
    fun saveCookie(rawCookie: String) {
        prefs.edit().putString(KEY_COOKIE, rawCookie).apply()
        _cookie.value = rawCookie
        _isSignedIn.value = true
    }

    /**
     * Cabecera `Authorization: SAPISIDHASH …` para peticiones autenticadas a InnerTube,
     * calculada a partir de la cookie SAPISID — igual que hace el propio YouTube en el
     * navegador. Null si no hay cookie o no trae SAPISID.
     */
    fun sapisidHashAuthorization(origin: String = "https://music.youtube.com"): String? {
        val raw = _cookie.value ?: return null
        val sapisid = parseCookieString(raw)["SAPISID"] ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val hash = sha1("$timestamp $sapisid $origin")
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    private fun parseCookieString(cookie: String): Map<String, String> =
        cookie.split("; ")
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate {
                val separator = it.indexOf('=')
                it.substring(0, separator) to it.substring(separator + 1)
            }

    private fun sha1(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ─── Inicio de sesión (flujo de dispositivo) ───────────────────────

    sealed interface SignInStep {
        /** Enseña este código y esta URL al usuario mientras se espera. */
        data class AwaitingUser(val userCode: String, val verificationUrl: String) : SignInStep
        object Success : SignInStep
        data class Failed(val reason: String) : SignInStep
    }

    /** Paso 1: pide el código. Devuelve null si Google no contestó. */
    suspend fun requestDeviceCode(): DeviceCodeResponse? = withContext(Dispatchers.IO) {
        try {
            val response = oauthService.requestDeviceCode(CLIENT_ID, SCOPE)
            if (!response.isSuccessful) {
                Timber.w("device/code respondió HTTP ${response.code()}")
                return@withContext null
            }
            response.body()
        } catch (e: Exception) {
            Timber.e(e, "Fallo pidiendo el device code de Google")
            null
        }
    }

    /**
     * Paso 2: sondea hasta que el usuario termina en google.com/device, o hasta que el
     * código caduca. Guarda los tokens si acaba bien.
     */
    suspend fun pollForToken(device: DeviceCodeResponse): SignInStep = withContext(Dispatchers.IO) {
        var intervalMs = (device.interval ?: 5L) * 1000L
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val response = try {
                oauthService.pollToken(CLIENT_ID, CLIENT_SECRET, device.deviceCode)
            } catch (e: Exception) {
                Timber.w(e, "Sondeo de token falló; se reintenta")
                continue
            }
            val body = response.body()
            val accessToken = body?.accessToken
            if (!accessToken.isNullOrBlank()) {
                saveTokens(accessToken, body.refreshToken, body.expiresIn ?: 3600L)
                return@withContext SignInStep.Success
            }
            when (body?.error) {
                "authorization_pending" -> Unit // el usuario aún no ha terminado
                "slow_down" -> intervalMs += 2000L // Google pide bajar el ritmo
                "access_denied" -> return@withContext SignInStep.Failed("Sign-in was denied.")
                "expired_token" -> return@withContext SignInStep.Failed("The code expired. Try again.")
                else -> if (!response.isSuccessful && body?.error != null) {
                    return@withContext SignInStep.Failed(body.error)
                }
            }
        }
        SignInStep.Failed("Timed out waiting for sign-in.")
    }

    // ─── Token de acceso ───────────────────────────────────────────────

    /** Token válido, refrescándolo si toca. Null si no hay sesión o el refresco falló. */
    suspend fun ensureValidAccessToken(): String? = refreshMutex.withLock {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val current = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (!current.isNullOrBlank() && System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS) {
            return@withLock current
        }
        refreshLocked()
    }

    private suspend fun refreshLocked(): String? = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (refreshToken.isNullOrBlank()) return@withContext null
        try {
            val response = oauthService.refreshToken(CLIENT_ID, CLIENT_SECRET, refreshToken)
            val body = response.body()
            val accessToken = body?.accessToken
            if (accessToken.isNullOrBlank()) {
                // invalid_grant → la sesión murió; hay que volver a entrar.
                if (body?.error == "invalid_grant") {
                    Timber.w("Refresh token de YouTube rechazado; cerrando sesión")
                    signOut()
                }
                return@withContext null
            }
            // El flujo de dispositivo no rota el refresh token, pero se persiste por si acaso.
            saveTokens(accessToken, body.refreshToken ?: refreshToken, body.expiresIn ?: 3600L)
            accessToken
        } catch (e: Exception) {
            Timber.e(e, "Fallo al refrescar el token de YouTube")
            null
        }
    }

    /** Cabecera `Authorization` lista, o null si no hay sesión. */
    suspend fun authorizationHeader(): String? =
        ensureValidAccessToken()?.let { "Bearer $it" }

    private fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
        if (!refreshToken.isNullOrBlank()) editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        editor.apply()
        _isSignedIn.value = prefs.getString(KEY_REFRESH_TOKEN, null) != null
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_COOKIE)
            .apply()
        _cookie.value = null
        _isSignedIn.value = false
    }

    companion object {
        // Credenciales del cliente OAuth de la app de YouTube para TV. Públicas.
        private const val CLIENT_ID =
            "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
        private const val SCOPE =
            "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"

        const val OAUTH_BASE_URL = "https://oauth2.googleapis.com/"

        private const val PREFS_NAME = "youtube_auth_prefs"
        private const val PREFS_NAME_FALLBACK = "youtube_auth_prefs_plain"

        private const val KEY_ACCESS_TOKEN = "yt_access_token"
        private const val KEY_REFRESH_TOKEN = "yt_refresh_token"
        private const val KEY_EXPIRES_AT = "yt_token_expires_at"
        private const val KEY_COOKIE = "yt_cookie"
        private const val KEY_VISITOR_DATA = "yt_visitor_data"

        // Relleno inicial hasta que el login capture el real — el mismo con el que arranca
        // InnerTune (referencia de código abierto verificada a mano) antes de loguearse.
        private const val DEFAULT_VISITOR_DATA = "CgtsZG1ySnZiQWtSbyiMjuGSBg%3D%3D"

        private const val EXPIRY_MARGIN_MS = 300_000L
    }
}
