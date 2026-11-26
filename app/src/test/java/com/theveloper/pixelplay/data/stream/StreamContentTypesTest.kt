package com.theveloper.pixelplay.data.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamContentTypesTest {
    @Test
    fun `the url's own mime wins, then the itag, then the upstream, then mp4`() {
        assertEquals("audio/webm", StreamContentTypes.ofUrl("https://r1.googlevideo.com/videoplayback?itag=140&mime=audio%2Fwebm"))
        assertEquals("audio/mp4", StreamContentTypes.ofUrl("https://r1.googlevideo.com/videoplayback?itag=140"))
        assertEquals("audio/webm", StreamContentTypes.ofUrl("https://r1.googlevideo.com/videoplayback?itag=251"))
        assertEquals("video/mp4", StreamContentTypes.ofUrl("https://r1.googlevideo.com/videoplayback?itag=18"))
        assertEquals("audio/flac", StreamContentTypes.ofUrl("https://files.example/a.flac", "audio/flac; charset=binary"))
        assertEquals("audio/mp4", StreamContentTypes.ofUrl("https://files.example/a", "application/octet-stream"))
        assertEquals("audio/mp4", StreamContentTypes.ofUrl(null))
    }
}
