package com.theveloper.pixelplay.data.stream

/**
 * When the listening cache (the full-song download for offline replays) may take the song that
 * is playing. Owner decision 2026-10-07: only a song actually heard for
 * [StreamPrefetchPolicy.LISTENING_CACHE_DELAY_MS], so a skimmed song is never downloaded.
 *
 * Pure bookkeeping for MusicService, which runs the wait and the download (main thread only).
 * The rule it guards: a wait belongs to the song that started it. A pause, a local song or a
 * change to another song drops it, including a skip whose next song is still buffering (the
 * old check returned early while nothing played, so the skimmed song's wait ran out and it was
 * downloaded in full, on cellular too). A rebuffer of the same song keeps it.
 */
internal class ListeningCacheGate {

    /** What MusicService does after [update]. */
    data class Decision(
        /** Drop the wait that is still running (it belongs to another song, or to a pause). */
        val cancelPending: Boolean,
        /** Start waiting for this song, or null. */
        val startFor: String?
    )

    // The song a wait was started for; it stays after the wait ran out, so one play asks once.
    private var songId: String? = null
    private var waiting = false

    /** The player now has [currentId] (null: paused, stopped or not a streamed song). */
    fun update(currentId: String?, isPlaying: Boolean): Decision {
        val cancel = waiting && currentId != songId
        if (cancel) {
            waiting = false
            songId = null
        }
        val start = currentId != null && isPlaying && currentId != songId
        if (start) {
            songId = currentId
            waiting = true
        }
        return Decision(cancelPending = cancel, startFor = if (start) currentId else null)
    }

    /** The wait for [id] ran out (whether or not the download was allowed). */
    fun waitFinished(id: String) {
        if (id == songId) waiting = false
    }
}
