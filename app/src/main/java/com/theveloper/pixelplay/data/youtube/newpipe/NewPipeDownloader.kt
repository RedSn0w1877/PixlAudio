package com.theveloper.pixelplay.data.youtube.newpipe

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptador de [Downloader] sobre OkHttp: es el único método que NewPipeExtractor exige
 * implementar para hacer sus propias peticiones (buscar el vídeo, pedir el reproductor,
 * resolver el stream). Usa el cliente de YouTube, sin el interceptor que reescribe el
 * User-Agent.
 *
 * Probado adjuntar aquí el token OAuth de la cuenta conectada (ver [YouTubeAuthManager]): rompió
 * la petición del cliente ANDROID por completo ("ANDROID player response is not valid" en cada
 * intento, confirmado por logcat) en vez de simplemente ignorarse — el token de la app de TV no
 * es válido para el contexto de cliente que arma NewPipeExtractor. Revertido; sin esta cabecera
 * vuelve a funcionar (con el tope de bytes por enlace ya conocido, no arreglado por esto).
 */
@Singleton
class NewPipeDownloader @Inject constructor(
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient
    private val okHttpClient: okhttp3.OkHttpClient
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            if (values.size == 1) {
                builder.header(name, values[0])
            } else {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
        }

        val response = okHttpClient.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        val responseBody = response.body.string()
        val latestUrl = response.request.url.toString()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            latestUrl
        )
    }

    companion object {
        // El de un navegador de escritorio normal — es lo que NewPipeExtractor espera para
        // sus propias peticiones (buscar, cargar la página del vídeo). No tiene por qué
        // coincidir con el user-agent que necesita luego el enlace de audio ya resuelto.
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/115.0"
    }
}
