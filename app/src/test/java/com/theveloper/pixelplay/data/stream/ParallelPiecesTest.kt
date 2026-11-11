package com.theveloper.pixelplay.data.stream

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ParallelPiecesTest {

    private val source = ByteArray(10_000) { (it * 31 + 7).toByte() }

    @Test
    fun `pieces arrive out of order but are written in order, never more than the window at once`() = runBlocking(Dispatchers.Default) {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val random = Random(42)
        val out = ByteArrayOutputStream()

        ParallelPieces.stream(
            from = 1_000,
            to = 9_499,
            pieceSize = 700,
            parallelism = 3,
            fetch = { start, end ->
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(random.nextLong(1, 25))
                inFlight.decrementAndGet()
                source.copyOfRange(start.toInt(), end.toInt() + 1)
            },
            write = { bytes -> synchronized(out) { out.write(bytes) } }
        )

        assertThat(out.toByteArray()).isEqualTo(source.copyOfRange(1_000, 9_500))
        assertThat(maxInFlight.get()).isAtMost(3)
        assertThat(maxInFlight.get()).isAtLeast(2)
    }

    @Test
    fun `the first piece is written before any parallel piece`() = runBlocking {
        val order = mutableListOf<String>()
        ParallelPieces.stream(
            from = 100,
            to = 299,
            pieceSize = 100,
            parallelism = 2,
            fetch = { start, _ -> ByteArray(100) { start.toByte() } },
            beforeFirstPiece = { order += "first" },
            write = { bytes -> order += "piece@${bytes[0]}" }
        )
        assertThat(order).containsExactly("first", "piece@100", "piece@${200.toByte()}").inOrder()
    }

    @Test
    fun `an empty remainder only writes the first piece`() = runBlocking {
        var firstRan = false
        var fetched = false
        ParallelPieces.stream(
            from = 500,
            to = 499,
            pieceSize = 100,
            parallelism = 3,
            fetch = { _, _ -> fetched = true; ByteArray(0) },
            beforeFirstPiece = { firstRan = true },
            write = { error("nothing to write") }
        )
        assertThat(firstRan).isTrue()
        assertThat(fetched).isFalse()
    }

    @Test
    fun `a failed piece fails the stream and cancels the pieces still downloading`() {
        val slowPieceCancelled = AtomicBoolean(false)
        val written = AtomicInteger()
        assertThrows(IOException::class.java) {
            runBlocking(Dispatchers.Default) {
                ParallelPieces.stream(
                    from = 0,
                    to = 2_999,
                    pieceSize = 1_000,
                    parallelism = 3,
                    fetch = { start, _ ->
                        when (start) {
                            0L -> { delay(50); throw IOException("HTTP 503") }
                            else -> try {
                                awaitCancellation()
                            } catch (e: CancellationException) {
                                slowPieceCancelled.set(true)
                                throw e
                            }
                        }
                    },
                    write = { written.incrementAndGet() }
                )
            }
        }
        assertThat(slowPieceCancelled.get()).isTrue()
        assertThat(written.get()).isEqualTo(0)
    }
}
