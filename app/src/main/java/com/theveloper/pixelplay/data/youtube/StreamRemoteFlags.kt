package com.theveloper.pixelplay.data.youtube

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one remote switch Android reads for streaming: `innertube.hedge` in the iOS repo's
 * `remote/config.json` (owner decision: one switch for both apps; Android keeps its own
 * InnerTube client table and ignores the file's iOS profiles). It ships `"enabled": false`;
 * flipping it on `main` turns overlapping clients on without a release.
 *
 * Streaming speed R4 applies: [current] never waits. The first call reads the saved copy and
 * fetches in the background; until then (and whenever anything fails) hedging stays off.
 *
 * Fetched with the app's default OkHttpClient on purpose: this talks to GitHub, not YouTube
 * (the YouTube rule about @YouTubeOkHttpClient doesn't apply), and its User-Agent is fine here.
 */
@Singleton
class StreamRemoteFlags @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)
    private val cacheFile: File by lazy { File(context.filesDir, CACHE_FILE_NAME) }

    @Volatile
    private var hedging: StreamHedging? = null

    // Wall-clock time of the last fetch attempt (the saved file's date across launches).
    @Volatile
    private var lastAttemptAtMs = 0L

    @Volatile
    private var loadedSavedCopy = false

    /** The hedging settings to use now, or null (off). Starts a background refresh when due. */
    fun current(): StreamHedging? {
        refreshInBackgroundIfDue()
        return hedging
    }

    private fun refreshInBackgroundIfDue() {
        if (loadedSavedCopy && System.currentTimeMillis() - lastAttemptAtMs < REFRESH_INTERVAL_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!loadedSavedCopy) {
                    val saved = cacheFile
                    if (saved.isFile) {
                        hedging = StreamHedging.parse(saved.readText())
                        lastAttemptAtMs = saved.lastModified()
                    }
                    loadedSavedCopy = true
                }
                if (System.currentTimeMillis() - lastAttemptAtMs >= REFRESH_INTERVAL_MS) fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Remote streaming flags unavailable; overlapping clients stay as they were")
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun fetch() {
        lastAttemptAtMs = System.currentTimeMillis()
        // A failed attempt tries again in 30 minutes: not on every resolve, and not only after
        // 6 hours (an offline first launch would otherwise miss a switched-on flag all day).
        fun retrySoon() {
            lastAttemptAtMs = System.currentTimeMillis() - REFRESH_INTERVAL_MS + RETRY_AFTER_FAILURE_MS
        }
        val request = Request.Builder().url(CONFIG_URL).header("Accept", "application/json").build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    retrySoon()
                    return
                }
                val text = response.body.string()
                if (text.length > MAX_CONFIG_CHARS) return
                hedging = StreamHedging.parse(text)
                runCatching { cacheFile.writeText(text) }
                Timber.d("Remote streaming flags refreshed: overlapping clients %s", if (hedging != null) "ON" else "off")
            }
        } catch (e: IOException) {
            retrySoon()
            throw e
        }
    }

    private companion object {
        const val CONFIG_URL = "https://raw.githubusercontent.com/RedSn0w1877/PixlAudio-iOS/main/remote/config.json"
        const val CACHE_FILE_NAME = "stream-remote-flags.json"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60_000L
        const val RETRY_AFTER_FAILURE_MS = 30 * 60_000L
        const val MAX_CONFIG_CHARS = 64 * 1024
    }
}
