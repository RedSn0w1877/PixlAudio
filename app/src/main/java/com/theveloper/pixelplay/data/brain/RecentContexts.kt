package com.theveloper.pixelplay.data.brain

import android.content.Context
import androidx.core.util.AtomicFile
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** One thing the listener played from, not one song: a playlist, an album or other queue, a mix or a station (8.1 "Jump back in"). */
@Serializable
data class RecentContext(
    val key: String,
    val kind: Int,
    val title: String,
    val art: String? = null,
    /** The library songs it held when it started (at most [RecentContexts.MAX_SONGS]), to play it again. */
    val songIds: List<String> = emptyList(),
    /** For a made-for-you mix: its id, so a tap opens the mix instead. */
    val mixId: String = "",
    val atMs: Long = 0L,
) {
    companion object {
        const val PLAYLIST = 0
        const val MIX = 1
        const val STATION = 2
        const val QUEUE = 3
    }
}

/**
 * The last distinct contexts the listener started playing (docs/brain-spec.md, 8.1 "Jump back in": the last 6 distinct
 * contexts, not songs). Written once per started queue from the dispatch path, kept in one small file under `files/brain`.
 * Reading it is a file read; nothing here touches the database or the network.
 */
@Singleton
class RecentContexts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: BrainClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val file: AtomicFile by lazy { AtomicFile(File(File(context.filesDir, "brain").also { it.mkdirs() }, "recent_contexts.json")) }
    @Volatile private var memory: List<RecentContext>? = null

    /** A queue started. [songs] are the songs of the queue in order. Never blocks the caller: the file is written in the background. */
    fun note(queueName: String, playlistId: String?, tag: BrainItemTag?, songs: List<Song>) {
        val c = contextOf(queueName, playlistId, tag, songs) ?: return
        scope.launch {
            lock.withLock {
                val list = (memory ?: read()).toMutableList()
                list.removeAll { it.key == c.key }
                list.add(0, c)
                val kept = list.take(KEEP)
                memory = kept
                write(kept)
            }
        }
    }

    /** The newest [n] contexts, newest first. */
    suspend fun last(n: Int): List<RecentContext> = lock.withLock { (memory ?: read().also { memory = it }).take(n) }

    private fun read(): List<RecentContext> = try {
        val f = File(context.filesDir, "brain/recent_contexts.json")
        if (!f.isFile || f.length() > 200_000L) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RecentContext.serializer()), file.readFully().toString(Charsets.UTF_8))
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<RecentContext>) {
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RecentContext.serializer()), list).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            out?.let { file.failWrite(it) }
            Timber.w(e, "brain: saving the recent contexts failed")
        }
    }

    private fun contextOf(queueName: String, playlistId: String?, tag: BrainItemTag?, songs: List<Song>): RecentContext? {
        if (songs.isEmpty() || queueName.isBlank() || queueName in IGNORED) return null
        val ids = songs.asSequence().filterNot { EngineTrack.isEngineOnly(it) }.take(MAX_SONGS).map { it.id }.toList()
        val art = songs.firstOrNull { !it.albumArtUriString.isNullOrBlank() }?.albumArtUriString
        val now = clock.nowMs()
        val surface = tag?.surface
        return when {
            !playlistId.isNullOrBlank() -> RecentContext("p:$playlistId", RecentContext.PLAYLIST, queueName, art, ids, atMs = now)
            surface == "dailyMix" || surface == "yourMix" || surface == "namedMix" ->
                RecentContext("m:${tag.surfaceId}", RecentContext.MIX, queueName, art, ids, mixId = tag.surfaceId.orEmpty(), atMs = now)
            queueName == "Your Mix" -> RecentContext("m:your", RecentContext.MIX, queueName, art, ids, mixId = "your", atMs = now)
            queueName.startsWith("Daily Mix") -> RecentContext("m:daily", RecentContext.MIX, queueName, art, ids, mixId = "daily", atMs = now)
            surface == "station" -> RecentContext("s:${tag.surfaceId}", RecentContext.STATION, queueName, art, ids, atMs = now)
            else -> RecentContext("q:$queueName", RecentContext.QUEUE, queueName, art, ids, atMs = now)
        }
    }

    companion object {
        const val KEEP = 12
        const val MAX_SONGS = 60
        private val IGNORED = setOf("None", "Recently Played", "Just Added", "Shuffle All", "Taizo")
    }
}
