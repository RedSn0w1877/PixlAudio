package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Brain
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.LibraryFacts
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Replay
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.history.ListeningHistoryDao
import com.theveloper.pixelplay.data.history.SeedKind
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteKind
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Merges the stored event log with the imported plays and replays the result (docs/brain-spec.md,
 * 4.4). Pure orchestration over [BrainStore]: no Android type, so the migration rules are unit-tested
 * with an in-memory store.
 */
class ReplayMerger(private val store: BrainStore) {

    class Result(val state: Affinity.State, val recentEventIds: Set<Long>, val importedUsed: Int, val importedDropped: Int)

    /**
     * [imported] in any order. [survivorOf] maps a merged recording to the one it was merged into.
     * An imported play whose end is within [DUPLICATE_WINDOW_MS] of a stored play of the same
     * recording is the same listen seen twice and is dropped.
     */
    fun rebuild(imported: List<PlayEvent>, facts: LibraryFacts, nowMs: Long, survivorOf: (Long) -> Long): Result {
        // Pass 1 over the log: when each recording's stored plays ended.
        val endsByRid = HashMap<Long, ArrayList<Long>>()
        val recent = HashSet<Long>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = store.eventsAfter(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().eventId
            for (e in page) endsByRid.getOrPut(survivorOf(e.rid)) { ArrayList(2) }.add(e.endedAtMs)
            if (page.size < PAGE) break
        }
        var dropped = 0
        val kept = ArrayList<PlayEvent>(imported.size)
        for (e in imported) {
            val ends = endsByRid[survivorOf(e.rid)]
            var duplicate = false
            if (ends != null) {
                val end = e.endedAtMs
                for (other in ends) {
                    val delta = if (end > other) end - other else other - end
                    if (delta <= DUPLICATE_WINDOW_MS) {
                        duplicate = true
                        break
                    }
                }
            }
            if (duplicate) dropped++ else kept.add(e)
        }
        kept.sortWith { a, b -> a.eventId.compareTo(b.eventId) }

        // Pass 2: both streams in ascending event id, the order the replay is defined on.
        val merged = object : Iterator<PlayEvent> {
            var page: List<PlayEvent> = store.eventsAfter(Long.MIN_VALUE, PAGE)
            var at = 0
            var importedAt = 0

            private fun storedPeek(): PlayEvent? {
                if (at >= page.size && page.size == PAGE) {
                    page = store.eventsAfter(page.last().eventId, PAGE)
                    at = 0
                }
                return if (at < page.size) page[at] else null
            }

            override fun hasNext(): Boolean = storedPeek() != null || importedAt < kept.size

            override fun next(): PlayEvent {
                val stored = storedPeek()
                val other = if (importedAt < kept.size) kept[importedAt] else null
                val next = if (stored != null && (other == null || stored.eventId <= other.eventId)) {
                    at++
                    // What was really replayed, so a play recorded while this runs is not applied twice.
                    if (stored.startedAtMs >= nowMs - RECENT_MS) recent.add(stored.eventId)
                    stored
                } else {
                    importedAt++
                    other!!
                }
                val survivor = survivorOf(next.rid)
                return if (survivor == next.rid) next else next.withRid(survivor)
            }
        }
        return Result(Replay.rebuild(merged, facts, nowMs), recent, kept.size, dropped)
    }

    private fun PlayEvent.withRid(rid: Long) = PlayEvent(
        eventId = eventId, origin = origin, rid = rid, primaryAid = primaryAid, otherAids = otherAids,
        keyAtWrite = keyAtWrite, startedAtMs = startedAtMs, tzOffsetMin = tzOffsetMin, playedMs = playedMs,
        durationMs = durationMs, startReason = startReason, endReason = endReason, flags = flags, surface = surface,
        surfaceId = surfaceId, recId = recId, slot = slot, arm = arm, sessionId = sessionId,
        posInSession = posInSession, passiveRun = passiveRun, route = route, retrQ = retrQ, radioRank = radioRank,
        pServedQ = pServedQ, modelVersion = modelVersion, schema = schema,
    )

    companion object {
        const val DUPLICATE_WINDOW_MS = 90_000L
        private const val PAGE = 2_000
        private const val RECENT_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * The app's [StateRebuilder]: the one-shot history copy, then the merge and replay, then the facts
 * (docs/brain-spec.md, 4.4, steps 1, 2 and 4). Runs in the background the first time the brain is
 * needed, and again when the logic's version, the import or the calendar say so. Never at app start.
 */
@Singleton
class AppStateRebuilder @Inject constructor(
    private val store: BrainStore,
    private val identity: TrackResolver,
    private val catalog: TrackCatalog,
    private val musicRepository: dagger.Lazy<MusicRepository>,
    private val playbackStatsRepository: dagger.Lazy<PlaybackStatsRepository>,
    private val historyDao: dagger.Lazy<ListeningHistoryDao>,
    private val tasteAdjustments: dagger.Lazy<TasteAdjustmentRepository>,
    private val clock: BrainClock,
) : StateRebuilder {

    override suspend fun isNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (store.getState(BrainStore.KEY_HISTORY_MIGRATED) == null) return@withContext true
        if (store.getState(BrainStore.KEY_SPEC_VERSION) != Brain.SPEC_VERSION.toString()) return@withContext true
        val importMax = runCatching { historyDao.get().maxPlayId() ?: 0L }.getOrDefault(0L)
        if (store.getState(BrainStore.KEY_IMPORT_MAX_ID) != importMax.toString()) return@withContext true
        // The facts (likes, top lists, follows, taste changes) are folded in at a rebuild; a weekly one
        // keeps them current without a background job.
        val rebuiltAt = store.getState(KEY_REBUILT_AT_MS)?.toLongOrNull() ?: 0L
        val age = clock.nowMs() - rebuiltAt
        age < 0L || age > FACTS_REFRESH_MS
    }

    override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = withContext(Dispatchers.IO) {
        val library = musicRepository.get().getAllSongsOnce()
        val songById = HashMap<String, Song>(library.size * 2)
        for (song in library) songById[song.id] = song

        // Step 2: the app's own older plays, once.
        if (store.getState(BrainStore.KEY_HISTORY_MIGRATED) == null) {
            val cutoff = LegacyMigration.cutoff(
                firstUseMs = store.getState(BrainStore.KEY_FIRST_USE_MS)?.toLongOrNull() ?: nowMs,
                firstLiveStartMs = store.eventsAfter(0L, 1).firstOrNull()?.takeIf { it.origin == Origin.LIVE }?.startedAtMs,
            )
            val rows = playbackStatsRepository.get().exportEventsForBackup().map {
                LegacyMigration.HistoryRow(it.songId, it.endTimestamp ?: it.timestamp, it.durationMs)
            }
            LegacyMigration(store, identity).copy(rows, cutoff) { songId ->
                songById[songId]?.let(LibraryTrackCatalog::factsOf) ?: catalog.factsFor(songId, null)
            }
            store.putState(BrainStore.KEY_HISTORY_MIGRATED, nowMs.toString())
        }

        // The library by recording id: lengths for imported plays, and the facts below.
        val facts = ArrayList<TrackFacts?>(library.size)
        for (song in library) facts.add(LibraryTrackCatalog.factsOf(song))
        val rids = LongArray(library.size)
        var from = 0
        while (from < library.size) {
            val to = minOf(from + RESOLVE_BATCH, library.size)
            val requests = ArrayList<TrackRequest>(to - from)
            for (i in from until to) {
                val f = facts[i]
                requests.add(TrackRequest(f?.keys ?: Ids.TrackKeys(), f?.durationMs?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0))
            }
            val resolved = identity.resolveTracks(requests)
            for (i in from until to) rids[i] = resolved[i - from]
            from = to
        }
        val durationByRid = HashMap<Long, Int>(library.size * 2)
        val ridBySongId = HashMap<String, Long>(library.size * 2)
        for (i in library.indices) {
            if (rids[i] == 0L) continue
            ridBySongId[library[i].id] = rids[i]
            if (library[i].duration > 0L) durationByRid[rids[i]] = library[i].duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        // Step 1: imported history is read, not copied.
        val adapter = ImportedPlayAdapter(ImportedPlaySource.OfDao(historyDao.get()), identity)
        val importMax = runCatching { adapter.maxId() }.getOrDefault(0L)
        val imported = runCatching { adapter.events { durationByRid[it] ?: 0 } }.getOrDefault(emptyList())

        val merges = identity.merges()
        val result = ReplayMerger(store).rebuild(imported, libraryFacts(library, ridBySongId, nowMs), nowMs) { merges[it] ?: it }
        StateRebuilder.Rebuilt(
            state = result.state,
            recentEventIds = result.recentEventIds,
            stateValues = mapOf(
                BrainStore.KEY_SPEC_VERSION to Brain.SPEC_VERSION.toString(),
                BrainStore.KEY_IMPORT_MAX_ID to importMax.toString(),
                KEY_REBUILT_AT_MS to nowMs.toString(),
            ),
        )
    }

    /** Step 4: facts that are not events, by id. */
    private suspend fun libraryFacts(library: List<Song>, ridBySongId: Map<String, Long>, nowMs: Long): LibraryFacts {
        // In-app favourites. (Spotify's Liked Songs arrive as library tracks; the ones the listener
        // also hearts in the app are these.)
        val favourites = runCatching { musicRepository.get().getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val likedSongs = library.filter { it.id in favourites || it.isFavorite }
        val likedAids = identity.resolveArtistNames(likedSongs.map(LibraryIndex::primaryArtistName))
        val liked = ArrayList<LibraryFacts.TrackFact>(likedSongs.size)
        for (i in likedSongs.indices) {
            val rid = ridBySongId[likedSongs[i].id] ?: continue
            liked.add(LibraryFacts.TrackFact(rid, likedAids[i]))
        }

        val topArtists = ArrayList<LibraryFacts.TopArtist>()
        val topTracks = ArrayList<LibraryFacts.TopTrack>()
        val followed = ArrayList<Long>()
        val seeds = runCatching { historyDao.get().allSeeds() }.getOrDefault(emptyList())
        for (seed in seeds) {
            val kind = SeedKind.fromKey(seed.kind) ?: continue
            val range = when (seed.timeRange) {
                "short_term" -> LibraryFacts.RANGE_SHORT
                "medium_term" -> LibraryFacts.RANGE_MEDIUM
                else -> LibraryFacts.RANGE_LONG
            }
            val aid = identity.resolveArtistNames(listOf(seed.artistName))[0]
            when (kind) {
                SeedKind.TOP_ARTIST -> if (aid != 0L) topArtists.add(LibraryFacts.TopArtist(aid, range, seed.position, seed.listSize))
                SeedKind.FOLLOWED_ARTIST -> if (aid != 0L) followed.add(aid)
                SeedKind.TOP_TRACK -> {
                    val title = seed.trackName ?: continue
                    val rid = identity.resolveTrack(
                        Ids.TrackKeys(spotifyId = seed.spotifyId, nameKey = Ids.nameKey(seed.artistName, title)), 0
                    )
                    if (rid != 0L) topTracks.add(LibraryFacts.TopTrack(rid, aid, range, seed.position, seed.listSize))
                }
            }
        }

        val adjustments = ArrayList<LibraryFacts.Adjustment>()
        val active = runCatching { tasteAdjustments.get().snapshot(nowMs).active }.getOrDefault(emptyList())
        for (adjustment in active) {
            val more = when (adjustment.kind) {
                TasteKind.BOOST -> true
                TasteKind.REDUCE -> false
                else -> continue // mute and block are hard exclusions, applied at retrieval (5.11)
            }
            when (adjustment.target) {
                TasteTarget.ARTIST -> {
                    val aid = identity.resolveArtistNames(listOf(adjustment.label))[0]
                    if (aid != 0L) adjustments.add(LibraryFacts.Adjustment(Family.ARTIST, aid, more, adjustment.strength))
                }
                TasteTarget.SONG -> {
                    val song = library.firstOrNull { it.id == adjustment.key || it.spotifyId == adjustment.key }
                    val rid = song?.let { ridBySongId[it.id] } ?: continue
                    adjustments.add(LibraryFacts.Adjustment(Family.TRACK, rid, more, adjustment.strength))
                }
                else -> Unit // genre and album adjustments need the atlas and album ids of stage 2
            }
        }
        return LibraryFacts(liked, topArtists, topTracks, followed.toLongArray(), adjustments)
    }

    private companion object {
        const val KEY_REBUILT_AT_MS = "affinity_rebuilt_at_ms"
        const val FACTS_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val RESOLVE_BATCH = 400
    }
}
