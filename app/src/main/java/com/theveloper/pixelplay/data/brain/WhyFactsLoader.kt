package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.WhyFacts
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The supporting facts under a reason (docs/brain-spec.md, 8.8), read from the listener's own log when the
 * "Why this song" sheet opens: how many of their last plays by the artist they finished, how often they
 * finished this track, and the year when the song knows one. [WhyFacts] decides which are worth saying.
 *
 * Reading the log is one small indexed query off the main thread; a track the brain has not seen yet, or
 * a brain that is not in memory, simply has fewer facts.
 */
@Singleton
class WhyFactsLoader @Inject constructor(
    private val events: PlayEventDao,
    private val identity: TrackResolver,
    private val engine: BrainEngine,
) {
    suspend fun load(song: Song): List<WhyFacts.Fact> {
        val facts = LibraryTrackCatalog.factsOf(song)
        var artistPlays = 0
        var artistFinished = 0
        var trackFinished = 0
        if (facts != null) {
            try {
                val aid = identity.resolveArtistNames(listOf(facts.primaryArtist))[0]
                if (aid != 0L) {
                    val finished = events.recentFinishedByArtist(aid, RECENT_PLAYS)
                    artistPlays = finished.size
                    artistFinished = finished.count { it == 1 }
                }
                if (engine.isLoaded) {
                    val rid = identity.resolveTrack(facts.keys, facts.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    if (rid != 0L) trackFinished = engine.withState { it.affinity.completions[rid] ?: 0 }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "brain: reading the facts of a pick failed")
            }
        }
        return WhyFacts.pick(artistPlays, artistFinished, trackFinished, song.year)
    }

    private companion object {
        const val RECENT_PLAYS = 10
    }
}
