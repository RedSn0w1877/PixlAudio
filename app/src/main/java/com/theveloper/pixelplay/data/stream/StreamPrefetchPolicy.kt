package com.theveloper.pixelplay.data.stream

import androidx.media3.common.C

/**
 * Streaming speed R3: how far ahead the app prepares upcoming streamed songs, and when it may
 * download whole songs for the listening cache. Pure rules (unit-tested); MusicService applies
 * them with [StreamNetworkConditions].
 *
 * Owner decisions (2026-10-07, same as iOS PixlNet `StreamPrefetchPolicy`):
 * - while music plays, prepare the next 2 songs on Wi-Fi / Ethernet, the next 1 on cellular
 *   (or a metered Wi-Fi such as a hotspot), none under Data Saver, offline, or while paused;
 * - none while a Spotify Connect device plays: the phone isn't the one playing.
 *
 * "Prepare" on Android means: match the Spotify song and resolve its stream URL (both songs),
 * plus ExoPlayer's own preload of the next song's first seconds (depth >= 1). See
 * handoff/2026-10-08-android-streaming.md for why the second song gets no audio bytes.
 */
object StreamPrefetchPolicy {

    enum class Network { WIFI, ETHERNET, CELLULAR, OTHER, OFFLINE }

    data class Conditions(
        val network: Network,
        /** Neither NOT_METERED nor TEMPORARILY_NOT_METERED (cellular, hotspots, capped Wi-Fi). */
        val metered: Boolean,
        /** Android's Data Saver is on for this app (RESTRICT_BACKGROUND_STATUS_ENABLED). */
        val dataSaver: Boolean
    )

    /** The most songs prepared ahead. */
    const val MAX_DEPTH = 2

    /** Matching and resolving start this long after playback starts or the queue changes. */
    const val SETTLE_DELAY_MS = 1_500L

    /** The playing song joins the listening cache after this much playback (not on a skim). */
    const val LISTENING_CACHE_DELAY_MS = 5_000L

    fun depth(conditions: Conditions, isPlaying: Boolean, isConnectSession: Boolean): Int {
        if (!isPlaying || isConnectSession || conditions.dataSaver) return 0
        return when (conditions.network) {
            Network.OFFLINE -> 0
            Network.WIFI, Network.ETHERNET -> if (conditions.metered) 1 else MAX_DEPTH
            Network.CELLULAR, Network.OTHER -> 1
        }
    }

    /**
     * Whether the song that is playing may be downloaded in full for the listening cache.
     * Never under Data Saver (owner decision); the download itself waits for any network.
     */
    fun allowsListeningCache(conditions: Conditions): Boolean =
        !conditions.dataSaver && conditions.network != Network.OFFLINE

    /**
     * Whether the NEXT song may also be downloaded in full ahead of time: only on an unmetered
     * Wi-Fi / Ethernet with Data Saver off. On cellular the short ExoPlayer preload is enough.
     */
    fun allowsFullNextSongCache(conditions: Conditions): Boolean =
        !conditions.dataSaver && !conditions.metered &&
            (conditions.network == Network.WIFI || conditions.network == Network.ETHERNET)

    /**
     * The upcoming indices in skip order, at most [depth]: each step is [next] (pass
     * `timeline.getNextWindowIndex(i, repeatMode, shuffle)`, with repeat-one mapped to off),
     * stopping at [C.INDEX_UNSET], never returning [current] or an index twice.
     */
    fun upcomingIndices(current: Int, depth: Int, next: (Int) -> Int): List<Int> {
        if (current == C.INDEX_UNSET || depth <= 0) return emptyList()
        val indices = ArrayList<Int>(depth)
        var index = current
        while (indices.size < depth) {
            index = next(index)
            if (index == C.INDEX_UNSET || index == current || index in indices) break
            indices += index
        }
        return indices
    }
}
