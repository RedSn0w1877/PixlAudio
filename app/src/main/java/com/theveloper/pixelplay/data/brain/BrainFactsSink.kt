package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.SpotifyFactDao
import com.theveloper.pixelplay.data.brain.store.SpotifyFactEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.di.AppScope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the facts the Spotify sync already receives with every track and used to drop (docs/brain-spec.md,
 * 13.1): the artists' Spotify ids, the album and its release date, whether the track is explicit. It is
 * called from the one place that maps a Spotify track to a library row, so a sync pays nothing for it: a
 * track is queued in memory and written in batches a moment later, off the sync's thread.
 *
 * The facts shape picks (a year, an artist id). Nothing here trains anything (owner question 1), and the
 * switch "Use my Spotify music to shape picks" turns it off.
 */
interface BrainFactsSink {
    fun record(track: SpotifyTrack)

    object None : BrainFactsSink {
        override fun record(track: SpotifyTrack) = Unit
    }
}

@Singleton
class RoomBrainFactsSink @Inject constructor(
    private val dao: SpotifyFactDao,
    private val switch: BrainSwitch,
    private val clock: BrainClock,
    @AppScope private val scope: CoroutineScope,
) : BrainFactsSink {
    private val pending = ConcurrentLinkedQueue<SpotifyTrack>()
    private val flushing = AtomicBoolean(false)

    override fun record(track: SpotifyTrack) {
        if (track.id.isNullOrBlank() || track.isLocal == true) return
        // A sync of a very large library is bounded: the rest arrives at the next sync.
        if (pending.size < MAX_PENDING) pending.add(track)
        if (flushing.compareAndSet(false, true)) {
            scope.launch {
                try {
                    delay(FLUSH_DELAY_MS)
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "brain: Spotify facts could not be saved")
                } finally {
                    flushing.set(false)
                    if (pending.isNotEmpty() && flushing.compareAndSet(false, true)) {
                        // More came in while this batch was written.
                        scope.launch {
                            try {
                                delay(FLUSH_DELAY_MS)
                                flush()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.d(e, "brain: Spotify facts could not be saved")
                            } finally {
                                flushing.set(false)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun flush() {
        val enabled = switch.spotifyShapesPicksFlow.first()
        val batch = ArrayList<SpotifyTrack>(MAX_BATCH)
        while (true) {
            batch.clear()
            while (batch.size < MAX_BATCH) batch.add(pending.poll() ?: break)
            if (batch.isEmpty()) return
            if (!enabled) continue // switched off: what was queued is dropped, not kept
            val now = clock.nowMs()
            dao.upsert(batch.distinctBy { it.id }.map { factOf(it, now) })
        }
    }

    companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val MAX_BATCH = 200
        const val MAX_PENDING = 20_000

        fun factOf(track: SpotifyTrack, nowMs: Long): SpotifyFactEntity = SpotifyFactEntity(
            spotifyId = track.id!!,
            isrc = track.externalIds?.isrc?.takeIf { it.isNotBlank() },
            artistIds = track.artists.orEmpty().mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }.joinToString(","),
            albumId = track.album?.id?.takeIf { it.isNotBlank() },
            releaseDate = track.album?.releaseDate?.takeIf { it.isNotBlank() },
            releasePrecision = track.album?.releaseDatePrecision?.takeIf { it.isNotBlank() },
            explicit = track.explicit,
            seenAtMs = nowMs,
        )
    }
}
