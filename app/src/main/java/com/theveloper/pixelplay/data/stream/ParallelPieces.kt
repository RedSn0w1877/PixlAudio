package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Downloads `from..to` (inclusive) as pieces of [pieceSize] bytes, at most `parallelism` at a time,
 * and hands them to `write` strictly in order. [CloudStreamProxy] uses it for hosts that cap each
 * connection below what the audio needs (SpotiFLAC's FLAC streamer).
 *
 * `beforeFirstPiece` runs once the first downloads are under way: the proxy streams its already-open
 * first response there, so the player's first bytes never wait for the parallel pieces. A failed
 * piece fails the whole call and cancels the others; at most `parallelism` pieces are ever held.
 */
internal object ParallelPieces {

    suspend fun stream(
        from: Long,
        to: Long,
        pieceSize: Long,
        parallelism: Int,
        fetch: suspend (start: Long, end: Long) -> ByteArray,
        beforeFirstPiece: suspend () -> Unit = {},
        write: suspend (ByteArray) -> Unit
    ): Unit = coroutineScope {
        require(pieceSize > 0) { "pieceSize must be positive" }
        require(parallelism >= 1) { "parallelism must be at least 1" }

        val window = ArrayDeque<Deferred<ByteArray>>(parallelism)
        var next = from
        fun launchNext() {
            val start = next
            val end = minOf(start + pieceSize - 1, to)
            next = end + 1
            window.addLast(async { fetch(start, end) })
        }

        while (window.size < parallelism && next <= to) launchNext()
        beforeFirstPiece()
        while (window.isNotEmpty()) {
            val bytes = window.removeFirst().await()
            if (next <= to) launchNext()
            write(bytes)
        }
    }
}
