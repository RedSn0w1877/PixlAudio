package com.theveloper.pixelplay.data.songtap.ring

import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads

/** A lookup the test controls: which video ids resolve to which song, and how many lookups were made. */
class FakeRingResolver : RingCodeResolver {
    val known = mutableMapOf<String, Pair<String, String>>()
    var offline = false
    val lookups = ArrayList<String>()

    init {
        known["dQw4w9WgXcQ"] = "Never Gonna Give You Up" to "Rick Astley"
    }

    override suspend fun resolve(videoId: String, nowMs: Long): SongTapPayload? {
        lookups += videoId
        if (offline) return null
        val (title, artist) = known[videoId] ?: return null
        return SongTapPayloads.make(
            nowMs = nowMs, ytVideoId = videoId, spotifyId = null, isrc = null, title = title, artist = artist,
            durationMs = 213_000L, artUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )
    }
}
