package com.theveloper.pixelplay.data.spotify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.network.spotify.SpotifyAuthApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Login de Spotify por OAuth 2.0 con PKCE.
 *
 * PKCE es el flujo documentado para apps móviles: no necesita client secret, así que no
 * hay ningún dato sensible embebido en el APK. El precio es que Spotify rota el refresh
 * token en cada refresco — ver [saveTokens].
 *
 * No se usa un WebView: la página de login de Spotify bloquea webviews embebidos, así que
 * el navegador se abre con Custom Tabs (con fallback a un `ACTION_VIEW` normal).
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authApi: SpotifyAuthApiService
) {

    // EncryptedSharedPreferences.create puede fallar en dispositivos con el keystore
    // corrupto. Perder la sesión es mejor que reventar al arrancar.
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
        Timber.e(e, "No se pudo crear EncryptedSharedPreferences de Spotify; usando prefs planas")
        context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
    }

    private val refreshMutex = Mutex()

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_REFRESH_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ─── Client ID ─────────────────────────────────────────────────────

    /**
     * El client ID incluido en la compilación, o el que haya pegado el usuario en Ajustes.
     * Una app de Spotify en modo desarrollo solo admite 25 cuentas en su lista, así que
     * quien tenga la suya propia nunca choca con ese límite.
     */
    fun clientId(): String {
        val override = prefs.getString(KEY_CLIENT_ID_OVERRIDE, null)?.trim()
        if (!override.isNullOrEmpty()) return override
        return BuildConfig.SPOTIFY_CLIENT_ID
    }

    fun hasClientId(): Boolean = clientId().isNotBlank()

    fun clientIdOverride(): String = prefs.getString(KEY_CLIENT_ID_OVERRIDE, null).orEmpty()

    fun setClientIdOverride(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_CLIENT_ID_OVERRIDE) else putString(KEY_CLIENT_ID_OVERRIDE, value.trim())
        }.apply()
    }

    // ─── Inicio de sesión ──────────────────────────────────────────────

    /**
     * Genera un `code_verifier` nuevo, lo guarda y devuelve la URL de autorización.
     * Devuelve null si no hay client ID configurado.
     */
    fun buildAuthorizationUri(): Uri? {
        val clientId = clientId()
        if (clientId.isBlank()) {
            _lastError.value = "Falta el client ID de Spotify"
            return null
        }

        val verifier = generateCodeVerifier()
        val state = generateCodeVerifier().take(24)
        prefs.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_AUTH_STATE, state)
            .apply()

        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", deriveCodeChallenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            // Without this, Spotify silently skips the consent screen for a user who already
            // approved this client_id once — and re-grants whatever scope set was approved
            // THEN, not the one requested now. That's why adding a scope to SCOPES (e.g.
            // playlist-read-private) never took effect for already-linked accounts even after
            // logging out and back in: the authorize call never re-prompted, so the token kept
            // coming back with the old, narrower grant. Forcing the dialog every time is the only
            // way a re-login actually re-requests the current scope list.
            .appendQueryParameter("show_dialog", "true")
            .build()
    }

    /** Abre el navegador en la página de login. Devuelve false si no se pudo. */
    fun launchAuthorization(activityContext: Context): Boolean {
        val uri = buildAuthorizationUri() ?: return false
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activityContext, uri)
            true
        } catch (e: Exception) {
            Timber.w(e, "Custom Tabs no disponible; abriendo navegador externo")
            try {
                activityContext.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e2: Exception) {
                Timber.e(e2, "No hay ningún navegador para completar el login de Spotify")
                _lastError.value = "No se encontró un navegador para iniciar sesión"
                false
            }
        }
    }

    fun isCallbackUri(uri: Uri?): Boolean =
        uri != null && uri.scheme == REDIRECT_SCHEME && uri.host == REDIRECT_HOST

    /**
     * Procesa la vuelta del navegador: valida el `state`, canjea el código por tokens
     * y los guarda.
     */
    suspend fun handleAuthorizationResponse(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            _lastError.value = error
            return@withContext Result.failure(IllegalStateException("Spotify devolvió: $error"))
        }

        val expectedState = prefs.getString(KEY_AUTH_STATE, null)
        val receivedState = uri.getQueryParameter("state")
        if (expectedState == null || expectedState != receivedState) {
            _lastError.value = "Respuesta de login no válida"
            return@withContext Result.failure(IllegalStateException("state no coincide"))
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            _lastError.value = "Spotify no devolvió ningún código"
            return@withContext Result.failure(IllegalStateException("sin código de autorización"))
        }

        val verifier = prefs.getString(KEY_CODE_VERIFIER, null)
            ?: return@withContext Result.failure(IllegalStateException("sin code_verifier guardado"))

        return@withContext try {
            val response = authApi.exchangeCode(
                code = code,
                redirectUri = REDIRECT_URI,
                clientId = clientId(),
                codeVerifier = verifier
            )
            val body = response.body()
            if (!response.isSuccessful || body?.accessToken.isNullOrBlank()) {
                val message = "Canje de código fallido (HTTP ${response.code()})"
                _lastError.value = message
                Result.failure(IllegalStateException(message))
            } else {
                saveTokens(body!!.accessToken!!, body.refreshToken, body.expiresIn ?: 3600L)
                prefs.edit().remove(KEY_CODE_VERIFIER).remove(KEY_AUTH_STATE).apply()
                _lastError.value = null
                _isLoggedIn.value = true
                // Diagnostic only: Spotify's token response echoes back the scope it actually
                // granted, which is the one source of truth for why playlist reads keep 403ing —
                // logging it (not the token itself) tells us whether the requested scopes were
                // actually approved or silently narrowed.
                Timber.i("Spotify token exchange granted scope: \"${body.scope}\"")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Timber.e(e, "Fallo al canjear el código de Spotify")
            _lastError.value = e.message
            Result.failure(e)
        }
    }

    // ─── Tokens ────────────────────────────────────────────────────────

    /**
     * Cabecera `Authorization` lista para usar, refrescando el token si toca.
     * Devuelve null si no hay sesión.
     */
    suspend fun authorizationHeader(): String? {
        val token = ensureValidToken() ?: return null
        return "Bearer $token"
    }

    suspend fun ensureValidToken(): String? = refreshMutex.withLock {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val current = prefs.getString(KEY_ACCESS_TOKEN, null)
        // Se refresca 5 minutos antes de caducar para que una sync larga no se corte a medias.
        if (!current.isNullOrBlank() && System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS) {
            return@withLock current
        }
        refreshAccessTokenLocked().getOrNull()
    }

    suspend fun forceRefresh(): Result<String> = refreshMutex.withLock { refreshAccessTokenLocked() }

    private suspend fun refreshAccessTokenLocked(): Result<String> = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (refreshToken.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("sin refresh token"))
        }
        try {
            val response = authApi.refreshToken(
                refreshToken = refreshToken,
                clientId = clientId()
            )
            val body = response.body()
            val accessToken = body?.accessToken
            if (!response.isSuccessful || accessToken.isNullOrBlank()) {
                // 400 con `invalid_grant` significa que el refresh token ya no vale:
                // la sesión está muerta y hay que volver a iniciarla.
                if (response.code() == 400) {
                    Timber.w("Refresh token de Spotify rechazado; cerrando sesión")
                    clearSession()
                }
                val message = "Refresco de token fallido (HTTP ${response.code()})"
                _lastError.value = message
                return@withContext Result.failure(IllegalStateException(message))
            }
            saveTokens(accessToken, body.refreshToken, body.expiresIn ?: 3600L)
            // Same diagnostic as the initial code exchange: a refresh_token grant carries
            // whatever scope the refresh token was originally issued with — it cannot pick up
            // scopes added after the fact, so this confirms whether the *current* refresh token
            // (not just the latest login) actually carries the playlist scopes.
            Timber.i("Spotify token refresh carries scope: \"${body.scope}\"")
            Result.success(accessToken)
        } catch (e: Exception) {
            Timber.e(e, "Fallo al refrescar el token de Spotify")
            _lastError.value = e.message
            Result.failure(e)
        }
    }

    /**
     * Guarda los tokens.
     *
     * Bajo PKCE, Spotify devuelve un refresh token nuevo en **cada** refresco y anula el
     * anterior. Por eso [refreshToken] se persiste siempre que venga: quedarse con el
     * primero rompe la cuenta en cuanto rota.
     */
    private fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
        _isLoggedIn.value = prefs.getString(KEY_REFRESH_TOKEN, null) != null
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_CODE_VERIFIER)
            .remove(KEY_AUTH_STATE)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
        _isLoggedIn.value = false
    }

    // ─── Perfil en caché ───────────────────────────────────────────────

    fun cacheAccount(displayName: String?, email: String?) {
        prefs.edit()
            .putString(KEY_ACCOUNT_NAME, displayName.orEmpty())
            .putString(KEY_ACCOUNT_EMAIL, email.orEmpty())
            .apply()
    }

    fun accountName(): String? = prefs.getString(KEY_ACCOUNT_NAME, null)?.ifBlank { null }

    fun accountEmail(): String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.ifBlank { null }

    companion object {
        const val REDIRECT_SCHEME = "pixelplay"
        const val REDIRECT_HOST = "spotify-callback"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"

        private const val AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
        const val ACCOUNTS_BASE_URL = "https://accounts.spotify.com/"
        const val API_BASE_URL = "https://api.spotify.com/"

        /**
         * `user-top-read` es el que permite leer "lo más escuchado". Se añadió después del
         * primer login, así que una cuenta enlazada antes no lo tiene: hay que desconectar
         * y volver a entrar una vez para que Spotify lo conceda.
         */
        private const val SCOPES =
            "user-library-read playlist-read-private playlist-read-collaborative " +
                "user-read-private user-top-read"

        private const val PREFS_NAME = "spotify_prefs"
        private const val PREFS_NAME_FALLBACK = "spotify_prefs_plain"

        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "spotify_token_expires_at"
        private const val KEY_CODE_VERIFIER = "spotify_code_verifier"
        private const val KEY_AUTH_STATE = "spotify_auth_state"
        private const val KEY_CLIENT_ID_OVERRIDE = "spotify_client_id_override"
        private const val KEY_ACCOUNT_NAME = "spotify_account_name"
        private const val KEY_ACCOUNT_EMAIL = "spotify_account_email"

        private const val EXPIRY_MARGIN_MS = 300_000L
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        /** 64 bytes aleatorios → 86 caracteres base64url, dentro del rango 43-128 del RFC 7636. */
        internal fun generateCodeVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, BASE64_FLAGS)
        }

        internal fun deriveCodeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, BASE64_FLAGS)
        }
    }
}
