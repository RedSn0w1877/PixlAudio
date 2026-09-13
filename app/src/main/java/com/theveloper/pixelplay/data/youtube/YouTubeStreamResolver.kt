package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Una URL de audio y el `User-Agent` con el que hay que pedirla.
 *
 * googlevideo ata cada URL al cliente que la solicitó: pedirla desde otro agente devuelve
 * 403 aunque la URL sea correcta. Por eso los dos viajan juntos.
 */
data class ResolvedStream(
    val url: String,
    val userAgent: String,
    val strategyName: String? = null
)

/**
 * Convierte un `videoId` en una URL de audio reproducible.
 *
 * Es una interfaz a propósito. Resolver un stream de YouTube depende de detalles internos
 * que Google cambia sin previo aviso; cuando una vía deja de funcionar se añade o se
 * sustituye una implementación y el resto de la app no se entera.
 */
interface YouTubeStreamResolver {
    /** Nombre corto para los logs. */
    val strategyName: String

    /** URL de audio directa, o null si esta estrategia no ha podido resolverla. */
    suspend fun resolve(videoId: String): String?

    /**
     * Por qué falló el último intento. Lo lee el diagnóstico: sin esto, cuando ninguna
     * estrategia funciona, lo único que se sabe es "no hay audio".
     */
    val lastDetail: String?
        get() = null
}

/**
 * Estrategia barata: pide el reproductor con un cliente que suele devolver las URLs ya
 * firmadas (iOS, Android Music) y las usa tal cual.
 */
class PreSignedStreamResolver(
    private val innerTubeClient: InnerTubeClient,
    private val cipherSolver: SignatureCipherSolver,
    val profile: InnerTubeContexts.ClientProfile,
    private val maxBitrateKbpsProvider: suspend () -> Int? = { null }
) : YouTubeStreamResolver {

    override val strategyName: String = profile.name

    @Volatile
    override var lastDetail: String? = null
        private set

    override suspend fun resolve(videoId: String): String? {
        val response = innerTubeClient.fetchPlayer(videoId, profile)
        if (response == null) {
            lastDetail = innerTubeClient.lastFailureReason ?: "no response"
            return null
        }
        if (!response.isPlayable) {
            lastDetail = "${response.status}${response.reason?.let { " — $it" }.orEmpty()}"
            Timber.d("$strategyName: vídeo no reproducible (${lastDetail})")
            return null
        }
        val eligible = if (profile.requiresStreamingPoToken && response.streamingPoToken == null) {
            response.formats.filter { it.isMuxedFallback }
        } else response.formats
        val best = eligible.filter { it.url != null }.pickBestAudio(maxBitrateKbpsProvider())
        if (best?.url == null) {
            // "OK pero sin formatos" es la firma de que YouTube exige atestación
            // (PoToken) a este cliente.
            lastDetail = "OK but ${response.formats.size} usable formats"
            return null
        }
        // Aunque la URL venga ya firmada, sigue llevando el parámetro `n` revuelto. Sin
        // transformarlo, googlevideo deja pasar una petición minúscula (por eso la validación
        // de 2 bytes salía verde) pero responde 403 a la descarga real. Hay que descifrarlo
        // ejecutando base.js, igual que en la estrategia con cifrado.
        val transformed = cipherSolver.applyNTransform(best.url)
        val nWasTransformed = transformed != best.url
        lastDetail = "itag ${best.itag}, ${best.bitrate / 1000} kbps" +
            if (nWasTransformed) ", n descifrado" else ", n sin cambiar"
        return transformed
    }
}

/**
 * Estrategia cara: usa el cliente web, que devuelve la firma revuelta, y la descifra
 * ejecutando `base.js`.
 */
class CipheredStreamResolver(
    private val innerTubeClient: InnerTubeClient,
    private val cipherSolver: SignatureCipherSolver,
    val profile: InnerTubeContexts.ClientProfile = InnerTubeContexts.WEB_REMIX,
    private val maxBitrateKbpsProvider: suspend () -> Int? = { null }
) : YouTubeStreamResolver {

    override val strategyName: String = "${profile.name} (deciphered)"

    @Volatile
    override var lastDetail: String? = null
        private set

    override suspend fun resolve(videoId: String): String? {
        val response = innerTubeClient.fetchPlayer(videoId, profile)
        if (response == null) {
            lastDetail = innerTubeClient.lastFailureReason ?: "no response"
            Timber.d("$strategyName: sin respuesta ($lastDetail)")
            return null
        }
        if (!response.isPlayable) {
            lastDetail = "${response.status}${response.reason?.let { " — $it" }.orEmpty()}"
            Timber.d("$strategyName: vídeo no reproducible ($lastDetail)")
            return null
        }

        val eligible = if (profile.requiresStreamingPoToken && response.streamingPoToken == null) {
            response.formats.filter { it.isMuxedFallback }
        } else response.formats
        val format = eligible.pickBestAudio(maxBitrateKbpsProvider())
        if (format == null) {
            lastDetail = "OK but no audio formats"
            Timber.d("$strategyName: sin formatos de audio utilizables")
            return null
        }
        format.url?.let {
            lastDetail = "itag ${format.itag}, plain URL"
            return withStreamingPoToken(cipherSolver.applyNTransform(it), response.streamingPoToken)
        }
        val cipher = format.signatureCipher
        if (cipher == null) {
            lastDetail = "itag ${format.itag} had neither URL nor cipher"
            Timber.d("$strategyName: itag ${format.itag} sin URL ni cipher")
            return null
        }
        val resolved = cipherSolver.resolveCipheredUrl(cipher)
        lastDetail = if (resolved == null) "could not run base.js" else "itag ${format.itag}, deciphered"
        if (resolved == null) Timber.d("$strategyName: no se pudo ejecutar base.js para descifrar la firma")
        return withStreamingPoToken(resolved, response.streamingPoToken)
    }

    /**
     * Un enlace conseguido con poToken sigue exigiéndolo para la descarga real, como
     * parámetro `pot` — sin él, googlevideo puede responder 403 aunque el enlace en sí
     * parezca válido (confirmado: es el mismo tipo de fallo silencioso que ya se vio con
     * el parámetro `n` sin transformar).
     */
    private fun withStreamingPoToken(url: String?, streamingPoToken: String?): String? {
        if (url == null || streamingPoToken.isNullOrBlank()) return url
        return android.net.Uri.parse(url).buildUpon()
            .appendQueryParameter("pot", streamingPoToken)
            .build()
            .toString()
    }
}

/**
 * Prueba las estrategias en orden y devuelve la primera que funcione.
 *
 * El orden importa: las que no necesitan descifrar van antes porque son una sola petición
 * HTTP, frente a bajar y ejecutar cerca de dos megas de JavaScript.
 */
@Singleton
class ChainedYouTubeStreamResolver @Inject constructor(
    innerTubeClient: InnerTubeClient,
    cipherSolver: SignatureCipherSolver,
    private val validator: StreamUrlValidator,
    private val authManager: com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager,
    userPreferencesRepository: com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
) : YouTubeStreamResolver {

    // Se resuelve una vez por intento de reproducción, no una vez por formato: cada estrategia
    // relee el mismo tope, así que sale del DataStore cacheado en memoria, no de disco.
    private val qualityCapProvider: suspend () -> Int? = {
        userPreferencesRepository.audioQualityFlow.first().maxBitrateKbps
    }

    // yt-dlp — la referencia más veterana en esto, y la que se actualiza más rápido
    // contra los cambios de YouTube — usa exactamente DOS clientes cuando hay cookie:
    // `_DEFAULT_AUTHED_CLIENTS = ('tv_downgraded', 'web')`. Deliberadamente NINGÚN
    // cliente de app móvil (ANDROID_MUSIC, IOS...) — que es justo lo que nos venía dando
    // 400 / "Precondition check failed" con cookie adjunta, probablemente porque esos
    // exigen una atestación (DroidGuard/iosGuard) que solo la app oficial puede generar.
    // Tampoco WEB_REMIX como primera opción: en yt-dlp exige poToken también para el
    // audio en sí (no solo para la respuesta del reproductor), y esa pieza (streaming
    // pot) es la más nueva y frágil de toda la cadena — se deja como red de seguridad
    // más abajo, no como primera apuesta.
    private val tvDowngradedStrategy: YouTubeStreamResolver =
        CipheredStreamResolver(innerTubeClient, cipherSolver, InnerTubeContexts.TVHTML5, qualityCapProvider)
    private val webStrategy: YouTubeStreamResolver =
        CipheredStreamResolver(innerTubeClient, cipherSolver, InnerTubeContexts.WEB, qualityCapProvider)

    // WEB_REMIX es el único perfil para el que existe un poToken real (BotGuard vía
    // WebView) — el resto usa DroidGuard/iosGuard, cerrados, imposibles de generar fuera
    // de las apps oficiales. InnerTubeClient ya le adjunta cookie + poToken automáticamente
    // en cuanto hay sesión iniciada (ver fetchPlayer).
    private val webRemixStrategy: YouTubeStreamResolver =
        CipheredStreamResolver(innerTubeClient, cipherSolver, maxBitrateKbpsProvider = qualityCapProvider)

    // Los clientes que devuelven la URL ya firmada, en el orden de PLAYER_PROFILES: VISIONOS
    // primero (ni PoToken ni base.js), luego ANDROID_VR, IOS y ANDROID_MUSIC.
    private val preSignedStrategies: List<YouTubeStreamResolver> =
        InnerTubeContexts.PLAYER_PROFILES
            .filter { it.expectsPreSignedUrls }
            .map { PreSignedStreamResolver(innerTubeClient, cipherSolver, it, qualityCapProvider) }

    private val anonymousStrategies: List<YouTubeStreamResolver> =
        preSignedStrategies + listOf(tvDowngradedStrategy, webRemixStrategy)

    // Los pre-firmados van primero TAMBIÉN con sesión iniciada: ninguno acepta cookie (no
    // están en `SUPPORTS_COOKIES`, y mandársela era lo que los tiraba con HTTP 400), así que
    // el login no les cambia nada — y siguen siendo los únicos que resuelven el audio con una
    // sola petición, sin PoToken ni base.js. Los que sí usan la cookie van detrás.
    private fun currentStrategies(): List<YouTubeStreamResolver> =
        if (authManager.isSignedIn.value) {
            preSignedStrategies + listOf(tvDowngradedStrategy, webStrategy, webRemixStrategy)
        } else {
            anonymousStrategies
        }

    override val strategyName: String = "chained"

    /** Última estrategia que funcionó, para el informe de diagnóstico. */
    @Volatile
    var lastSuccessfulStrategy: String? = null
        private set

    /** Qué hizo cada cliente en el último intento, en orden. */
    @Volatile
    var lastAttempts: List<String> = emptyList()
        private set

    override val lastDetail: String?
        get() = lastAttempts.joinToString("\n").ifBlank { null }

    override suspend fun resolve(videoId: String): String? = resolveStream(videoId)?.url

    /**
     * Igual que [resolve] pero devuelve también el `User-Agent` con el que se validó la
     * URL. Quien luego la descargue debe usar ese mismo agente.
     */
    /**
     * @param validate cuando es false NO se prueba la URL con una petición previa. Esto es
     *   deliberado para el proxy de reproducción: la comprobación añade latencia y otra petición de red. El proxy valida
     *   la respuesta real y prueba otro cliente si esa descarga falla. La elección de cliente ya la hacen las señales del player API
     *   (isPlayable + formatos), que descartan los que exigen login sin tocar el stream.
     */
    suspend fun resolveStream(
        videoId: String,
        validate: Boolean = true,
        excludedStrategies: Set<String> = emptySet()
    ): ResolvedStream? {
        val attempts = mutableListOf<String>()

        for (strategy in currentStrategies().filterNot { it.strategyName in excludedStrategies }) {
            val userAgent = strategy.userAgent()
            val url = runCatching { withTimeoutOrNull(15_000L) { strategy.resolve(videoId) } }
                .onFailure {
                    if (it is CancellationException) throw it
                    Timber.w(it, "Estrategia ${strategy.strategyName} falló")
                }
                .getOrNull()

            if (url.isNullOrBlank()) {
                attempts += "${strategy.strategyName}: ${strategy.lastDetail ?: "no URL"}"
                continue
            }

            if (!validate) {
                attempts += "${strategy.strategyName}: ${strategy.lastDetail ?: "url"} (sin validar)"
                lastAttempts = attempts
                lastSuccessfulStrategy = strategy.strategyName
                Timber.d("Stream resuelto por ${strategy.strategyName} (sin validación previa)")
                return ResolvedStream(url = url, userAgent = userAgent, strategyName = strategy.strategyName)
            }

            // Tener una URL no basta. Sin la atestación que YouTube exige a algunos
            // clientes, googlevideo responde 403 a un enlace de aspecto impecable; si no
            // se comprueba aquí, el reproductor recibe un enlace muerto y ya no hay
            // ocasión de probar el siguiente cliente.
            val probe = validator.probe(url, userAgent)
            if (probe.ok) {
                attempts += "${strategy.strategyName}: ${probe.describe()}"
                lastAttempts = attempts
                lastSuccessfulStrategy = strategy.strategyName
                Timber.d("Stream resuelto por ${strategy.strategyName}")
                return ResolvedStream(url = url, userAgent = userAgent, strategyName = strategy.strategyName)
            }

            attempts += "${strategy.strategyName}: got URL but ${probe.describe()}"
        }

        lastAttempts = attempts
        lastSuccessfulStrategy = null
        Timber.w("Ninguna estrategia pudo resolver el audio de $videoId: $attempts")
        return null
    }

    private fun YouTubeStreamResolver.userAgent(): String = when (this) {
        is PreSignedStreamResolver -> profile.userAgent
        is CipheredStreamResolver -> profile.userAgent
        else -> InnerTubeContexts.ANDROID_VR.userAgent
    }
}

/**
 * Elige el formato de audio de más calidad disponible.
 *
 * Se ordena por bitrate real y no por una lista fija de itags: así el itag 141 (AAC
 * 256 kbps) gana al 251 (Opus ~160 kbps) y este al 140 (AAC 128 kbps), y si YouTube
 * añade algún día un formato mejor se coge solo, sin tocar nada.
 *
 * Nota sobre "lossless": YouTube no sirve audio sin pérdida por ninguna vía. El techo real
 * son esos ~256 kbps. Lo que hay aquí es lo máximo que existe, no una limitación de la app.
 */
internal fun List<YouTubeAudioFormat>.pickBestAudio(maxBitrateKbps: Int? = null): YouTubeAudioFormat? {
    if (isEmpty()) return null
    // El tope de calidad del usuario solo recorta entre lo que YouTube realmente ofrece para
    // este vídeo — si aplicarlo dejara la lista vacía (p.ej. el vídeo solo trae un formato por
    // encima del tope), se ignora antes que devolver silencio.
    val audioFirst = filterNot { it.isMuxedFallback }.ifEmpty { this }
    val capped = if (maxBitrateKbps == null) audioFirst else audioFirst.filter { it.bitrate <= maxBitrateKbps * 1000 }
    val candidates = if (capped.isEmpty()) audioFirst else capped
    // El itag 18 (audio+vídeo juntos) va siempre último por mucho bitrate que declare: el
    // suyo cuenta también el vídeo, y su audio real es de 22 kHz. Solo sirve cuando no hay
    // ninguna pista de audio de verdad utilizable — que es justo lo que pasa cuando YouTube
    // devuelve el audio por SABR, sin URL ni cipher.
    // A igualdad de bitrate se prefiere Opus: rinde mejor que AAC al mismo caudal.
    return candidates.maxWithOrNull(
        compareBy<YouTubeAudioFormat> { if (it.isMuxedFallback) 0 else 1 }
            .thenBy { it.bitrate }
            .thenBy { if (it.mimeType?.contains("opus", ignoreCase = true) == true) 1 else 0 }
    )
}
