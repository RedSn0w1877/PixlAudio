package com.theveloper.pixelplay.data.network.lyrics

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic catalog rows and lyrics only; nothing here is a real song's text. */
internal object BiniFixtures {
    fun row(
        isrc: String,
        title: String = "Paper Lanterns",
        artist: String = "Test Singer",
        album: String = "Harbor Songs",
        duration: Int = 200,
        timing: String = "word",
        url: String = "https://lrc.red/s/$isrc.ttml",
    ) = """{"album_name":"$album","artist_name":"$artist","duration":$duration,"id":"$isrc","isrc":"$isrc",""" +
        """"lyricsUrl":"$url","timing_type":"$timing","track_name":"$title"}"""

    fun results(vararg rows: String) =
        """{"results":[${rows.joinToString(",")}],"source":"HIT-LRC-RED","total":${rows.size}}"""

    val EMPTY = """{"results":[],"source":"MISS-LRC-RED","total":0}"""

    fun ttml(firstWordMs: Long = 12_000, lastWordMs: Long = 14_000) =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
            """xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word"><head><metadata>""" +
            """<ttm:agent type="person" xml:id="v1"/></metadata></head><body dur="3:20.000"><div>""" +
            """<p begin="${firstWordMs / 1000.0}" end="${lastWordMs / 1000.0 + 1}" ttm:agent="v1">""" +
            """<span begin="${firstWordMs / 1000.0}" end="${firstWordMs / 1000.0 + 0.5}">Lan</span>""" +
            """<span begin="${firstWordMs / 1000.0 + 0.5}" end="${firstWordMs / 1000.0 + 1}">terns</span> """ +
            """<span begin="${lastWordMs / 1000.0}" end="${lastWordMs / 1000.0 + 1}">glow</span></p></div></body></tt>"""

    val LINE_TTML = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
        """lrc:timing="Line"><body><div><p begin="12.0" end="15.0">Lanterns glow</p></div></body></tt>"""
}

class BiniLyricsSourceTest {
    private val song = Song.emptySong().copy(
        id = "42", title = "Paper Lanterns", artist = "Test Singer", album = "Harbor Songs", duration = 200_000,
    )
    private val requests = CopyOnWriteArrayList<Request>()
    private val clients = mutableListOf<OkHttpClient>()
    private var clock = 1_000_000L

    /**
     * Fake network. The entry host always answers 307 to lrc.red (as the real one does);
     * [api] answers `lrc.red/api/v1` and [files] answers lyrics file paths.
     */
    private fun client(
        api: (Request) -> Pair<Int, String> = { 200 to BiniFixtures.EMPTY },
        files: (Request) -> Pair<Int, String> = { 200 to BiniFixtures.ttml() },
        redirectTo: (Request) -> String = { "https://lrc.red/api/v1?" + it.url.encodedQuery },
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body, location) = when {
            request.url.host == "lyrics-api.binimum.org" -> Triple(307, "", redirectTo(request))
            request.url.host == "lrc.red" && request.url.encodedPath == "/api/v1" -> api(request).let { Triple(it.first, it.second, null) }
            else -> files(request).let { Triple(it.first, it.second, null) }
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .apply { location?.let { header("Location", it) } }
            .header("Retry-After", "120")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build().also(clients::add)

    private fun source(client: OkHttpClient) = BiniLyricsSource(client, now = { clock })

    @AfterEach
    fun shutdown() = clients.forEach { it.dispatcher.executorService.shutdown() }

    @Test
    fun isrcHit_fetchesThatRecordingWithoutASearch() = runBlocking<Unit> {
        val http = client(api = { req ->
            assertThat(req.url.queryParameter("isrc")).isEqualTo("USAB12000001")
            200 to BiniFixtures.results(BiniFixtures.row("USAB12000001", title = "Localized Title"))
        })
        val match = source(http).find(song, isrc = "us-ab1-20-00001")!!

        assertThat(match.candidate.isrc).isEqualTo("USAB12000001")
        assertThat(match.isWordTimed).isTrue()
        assertThat(match.lyrics.areFromRemote).isTrue()
        assertThat(match.lyrics.document!!.metadata.source).isEqualTo("BiniLyrics")
        assertThat(match.lyrics.synced!!.first().words!!.map { it.word }).containsExactly("Lan", "terns", "glow").inOrder()
        assertThat(requests.none { it.url.queryParameter("track") != null }).isTrue()
        assertThat(requests.last().url.toString()).isEqualTo("https://lrc.red/s/USAB12000001.ttml")
    }

    @Test
    fun isrcMiss_fallsBackToOneTrackArtistSearch() = runBlocking<Unit> {
        val http = client(api = { req ->
            if (req.url.queryParameter("isrc") != null) 200 to BiniFixtures.EMPTY
            else 200 to BiniFixtures.results(BiniFixtures.row("USAB12000002"))
        })
        val match = source(http).find(song, isrc = "USAB12000099")!!
        assertThat(match.candidate.isrc).isEqualTo("USAB12000002")
        val searches = requests.filter { it.url.host == "lyrics-api.binimum.org" }
        assertThat(searches).hasSize(2)
        assertThat(searches.last().url.queryParameter("track")).isEqualTo("Paper Lanterns")
        assertThat(searches.last().url.queryParameter("artist")).isEqualTo("Test Singer")
    }

    @Test
    fun remixDecoy_isNeverChosenEvenWhenItIsBetterTimed() = runBlocking<Unit> {
        val http = client(
            api = { 200 to BiniFixtures.results(
                BiniFixtures.row("USAB12000010", title = "Paper Lanterns (Night Remix)", timing = "word"),
                BiniFixtures.row("USAB12000011", title = "Paper Lanterns (Live)", timing = "word"),
                BiniFixtures.row("USAB12000012", title = "Paper Lanterns", timing = "line", duration = 201),
            ) },
            files = { 200 to BiniFixtures.LINE_TTML },
        )
        val match = source(http).find(song)!!
        assertThat(match.candidate.isrc).isEqualTo("USAB12000012")
        assertThat(match.isWordTimed).isFalse()
        assertThat(requests.map { it.url.encodedPath }).doesNotContain("/s/USAB12000010.ttml")
    }

    @Test
    fun remixSong_matchesOnlyTheSameRemix() {
        val remixSong = song.copy(title = "Paper Lanterns (Night Remix)")
        val rows = listOf(candidate("A", "Paper Lanterns"), candidate("B", "Paper Lanterns (Night Remix)"))
        assertThat(BiniLyricsSource.selectCandidate(remixSong, rows)?.isrc).isEqualTo("B")
        assertThat(BiniLyricsSource.selectCandidate(song, rows)?.isrc).isEqualTo("A")
    }

    @Test
    fun durationMismatch_isAMissAndNoFileIsFetched() = runBlocking<Unit> {
        val http = client(api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000020", duration = 240)) })
        assertThat(source(http).find(song)).isNull()
        assertThat(requests.none { it.url.encodedPath.startsWith("/s/") }).isTrue()
        // Within 3 s still matches.
        assertThat(BiniLyricsSource.selectCandidate(song, listOf(candidate("X", durationSec = 202.5)))).isNotNull()
        assertThat(BiniLyricsSource.selectCandidate(song, listOf(candidate("X", durationSec = 203.5)))).isNull()
    }

    @Test
    fun ambiguousCandidates_returnNullButAlbumBreaksTheTie() = runBlocking<Unit> {
        val twins = arrayOf(
            BiniFixtures.row("USAB12000030", album = "Single One"),
            BiniFixtures.row("USAB12000031", album = "Compilation Two"),
        )
        val http = client(api = { 200 to BiniFixtures.results(*twins) })
        assertThat(source(http).find(song)).isNull()
        assertThat(requests.none { it.url.encodedPath.startsWith("/s/") }).isTrue()

        val rows = listOf(candidate("A", album = "Single One"), candidate("B", album = "Harbor Songs"))
        assertThat(BiniLyricsSource.selectCandidate(song, rows)?.isrc).isEqualTo("B")
    }

    @Test
    fun wordTimingBeatsLineTimingAmongValidCandidates() {
        val rows = listOf(candidate("L", timing = "line", album = "Harbor Songs"), candidate("W", timing = "word", album = "Other"))
        assertThat(BiniLyricsSource.selectCandidate(song, rows)?.isrc).isEqualTo("W")
        val unknown = listOf(candidate("U", timing = "karaoke-v2"), candidate("N", timing = "none", album = "Other"))
        assertThat(BiniLyricsSource.selectCandidate(song, unknown)?.isrc).isEqualTo("U")
    }

    @Test
    fun artistMustReallyMatch() {
        assertThat(BiniLyricsSource.artistMatches(song, candidate("A", artist = "Test Singer, Guest Star"))).isTrue()
        assertThat(BiniLyricsSource.artistMatches(song, candidate("A", artist = "Another Band"))).isFalse()
        assertThat(BiniLyricsSource.artistMatches(song.copy(artist = "Lady Nova"), candidate("A", artist = "Lady Comet"))).isFalse()
        val unknown = song.copy(artist = "<unknown>")
        assertThat(BiniLyricsSource.selectCandidate(unknown, listOf(candidate("A")))).isNull()
    }

    @Test
    fun redirectToAnUnlistedHost_isRefused() = runBlocking<Unit> {
        val http = client(redirectTo = { "https://evil.example/api/v1?" + it.url.encodedQuery })
        assertThat(source(http).find(song)).isNull()
        assertThat(requests.map { it.url.host }).containsExactly("lyrics-api.binimum.org")
    }

    @Test
    fun lyricsUrlOnAnUnlistedHostOrPlainHttp_isNeverFetched() = runBlocking<Unit> {
        for (url in listOf("https://evil.example/s/x.ttml", "http://lrc.red/s/x.ttml", "file:///sdcard/x.ttml")) {
            requests.clear()
            val http = client(api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000040", url = url)) })
            assertThat(source(http).find(song)).isNull()
            assertThat(requests.map { it.url.host }.toSet()).containsExactly("lyrics-api.binimum.org", "lrc.red")
            assertThat(requests.none { it.url.encodedPath.startsWith("/s/") }).isTrue()
        }
        assertThat(BiniLyricsSource.isAllowed("https://lyrics-storage.binimum.org/a.ttml".toHttpUrl())).isTrue()
        assertThat(BiniLyricsSource.isAllowed("https://lrc.red.evil.example/a".toHttpUrl())).isFalse()
        assertThat(BiniLyricsSource.isAllowed("http://lrc.red/a".toHttpUrl())).isFalse()
    }

    @Test
    fun rateLimit_backsOffWithoutRetrying() = runBlocking<Unit> {
        var status = 429
        val http = client(api = { status to "slow down" })
        val bini = source(http)
        assertThat(bini.find(song)).isNull()
        val afterFirst = requests.size
        assertThat(afterFirst).isEqualTo(2) // entry + redirect target, no retry

        assertThat(bini.find(song.copy(id = "43", title = "Other Song"))).isNull()
        assertThat(requests.size).isEqualTo(afterFirst) // blocked: no request at all

        status = 200
        clock += 121_000 // past Retry-After (120 s)
        bini.find(song)
        assertThat(requests.size).isGreaterThan(afterFirst)
    }

    @Test
    fun repeatedLookups_hitTheNetworkOnce() = runBlocking<Unit> {
        val http = client(api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000050")) })
        val bini = source(http)
        val first = bini.find(song)
        val count = requests.size
        val second = bini.find(song)
        assertThat(second).isEqualTo(first)
        assertThat(requests.size).isEqualTo(count)

        // Misses are remembered too.
        val missSong = song.copy(id = "44", title = "Nothing Here")
        bini.find(missSong)
        val afterMiss = requests.size
        bini.find(missSong)
        assertThat(requests.size).isEqualTo(afterMiss)
    }

    @Test
    fun lyricsPastTheSongsEnd_areRejected() = runBlocking<Unit> {
        val http = client(
            api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000060")) },
            files = { 200 to BiniFixtures.ttml(firstWordMs = 12_000, lastWordMs = 230_000) },
        )
        assertThat(source(http).find(song)).isNull()
    }

    @Test
    fun malformedResponses_areQuietMisses() = runBlocking<Unit> {
        for (body in listOf("not json", "[]", """{"results":"nope"}""", """{"results":[{"track_name":5}]}""")) {
            val http = client(api = { 200 to body })
            assertThat(source(http).find(song.copy(id = body))).isNull()
        }
        val brokenFile = client(
            api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000070")) },
            files = { 200 to "<tt><body><p>unterminated" },
        )
        assertThat(source(brokenFile).find(song)).isNull()
        val serverError = client(api = { 200 to BiniFixtures.results(BiniFixtures.row("USAB12000071")) }, files = { 503 to "" })
        assertThat(source(serverError).find(song)).isNull()
    }

    @Test
    fun isrcNormalization() {
        assertThat(BiniLyricsSource.normalizeIsrc("us-ab1-20-00001")).isEqualTo("USAB12000001")
        assertThat(BiniLyricsSource.normalizeIsrc("USAB1200000")).isNull()
        assertThat(BiniLyricsSource.normalizeIsrc("")).isNull()
        assertThat(BiniLyricsSource.normalizeIsrc(null)).isNull()
    }

    private fun candidate(
        isrc: String,
        title: String = "Paper Lanterns",
        artist: String = "Test Singer",
        album: String = "Harbor Songs",
        durationSec: Double = 200.0,
        timing: String = "word",
    ) = BiniLyricsSource.Candidate(title, artist, album, durationSec, isrc, "https://lrc.red/s/$isrc.ttml", timing)
}
