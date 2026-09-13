package com.theveloper.pixelplay.data.cache

import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AudioDownloadCancellationTest {
    @TempDir lateinit var directory: Path

    @Test fun `cancelling after headers closes a stalled body socket and removes partial audio`() = runBlocking {
        val server = ServerSocket(0)
        val accepted = AtomicReference<Socket>()
        val peerClosed = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    val input = socket.getInputStream()
                    val reader = input.bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                        write(ByteArray(16) { 7 })
                        flush()
                    }
                    // Intentionally never finish the response. Client cancellation must close it.
                    try { while (input.read() >= 0) Unit } finally { peerClosed.countDown() }
                }
            } catch (_: Exception) {
                peerClosed.countDown()
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val pending = directory.resolve("download.part").toFile()
        try {
            val download = async(Dispatchers.IO) {
                AudioDownloadTransfer.fetch(client, "http://127.0.0.1:${server.localPort}/audio", pending)
            }
            // This proves cancellation occurs during body consumption, not while waiting for headers.
            withTimeout(5_000L) { while (pending.length() < 16L) delay(10L) }
            val started = System.nanoTime()
            download.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L)
            assertTrue(peerClosed.await(2, TimeUnit.SECONDS), "The stalled HTTP call must be cancelled too")
            withTimeout(2_000L) { while (pending.exists()) delay(10L) }
        } finally {
            accepted.get()?.close()
            server.close()
            serverThread.join(2_000L)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
