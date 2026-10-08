package com.theveloper.pixelplay.data.cloudstudio

import java.security.MessageDigest

/**
 * A strict FLAC reader for the subset [FlacEncoder] writes (STREAMINFO, fixed-size frames, CONSTANT / VERBATIM /
 * FIXED subframes, Rice residuals, the four stereo modes). Written from the format specification, independently of
 * the encoder's code paths, so a round trip proves the bitstream rather than the encoder agreeing with itself. Every
 * CRC-8, CRC-16 and the STREAMINFO MD5 are checked.
 */
internal class FlacTestDecoder(private val data: ByteArray) {
    class Decoded(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val minBlockSize: Int,
        val maxBlockSize: Int,
        val minFrameSize: Int,
        val maxFrameSize: Int,
        val md5: ByteArray,
        /** Interleaved samples. */
        val samples: ShortArray,
        val frameCount: Int,
    )

    private var pos = 0 // bit position

    fun decode(): Decoded {
        require(data.size >= 42 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC") { "no fLaC marker" }
        pos = 32
        var last = false
        var streamInfo: IntArray? = null
        var total = 0L
        var md5 = ByteArray(16)
        while (!last) {
            last = bits(1) == 1L
            val type = bits(7).toInt()
            val length = bits(24).toInt()
            if (type == 0) {
                val minBlock = bits(16).toInt()
                val maxBlock = bits(16).toInt()
                val minFrame = bits(24).toInt()
                val maxFrame = bits(24).toInt()
                val rate = bits(20).toInt()
                val channels = bits(3).toInt() + 1
                val bps = bits(5).toInt() + 1
                total = bits(36)
                md5 = ByteArray(16) { bits(8).toByte() }
                streamInfo = intArrayOf(minBlock, maxBlock, minFrame, maxFrame, rate, channels, bps)
                require(length == 34) { "STREAMINFO length $length" }
            } else {
                pos += length * 8
            }
        }
        val info = requireNotNull(streamInfo) { "no STREAMINFO" }
        val channels = info[5]
        val out = ShortArrayBuilder()
        var frames = 0
        var expectedFrameNumber = 0L
        while (pos / 8 < data.size) {
            require(pos % 8 == 0)
            val frameStart = pos / 8
            require(bits(14) == 0x3FFEL) { "lost sync at byte $frameStart" }
            require(bits(1) == 0L) { "reserved bit set" }
            require(bits(1) == 0L) { "variable block size not expected" }
            val blockCode = bits(4).toInt()
            val rateCode = bits(4).toInt()
            val assignment = bits(4).toInt()
            val sizeCode = bits(3).toInt()
            require(bits(1) == 0L)
            val frameNumber = codedNumber()
            require(frameNumber == expectedFrameNumber) { "frame number $frameNumber, expected $expectedFrameNumber" }
            expectedFrameNumber++
            val blockSize = when (blockCode) {
                1 -> 192
                in 2..5 -> 576 shl (blockCode - 2)
                6 -> bits(8).toInt() + 1
                7 -> bits(16).toInt() + 1
                in 8..15 -> 256 shl (blockCode - 8)
                else -> error("reserved block size code")
            }
            when (rateCode) {
                12 -> bits(8)
                13, 14 -> bits(16)
                15 -> error("invalid sample rate code")
            }
            require(sizeCode == 4 || sizeCode == 0) { "expected 16-bit samples" }
            val headerEnd = pos / 8
            val crc8 = bits(8).toInt()
            require(crc8 == crc8(data, frameStart, headerEnd)) { "header CRC-8 mismatch" }
            val subframes = when (assignment) {
                in 0..7 -> List(assignment + 1) { subframe(blockSize, 16) }
                8 -> listOf(subframe(blockSize, 16), subframe(blockSize, 17))
                9 -> listOf(subframe(blockSize, 17), subframe(blockSize, 16))
                10 -> listOf(subframe(blockSize, 16), subframe(blockSize, 17))
                else -> error("reserved channel assignment")
            }
            require(subframes.size == channels) { "channel count mismatch" }
            pos = (pos + 7) / 8 * 8
            val frameEnd = pos / 8
            val crc16 = bits(16).toInt()
            require(crc16 == crc16(data, frameStart, frameEnd)) { "frame CRC-16 mismatch" }
            for (i in 0 until blockSize) {
                val (l, r) = when (assignment) {
                    8 -> subframes[0][i] to subframes[0][i] - subframes[1][i]
                    9 -> subframes[0][i] + subframes[1][i] to subframes[1][i]
                    10 -> {
                        val side = subframes[1][i]
                        val mid = (subframes[0][i] shl 1) or (side and 1)
                        ((mid + side) shr 1) to ((mid - side) shr 1)
                    }
                    else -> subframes[0][i] to (if (channels > 1) subframes[1][i] else 0)
                }
                out.add(l.toShort())
                if (channels > 1) out.add(r.toShort())
            }
            frames++
        }
        val samples = out.toArray()
        val digest = MessageDigest.getInstance("MD5")
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[2 * i] = s.toInt().toByte()
            bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        val actualMd5 = digest.digest(bytes)
        require(actualMd5.contentEquals(md5)) { "MD5 mismatch" }
        return Decoded(info[4], channels, info[6], total, info[0], info[1], info[2], info[3], md5, samples, frames)
    }

    private fun subframe(n: Int, bps: Int): IntArray {
        require(bits(1) == 0L) { "subframe padding bit" }
        val type = bits(6).toInt()
        require(bits(1) == 0L) { "wasted bits not expected" }
        val x = IntArray(n)
        when {
            type == 0 -> {
                val v = signed(bps)
                x.fill(v)
            }
            type == 1 -> for (i in 0 until n) x[i] = signed(bps)
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) x[i] = signed(bps)
                val residual = residual(n, order)
                for (i in order until n) {
                    val e = residual[i - order]
                    x[i] = when (order) {
                        0 -> e
                        1 -> e + x[i - 1]
                        2 -> e + 2 * x[i - 1] - x[i - 2]
                        3 -> e + 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                        else -> e + 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
                    }
                }
            }
            else -> error("unexpected subframe type $type")
        }
        return x
    }

    private fun residual(n: Int, order: Int): IntArray {
        val method = bits(2).toInt()
        require(method == 0) { "expected 4-bit Rice parameters" }
        val partitionOrder = bits(4).toInt()
        val partitions = 1 shl partitionOrder
        val out = IntArray(n - order)
        var index = 0
        for (p in 0 until partitions) {
            val count = (n shr partitionOrder) - if (p == 0) order else 0
            val k = bits(4).toInt()
            require(k != 15) { "escape partitions not expected" }
            repeat(count) {
                var q = 0L
                while (bits(1) == 0L) q++
                val u = (q shl k) or (if (k > 0) bits(k) else 0L)
                out[index++] = if (u and 1L == 0L) (u ushr 1).toInt() else -((u ushr 1).toInt()) - 1
            }
        }
        require(index == n - order)
        return out
    }

    private fun signed(count: Int): Int {
        val raw = bits(count)
        val sign = 1L shl (count - 1)
        return (if (raw and sign != 0L) raw - (1L shl count) else raw).toInt()
    }

    private fun codedNumber(): Long {
        val first = bits(8).toInt()
        if (first and 0x80 == 0) return first.toLong()
        var extra = 0
        var mask = 0x40
        while (first and mask != 0) {
            extra++
            mask = mask shr 1
        }
        var value = (first and (mask - 1)).toLong()
        repeat(extra) {
            val next = bits(8).toInt()
            require(next and 0xC0 == 0x80) { "bad coded number" }
            value = (value shl 6) or (next and 0x3F).toLong()
        }
        return value
    }

    private fun bits(count: Int): Long {
        var value = 0L
        repeat(count) {
            val byte = data[pos ushr 3].toInt()
            val bit = (byte shr (7 - (pos and 7))) and 1
            value = (value shl 1) or bit.toLong()
            pos++
        }
        return value
    }

    private class ShortArrayBuilder {
        private var array = ShortArray(1 shl 16)
        private var size = 0
        fun add(value: Short) {
            if (size == array.size) array = array.copyOf(size * 2)
            array[size++] = value
        }
        fun toArray(): ShortArray = array.copyOf(size)
    }

    companion object {
        fun crc8(data: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc
        }

        fun crc16(data: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
            }
            return crc
        }
    }
}
