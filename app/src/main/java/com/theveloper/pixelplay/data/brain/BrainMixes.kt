package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.IslandContext
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
    /** Stage 2b: which list this is, its island's number (1..K) and its subtitle. */
    val spec: MixSpec = MixSpec(kind),
    val number: Int = 0,
    val subtitle: String = "",
    /** By song id: the recording id of each row (the Home page rules and the plan read it). */
    val rids: Map<String, Long> = emptyMap(),
    /** The list as it was saved (its rows outside the library are what a shelf of it needs to play them). */
    val storedItems: List<StoredMixItem> = emptyList(),
    /** How the list was ranked, decided when it was built (5.12 point 8). */
    val control: Boolean = false,
    val model: Int = 0,
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
    internal val brain: BrainEngine,
    internal val brainRepository: BrainRepository,
    internal val identity: TrackResolver,
    internal val musicRepository: MusicRepository,
    internal val dailyMixManager: DailyMixManager,
    internal val constraints: TasteConstraintAdapter,
    internal val seedDao: SeedDao,
    internal val playEventDao: PlayEventDao,
    internal val states: BrainStateDao,
    internal val served: ServedListStore,
    internal val graphEdges: GraphEdgeStore,
    internal val playableStore: PlayableStore,
    internal val stations: StationStore,
    internal val brainSwitch: BrainSwitch,
    internal val refreshScheduler: com.theveloper.pixelplay.data.worker.BrainRefreshScheduler,
    internal val clock: BrainClock,
    /** Stage 2b: the islands, the DNA and the whole library by id (docs/brain-spec.md, 5.4, 6.2, 8.2). */
    internal val islandStore: IslandStore,
    internal val islandBuilder: IslandBuilder,
    internal val candidateDna: CandidateDna,
    internal val libraryRids: LibraryRids,
    internal val atlasProvider: AtlasProvider,
    internal val newReleases: NewReleases,
    internal val blockedArtists: com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository,
    internal val tasteAdjustments: com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository,
    internal val trackDnaDao: com.theveloper.pixelplay.data.brain.store.TrackDnaDao,
    /** "Pick three artists you love" (8.10): seeds for a listener with a thin library. */
    internal val pickedArtists: PickedArtists,
) {
    private val buildLock = Mutex()
    @Volatile private var stateMarked = false

    /** True when the brain scorer builds the mixes: switched on, and there is state to rank by. */
    suspend fun ownsMixes(): Boolean {
        if (brainSwitch.engine() != BrainEngineChoice.BRAIN) return false
        return hasState()
    }

    /** True when the brain has played tracks to rank by (see [BrainEngineChoice.hasState]). Loads the state on first use. */
    suspend fun hasState(): Boolean {
        // Three picked artists are something of the listener's own to build from (8.10), before any play.
        val has = brain.withState { BrainEngineChoice.hasState(it.affinity) } || pickedArtists.hasPicks()
        // Remembered, so that the next app start knows without loading anything.
        if (has && !stateMarked) {
            stateMarked = true
            runCatching { brainSwitch.markBrainStateSeen() }
        }
        return has
    }

    /**
     * Today's saved mix of [kind], read back without building anything and without loading the brain's
     * state: one small read of the served log. Null when there is none, when it is too empty to show, or
     * when it was built without new music and radios are cached now (see [isStale]).
     */
    suspend fun saved(kind: Int): MixView? = savedOf(specFor(kind))

    /**
     * The list a caller that knows only "the Daily Mix" means (the legacy screens, the widgets): with several islands it is Daily
     * Mix 1, the island of the lowest id among the biggest six; without, the one list of stage 2a.
     */
    suspend fun specFor(kind: Int): MixSpec {
        if (kind != MixKinds.DAILY) return MixSpec(kind)
        val islands = islandStore.current()
        return if (islands != null && islands.hasMany) MixSpec(MixKinds.DAILY, islands.dailyIslands().first().id) else MixSpec(MixKinds.DAILY)
    }

    suspend fun savedOf(spec: MixSpec): MixView? = buildLock.withLock {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val stored = served.todays(spec.surface, now, tz, spec.surfaceId, weekly = spec.kind == MixKinds.FIRST_LISTEN) ?: return@withLock null
        if (isStale(stored, now)) return@withLock null
        materialize(stored, now)
    }

    /** The listener removed a song from today's mix: it stays out when the mix is read back. */
    suspend fun removeItem(kind: Int, songId: String) = removeItemOf(specFor(kind), songId)

    suspend fun removeItemOf(spec: MixSpec, songId: String) = buildLock.withLock {
        val now = clock.nowMs()
        val stored = served.todays(spec.surface, now, tzOffsetMin(now), spec.surfaceId) ?: return@withLock
        val kept = stored.items.filterNot { item ->
            item.songId == songId || (item.songId == null && item.row?.let { EngineTrack.from(it.row, it.rid).songId.toString() } == songId)
        }
        if (kept.size == stored.items.size) return@withLock
        served.put(spec.surface, spec.surfaceId, stored.copy(items = kept), nuQ = (nuOfSpec(spec, Novelty.factor(stored.novelty)) * 1000.0).toInt())
    }

    /**
     * The number a Daily Mix has among today's islands (1..K), read now: a refit after the list was saved can move an island, and two
     * cards must never share a number. Null for a list that is not an island's, and for an island that is no longer one of the six.
     */
    internal suspend fun liveNumberOf(spec: MixSpec): Int? {
        if (spec.kind != MixKinds.DAILY || spec.islandId <= 0) return null
        val index = islandStore.current()?.dailyIslands()?.indexOfFirst { it.id == spec.islandId } ?: return null
        return if (index >= 0) index + 1 else null
    }

    private fun nuOfSpec(spec: MixSpec, multiplier: Double): Double = when {
        spec.kind == MixKinds.DAILY && spec.islandId > 0 -> MixPlanner.nuOfIsland(multiplier)
        spec.kind == MixKinds.DAILY || spec.kind == MixKinds.YOUR -> MixPlanner.nuOf(spec.kind, multiplier)
        spec.kind == MixKinds.DAYPART -> DAYPART_NU
        spec.kind == MixKinds.ON_REPEAT || spec.kind == MixKinds.REDISCOVER -> 0.0
        else -> 1.0
    }

    /**
     * Today's mix of [kind]: the saved one when there is one for today's local day, otherwise a new one
     * (which is then saved). With [regenerate] a new list is drawn whatever is saved. Null when there is
     * nothing to build a mix from (an empty library and no cached radios).
     */
    suspend fun today(kind: Int, regenerate: Boolean = false): MixView? = todayOf(specFor(kind), regenerate)

    suspend fun todayOf(spec: MixSpec, regenerate: Boolean = false): MixView? = buildLock.withLock {
        val now = clock.nowMs()
        val tz = tzOffsetMin(now)
        val surface = spec.surface
        if (!regenerate) {
            val saved = served.todays(surface, now, tz, spec.surfaceId, weekly = spec.kind == MixKinds.FIRST_LISTEN)
            if (saved != null && !isStale(saved, now)) {
                materialize(saved, now)?.let { return@withLock it }
            }
        }
        // A Daily Mix of an island that is no longer one of the six is not built again (a saved id that went stale would take tracks from the real lists).
        if (spec.kind == MixKinds.DAILY && spec.islandId > 0 && liveNumberOf(spec) == null) return@withLock null
        val drawn = if (regenerate) served.buildsToday(surface, now, tz, spec.surfaceId) else 0
        val startedNanos = System.nanoTime()
        val stored = try {
            build(spec, now, tz, drawn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: building a mix failed")
            null
        } ?: return@withLock null
        PerformanceMetrics.recordTiming("brain_mix_build", (System.nanoTime() - startedNanos) / 1_000_000)
        // The brain has been used: now (and not at app start) the one background job is scheduled.
        refreshScheduler.ensureScheduled()
        val recId = served.put(surface, spec.surfaceId, stored, nuQ = (nuOfSpec(spec, Novelty.factor(stored.novelty)) * 1000.0).toInt())
        materialize(stored, now, recId)
    }

    /**
     * A mix built when no radio was cached holds no new music. Once lists are in the cache it is built
     * again, once: the listener should not wait a day for the first new music. A mix with new music is
     * the day's mix and stays.
     */
    private suspend fun isStale(saved: StoredMix, now: Long): Boolean {
        // "Reset what I've learned": a mix that the learned weights ranked, built before the reset, is not the day's mix any more.
        if (saved.model == BrainEventRecorder.MODEL_LEARNED && builtBeforeReset(saved.builtAtMs)) return true
        // Only the lists that take new music from cached radios can be waiting for one; On Repeat and Rediscover never are.
        if (!MixKinds.usesPlanner(saved.kind) || saved.kind == MixKinds.FIRST_LISTEN) return false
        if (saved.seedLists > 0 || saved.novelCount > 0) return false
        if (now - saved.builtAtMs < REBUILD_MIN_AGE_MS) return false
        return seedDao.fetchedSince(0L) > 0
    }

    /** True when [builtAtMs] is not after the last "Reset what I've learned" (the engine saves its moment inside the event id it sets). */
    internal suspend fun builtBeforeReset(builtAtMs: Long): Boolean {
        val after = states.get(BrainEngine.KEY_LEARN_AFTER)?.let { String(it, Charsets.UTF_8).toLongOrNull() } ?: return false
        return builtAtMs <= (after ushr 16)
    }

    // ---- building ---------------------------------------------------------------------------------

    private suspend fun build(spec: MixSpec, now: Long, tz: Int, regenerate: Int): StoredMix? {
        runCatching { graphEdges.backfillOnce(now) }
        if (!MixKinds.usesPlanner(spec.kind)) return buildSimple(spec, now, tz, regenerate)
        val kind = spec.kind
        val surface = spec.surface
        val taste = constraints.snapshot(now)
        val level = Novelty.clamp(stations.novelty.value)
        val salt = salt()
        val shortlisted = shortlist(now, tz, salt, taste)
        // Stage 2b: with islands the pool is the whole library (the planner keeps what is close to the island); First Listen's
        // library part is only unplayed tracks in adjacent genres (arm EDGE, 6.1).
        val islands = islandStore.current()?.takeIf { !it.isEmpty }
        val edgeRids = if (kind == MixKinds.FIRST_LISTEN) edgeLibrary(now) else emptyMap()
        val library = when {
            kind == MixKinds.FIRST_LISTEN -> edgeRids.values.toList()
            islands != null -> mergedLibrary(shortlisted, libraryRids.all(now))
            else -> shortlisted
        }
        val ignored = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
        val log = served.recent(now)
        val taken = Repetition.takenOnDay(log.newestPerList(), now, tz, exceptSurface = surface, exceptId = spec.surfaceId)
        // This week's First Listen is held all week (8.2): no other list repeats it.
        if (kind != MixKinds.FIRST_LISTEN) taken.addAll(firstListenRids(now, tz))
        val times = Repetition.timesServed(log, now)

        val byRid = HashMap<Long, LibraryTrack>(shortlisted.size * 2)
        for (t in shortlisted) byRid.putIfAbsent(t.rid, t)
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
        val (enriched, known) = candidateDna.enrich(pool.candidates)
        val context = islands?.let { IslandContext(it.islands) }
        val daypartMix = kind == MixKinds.DAYPART
        val ofIsland = kind == MixKinds.DAILY || daypartMix
        val islandIdx = if (ofIsland && spec.islandId > 0 && islands != null) islands.indexOfId(spec.islandId) else -1
        // A Daily Mix of an island that is gone is the plain Daily Mix: no island to be close to.
        val useIslands = context != null && !(ofIsland && spec.islandId > 0 && islandIdx < 0)
        val request = MixPlanner.Request(
            // A daypart mix is planned like a Daily Mix of one island, with its own size and share of new music.
            kind = if (daypartMix) MixKinds.DAILY else kind,
            count = if (daypartMix) DAYPART_COUNT else 0,
            candidates = enriched,
            islands = if (useIslands) context else null,
            islandIdx = islandIdx,
            embeddings = known.embeddings,
            tagsByRid = known.tags,
            seeds = pool.seedLists,
            nowMs = now,
            tzOffsetMin = tz,
            nuMultiplier = Novelty.factor(level),
            salt = salt,
            // The seed of a daypart mix differs from its island's Daily Mix by the bucket it is for.
            regenerate = if (daypartMix) 1 + spec.part else regenerate,
            excludedRids = taken + ignored,
            servedTimes = times,
            edges = edges,
        ).also { if (daypartMix) it.nuOverride = DAYPART_NU }
        var choice = RankingChoice(null, false, 0)
        val plan = brain.withState { st ->
            // Stage 3: the learned weights rank the list while the gate has them on (one posterior sample per list and day, 5.10).
            val listSeed = com.theveloper.pixelplay.brain.Ranker.listSeed(
                salt, "mix:$kind:$islandIdx:$regenerate", com.theveloper.pixelplay.brain.Repetition.mixDay(now, tz),
            )
            // How it was ranked is decided here, once, and saved with the list: its plays carry it (5.12 point 8).
            choice = st.rankingChoice(st.session.sessionId, listSeed)
            val ranking = choice.source
            // 5.10: the arm bandit and the learned appetite, while the learned weights rank this list; stage 2's values otherwise.
            val picker = st.armPicker(st.session.sessionId, com.theveloper.pixelplay.brain.ArmBandit.MIX, listSeed xor 0x41524D53L)
            val appetite = if (choice.modelVersion == BrainEventRecorder.MODEL_LEARNED) {
                com.theveloper.pixelplay.brain.NoveltyAppetite.nu(st.learner.posteriors.appetite, now)
            } else MixPlanner.NU0
            if (kind != MixKinds.FIRST_LISTEN) {
                request.ranking = ranking
                request.armPicker = picker
                request.nuBase = appetite
                return@withState MixPlanner.plan(request, st.affinity)
            }
            // First Listen: which tracks are adjacent-genre ones, and which artists the listener never played (6.2).
            val flagged = enriched.map { c ->
                c.withFlags(edge = c.rid in edgeRids, artistNew = c.aid != 0L && !Affinity.isHeard(st.affinity, Family.ARTIST, c.aid))
            }
            MixPlanner.plan(
                MixPlanner.Request(
                    kind = kind, candidates = flagged, seeds = request.seeds, nowMs = now, tzOffsetMin = tz, nuMultiplier = request.nuMultiplier,
                    salt = salt, regenerate = regenerate, excludedRids = request.excludedRids, servedTimes = times, edges = edges,
                    islands = request.islands, embeddings = known.embeddings, tagsByRid = known.tags,
                ).also { it.ranking = ranking; it.armPicker = picker },
                st.affinity,
            )
        }
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
                        whyArg = pick.whyArg, whyName = whyName, retrQ = pick.retrQ, radioRank = novel.radioRank.takeIf { it > 0 } ?: -1, explore = pick.explore,
                        sx = pick.servedX?.let(com.theveloper.pixelplay.brain.ServedVector::toHex).orEmpty(),
                    )
                )
            } else {
                val track = pool.library[pick.rid] ?: continue
                items.add(
                    StoredMixItem(
                        rid = pick.rid, songId = track.song.id, arm = pick.arm, slot = items.size, whyKey = whyKey,
                        whyArg = pick.whyArg, whyName = whyName, retrQ = pick.retrQ, radioRank = pick.radioRank, explore = pick.explore,
                        sx = pick.servedX?.let(com.theveloper.pixelplay.brain.ServedVector::toHex).orEmpty(),
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
            mixId = spec.surfaceId,
            islandId = if (useIslands && islandIdx >= 0) spec.islandId else 0,
            number = if (useIslands && islandIdx >= 0) (islands?.dailyIslands()?.indexOfFirst { it.id == spec.islandId } ?: -1) + 1 else 0,
            subtitle = if (useIslands && islandIdx >= 0 && islands != null) islandSubtitle(islands.islands[islandIdx], now) else "",
            control = choice.control,
            model = choice.modelVersion,
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
                if (BrainText.fold(LibraryIndex.primaryArtistName(t.song)) in NO_ARTIST_NAMES) continue
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
    internal suspend fun pickSeeds(now: Long, tz: Int, salt: Long, byRid: Map<Long, LibraryTrack>, taste: TasteConstraints): List<PickedSeed> {
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
        val picks = pickedArtists.all().map { p ->
            val track = EngineTrack.from(p.seed.row, p.seed.rid)
            PickedSeed(
                rid = p.seed.rid, aid = identity.resolveArtistNames(listOf(p.name)).firstOrNull() ?: 0L, title = p.seed.row.title,
                artist = p.name, playable = track.toPlayable(now),
            )
        }
        val selected = Seeds.select(groups).map { s ->
            val track = byRid[s.rid]
            if (track != null) {
                PickedSeed(s.rid, s.aid, track.song.title, LibraryIndex.primaryArtistName(track.song), song = track.song)
            } else {
                val row = playables[s.rid]
                PickedSeed(s.rid, s.aid, row?.title.orEmpty(), row?.artistDisplay.orEmpty(), playable = row)
            }
        }
        // The picked artists after the day's seeds, each once.
        val have = selected.mapTo(HashSet()) { it.rid }
        return selected + picks.filter { it.rid !in have }
    }

    /**
     * About 500 library tracks the mixes may pick: the legacy ranking's best 300 (it knows the library's
     * own engagement counters), the favourites, and a day-seeded handful of the rest so deep cuts get a
     * chance. Each gets its recording id; the whole library is not resolved.
     */
    // One immutable pair: the key and the list it was built for are published together, so a reader on another
    // thread (the refresh worker) never sees the new key with the old list.
    private class Slice(val key: String, val tracks: List<LibraryTrack>)

    @Volatile private var sliceCache: Slice? = null

    internal suspend fun shortlist(now: Long, tz: Int, salt: Long, taste: TasteConstraints): List<LibraryTrack> {
        val all = musicRepository.getAllSongsOnce()
        val day = Repetition.mixDay(now, tz)
        val favourites = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        // The favourites are part of the key: a song liked since is a "liked" row at once, not tomorrow.
        val key = "${all.size}:$day:${constraints.signature()}:${favourites.hashCode()}"
        sliceCache?.let { if (it.key == key && it.tracks.isNotEmpty()) return it.tracks }
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
        // Repeat activation (5.6): how hot each track still is in the listener's memory (features 10 and 11).
        val activation: Map<Long, Double> = runCatching {
            val stamps = ArrayList<com.theveloper.pixelplay.data.brain.store.PlayStamp>()
            for (chunk in rids.filter { it != 0L }.chunked(ACTIVATION_CHUNK)) {
                stamps.addAll(playEventDao.stampsOf(chunk, now - ActivationFeatures.LOOK_BACK_MS))
            }
            ActivationFeatures.compute(stamps, now)
        }.getOrDefault(emptyMap())
        val out = ArrayList<LibraryTrack>(kept.size)
        for (i in kept.indices) {
            if (rids[i] == 0L) continue
            val song = kept[i]
            out.add(
                LibraryTrack(
                    song, rids[i], aids[i], liked = song.isFavorite || song.id in favourites,
                    boostLn = taste.boostLn(song), actr = activation[rids[i]] ?: 0.0,
                )
            )
        }
        sliceCache = Slice(key, out)
        return out
    }

    internal suspend fun salt(): Long {
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
    internal suspend fun materialize(stored: StoredMix, now: Long, recIdKnown: Long = 0L): MixView? {
        val taste = constraints.snapshot(now)
        val libraryIds = stored.items.mapNotNull { it.songId }
        val library = if (libraryIds.isEmpty()) emptyMap() else
            musicRepository.getSongsByIds(libraryIds).first().associateBy { it.id }
        val spec = MixSpec(stored.kind, stored.islandId)
        val surface = spec.surface
        val recId = if (recIdKnown != 0L) recIdKnown else ServedListStore.recIdOf(stored.builtAtMs, surface)
        val songs = ArrayList<Song>(stored.items.size)
        val tracks = ArrayList<EngineTrack>()
        val reasons = HashMap<String, MixReason>()
        val tags = HashMap<String, BrainItemTag>(stored.items.size * 2)
        val novel = HashSet<String>()
        val ridBySong = HashMap<String, Long>(stored.items.size * 2)
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
            ridBySong[song.id] = item.rid
            val key = WhyLine.nameOf(item.whyKey)
            if (item.whyKey != WhyLine.NONE && (item.whyKey == WhyLine.ON_REPEAT || item.whyName.isNotEmpty())) {
                reasons[song.id] = MixReason(key, item.whyName)
            }
            tags[song.id] = BrainItemTag(
                surface = Surface.nameOf(surface),
                surfaceId = spec.surfaceId,
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
                control = stored.control,
                modelVersion = stored.model,
                explore = item.explore,
                servedX = item.sx.takeIf { it.isNotEmpty() },
            )
        }
        // A mix is the same all day: what is left of it is shown, a new list is drawn only when almost nothing is.
        if (songs.size < MIN_SAVED_SONGS) return null
        if (tracks.isNotEmpty()) playableStore.register(tracks, now)
        return MixView(
            stored.kind, stored.builtAtMs, recId, songs, reasons, tags, novel, stored.seedLists, spec, liveNumberOf(spec) ?: stored.number, stored.subtitle, ridBySong,
            storedItems = stored.items, control = stored.control, model = stored.model,
        )
    }

    companion object {
        /** SQLite allows 999 variables in one statement. */
        const val ACTIVATION_CHUNK = 800
        const val KEY_SALT = "salt"
        const val PICKS = 300
        const val FAVOURITES = 150
        const val DEEP = 100
        const val MIN_SONGS = 10

        /** The share of new music in a daypart mix (6.2). */
        const val DAYPART_NU = 0.15
        const val DAYPART_COUNT = 25

        /** A saved mix that has fewer songs than this left (songs left the library, taste changed) is replaced; more is kept. */
        const val MIN_SAVED_SONGS = 3
        const val TOP_ARTISTS = 20
        /** Folded names that name no artist: such a library credit is not one a station, a radio or a release lookup can use. */
        internal val NO_ARTIST_NAMES = setOf("various artists", "unknown artist", "unknown", "va", "<unknown>")
        private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
        private const val REBUILD_MIN_AGE_MS = 10L * 60 * 1000

        fun surfaceIdOf(kind: Int): String = if (kind == MixPlanner.KIND_YOUR) "your" else "daily"

        fun tzOffsetMin(nowMs: Long): Int = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
    }
}
