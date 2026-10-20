package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.SpotifySongEntity
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackMatcherTest {
    private val matcher = TrackMatcher(mockk())
    private fun song(title: String = "Northern Lights", artist: String = "Nova", durationMs: Long = 180_000) =
        SpotifySongEntity("row", "track", "playlist", title, artist, "First Light", null,
            durationMs, null, null, 0L)

    @Test fun `official video with artist in title is a valid fallback`() {
        val candidate = YouTubeSearchResult("abcdefghijk", "Nova - Northern Lights [Official Music Video]",
            "NovaVEVO", null, 183, isMusicVideo = true)
        assertTrue(matcher.score(song(), candidate) >= TrackMatcher.MIN_ACCEPT_SCORE)
    }

    @Test fun `same title and duration from another artist cannot win`() {
        val candidate = YouTubeSearchResult("abcdefghijk", "Northern Lights", "Completely Different", null, 180)
        assertEquals(0f, matcher.score(song(), candidate))
    }

    @Test fun `artist substring is not an artist match`() {
        val candidate = YouTubeSearchResult("abcdefghijk", "Northern Lights", "Supernova", null, 180)
        assertEquals(0f, matcher.score(song(), candidate))
    }

    @Test fun `unknown source duration does not reject an otherwise exact song`() {
        val candidate = YouTubeSearchResult("abcdefghijk", "Northern Lights", "Nova", null, 180)
        assertTrue(matcher.score(song(durationMs = 0), candidate) >= TrackMatcher.MIN_ACCEPT_SCORE)
    }

    @Test fun `non Latin names retain their identity`() {
        assertEquals("夜に駆ける", TrackMatcher.normalize("夜に駆ける"))
        assertEquals("cafe", TrackMatcher.normalize("Café"))
        assertTrue(TrackMatcher.similarity(TrackMatcher.normalize("夜に駆ける"), TrackMatcher.normalize("春の日")) < .5f)
    }

    @Test fun `live variant loses to original recording`() {
        val studio = YouTubeSearchResult("abcdefghijk", "Northern Lights", "Nova", null, 180)
        val live = studio.copy(title = "Northern Lights (Live)")
        assertTrue(matcher.score(song(), studio) > matcher.score(song(), live))
    }

    @Test fun `quality cap never promotes a muxed video above real audio`() {
        val audio = YouTubeAudioFormat(251, "audio/webm; codecs=opus", 160_000, "audio", null, null, null)
        val muxed = audio.copy(itag = 18, mimeType = "video/mp4", bitrate = 90_000, isMuxedFallback = true)
        assertEquals(audio, listOf(muxed, audio).pickBestAudio(96))
    }
}
