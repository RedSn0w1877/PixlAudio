package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** At most one speculative lookup; a queue change cancels the obsolete one. */
internal class StreamUrlPrefetcher<K : Any>(
    private val scope: CoroutineScope,
    private val resolve: suspend (K) -> Unit,
    private val onFailure: (Exception) -> Unit = {}
) {
    private var requestedId: K? = null
    private var job: Job? = null

    @Synchronized
    fun prefetch(id: K?) {
        if (id != null && id == requestedId && job?.isActive == true) return
        job?.cancel()
        requestedId = id
        job = if (id == null) null else scope.launch {
            try {
                resolve(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Preparation is optional. A failed attempt must not interrupt playback
                // or prevent the foreground request from trying again.
                onFailure(e)
            }
        }
    }
}
