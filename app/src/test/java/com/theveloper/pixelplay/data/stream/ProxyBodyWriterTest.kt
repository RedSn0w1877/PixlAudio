package com.theveloper.pixelplay.data.stream

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProxyBodyWriterTest {

    /** Serves [first] once, then blocks like a slow googlevideo socket until [release]. */
    private class GatedInput(private val first: ByteArray, private val release: CountDownLatch) : InputStream() {
        private var served = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!served) {
                served = true
                val n = minOf(len, first.size)
                System.arraycopy(first, 0, b, off, n)
                return n
            }
            release.await(10, TimeUnit.SECONDS)
            return -1
        }
    }

    @Test
    fun `each piece reaches the player while the upstream is still sending`() = runBlocking {
        // What Ktor CIO gives every response body.
        val channel = ByteChannel(autoFlush = false)
        val release = CountDownLatch(1)
        val copy = async(Dispatchers.IO) {
            ProxyBodyWriter.copyUpstream(GatedInput(ByteArray(65_536) { 7 }, release), channel, limit = null)
        }
        // The copy is now blocked reading the next piece; the first one must already be readable.
        val readable = withTimeoutOrNull(5_000) { channel.awaitContent(65_536) }
        release.countDown()
        assertEquals(true, readable)
        assertEquals(65_536L, copy.await())
    }

    @Test
    fun `without a flush ktor keeps a 64 KiB write from the reader`() = runBlocking {
        // Records the Ktor 3.x behaviour that made the flush necessary (flushIfNeeded waits
        // for 1 MiB on a non-autoFlush channel). If an upgrade changes it, the flush stays harmless.
        val channel = ByteChannel(autoFlush = false)
        channel.writeFully(ByteArray(65_536), 0, 65_536)
        assertNull(withTimeoutOrNull(300) { channel.awaitContent(1) })
        channel.flush()
        assertEquals(true, withTimeoutOrNull(5_000) { channel.awaitContent(65_536) })
    }

    @Test
    fun `the copy stops at the limit and reports every piece`() = runBlocking {
        val channel = ByteChannel(autoFlush = false)
        val pieces = mutableListOf<Int>()
        val written = ProxyBodyWriter.copyUpstream(
            ByteArrayInputStream(ByteArray(200_000) { (it % 251).toByte() }),
            channel,
            limit = 100_000L
        ) { pieces += it }
        channel.flushAndClose()
        val received = channel.toByteArray()
        assertEquals(100_000L, written)
        assertEquals(100_000, pieces.sum())
        assertTrue(pieces.all { it <= ProxyBodyWriter.BUFFER_SIZE })
        assertEquals(100_000, received.size)
        assertEquals((99_999 % 251).toByte(), received.last())
    }

    @Test
    fun `without a limit the copy runs to the end of the body`() = runBlocking {
        val channel = ByteChannel(autoFlush = false)
        val written = ProxyBodyWriter.copyUpstream(ByteArrayInputStream(ByteArray(150_000)), channel, limit = null)
        channel.flushAndClose()
        assertEquals(150_000L, written)
        assertEquals(150_000, channel.toByteArray().size)
    }
}
