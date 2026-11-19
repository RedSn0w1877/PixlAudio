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

    /**
     * Requests lane 1 leaves in the window for lane 2: the radios stop when no more than this is left, so a long
     * list of uncached seeds cannot spend everything before the releases lane is reached. The default is none.
     */
    suspend fun releasesReserve(): Int = 0

    /**
     * Lane 2 (3.7, 6.3): the artists whose releases are looked at, the one checked longest ago first. The
     * default is none, so a work that has no releases lane runs exactly as before.
     */
    suspend fun releaseTargets(): List<RefreshTarget> = emptyList()

    /** One artist's releases: the artist page, the discography when a carousel was full, the tracks of what is new. */
    suspend fun releases(target: RefreshTarget): BrainRefreshRunner.Fetch = BrainRefreshRunner.Fetch.NONE

    /**
     * Lanes 3 to 5 (3.7, stage 2b): the tracks to enrich next, [lane] 3 the most played without DNA, 4 the rest of the library,
     * 5 the rows whose tags or popularity expired. Empty by default, so a work without DNA lanes runs exactly as before.
     */
    suspend fun dnaTargets(lane: Int, limit: Int): List<DnaTarget> = emptyList()

    /** Enriches [targets] from the open-data sources; [gate] false stops it, and what was gathered is still saved. */
    suspend fun enrich(targets: List<DnaTarget>, gate: suspend () -> Boolean): DnaEnricher.Report = DnaEnricher.Report(0, false)

    /**
     * After the lanes (stage 2b, 2.3 step 9): the embeddings and the taste islands, refitted when they are due (weekly, or after 200
     * qualified plays). Reads the database only. A no-op by default, so a work without islands runs exactly as before.
     */
    suspend fun fitIslands() {}

    /**
     * Before the islands are fitted: with "Use my Spotify music to shape picks" off, takes Spotify's genres and years out of the stored
     * DNA once, so nothing derived from them stays. Reads and writes the database only. A no-op by default.
     */
    suspend fun purgeSpotify() {}

    /** Gives the finished Home visits to the shelf bandit (stage 3, 5.10). Reads and writes the database only. A no-op by default. */
    suspend fun settleShelves() {}
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
    /** Milliseconds since the run began; the DNA lanes stop on their own before a periodic work's ten minutes are up. */
    private val elapsedMs: () -> Long = { 0L },
) {
    enum class Fetch { NETWORK, CACHED, NONE }

    enum class Stop { NONE, OFF, PLAYING, BUDGET, TIME }

    class Report(
        val stopped: Stop,
        val radiosFetched: Int,
        val radiosCached: Int,
        val artistsFetched: Int,
        val artistsCached: Int,
        val missed: Int,
        val releasesFetched: Int = 0,
        val releasesCached: Int = 0,
        /** Tracks whose DNA was saved in lanes 3 to 5, per lane. */
        val dnaLane3: Int = 0,
        val dnaLane4: Int = 0,
        val dnaLane5: Int = 0,
    ) {
        val fetched: Int get() = radiosFetched + artistsFetched + releasesFetched
        val dnaTracks: Int get() = dnaLane3 + dnaLane4 + dnaLane5
    }

    suspend fun run(): Report {
        var stopped = Stop.NONE
        var radiosFetched = 0
        var radiosCached = 0
        var artistsFetched = 0
        var artistsCached = 0
        var releasesFetched = 0
        var releasesCached = 0
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
            val reserve = work.releasesReserve()
            for (target in work.radioSeeds()) {
                // A list in the cache costs nothing: it is only the network that has to be allowed.
                val stop = gate()
                if (stop != Stop.NONE) { stopped = stop; break }
                // The rest of the window is the releases lane's: lane 1 yields to it (not a stop).
                if (reserve > 0 && work.requestsLeft() <= reserve) break
                when (work.radio(target)) {
                    Fetch.NETWORK -> radiosFetched++
                    Fetch.CACHED -> radiosCached++
                    Fetch.NONE -> missed++
                }
            }
        }
        if (stopped == Stop.NONE) {
            // Lane 2 of the spec: what is new from the artists the listener plays. It goes before the artist
            // lists below so the daily budget cannot be spent before it is reached; it has its own share.
            for (target in work.releaseTargets()) {
                val stop = gate()
                if (stop != Stop.NONE) { stopped = stop; break }
                when (work.releases(target)) {
                    Fetch.NETWORK -> releasesFetched++
                    Fetch.CACHED -> releasesCached++
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

        // Lanes 3 to 5 (stage 2b): the open-data sources. They have budgets of their own (3.7) and never spend YouTube's, so a day with
        // no YouTube requests left still enriches; the same switches, the same "never while music plays", and a time limit.
        val dna = IntArray(3)
        if (stopped == Stop.NONE || stopped == Stop.BUDGET) {
            suspend fun dnaGate(): Boolean {
                currentCoroutineContext().ensureActive()
                return allowed() && !playing() && elapsedMs() < DNA_TIME_LIMIT_MS
            }
            lanes@ for (lane in 3..5) {
                var rounds = 0
                while (rounds++ < MAX_ROUNDS[lane - 3]) {
                    if (!dnaGate()) {
                        stopped = if (!allowed()) Stop.OFF else if (playing()) Stop.PLAYING else Stop.TIME
                        break@lanes
                    }
                    val targets = work.dnaTargets(lane, DNA_BATCH)
                    if (targets.isEmpty()) break
                    val r = work.enrich(targets, ::dnaGate)
                    dna[lane - 3] += r.tracks
                    if (r.noAtlas) break@lanes
                    if (r.stopped) {
                        stopped = if (!allowed()) Stop.OFF else if (playing()) Stop.PLAYING else Stop.TIME
                        break@lanes
                    }
                    // A round that saved nothing asked nothing: every source is spent or off. The next lane would find the same.
                    if (r.tracks == 0) break
                }
            }
        }
        // The embeddings and the islands (2.3 step 9): the part of the job that needs no network, so it runs when the lanes could not.
        if (allowed() && !playing() && stopped != Stop.OFF && stopped != Stop.PLAYING) {
            try {
                work.purgeSpotify()
                work.fitIslands()
                work.settleShelves()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                timber.log.Timber.w(e, "brain: fitting the islands failed")
            }
        }
        return Report(stopped, radiosFetched, radiosCached, artistsFetched, artistsCached, missed, releasesFetched, releasesCached, dna[0], dna[1], dna[2])
    }

    companion object {
        /** Tracks per enrichment round: about three minutes of polite requests, so little is lost if the work is cancelled. */
        const val DNA_BATCH = 16

        /** Rounds per lane in one run (lane 3, 4, 5). The daily budgets are the real limit; this keeps one run bounded. */
        val MAX_ROUNDS = intArrayOf(10, 10, 4)

        /** A periodic work gets about ten minutes: the DNA lanes stop at eight. */
        const val DNA_TIME_LIMIT_MS = 8L * 60 * 1000
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
    private val newReleases: NewReleases,
    private val musicRepository: com.theveloper.pixelplay.data.repository.MusicRepository,
    private val ledger: RequestLedger,
    private val dnaTargets: DnaTargets,
    private val dnaEnricher: DnaEnricher,
    private val spotifyPurge: SpotifyDnaPurge,
    private val shelfStore: ShelfStore,
) : RefreshWork {

    override suspend fun dnaTargets(lane: Int, limit: Int): List<DnaTarget> = when (lane) {
        3 -> dnaTargets.mostPlayed(limit)
        4 -> dnaTargets.restOfLibrary(limit)
        5 -> dnaTargets.expired(limit)
        else -> emptyList()
    }

    override suspend fun enrich(targets: List<DnaTarget>, gate: suspend () -> Boolean): DnaEnricher.Report = dnaEnricher.enrich(targets, gate)

    override suspend fun fitIslands() {
        mixes.islandBuilder.ensure()
    }

    override suspend fun purgeSpotify() {
        spotifyPurge.run()
    }

    override suspend fun settleShelves() {
        shelfStore.settle()
    }


    override suspend fun radioSeeds(): List<RefreshTarget> = targetsOf(mixes.seedsForRefresh())

    override suspend fun artistSeeds(): List<RefreshTarget> = targetsOf(mixes.topArtistSeeds())

    override suspend fun requestsLeft(): Int {
        val since = clock.nowMs() - HostBudget.DAY_MS
        // REQUESTS of the day, whoever made them (the ledger counts every token the bucket gave out), not stored lists:
        // the worker stops at 40 of the 60, so the foreground keeps at least 20.
        val today = ledger.countSince(since)
        val byCeiling = WORKER_DAILY_CEILING - today
        val byBucket = budget.available() - FOREGROUND_RESERVE
        return minOf(byCeiling, byBucket)
    }

    override suspend fun releasesReserve(): Int {
        val since = clock.nowMs() - HostBudget.DAY_MS
        return (RELEASES_DAILY_CEILING - newReleases.requestsSince(since)).coerceIn(0, RELEASES_RESERVE)
    }

    override suspend fun releaseTargets(): List<RefreshTarget> {
        // The lane's own share of the day: the rest of the worker's requests belong to the radios.
        val since = clock.nowMs() - HostBudget.DAY_MS
        if (newReleases.requestsSince(since) >= RELEASES_DAILY_CEILING) return emptyList()
        val artists = artistSeeds()
        val order = newReleases.checkOrder(artists.map { it.artistName to null })
        val byKey = HashMap<String, RefreshTarget>(artists.size * 2)
        for (a in artists) byKey.putIfAbsent(com.theveloper.pixelplay.brain.BrainText.fold(a.artistName), a)
        return order.mapNotNull { byKey[com.theveloper.pixelplay.brain.BrainText.fold(it.first)] }.take(RELEASE_ARTISTS_PER_RUN)
    }

    override suspend fun releases(target: RefreshTarget): BrainRefreshRunner.Fetch {
        val since = clock.nowMs() - HostBudget.DAY_MS
        if (newReleases.requestsSince(since) >= RELEASES_DAILY_CEILING) return BrainRefreshRunner.Fetch.NONE
        val channel = newReleases.channelOf(target.artistName)
            ?: repository.artistChannelId(target.seed, allowNetwork = true)
            ?: return BrainRefreshRunner.Fetch.NONE
        if (newReleases.isFresh(channel)) return BrainRefreshRunner.Fetch.CACHED
        val index = libraryIndex()
        return newReleases.check(channel, target.artistName) { row -> index.match(row) != null }
    }

    // The library is read once per run: a release's tracks the listener already has are not news to them.
    private var cachedIndex: LibraryIndex? = null
    private var cachedIndexAtMs = 0L

    private suspend fun libraryIndex(): LibraryIndex {
        val now = clock.nowMs()
        cachedIndex?.let { if (now - cachedIndexAtMs in 0 until INDEX_TTL_MS) return it }
        return LibraryIndex(musicRepository.getAllSongsOnce()).also {
            cachedIndex = it
            cachedIndexAtMs = now
        }
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

        /** The releases lane's share of the worker's day (6.3, 3.7 lane 2): artist pages, discographies, album playlists. */
        const val RELEASES_DAILY_CEILING = 14

        /** What lane 1 leaves in the window for the releases lane (about two artists), so the radios cannot take it all. */
        const val RELEASES_RESERVE = 4

        /** Artists looked at per run: the top 20 are all covered within about four days, not daily (deviation 101). */
        const val RELEASE_ARTISTS_PER_RUN = 6

        private const val INDEX_TTL_MS = 10L * 60 * 1000
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
        val startedAt = System.currentTimeMillis()
        val report = BrainRefreshRunner(work, switch::refreshAllowed, playing) { System.currentTimeMillis() - startedAt }.run()
        runCatching {
            val now = clock.nowMs()
            graphEdges.prune(now)
            val note = "$now|r${report.radiosFetched}+${report.radiosCached}|a${report.artistsFetched}+${report.artistsCached}|n${report.releasesFetched}+${report.releasesCached}|m${report.missed}|d${report.dnaLane3}+${report.dnaLane4}+${report.dnaLane5}|${report.stopped}"
            states.put(BrainStateEntity(KEY_LAST, note.toByteArray(Charsets.UTF_8)))
        }.onFailure { if (it is CancellationException) throw it else Timber.d(it, "brain: refresh note not saved") }
        return report
    }

    /** What the note of one run says, for the Developer screen. Radios, artists and releases are fetched plus cached. */
    class Note(
        val atMs: Long,
        val radios: Int,
        val artists: Int,
        val releases: Int,
        val tracksEnriched: Int,
        val stopped: String,
    )

    companion object {
        const val KEY_LAST = "refresh_last"

        /** Reads a note written by [run] (`ms|r1+2|a3+4|n5+6|m7|d8+9+10|STOP`; the `d` part is missing in a stage 2a note), or null. */
        fun parseNote(text: String?): Note? {
            if (text.isNullOrBlank()) return null
            val parts = text.split('|')
            val at = parts.firstOrNull()?.toLongOrNull() ?: return null
            fun sum(prefix: Char): Int = parts.firstOrNull { it.startsWith(prefix) && it.length > 1 && it[1].isDigit() }
                ?.substring(1)?.split('+')?.sumOf { it.toIntOrNull() ?: 0 } ?: 0
            return Note(at, sum('r'), sum('a'), sum('n'), sum('d'), parts.lastOrNull().orEmpty().takeIf { it.isNotBlank() } ?: "NONE")
        }
    }
}
