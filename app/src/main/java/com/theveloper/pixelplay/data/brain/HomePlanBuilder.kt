package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.HomePlanner
import com.theveloper.pixelplay.brain.Retrieval
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Plans the Home page (docs/brain-spec.md, 8.1, stage 2) in the background: it builds the made-for-you lists and every shelf of
 * the table in the fixed order, lets [HomePlanner] apply the page rules, and saves the result as `home_plan.json`.
 *
 * Triggers (2.3 step 8): the first Home after 04:00 or a new daypart, a change to the listener's taste adjustments, and a saved
 * plan that is missing. Never at app start and never on every Home start: [isDue] is a comparison of two numbers.
 *
 * Everything here reads the database and the caches the refresh worker left; nothing asks the network. A shelf whose music is not
 * known yet is left out, and a plan with no shelf at all is still a plan (Jump back in and the library rows carry the page).
 */
@Singleton
class HomePlanBuilder @Inject constructor(
    private val mixes: BrainMixes,
    private val homeRows: BrainHomeRows,
    private val genreStations: GenreStations,
    private val store: HomePlanStore,
    private val brainSwitch: BrainSwitch,
    private val seedDao: SeedDao,
    private val musicRepository: MusicRepository,
    private val clock: BrainClock,
    private val contextStore: ContextStore,
    private val shelfStore: ShelfStore,
) {
    private val lock = Mutex()

    /** True when the saved plan is for another day or daypart, or was made under other taste adjustments. */
    fun isDue(plan: HomePlan?, nowMs: Long, taste: String): Boolean {
        if (plan == null) return true
        if (plan.taste != taste) return true
        return plan.planKey != HomePlanner.planKey(nowMs, BrainMixes.tzOffsetMin(nowMs))
    }

    /** Builds and saves the plan; null when the brain does not own Home (switched off, or nothing to rank by yet). */
    suspend fun build(): HomePlan? = lock.withLock {
        try {
            if (brainSwitch.engine() != BrainEngineChoice.BRAIN) return@withLock null
            if (!mixes.hasState()) return@withLock null
            val started = System.nanoTime()
            val plan = plan(clock.nowMs())
            store.write(plan)
            PerformanceMetrics.recordTiming("brain_home_plan", (System.nanoTime() - started) / 1_000_000)
            plan
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: planning Home failed; the saved plan stays")
            null
        }
    }

    private fun nameHash(name: String?): Long = if (name.isNullOrBlank()) 0L else BrainHash.fnv1a64(BrainText.fold(name))

    private fun art(song: Song): String? = song.albumArtUriString?.takeIf { it.isNotBlank() }

    private fun cardOf(view: MixView): PlanMix = PlanMix(
        mixId = view.spec.surfaceId, kind = view.spec.kind, number = view.number, subtitle = view.subtitle,
        count = view.songs.size, covers = view.songs.mapNotNull(::art).distinct().take(3), builtAtMs = view.builtAtMs,
    )

    private suspend fun plan(now: Long): HomePlan {
        val tz = BrainMixes.tzOffsetMin(now)
        val taste = mixes.constraints.signature()
        // The islands first (weekly): the Daily Mixes are one per island.
        val islands = mixes.islandBuilder.ensure()
        // The made-for-you lists, in the order that gives each its own music: a track is in one of them a day (6.4).
        val built = ArrayList<MixView>()
        suspend fun add(spec: MixSpec) {
            mixes.todayOf(spec)?.let { built.add(it) }
        }
        // On Repeat is what the listener plays now: it takes its tracks first, so the lists drawn from the most played do not leave it short.
        val repeat = mixes.todayOf(MixSpec(MixKinds.ON_REPEAT))
        add(MixSpec(MixKinds.FIRST_LISTEN))
        if (islands != null && islands.hasMany) for (island in islands.dailyIslands()) add(MixSpec(MixKinds.DAILY, island.id))
        else add(MixSpec(MixKinds.DAILY))
        add(MixSpec(MixKinds.YOUR))
        add(MixSpec(MixKinds.JUST_DROPPED))
        val rediscover = mixes.todayOf(MixSpec(MixKinds.REDISCOVER))
        val cards = built.map(::cardOf)

        val shelves = ArrayList<PlanShelf>()
        val inputs = ArrayList<HomePlanner.ShelfIn>()
        fun addShelf(shelf: PlanShelf) {
            val index = shelves.size
            shelves.add(shelf)
            val items = when {
                shelf.mixes.isNotEmpty() -> shelf.mixes.mapIndexed { i, _ -> HomePlanner.Item(0L, 0L, i) }
                shelf.stations.isNotEmpty() -> shelf.stations.mapIndexed { i, _ -> HomePlanner.Item(0L, 0L, i) }
                else -> shelf.items.mapIndexed { i, it -> HomePlanner.Item(it.rid, it.aid, i) }
            }
            inputs.add(HomePlanner.ShelfIn(shelf.type, "$index:${shelf.id}", items))
        }

        // Made for you: the lists as cards.
        // Endless Listening leads the shelf: a station, not a list (it is in no served_list row and in no other mix of the day), so it is on the shelf only.
        val endless = endlessCard(now)
        if (cards.isNotEmpty() || endless != null) {
            addShelf(PlanShelf(HomePlanner.MADE_FOR_YOU, "made_for_you", "made_for_you", mixes = listOfNotNull(endless) + cards))
        }
        // Because you listened to X (at most two, the newest anchors by different artists): their saved rows.
        for (row in homeRows.cached().take(2)) {
            val items = row.items.mapIndexed { slot, item ->
                val artist = item.track.artists.firstOrNull()?.name
                val artistMore = item.arm == Arm.ARTIST && !artist.isNullOrBlank()
                PlanItem(
                    rid = item.track.rid, aid = nameHash(artist), row = StoredRadioRow(item.track.toRadioRow(), item.track.rid),
                    arm = item.arm, whyKey = if (artistMore) WhyLine.ARTIST_MORE else WhyLine.RADIO_FANS,
                    whyName = if (artistMore) artist.orEmpty() else row.anchor.primaryArtist, slot = slot, explore = item.explore,
                    sx = item.servedX.orEmpty(),
                )
            }
            addShelf(PlanShelf(HomePlanner.BECAUSE_LISTENED, row.id, "because_listened", row.anchor.title, "because_listened_subtitle", items, recId = row.recId, control = row.control, model = row.model))
        }
        // Your weekday nights (stage 3): the island that fits this time of the week, only once the bucket has 50 plays (5.10).
        daypartShelf(now, tz, islands)?.let { addShelf(it) }
        // New from artists you love: the saved releases.
        val allSongs = musicRepository.getAllSongsOnce()
        val releaseCards = mixes.newReleases.cards(mixes.blockedArtists.current, mixes.tasteAdjustments.snapshot(now), LibraryIndex(allSongs))
        if (releaseCards.isNotEmpty()) {
            val items = releaseCards.mapIndexed { slot, c ->
                PlanItem(
                    rid = c.stored.rid, aid = nameHash(c.stored.row.artists.firstOrNull()?.name), row = c.stored, arm = Arm.NEW,
                    whyKey = WhyLine.NEW_RELEASE, whyName = c.release.artistName, slot = slot,
                )
            }
            addShelf(PlanShelf(HomePlanner.NEW_FROM_LOVED, "new_from_loved", "new_from_loved", subtitleKey = "new_from_loved_subtitle", items = items, recId = BrainReleaseRow.recIdOf(releaseCards.maxOf { it.release.foundAtMs })))
        }
        repeat?.let { addShelf(PlanShelf(HomePlanner.ON_REPEAT, "on_repeat", "on_repeat", subtitleKey = "on_repeat_subtitle", items = libraryItems(it, Arm.FAV), recId = it.recId)) }
        rediscover?.let { addShelf(PlanShelf(HomePlanner.REDISCOVER, "rediscover", "rediscover", subtitleKey = "rediscover_subtitle", items = libraryItems(it, Arm.FAV), recId = it.recId)) }
        // Stations for you: genres and moods of the listener's own songs, plus one genre next to them.
        val adjacent = mixes.adjacentGenres()
        val stationCards = genreStations.cards(now, adjacent)
        if (stationCards.isNotEmpty()) {
            addShelf(
                PlanShelf(
                    HomePlanner.STATIONS, "stations", "stations", subtitleKey = "stations_subtitle",
                    stations = stationCards.map { PlanStation(it.kind, it.atlasId, it.name, it.count, it.covers) },
                )
            )
        }
        stepSideways(now, adjacent)?.let { addShelf(it) }
        moreLikeAlbum(now, allSongs)?.let { addShelf(it) }

        // The shelf bandit's order (5.10), drawn once per daypart; with no data, or while the gate has the prior on, stage 2's fixed order stays.
        val fixedTypes = inputs.map { it.type }.distinct().toIntArray()
        val drawn = shelfStore.order(fixedTypes, HomePlanner.planKey(now, tz), HomePlanner.daypart(now, tz), mixes.salt(), now)
        val ordered = if (drawn != null) HomePlanner.reorder(inputs, drawn) else inputs

        // The page rules.
        val chosen = HomePlanner.plan(ordered)
        val out = ArrayList<PlanShelf>(chosen.size)
        for (s in chosen) {
            val index = s.id.substringBefore(':').toInt()
            val source = shelves[index]
            val keep = s.items.map { it.key }
            out.add(
                when {
                    source.mixes.isNotEmpty() -> source.copy(mixes = keep.map { source.mixes[it] })
                    source.stations.isNotEmpty() -> source.copy(stations = keep.map { source.stations[it] })
                    else -> source.copy(items = keep.map { source.items[it] })
                }
            )
        }

        // The hero: First Listen on Mondays, a release from a top-5 artist, else a Daily Mix.
        val dailyCards = cards.filter { it.kind == MixKinds.DAILY }
        val topRelease = topArtistRelease(now, releaseCards)
        val salt = mixes.salt()
        val hero = HomePlanner.hero(
            hasFirstListen = cards.any { it.kind == MixKinds.FIRST_LISTEN }, hasTopRelease = topRelease != null,
            dailyCount = dailyCards.size, nowMs = now, tzOffsetMin = tz, salt = salt,
        )
        val planHero = when (hero.kind) {
            HomePlanner.HERO_FIRST_LISTEN -> PlanHero(hero.kind, mixId = "first")
            HomePlanner.HERO_DAILY -> PlanHero(hero.kind, mixId = dailyCards[hero.index].mixId)
            HomePlanner.HERO_RELEASE -> topRelease?.let { PlanHero(hero.kind, item = it, artistName = it.whyName) }
            else -> null
        }
        return HomePlan(
            generatedAtMs = now, planKey = HomePlanner.planKey(now, tz), taste = taste,
            daypart = HomePlanner.daypart(now, tz), weekday = HomePlanner.weekday(now, tz),
            hero = planHero, mixes = cards, shelves = out,
        )
    }

    /**
     * The Endless Listening card: present when there are at least two seeds to start from (one for each island, or the top artists), so the
     * station is more than one song's radio. [PlanMix.subtitle] holds the names of the islands the seeds stand for (real names only, "" for none).
     * Nothing is fetched; a failure leaves the card out and the rest of the plan alone.
     */
    private suspend fun endlessCard(now: Long): PlanMix? = try {
        val seeds = mixes.endlessSeeds(now)
        if (seeds.size < 2) null else PlanMix(
            mixId = ENDLESS_ID, kind = MixKinds.ENDLESS, number = 0,
            subtitle = mixes.endlessIslandNames(seeds).take(3).joinToString(", "),
            count = seeds.size, covers = seeds.mapNotNull { art(it.song) }.distinct().take(3), builtAtMs = now,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "brain: the Endless Listening card could not be planned")
        null
    }

    /**
     * "Your weekday nights": the daypart mix of the island that leads this bucket of the week. Absent until the bucket has [ContextLift.MIN_PLAYS]
     * plays with an island, and while the listener has one island only (nothing leads).
     */
    private suspend fun daypartShelf(now: Long, tz: Int, islands: IslandSet?): PlanShelf? {
        if (islands == null || !islands.hasMany) return null
        val table = contextStore.table(now) ?: return null
        val bucket = com.theveloper.pixelplay.brain.ContextLift.bucket(now, tz)
        if (!table.hasLift(bucket)) return null
        val leader = table.leader(bucket, islands.dailyIslands().map { it.id to it.weight })
        if (leader < 0) return null
        val view = mixes.todayOf(MixSpec(MixKinds.DAYPART, leader, bucket)) ?: return null
        val byRid = HashMap<Long, StoredMixItem>(view.storedItems.size * 2)
        for (item in view.storedItems) byRid[item.rid] = item
        val items = ArrayList<PlanItem>(view.songs.size)
        for (song in view.songs) {
            val rid = view.rids[song.id] ?: continue
            val stored = byRid[rid] ?: continue
            val row = stored.row.takeIf { song.id in view.novelIds }
            items.add(
                PlanItem(
                    rid = rid, aid = nameHash(if (row != null) row.row.artists.firstOrNull()?.name else LibraryIndex.primaryArtistName(song)),
                    songId = if (row == null) song.id else null, row = row, arm = stored.arm, slot = items.size, explore = stored.explore,
                    sx = stored.sx,
                )
            )
        }
        if (items.size < HomePlanner.MIN_TRACKS) return null
        return PlanShelf(
            HomePlanner.DAYPART_MIX, "daypart:$bucket", "your_daypart", bucket.toString(), "your_daypart_subtitle", items,
            recId = view.recId, control = view.control, model = view.model,
        )
    }

    private fun libraryItems(view: MixView, arm: Int): List<PlanItem> =
        view.songs.mapIndexed { slot, song ->
            PlanItem(
                rid = view.rids[song.id] ?: 0L, aid = nameHash(LibraryIndex.primaryArtistName(song)), songId = song.id, arm = arm, slot = slot,
            )
        }

    /** A release by one of the listener's five most played artists, or null. */
    private suspend fun topArtistRelease(now: Long, cards: List<com.theveloper.pixelplay.data.brain.NewReleaseRules.Card>): PlanItem? {
        if (cards.isEmpty()) return null
        val names = cards.map { it.stored.row.artists.firstOrNull()?.name.orEmpty() }
        val aids = mixes.identity.resolveArtistNames(names)
        val top = mixes.brain.withState { st ->
            st.affinity.sortedKeys(Family.ARTIST).sortedWith { a, b ->
                val c = Affinity.weight(st.affinity, Family.ARTIST, b, now).compareTo(Affinity.weight(st.affinity, Family.ARTIST, a, now))
                if (c != 0) c else a.compareTo(b)
            }.take(5).toHashSet()
        }
        for (i in cards.indices) {
            if (aids[i] in top) {
                val c = cards[i]
                return PlanItem(
                    rid = c.stored.rid, aid = nameHash(names[i]), row = c.stored, arm = Arm.NEW,
                    whyKey = WhyLine.NEW_RELEASE, whyName = c.release.artistName, slot = 0,
                )
            }
        }
        return null
    }

    /** "A step sideways": one genre next to what the listener plays, with songs of it they have not played. The card says so. */
    private suspend fun stepSideways(now: Long, adjacent: IntArray): PlanShelf? {
        if (adjacent.isEmpty()) return null
        val atlas = mixes.atlasProvider.atlas() ?: return null
        val byGenre = mixes.edgeByGenre(now)
        val genre = byGenre.entries.maxByOrNull { it.value.size }?.takeIf { it.value.size >= HomePlanner.MIN_TRACKS } ?: return null
        val name = AtlasNames.genreName(atlas, genre.key) ?: return null
        val items = genre.value.take(HomePlanner.ITEMS_PER_SHELF * 2).mapIndexed { slot, t ->
            PlanItem(rid = t.rid, aid = nameHash(LibraryIndex.primaryArtistName(t.song)), songId = t.song.id, arm = Arm.EDGE, whyKey = WhyLine.ADJACENT_GENRE, slot = slot)
        }
        return PlanShelf(HomePlanner.STEP_SIDEWAYS, "step_sideways:${genre.key}", "step_sideways", name, "step_sideways_subtitle", items)
    }

    /**
     * "More like <album>": an album the listener finished three songs of lately, as a station of its radios (the cached ones
     * of up to three of its songs, merged by co-occurrence, 6.2). Nothing is fetched: an album none of whose radios are cached
     * gets no shelf.
     */
    private suspend fun moreLikeAlbum(now: Long, allSongs: List<Song>): PlanShelf? {
        val recent = seedDao.recentCompleted(now - ALBUM_WINDOW_MS, 80)
        if (recent.isEmpty()) return null
        val library = mixes.libraryRids.all(now)
        val byRid = HashMap<Long, LibraryTrack>(library.size * 2)
        for (t in library) byRid.putIfAbsent(t.rid, t)
        data class Group(val album: String, val tracks: ArrayList<LibraryTrack>, val newest: Long)
        val groups = LinkedHashMap<String, Group>()
        for (row in recent) {
            val track = byRid[row.rid] ?: continue
            val album = track.song.album.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) } ?: continue
            val g = groups.getOrPut(album) { Group(album, ArrayList(), row.lastMs) }
            if (g.tracks.none { it.rid == track.rid }) g.tracks.add(track)
        }
        val taste = mixes.constraints.snapshot(now)
        val ignored = runCatching { mixes.playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
        val index = LibraryIndex(allSongs)
        for (g in groups.values.filter { it.tracks.size >= 3 }.sortedByDescending { it.newest }) {
            val seeds = ArrayList<MixSeed>()
            for (t in g.tracks.take(3)) {
                val list = mixes.brainRepository.cachedRadio(t.rid) ?: continue
                seeds.add(MixSeed(t.rid, t.aid, t.song.title, LibraryIndex.primaryArtistName(t.song), list))
            }
            if (seeds.isEmpty()) continue
            val names = LinkedHashSet<String>()
            for (s in seeds) s.list?.rows?.forEach { r -> r.row.artists.firstOrNull()?.name?.let(names::add) }
            val nameList = names.toList()
            val nameAids = mixes.identity.resolveArtistNames(nameList)
            val aidByName = HashMap<String, Long>()
            for (i in nameList.indices) aidByName[nameList[i]] = nameAids[i]
            val pool = MixPoolBuilder.build(emptyList(), seeds, index, taste, ignored) { aidByName[it] ?: 0L }
            if (pool.novel.size < HomePlanner.MIN_TRACKS) continue
            val cooc = mixes.brain.withState { st -> Retrieval.cooc(pool.seedLists, st.affinity, now, Affinity.p0(st.affinity, now)) }
            val ranked = pool.novel.values.sortedWith { a, b ->
                val c = com.theveloper.pixelplay.brain.BrainMath.orderKey(cooc[b.stored.rid]?.cooc ?: 0.0)
                    .compareTo(com.theveloper.pixelplay.brain.BrainMath.orderKey(cooc[a.stored.rid]?.cooc ?: 0.0))
                if (c != 0) c else a.stored.rid.compareTo(b.stored.rid)
            }
            val items = ranked.take(HomePlanner.ITEMS_PER_SHELF * 2).mapIndexed { slot, n ->
                PlanItem(
                    rid = n.stored.rid, aid = nameHash(n.stored.row.artists.firstOrNull()?.name), row = n.stored, arm = Arm.RADIO,
                    whyKey = WhyLine.RADIO_FANS, whyName = n.seedArtist, slot = slot,
                )
            }
            return PlanShelf(HomePlanner.MORE_LIKE_ALBUM, "more_like_album", "more_like_album", g.album, "more_like_album_subtitle", items)
        }
        return null
    }

    companion object {
        /** The `mixId` of the Endless Listening card. */
        const val ENDLESS_ID = "endless"
        private const val ALBUM_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
    }
}
