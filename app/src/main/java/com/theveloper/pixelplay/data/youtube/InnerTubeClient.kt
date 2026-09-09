package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Una canción encontrada en YouTube Music. */
data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String? = null,
    val isMusicVideo: Boolean = false
)

/** Un formato de audio concreto dentro de un vídeo. */
data class YouTubeAudioFormat(
    val itag: Int,
    val mimeType: String?,
    val bitrate: Int,
    /** URL directa, ya reproducible. Null si venía cifrada. */
    val url: String?,
    /** Cadena `signatureCipher` sin resolver (necesita `base.js`). */
    val signatureCipher: String?,
    val contentLength: Long?,
    val approxDurationMs: Long?,
    /**
     * true para el itag 18 (mp4 360p con audio y vídeo en el mismo fichero). No es una pista
     * de audio de verdad, pero yt-dlp lo exime explícitamente de necesitar PoToken
     * (`stream_id[0] not in ['18']`), así que es el único formato que llega COMPLETO cuando
     * el resto del audio viene por SABR o recortado a ~53 s. Se gasta ancho de banda en un
     * vídeo que no se ve y el audio es de 22 kHz, así que solo se usa si no hay nada más:
     * [pickBestAudio] lo deja siempre en último lugar.
     */
    val isMuxedFallback: Boolean = false
)

data class YouTubePlayerResponse(
    val status: String,
    val reason: String?,
    val formats: List<YouTubeAudioFormat>,
    /**
     * El `pot` que hay que añadir a la URL de audio final para que googlevideo la acepte —
     * solo se rellena cuando esta respuesta se pidió con poToken (ver
     * [InnerTubeClient.fetchPlayer], caso WEB_REMIX con cookie). Sin él, un enlace que
     * necesitó poToken para conseguirse puede seguir sirviendo un 403 al descargarlo.
     */
    val streamingPoToken: String? = null
) {
    val isPlayable: Boolean get() = status.equals("OK", ignoreCase = true)
}

/**
 * Cliente de InnerTube, la API privada de YouTube.
 *
 * No es una API pública ni documentada: YouTube cambia la forma de las respuestas cuando
 * quiere. Por eso el parseo aquí es deliberadamente laxo — recorre el árbol JSON buscando
 * las claves que le interesan en vez de seguir una ruta fija que se rompería al primer
 * rediseño.
 */
@Singleton
class InnerTubeClient @Inject constructor(
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient private val okHttpClient: OkHttpClient,
    private val authManager: com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager,
    private val poTokenProvider: com.theveloper.pixelplay.data.youtube.potoken.PixelPlayPoTokenProvider
) {

    /** Motivo del último fallo de red, para el informe de diagnóstico. */
    @Volatile
    var lastFailureReason: String? = null
        private set

    // ─── Búsqueda ──────────────────────────────────────────────────────

    suspend fun searchSongs(query: String, limit: Int = 10): List<YouTubeSearchResult> =
        searchFiltered(query, limit, InnerTubeContexts.SONGS_SEARCH_PARAMS, isVideo = false)

    /** Music videos remain audio-only during playback, using the same offline pipeline. */
    suspend fun searchVideos(query: String, limit: Int = 10): List<YouTubeSearchResult> =
        searchFiltered(query, limit, InnerTubeContexts.VIDEOS_SEARCH_PARAMS, isVideo = true)

    suspend fun searchMusic(
        query: String,
        limit: Int = 12,
        includeVideos: Boolean = true
    ): List<YouTubeSearchResult> {
        if (!includeVideos) return searchSongs(query, limit)
        // Reserve space for videos even when the song shelf is full. One failed shelf
        // must not discard successful results from the other.
        var failure: Exception? = null
        val songs = try { searchSongs(query, limit) } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure = e
            emptyList()
        }
        val videos = try { searchVideos(query, limit) } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure = e
            emptyList()
        }
        if (songs.isEmpty() && videos.isEmpty()) failure?.let { throw it }
        val unique = LinkedHashMap<String, YouTubeSearchResult>()
        for (index in 0 until maxOf(songs.size, videos.size)) {
            songs.getOrNull(index)?.let { unique.putIfAbsent(it.videoId, it) }
            videos.getOrNull(index)?.let { unique.putIfAbsent(it.videoId, it) }
        }
        return unique.values.take(limit.coerceIn(1, 50))
    }

    private suspend fun searchFiltered(
        query: String,
        limit: Int,
        params: String,
        isVideo: Boolean
    ): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val profile = InnerTubeContexts.SEARCH_PROFILE
        val visitorData = poTokenProvider.anonymousVisitorData() ?: authManager.currentVisitorData()
        val body = InnerTubeContexts.buildContext(profile, visitorData).apply {
            put("query", query.trim())
            put("params", params)
        }
        // A transport/API error is different from an empty search. Propagate it so the
        // matching worker keeps the song PENDING and retries after the network recovers.
        val json = post(InnerTubeContexts.endpoint(profile, "search"), body, profile,
            throwOnFailure = true) ?: throw IOException("YouTube search returned no response")
        val results = mutableListOf<YouTubeSearchResult>()
        collectByKey(json, "musicResponsiveListItemRenderer") { renderer ->
            parseSearchItem(renderer)?.copy(isMusicVideo = isVideo)?.let(results::add)
            results.size < limit.coerceAtMost(50)
        }
        if (results.isEmpty() && !json.has("contents") && !json.has("continuationContents")) {
            throw IOException("YouTube search response did not contain a results page")
        }
        results.distinctBy { it.videoId }
    }

    // ─── Formatos de audio ─────────────────────────────────────────────

    suspend fun fetchPlayer(
        videoId: String,
        profile: InnerTubeContexts.ClientProfile
    ): YouTubePlayerResponse? = withContext(Dispatchers.IO) {
        // Si el usuario ha iniciado sesión (cookie de una sesión real de
        // music.youtube.com), se adjunta la autenticación SAPISIDHASH: es lo que quita el
        // "confirma que no eres un robot" (LOGIN_REQUIRED) que YouTube empezó a exigir.
        // A diferencia del token OAuth del flujo de dispositivo (que solo aceptaba
        // TVHTML5 y encima seguía exigiendo descifrar la firma con base.js), la cookie
        // la aceptan todos los clientes por igual — es el mismo mecanismo que usa
        // cualquier producto de Google en el navegador — y con ANDROID_MUSIC/IOS el
        // vídeo llega con URL ya firmada, sin cipher de por medio.
        // …pero SOLO a los clientes que la aceptan. VISIONOS/ANDROID_VR/IOS/ANDROID_MUSIC
        // no están en `SUPPORTS_COOKIES` de yt-dlp y responden HTTP 400 si les llega una
        // cookie: era la causa exacta de los tres "respondió HTTP 400" que aparecían en
        // cuanto había sesión iniciada, y por tanto de que se cayeran justo los clientes
        // que devuelven URLs directas. A esos se les habla anónimamente, con sesión o sin
        // ella. yt-dlp hace lo mismo (los saca de la lista en vez de mandarles la cookie).
        val cookie = authManager.cookie?.takeIf { profile.supportsCookies }

        // WEB_REMIX es el único perfil para el que sí se puede generar un poToken real
        // (BotGuard vía WebView, ver PixelPlayPoTokenProvider) — ANDROID_MUSIC/IOS/
        // ANDROID_VR usan DroidGuard/iosGuard, cerrados, imposibles de reproducir fuera de
        // esas apps. Si YouTube ya exige poToken también en peticiones autenticadas (no
        // solo anónimas), es la única combinación con la que hay alguna posibilidad. El
        // poToken viene atado a un visitorData concreto — el que generó el propio
        // proveedor, NO el capturado en el login — así que aquí se sustituye solo para
        // esta petición.
        val poToken = if (cookie != null && profile == InnerTubeContexts.WEB_REMIX) {
            runCatching { poTokenProvider.getWebClientPoToken(videoId) }
                .onFailure { Timber.w(it, "No se pudo generar poToken para WEB_REMIX autenticado") }
                .getOrNull()
        } else {
            null
        }
        // El visitorData va SIEMPRE, también sin cookie. Los clientes que no aceptan cookie
        // (VISIONOS, ANDROID_VR, IOS...) se quedaban sin ninguna identidad de sesión, y
        // YouTube les respondía "LOGIN_REQUIRED — Sign in to confirm you're not a bot": no
        // es que faltara la cuenta, es que sin visitorData la petición parece un robot.
        // Justo eso tiraba a VISIONOS, que es el único cliente cuyo audio no necesita
        // PoToken (y por tanto el único que no llega recortado a ~53 s).
        val visitorData = poToken?.visitorData
            ?: poTokenProvider.anonymousVisitorData()
            ?: authManager.currentVisitorData()

        val body = InnerTubeContexts.buildContext(profile, visitorData).apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            // InnerTune no manda racyCheckOk en absoluto — se replica el cuerpo exacto
            // solo para la petición autenticada, para descartar esto como variable.
            if (cookie == null) put("racyCheckOk", true)
            poToken?.let {
                put("serviceIntegrityDimensions", JSONObject().put("poToken", it.playerRequestPoToken))
            }
        }

        // Cada perfil ya declara su propio host natural (WEB_REMIX → music.youtube.com,
        // TVHTML5/WEB → www.youtube.com — igual que INNERTUBE_HOST en yt-dlp, donde solo
        // 'web_music' se sale de www.youtube.com). Forzar TODO a music.youtube.com (como se
        // hacía antes, imitando a InnerTune) fue lo que rompía ANDROID_MUSIC con
        // "Request contains an invalid argument": ese cliente nunca habla con
        // music.youtube.com de verdad. El origen del hash SAPISIDHASH tiene que ser el
        // mismo dominio al que se manda la petición, así que sale del propio perfil.
        val origin = InnerTubeContexts.originFor(profile)
        val sapisidHeader = if (cookie != null) authManager.sapisidHashAuthorization(origin) else null
        val json = post(
            url = InnerTubeContexts.endpoint(profile, "player"),
            body = body,
            profile = profile,
            cookie = cookie,
            sapisidAuth = sapisidHeader,
            origin = origin
        ) ?: return@withContext null

        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty().ifBlank { "UNKNOWN" }
        val reason = playability?.optString("reason")?.ifBlank { null }

        val formats = mutableListOf<YouTubeAudioFormat>()
        val streamingData = json.optJSONObject("streamingData")
        val rawArrays = listOfNotNull(
            streamingData?.optJSONArray("adaptiveFormats"),
            streamingData?.optJSONArray("formats")
        )
        rawArrays.forEach { array ->
            for (i in 0 until array.length()) {
                parseFormat(array.optJSONObject(i) ?: continue)?.let(formats::add)
            }
        }
        if (status.equals("OK", ignoreCase = true) && formats.isEmpty()) {
            // "OK" pero sin audio utilizable — para saber si de verdad no había NADA de
            // audio en la respuesta, o si parseFormat lo estaba descartando por algo
            // (mimeType con forma rara, sin url ni cipher...) en vez de adivinar.
            val mimeTypes = rawArrays.flatMap { array ->
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.optString("mimeType") }
            }
            val hasHls = streamingData?.has("hlsManifestUrl") == true
            // Esto es la firma de SABR: las entradas de audio existen y traen
            // initRange/indexRange/contentLength, pero SIN url ni signatureCipher, así que no
            // hay nada que reproducir. Le pasa a WEB. Si vuelve a aparecer, el arreglo no está
            // por aquí: es que se está usando un cliente que sirve SABR en vez de VISIONOS.
            Timber.d("${profile.name}: OK pero 0 formatos de audio tras parseFormat. mimeTypes=$mimeTypes hlsManifestUrl=$hasHls")
        }

        YouTubePlayerResponse(
            status = status,
            reason = reason,
            formats = formats,
            streamingPoToken = poToken?.streamingDataPoToken
        )
    }

    // ─── Internos ──────────────────────────────────────────────────────

    private suspend fun post(
        url: String,
        body: JSONObject,
        profile: InnerTubeContexts.ClientProfile,
        cookie: String? = null,
        sapisidAuth: String? = null,
        origin: String = "https://music.youtube.com",
        throwOnFailure: Boolean = false
    ): JSONObject? {
        return try {
            val authenticated = cookie != null && sapisidAuth != null
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    if (authenticated) {
                        // Keep the numeric header identity consistent with the client
                        // context, adding browser authentication only to supported clients.
                        header("Content-Type", "application/json")
                        header("X-Goog-Api-Format-Version", "1")
                        header("X-YouTube-Client-Name", profile.clientNameId.toString())
                        header("X-YouTube-Client-Version", profile.clientVersion)
                        header("x-origin", origin)
                        header("User-Agent", profile.userAgent)
                        header("cookie", cookie!!)
                        header("Authorization", sapisidAuth!!)
                    } else {
                        InnerTubeContexts.headers(profile).forEach { (k, v) -> header(k, v) }
                    }
                }
                .build()

            // Do not log cookies, SAPISID authorization, visitor identity or PoTokens.
            Timber.d("InnerTube %s request (authenticated=%s)", profile.name, authenticated)

            okHttpClient.newCall(request).awaitYouTubeResponse().use { response ->
                if (!response.isSuccessful) {
                    lastFailureReason = "${profile.name} respondió HTTP ${response.code}"
                    // El motivo real de un 400 con cookie adjunta suele venir en el cuerpo
                    // ("Invalid Origin", "Invalid Authorization credentials", etc.) — sin
                    // esto solo se sabe "400" y hay que adivinar por qué.
                    val errorBody = runCatching { response.body.string() }.getOrNull()?.take(300)
                    Timber.w("InnerTube ${profile.name} respondió HTTP ${response.code}${if (cookie != null) " (con cookie)" else ""}: $errorBody")
                    if (throwOnFailure) throw IOException(lastFailureReason)
                    return null
                }
                lastFailureReason = null
                response.body.string().takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastFailureReason = "${profile.name}: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            Timber.w(e, "Petición a InnerTube fallida (${profile.name})")
            if (throwOnFailure) throw IOException(lastFailureReason, e)
            null
        }
    }

    private fun parseFormat(format: JSONObject): YouTubeAudioFormat? {
        val mimeType = format.optString("mimeType").takeIf { it.isNotBlank() }
        val itag = format.optInt("itag", -1)
        // Solo pistas de audio: los formatos de vídeo desperdiciarían ancho de banda. La
        // excepción es el itag 18, que lleva audio dentro y es el único que YouTube sirve
        // completo sin PoToken — ver [YouTubeAudioFormat.isMuxedFallback].
        val muxedFallback = itag == MUXED_FALLBACK_ITAG
        if (mimeType != null && !mimeType.startsWith("audio/") && !muxedFallback) return null

        val url = format.optString("url").takeIf { it.isNotBlank() }
        val cipher = format.optString("signatureCipher").takeIf { it.isNotBlank() }
            ?: format.optString("cipher").takeIf { it.isNotBlank() }
        if (url == null && cipher == null) return null

        return YouTubeAudioFormat(
            itag = itag,
            mimeType = mimeType,
            bitrate = format.optInt("bitrate", 0),
            url = url,
            signatureCipher = cipher,
            contentLength = format.optString("contentLength").toLongOrNull(),
            approxDurationMs = format.optString("approxDurationMs").toLongOrNull(),
            isMuxedFallback = muxedFallback
        )
    }

    private fun parseSearchItem(renderer: JSONObject): YouTubeSearchResult? {
        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: findFirstString(renderer, "videoId")
            ?: return null

        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val columnTexts = (0 until columns.length()).map { index ->
            val column = columns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            collectRuns(column?.optJSONObject("text"))
        }

        val title = columnTexts.getOrNull(0)?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: return null

        // La segunda columna es "Artista • Álbum • 3:07" repartida en runs; los separadores
        // llegan como runs sueltos con "•".
        val details = columnTexts.getOrNull(1)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it != "•" }
            .orEmpty()

        val duration = details.firstNotNullOfOrNull(::parseDurationSeconds)
        val meaningful = details.filter { parseDurationSeconds(it) == null }
        // "Song" / "Video" es la etiqueta de tipo que YouTube Music pone primero.
        val withoutType = meaningful.filterNot { it.equals("Song", true) || it.equals("Video", true) }

        val thumbnailUrl = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            ?.maxByOrNull { it.optInt("width", 0) }
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }

        return YouTubeSearchResult(
            videoId = videoId,
            title = title,
            artist = withoutType.firstOrNull().orEmpty(),
            album = withoutType.getOrNull(1)?.takeUnless { it.contains("views", true) || it.contains("plays", true) },
            durationSeconds = duration,
            thumbnailUrl = thumbnailUrl
        )
    }

    private fun collectRuns(text: JSONObject?): List<String> {
        val runs = text?.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }
    }

    /**
     * Recorre el árbol JSON invocando [onFound] por cada objeto bajo la clave [key].
     * [onFound] devuelve false para detener la búsqueda.
     */
    private fun collectByKey(root: Any?, key: String, onFound: (JSONObject) -> Boolean): Boolean {
        when (root) {
            is JSONObject -> {
                for (name in root.keys()) {
                    val value = root.opt(name)
                    if (name == key && value is JSONObject) {
                        if (!onFound(value)) return false
                    } else if (!collectByKey(value, key, onFound)) {
                        return false
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until root.length()) {
                    if (!collectByKey(root.opt(i), key, onFound)) return false
                }
            }
        }
        return true
    }

    private fun findFirstString(root: Any?, key: String): String? {
        when (root) {
            is JSONObject -> {
                for (name in root.keys()) {
                    val value = root.opt(name)
                    if (name == key && value is String && value.isNotBlank()) return value
                    findFirstString(value, key)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findFirstString(root.opt(i), key)?.let { return it }
                }
            }
        }
        return null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** mp4 360p con audio+vídeo juntos; el único itag exento de PoToken en yt-dlp. */
        const val MUXED_FALLBACK_ITAG = 18

        /** "3:07" o "1:02:33" → segundos. */
        fun parseDurationSeconds(raw: String): Int? {
            val parts = raw.split(":")
            if (parts.size !in 2..3) return null
            val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
            return when (numbers.size) {
                2 -> numbers[0] * 60 + numbers[1]
                else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            }
        }
    }
}
