package com.theveloper.pixelplay.data.spotiflac

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioFileFactsTest {

    /** `fLaC` + a STREAMINFO block laid out as in the FLAC spec (rate 20 bits, channels-1 3, bits-1 5). */
    private fun flacHead(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val head = ByteArray(42)
        "fLaC".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        head[7] = 34 // block type 0 (STREAMINFO), length 34
        val packed = (sampleRate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
            ((bitDepth - 1).toLong() shl 36) or 12_345_678L // + total samples
        for (i in 0 until 8) head[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        return head
    }

    @Test
    fun `FLAC headers give the sample rate, bit depth and channels`() {
        assertEquals(
            AudioFileFacts(AudioFileFacts.Format.FLAC, 44_100, 16, 2),
            AudioFileFacts.parse(flacHead(44_100, 2, 16))
        )
        val studio = AudioFileFacts.parse(flacHead(96_000, 2, 24))
        assertEquals(96_000, studio.flacSampleRateHz)
        assertEquals(24, studio.bitDepth)
        assertEquals(176_400, AudioFileFacts.parse(flacHead(176_400, 2, 24)).sampleRateHz)
        assertEquals("audio/flac", studio.format.mimeType)
    }

    @Test
    fun `other containers are recognised and never report a FLAC rate`() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray(Charsets.US_ASCII)
        assertEquals(AudioFileFacts.Format.MP4, AudioFileFacts.parse(mp4).format)
        assertNull(AudioFileFacts.parse(mp4).flacSampleRateHz)
        assertEquals(AudioFileFacts.Format.MP3, AudioFileFacts.parse("ID3".toByteArray() + ByteArray(10)).format)
        assertEquals(AudioFileFacts.Format.MP3, AudioFileFacts.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())).format)
        assertEquals(AudioFileFacts.Format.AAC, AudioFileFacts.parse(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50)).format)
        assertEquals(AudioFileFacts.Format.OGG, AudioFileFacts.parse("OggS".toByteArray()).format)
        assertEquals(AudioFileFacts.Format.UNKNOWN, AudioFileFacts.parse("<!doctype html>".toByteArray()).format)
        assertEquals(AudioFileFacts.Format.UNKNOWN, AudioFileFacts.parse("fLa".toByteArray()).format)
        // A truncated FLAC head is still FLAC, just without a rate (counted as allowed by every tier).
        val short = AudioFileFacts.parse("fLaC".toByteArray())
        assertEquals(AudioFileFacts.Format.FLAC, short.format)
        assertNull(short.flacSampleRateHz)
    }
}
