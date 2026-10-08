package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FlacEncoderTest {
    @TempDir lateinit var directory: Path

    private fun encode(samples: ShortArray, channels: Int, rate: Int, chunk: Int = 1000): Pair<File, Long> {
        val file = directory.resolve("out.flac").toFile()
        val encoder = FlacEncoder(file, rate, channels)
        var offset = 0
        val frames = samples.size / channels
        while (offset < frames) {
            val take = minOf(chunk, frames - offset)
            encoder.write(samples.copyOfRange(offset * channels, (offset + take) * channels), take)
            offset += take
        }
        return file to encoder.finish()
    }

    private fun music(frames: Int, rate: Int, seed: Int = 7): ShortArray {
        val random = Random(seed)
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val tone = 9000 * sin(2 * PI * 220 * t) + 4000 * sin(2 * PI * 331 * t + 0.3)
            val l = tone + random.nextInt(-300, 300)
            val r = tone * 0.8 + 2500 * sin(2 * PI * 440 * t) + random.nextInt(-300, 300)
            out[2 * i] = l.toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = r.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    @Test fun `stereo music round-trips bit for bit with every CRC and the MD5 intact`() {
        // 3 s plus a partial last block, written in odd-sized chunks.
        val frames = 44_100 * 3 + 1_234
        val pcm = music(frames, 44_100)
        val (file, total) = encode(pcm, 2, 44_100, chunk = 777)
        assertEquals(frames.toLong(), total)
        val decoded = FlacTestDecoder(file.readBytes()).decode()
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(16, decoded.bitsPerSample)
        assertEquals(frames.toLong(), decoded.totalSamples)
        assertEquals(4096, decoded.maxBlockSize)
        assertTrue(decoded.minFrameSize in 1..decoded.maxFrameSize)
        assertArrayEquals(pcm, decoded.samples)
        // Real compression: well under 16-bit PCM.
        assertTrue(file.length() < frames * 4L * 9 / 10, "FLAC should be smaller than PCM (${file.length()} bytes)")
    }

    @Test fun `extremes, silence, full-scale noise and 48 kHz round-trip`() {
        val rate = 48_000
        val frames = 4096 * 2 + 17
        val random = Random(3)
        val pcm = ShortArray(frames * 2) { i ->
            when {
                i < 4096 * 2 -> 0 // digital silence: CONSTANT subframes
                i < 4096 * 4 -> random.nextInt(-32768, 32768).toShort().toInt() // noise: VERBATIM
                i % 4 == 0 -> Short.MAX_VALUE.toInt()
                else -> Short.MIN_VALUE.toInt()
            }.toShort()
        }
        val (file, total) = encode(pcm, 2, rate)
        assertEquals(frames.toLong(), total)
        val decoded = FlacTestDecoder(file.readBytes()).decode()
        assertEquals(rate, decoded.sampleRate)
        assertArrayEquals(pcm, decoded.samples)
    }

    @Test fun `mono, an unusual rate and a song shorter than one block`() {
        val rate = 37_800 // no frame code: "from STREAMINFO"
        val frames = 300
        val pcm = ShortArray(frames) { (sin(it / 5.0) * 20_000).toInt().toShort() }
        val (file, total) = encode(pcm, 1, rate)
        assertEquals(frames.toLong(), total)
        val decoded = FlacTestDecoder(file.readBytes()).decode()
        assertEquals(1, decoded.channels)
        assertEquals(rate, decoded.sampleRate)
        assertEquals(1, decoded.frameCount)
        assertArrayEquals(pcm, decoded.samples)
    }

    @Test fun `many frames exercise the multi-byte frame numbers`() {
        // > 128 frames needs the two-byte coded number; a short block size keeps the test fast.
        val frames = 4096 * 130 + 5
        val pcm = ShortArray(frames * 2) { ((it * 37) % 2000 - 1000).toShort() }
        val (file, _) = encode(pcm, 2, 44_100, chunk = 65_536)
        val decoded = FlacTestDecoder(file.readBytes()).decode()
        assertEquals(131, decoded.frameCount)
        assertArrayEquals(pcm, decoded.samples)
    }

    @Test fun `CRC helpers match the published check values`() {
        val check = "123456789".toByteArray()
        // CRC-8/SMBUS (poly 0x07, init 0) check value 0xF4; CRC-16/UMTS (poly 0x8005, init 0) check value 0xFEE8.
        assertEquals(0xF4, FlacEncoder.crc8(check, check.size))
        assertEquals(0xFEE8, FlacEncoder.crc16(check, check.size))
    }
}
