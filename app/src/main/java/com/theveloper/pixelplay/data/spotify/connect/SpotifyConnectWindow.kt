package com.theveloper.pixelplay.data.spotify.connect

/** What one queue entry resolved to. */
sealed interface SpotifyConnectSlot {
    data class Uri(val uri: String) : SpotifyConnectSlot
    /** Not on Spotify (or not resolvable): skipped. */
    data object Skipped : SpotifyConnectSlot
    /** Not looked up yet. */
    data object Pending : SpotifyConnectSlot
}

/**
 * The `uris` of one `PUT /me/player/play`, each with the PixlAudio queue index it plays: from a start
 * entry onwards, skipping entries that aren't on Spotify, stopping at the first one not looked up yet
 * (the order must hold) or at [SpotifyConnect.MAX_URIS_PER_PLAY]. Mirrors iOS `SpotifyConnectWindow`.
 */
data class SpotifyConnectWindow(
    val uris: List<String> = emptyList(),
    val queueIndices: List<Int> = emptyList()
) {
    val isEmpty: Boolean get() = uris.isEmpty()
    val count: Int get() = uris.size

    /**
     * The window position of [uri]: the occurrence nearest at or after [hint] (a song can be queued
     * twice), else the first one; null when the window doesn't hold it.
     */
    fun positionOf(uri: String, hint: Int): Int? {
        if (hint in uris.indices) {
            for (i in hint until uris.size) if (uris[i] == uri) return i
        }
        return uris.indexOf(uri).takeIf { it >= 0 }
    }

    /** The window position that plays queue entry [queueIndex]. */
    fun positionOfQueueIndex(queueIndex: Int): Int? = queueIndices.indexOf(queueIndex).takeIf { it >= 0 }

    companion object {
        fun make(
            slots: List<SpotifyConnectSlot>,
            start: Int,
            maxCount: Int = SpotifyConnect.MAX_URIS_PER_PLAY
        ): SpotifyConnectWindow {
            if (start < 0 || maxCount <= 0) return SpotifyConnectWindow()
            val uris = ArrayList<String>()
            val indices = ArrayList<Int>()
            var index = start
            while (index < slots.size && uris.size < maxCount) {
                when (val slot = slots[index]) {
                    is SpotifyConnectSlot.Uri -> {
                        uris += slot.uri
                        indices += index
                    }
                    SpotifyConnectSlot.Skipped -> Unit
                    SpotifyConnectSlot.Pending -> return SpotifyConnectWindow(uris, indices)
                }
                index++
            }
            return SpotifyConnectWindow(uris, indices)
        }

        /** Whether the queue holds anything playable (or still unknown) after the window's last entry. */
        fun hasMore(window: SpotifyConnectWindow, slots: List<SpotifyConnectSlot>): Boolean {
            val last = window.queueIndices.lastOrNull() ?: return false
            for (i in last + 1 until slots.size) if (slots[i] != SpotifyConnectSlot.Skipped) return true
            return false
        }

        /** Entries in [from] until [until] that turned out not to be on Spotify. */
        fun skippedCount(slots: List<SpotifyConnectSlot>, from: Int, until: Int): Int {
            val start = from.coerceIn(0, slots.size)
            val end = until.coerceIn(start, slots.size)
            return (start until end).count { slots[it] == SpotifyConnectSlot.Skipped }
        }

        /** The toast for skipped songs (null for none). */
        fun skippedMessage(count: Int): String? = when {
            count < 1 -> null
            count == 1 -> "1 song isn't on Spotify and was skipped"
            else -> "$count songs aren't on Spotify and were skipped"
        }
    }
}
