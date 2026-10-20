package com.theveloper.pixelplay.data.youtube

import org.json.JSONObject

/**
 * YouTube client identities. Checked against yt-dlp's current _base.py on 2026-09-07.
 * VISIONOS is the preferred direct-audio path; clients requiring streaming attestation
 * are restricted to eligible fallback formats. Version/policy changes belong here.
 */
object InnerTubeContexts {

    const val BASE_URL = "https://www.youtube.com/youtubei/v1/"
    const val MUSIC_BASE_URL = "https://music.youtube.com/youtubei/v1/"

    /** Filtro "solo canciones" del buscador de YouTube Music. */
    const val SONGS_SEARCH_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    const val VIDEOS_SEARCH_PARAMS = "EgWKAQIQAWoKEAkQChAFEAMQBA=="

    data class ClientProfile(
        val name: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        /** Valor de la cabecera `X-YouTube-Client-Name`. */
        val clientNameId: Int,
        /**
         * Clave pública de InnerTube de este cliente. No es un secreto: va en la URL.
         * null cuando el cliente funciona sin ella (InnerTube ya no la exige, y yt-dlp no
         * declara ninguna para [VISIONOS]).
         */
        val apiKey: String? = null,
        val baseUrl: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        /**
         * Obligatorio para los clientes Android: sin él InnerTube devuelve un reproductor
         * sin formatos.
         */
        val androidSdkVersion: Int? = null,
        /** true si este cliente suele devolver URLs ya firmadas. */
        val expectsPreSignedUrls: Boolean,
        /**
         * `SUPPORTS_COOKIES` de yt-dlp. **Esto importa mucho.** Solo los clientes de
         * navegador/TV aceptan autenticación por cookie; a los nativos (VISIONOS,
         * ANDROID_VR, IOS, ANDROID_MUSIC) hay que hablarles ANÓNIMAMENTE o responden
         * HTTP 400 — era exactamente el 400 que daban los tres desde que se añadió el
         * login. yt-dlp directamente descarta de la lista los clientes sin cookies
         * cuando hay sesión, en vez de mandársela igualmente.
         */
        val supportsCookies: Boolean = false,
        /** Native clients with required GVS attestation must not expose tokenless audio. */
        val requiresStreamingPoToken: Boolean = false
    )

    /**
     * El cliente de Apple Vision Pro, y el más valioso de todos: es el primero de
     * `_DEFAULT_CLIENTS` y de `_DEFAULT_JSLESS_CLIENTS` en yt-dlp, y el único que no
     * declara **ni** `GVS_PO_TOKEN_POLICY` (así que su audio no necesita PoToken, y no le
     * aplica el recorte a ~900 KB / ~53 s) **ni** `REQUIRE_JS_PLAYER` (así que devuelve
     * URLs directas, sin pasar por base.js). Sin cookie: no está en `SUPPORTS_COOKIES`.
     */
    val VISIONOS = ClientProfile(
        name = "VISIONOS",
        clientName = "VISIONOS",
        clientVersion = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        clientNameId = 101,
        baseUrl = BASE_URL,
        deviceMake = "Apple",
        deviceModel = "RealityDevice17,1",
        osName = "visionOS",
        osVersion = "26.5.23O471",
        expectsPreSignedUrls = true
    )

    /**
     * La app de YouTube para Quest, en la forma exacta que tiene hoy en yt-dlp. No aplica
     * cifrado de firma (`REQUIRE_JS_PLAYER: False`), pero **ya sí** exige PoToken para el
     * audio (`GVS_PO_TOKEN_POLICY` HTTPS con `required=true`) — de ahí que su stream llegue
     * recortado. Por eso va detrás de [VISIONOS], que no lo exige. Sin cookie.
     */
    val ANDROID_VR = ClientProfile(
        name = "ANDROID_VR",
        clientName = "ANDROID_VR",
        clientVersion = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        clientNameId = 28,
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        baseUrl = BASE_URL,
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        osName = "Android",
        osVersion = "12L",
        androidSdkVersion = 32,
        expectsPreSignedUrls = true,
        requiresStreamingPoToken = true
    )

    /**
     * La app de iPhone, en la forma exacta que tiene hoy en yt-dlp. Devuelve URL directa sin
     * base.js, pero su `GVS_PO_TOKEN_POLICY` de HTTPS es `required=true`: **su audio llega
     * recortado** (~47-53 s) porque no hay forma de generarle un PoToken (iosGuard es
     * cerrado). Por eso va detrás de [VISIONOS], que no lo exige. Sin cookie.
     */
    val IOS = ClientProfile(
        name = "IOS",
        clientName = "IOS",
        clientVersion = "21.26.4",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        clientNameId = 5,
        apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
        baseUrl = BASE_URL,
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        expectsPreSignedUrls = true,
        requiresStreamingPoToken = true
    )

    val ANDROID_MUSIC = ClientProfile(
        name = "ANDROID_MUSIC",
        clientName = "ANDROID_MUSIC",
        clientVersion = "7.27.52",
        userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip",
        clientNameId = 21,
        apiKey = "AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
        baseUrl = BASE_URL,
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
        expectsPreSignedUrls = true,
        requiresStreamingPoToken = true
    )

    /**
     * Cliente de la app de YouTube para TV, en la forma exacta "tv_downgraded" de yt-dlp —
     * la referencia más veterana en esto, actualizada constantemente contra los cambios de
     * YouTube. Es, junto con [WEB], uno de los DOS únicos clientes que yt-dlp usa cuando hay
     * cookie (`_DEFAULT_AUTHED_CLIENTS = ('tv_downgraded', 'web')`) — deliberadamente NINGÚN
     * cliente de app móvil (ANDROID_MUSIC, IOS...), que es justo lo que nos venía dando 400/
     * "Precondition check failed" con cookie adjunta. Sin política de PoToken registrada
     * para este cliente en yt-dlp: no debería hacer falta uno para el audio.
     */
    val TVHTML5 = ClientProfile(
        name = "TVHTML5",
        clientName = "TVHTML5",
        clientVersion = "5.20260707",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        clientNameId = 7,
        apiKey = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8",
        baseUrl = BASE_URL,
        expectsPreSignedUrls = false,
        supportsCookies = true
    )

    /**
     * Cliente WEB normal (no WEB_REMIX/YouTube Music) — el otro de los dos únicos que
     * yt-dlp usa autenticado. WEB_REMIX exige PoToken también para el audio en sí
     * (GVS_PO_TOKEN_POLICY con required=true en yt-dlp); este WEB "a secas" es el que de
     * verdad usa yt-dlp por defecto con cookie, y va contra www.youtube.com, no
     * music.youtube.com.
     */
    val WEB = ClientProfile(
        name = "WEB",
        clientName = "WEB",
        clientVersion = "2.20260708.00.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        clientNameId = 1,
        apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3",
        baseUrl = BASE_URL,
        expectsPreSignedUrls = false,
        supportsCookies = true,
        requiresStreamingPoToken = true
    )

    val WEB_REMIX = ClientProfile(
        name = "WEB_REMIX",
        clientName = "WEB_REMIX",
        clientVersion = "1.20260707.12.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        clientNameId = 67,
        apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
        baseUrl = MUSIC_BASE_URL,
        expectsPreSignedUrls = false,
        supportsCookies = true,
        requiresStreamingPoToken = true
    )

    /**
     * Orden en que se intenta resolver el audio, copiado de las preferencias de yt-dlp:
     * [VISIONOS] primero porque es el único sin PoToken ni descifrado, luego el resto de los
     * que devuelven URL ya firmada, y por último los que obligan a pasar por base.js.
     */
    // yt-dlp _base.py, checked 2026-09-07: VR 1.65.10 returns 403 for ALL formats
    // since August 17. Retain its definition for diagnostics, but do not delay playback.
    val PLAYER_PROFILES = listOf(VISIONOS, IOS, TVHTML5, WEB_REMIX)

    /** El buscador siempre va por YouTube Music: es el que devuelve resultados tipo canción. */
    val SEARCH_PROFILE = WEB_REMIX

    fun buildContext(profile: ClientProfile, visitorData: String? = null): JSONObject {
        val client = JSONObject().apply {
            put("clientName", profile.clientName)
            put("clientVersion", profile.clientVersion)
            put("userAgent", profile.userAgent)
            put("hl", "en")
            put("gl", "US")
            profile.deviceMake?.let { put("deviceMake", it) }
            profile.deviceModel?.let { put("deviceModel", it) }
            profile.osName?.let { put("osName", it) }
            profile.osVersion?.let { put("osVersion", it) }
            profile.androidSdkVersion?.let { put("androidSdkVersion", it) }
            // Solo se manda cuando se tiene (peticiones autenticadas por cookie, ver
            // YouTubeAuthManager) — InnerTube lo espera atado a la sesión del navegador
            // que puso la cookie; mandar uno inventado o ninguno es parte de por qué las
            // peticiones autenticadas volvían con "Request contains an invalid argument".
            visitorData?.let { put("visitorData", it) }
        }
        return JSONObject().apply {
            put("context", JSONObject().put("client", client))
        }
    }

    /**
     * El origen real que ve YouTube para este cliente — depende de a qué dominio se manda
     * la petición, no es fijo. Se usa tanto para la cabecera `Origin` como para calcular el
     * hash de `Authorization: SAPISIDHASH` (ver YouTubeAuthManager): si no coinciden,
     * YouTube responde 400 a cualquier petición autenticada, aunque la cookie sea válida.
     */
    fun originFor(profile: ClientProfile): String =
        if (profile.baseUrl == MUSIC_BASE_URL) "https://music.youtube.com" else "https://www.youtube.com"

    fun headers(profile: ClientProfile): Map<String, String> = buildMap {
        put("User-Agent", profile.userAgent)
        put("Content-Type", "application/json")
        put("Accept-Language", "en-US,en;q=0.9")
        put("X-YouTube-Client-Name", profile.clientNameId.toString())
        put("X-YouTube-Client-Version", profile.clientVersion)
        put("Origin", originFor(profile))
    }

    /**
     * @param includeKey false cuando la petición lleva un token OAuth. YouTube rechaza con
     *   HTTP 400 una petición que trae a la vez la clave anónima y una cabecera
     *   `Authorization`: hay que mandar una u otra, no las dos.
     */
    fun endpoint(profile: ClientProfile, path: String, includeKey: Boolean = true): String {
        val key = profile.apiKey?.takeIf { includeKey }
        return if (key != null) {
            "${profile.baseUrl}$path?key=$key&prettyPrint=false"
        } else {
            "${profile.baseUrl}$path?prettyPrint=false"
        }
    }

    /**
     * Cabeceras para descargar el audio de `googlevideo`.
     *
     * Solo el User-Agent, y tiene que ser el del cliente que pidió la URL. **Nada más.**
     * Añadir `Origin` o `Referer` hace que googlevideo responda 403: un enlace emitido
     * para la app de iPhone acompañado de cabeceras de navegador no le cuadra.
     *
     * Está aquí, en un único sitio, porque el comprobador de enlaces y el proxy deben
     * mandar exactamente lo mismo. Cuando se separaron, el comprobador daba el enlace por
     * bueno y la descarga real fallaba con 403 — y desde fuera parecía que el enlace
     * funcionaba y la reproducción no.
     */
    fun streamHeaders(userAgent: String): Map<String, String> = mapOf("User-Agent" to userAgent)
}
