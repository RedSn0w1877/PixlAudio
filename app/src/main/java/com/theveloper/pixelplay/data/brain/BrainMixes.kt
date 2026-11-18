package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.brain.Repetition
import com.theveloper.pixelplay.brain.Seeds
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RecentTrackRow
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.brain.Surface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** The reason under a row of a mix: the key of `WhyLine.nameOf` and the name it is about. */
data class MixReason(val key: String, val arg: String)

/** A mix as the screens show it: songs in order (library and outside it), reasons, and what a play carries. */
class MixView(
    val kind: Int,
    val builtAtMs: Long,
    val recId: Long,
    val songs: List<Song>,
    /** By song id; only for rows that have a true sentence to show. */
    val reasons: Map<String, MixReason>,
    /** By song id: the surface, the list, the pick's arm and reason, for the event log and the queue. */
    val tags: Map<String, BrainItemTag>,
    /** Song ids of the tracks the listener does not have. */
    val novelIds: Set<String>,
    val seedLists: Int,
) {
    val isEmpty: Boolean get() = songs.isEmpty()
}

/**
 * Daily Mix and Your Mix with new music (docs/brain-spec.md, 6.2, 8.2, stage 2a).
 *
 * The pool is a library shortlist plus the cached radios of the listener's own tracks; the order is the
 * brain scorer ([MixPlanner], weights `M0`); the list is saved in the served log, so it is the same all
 * day and comes back after a restart, and the repetition budget reads it back (no track in two mixes on
 * one day, a penalty for what was served often). Nothing here touches the network: new music comes from
 * lists that listening and the refresh worker left in the cache.
 *
 * Every call is off the main thread and bounded: one pass over the library for a shortlist of about 500
 * tracks, a few database reads, one planner run (tens of milliseconds).
 */
@Singleton
class BrainMixes @Inject constructor(
    private val brain: BrainEngine,
    private val brainRepository: BrainRepository,
    private val identity: TrackResolver,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val constraints: TasteConstraintAdapter,
    private val seedDao: SeedDao,
    private val playEventDao: PlayEventDao,
    private val states: BrainStateDao,
    private val served: ServedListStore,
    private val graphEdges: GraphEdgeStore,
    private val playableStore: PlayableStore,
    private val stations: StationStore,
    private val brainSwitch: BrainSwitch,
    private val refreshScheduler: com.theveloper.pixelplay.data.worker.BrainRefreshScheduler,
    private val clock: BrainClock,
) {
    private val buildLock = Mutex()

    /** True when the brain scorer builds the mixes: switched on, and there is state to rank by. */
    suspend fun ownsMixes(): Boolean {
        if (brainSwitch.engine() != BrainEngineChoice.BRAIN) return false
        return brain.withState { BrainEngineChoice.hasState(it.affinity) }
    }

    /** True when the brain has played tracks to rank by (see [BrainEngineChoice.hasState]). Loads the state on first use. */
    suspend fun hasState(): Boolean = brain.withState { BrainEngineChoice.hasState(it.affinity) }

    /**
     * Today's saved mix of [kind], read back without building anything and without loading the brain's
     * state: one small read of the served log. Null when there is none, when it is too empty to show, or
     * when it was built without new music and radios are cached now (see [isStale]).
     */
    suspend fun saved(kind: Int): MixView? = buildLock.withLock {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val stored = served.todays(MixPlanner.surfaceOf(kind), now, tz) ?: return@withLock null
        if (isStale(stored, now)) return@withLock null
        materialize(stored, now)
    }

    /** The listener removed a song from today's mix: it stays out when the mix is read back. */
    suspend fun removeItem(kind: Int, songId: String) = buildLock.withLock {
        val now = clock.nowMs()
        val surface = MixPlanner.surfaceOf(kind)
        val stored = served.todays(surface, now, tzOffsetMin(now)) ?: return@withLock
        val kept = stored.items.filterNot { item ->
            item.songId == songId || (item.songId == null && item.row?.let { EngineTrack.from(it.row, it.rid).songId.toString() } == songId)
        }
        if (kept.size == stored.items.size) return@withLock
        served.put(surface, surfaceIdOf(kind), stored.copy(items = kept), nuQ = (MixPlanner.nuOf(kind, Novelty.factor(stored.novelty)) * 1000.0).toInt())
    }

    /**
     * Today's mix of [kind]: the saved one when there is one for today's local day, otherwise a new one
     * (which is then saved). With [regenerate] a new list is drawn whatever is saved. Null when there is
     * nothing to build a mix from (an empty library and no cached radios).
     */
    suspend fun today(kind: Int, regenerate: Boolean = false): MixView? = buildLock.withLock {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val surface = MixPlanner.surfaceOf(kind)
        if (!regenerate) {
            val saved = served.todays(surface, now, tz)
            if (saved != null && !isStale(saved, now)) {
                materialize(saved, now)?.let { return@withLock it }
            }
        }
        val drawn = if (regenerate) served.buildsToday(surface, now, tz) else 0
        val startedNanos = System.nanoTime()
        val stored = try {
            build(kind, now, tz, drawn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: building a mix failed")
            null
        } ?: return@withLock null
        PerformanceMetrics.recordTiming("brain_mix_build", (System.nanoTime() - startedNanos) / 1_000_000)
        // The brain has been used: now (and not at app start) the one background job is scheduled.
        refreshScheduler.ensureScheduled()
        val recId = served.put(surface, surfaceIdOf(kind), stored, nuQ = (MixPlanner.nuOf(kind, Novelty.factor(stored.novelty)) * 1000.0).toInt())
        materialize(stored, now, recId)
    }

    /**
     * A mix built when no radio was cached holds no new music. Once lists are in the cache it is built
     * again, once: the listener should not wait a day for the first new music. A mix with new music is
     * the day's mix and stays.
     */
    private suspend fun isStale(saved: StoredMix, now: Long): Boolean {
        if (saved.seedLists > 0 || saved.novelCount > 0) return false
        if (now - saved.builtAtMs < REBUILD_MIN_AGE_MS) return false
        return seedDao.fetchedSince(0L) > 0
    }

    // ---- building ---------------------------------------------------------------------------------

    private suspend fun build(kind: Int, now: Long, tz: Int, regenerate: Int): StoredMix? {
        runCatching { graphEdges.backfillOnce(now) }
        val surface = MixPlanner.surfaceOf(kind)
        val taste = constraints.snapshot(now)
        val level = Novelty.clamp(stations.novelty.value)
        val salt = salt()
        val library = shortlist(now, tz, salt, taste)
        val ignored = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
        val log = served.recent(now)
        val taken = Repetition.takenOnDay(log, now, tz, exceptSurface = surface)
        val times = Repetition.timesServed(log, now)

        val byRid = HashMap<Long, LibraryTrack>(library.size * 2)
        for (t in library) byRid.putIfAbsent(t.rid, t)
        val picked = pickSeeds(now, tz, salt, byRid, taste)
        val seeds = ArrayList<MixSeed>(picked.size)
        for (p in picked) {
            val list = brainRepository.cachedRadio(p.rid)
            val artistRows = brainRepository.cachedArtistRadio(p.rid)
            if (list == null && artistRows.isEmpty()) continue
            seeds.add(MixSeed(p.rid, p.aid, p.title, p.artist, list, artistRows))
        }
        if (library.isEmpty() && seeds.isEmpty()) return null

        val names = LinkedHashSet<String>()
        for (s in seeds) {
            s.list?.rows?.forEach { r -> r.row.artists.firstOrNull()?.name?.let(names::add) }
            s.artistRows.forEach { r -> r.row.artists.firstOrNull()?.name?.let(names::add) }
        }
        val nameList = names.toList()
        val nameAids = identity.resolveArtistNames(nameList)
        val aidByName = HashMap<String, Long>(nameList.size * 2)
        for (i in nameList.indices) aidByName[nameList[i]] = nameAids[i]

        val index = LibraryIndex(musicRepository.getAllSongsOnce())
        val pool = MixPoolBuilder.build(library, seeds, index, taste, ignored) { aidByName[it] ?: 0L }
        if (pool.candidates.isEmpty()) return null

        val edges = graphEdges.edgesFor(seeds.map { it.rid } + pool.novel.keys)
        val request = MixPlanner.Request(
            kind = kind,
            candidates = pool.candidates,
            seeds = pool.seedLists,
            nowMs = now,
            tzOffsetMin = tz,
            nuMultiplier = Novelty.factor(level),
            salt = salt,
            regenerate = regenerate,
            excludedRids = taken + ignored,
            servedTimes = times,
            edges = edges,
        )
        val plan = brain.withState { MixPlanner.plan(request, it.affinity) }
        if (plan.picks.isEmpty()) return null

        val items = ArrayList<StoredMixItem>(plan.picks.size)
        for (pick in plan.picks) {
            val novel = pool.novel[pick.rid]
            val whyName = whyNameOf(pick.whyKey, pick.whyArg, pool)
            val whyKey = if (whyName.isEmpty() && pick.whyKey != WhyLine.ON_REPEAT) WhyLine.NONE else pick.whyKey
            if (novel != null) {
                items.add(
                    StoredMixItem(
                        rid = pick.rid, row = novel.stored, arm = pick.arm, slot = items.size, whyKey = whyKey,
                        whyArg = pick.whyArg, whyName = whyName, retrQ = pick.retrQ, radioRank = novel.radioRank.takeIf { it > 0 } ?: -1,
                    )
                )
            } else {
                val track = pool.library[pick.rid] ?: continue
                items.add(
                    StoredMixItem(
                        rid = pick.rid, songId = track.song.id, arm = pick.arm, slot = items.size, whyKey = whyKey,
                        whyArg = pick.whyArg, whyName = whyName, retrQ = pick.retrQ, radioRank = pick.radioRank,
                    )
                )
            }
        }
        if (items.isEmpty()) return null
        return StoredMix(
            kind = kind,
            builtAtMs = now,
            seed = MixPlanner.tieSeedOf(request),
            regenerate = regenerate,
            novelty = level,
            tasteSignature = constraints.signature(),
            seedLists = seeds.count { it.list != null },
            items = items,
        )
    }

    /** The name a reason is about, or "" when the sentence would have none (it is then not shown). */
    private fun whyNameOf(key: Int, arg: Long, pool: MixPool): String = when (key) {
        WhyLine.RADIO_FANS, WhyLine.ARTIST_MORE -> pool.artistNames[arg].orEmpty()
        else -> ""
    }

    /**
     * Lane 1 of the refresh worker (3.7): the day's radio seeds, at most 24, two per artist, only tracks
     * the listener chose (5.12 rule 10). Reads the library and the event log; never the network.
     */
    suspend fun seedsForRefresh(): List<PickedSeed> {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val taste = constraints.snapshot(now)
        val library = shortlist(now, tz, salt(), taste)
        val byRid = HashMap<Long, LibraryTrack>(library.size * 2)
        for (t in library) byRid.putIfAbsent(t.rid, t)
        return pickSeeds(now, tz, salt(), byRid, taste)
    }

    /**
     * Lane 2 of the refresh worker (3.7): one track for each of the listener's top [limit] artists (by
     * artist affinity above [com.theveloper.pixelplay.brain.StationPlanner.ARTIST_ARM_AFF] and enough
     * plays), so the artist's top songs and radio are cached for the ARTIST arm of the mixes. The track is
     * the artist's most played library track in the shortlist.
     */
    suspend fun topArtistSeeds(limit: Int = TOP_ARTISTS): List<PickedSeed> {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val taste = constraints.snapshot(now)
        val library = shortlist(now, tz, salt(), taste)
        return brain.withState { state ->
            val p0 = Affinity.p0(state.affinity, now)
            val bestByAid = HashMap<Long, Pair<LibraryTrack, Double>>()
            for (t in library) {
                if (t.aid == 0L) continue
                val w = Affinity.weight(state.affinity, Family.TRACK, t.rid, now)
                val old = bestByAid[t.aid]
                if (old == null || w > old.second) bestByAid[t.aid] = t to w
            }
            val artists = state.affinity.sortedKeys(Family.ARTIST).filter { aid ->
                Affinity.aff(state.affinity, Family.ARTIST, aid, now, p0) > com.theveloper.pixelplay.brain.StationPlanner.ARTIST_ARM_AFF &&
                    Affinity.weight(state.affinity, Family.ARTIST, aid, now) >= com.theveloper.pixelplay.brain.StationPlanner.ARTIST_ARM_WEIGHT &&
                    bestByAid.containsKey(aid)
            }.sortedWith { a, b ->
                val c = Affinity.weight(state.affinity, Family.ARTIST, b, now).compareTo(Affinity.weight(state.affinity, Family.ARTIST, a, now))
                if (c != 0) c else a.compareTo(b)
            }.take(limit)
            artists.map { aid ->
                val t = bestByAid[aid]!!.first
                PickedSeed(t.rid, aid, t.song.title, LibraryIndex.primaryArtistName(t.song), song = t.song)
            }
        }
    }

    /** A track the day's seeds are made of: a library song or a track from outside it (never both). */
    class PickedSeed(
        val rid: Long,
        val aid: Long,
        val title: String,
        val artist: String,
        val song: Song? = null,
        val playable: com.theveloper.pixelplay.data.brain.store.PlayableEntity? = null,
    )

    /** The day's seeds (3.6): the four groups, at most 24, two per artist, the listener's own choices only. */
    private suspend fun pickSeeds(now: Long, tz: Int, salt: Long, byRid: Map<Long, LibraryTrack>, taste: TasteConstraints): List<PickedSeed> {
        val recent = seedDao.recentChosen(now - Seeds.RECENT_WINDOW_MS, 40)
        val saved = seedDao.recentlySaved(now - Seeds.SAVED_WINDOW_MS, 20)
        val day = Repetition.mixDay(now, tz)
        val (top, random) = brain.withState { state ->
            val p0 = Affinity.p0(state.affinity, now)
            val keys = state.affinity.sortedKeys(Family.TRACK)
            val weights = HashMap<Long, Double>(keys.size * 2)
            for (k in keys) weights[k] = Affinity.weight(state.affinity, Family.TRACK, k, now)
            val topRids = keys.sortedWith { a, b ->
                val c = weights[b]!!.compareTo(weights[a]!!)
                if (c != 0) c else a.compareTo(b)
            }.take(40)
            val nonNegative = byRid.keys.filter { Affinity.aff(state.affinity, Family.TRACK, it, now, p0) >= 0.0 }.toLongArray()
            topRids to Seeds.pseudoRandomOrder(nonNegative, salt, day)
        }
        val needAid = (top + recent.map { it.rid } + saved.map { it.rid }).filter { byRid[it] == null }.distinct()
        val aidRows: Map<Long, Long> = if (needAid.isEmpty()) emptyMap() else
            needAid.chunked(400).flatMap { seedDao.artistsOf(it) }.associate { it.rid to it.aid }
        val playables = LinkedHashMap<Long, com.theveloper.pixelplay.data.brain.store.PlayableEntity>()
        val needPlayable = (top + recent.map { it.rid } + saved.map { it.rid }).filter { byRid[it] == null }.distinct()
        for (chunk in needPlayable.chunked(400)) for (row in seedDao.playableByRid(chunk)) playables.putIfAbsent(row.rid, row)

        fun aidOf(rid: Long): Long = byRid[rid]?.aid ?: aidRows[rid] ?: 0L
        fun toSource(row: RecentTrackRow) = SeedGroups.Source(row.rid, row.aid.takeIf { it != 0L } ?: aidOf(row.rid))
        val groups = SeedGroups.groups(
            recent = recent.map(::toSource),
            top = top.map { SeedGroups.Source(it, aidOf(it)) },
            saved = saved.map(::toSource),
            randomRids = random,
            aidOf = ::aidOf,
            usable = { rid -> rid != 0L && (byRid[rid]?.let { taste.allows(it.song) } ?: playables.containsKey(rid)) },
        )
        return Seeds.select(groups).map { s ->
            val track = byRid[s.rid]
            if (track != null) {
                PickedSeed(s.rid, s.aid, track.song.title, LibraryIndex.primaryArtistName(track.song), song = track.song)
            } else {
                val row = playables[s.rid]
                PickedSeed(s.rid, s.aid, row?.title.orEmpty(), row?.artistDisplay.orEmpty(), playable = row)
            }
        }
    }

    /**
     * About 500 library tracks the mixes may pick: the legacy ranking's best 300 (it knows the library's
     * own engagement counters), the favourites, and a day-seeded handful of the rest so deep cuts get a
     * chance. Each gets its recording id; the whole library is not resolved.
     */
    private var sliceKey = ""
    private var slice: List<LibraryTrack> = emptyList()

    private suspend fun shortlist(now: Long, tz: Int, salt: Long, taste: TasteConstraints): List<LibraryTrack> {
        val all = musicRepository.getAllSongsOnce()
        val day = Repetition.mixDay(now, tz)
        val key = "${all.size}:$day:${constraints.signature()}"
        if (key == sliceKey && slice.isNotEmpty()) return slice
        val favourites = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val songs = LinkedHashMap<String, Song>()
        val picks = runCatching { dailyMixManager.personalizedPicks(all, favourites, PICKS) }.getOrDefault(emptyList())
        for (p in picks) songs.putIfAbsent(p.song.id, p.song)
        for (s in all) if (s.id in favourites && songs.size < PICKS + FAVOURITES) songs.putIfAbsent(s.id, s)
        val ids = LongArray(all.size) { com.theveloper.pixelplay.brain.BrainHash.fnv1a64(all[it].id) }
        val order = Seeds.pseudoRandomOrder(ids, salt, day)
        val bySongHash = HashMap<Long, Song>(all.size * 2)
        for (i in all.indices) bySongHash[ids[i]] = all[i]
        var deep = 0
        for (h in order) {
            if (deep >= DEEP) break
            val song = bySongHash[h] ?: continue
            if (songs.putIfAbsent(song.id, song) == null) deep++
        }
        val allowed = songs.values.filter { taste.allows(it) }
        val requests = ArrayList<TrackRequest>(allowed.size)
        val kept = ArrayList<Song>(allowed.size)
        for (song in allowed) {
            val facts = LibraryTrackCatalog.factsOf(song) ?: continue
            requests.add(TrackRequest(facts.keys, song.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
            kept.add(song)
        }
        val rids = identity.resolveTracks(requests)
        val aids = identity.resolveArtistNames(kept.map { LibraryIndex.primaryArtistName(it) })
        val out = ArrayList<LibraryTrack>(kept.size)
        for (i in kept.indices) {
            if (rids[i] == 0L) continue
            val song = kept[i]
            out.add(LibraryTrack(song, rids[i], aids[i], liked = song.isFavorite || song.id in favourites, boostLn = taste.boostLn(song)))
        }
        sliceKey = key
        slice = out
        return out
    }

    private suspend fun salt(): Long {
        states.get(KEY_SALT)?.let { bytes -> String(bytes, Charsets.UTF_8).toLongOrNull()?.let { return it } }
        val fresh = java.security.SecureRandom().nextLong()
        states.put(BrainStateEntity(KEY_SALT, fresh.toString().toByteArray(Charsets.UTF_8)))
        return fresh
    }

    // ---- reading a saved mix back -----------------------------------------------------------------

    /**
     * The songs of a saved mix. A library song that left the library or that the listener has since sent
     * away is dropped; a track from outside the library is registered in `playable` so that it plays.
     * Null when too little is left to call it a mix: the caller builds a new one.
     */
    private suspend fun materialize(stored: StoredMix, now: Long, recIdKnown: Long = 0L): MixView? {
        val taste = constraints.snapshot(now)
        val libraryIds = stored.items.mapNotNull { it.songId }
        val library = if (libraryIds.isEmpty()) emptyMap() else
            musicRepository.getSongsByIds(libraryIds).first().associateBy { it.id }
        val surface = MixPlanner.surfaceOf(stored.kind)
        val recId = if (recIdKnown != 0L) recIdKnown else ServedListStore.recIdOf(stored.builtAtMs, surface)
        val songs = ArrayList<Song>(stored.items.size)
        val tracks = ArrayList<EngineTrack>()
        val reasons = HashMap<String, MixReason>()
        val tags = HashMap<String, BrainItemTag>(stored.items.size * 2)
        val novel = HashSet<String>()
        for (item in stored.items) {
            val song: Song
            if (item.songId != null) {
                song = library[item.songId] ?: continue
            } else {
                val row = item.row ?: continue
                val track = EngineTrack.from(row.row, row.rid)
                tracks.add(track)
                song = track.toSong()
                novel.add(song.id)
            }
            if (!taste.allows(song)) {
                if (item.songId == null) tracks.removeAt(tracks.size - 1)
                novel.remove(song.id)
                continue
            }
            songs.add(song)
            val key = WhyLine.nameOf(item.whyKey)
            if (item.whyKey != WhyLine.NONE && (item.whyKey == WhyLine.ON_REPEAT || item.whyName.isNotEmpty())) {
                reasons[song.id] = MixReason(key, item.whyName)
            }
            tags[song.id] = BrainItemTag(
                surface = Surface.nameOf(surface),
                surfaceId = surfaceIdOf(stored.kind),
                recId = recId,
                slot = item.slot,
                arm = Arm.nameOf(item.arm),
                whyKey = key.takeIf { item.whyKey != WhyLine.NONE },
                whyArg = item.whyName.takeIf { it.isNotEmpty() },
                primaryArtist = LibraryIndex.primaryArtistName(song),
                // The listener chose to play the mix: its tracks are theirs, like a playlist's.
                chosen = true,
                retrQ = item.retrQ,
                radioRank = item.radioRank,
            )
        }
        if (songs.size < MIN_SONGS) return null
        if (tracks.isNotEmpty()) playableStore.register(tracks, now)
        return MixView(stored.kind, stored.builtAtMs, recId, songs, reasons, tags, novel, stored.seedLists)
    }

    companion object {
        const val KEY_SALT = "salt"
        const val PICKS = 300
        const val FAVOURITES = 150
        const val DEEP = 100
        const val MIN_SONGS = 10
        const val TOP_ARTISTS = 20
        private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
        private const val REBUILD_MIN_AGE_MS = 10L * 60 * 1000

        fun surfaceIdOf(kind: Int): String = if (kind == MixPlanner.KIND_YOUR) "your" else "daily"

        fun tzOffsetMin(nowMs: Long): Int = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
    }
}
