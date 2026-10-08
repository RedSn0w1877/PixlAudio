package com.theveloper.pixelplay.data.cloudstudio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A small streaming FLAC encoder for Cloud Studio uploads: 16-bit PCM, one or two channels, any sample rate, fixed
 * 4096-frame blocks, FIXED predictors (orders 0–4) with partitioned Rice coding, CONSTANT subframes for digital
 * silence, VERBATIM when nothing smaller exists, and the four stereo decorrelation modes. The STREAMINFO block
 * (total samples, frame sizes, MD5 of the PCM) is rewritten once the last frame is out, so ffprobe on the worker
 * sees the real duration.
 *
 * Why our own and not `MediaCodec`'s `audio/flac` encoder: every song the phone sends goes up as FLAC of the samples
 * its own decoder produced (design §7.1, owner decision for streamed songs), and the import's length check compares the
 * worker's decode of this file with the frame count recorded here. Platform FLAC encoders hand the stream header back
 * in different places (a codec-config buffer, `csd-0`, with or without the `fLaC` marker) and leave STREAMINFO's total
 * at "unknown"; this encoder is plain JVM code, so the unit tests decode its output bit for bit, and the worker's
 * ffmpeg reads it like any `flac` file. About 60 % of PCM on music, which is all the upload estimate assumes.
 *
 * Format reference: the FLAC format specification (RFC 9639). Not thread-safe; one encoder per file.
 */
class FlacEncoder(
    private val file: File,
    val sampleRate: Int,
    val channels: Int = 2,
) : Closeable {
    init {
        require(channels in 1..2) { "1 or 2 channels" }
        require(sampleRate in 1..655_350) { "Unsupported sample rate" }
    }

    private val output = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val md5 = MessageDigest.getInstance("MD5")
    private val md5Buffer = ByteArray(BLOCK_SIZE * 4)
    private val block = Array(channels) { IntArray(BLOCK_SIZE) }
    private var filled = 0
    private var frameNumber = 0L
    private var totalFrames = 0L
    private var minFrameBytes = Int.MAX_VALUE
    private var maxFrameBytes = 0
    private var finished = false
    private val bits = BitWriter(BLOCK_SIZE * channels * 3 + 64)

    // Scratch, reused for every block (no per-block allocation).
    private val mid = IntArray(BLOCK_SIZE)
    private val side = IntArray(BLOCK_SIZE)
    private val residual = IntArray(BLOCK_SIZE)
    private val folded = LongArray(BLOCK_SIZE)

    init {
        output.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43)) // "fLaC"
        output.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, STREAMINFO_LENGTH.toByte())) // last block, STREAMINFO, 34
        output.write(streamInfo(md5 = ByteArray(16)))
    }

    /** Frames written so far (per channel). */
    val framesWritten: Long get() = totalFrames + filled

    /** Adds [frames] interleaved frames from [samples] (`channels` shorts per frame). */
    fun write(samples: ShortArray, frames: Int) {
        check(!finished) { "Encoder already finished" }
        require(frames >= 0 && frames * channels <= samples.size) { "Not enough samples" }
        var index = 0
        var remaining = frames
        while (remaining > 0) {
            val take = minOf(remaining, BLOCK_SIZE - filled)
            for (f in 0 until take) {
                for (c in 0 until channels) block[c][filled + f] = samples[index++].toInt()
            }
            filled += take
            remaining -= take
            if (filled == BLOCK_SIZE) flushBlock()
        }
    }

    /** Encodes what is left, rewrites STREAMINFO and closes the file. Returns the total frames per channel. */
    fun finish(): Long {
        if (finished) return totalFrames
        if (filled > 0) flushBlock()
        output.flush()
        output.close()
        finished = true
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(8)
            raf.write(streamInfo(md5.digest()))
        }
        return totalFrames
    }

    /** Closes without finishing (the file is then incomplete; callers delete it). */
    override fun close() {
        if (!finished) {
            finished = true
            runCatching { output.close() }
        }
    }

    private fun streamInfo(md5: ByteArray): ByteArray {
        val w = BitWriter(STREAMINFO_LENGTH + 8)
        w.write(BLOCK_SIZE.toLong(), 16) // min block size (the last block may be shorter, as the format allows)
        w.write(BLOCK_SIZE.toLong(), 16) // max block size
        w.write(if (maxFrameBytes == 0) 0L else minFrameBytes.toLong(), 24)
        w.write(maxFrameBytes.toLong(), 24)
        w.write(sampleRate.toLong(), 20)
        w.write((channels - 1).toLong(), 3)
        w.write(15, 5) // 16 bits per sample
        w.write(totalFrames ushr 32, 4)
        w.write(totalFrames and 0xFFFF_FFFFL, 32)
        for (b in md5) w.write((b.toInt() and 0xFF).toLong(), 8)
        return w.toByteArray()
    }

    private fun updateMd5(n: Int) {
        var p = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val s = block[c][i]
                md5Buffer[p++] = s.toByte()
                md5Buffer[p++] = (s shr 8).toByte()
            }
        }
        md5.update(md5Buffer, 0, p)
    }

    private fun flushBlock() {
        val n = filled
        updateMd5(n)
        bits.reset()
        // Channel assignment: independent, left/side, right/side or mid/side, whichever is estimated smallest.
        var assignment = if (channels == 1) 0 else 1
        if (channels == 2) {
            val left = block[0]
            val right = block[1]
            for (i in 0 until n) {
                side[i] = left[i] - right[i]
                mid[i] = (left[i] + right[i]) shr 1
            }
            val l = estimateBits(left, n, 16)
            val r = estimateBits(right, n, 16)
            val s = estimateBits(side, n, 17)
            val m = estimateBits(mid, n, 16)
            val independent = l + r
            val leftSide = l + s
            val rightSide = s + r
            val midSide = m + s
            val best = minOf(minOf(independent, leftSide), minOf(rightSide, midSide))
            assignment = when (best) {
                independent -> 1
                leftSide -> 8
                rightSide -> 9
                else -> 10
            }
        }
        writeFrameHeader(n, assignment)
        when (assignment) {
            0 -> writeSubframe(block[0], n, 16)
            1 -> { writeSubframe(block[0], n, 16); writeSubframe(block[1], n, 16) }
            8 -> { writeSubframe(block[0], n, 16); writeSubframe(side, n, 17) }
            9 -> { writeSubframe(side, n, 17); writeSubframe(block[1], n, 16) }
            else -> { writeSubframe(mid, n, 16); writeSubframe(side, n, 17) }
        }
        bits.padToByte()
        val crc16 = crc16(bits.buffer, bits.length)
        bits.write(crc16.toLong(), 16)
        output.write(bits.buffer, 0, bits.length)
        minFrameBytes = minOf(minFrameBytes, bits.length)
        maxFrameBytes = maxOf(maxFrameBytes, bits.length)
        totalFrames += n
        frameNumber++
        filled = 0
    }

    private fun writeFrameHeader(n: Int, assignment: Int) {
        bits.write(0xFFF8, 16) // sync code, reserved 0, fixed block size
        val blockCode = when (n) {
            BLOCK_SIZE -> 12 // 256 × 2^(12−8)
            in 1..256 -> 6 // 8-bit (n − 1) follows
            else -> 7 // 16-bit (n − 1) follows
        }
        val rateCode = SAMPLE_RATE_CODES[sampleRate] ?: 0 // 0 = "from STREAMINFO"
        bits.write(blockCode.toLong(), 4)
        bits.write(rateCode.toLong(), 4)
        bits.write(assignment.toLong(), 4)
        bits.write(4, 3) // 16 bits per sample
        bits.write(0, 1)
        writeCodedNumber(frameNumber)
        if (blockCode == 6) bits.write((n - 1).toLong(), 8)
        if (blockCode == 7) bits.write((n - 1).toLong(), 16)
        val crc8 = crc8(bits.buffer, bits.length)
        bits.write(crc8.toLong(), 8)
    }

    /** The frame number in FLAC's UTF-8-like coding. */
    private fun writeCodedNumber(value: Long) {
        when {
            value < 0x80 -> bits.write(value, 8)
            else -> {
                val bytes = when {
                    value < 0x800 -> 2
                    value < 0x10000 -> 3
                    value < 0x200000 -> 4
                    value < 0x4000000 -> 5
                    else -> 6
                }
                val leading = (0xFF shl (8 - bytes)) and 0xFF
                val firstBits = 7 - bytes
                bits.write(leading.toLong() or (value ushr (6 * (bytes - 1))), 8)
                for (i in bytes - 2 downTo 0) bits.write(0x80L or ((value ushr (6 * i)) and 0x3F), 8)
                check(value ushr (6 * (bytes - 1)) < (1L shl firstBits)) { "Frame number too large" }
            }
        }
    }

    /** Bits a channel would take with its best FIXED order (or VERBATIM), for the stereo-mode choice. */
    private fun estimateBits(x: IntArray, n: Int, bps: Int): Long {
        if (isConstant(x, n)) return bps.toLong()
        var best = n.toLong() * bps
        for (order in 0..minOf(MAX_FIXED_ORDER, n - 1)) {
            fixedResidual(x, n, order)
            val count = n - order
            var sum = 0L
            for (i in 0 until count) sum += fold(residual[i])
            val k = riceParameter(sum, count)
            val estimate = order.toLong() * bps + count.toLong() * (k + 1) + (sum ushr k) + 6
            if (estimate < best) best = estimate
        }
        return best
    }

    private fun writeSubframe(x: IntArray, n: Int, bps: Int) {
        if (isConstant(x, n)) {
            bits.write(0, 8) // pad 0, type CONSTANT (000000), no wasted bits
            bits.writeSigned(x[0], bps)
            return
        }
        // Pick the order with the smallest residual (sum of folded values is a close proxy for the Rice size).
        var bestOrder = 0
        var bestSum = Long.MAX_VALUE
        for (order in 0..minOf(MAX_FIXED_ORDER, n - 1)) {
            fixedResidual(x, n, order)
            var sum = 0L
            for (i in 0 until n - order) sum += fold(residual[i])
            if (sum < bestSum) {
                bestSum = sum
                bestOrder = order
            }
        }
        fixedResidual(x, n, bestOrder)
        val count = n - bestOrder
        for (i in 0 until count) folded[i] = fold(residual[i])
        val (partitionOrder, riceBits) = bestPartitioning(n, bestOrder)
        val fixedBits = bestOrder.toLong() * bps + 6 + riceBits
        val verbatimBits = n.toLong() * bps
        if (fixedBits >= verbatimBits) {
            bits.write(0b0000_0010, 8) // pad 0, type VERBATIM (000001), no wasted bits
            for (i in 0 until n) bits.writeSigned(x[i], bps)
            return
        }
        bits.write(0, 1)
        bits.write((0b001000 or bestOrder).toLong(), 6) // FIXED, order in the low 3 bits
        bits.write(0, 1)
        for (i in 0 until bestOrder) bits.writeSigned(x[i], bps)
        bits.write(0, 2) // residual coding: Rice, 4-bit parameters
        bits.write(partitionOrder.toLong(), 4)
        val partitions = 1 shl partitionOrder
        val perPartition = n shr partitionOrder
        var offset = 0
        for (p in 0 until partitions) {
            val count = if (p == 0) perPartition - bestOrder else perPartition
            var sum = 0L
            for (i in offset until offset + count) sum += folded[i]
            val k = riceParameter(sum, count)
            bits.write(k.toLong(), 4)
            for (i in offset until offset + count) {
                val u = folded[i]
                bits.writeZeros((u ushr k).toInt())
                bits.write(1, 1)
                if (k > 0) bits.write(u and ((1L shl k) - 1), k)
            }
            offset += count
        }
    }

    /** The partition order (0–6) with the smallest Rice size, and that size in bits. */
    private fun bestPartitioning(n: Int, order: Int): Pair<Int, Long> {
        var bestOrder = 0
        var bestBits = Long.MAX_VALUE
        for (partitionOrder in 0..MAX_PARTITION_ORDER) {
            val partitions = 1 shl partitionOrder
            if (n % partitions != 0) break
            val perPartition = n shr partitionOrder
            if (perPartition <= order) break
            var total = 0L
            var offset = 0
            for (p in 0 until partitions) {
                val count = if (p == 0) perPartition - order else perPartition
                var sum = 0L
                for (i in offset until offset + count) sum += folded[i]
                val k = riceParameter(sum, count)
                var size = 4L + count // parameter + one stop bit per value
                if (k > 0) size += count.toLong() * k
                for (i in offset until offset + count) size += folded[i] ushr k
                total += size
                offset += count
            }
            if (total < bestBits) {
                bestBits = total
                bestOrder = partitionOrder
            }
        }
        return bestOrder to bestBits
    }

    private fun fixedResidual(x: IntArray, n: Int, order: Int) {
        when (order) {
            0 -> for (i in 0 until n) residual[i] = x[i]
            1 -> for (i in 1 until n) residual[i - 1] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) residual[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) residual[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) residual[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    private fun isConstant(x: IntArray, n: Int): Boolean {
        val first = x[0]
        for (i in 1 until n) if (x[i] != first) return false
        return true
    }

    companion object {
        const val BLOCK_SIZE = 4096
        private const val STREAMINFO_LENGTH = 34
        private const val MAX_FIXED_ORDER = 4
        private const val MAX_PARTITION_ORDER = 6
        /** The largest parameter a 4-bit Rice field holds (15 is the escape code). */
        private const val MAX_RICE_PARAMETER = 14

        private val SAMPLE_RATE_CODES = mapOf(
            88_200 to 1, 176_400 to 2, 192_000 to 3, 8_000 to 4, 16_000 to 5, 22_050 to 6, 24_000 to 7,
            32_000 to 8, 44_100 to 9, 48_000 to 10, 96_000 to 11,
        )

        /** Zig-zag fold: 0, −1, 1, −2, 2 … → 0, 1, 2, 3, 4 … */
        internal fun fold(value: Int): Long = if (value >= 0) value.toLong() shl 1 else ((-value.toLong()) shl 1) - 1

        /** The Rice parameter for values whose sum is [sum] over [count] values. */
        internal fun riceParameter(sum: Long, count: Int): Int {
            if (count <= 0 || sum <= 0) return 0
            var k = 0
            while (k < MAX_RICE_PARAMETER && (count.toLong() shl (k + 1)) < sum) k++
            return k
        }

        private val CRC8_TABLE = IntArray(256) { index ->
            var crc = index
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            crc
        }
        private val CRC16_TABLE = IntArray(256) { index ->
            var crc = index shl 8
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
            crc
        }

        /** CRC-8, polynomial x⁸ + x² + x + 1, initial 0 (the frame header's check). */
        internal fun crc8(data: ByteArray, length: Int): Int {
            var crc = 0
            for (i in 0 until length) crc = CRC8_TABLE[crc xor (data[i].toInt() and 0xFF)]
            return crc
        }

        /** CRC-16, polynomial x¹⁶ + x¹⁵ + x² + 1, initial 0 (the whole frame's check). */
        internal fun crc16(data: ByteArray, length: Int): Int {
            var crc = 0
            for (i in 0 until length) crc = ((crc shl 8) and 0xFFFF) xor CRC16_TABLE[(crc ushr 8) xor (data[i].toInt() and 0xFF)]
            return crc
        }
    }
}

/** MSB-first bit writer over a growable byte buffer. */
internal class BitWriter(initialCapacity: Int) {
    var buffer = ByteArray(initialCapacity)
        private set
    var length = 0
        private set
    private var accumulator = 0L
    private var pending = 0

    fun reset() {
        length = 0
        accumulator = 0
        pending = 0
    }

    /** Writes the low [count] bits (0–32) of [value]. */
    fun write(value: Long, count: Int) {
        if (count == 0) return
        accumulator = (accumulator shl count) or (value and ((1L shl count) - 1))
        pending += count
        while (pending >= 8) {
            pending -= 8
            put(((accumulator ushr pending) and 0xFF).toInt())
        }
    }

    fun write(value: Int, count: Int) = write(value.toLong(), count)

    /** Two's complement in [count] bits. */
    fun writeSigned(value: Int, count: Int) = write(value.toLong() and ((1L shl count) - 1), count)

    fun writeZeros(count: Int) {
        var left = count
        while (left >= 32) {
            write(0L, 32)
            left -= 32
        }
        if (left > 0) write(0L, left)
    }

    fun padToByte() {
        if (pending > 0) write(0L, 8 - pending)
    }

    fun toByteArray(): ByteArray {
        padToByte()
        return buffer.copyOf(length)
    }

    private fun put(byte: Int) {
        if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[length++] = byte.toByte()
    }
}
