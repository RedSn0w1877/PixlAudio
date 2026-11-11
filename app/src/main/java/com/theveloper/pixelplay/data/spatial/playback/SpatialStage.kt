package com.theveloper.pixelplay.data.spatial.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.theveloper.pixelplay.data.spatial.SpatialContract
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared side of the on-device stage: the parameters every [SpatialStageProcessor] reads (both players' sinks and
 * every rebuilt player get the same object, like `MidSideVocalProcessor`'s provider), the table (read once, off the main
 * thread, the first time a profile is selected), and the registry of which items play a spatial render.
 *
 * The registry is written where the player decides an item's file (the data source's first open, a baked render URI)
 * and read by the stage when that item's first sample arrives, so the bypass of §6.3 ("never spatialize twice") starts
 * exactly at the item boundary.
 */
@Singleton
class SpatialStage @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SpatialStageLink {

    @Volatile
    override var state: SpatialStageState = SpatialStageState.OFF
        private set

    /**
     * The Spotify ids Spatial audio is turned off for (`spatial.songsOff`), for the start resolver on the loading
     * thread. Written by [RealSpatialPlayback.apply].
     */
    @Volatile
    var songsOff: Set<String> = emptySet()

    /** The parsed table, once read. */
    @Volatile
    var tables: SpatialPreviewTables? = null
        private set

    private val loading = AtomicBoolean(false)
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // spotify id -> the render that id's latest data-source decision chose (absent: the stream).
    private val renderBySpotifyId = ConcurrentHashMap<String, SpatialRender>()

    // file:// URI of a render handed out by the resolver (baked into crossfade and restored items).
    private val renderByUri = ConcurrentHashMap<String, SpatialRender>()

    /** Main thread: a stream reached a stage. */
    @Volatile
    var reportListener: ((SpatialStreamReport) -> Unit)? = null

    /** Main thread: the table was read. */
    @Volatile
    var tablesListener: (() -> Unit)? = null

    /** A processor for one player's chain; one per sink. */
    fun newProcessor(): SpatialStageProcessor = SpatialStageProcessor(this)

    /** Main thread: the selected profile and whether Spatial audio is active (§6.3). */
    fun update(profile: SpatialProfile?, wet: Boolean) {
        if (profile != null) ensureTables()
        val current = state
        val tables = tables
        if (current.profile == profile && current.wet == wet && current.tables === tables) return
        state = SpatialStageState(tables, profile, wet)
    }

    private fun ensureTables() {
        if (tables != null || !loading.compareAndSet(false, true)) return
        loadScope.launch {
            val parsed = try {
                val text = context.assets.open(SpatialContract.PREVIEW_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
                SpatialPreviewTables.parse(text)
            } catch (e: Exception) {
                // A broken table leaves the stage off; playback is never affected.
                Timber.tag(TAG).e(e, "The on-device spatial table could not be read")
                null
            }
            if (parsed != null) {
                tables = parsed
                mainHandler.post {
                    val current = state
                    state = SpatialStageState(parsed, current.profile, current.wet)
                    tablesListener?.invoke()
                }
            }
        }
    }

    /** Remembers that [render]'s file is a render, and returns its URI (what the player is given). */
    fun recordRender(render: SpatialRender): Uri {
        val uri = Uri.fromFile(render.file)
        rememberBounded(renderByUri, uri.toString(), render)
        return uri
    }

    /** The data source of [spotifyId]'s item decided its file: [render], or the stream (null). */
    fun recordItem(spotifyId: String, render: SpatialRender?) {
        if (render == null) renderBySpotifyId.remove(spotifyId) else rememberBounded(renderBySpotifyId, spotifyId, render)
    }

    /** Forgets the renders of [videoId] (null: all), after the library removed their files. */
    fun forget(videoId: String?) {
        renderBySpotifyId.values.removeIf { videoId == null || it.videoId == videoId }
        renderByUri.values.removeIf { videoId == null || it.videoId == videoId }
    }

    override fun renditionOf(uri: String?): SpatialRender? =
        SpatialItemRendition.of(uri, renderBySpotifyId, renderByUri)

    override fun onStreamStarted(mediaId: String?, render: SpatialRender?, formatSupported: Boolean) {
        if (reportListener == null) return
        val report = SpatialStreamReport(mediaId, render, formatSupported)
        mainHandler.post { reportListener?.invoke(report) }
    }

    private fun <K : Any> rememberBounded(map: ConcurrentHashMap<K, SpatialRender>, key: K, render: SpatialRender) {
        if (map.size >= MAX_REMEMBERED) map.clear()
        map[key] = render
    }

    private companion object {
        const val TAG = "SpatialStage"
        const val MAX_REMEMBERED = 512
    }
}

/** Which render an item's URI plays (pure, so the per-item rule is unit-tested). */
object SpatialItemRendition {
    private const val SPOTIFY_PREFIX = "spotify://"

    /**
     * `spotify://<id>`: what that id's data source chose; a `file://` URI baked into an item (the crossfade player, a
     * restored queue): whether the resolver handed it out as a render; anything else: the stream (null).
     */
    fun of(
        uri: String?,
        bySpotifyId: Map<String, SpatialRender>,
        byUri: Map<String, SpatialRender>,
    ): SpatialRender? {
        if (uri == null) return null
        if (uri.startsWith(SPOTIFY_PREFIX)) return spotifyIdOf(uri)?.let { bySpotifyId[it] }
        return byUri[uri]
    }

    /** `spotify://<id>` → `<id>`. */
    fun spotifyIdOf(uri: String): String? =
        uri.removePrefix(SPOTIFY_PREFIX).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
}
