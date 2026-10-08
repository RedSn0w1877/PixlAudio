package com.theveloper.pixelplay.data.stream

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.InputStream

/**
 * Copies an upstream audio body into the local proxy's response, handing every piece to
 * ExoPlayer as soon as it arrives.
 *
 * Streaming speed R2 (the Android form of iOS's "small first chunk"). Ktor CIO creates each
 * response body as `ByteChannel(autoFlush = false)`, and `writeFully` only flushes once
 * 1 MiB is pending (`flushIfNeeded`, checked in the Ktor 3.5.0 and 3.6.0 bytecode). Without
 * an explicit [ByteWriteChannel.flush], ExoPlayer's first read therefore waited for about
 * 1 MiB: two whole 512,000-byte googlevideo requests and part of a third before any audio,
 * on every uncached start and every seek into audio that wasn't buffered yet.
 *
 * Flushing after each 64 KiB write keeps backpressure as it was: `flush` still suspends while
 * the reader has 1 MiB unread, so a paused player still stops the upstream reads.
 */
internal object ProxyBodyWriter {
    const val BUFFER_SIZE = 64 * 1024

    /**
     * Copies at most [limit] bytes (all of [input] when null) and returns how many were
     * written. [onWritten] runs after each piece has been flushed to the reader.
     */
    suspend fun copyUpstream(
        input: InputStream,
        channel: ByteWriteChannel,
        limit: Long?,
        onWritten: (Int) -> Unit = {}
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        while (limit == null || written < limit) {
            val wanted = if (limit == null) buffer.size else minOf(buffer.size.toLong(), limit - written).toInt()
            val count = input.read(buffer, 0, wanted)
            if (count < 0) break
            if (count == 0) continue
            channel.writeFully(buffer, 0, count)
            channel.flush()
            written += count
            onWritten(count)
        }
        return written
    }
}
