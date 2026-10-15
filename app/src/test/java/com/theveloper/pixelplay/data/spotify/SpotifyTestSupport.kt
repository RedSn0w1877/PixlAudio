package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/** In-memory replacement for the SharedPreferences-backed import store. */
class MemoryImportStore : SpotifyImportStore(mockk(relaxed = true)) {
    var raw: String? = null
    override fun readRaw(): String? = raw
    override fun writeRaw(raw: String?) {
        this.raw = raw
    }
}

/** An HTTP error response that carries headers (Retrofit's `Response.error` alone cannot). */
fun <T> errorResponse(code: Int, vararg headers: Pair<String, String>): Response<T> {
    val raw = okhttp3.Response.Builder()
        .code(code)
        .message("HTTP $code")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://api.spotify.com/v1/test").build())
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return Response.error("{}".toResponseBody(), raw)
}

/** Virtual clock + sleeper so retry/backoff/Retry-After logic runs instantly and is observable. */
class FakeTime {
    @Volatile
    var now = 0L
    val sleeps: MutableList<Long> = java.util.concurrent.CopyOnWriteArrayList()
    val clock: () -> Long = { now }
    val sleeper: suspend (Long) -> Unit = { ms ->
        synchronized(this) {
            sleeps += ms
            now += ms
        }
    }
}

class SpotifyFixture(scopesMissing: Set<String> = emptySet()) {
    val time = FakeTime()
    val api = mockk<SpotifyApiService>()
    val dao = mockk<SpotifyDao>(relaxed = true) {
        every { getAllMatchStatesFlow() } returns flowOf(emptyList())
    }
    val auth = mockk<SpotifyAuthManager>(relaxed = true) {
        every { isLoggedIn } returns MutableStateFlow(true)
        coEvery { authorizationHeader() } returns "Bearer test"
        every { missingScopes(any()) } returns scopesMissing
    }
    val store = MemoryImportStore()
    val musicDao = mockk<MusicDao>(relaxed = true)
    val playlistPrefs = mockk<PlaylistPreferencesRepository>(relaxed = true)

    fun repository(): SpotifyRepository = SpotifyRepository(
        mockk(), api, auth, dao, musicDao, playlistPrefs, store
    ).also {
        it.executor = SpotifyApiExecutor(auth, time.clock, time.sleeper)
    }
}
