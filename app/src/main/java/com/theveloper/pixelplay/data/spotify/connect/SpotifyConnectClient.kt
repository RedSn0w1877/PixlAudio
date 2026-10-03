package com.theveloper.pixelplay.data.spotify.connect

import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlayOffset
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlayRequest
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlayerApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.network.spotify.SpotifyTransferRequest
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Response
import timber.log.Timber

/** A Connect call's answer: the value, or what went wrong (never an exception). */
sealed interface ConnectResult<out T> {
    data class Ok<T>(val value: T) : ConnectResult<T>
    data class Err(val error: SpotifyConnectError) : ConnectResult<Nothing>
}

/** Throws nothing: maps an [ConnectResult.Err] to null. */
fun <T> ConnectResult<T>.valueOrNull(): T? = (this as? ConnectResult.Ok)?.value

/**
 * The Web API Player endpoints over the existing Spotify session (api.spotify.com, the regular
 * OkHttp client — Spotify isn't YouTube, so the User-Agent rewrite is harmless here).
 *
 * - The access token comes from [SpotifyAuthManager], which refreshes it ahead of expiry and
 *   persists the rotated refresh token Spotify hands out on every refresh.
 * - A 401 forces one refresh and one retry.
 * - A 429 opens a Retry-After gate: every call before it ends fails fast with
 *   [SpotifyConnectError.RateLimited] (the poller sleeps it out) instead of hammering the API.
 * - All work runs on [Dispatchers.IO]; cancellation propagates, everything else becomes an error value.
 *
 * Mirrors iOS `SpotifyConnectClient`.
 */
@Singleton
class SpotifyConnectClient @Inject constructor(
    private val playerApi: SpotifyPlayerApiService,
    private val api: SpotifyApiService,
    private val authManager: SpotifyAuthManager
) {
    /** Test seams. */
    internal var nowMs: () -> Long = System::currentTimeMillis
    internal var sleep: suspend (Long) -> Unit = { delay(it) }

    @Volatile
    private var notBeforeMs = 0L

    /** How long the Retry-After gate still holds (0 = open). */
    val retryAfterRemainingMs: Long get() = (notBeforeMs - nowMs()).coerceAtLeast(0L)

    private suspend fun <T> send(label: String, block: suspend (authorization: String) -> Response<T>): ConnectResult<Response<T>> =
        withContext(Dispatchers.IO) {
            val wait = notBeforeMs - nowMs()
            if (wait > 0) return@withContext ConnectResult.Err(SpotifyConnectError.RateLimited(wait))
            var refreshed = false
            while (true) {
                val authorization = authManager.authorizationHeader()
                    ?: return@withContext ConnectResult.Err(SpotifyConnectError.NotSignedIn)
                val response = try {
                    block(authorization)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "[%s] request failed", label)
                    return@withContext ConnectResult.Err(SpotifyConnectError.Network(e.message))
                }
                if (response.isSuccessful) return@withContext ConnectResult.Ok(response)
                if (response.code() == 401 && !refreshed) {
                    refreshed = true
                    if (authManager.forceRefresh().isSuccess) continue
                    return@withContext ConnectResult.Err(SpotifyConnectError.NotSignedIn)
                }
                val body = runCatching { response.errorBody()?.string() }.getOrNull()
                val error = SpotifyConnectErrors.map(response.code(), body, response.headers()["Retry-After"])
                if (error is SpotifyConnectError.RateLimited) notBeforeMs = nowMs() + error.retryAfterMs
                Timber.tag(TAG).w("[%s] HTTP %d %s", label, response.code(), body?.take(300).orEmpty())
                return@withContext ConnectResult.Err(error)
            }
            @Suppress("UNREACHABLE_CODE")
            ConnectResult.Err(SpotifyConnectError.Network(null))
        }

    private suspend fun command(label: String, block: suspend (String) -> Response<Unit>): ConnectResult<Unit> =
        when (val r = send(label, block)) {
            is ConnectResult.Ok -> ConnectResult.Ok(Unit)
            is ConnectResult.Err -> r
        }

    // ─── Reads ────────────────────────────────────────────────────────────────────────────

    /** `GET /me/player/devices`. */
    suspend fun devices(): ConnectResult<List<SpotifyConnectDevice>> =
        when (val r = send("devices") { playerApi.getDevices(it) }) {
            is ConnectResult.Ok -> ConnectResult.Ok(r.value.body()?.devices.orEmpty().map(SpotifyConnectDevice::from))
            is ConnectResult.Err -> r
        }

    /** `GET /me/player`; null for 204 (nothing playing on any device). */
    suspend fun playbackState(): ConnectResult<SpotifyPlaybackSnapshot?> =
        when (val r = send("state") { playerApi.getPlaybackState(it) }) {
            is ConnectResult.Ok -> {
                val body = r.value.body()
                ConnectResult.Ok(if (r.value.code() == 204 || body == null) null else SpotifyPlaybackSnapshot.from(body))
            }
            is ConnectResult.Err -> r
        }

    // ─── Commands ─────────────────────────────────────────────────────────────────────────

    suspend fun transfer(deviceId: String, play: Boolean): ConnectResult<Unit> =
        command("transfer") { playerApi.transferPlayback(it, SpotifyTransferRequest(listOf(deviceId), play)) }

    /** `PUT /me/player/play` with `{uris, offset: {position: 0}, position_ms}` (the window starts at the current entry). */
    suspend fun play(deviceId: String, uris: List<String>, positionMs: Long): ConnectResult<Unit> =
        command("play") {
            playerApi.play(
                it,
                deviceId,
                SpotifyPlayRequest(uris = uris, offset = SpotifyPlayOffset(0), positionMs = positionMs.coerceAtLeast(0L))
            )
        }

    /**
     * Starts [uris] on the device: transfers first when it isn't the active device, and once more
     * when `play` answers 404 `NO_ACTIVE_DEVICE` (then one more `play` after a short wake-up pause).
     */
    suspend fun start(deviceId: String, isActive: Boolean, uris: List<String>, positionMs: Long): ConnectResult<Unit> {
        if (!isActive) {
            val transferred = transfer(deviceId, play = false)
            if (transferred is ConnectResult.Err) return transferred
        }
        val first = play(deviceId, uris, positionMs)
        if (first is ConnectResult.Err && first.error == SpotifyConnectError.NoActiveDevice) {
            if (isActive) {
                val transferred = transfer(deviceId, play = false)
                if (transferred is ConnectResult.Err) return transferred
            }
            sleep(WAKE_RETRY_DELAY_MS)
            return play(deviceId, uris, positionMs)
        }
        return first
    }

    suspend fun resume(deviceId: String) = command("resume") { playerApi.resume(it, deviceId) }

    suspend fun pause(deviceId: String) = command("pause") { playerApi.pause(it, deviceId) }

    suspend fun next(deviceId: String) = command("next") { playerApi.next(it, deviceId) }

    suspend fun previous(deviceId: String) = command("previous") { playerApi.previous(it, deviceId) }

    suspend fun seek(deviceId: String, positionMs: Long) =
        command("seek") { playerApi.seek(it, positionMs.coerceAtLeast(0L), deviceId) }

    suspend fun setVolume(deviceId: String, percent: Int) =
        command("volume") { playerApi.setVolume(it, percent.coerceIn(0, 100), deviceId) }

    suspend fun setShuffle(deviceId: String, enabled: Boolean) =
        command("shuffle") { playerApi.setShuffle(it, enabled, deviceId) }

    /** [state] is "track", "context" or "off". */
    suspend fun setRepeat(deviceId: String, state: String) =
        command("repeat") { playerApi.setRepeat(it, state, deviceId) }

    /**
     * One track search for the resolver; null when the request failed (a 429 included — it is
     * retried later). No `limit`: Spotify answers `400 Invalid limit` to it (see SpotifyRepository).
     */
    suspend fun searchTracks(query: String): List<SpotifyTrack>? =
        when (val r = send("search") { api.search(it, query, type = "track") }) {
            is ConnectResult.Ok -> r.value.body()?.tracks?.items.orEmpty()
            is ConnectResult.Err -> null
        }

    companion object {
        private const val TAG = "SpotifyConnect"
        /** A device that was just woken by a transfer may still answer `NO_ACTIVE_DEVICE`: one retry after this. */
        const val WAKE_RETRY_DELAY_MS = 800L
    }
}
