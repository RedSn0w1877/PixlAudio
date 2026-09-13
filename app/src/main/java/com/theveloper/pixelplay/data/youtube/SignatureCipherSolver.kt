package com.theveloper.pixelplay.data.youtube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Descifra las URLs de audio protegidas de YouTube.
 *
 * Cuando InnerTube devuelve `signatureCipher` en lugar de una URL, la firma viene revuelta
 * por una función JavaScript que vive dentro de `base.js`, el reproductor web de YouTube.
 * Además, el parámetro `n` de la URL pasa por otra función; si no se transforma, YouTube
 * limita la descarga a velocidades inservibles.
 *
 * No hay forma de replicar esas funciones en Kotlin de manera estable: cambian cada pocas
 * semanas. Lo que se hace es bajarlas y **ejecutarlas** con [JsEvaluator].
 *
 * Es, por construcción, la parte más frágil de la reproducción. Por eso vive detrás de
 * [YouTubeStreamResolver]: si deja de funcionar se sustituye la estrategia, no la función.
 */
@Singleton
class SignatureCipherSolver @Inject constructor(
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient private val okHttpClient: OkHttpClient,
    private val jsEvaluator: JsEvaluator
) {

    private val loadMutex = Mutex()

    private data class PlayerScript(
        val playerId: String,
        val signatureFunctionJs: String?,
        val nFunctionJs: String?
    )

    @Volatile
    private var cached: PlayerScript? = null

    /**
     * Convierte un `signatureCipher` en una URL reproducible.
     * Devuelve null si no se pudo obtener o ejecutar el descifrador.
     */
    suspend fun resolveCipheredUrl(signatureCipher: String): String? {
        // Viene como querystring: url=…&s=…&sp=sig
        val params = parseQueryString(signatureCipher)
        val baseUrl = params["url"] ?: return null
        val scrambled = params["s"] ?: return applyNTransform(baseUrl)
        val signatureParam = params["sp"] ?: "signature"

        val script = ensurePlayerScript() ?: return null
        val signatureJs = script.signatureFunctionJs ?: return null

        val deciphered = jsEvaluator.evaluate(
            "(function(){ $signatureJs; return __ppSig(${JSONObject.quote(scrambled)}); })()"
        ) ?: return null

        val withSignature = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter(signatureParam, deciphered)
            .build()
            .toString()

        return applyNTransform(withSignature)
    }

    /**
     * Aplica la transformación del parámetro `n`. Sin ella YouTube estrangula la descarga
     * a una fracción de la velocidad real y el audio se corta constantemente.
     */
    suspend fun applyNTransform(url: String): String {
        val uri = Uri.parse(url)
        val n = uri.getQueryParameter("n") ?: return url
        val script = ensurePlayerScript() ?: return url
        val nJs = script.nFunctionJs ?: return url

        val transformed = jsEvaluator.evaluate(
            "(function(){ $nJs; return __ppN(${JSONObject.quote(n)}); })()"
        ) ?: return url

        // Un resultado que empieza por "enhanced_except" significa que la función se rindió;
        // reenviarlo es peor que dejar el original.
        if (transformed.startsWith("enhanced_except") || transformed == n) return url

        val rebuilt = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            val value = if (name == "n") transformed else uri.getQueryParameter(name)
            rebuilt.appendQueryParameter(name, value.orEmpty())
        }
        return rebuilt.build().toString()
    }

    // ─── Descarga y extracción de base.js ──────────────────────────────

    private suspend fun ensurePlayerScript(): PlayerScript? {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            val loaded = downloadPlayerScript()
            cached = loaded
            loaded
        }
    }

    /** Invalida el reproductor cacheado; la siguiente resolución lo vuelve a bajar. */
    fun invalidate() {
        cached = null
    }

    /**
     * Igual que [downloadPlayerScript] pero contando qué pasó en cada paso, en vez de
     * devolver null en el primer fallo. Solo para el sondeo de depuración: sin esto, "n
     * transform no cambia n" no dice si fue el iframe, el playerId, la descarga de base.js
     * o el patrón de la función lo que falló.
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val iframeProbe = httpGetWithStatus("https://www.youtube.com/iframe_api")
        out.appendLine("iframe_api: HTTP ${iframeProbe.status ?: "?"} (${iframeProbe.body?.length ?: 0} bytes)${iframeProbe.error?.let { " — $it" }.orEmpty()}")

        val playerId = iframeProbe.body?.let { PLAYER_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
        out.appendLine("playerId: ${playerId ?: "NOT FOUND (PLAYER_ID_REGEX didn't match)"}")
        if (playerId == null) return@withContext out.toString()

        val baseJsUrl = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"
        val baseJsProbe = httpGetWithStatus(baseJsUrl)
        out.appendLine("base.js: HTTP ${baseJsProbe.status ?: "?"} (${baseJsProbe.body?.length ?: 0} bytes)${baseJsProbe.error?.let { " — $it" }.orEmpty()}")
        val baseJs = baseJsProbe.body ?: return@withContext out.toString()

        val sigFn = buildSignatureFunction(baseJs)
        val nFn = buildNFunction(baseJs)
        out.appendLine("signature function: ${if (sigFn != null) "found" else "NOT FOUND (outdated patterns)"}")
        out.appendLine("n function: ${if (nFn != null) "found" else "NOT FOUND (outdated patterns)"}")

        if (nFn != null) {
            val testResult = runCatching {
                jsEvaluator.evaluate("(function(){ $nFn; return __ppN(${JSONObject.quote("TESTVALUE123")}); })()")
            }.getOrNull()
            out.appendLine("run n function via JsEvaluator: ${testResult ?: "NULL (JsEvaluator returned nothing)"}")
        }

        // Si todo esto salió bien, cachear para que la resolución real no lo repita.
        if (sigFn != null || nFn != null) {
            cached = PlayerScript(playerId, sigFn, nFn)
        }
        out.toString()
    }

    private data class HttpProbe(val status: Int?, val body: String?, val error: String?)

    private fun httpGetWithStatus(url: String): HttpProbe = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", InnerTubeContexts.WEB_REMIX.userAgent)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            HttpProbe(response.code, if (response.isSuccessful) response.body.string() else null, null)
        }
    } catch (e: Exception) {
        HttpProbe(null, null, "${e.javaClass.simpleName}: ${e.message}")
    }

    private suspend fun downloadPlayerScript(): PlayerScript? = withContext(Dispatchers.IO) {
        val playerId = fetchPlayerId() ?: return@withContext null
        val baseJs = httpGet(
            "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"
        ) ?: return@withContext null

        PlayerScript(
            playerId = playerId,
            signatureFunctionJs = buildSignatureFunction(baseJs),
            nFunctionJs = buildNFunction(baseJs)
        ).also {
            if (it.signatureFunctionJs == null) {
                Timber.w("No se encontró la función de firma en base.js ($playerId)")
            }
            if (it.nFunctionJs == null) {
                Timber.w("No se encontró la función 'n' en base.js ($playerId)")
            }
        }
    }

    private fun fetchPlayerId(): String? {
        val iframe = httpGet("https://www.youtube.com/iframe_api") ?: return null
        return PLAYER_ID_REGEX.find(iframe)?.groupValues?.getOrNull(1)
    }

    private fun httpGet(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", InnerTubeContexts.WEB_REMIX.userAgent)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (e: Exception) {
        Timber.w(e, "No se pudo descargar $url")
        null
    }

    /**
     * Devuelve JavaScript que define `__ppSig(s)`: el objeto auxiliar con las operaciones
     * de mezcla más la función que las encadena.
     */
    private fun buildSignatureFunction(baseJs: String): String? {
        val name = SIGNATURE_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(baseJs)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } ?: return null

        val body = extractFunctionBody(baseJs, name) ?: return null
        // El cuerpo llama a un objeto auxiliar: `Xy.AB(a,3); Xy.CD(a,17); ...`
        val helperName = HELPER_CALL_REGEX.find(body)?.groupValues?.getOrNull(1)
        val helper = helperName?.let { extractObjectLiteral(baseJs, it) }.orEmpty()

        return "$helper function __ppSig(a){$body}"
    }

    /** Igual que [buildSignatureFunction] pero para la transformación del parámetro `n`. */
    private fun buildNFunction(baseJs: String): String? {
        val rawName = N_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(baseJs)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } ?: return null

        // A veces el nombre no es la función sino un array de una posición: `var Dm=[nfn];`
        val name = Regex("""var\s+${Regex.escape(rawName)}\s*=\s*\[\s*([a-zA-Z0-9_$]+)\s*]""")
            .find(baseJs)?.groupValues?.getOrNull(1) ?: rawName

        val body = extractFunctionBody(baseJs, name) ?: return null
        return "function __ppN(a){$body}"
    }

    /**
     * Extrae el cuerpo de `name = function(a){ … }` contando llaves.
     *
     * Una expresión regular no sirve: el cuerpo de la función `n` tiene llaves anidadas y
     * un `[^}]+` cortaría por la primera.
     */
    private fun extractFunctionBody(js: String, name: String): String? {
        val escaped = Regex.escape(name)
        val declaration = listOf(
            Regex("""(?:var\s+|;|,|^)$escaped\s*=\s*function\s*\(\s*[a-zA-Z0-9_$]*\s*\)\s*\{"""),
            Regex("""function\s+$escaped\s*\(\s*[a-zA-Z0-9_$]*\s*\)\s*\{"""),
            Regex("""$escaped\s*:\s*function\s*\(\s*[a-zA-Z0-9_$]*\s*\)\s*\{""")
        ).firstNotNullOfOrNull { it.find(js) } ?: return null

        val openBrace = js.indexOf('{', declaration.range.last - 1).takeIf { it >= 0 } ?: return null
        val closeBrace = findMatchingBrace(js, openBrace) ?: return null
        return js.substring(openBrace + 1, closeBrace)
    }

    private fun extractObjectLiteral(js: String, name: String): String? {
        val match = Regex("""var\s+${Regex.escape(name)}\s*=\s*\{""").find(js) ?: return null
        val openBrace = js.indexOf('{', match.range.last - 1).takeIf { it >= 0 } ?: return null
        val closeBrace = findMatchingBrace(js, openBrace) ?: return null
        return "var $name = ${js.substring(openBrace, closeBrace + 1)};"
    }

    /**
     * Devuelve el índice de la llave que cierra la abierta en [openIndex], ignorando las
     * que aparecen dentro de cadenas, expresiones regulares literales o comentarios.
     */
    private fun findMatchingBrace(js: String, openIndex: Int): Int? {
        var depth = 0
        var index = openIndex
        var quote: Char? = null
        var escaped = false

        while (index < js.length) {
            val char = js[index]
            when {
                escaped -> escaped = false
                quote != null && char == '\\' -> escaped = true
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '"' || char == '\'' || char == '`' -> quote = char
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun parseQueryString(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = pair.substring(0, separator)
            val value = Uri.decode(pair.substring(separator + 1))
            key to value
        }.toMap()

    private companion object {
        val PLAYER_ID_REGEX = Regex("""player\\?/([a-zA-Z0-9_-]{8,})\\?/""")

        val SIGNATURE_NAME_PATTERNS = listOf(
            Regex("""\bm=([a-zA-Z0-9_$]{2,})\(decodeURIComponent\(h\.s\)\)"""),
            Regex("""\bc&&\(c=([a-zA-Z0-9_$]{2,})\(decodeURIComponent\(c\)\)"""),
            Regex("""(?:\b|[^a-zA-Z0-9_$])([a-zA-Z0-9_$]{2,})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*[""']{2}\s*\)"""),
            Regex("""([a-zA-Z0-9_$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*[""']{2}\s*\);""")
        )

        val N_NAME_PATTERNS = listOf(
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9_$]=([a-zA-Z0-9_$]+)(?:\[\d+])?\("""),
            Regex("""\([a-zA-Z0-9_$]=String\.fromCharCode\(110\),[a-zA-Z0-9_$]=[a-zA-Z0-9_$]\.get\([a-zA-Z0-9_$]\)\)&&\([a-zA-Z0-9_$]=([a-zA-Z0-9_$]+)(?:\[\d+])?\("""),
            Regex("""[a-zA-Z0-9_$]+\.set\("n",\s*([a-zA-Z0-9_$]+)\(""")
        )

        val HELPER_CALL_REGEX = Regex("""([a-zA-Z0-9_$]{2,})\.[a-zA-Z0-9_$]{2,}\(\s*a\s*,""")
    }
}
