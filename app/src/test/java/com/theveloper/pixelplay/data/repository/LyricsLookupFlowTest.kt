package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.lyrics.BiniFixtures
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import com.theveloper.pixelplay.data.network.lyrics.LrcLibResponse
import com.theveloper.pixelplay.data.network.lyrics.SongIsrcResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The automatic lookup of a streamed (Spotify) song, end to end with a fake network: lookup
 * order (ISRC, then strict title/artist), the fallback chain (BiniLyrics, then LRCLIB), the
 * negative cache, the explicit retry, and one shared lookup for concurrent callers.
 * Real time on purpose (runBlocking): the catalog race uses timeouts.
 */
class LyricsLookupFlowTest {

    private val spotifySong = Song.emptySong().copy(
        id = "-3000000000123",
        title = "Paper Lanterns",
        artist = "Test Singer",
        album = "Harbor Songs",
        duration = 200_000,
        path = "",
        contentUriString = "spotify://4uLU6hMCjMI75M1A2tKUQC",
        spotifyId = "4uLU6hMCjMI75M1A2tKUQC",
    )

    private class Net(
        /** lrc.red/api/v1 answers (the BiniLyrics search). */
        val search: (Request) -> String = { BiniFixtures.EMPTY },
        val delayMs: Long = 0,
    ) {
        val searches = CopyOnWriteArrayList<Request>()
        val files = AtomicInteger()
        val other = AtomicInteger()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val (code, body, location) = when {
                host == "lyrics-api.binimum.org" ->
                    Triple(307, "", "https://lrc.red/api/v1?" + request.url.encodedQuery)
                host == "lrc.red" && request.url.encodedPath == "/api/v1" -> {
                    searches += request
                    if (delayMs > 0) Thread.sleep(delayMs)
                    Triple(200, search(request), null)
                }
                host == "lrc.red" -> {
                    files.incrementAndGet()
                    Triple(200, BiniFixtures.ttml(), null)
                }
                else -> {
                    other.incrementAndGet()
                    Triple(404, "", null)
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .apply { location?.let { header("Location", it) } }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private fun context(): Context {
        val dir: File = Files.createTempDirectory("pixelplay-lookup-test").toFile()
        return mockk<Context>(relaxed = true) { every { filesDir } returns dir }
    }

    private fun dao(): LyricsDao = mockk(relaxed = true) { coEvery { getLyrics(any()) } returns null }

    private fun lrclib(calls: AtomicInteger, vararg hits: LrcLibResponse): LrcLibApiService =
        mockk(relaxed = true) {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.incrementAndGet()
                arrayOf(*hits)
            }
            coEvery { getLyrics(any(), any(), any(), any()) } returns null
        }

    private fun lrcHit() = LrcLibResponse(
        id = 7, name = "Paper Lanterns", artistName = "Test Singer", albumName = "Harbor Songs",
        duration = 200.0, plainLyrics = null, syncedLyrics = "[00:01.00]First line\n[00:05.00]Second line",
    )

    @Test
    fun lookupOrder_isIsrcFirstThenTitleAndArtist() = runBlocking<Unit> {
        val net = Net(search = { request ->
            if (request.url.queryParameter("isrc") != null) BiniFixtures.EMPTY
            else BiniFixtures.results(BiniFixtures.row("USAB12000007"))
        })
        val resolver = mockk<SongIsrcResolver> { coEvery { isrcFor(any()) } returns "USAB12000001" }
        val repository = LyricsRepositoryImpl(context(), lrclib(AtomicInteger()), dao(), net.client, resolver)

        val found = repository.findOnlineSyncedLyrics(spotifySong)

        assertThat(found?.source).isEqualTo("Pixel Master Lyrics")
        assertThat(found?.lyrics?.synced.orEmpty().first().words).isNotEmpty()
        assertThat(net.searches.map { it.url.queryParameter("isrc") }).containsExactly("USAB12000001", null).inOrder()
        assertThat(net.searches[1].url.queryParameter("track")).isEqualTo("Paper Lanterns")
        assertThat(net.searches[1].url.queryParameter("artist")).isEqualTo("Test Singer")
        assertThat(net.files.get()).isEqualTo(1)
        net.client.dispatcher.executorService.shutdown()
    }

    @Test
    fun streamedSong_fallsBackToLrclibWhenBiniLyricsHasNothing_andCachesIt() = runBlocking<Unit> {
        val net = Net()
        val lrclibCalls = AtomicInteger()
        val ctx = context()
        val repository = LyricsRepositoryImpl(ctx, lrclib(lrclibCalls, lrcHit()), dao(), net.client)

        val lyrics = repository.getLyrics(spotifySong, LyricsSourcePreference.EMBEDDED_FIRST)

        assertThat(lyrics?.synced?.map { it.line }).containsExactly("First line", "Second line").inOrder()
        assertThat(lyrics?.areFromRemote).isTrue()
        assertThat(File(ctx.filesDir, "lyrics/${spotifySong.id}.json").exists()).isTrue()
        val calls = lrclibCalls.get()
        assertThat(calls).isGreaterThan(0)

        // Served from the cache the second time: no network at all.
        val again = repository.getLyrics(spotifySong, LyricsSourcePreference.EMBEDDED_FIRST)
        assertThat(again?.synced).hasSize(2)
        assertThat(lrclibCalls.get()).isEqualTo(calls)
        net.client.dispatcher.executorService.shutdown()
    }

    @Test
    fun nothingFound_isNegativeCached_andRetryBypassesIt() = runBlocking<Unit> {
        val net = Net()
        val lrclibCalls = AtomicInteger()
        val repository = LyricsRepositoryImpl(context(), lrclib(lrclibCalls), dao(), net.client)

        assertThat(repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST)).isNull()
        val searches = net.searches.size
        val lrclib = lrclibCalls.get()
        assertThat(searches).isGreaterThan(0)

        // Within the TTL the same song is not looked up again: not a single request.
        assertThat(repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(net.searches.size).isEqualTo(searches)
        assertThat(lrclibCalls.get()).isEqualTo(lrclib)

        // The user's Retry looks again, BiniLyrics' own memo included.
        assertThat(repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST, forceRefresh = true)).isNull()
        assertThat(net.searches.size).isGreaterThan(searches)
        assertThat(lrclibCalls.get()).isGreaterThan(lrclib)
        net.client.dispatcher.executorService.shutdown()
    }

    @Test
    fun retryAfterAMiss_findsLyricsThatAppearedMeanwhile() = runBlocking<Unit> {
        var catalogHasIt = false
        val net = Net(search = {
            if (catalogHasIt) BiniFixtures.results(BiniFixtures.row("USAB12000008")) else BiniFixtures.EMPTY
        })
        val repository = LyricsRepositoryImpl(context(), lrclib(AtomicInteger()), dao(), net.client)

        assertThat(repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST)).isNull()
        catalogHasIt = true
        assertThat(repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST)).isNull() // still remembered
        val retried = repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST, forceRefresh = true)

        assertThat(retried?.synced.orEmpty()).isNotEmpty()
        net.client.dispatcher.executorService.shutdown()
    }

    @Test
    fun concurrentCallsForOneSong_shareOneLookup() = runBlocking<Unit> {
        // One lookup on its own: how many LRCLIB calls does it take?
        val soloCalls = AtomicInteger()
        val soloNet = Net(delayMs = 0)
        LyricsRepositoryImpl(context(), lrclib(soloCalls), dao(), soloNet.client)
            .getLyrics(spotifySong, LyricsSourcePreference.API_FIRST)
        soloNet.client.dispatcher.executorService.shutdown()

        // Three callers at once while the network is slow: still exactly that many.
        val sharedCalls = AtomicInteger()
        val net = Net(delayMs = 300)
        val repository = LyricsRepositoryImpl(context(), lrclib(sharedCalls), dao(), net.client)
        val results = (1..3).map { async { repository.getLyrics(spotifySong, LyricsSourcePreference.API_FIRST) } }.awaitAll()

        assertThat(results).containsExactly(null, null, null)
        assertThat(sharedCalls.get()).isEqualTo(soloCalls.get())
        net.client.dispatcher.executorService.shutdown()
    }

    @Test
    fun streamedSong_neverTouchesTheContentResolverForTags() = runBlocking<Unit> {
        val net = Net()
        val ctx = context()
        val repository = LyricsRepositoryImpl(ctx, lrclib(AtomicInteger()), dao(), net.client)

        repository.getLyrics(spotifySong, LyricsSourcePreference.EMBEDDED_FIRST)

        io.mockk.verify(exactly = 0) { ctx.contentResolver }
        net.client.dispatcher.executorService.shutdown()
    }
}
