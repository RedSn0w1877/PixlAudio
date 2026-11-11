package com.theveloper.pixelplay.data.spotiflac

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class SpotiFLACTest {

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
            isrc = "UG3S19001129"
        )

        val exactIsrc = SpotiFLACCandidate(
            provider = SpotiFLACProvider.MONOCHROME_TRACKS,
            trackId = "track_isrc",
            releaseId = "rel_1",
            title = "Blinding Lights (Album Version)",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_120L,
            isrc = "UG-3S1-90-01129",
            playable = true
        )

        val metadataOnly = SpotiFLACCandidate(
            provider = SpotiFLACProvider.MONOCHROME_TRACKS,
            trackId = "track_meta",
            releaseId = "rel_1",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_040L,
            isrc = null,
            playable = true
        )

        val liveMismatch = SpotiFLACCandidate(
            provider = SpotiFLACProvider.MONOCHROME_TRACKS,
            trackId = "track_live",
            releaseId = "rel_2",
            title = "Blinding Lights (Live)",
            artist = "The Weeknd",
            album = "Live at SoFi",
            durationMs = 200_040L,
            isrc = null,
            playable = true
        )

        assertEquals(SpotiFLACMatcher.EXACT_ISRC_SCORE, SpotiFLACMatcher.scoreCandidate(exactIsrc, query))
        val metaScore = SpotiFLACMatcher.scoreCandidate(metadataOnly, query)
        assertNotNull(metaScore)
        assertTrue(metaScore!! >= SpotiFLACMatcher.MINIMUM_ACCEPT_SCORE)
        assertNull(SpotiFLACMatcher.scoreCandidate(liveMismatch, query))

        val best = SpotiFLACMatcher.selectBestCandidate(listOf(metadataOnly, exactIsrc, liveMismatch), query)
        assertEquals("track_isrc", best?.trackId)
    }

    @Test
    fun `Monochrome search and release parsing extracts trackId, ISRC, duration, and stream URL`() {
        val searchJson = """
            {
              "query": "daft punk get lucky",
              "results": [
                {
                  "id": "rel-ram",
                  "title": "Get Lucky (feat. Pharrell Williams)",
                  "artist_name": "Daft Punk",
                  "release_id": "rel-ram",
                  "track_id": "mc-track-88"
                }
              ]
            }
        """.trimIndent()

        val searchHits = SpotiFLACParsing.parseMonochromeSearchTracks(searchJson)
        assertEquals(1, searchHits.size)
        assertEquals("mc-track-88", searchHits[0].trackId)
        assertEquals("rel-ram", searchHits[0].releaseId)

        val releaseJson = """
            {
              "release": {
                "id": "rel-ram",
                "title": "Random Access Memories",
                "artist_name": "Daft Punk"
              },
              "tracks": [
                {
                  "id": "mc-track-88",
                  "title": "Get Lucky (feat. Pharrell Williams)",
                  "artist_name": "Daft Punk",
                  "duration": 369626,
                  "isrc": "USQX91300108",
                  "playable": true
                }
              ]
            }
        """.trimIndent()

        val releaseTracks = SpotiFLACParsing.parseMonochromeReleaseTracks(releaseJson)
        assertEquals(1, releaseTracks.size)
        assertEquals("USQX91300108", releaseTracks[0].isrc)
        assertEquals(369_626L, releaseTracks[0].durationMs)

        val streamUrl = SpotiFLACParsing.monochromeTrackStreamUrl("https://tracks.monochrome.st", releaseTracks[0].trackId)
        assertEquals("https://tracks.monochrome.st/track/mc-track-88", streamUrl)
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
    fun `SpotiFLACClient resolves via Monochrome primary and falls back to Tidal HiFi when primary fails`() = runBlocking {
        var monochromeShouldFail = false
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath

                fun jsonResponse(code: Int, body: String): Response = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()

                if (host == "tracks.monochrome.st") {
                    if (monochromeShouldFail) {
                        return@Interceptor jsonResponse(503, "{}")
                    }
                    if (path == "/search/tracks") {
                        return@Interceptor jsonResponse(
                            200,
                            """{"results":[{"id":"r1","title":"Veridis Quo","artist_name":"Daft Punk","release_id":"r1","track_id":"t1"}]}"""
                        )
                    }
                    if (path == "/releases/r1") {
                        return@Interceptor jsonResponse(
                            200,
                            """{"release":{"id":"r1","title":"Discovery","artist_name":"Daft Punk"},"tracks":[{"id":"t1","title":"Veridis Quo","artist_name":"Daft Punk","duration":345000,"isrc":"GBDUW0000059","playable":true}]}"""
                        )
                    }
                }

                if (host == "api.monochrome.tf") {
                    if (path.startsWith("/search")) {
                        return@Interceptor jsonResponse(
                            200,
                            """{"data":{"items":[{"id":998877,"title":"Veridis Quo","duration":345,"isrc":"GBDUW0000059","allowStreaming":true,"streamReady":true,"artist":{"name":"Daft Punk"},"album":{"id":10,"title":"Discovery"}}]}}"""
                        )
                    }
                    if (path.startsWith("/track")) {
                        return@Interceptor jsonResponse(
                            200,
                            """[{"trackId":998877,"assetPresentation":"FULL","audioQuality":"LOSSLESS","OriginalTrackUrl":"https://sp-pr-cf.audio.tidal.com/mediatracks/veridis_quo.flac"}]"""
                        )
                    }
                }

                jsonResponse(404, "{}")
            })
            .build()

        val client = SpotiFLACClient(okHttp)
        val query = SpotiFLACTrackQuery(
            spotifyId = "2LD2gT7gwAurzdQDQtILds",
            title = "Veridis Quo",
            artist = "Daft Punk",
            album = "Discovery",
            durationMs = 345_000L,
            isrc = "GBDUW0000059"
        )

        val primary = client.resolve(query)
        assertNotNull(primary)
        assertEquals(SpotiFLACProvider.MONOCHROME_TRACKS, primary?.provider)
        assertEquals("https://tracks.monochrome.st/track/t1", primary?.url)

        client.invalidate(query.spotifyId)
        monochromeShouldFail = true

        val fallback = client.resolve(query)
        assertNotNull(fallback)
        assertEquals(SpotiFLACProvider.TIDAL_HIFI, fallback?.provider)
        assertEquals("https://sp-pr-cf.audio.tidal.com/mediatracks/veridis_quo.flac", fallback?.url)
    }

    @Test
    fun `FLAC magic header detector identifies genuine fLaC streams`() {
        assertTrue(SpotiFLACParsing.isFlacMagicHeader(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x00, 0x00)))
        assertFalse(SpotiFLACParsing.isFlacMagicHeader(byteArrayOf(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70)))
    }
}
