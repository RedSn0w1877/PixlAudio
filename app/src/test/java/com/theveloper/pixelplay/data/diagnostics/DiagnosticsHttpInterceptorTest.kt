package com.theveloper.pixelplay.data.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticsHttpInterceptorTest {
    @Test
    fun `an ISRC or a MusicBrainz id in the path of an open-data host is left out of the log`() {
        assertEquals("api.deezer.com/track/_", DiagnosticsHttpInterceptor.describe("https://api.deezer.com/track/isrc:USUM71703861".toHttpUrl()))
        assertEquals("musicbrainz.org/ws/2/isrc/_", DiagnosticsHttpInterceptor.describe("https://musicbrainz.org/ws/2/isrc/USUM71703861?fmt=json".toHttpUrl()))
        assertEquals(
            "musicbrainz.org/ws/2/artist/_",
            DiagnosticsHttpInterceptor.describe("https://musicbrainz.org/ws/2/artist/b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d".toHttpUrl()),
        )
        assertEquals("api.deezer.com/search", DiagnosticsHttpInterceptor.describe("https://api.deezer.com/search?q=a".toHttpUrl()))
    }

    @Test
    fun `other hosts are logged as before`() {
        assertEquals("api.spotify.com/v1/me/tracks", DiagnosticsHttpInterceptor.describe("https://api.spotify.com/v1/me/tracks?limit=50".toHttpUrl()))
    }
}
