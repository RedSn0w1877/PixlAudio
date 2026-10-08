package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * At most one speculative job, preparing the upcoming songs in skip order (streaming speed R3:
 * the next 1 on cellular, the next 2 on Wi-Fi). A queue change cancels the obsolete job; asking
 * for the same list again while it runs is a no-op.
 */
internal class StreamUrlPrefetcher<K : Any>(
    private val scope: CoroutineScope,
    private val resolve: suspend (K) -> Unit,
    private val onFailure: (Exception) -> Unit = {}
) {
    private var requestedIds: List<K> = emptyList()
    private var job: Job? = null

    /** One song, or none (null cancels). */
    fun prefetch(id: K?) = prefetch(listOfNotNull(id))

    /** These songs, one after another and in order (the next song first); empty cancels. */
    @Synchronized
    fun prefetch(ids: List<K>) {
        if (ids.isNotEmpty() && ids == requestedIds && job?.isActive == true) return
        job?.cancel()
        requestedIds = ids
        job = if (ids.isEmpty()) null else scope.launch {
            for (id in ids) {
                try {
                    resolve(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Preparation is optional. A failed attempt must not interrupt playback,
                    // prevent the foreground request from trying again, or skip the next song.
                    onFailure(e)
                }
            }
        }
    }
}
