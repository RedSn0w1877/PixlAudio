package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

/** What a radio is asked for in the background, with the library track it is asked for. */
class RefreshTarget(val seed: RadioSeed, val rid: Long, val artistName: String)

/** What the refresh needs from the app; the real one reads the database and asks YouTube Music. */
interface RefreshWork {
    /** Lane 1 (3.6, 3.7): the day's radio seeds, best first. */
    suspend fun radioSeeds(): List<RefreshTarget>

    /** Lane 2 (3.7): one track for each of the listener's top artists. */
    suspend fun artistSeeds(): List<RefreshTarget>

    /** Network requests the brain may still make now, after the reserve the foreground keeps. */
    suspend fun requestsLeft(): Int

    suspend fun radio(target: RefreshTarget): BrainRefreshRunner.Fetch

    suspend fun artist(target: RefreshTarget): BrainRefreshRunner.Fetch
}

/**
 * The refresh worker's work (docs/brain-spec.md, 3.7, stage 2a): lane 1 fetches the song radios of the
 * day's seeds, lane 2 the artist pages of the top artists, in that order, and nothing else. It is a plain
 * class so the rules are unit-tested without WorkManager:
 *
 *  - **Off switches.** [allowed] is asked before the first request and before every one after it: flipping
 *    the local or the remote switch stops the run within one fetch.
 *  - **Never while music plays** ([playing]): the run stops and the worker tries again later.
 *  - **Bounded.** One request at a time. It stops when [RefreshWork.requestsLeft] says the daily budget
 *    (and the reserve kept for what the listener is doing) is used.
 *  - **Idempotent.** A list that is cached and fresh is answered from the cache with no request, so a run
 *    that is repeated, resumed or restarted asks for nothing twice.
 *  - **Cancellable.** Cancellation is checked between requests and is never swallowed.
 *  - **Only the listener's own music seeds it** (5.12 rule 10): that is [RefreshWork.radioSeeds]'s contract.
 */
class BrainRefreshRunner(
    private val work: RefreshWork,
    private val allowed: suspend () -> Boolean,
    private val playing: () -> Boolean,
) {
    enum class Fetch { NETWORK, CACHED, NONE }

    enum class Stop { NONE, OFF, PLAYING, BUDGET }

    class Report(
        val stopped: Stop,
        val radiosFetched: Int,
        val radiosCached: Int,
        val artistsFetched: Int,
        val artistsCached: Int,
        val missed: Int,
    ) {
        val fetched: Int get() = radiosFetched + artistsFetched
    }

    suspend fun run(): Report {
        var stopped = Stop.NONE
        var radiosFetched = 0
        var radiosCached = 0
        var artistsFetched = 0
        var artistsCached = 0
        var missed = 0

        suspend fun gate(): Stop {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return Stop.OFF
            if (playing()) return Stop.PLAYING
            if (work.requestsLeft() <= 0) return Stop.BUDGET
            return Stop.NONE
        }

        stopped = gate()
        if (stopped == Stop.NONE) {
            for (target in work.radioSeeds()) {
                // A list in the cache costs nothing: it is only the network that has to be allowed.
                val stop = gate()
                if (stop != Stop.NONE) { stopped = stop; break }
                when (work.radio(target)) {
                    Fetch.NETWORK -> radiosFetched++
                    Fetch.CACHED -> radiosCached++
                    Fetch.NONE -> missed++
                }
            }
        }
        if (stopped == Stop.NONE) {
            for (target in work.artistSeeds()) {
                val stop = gate()
                if (stop != Stop.NONE) { stopped = stop; break }
                when (work.artist(target)) {
                    Fetch.NETWORK -> artistsFetched++
                    Fetch.CACHED -> artistsCached++
                    Fetch.NONE -> missed++
                }
            }
        }
        return Report(stopped, radiosFetched, radiosCached, artistsFetched, artistsCached, missed)
    }
}

/** The app's [RefreshWork]: seeds from [BrainMixes], radios through [BrainRepository], one budget. */
@Singleton
class AppRefreshWork @Inject constructor(
    private val mixes: BrainMixes,
    private val repository: BrainRepository,
    private val seedDao: SeedDao,
    private val spotifyDao: SpotifyDao,
    private val playableStore: PlayableStore,
    private val budget: HostBudget,
    private val clock: BrainClock,
) : RefreshWork {

    override suspend fun radioSeeds(): List<RefreshTarget> = targetsOf(mixes.seedsForRefresh())

    override suspend fun artistSeeds(): List<RefreshTarget> = targetsOf(mixes.topArtistSeeds())

    override suspend fun requestsLeft(): Int {
        val today = seedDao.fetchedSince(clock.nowMs() - HostBudget.DAY_MS)
        val byCeiling = WORKER_DAILY_CEILING - today
        val byBucket = budget.available() - FOREGROUND_RESERVE
        return minOf(byCeiling, byBucket)
    }

    override suspend fun radio(target: RefreshTarget): BrainRefreshRunner.Fetch {
        val radio = repository.radioFor(target.seed, allowNetwork = true) ?: return BrainRefreshRunner.Fetch.NONE
        return if (radio.fromCache) BrainRefreshRunner.Fetch.CACHED else BrainRefreshRunner.Fetch.NETWORK
    }

    override suspend fun artist(target: RefreshTarget): BrainRefreshRunner.Fetch {
        val radio = repository.artistRadioFor(target.seed, allowNetwork = true) ?: return BrainRefreshRunner.Fetch.NONE
        return if (radio.fromCache) BrainRefreshRunner.Fetch.CACHED else BrainRefreshRunner.Fetch.NETWORK
    }

    /** A seed without a known YouTube audio id is not a seed: matching it is the matcher's job (3.6). */
    private suspend fun targetsOf(picked: List<BrainMixes.PickedSeed>): List<RefreshTarget> {
        val out = ArrayList<RefreshTarget>(picked.size)
        for (p in picked) {
            val song = p.song
            val seed = if (song != null) {
                val spotifyId = song.spotifyId ?: continue
                val videoId = runCatching { spotifyDao.getMatchedVideoId(spotifyId) }.getOrNull()
                    ?: playableStore.videoIdFor(spotifyId)
                    ?: continue
                RadioSeed(
                    mediaId = song.id,
                    videoId = videoId,
                    title = song.title,
                    primaryArtist = p.artist,
                    durationMs = song.duration,
                    spotifyId = spotifyId.takeUnless(LibraryTrackCatalog::isSyntheticId),
                )
            } else {
                val row = p.playable ?: continue
                RadioSeed(
                    mediaId = row.songId.toString(),
                    videoId = row.videoId,
                    title = row.title,
                    primaryArtist = p.artist,
                    durationMs = row.durationMs,
                )
            }
            out.add(RefreshTarget(seed, p.rid, p.artist))
        }
        return out
    }

    companion object {
        /** The worker's share of the day: the foreground (autoplay, stations, Home) has the rest of [HostBudget.DAILY_LIMIT]. */
        const val WORKER_DAILY_CEILING = 40

        /** Tokens of the burst bucket the worker never takes: what the listener is doing right now may need them. */
        const val FOREGROUND_RESERVE = 4
    }
}

/**
 * One run of the refresh with the app's real parts, and the note of how it went (`brain_state`
 * `refresh_last`, for the diagnostics and the handoff's phone checklist).
 */
@Singleton
class BrainRefresh @Inject constructor(
    private val work: AppRefreshWork,
    private val switch: BrainSwitch,
    private val states: BrainStateDao,
    private val graphEdges: GraphEdgeStore,
    private val clock: BrainClock,
) {
    suspend fun run(playing: () -> Boolean): BrainRefreshRunner.Report {
        // The lists stage 1 left behind become edges, once (3.6); a no-op on every later run.
        try {
            graphEdges.backfillOnce(clock.nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: converting the old radios to edges failed")
        }
        val report = BrainRefreshRunner(work, switch::refreshAllowed, playing).run()
        runCatching {
            val now = clock.nowMs()
            graphEdges.prune(now)
            val note = "$now|r${report.radiosFetched}+${report.radiosCached}|a${report.artistsFetched}+${report.artistsCached}|m${report.missed}|${report.stopped}"
            states.put(BrainStateEntity(KEY_LAST, note.toByteArray(Charsets.UTF_8)))
        }.onFailure { if (it is CancellationException) throw it else Timber.d(it, "brain: refresh note not saved") }
        return report
    }

    companion object {
        const val KEY_LAST = "refresh_last"
    }
}
