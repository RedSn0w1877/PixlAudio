package com.theveloper.pixelplay.data.cache

import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * El progreso de descarga se calcula dentro del bucle de copia real, así que se comprueba contra
 * un servidor de verdad en vez de con mocks: lo que importa es cómo se comporta con respuestas
 * troceadas y con respuestas sin `Content-Length`.
 */
class AudioDownloadProgressTest {
    @TempDir lateinit var directory: Path

    private fun serve(body: ByteArray, withContentLength: Boolean): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\n")
                        if (withContentLength) append("Content-Length: ${body.size}\r\n")
                        else append("Connection: close\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(headers.toByteArray())
                        // A trozos, para que el bucle de copia dé varias vueltas de verdad.
                        var offset = 0
                        while (offset < body.size) {
                            val end = minOf(offset + 32 * 1024, body.size)
                            write(body, offset, end - offset)
                            flush()
                            offset = end
                        }
                    }
                }
            } catch (_: Exception) {
                // El test cierra el servidor al terminar; cualquier fallo se ve en las aserciones.
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun `reports increasing percentages ending at one hundred when the length is known`() = runBlocking {
        val body = ByteArray(512 * 1024) { it.toByte() }
        val server = serve(body, withContentLength = true)
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val pending = directory.resolve("known-length.part").toFile()
        val percents = mutableListOf<Int>()
        try {
            withContext(Dispatchers.IO) {
                AudioDownloadTransfer.fetch(client, "http://127.0.0.1:${server.localPort}/audio", pending) {
                    percents += it
                }
            }
            assertTrue(percents.isNotEmpty(), "A download with a known length must report progress")
            assertEquals(100, percents.last(), "A completed download must finish at 100%")
            assertTrue(percents.all { it in 0..100 }, "Every percentage must stay within 0..100: $percents")
            // Solo se notifica cuando el entero cambia, así que no puede haber repetidos ni saltos
            // hacia atrás: una barra que retrocede es peor que ninguna barra.
            assertEquals(percents.distinct(), percents, "Percentages must not repeat: $percents")
            assertEquals(percents.sorted(), percents, "Percentages must never move backwards: $percents")
        } finally {
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `reports no percentage when the server omits the content length`() = runBlocking {
        val body = ByteArray(128 * 1024) { it.toByte() }
        val server = serve(body, withContentLength = false)
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val pending = directory.resolve("unknown-length.part").toFile()
        val percents = mutableListOf<Int>()
        try {
            withContext(Dispatchers.IO) {
                AudioDownloadTransfer.fetch(client, "http://127.0.0.1:${server.localPort}/audio", pending) {
                    percents += it
                }
            }
            // Sin total no hay porcentaje honesto que calcular: la interfaz enseña una barra
            // indeterminada en vez de inventarse un número.
            assertTrue(percents.isEmpty(), "Without Content-Length there is no percentage to report: $percents")
            assertEquals(body.size.toLong(), pending.length(), "The body must still be written in full")
        } finally {
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `progress cache clamps values and forgets a song once it finishes`() {
        DownloadProgressCache.update("song-a", 47)
        DownloadProgressCache.update("song-b", 250)
        assertEquals(47, DownloadProgressCache.progress.value["song-a"])
        assertEquals(100, DownloadProgressCache.progress.value["song-b"], "Values above 100 must be clamped")

        DownloadProgressCache.clear("song-a")
        assertTrue(!DownloadProgressCache.progress.value.containsKey("song-a"))
        DownloadProgressCache.clear("song-b")
    }
}
