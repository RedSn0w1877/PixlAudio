package com.theveloper.pixelplay.data.youtube.piped

import com.theveloper.pixelplay.data.youtube.ResolvedStream
import com.theveloper.pixelplay.di.YouTubeOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resuelve audio a través de instancias públicas de Piped en vez de extraerlo en el propio
 * dispositivo.
 *
 * [com.theveloper.pixelplay.data.youtube.newpipe.NewPipeStreamResolver] consigue formatos de
 * audio reales, pero el cliente anónimo (IOS, sin poToken) que usa para conseguirlos entrega un
 * enlace recortado a unos 900 KB — confirmado con pruebas exhaustivas de lectura secuencial,
 * salto directo y avance progresivo, todas topando en el mismo punto sin importar el enfoque, lo
 * que apunta a un recorte deliberado del lado de YouTube para clientes sin sesión, no a un límite
 * que se pueda sortear con peticiones más listas. Las instancias de Piped resuelven el vídeo en
 * su propio servidor (con su propia solución de poToken/autenticación) y devuelven un enlace de
 * googlevideo ya autenticado como cliente ANDROID — probado a mano contra
 * api.piped.private.coffee: una lectura a los 5 MB de un enlace así funciona a la primera, sin
 * ningún recorte.
 *
 * Muchas instancias públicas de Piped tienen roto el formato de audio-solo (`audioStreams`)
 * porque comparten el mismo problema de poToken que este proyecto — así que si no hay audio
 * puro disponible, se cae al formato de vídeo+audio combinado de menor calidad (normalmente
 * itag 18, 360p) y se reproduce igual: ExoPlayer solo decodifica la pista de audio, el vídeo
 * simplemente no se usa. Cuesta algo más de datos que un audio-solo, pero es real y no se corta.
 */
@Singleton
class PipedStreamResolver @Inject constructor(
    @YouTubeOkHttpClient sharedOkHttpClient: OkHttpClient
) {

    // El cliente compartido usa timeouts pensados para el streaming en sí (15 s de conexión,
    // 30 s de lectura) — bien para descargar audio, fatal para elegir instancia: con 3
    // instancias de respaldo, una sola que esté caída/bloqueada podía dejar la app esperando
    // hasta 45 s antes de pasar a la siguiente, y hasta 2-3 minutos si las tres fallaban antes
    // de caer al resolver de NewPipeExtractor. Esta sola llamada (pedir el JSON de streams) no
    // necesita más de unos segundos si la instancia está viva.
    private val probeOkHttpClient = sharedOkHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile
    var lastDetail: String? = null
        private set

    // Lista blanca de hosts que CloudStreamProxy debe aceptar para el audio resuelto por Piped
    // (ver SpotifyStreamProxy.allowedHostSuffixes). Empieza con la lista fija de respaldo y
    // crece con cada instancia nueva que el registro oficial devuelva — nunca con hosts sacados
    // del propio JSON de streams, solo con los que este resolver decidió consultar.
    val trustedHosts: Set<String>
        get() = INSTANCES.toSet() + discoveredInstances.keys
    private val discoveredInstances = ConcurrentHashMap<String, Boolean>()

    private val instancesListMutex = Mutex()
    private var cachedLiveInstances: List<String>? = null
    private var liveInstancesFetchedAtMs: Long = 0L

    // Cuando las tres instancias fallan, no tiene sentido volver a intentarlas en el
    // refresco siguiente 10 segundos después — sobre todo dentro de una misma canción, donde
    // CloudStreamProxy pide un enlace nuevo cada vez que el actual toca el tope de ~900 KB del
    // resolver de respaldo. Sin este enfriamiento, cada uno de esos refrescos paga de nuevo el
    // coste completo de las tres instancias (hasta ~18s con los timeouts cortos de abajo) antes
    // de caer a NewPipeExtractor — eso fue lo que dejó a Hoa Vo esperando más de dos minutos
    // sin que sonara nada.
    @Volatile
    private var unavailableUntilMs: Long = 0L

    suspend fun resolve(videoId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now < unavailableUntilMs) {
            lastDetail = "en enfriamiento tras fallos recientes, ${(unavailableUntilMs - now) / 1000}s restantes"
            return@withContext null
        }

        for (instance in candidateInstances()) {
            val result = runCatching { resolveFromInstance(instance, videoId) }
                .onFailure { Timber.d("PipedStreamResolver: $instance falló para $videoId: ${it.message}") }
                .getOrNull()
            if (result != null) {
                lastDetail = "$instance ($videoId)"
                return@withContext result
            }
        }
        lastDetail = "ninguna instancia de Piped resolvió $videoId"
        unavailableUntilMs = System.currentTimeMillis() + COOLDOWN_MS
        null
    }

    /**
     * Las tres instancias fijas de abajo se murieron todas a la vez entre sesiones — la
     * disponibilidad pública de Piped cambia más rápido de lo que conviene tener grabado a
     * fuego en el código. El registro oficial del proyecto lleva la cuenta de qué instancias
     * están vivas en cada momento; se consulta una vez cada [LIVE_LIST_TTL_MS] y se combina con
     * la lista fija como último recurso si el propio registro no responde.
     */
    private suspend fun candidateInstances(): List<String> = instancesListMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedLiveInstances
        if (cached != null && now - liveInstancesFetchedAtMs < LIVE_LIST_TTL_MS) {
            return@withLock cached
        }
        val fetched = runCatching { fetchLiveInstances() }
            .onFailure { Timber.d("PipedStreamResolver: no se pudo consultar el registro de instancias: ${it.message}") }
            .getOrNull()
        if (fetched.isNullOrEmpty()) {
            // Sin registro disponible, la lista fija es lo único que queda — puede que también
            // esté caída, pero no hay más de dónde tirar.
            return@withLock INSTANCES
        }
        fetched.forEach { discoveredInstances[it] = true }
        val combined = (fetched + INSTANCES).distinct().take(MAX_INSTANCES_TO_TRY)
        cachedLiveInstances = combined
        liveInstancesFetchedAtMs = now
        combined
    }

    private fun fetchLiveInstances(): List<String> {
        val request = Request.Builder()
            .url("https://piped-instances.kavin.rocks")
            .header("User-Agent", USER_AGENT)
            .build()

        probeOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body.string()
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
            val hosts = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val apiUrl = entry.optString("api_url").takeIf { it.isNotBlank() } ?: continue
                val host = runCatching { java.net.URI(apiUrl).host }.getOrNull() ?: continue
                hosts += host
            }
            Timber.d("PipedStreamResolver: registro devolvió ${hosts.size} instancias")
            return hosts
        }
    }

    private fun resolveFromInstance(instance: String, videoId: String): ResolvedStream? {
        val request = Request.Builder()
            .url("https://$instance/streams/$videoId")
            .header("User-Agent", USER_AGENT)
            .build()

        probeOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.d("PipedStreamResolver: $instance respondió HTTP ${response.code}")
                return null
            }
            val body = response.body.string()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json == null) {
                Timber.d("PipedStreamResolver: $instance devolvió algo que no es JSON válido (${body.take(80)})")
                return null
            }
            if (json.has("error")) {
                Timber.d("PipedStreamResolver: $instance devolvió error: ${json.optString("error")}")
                return null
            }

            // Audio puro primero: no gasta ancho de banda de más en vídeo que no se va a ver.
            bestAudioOnlyUrl(json)?.let {
                Timber.d("PipedStreamResolver: $instance dio audio puro para $videoId")
                trustResolvedUrlHost(it)
                return ResolvedStream(url = it, userAgent = USER_AGENT)
            }

            // Red de seguridad: formato combinado de menor calidad (arrastra vídeo, pero trae
            // audio garantizado en instancias donde audioStreams viene vacío).
            bestMuxedUrl(json)?.let {
                Timber.d("PipedStreamResolver: $instance no tenía audio puro, usando vídeo+audio combinado para $videoId")
                trustResolvedUrlHost(it)
                return ResolvedStream(url = it, userAgent = USER_AGENT)
            }

            val audioCount = json.optJSONArray("audioStreams")?.length() ?: 0
            val videoCount = json.optJSONArray("videoStreams")?.length() ?: 0
            Timber.d("PipedStreamResolver: $instance respondió pero sin nada usable ($audioCount audioStreams, $videoCount videoStreams)")
            return null
        }
    }

    /**
     * Las instancias de Piped a menudo sirven el audio/vídeo a través de su propio subdominio
     * "proxy.*" en vez de devolver un enlace directo de googlevideo — distinto del subdominio
     * "api.*" que se consultó para pedir el JSON. `allowedHostSuffixes` solo confiaba en el host
     * de la instancia consultada, así que ese enlace de reproducción quedaba rechazado por
     * [com.theveloper.pixelplay.data.stream.CloudStreamSecurity] aunque Piped hubiera resuelto
     * todo correctamente. Como el host viene del JSON de una instancia en la que ya se decidió
     * confiar (está en [candidateInstances]), es seguro añadirlo aquí.
     */
    private fun trustResolvedUrlHost(url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (host.endsWith("googlevideo.com")) return
        discoveredInstances[host] = true
    }

    private fun bestAudioOnlyUrl(json: JSONObject): String? {
        val streams = json.optJSONArray("audioStreams") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val url = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bitrate = stream.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Formato combinado de menor resolución que no sea HLS/LBRY, para minimizar el vídeo desperdiciado. */
    private fun bestMuxedUrl(json: JSONObject): String? {
        val streams = json.optJSONArray("videoStreams") ?: return null
        var bestUrl: String? = null
        var bestHeight = Int.MAX_VALUE
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            if (stream.optBoolean("videoOnly", true)) continue
            val mimeType = stream.optString("mimeType")
            if (!mimeType.startsWith("video/")) continue
            val url = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
            // LBRY/Odysee y HLS no pasan la lista blanca de hosts del proxy; solo interesan los
            // que de verdad pasan por la instancia de Piped (googlevideo detrás del proxy).
            if (!url.contains("/videoplayback")) continue
            val quality = stream.optString("quality")
            val height = Regex("""(\d+)p""").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            if (height < bestHeight) {
                bestHeight = height
                bestUrl = url
            }
        }
        return bestUrl
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private const val COOLDOWN_MS = 20 * 1000L
        private const val LIVE_LIST_TTL_MS = 60 * 60 * 1000L
        private const val MAX_INSTANCES_TO_TRY = 8

        // Última red de seguridad si el registro oficial (ver fetchLiveInstances) no responde.
        // Probadas a mano el 2026-07-24 — pueden estar caídas para cuando esto se lea, la
        // disponibilidad de las instancias de Piped cambia con el tiempo. La lista dinámica del
        // registro es la fuente principal; esta es solo el último recurso.
        val INSTANCES = listOf(
            "api.piped.private.coffee",
            "pipedapi.kavin.rocks",
            "pipedapi.adminforge.de"
        )
    }
}
