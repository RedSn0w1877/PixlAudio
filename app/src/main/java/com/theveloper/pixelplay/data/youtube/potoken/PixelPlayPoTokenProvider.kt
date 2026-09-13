package com.theveloper.pixelplay.data.youtube.potoken

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portado de la app oficial de NewPipe (`util/potoken/PoTokenProviderImpl.kt`), adaptado a
 * corrutinas. Solo implementa el cliente WEB — es el único que NewPipe mismo soporta con
 * BotGuard vía WebView; los demás (`getWebEmbedClientPoToken`, `getAndroidClientPoToken`,
 * `getIosClientPoToken`) devuelven null igual que en la app original, porque sus tokens se
 * generan con DroidGuard/iosGuard, cerrados y no reproducibles fuera de esas apps.
 */
@Singleton
class PixelPlayPoTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient private val okHttpClient: OkHttpClient,
    private val downloader: com.theveloper.pixelplay.data.youtube.newpipe.NewPipeDownloader
) : PoTokenProvider {

    private val lock = Any()

    /**
     * `NewPipe.init()` lo llamaba únicamente
     * [com.theveloper.pixelplay.data.youtube.newpipe.NewPipeStreamResolver], que va ÚLTIMO en
     * la cadena de estrategias. Este proveedor se usa mucho antes (lo llama InnerTubeClient),
     * así que [YoutubeParsingHelper.getClientVersion] se encontraba el downloader estático a
     * null y todo generar-poToken moría con NullPointerException — es decir, el poToken nunca
     * llegó a generarse ni una vez. Se inicializa aquí también, que es idempotente.
     */
    private fun ensureNewPipeInitialized() {
        if (NewPipe.getDownloader() == null) NewPipe.init(downloader)
    }
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    @Volatile
    private var cachedAnonymousVisitorData: String? = null

    /**
     * Un `visitorData` recién emitido por InnerTube, para las peticiones SIN cookie.
     *
     * Hace falta porque el guardado en [com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager]
     * puede ser todavía el placeholder heredado de InnerTune, que YouTube ya no acepta: sin
     * un visitorData válido responde "LOGIN_REQUIRED — Sign in to confirm you're not a bot"
     * incluso a vídeos públicos. Se pide una sola vez por proceso y se guarda en memoria, así
     * que no añade latencia salvo en la primera canción.
     */
    fun anonymousVisitorData(): String? {
        cachedAnonymousVisitorData?.let { return it }
        return synchronized(lock) {
            cachedAnonymousVisitorData ?: runCatching {
                ensureNewPipeInitialized()
                val requestInfo = InnertubeClientRequestInfo.ofWebClient().apply {
                    clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                }
                YoutubeParsingHelper.getVisitorDataFromInnertube(
                    requestInfo,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false
                )
            }.onFailure {
                Timber.w(it, "No se pudo pedir un visitorData fresco")
            }.getOrNull()?.also { cachedAnonymousVisitorData = it }
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = try {
        getWebClientPoTokenBlocking(videoId, forceRecreate = false)
    } catch (e: Exception) {
        Timber.w(e, "No se pudo generar poToken para $videoId")
        null
    }

    /**
     * Igual que [getWebClientPoToken] pero deja pasar la excepción tal cual, sin
     * envolverla ni convertirla en null. Solo para el sondeo de depuración: NewPipeExtractor
     * también se traga cualquier fallo del proveedor y lo convierte en "0 pistas de audio",
     * así que sin esto no hay forma de ver qué rompió realmente.
     */
    fun getWebClientPoTokenOrThrow(videoId: String): PoTokenResult =
        getWebClientPoTokenBlocking(videoId, forceRecreate = false)

    private fun getWebClientPoTokenBlocking(videoId: String, forceRecreate: Boolean): PoTokenResult {
        data class Snapshot(
            val generator: PoTokenWebView,
            val visitorData: String,
            val streamingPot: String,
            val wasRecreated: Boolean
        )

        val snapshot = synchronized(lock) {
            ensureNewPipeInitialized()
            val shouldRecreate = webPoTokenGenerator == null || forceRecreate ||
                webPoTokenGenerator!!.isExpired()

            if (shouldRecreate) {
                val requestInfo = InnertubeClientRequestInfo.ofWebClient().apply {
                    clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                }
                webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    requestInfo,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false
                )

                // Cierra el WebView anterior fuera del lock no hace falta: close() ya se
                // encarga de saltar al hilo principal por su cuenta.
                webPoTokenGenerator?.close()
                webPoTokenGenerator = PoTokenWebView.newPoTokenGenerator(context, okHttpClient)
                // El poToken de streaming hay que generarlo una vez, antes que cualquier otro.
                webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenVisitorData!!)
            }

            Snapshot(webPoTokenGenerator!!, webPoTokenVisitorData!!, webPoTokenStreamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            snapshot.generator.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (snapshot.wasRecreated) {
                throw t
            }
            // Puede que el WebView se haya perdido (la app pasó a segundo plano, etc.).
            // Se reintenta una vez recreándolo desde cero.
            Timber.w(t, "Fallo generando poToken; reintentando desde cero")
            return getWebClientPoTokenBlocking(videoId, forceRecreate = true)
        }

        return PoTokenResult(snapshot.visitorData, playerPot, snapshot.streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
