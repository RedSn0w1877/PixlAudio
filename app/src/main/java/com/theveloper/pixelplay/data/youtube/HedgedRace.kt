package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streaming speed R8: run strategies in order, overlapping them when one is slow.
 *
 * Strategy 0 starts at once. Strategy i+1 starts as soon as the newest one fails, or when the
 * newest one has run [afterMs] without an answer. Each strategy gets [strategyTimeoutMs]. The
 * first non-null result wins and every other strategy is cancelled (the InnerTube calls go
 * through awaitYouTubeResponse, which cancels the OkHttp call). Null when all of them fail.
 *
 * Pure coroutine logic, tested with virtual time (HedgedRaceTest).
 */
internal object HedgedRace {

    data class Winner<T>(val index: Int, val value: T)

    private sealed interface Event<out T> {
        data class Done<T>(val index: Int, val value: T?) : Event<T>
        data class Timer(val index: Int) : Event<Nothing>
    }

    /**
     * @param onFinished called in completion order (from the race's own coroutine) for every
     *   strategy that finished before the winner was chosen, the winner included.
     */
    suspend fun <T : Any> race(
        count: Int,
        afterMs: Long,
        strategyTimeoutMs: Long,
        attempt: suspend (index: Int) -> T?,
        onFinished: (index: Int, value: T?) -> Unit = { _, _ -> }
    ): Winner<T>? {
        if (count <= 0) return null
        return coroutineScope {
            val events = Channel<Event<T>>(Channel.UNLIMITED)
            var started = 0
            var finished = 0
            val done = BooleanArray(count)

            fun startNext() {
                val index = started++
                launch {
                    val value = try {
                        withTimeoutOrNull(strategyTimeoutMs) { attempt(index) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    events.send(Event.Done(index, value))
                }
                if (started < count) {
                    launch {
                        delay(afterMs)
                        events.send(Event.Timer(index))
                    }
                }
            }

            startNext()
            var winner: Winner<T>? = null
            while (finished < count && winner == null) {
                when (val event = events.receive()) {
                    is Event.Timer -> {
                        // Only the newest strategy's timer counts, and only while it still runs.
                        if (event.index == started - 1 && !done[event.index] && started < count) startNext()
                    }
                    is Event.Done -> {
                        done[event.index] = true
                        finished++
                        onFinished(event.index, event.value)
                        val value = event.value
                        if (value != null) {
                            winner = Winner(event.index, value)
                        } else if (event.index == started - 1 && started < count) {
                            // The newest one failed: don't wait for its timer.
                            startNext()
                        }
                    }
                }
            }
            coroutineContext.cancelChildren()
            winner
        }
    }
}
