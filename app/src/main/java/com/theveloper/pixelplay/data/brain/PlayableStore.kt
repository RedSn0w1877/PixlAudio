package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.EphemeralTrackLookup
import com.theveloper.pixelplay.data.brain.store.PlayableDao
import com.theveloper.pixelplay.data.model.Song
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The `playable` table (docs/brain-spec.md, 7.2): every track from outside the library that was put in
 * a queue, so its `spotify://<synthId>` uri resolves to audio, and so Recently Played can name it.
 *
 * A track must be [register]ed BEFORE its media item reaches the player: the stream proxy asks
 * [videoIdFor] the moment ExoPlayer opens the uri. The small in-memory map answers that without a
 * database read for anything registered in this process; a restored queue falls through to the table.
 */
@Singleton
class PlayableStore @Inject constructor(
    private val dao: PlayableDao,
) : EphemeralTrackLookup {

    private val videoIdBySynthId = ConcurrentHashMap<String, String>()

    override suspend fun videoIdFor(synthId: String): String? {
        videoIdBySynthId[synthId]?.let { return it }
        return try {
            dao.videoIdFor(synthId)?.also { remember(synthId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "playable lookup failed")
            null
        }
    }

    /** Stores [tracks] so they can play. Suspends until the rows are written. */
    suspend fun register(tracks: List<EngineTrack>, nowMs: Long = System.currentTimeMillis()) {
        if (tracks.isEmpty()) return
        for (track in tracks) remember(track.synthId, track.videoId)
        dao.upsert(tracks.map { it.toPlayable(nowMs) })
    }

    /** Songs for the media ids in [songIds] that are engine tracks; ids that are not are left out. */
    suspend fun songsFor(songIds: Collection<String>): List<Song> {
        val numeric = songIds.mapNotNull { it.toLongOrNull() }
        if (numeric.isEmpty()) return emptyList()
        val out = ArrayList<Song>(numeric.size)
        for (chunk in numeric.chunked(SQLITE_CHUNK)) {
            dao.getBySongIds(chunk).mapTo(out, EngineTrack::songOf)
        }
        return out
    }

    suspend fun bySongId(songId: String) = songId.toLongOrNull()?.let { dao.getBySongId(it) }

    /**
     * Deletes rows nobody used for [RETENTION_MS], except the ones in [keepSongIds] (the saved queue and
     * the recent history). Called at most once a day from the autoplay path; never at app start.
     */
    suspend fun prune(keepSongIds: Collection<String>, nowMs: Long = System.currentTimeMillis()): Int {
        val keepNumeric = keepSongIds.mapNotNull { it.toLongOrNull() }
        val keepSynth = ArrayList<String>()
        for (chunk in keepNumeric.chunked(SQLITE_CHUNK)) dao.getBySongIds(chunk).mapTo(keepSynth) { it.synthId }
        // SQLite caps bound variables near 999; a longer keep-list keeps the newest ones it can name.
        return dao.deleteUnused(nowMs - RETENTION_MS, keepSynth.take(SQLITE_CHUNK))
    }

    private fun remember(synthId: String, videoId: String) {
        if (videoIdBySynthId.size > MEMORY_LIMIT) videoIdBySynthId.clear()
        videoIdBySynthId[synthId] = videoId
    }

    companion object {
        const val RETENTION_MS = 60L * 24 * 60 * 60 * 1000
        private const val SQLITE_CHUNK = 900
        private const val MEMORY_LIMIT = 2_000
    }
}
