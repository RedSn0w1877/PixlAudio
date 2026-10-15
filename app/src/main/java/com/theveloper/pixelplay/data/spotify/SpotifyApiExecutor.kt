package com.theveloper.pixelplay.data.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import timber.log.Timber
import java.io.IOException

/**
 * What a Spotify Web API call ended in, with every HTTP failure told apart.
 *
 * The old `apiCall` collapsed all of these into `null`, so a 403 on someone else's playlist,
 * a 429 and a dropped connection looked identical to the importer, and a single 403 raised
 * the "reconnect Spotify" banner for the whole account.
 */
sealed interface SpotifyResult<out T> {
    data class Ok<T>(val body: T) : SpotifyResult<T>

    /** 403 with a valid token: Spotify refuses this one resource (not an expired login). */
    data class Forbidden(val message: String?) : SpotifyResult<Nothing>

    data class NotFound(val message: String?) : SpotifyResult<Nothing>

    /** 400: Spotify did not like a parameter (`limit`, `fields`, ...). */
    data class BadRequest(val message: String?) : SpotifyResult<Nothing>

    /** No session, or still 401 after one token refresh: the user has to sign in again. */
    data object Unauthorized : SpotifyResult<Nothing>

    /** 429 that did not clear within the retry budget; [retryAfterSeconds] is Spotify's ask. */
    data class RateLimited(val retryAfterSeconds: Long) : SpotifyResult<Nothing>

    /** 5xx (or any other unexpected status) that persisted through the bounded retries. */
    data class ServerError(val code: Int, val message: String?) : SpotifyResult<Nothing>

    /** Connection trouble that persisted through the bounded retries. */
    data class NetworkError(val cause: Throwable) : SpotifyResult<Nothing>
}

/** `body` when the call succeeded, null for every failure. For callers that do not care why. */
fun <T> SpotifyResult<T>.orNull(): T? = (this as? SpotifyResult.Ok<T>)?.body

/**
 * Runs Spotify calls through ONE shared rate limiter with the retry rules Spotify documents:
 *
 *  - 401: refresh the token and retry exactly once (and not at all when another coroutine
 *    already replaced the rejected token), then give up as [SpotifyResult.Unauthorized].
 *  - 429: honour `Retry-After`, for every caller at once (the whole app waits, nothing
 *    hammers). A wait longer than [MAX_INLINE_WAIT_SECONDS] is not slept through: the call
 *    returns [SpotifyResult.RateLimited] and later calls fail fast until the penalty passes,
 *    so the importer can stop cleanly and be rescheduled instead of sitting in a worker.
 *  - 5xx / network: bounded retries with exponential backoff.
 *  - 400/403/404: returned as-is. Repeating them cannot change the answer.
 */
class SpotifyApiExecutor(
    private val auth: SpotifyAuthManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val minIntervalMs: Long = MIN_REQUEST_INTERVAL_MS
) {
    private val gate = Mutex()

    // Written only under [gate]; read without it for the fail-fast check.
    @Volatile
    private var nextAllowedAtMs = 0L

    @Volatile
    private var penaltyUntilMs = 0L

    private suspend fun acquire() {
        gate.withLock {
            val wait = nextAllowedAtMs - clock()
            if (wait > 0) sleeper(wait)
            nextAllowedAtMs = clock() + minIntervalMs
        }
    }

    private suspend fun penalize(waitMs: Long) {
        gate.withLock {
            val until = clock() + waitMs
            if (until > nextAllowedAtMs) nextAllowedAtMs = until
            if (until > penaltyUntilMs) penaltyUntilMs = until
        }
    }

    suspend fun <T> execute(
        label: String,
        block: suspend (authorization: String) -> Response<T>
    ): SpotifyResult<T> {
        var rateLimitHits = 0
        var transientFailures = 0
        var refreshed = false

        while (true) {
            // A long penalty from an earlier 429 is not slept through by every queued call.
            val remainingPenaltySec = (penaltyUntilMs - clock()) / 1000L
            if (remainingPenaltySec > MAX_INLINE_WAIT_SECONDS) {
                return SpotifyResult.RateLimited(remainingPenaltySec)
            }

            acquire()
            val authorization = auth.authorizationHeader() ?: return SpotifyResult.Unauthorized

            val response = try {
                block(authorization)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                transientFailures++
                Timber.w(e, "[$label] Spotify request failed (attempt $transientFailures)")
                if (transientFailures >= MAX_TRANSIENT_ATTEMPTS) return SpotifyResult.NetworkError(e)
                sleeper(backoffMs(transientFailures))
                continue
            }

            val code = response.code()
            when {
                response.isSuccessful -> {
                    val body = response.body()
                        ?: return SpotifyResult.NetworkError(IOException("Spotify returned an empty body (HTTP $code)"))
                    return SpotifyResult.Ok(body)
                }

                code == 401 -> {
                    if (refreshed) return SpotifyResult.Unauthorized
                    refreshed = true
                    if (!auth.refreshAfterRejected(authorization)) return SpotifyResult.Unauthorized
                }

                code == 429 -> {
                    rateLimitHits++
                    val headerSeconds = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
                    val waitSeconds = (headerSeconds ?: (2L shl (rateLimitHits - 1)).coerceAtMost(30L))
                        .coerceAtLeast(1L)
                    Timber.w("[$label] Spotify asked us to wait ${waitSeconds}s (429, hit $rateLimitHits)")
                    penalize(waitSeconds * 1000L)
                    if (waitSeconds > MAX_INLINE_WAIT_SECONDS || rateLimitHits >= MAX_RATE_LIMIT_ATTEMPTS) {
                        return SpotifyResult.RateLimited(waitSeconds)
                    }
                    // Loop: the next acquire() sleeps the penalty for every caller.
                }

                code in 500..599 -> {
                    transientFailures++
                    Timber.w("[$label] Spotify HTTP $code (attempt $transientFailures)")
                    if (transientFailures >= MAX_TRANSIENT_ATTEMPTS) {
                        return SpotifyResult.ServerError(code, errorText(response))
                    }
                    sleeper(backoffMs(transientFailures))
                }

                code == 403 -> return SpotifyResult.Forbidden(errorText(response)).also {
                    Timber.w("[$label] Spotify HTTP 403: ${it.message}")
                }

                code == 404 -> return SpotifyResult.NotFound(errorText(response))

                code == 400 -> return SpotifyResult.BadRequest(errorText(response)).also {
                    Timber.w("[$label] Spotify HTTP 400: ${it.message}")
                }

                else -> return SpotifyResult.ServerError(code, errorText(response))
            }
        }
    }

    private fun errorText(response: Response<*>): String? {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
        return (raw ?: response.message()).take(300).ifBlank { null }
    }

    private fun backoffMs(failures: Int): Long = RETRY_BACKOFF_MS shl (failures - 1).coerceAtMost(4)

    companion object {
        const val MIN_REQUEST_INTERVAL_MS = 120L
        const val MAX_INLINE_WAIT_SECONDS = 90L
        private const val MAX_TRANSIENT_ATTEMPTS = 3
        private const val MAX_RATE_LIMIT_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 800L
    }
}
