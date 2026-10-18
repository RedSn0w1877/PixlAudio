package com.theveloper.pixelplay.data.haptics

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Test

class BeatDetectorTest {

    private class Counter : BeatDetector.Listener {
        var kicks = 0
        var snares = 0
        var maxBass = 0f
        override fun onKick(strength: Float) { kicks++ }
        override fun onSnare(strength: Float) { snares++ }
        override fun onBass(level: Float) { if (level > maxBass) maxBass = level }
    }

    private val rate = 44_100

    private fun detector(counter: Counter) = BeatDetector(counter).also { it.configure(rate) }

    /** [bursts] bursts of [burstMs], one every [periodMs]; [wave] gives the burst waveform at sample n. */
    private fun feed(
        d: BeatDetector,
        seconds: Double,
        periodMs: Int,
        burstMs: Int,
        wave: (Int) -> Float,
    ) {
        val total = (seconds * rate).toInt()
        val period = periodMs * rate / 1000
        val burst = burstMs * rate / 1000
        for (n in 0 until total) {
            val inBurst = (n % period) < burst
            d.processSample(if (inBurst) wave(n) else 0f)
        }
    }

    @Test
    fun silenceProducesNothing() {
        val c = Counter()
        val d = detector(c)
        repeat(rate * 2) { d.processSample(0f) }
        assertThat(c.kicks).isEqualTo(0)
        assertThat(c.snares).isEqualTo(0)
    }

    @Test
    fun lowFrequencyBurstsAreKicksNotSnares() {
        val c = Counter()
        val d = detector(c)
        feed(d, seconds = 4.0, periodMs = 500, burstMs = 60) { n -> (0.8 * sin(2 * PI * 60 * n / rate)).toFloat() }
        assertThat(c.kicks).isIn(7..8)
        assertThat(c.snares).isEqualTo(0)
        assertThat(c.maxBass).isGreaterThan(0.2f)
    }

    @Test
    fun noiseBurstsAreSnaresNotKicks() {
        val c = Counter()
        val d = detector(c)
        var seed = 12345L
        feed(d, seconds = 4.0, periodMs = 500, burstMs = 60) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            ((((seed ushr 33).toInt() % 2000) - 1000) / 1250f)
        }
        assertThat(c.snares).isIn(7..8)
        assertThat(c.kicks).isEqualTo(0)
    }

    @Test
    fun steadyToneDoesNotRetrigger() {
        val c = Counter()
        val d = detector(c)
        repeat(rate * 3) { n -> d.processSample((0.6 * sin(2 * PI * 60 * n / rate)).toFloat()) }
        assertThat(c.kicks).isAtMost(7)
        // After the opening swell the level is steady: no further onsets in the last two seconds.
        val before = c.kicks
        repeat(rate * 2) { n -> d.processSample((0.6 * sin(2 * PI * 60 * n / rate)).toFloat()) }
        assertThat(c.kicks).isEqualTo(before)
    }

    @Test
    fun kicksAreSpacedAtLeastTwelveHops() {
        val c = Counter()
        val d = detector(c)
        // Bursts 60 ms apart would be 6 hops: the detector must still not fire faster than ~8 per second.
        feed(d, seconds = 2.0, periodMs = 60, burstMs = 30) { n -> (0.8 * sin(2 * PI * 60 * n / rate)).toFloat() }
        assertThat(c.kicks).isAtMost(17)
    }

    @Test
    fun readsPcm16BuffersWithoutConsumingThem() {
        val c = Counter()
        val d = detector(c)
        val frames = rate / 2
        val buffer = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) {
            val v: Short = if (n > rate / 10) (0.8 * 32767 * sin(2 * PI * 60 * n / rate)).toInt().toShort() else 0
            buffer.putShort(v)
            buffer.putShort(v)
        }
        buffer.flip()
        val position = buffer.position()
        val limit = buffer.limit()
        d.processPcm16(buffer, channels = 2)
        assertThat(buffer.position()).isEqualTo(position)
        assertThat(buffer.limit()).isEqualTo(limit)
        assertThat(c.kicks).isAtLeast(1)
    }

    @Test
    fun reconfigureClearsState() {
        val c = Counter()
        val d = detector(c)
        feed(d, seconds = 1.0, periodMs = 500, burstMs = 60) { n -> (0.8 * sin(2 * PI * 60 * n / rate)).toFloat() }
        val first = c.kicks
        d.configure(48_000)
        feed(d, seconds = 1.0, periodMs = 500, burstMs = 60) { n -> (0.8 * sin(2 * PI * 60 * n / 48_000)).toFloat() }
        assertThat(c.kicks).isGreaterThan(first)
    }
}
