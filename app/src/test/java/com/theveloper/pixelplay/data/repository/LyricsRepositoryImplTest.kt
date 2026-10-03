package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.LyricsEntity
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import com.theveloper.pixelplay.data.network.lyrics.LrcLibResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import com.theveloper.pixelplay.data.network.lyrics.BiniFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test

class LyricsRepositoryImplTest {

    @Test
    fun getLyrics_storageProbeFailureFallsBackToSongLyricsWithoutRemoteFetch() = runTest {
        val api = mockk<LrcLibApiService>(relaxed = true)
        val dao = mockk<LyricsDao>(relaxed = true)
        coEvery { dao.getLyrics(any()) } returns null
        val context = mockk<Context> {
            every { filesDir } throws SecurityException("Storage temporarily unavailable")
        }
        val repository = LyricsRepositoryImpl(context, api, dao, offlineClient())
        val song = testSong(id = "1042", title = "Stored song", artist = "Artist", duration = 180_000L).copy(lyrics = "Saved words")

        val lyrics = repository.getLyrics(song, LyricsSourcePreference.API_FIRST)

        assertThat(lyrics?.plain).containsExactly("Saved words")
        coVerify(exactly = 0) { api.getLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.searchLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun parseBestEmbeddedLyricsField_prefersSyncedLyricsWhenLyricsFieldIsPlain() {
        val result = parseBestEmbeddedLyricsField(
            mapOf(
                "LYRICS" to arrayOf("plain lyrics only"),
                "SYNCEDLYRICS" to arrayOf("[00:01.00]Synced lyrics")
            )
        )

        assertThat(result).isNotNull()
        assertThat(result!!.synced).hasSize(1)
        assertThat(result.synced!!.first().line).isEqualTo("Synced lyrics")
        assertThat(result.areFromRemote).isFalse()
    }

    @Test
    fun getLyrics_returnsSongLyricsBeforeNeedingStorageRead() = runTest {
        val repository = LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            lrcLibApiService = mockk<LrcLibApiService>(relaxed = true),
            lyricsDao = mockk<LyricsDao>(relaxed = true),
            okHttpClient = offlineClient()
        )
        val song = Song(
            id = "12",
            title = "Track",
            artist = "Artist",
            artistId = 5L,
            album = "Album",
            albumId = 8L,
            path = "",
            contentUriString = "",
            albumArtUriString = null,
            duration = 180_000L,
            lyrics = "[00:01.00]Hello again",
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
        )

        val lyrics = repository.getLyrics(song, LyricsSourcePreference.EMBEDDED_FIRST)

        assertThat(lyrics).isNotNull()
        assertThat(lyrics!!.areFromRemote).isFalse()
        assertThat(lyrics.synced).isNotEmpty()
        assertThat(lyrics.synced!!.first().line).isEqualTo("Hello again")
    }

    @Test
    fun getLyrics_apiFirst_usesStoredLyricsBeforeCallingLrcLib() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val repository = LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            lrcLibApiService = apiService,
            lyricsDao = mockk<LyricsDao>(relaxed = true),
            okHttpClient = offlineClient()
        )
        val song = Song(
            id = "45",
            title = "Already Here",
            artist = "Artist",
            artistId = 5L,
            album = "Album",
            albumId = 8L,
            path = "",
            contentUriString = "",
            albumArtUriString = null,
            duration = 180_000L,
            lyrics = "These lyrics are already saved",
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
        )

        val lyrics = repository.getLyrics(song, LyricsSourcePreference.API_FIRST)

        assertThat(lyrics).isNotNull()
        assertThat(lyrics!!.plain).containsExactly("These lyrics are already saved")
        assertThat(lyrics.areFromRemote).isFalse()
        coVerify(exactly = 0) { apiService.searchLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiService.getLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun fetchFromRemote_returnsStoredLyricsWithoutCallingApi() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics(77L) } returns LyricsEntity(
            songId = 77L,
            content = "[00:01.00]Stored line",
            isSynced = true,
            source = "manual"
        )
        val repository = LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = offlineClient()
        )
        val song = Song(
            id = "77",
            title = "Stored Track",
            artist = "Artist",
            artistId = 5L,
            album = "Album",
            albumId = 8L,
            path = "",
            contentUriString = "",
            albumArtUriString = null,
            duration = 180_000L,
            lyrics = null,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        val (lyrics, rawLyrics) = result.getOrThrow()
        assertThat(rawLyrics).isEqualTo("[00:01.00]Stored line")
        assertThat(lyrics.synced).isNotEmpty()
        assertThat(lyrics.areFromRemote).isFalse()
        coVerify(exactly = 0) { apiService.searchLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiService.getLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun fetchFromRemote_rejectsDurationOnlySearchMatch() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics(101L) } returns null
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(
            lrcResponse(
                name = "Completely Different Song",
                artistName = "Different Artist",
                duration = 180.0
            )
        )
        coEvery { apiService.getLyrics(any(), any(), any(), any()) } returns null

        val repository = LyricsRepositoryImpl(
            context = testContext(),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = offlineClient()
        )
        val song = testSong(
            id = "101",
            title = "Actual Song",
            artist = "Actual Artist",
            duration = 180_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { lyricsDao.insert(any()) }
    }

    @Test
    fun fetchFromRemote_rejectsOriginalLyricsForRemix() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val originalSongLyrics = lrcResponse(
            name = "Midnight City",
            artistName = "M83",
            duration = 242.0
        )
        coEvery { lyricsDao.getLyrics(102L) } returns null
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(originalSongLyrics)
        coEvery { apiService.getLyrics(any(), any(), any(), any()) } returns originalSongLyrics

        val repository = LyricsRepositoryImpl(
            context = testContext(),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = offlineClient()
        )
        val song = testSong(
            id = "102",
            title = "Midnight City (Remix)",
            artist = "M83",
            path = "/music/Midnight City (Remix).mp3",
            duration = 242_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { lyricsDao.insert(any()) }
    }

    @Test
    fun fetchFromRemote_acceptsMatchingRemixVariant() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val remixLyrics = lrcResponse(
            name = "Midnight City (Eric Prydz Remix)",
            artistName = "M83",
            duration = 242.0
        )
        coEvery { lyricsDao.getLyrics(103L) } returns null
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(remixLyrics)

        val repository = LyricsRepositoryImpl(
            context = testContext(),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = offlineClient()
        )
        val song = testSong(
            id = "103",
            title = "Midnight City (Eric Prydz Remix)",
            artist = "M83",
            path = "/music/Midnight City (Eric Prydz Remix).mp3",
            duration = 242_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().first.areFromRemote).isTrue()
        coVerify(exactly = 1) { lyricsDao.insert(any()) }
    }

    @Test
    fun fetchFromRemote_doesNotTreatArtistNameInFilePathAsVariant() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val lyrics = lrcResponse(
            name = "Black Magic",
            artistName = "Little Mix",
            duration = 211.0
        )
        coEvery { lyricsDao.getLyrics(104L) } returns null
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(lyrics)

        val repository = LyricsRepositoryImpl(
            context = testContext(),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = offlineClient()
        )
        val song = testSong(
            id = "104",
            title = "Black Magic",
            artist = "Little Mix",
            path = "/music/Little Mix - Black Magic.mp3",
            duration = 211_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { lyricsDao.insert(any()) }
    }

    @Test
    fun fetchFromRemote_prefersBiniLyricsWordTimingOverLrclibLineSync() = runBlocking<Unit> {
        // Real time on purpose: the catalog race uses withTimeoutOrNull, which runTest's
        // virtual clock would expire the moment the fake network thread is busy.
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics(105L) } returns null
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(
            lrcResponse(name = "Paper Lanterns", artistName = "Test Singer", duration = 200.0)
        )
        val inserted = mutableListOf<LyricsEntity>()
        coEvery { lyricsDao.insert(capture(inserted)) } returns Unit
        val http = fakeClient { request ->
            when {
                request.url.host == "lyrics-api.binimum.org" ->
                    Triple(307, "", "https://lrc.red/api/v1?" + request.url.encodedQuery)
                request.url.host == "lrc.red" && request.url.encodedPath == "/api/v1" ->
                    Triple(200, BiniFixtures.results(BiniFixtures.row("USAB12000105")), null)
                request.url.host == "lrc.red" -> Triple(200, BiniFixtures.ttml(), null)
                else -> Triple(404, "", null)
            }
        }
        val repository = LyricsRepositoryImpl(
            context = testContext(),
            lrcLibApiService = apiService,
            lyricsDao = lyricsDao,
            okHttpClient = http
        )
        val song = testSong(id = "105", title = "Paper Lanterns", artist = "Test Singer", duration = 200_000L)
            .copy(album = "Harbor Songs")

        val online = repository.findOnlineSyncedLyrics(song)
        assertThat(online?.source).isEqualTo("BiniLyrics")

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        val (lyrics, raw) = result.getOrThrow()
        assertThat(lyrics.document?.metadata?.source).isEqualTo("BiniLyrics")
        assertThat(lyrics.synced!!.first().words).isNotEmpty()
        assertThat(raw).contains("pixelplay-lyrics")
        assertThat(inserted.single().content).isEqualTo(raw)
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun searchRemote_listsBiniLyricsMatchFirstWithItsSourceName() = runTest {
        val apiService = mockk<LrcLibApiService>(relaxed = true)
        coEvery { apiService.searchLyrics(any(), any(), any(), any()) } returns arrayOf(
            lrcResponse(name = "Paper Lanterns", artistName = "Test Singer", duration = 200.0)
        )
        val http = fakeClient { request ->
            when {
                request.url.host == "lyrics-api.binimum.org" ->
                    Triple(307, "", "https://lrc.red/api/v1?" + request.url.encodedQuery)
                request.url.host == "lrc.red" && request.url.encodedPath == "/api/v1" ->
                    Triple(200, BiniFixtures.results(BiniFixtures.row("USAB12000106")), null)
                request.url.host == "lrc.red" -> Triple(200, BiniFixtures.ttml(), null)
                else -> Triple(404, "", null)
            }
        }
        val repository = LyricsRepositoryImpl(testContext(), apiService, mockk(relaxed = true), http)
        val song = testSong(id = "106", title = "Paper Lanterns", artist = "Test Singer", duration = 200_000L)

        val (_, results) = repository.searchRemote(song).getOrThrow()

        assertThat(results.map { it.source }).containsExactly("BiniLyrics", "LRCLIB").inOrder()
        assertThat(results.first().record.id).isLessThan(0)
        assertThat(results.first().lyrics.synced).isNotEmpty()
        http.dispatcher.executorService.shutdown()
    }

    /** No network: every request is answered locally (404 unless [reply] says otherwise). */
    private fun fakeClient(reply: (Request) -> Triple<Int, String, String?>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body, location) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .apply { location?.let { header("Location", it) } }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun offlineClient(): OkHttpClient = fakeClient { Triple(404, "", null) }

    private fun testContext(filesDir: File = Files.createTempDirectory("pixelplay-lyrics-test").toFile()): Context {
        return mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
    }

    private fun testSong(
        id: String,
        title: String,
        artist: String,
        path: String = "",
        duration: Long
    ): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = 5L,
            album = "Album",
            albumId = 8L,
            path = path,
            contentUriString = "",
            albumArtUriString = null,
            duration = duration,
            lyrics = null,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
        )
    }

    private fun lrcResponse(
        name: String,
        artistName: String,
        duration: Double
    ): LrcLibResponse {
        return LrcLibResponse(
            id = name.hashCode(),
            name = name,
            artistName = artistName,
            albumName = "Album",
            duration = duration,
            plainLyrics = null,
            syncedLyrics = "[00:01.00]First line\n[00:05.00]Second line"
        )
    }
}
