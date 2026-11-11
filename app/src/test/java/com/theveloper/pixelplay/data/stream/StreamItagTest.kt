package com.theveloper.pixelplay.data.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The stream's itag for a Spatial audio job's `library.source` (cloud-audio-spec §6.2 step 3), read off its URL. */
class StreamItagTest {
    @Test fun `the itag comes from the stream URL's own query`() {
        assertEquals(251, StreamItag.fromUrl("https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&itag=251&mime=audio%2Fwebm&clen=3"))
        assertEquals(140, StreamItag.fromUrl("https://rr1---sn-x.googlevideo.com/videoplayback?itag=140"))
        assertEquals(18, StreamItag.fromUrl("https://x.test/v?a=1&itag=18#t=2"))
    }

    @Test fun `no itag, or not a number, is none`() {
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback"))
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback?mime=audio"))
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback?itag=abc"))
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback?itag="))
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback?xitag=251"))
        assertNull(StreamItag.fromUrl("https://x.test/videoplayback?itag=-1"))
    }

    @Test fun `the stream's length comes from its clen, when the URL has a real one`() {
        assertEquals(3_412_208L, StreamItag.contentLengthFromUrl("https://rr1---sn-x.googlevideo.com/videoplayback?itag=251&clen=3412208&dur=202.001"))
        assertEquals(9_999_999_999L, StreamItag.contentLengthFromUrl("https://x.test/v?clen=9999999999#t=1"))
        assertNull(StreamItag.contentLengthFromUrl("https://x.test/videoplayback?itag=251"))
        assertNull(StreamItag.contentLengthFromUrl("https://x.test/videoplayback?clen="))
        assertNull(StreamItag.contentLengthFromUrl("https://x.test/videoplayback?clen=0"))
        assertNull(StreamItag.contentLengthFromUrl("https://x.test/videoplayback?clen=x1"))
        assertNull(StreamItag.contentLengthFromUrl("https://x.test/videoplayback?xclen=12"))
    }
}
