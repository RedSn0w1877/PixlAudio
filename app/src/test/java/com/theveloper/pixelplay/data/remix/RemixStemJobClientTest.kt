package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.cloud.RemixBackendConfig
import com.theveloper.pixelplay.data.remix.cloud.RemixStemJobClient
import com.theveloper.pixelplay.data.remix.cloud.StemJobOutcome
import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the separation protocol against a real socket rather than a mocking library — the
 * same approach `AudioDownloadProgressTest` already uses in this module, and it catches the
 * things that actually break: a status that is still queued on the first poll, and downloads
 * arriving as four separate requests.
 */
class RemixStemJobClientTest {

    private class FakeServer(private val script: (String) -> Pair<String, ByteArray>) {
        private val server = ServerSocket(0)
        val requests = AtomicInteger(0)
        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    handle(socket)
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                // Drain the upload so the client sees a complete exchange.
                var remaining = contentLength
                val scratch = ByteArray(8 * 1024)
                while (remaining > 0) {
                    val read = input.read(scratch, 0, minOf(scratch.size, remaining))
                    if (read <= 0) break
                    remaining -= read
                }

                requests.incrementAndGet()
                val (contentType, body) = script(requestLine)
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                output.write(body)
                output.flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val value = input.read()
                if (value == -1) return if (builder.isEmpty()) null else builder.toString()
                if (value == '\n'.code) return builder.toString().removeSuffix("\r")
                builder.append(value.toChar())
            }
        }

        fun close() = server.close()
    }

    private fun wavBytes(): ByteArray = ByteArray(64) { index ->
        when (index) {
            0 -> 'R'.code.toByte(); 1 -> 'I'.code.toByte(); 2 -> 'F'.code.toByte(); 3 -> 'F'.code.toByte()
            else -> index.toByte()
        }
    }

    @Test
    fun `submits, polls past a queued status, and downloads four stems`(@TempDir temp: File) {
        var statusCalls = 0
        val server = FakeServer { requestLine ->
            when {
                requestLine.startsWith("POST /run") ->
                    "application/json" to """{"id":"j1","status":"IN_QUEUE"}""".toByteArray()

                requestLine.startsWith("GET /status/j1") -> {
                    statusCalls++
                    val body = if (statusCalls < 2) {
                        """{"id":"j1","status":"IN_PROGRESS","progress":0.4}"""
                    } else {
                        """{"id":"j1","status":"COMPLETED","progress":1.0,"stems":{""" +
                            """"vocals":"/download/j1/vocals","drums":"/download/j1/drums",""" +
                            """"bass":"/download/j1/bass","other":"/download/j1/other"}}"""
                    }
                    "application/json" to body.toByteArray()
                }

                requestLine.startsWith("GET /download/") -> "audio/wav" to wavBytes()
                else -> "text/plain" to "unexpected".toByteArray()
            }
        }

        try {
            val source = File(temp, "track.mp3").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
            val outputs = File(temp, "stems")
            val progress = mutableListOf<Float>()

            val outcome = runBlocking {
                RemixStemJobClient(OkHttpClient()).separate(
                    config = RemixBackendConfig(baseUrl = server.baseUrl, token = "t0ken"),
                    source = source,
                    outputDirectory = outputs,
                    outputPrefix = "song_stem_",
                    onProgress = { progress.add(it) },
                )
            }

            assertTrue(outcome is StemJobOutcome.Completed) { "unexpected outcome: $outcome" }
            val files = (outcome as StemJobOutcome.Completed).files
            assertEquals(4, files.size)
            RemixStemJobClient.EXPECTED_STEMS.forEach { kind ->
                val file = files[kind]
                assertTrue(file != null && file.exists()) { "$kind was not written" }
                assertEquals("song_stem_$kind.wav", file!!.name)
            }
            assertTrue(progress.isNotEmpty()) { "progress was never reported" }
            // Nothing may be left behind half-written.
            assertTrue(outputs.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            server.close()
        }
    }

    @Test
    fun `reports the server's own failure message`(@TempDir temp: File) {
        val server = FakeServer { requestLine ->
            when {
                requestLine.startsWith("POST /run") ->
                    "application/json" to """{"id":"j2","status":"IN_QUEUE"}""".toByteArray()
                requestLine.startsWith("GET /status/") ->
                    "application/json" to
                        """{"id":"j2","status":"FAILED","error":"demucs ran out of memory"}""".toByteArray()
                else -> "text/plain" to "?".toByteArray()
            }
        }
        try {
            val source = File(temp, "track.mp3").apply { writeBytes(ByteArray(512)) }
            val outcome = runBlocking {
                RemixStemJobClient(OkHttpClient()).separate(
                    config = RemixBackendConfig(server.baseUrl, "t"),
                    source = source,
                    outputDirectory = File(temp, "stems"),
                    outputPrefix = "s_",
                    onProgress = {},
                )
            }
            assertTrue(outcome is StemJobOutcome.Failed)
            assertEquals("demucs ran out of memory", (outcome as StemJobOutcome.Failed).message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `refuses to start without a configured server`(@TempDir temp: File) {
        val outcome = runBlocking {
            RemixStemJobClient(OkHttpClient()).separate(
                config = RemixBackendConfig(baseUrl = "", token = ""),
                source = File(temp, "missing.mp3"),
                outputDirectory = temp,
                outputPrefix = "s_",
                onProgress = {},
            )
        }
        assertTrue(outcome is StemJobOutcome.Failed)
    }
}
