package com.theveloper.pixelplay.data.spotiflac

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class SpotiFLACTest {

    // Shapes recorded from the live `tracks.monochrome.st` API on 2026-11-11 (trimmed).
    private val oneMoreTimeRow = """
        {"id":"155102871061270528","trackId":"155102871061270528","title":"One More Time",
         "artistIds":["153542153123926016"],"artistNames":["Daft Punk"],"releaseId":"155102851176075264",
         "explicit":false,"playable":true,"duration":320357,"isrc":"GBDUW0000053",
         "recordingId":"156636455916015616","searchPriority":0}
    """.trimIndent()
    private val radioEditRow = """
        {"id":"165741332789653504","trackId":"165741332789653504","title":"One More Time - Radio Edit",
         "artistIds":["153542153123926016"],"artistNames":["Daft Punk"],"releaseId":"165730834890960896",
         "explicit":false,"playable":true,"duration":235466,"isrc":"GBDUW0000054"}
    """.trimIndent()

    private fun searchBody(vararg rows: String) =
        """{"tracks":[${rows.joinToString(",")}],"releases":[],"artists":[],"topResults":[],"users":[],"playlists":[]}"""

    private val oneMoreTime = SpotiFLACTrackQuery(
        spotifyId = "0DiWol3AO6WpXZgp0goxAV",
        title = "One More Time",
        artist = "Daft Punk",
        album = "Discovery",
        durationMs = 320_357L,
        isrc = "GBDUW0000053"
    )

    /** An OkHttp client whose every call is answered by [answer] (status to body), recording each request. */
    private fun fakeHttp(requests: MutableList<Request>, answer: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val (code, body) = answer(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

    @Test
    fun `normalizeIsrc strips hyphens and validates 12-char alphanumeric code`() {
        assertEquals("USWB11403680", SpotiFLACMatcher.normalizeIsrc("US-WB1-14-03680"))
        assertEquals("GBAYE0601498", SpotiFLACMatcher.normalizeIsrc("gbaye0601498"))
        assertNull(SpotiFLACMatcher.normalizeIsrc("SHORT"))
        assertNull(SpotiFLACMatcher.normalizeIsrc(null))
    }

    @Test
    fun `exact ISRC match scores 200 and beats metadata-only matches`() {
        val query = SpotiFLACTrackQuery(
            spotifyId = "0VjIjW4GlUZAMYd2vXMi3b",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_040L,
            isrc = "USUG11904206"
        )
        fun candidate(id: String, title: String, isrc: String?) = SpotiFLACCandidate(
            provider = SpotiFLACProvider.MONOCHROME_TRACKS,
            trackId = id,
            title = title,
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_040L,
            isrc = isrc
        )
        val exactIsrc = candidate("track_isrc", "Blinding Lights (Album Version)", "US-UG1-19-04206")
        val metadataOnly = candidate("track_meta", "Blinding Lights", null)
        val liveMismatch = candidate("track_live", "Blinding Lights (Live)", null)

        assertEquals(SpotiFLACMatcher.EXACT_ISRC_SCORE, SpotiFLACMatcher.scoreCandidate(exactIsrc, query))
        val metaScore = SpotiFLACMatcher.scoreCandidate(metadataOnly, query)
        assertNotNull(metaScore)
        assertTrue(metaScore!! >= SpotiFLACMatcher.MINIMUM_ACCEPT_SCORE)
        assertNull(SpotiFLACMatcher.scoreCandidate(liveMismatch, query))

        val best = SpotiFLACMatcher.selectBestCandidate(listOf(metadataOnly, exactIsrc, liveMismatch), query)
        assertEquals("track_isrc", best?.trackId)
    }

    @Test
    fun `Monochrome search parsing reads the live response shape`() {
        val hits = SpotiFLACParsing.parseMonochromeSearchTracks(
            searchBody(
                oneMoreTimeRow,
                """{"trackId":"1","title":"luther (with sza)","artistNames":["Kendrick Lamar","SZA"],"duration":177598,"isrc":"USUG12408496","playable":false}"""
            )
        )
        assertEquals(2, hits.size)
        with(hits[0]) {
            assertEquals("155102871061270528", trackId)
            assertEquals("155102851176075264", releaseId)
            assertEquals("One More Time", title)
            assertEquals("Daft Punk", artist)
            assertEquals(320_357L, durationMs)
            assertEquals("GBDUW0000053", isrc)
            assertTrue(playable)
        }
        assertEquals("Kendrick Lamar, SZA", hits[1].artist)
        assertFalse(hits[1].playable)

        assertEquals(
            "https://tracks.monochrome.st/track/155102871061270528",
            SpotiFLACParsing.monochromeTrackStreamUrl("https://tracks.monochrome.st/", hits[0].trackId)
        )
        assertEquals("", SpotiFLACParsing.monochromeTrackStreamUrl("https://tracks.monochrome.st", "../admin?x=1"))
    }

    @Test
    fun `parsers survive malformed and unexpected JSON`() {
        assertTrue(SpotiFLACParsing.parseMonochromeSearchTracks("<!doctype html><html>Redirecting...</html>").isEmpty())
        assertTrue(SpotiFLACParsing.parseMonochromeSearchTracks("""{"tracks":{"not":"an array"}}""").isEmpty())
        val odd = SpotiFLACParsing.parseMonochromeSearchTracks(
            """{"tracks":[{"trackId":"9","title":"Song","artistNames":"Solo Artist","duration":"x"},42,null]}"""
        )
        assertEquals(1, odd.size)
        assertEquals("", odd[0].artist)
        assertEquals(0L, odd[0].durationMs)
        assertTrue(SpotiFLACParsing.parseHiFiSearchResponse("""{"data":"nope"}""").isEmpty())
        assertNull(SpotiFLACParsing.parseHiFiStreamResponse("not json", fallbackTrackId = "1"))
        assertNull(SpotiFLACParsing.parseDeezerTrack("""{"error":{"code":800}}"""))
    }

    @Test
    fun `Tidal HiFi base64 manifest decoding extracts FLAC stream URL and rejects PREVIEW`() {
        val innerManifest = """
            {"mimeType":"audio/flac","codecs":"flac","urls":["https://sp-pr-cf.audio.tidal.com/mediatracks/flac_16_44.flac"]}
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(innerManifest.toByteArray(Charsets.UTF_8))

        val hifiJson = """
            {
              "version": "2.7",
              "data": {
                "trackId": 77665544,
                "assetPresentation": "FULL",
                "audioQuality": "LOSSLESS",
                "bitDepth": 16,
                "sampleRate": 44100,
                "trackReplayGain": -8.4,
                "trackPeakAmplitude": 0.98,
                "manifestMimeType": "application/vnd.tidal.bts",
                "manifest": "$b64"
              }
            }
        """.trimIndent()

        val stream = SpotiFLACParsing.parseHiFiStreamResponse(hifiJson, fallbackTrackId = "77665544", isrc = "USQX91300108")
        assertNotNull(stream)
        assertEquals("https://sp-pr-cf.audio.tidal.com/mediatracks/flac_16_44.flac", stream?.url)
        assertEquals("audio/flac", stream?.mimeType)
        assertEquals(16, stream?.bitDepth)
        assertEquals(44_100, stream?.sampleRateHz)
        assertEquals("USQX91300108", stream?.isrc)
        assertEquals(-8.4, stream?.replayGain?.trackGainDb)

        val previewJson = """
            {
              "data": {
                "trackId": 77665544,
                "assetPresentation": "PREVIEW",
                "OriginalTrackUrl": "https://sp-pr-cf.audio.tidal.com/preview.flac"
              }
            }
        """.trimIndent()
        assertNull(SpotiFLACParsing.parseHiFiStreamResponse(previewJson, fallbackTrackId = "77665544"))
    }

    @Test
    fun `a known ISRC resolves with one isrc search request`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = SpotiFLACClient(fakeHttp(requests) { request ->
            if (request.url.host == "tracks.monochrome.st" && request.url.encodedPath == "/search/tracks" &&
                request.url.queryParameter("q") == "isrc:GBDUW0000053"
            ) 200 to searchBody(oneMoreTimeRow) else 404 to "{}"
        })

        val stream = client.resolve(oneMoreTime)

        assertNotNull(stream)
        assertEquals(SpotiFLACProvider.MONOCHROME_TRACKS, stream?.provider)
        assertEquals("https://tracks.monochrome.st/track/155102871061270528", stream?.url)
        assertEquals("audio/flac", stream?.mimeType)
        assertEquals(1, requests.size)
        assertEquals("PixlAudio/1.0", requests[0].header("User-Agent"))

        // Cached: no second request.
        assertEquals(stream, client.resolve(oneMoreTime))
        assertEquals(1, requests.size)
    }

    @Test
    fun `without an ISRC the text search picks the right cut`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = SpotiFLACClient(fakeHttp(requests) { request ->
            if (request.url.encodedPath == "/search/tracks" && request.url.queryParameter("q") == "Daft Punk One More Time") {
                200 to searchBody(radioEditRow, oneMoreTimeRow)
            } else {
                404 to "{}"
            }
        })

        val stream = client.resolve(oneMoreTime.copy(isrc = null, artist = "Daft Punk, Romanthony"))

        assertEquals("155102871061270528", stream?.providerTrackId)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a clean miss is remembered, a configured HiFi instance is the fallback`() = runBlocking {
        val manifest = Base64.getEncoder().encodeToString(
            """{"mimeType":"audio/flac","urls":["https://sp-pr-cf.audio.tidal.com/mediatracks/one_more_time.flac"]}""".toByteArray()
        )
        val hifiUp = AtomicBoolean(false)
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = SpotiFLACClient(fakeHttp(requests) { request ->
            when {
                request.url.host == "tracks.monochrome.st" -> 200 to searchBody()
                !hifiUp.get() -> 404 to "{}"
                request.url.encodedPath == "/search/" ->
                    200 to """{"data":{"items":[{"id":998877,"title":"One More Time","duration":320,"isrc":"GBDUW0000053","artist":{"name":"Daft Punk"}}]}}"""
                request.url.encodedPath == "/track/" ->
                    200 to """{"data":{"trackId":998877,"assetPresentation":"FULL","manifestMimeType":"application/vnd.tidal.bts","manifest":"$manifest"}}"""
                else -> 404 to "{}"
            }
        })
        client.hifiBaseUrls = listOf("https://hifi.example.org")

        assertNull(client.resolve(oneMoreTime))
        val afterMiss = requests.size
        hifiUp.set(true)
        assertNull(client.resolve(oneMoreTime)) // remembered: nothing asked
        assertEquals(afterMiss, requests.size)

        client.invalidate(oneMoreTime.spotifyId)
        val stream = client.resolve(oneMoreTime)
        assertEquals(SpotiFLACProvider.TIDAL_HIFI, stream?.provider)
        assertEquals("https://sp-pr-cf.audio.tidal.com/mediatracks/one_more_time.flac", stream?.url)
    }

    @Test
    fun `a stream URL outside the trusted hosts is refused`() = runBlocking {
        val manifest = Base64.getEncoder().encodeToString(
            """{"mimeType":"audio/flac","urls":["https://evil.example.com/a.flac"]}""".toByteArray()
        )
        val client = SpotiFLACClient(fakeHttp(mutableListOf()) { request ->
            when {
                request.url.host == "tracks.monochrome.st" -> 200 to searchBody()
                request.url.encodedPath == "/search/" ->
                    200 to """{"items":[{"id":5,"title":"One More Time","isrc":"GBDUW0000053","artist":{"name":"Daft Punk"}}]}"""
                request.url.encodedPath == "/track/" -> 200 to """{"manifest":"$manifest"}"""
                else -> 404 to "{}"
            }
        })
        client.hifiBaseUrls = listOf("https://hifi.example.org")

        assertNull(client.resolve(oneMoreTime))
    }

    @Test
    fun `a cancelled lookup leaves no miss and no cooldown behind`() = runBlocking {
        val slow = AtomicBoolean(true)
        val client = SpotiFLACClient(fakeHttp(Collections.synchronizedList(mutableListOf())) { _ ->
            if (slow.get()) Thread.sleep(1_500)
            200 to searchBody(oneMoreTimeRow)
        })

        assertNull(withTimeoutOrNull(200) { client.resolve(oneMoreTime) })

        slow.set(false)
        assertEquals("155102871061270528", client.resolve(oneMoreTime)?.providerTrackId)
    }

    @Test
    fun `FLAC magic header detector identifies genuine fLaC streams`() {
        assertTrue(SpotiFLACParsing.isFlacMagicHeader(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x00, 0x00)))
        assertFalse(SpotiFLACParsing.isFlacMagicHeader(byteArrayOf(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70)))
    }
}
