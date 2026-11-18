package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Like, "Add to playlist" and Save turn a track from outside the library into a library track for good
 * (docs/brain-spec.md, 7.2 point 7). It goes through the existing YouTube Music importer, which gives
 * the row the very ids the track already plays under, so the queue item, the history entry and the
 * favourite all point at the new library row without any rewrite.
 */
@Singleton
class EngineTrackSaver @Inject constructor(
    private val playableStore: PlayableStore,
    private val musicDao: MusicDao,
    private val spotifyRepository: SpotifyRepository,
) {
    private val lock = Mutex()

    /**
     * Imports [songId] when it is an engine track that has no library row yet. Returns true when a row
     * was imported. Cheap for a library song: one indexed lookup in `brain.db` that finds nothing.
     */
    suspend fun saveIfEngineTrack(songId: String): Boolean = saveAll(listOf(songId)) > 0

    /** Imports every engine track among [songIds]; returns how many were imported. */
    suspend fun saveAll(songIds: Collection<String>): Int {
        if (songIds.isEmpty()) return 0
        return try {
            lock.withLock {
                val results = ArrayList<YouTubeSearchResult>()
                for (songId in songIds) {
                    val numericId = songId.toLongOrNull() ?: continue
                    val row = playableStore.bySongId(songId) ?: continue
                    if (musicDao.getSongByIdOnce(numericId) != null) continue
                    results.add(
                        YouTubeSearchResult(
                            videoId = row.videoId,
                            title = row.title,
                            artist = row.artistDisplay,
                            album = row.album,
                            durationSeconds = (row.durationMs / 1000L).toInt().takeIf { it > 0 },
                            thumbnailUrl = row.artUrl,
                        )
                    )
                }
                if (results.isEmpty()) 0 else {
                    spotifyRepository.importYouTubeMusicTracks(results)
                    results.size
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: saving an engine track failed")
            0
        }
    }
}
