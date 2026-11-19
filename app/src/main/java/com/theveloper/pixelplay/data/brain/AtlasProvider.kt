package com.theveloper.pixelplay.data.brain

import android.content.Context
import com.theveloper.pixelplay.brain.GenreAtlas
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Where the genre atlas comes from: the bundled asset in the app, a plain object in tests. */
interface AtlasProvider {
    /** The parsed atlas, or null when it cannot be read. Parsing runs off the main thread; never called at app start. */
    suspend fun atlas(): GenreAtlas?

    /** The atlas if it is already in memory; never loads. */
    fun loadedOrNull(): GenreAtlas?
}

/**
 * Loads `genre-atlas-v1.json.gz` from the app's assets (docs/brain-spec.md, 3.5) the first time something needs a genre: about 175 KB
 * of gzip, 500 KB of JSON, parsed once on `Dispatchers.Default` into the id table and the two name maps. Nothing in the app asks for
 * it at start: the refresh worker, the Developer screen and (from the next milestone) Home do.
 */
@Singleton
class GenreAtlasStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AtlasProvider {
    private val lock = Mutex()

    @Volatile
    private var cached: GenreAtlas? = null

    /** When the last read failed (elapsed realtime): a bad asset is not parsed again by every caller of one Home plan. */
    @Volatile
    private var failedAtMs = NEVER

    override fun loadedOrNull(): GenreAtlas? = cached

    override suspend fun atlas(): GenreAtlas? {
        cached?.let { return it }
        if (recentlyFailed()) return null
        return lock.withLock {
            cached ?: if (recentlyFailed()) null else load().also {
                cached = it
                if (it == null) failedAtMs = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    private fun recentlyFailed(): Boolean {
        val at = failedAtMs
        return at != NEVER && android.os.SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS
    }

    private suspend fun load(): GenreAtlas? = withContext(Dispatchers.Default) {
        try {
            val startedNanos = System.nanoTime()
            val bytes = context.assets.open(ASSET).use { GZIPInputStream(it).readBytes() }
            GenreAtlas.parse(String(bytes, Charsets.UTF_8)).also {
                // How long the first genre lookup costs on this phone (never at app start; docs/performance-report.md).
                com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics.recordTiming("brain_atlas_load", (System.nanoTime() - startedNanos) / 1_000_000)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: the genre atlas could not be loaded")
            null
        }
    }

    companion object {
        const val ASSET = "genre-atlas-v1.json.gz"
        private const val NEVER = Long.MIN_VALUE
        private const val RETRY_AFTER_MS = 10L * 60 * 1000
    }
}
