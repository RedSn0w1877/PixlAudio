package com.theveloper.pixelplay.data.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RemoteThumbnailUrlsTest {

    private val deezerArtist =
        "https://e-cdns-images.dzcdn.net/images/artist/0123abcd/1000x1000-000000-80-0-0.jpg"

    @Test
    fun `deezer picks the smallest api size covering the target`() {
        assertEquals(
            "https://e-cdns-images.dzcdn.net/images/artist/0123abcd/250x250-000000-80-0-0.jpg",
            RemoteThumbnailUrls.variantFor(deezerArtist, 144)
        )
        assertEquals(
            "https://e-cdns-images.dzcdn.net/images/artist/0123abcd/56x56-000000-80-0-0.jpg",
            RemoteThumbnailUrls.variantFor(deezerArtist, 56)
        )
        assertEquals(
            "https://e-cdns-images.dzcdn.net/images/artist/0123abcd/500x500-000000-80-0-0.jpg",
            RemoteThumbnailUrls.variantFor(deezerArtist, 251)
        )
    }

    @Test
    fun `deezer never upsizes or rewrites a large target`() {
        assertNull(RemoteThumbnailUrls.variantFor(deezerArtist, 700))
        assertNull(RemoteThumbnailUrls.variantFor(deezerArtist, 1200))
        val small = "https://cdn-images.dzcdn.net/images/cover/ff00/250x250-000000-80-0-0.jpg"
        assertNull(RemoteThumbnailUrls.variantFor(small, 300))
        assertEquals(
            "https://cdn-images.dzcdn.net/images/cover/ff00/56x56-000000-80-0-0.jpg",
            RemoteThumbnailUrls.variantFor(small, 40)
        )
    }

    @Test
    fun `googleusercontent square sizes are bucketed down`() {
        val url = "https://lh3.googleusercontent.com/abcDEF=w544-h544-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abcDEF=w192-h192-l90-rj",
            RemoteThumbnailUrls.variantFor(url, 150)
        )
        assertNull(RemoteThumbnailUrls.variantFor(url, 600))
        assertEquals(
            "https://yt3.ggpht.com/xyz=s128-c-k-c0x00ffffff-no-rj",
            RemoteThumbnailUrls.variantFor("https://yt3.ggpht.com/xyz=s800-c-k-c0x00ffffff-no-rj", 100)
        )
    }

    @Test
    fun `other hosts and non-square images are left alone`() {
        assertNull(RemoteThumbnailUrls.variantFor("https://i.scdn.co/image/ab67616d0000b273deadbeef", 64))
        assertNull(RemoteThumbnailUrls.variantFor("https://lh3.googleusercontent.com/a=w1200-h630-p", 100))
        assertNull(RemoteThumbnailUrls.variantFor("content://media/external/audio/albumart/12", 64))
        assertNull(RemoteThumbnailUrls.variantFor("https://example.com/images/1000x1000-000000-80-0-0.jpg", 64))
        assertNull(RemoteThumbnailUrls.variantFor(deezerArtist, 0))
    }
}
