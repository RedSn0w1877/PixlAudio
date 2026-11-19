package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.HomePlanner
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainEngineChoice
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.BrainPlayTags
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.HomePlan
import com.theveloper.pixelplay.data.brain.HomePlanBuilder
import com.theveloper.pixelplay.data.brain.HomePlanStore
import com.theveloper.pixelplay.data.brain.MixKinds
import com.theveloper.pixelplay.data.brain.MixSpec
import com.theveloper.pixelplay.data.brain.PlanHero
import com.theveloper.pixelplay.data.brain.PlanItem
import com.theveloper.pixelplay.data.brain.PlanMix
import com.theveloper.pixelplay.data.brain.PlanShelf
import com.theveloper.pixelplay.data.brain.PlanStation
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.RecentContext
import com.theveloper.pixelplay.data.brain.RecentContexts
import com.theveloper.pixelplay.data.brain.TasteConstraintAdapter
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.HomeMusicSection
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A made-for-you list as Home draws it. */
@Immutable
data class MixCardUi(
    val mixId: String,
    val kind: Int,
    /** 1..K for a Daily Mix of an island; 0 otherwise. */
    val number: Int,
    val title: String,
    val subtitle: String,
    val count: Int,
    val covers: ImmutableList<String>,
)

@Immutable
data class StationCardUi(val kind: Int, val atlasId: Int, val name: String, val count: Int, val covers: ImmutableList<String>)

/** One thing on the page, in the order the plan gives (docs/brain-spec.md, 8.1). */
sealed interface HomeShelfUi {
    val id: String

    /** A shelf of songs: drawn by the existing shelf component. */
    @Immutable
    data class Tracks(val section: HomeMusicSection) : HomeShelfUi {
        override val id: String get() = section.id
    }

    /** "Made for you": the lists as cards. */
    @Immutable
    data class Mixes(override val id: String, val title: String, val subtitle: String, val cards: ImmutableList<MixCardUi>) : HomeShelfUi

    @Immutable
    data class Stations(override val id: String, val title: String, val subtitle: String, val cards: ImmutableList<StationCardUi>) : HomeShelfUi
}

@Immutable
data class HeroUi(
    val kind: Int,
    val title: String,
    val subtitle: String,
    val art: String?,
    val mixId: String,
    /** For a release: the song to play. */
    val song: Song? = null,
)

/** A "Jump back in" tile: a place the listener played from (8.1). */
@Immutable
data class TileUi(
    val key: String,
    val title: String,
    val art: String?,
    val kind: Int,
    val mixId: String,
    val songIds: ImmutableList<String>,
)

/**
 * What Home shows. [hasPlan] is false until a plan is read or built; Home never shows a spinner or an empty state for it: without a
 * plan it shows Jump back in and the library rows (8.1, 8.10).
 */
@Immutable
data class HomePlanUi(
    /** The brain owns Home: the engine is on and a plan was read or built. False falls back to the legacy Home. */
    val owned: Boolean = false,
    val dateLine: String = "",
    val hero: HeroUi? = null,
    val shelves: ImmutableList<HomeShelfUi> = persistentListOf(),
    val tiles: ImmutableList<TileUi> = persistentListOf(),
    /** Every made-for-you card, for the "Mixes" chip. */
    val allMixes: ImmutableList<MixCardUi> = persistentListOf(),
    /** The "New" chip: just dropped, First Listen and the releases. */
    val newShelves: ImmutableList<HomeShelfUi> = persistentListOf(),
    val message: String? = null,
    /** "Getting to know your music · done of total" (8.10); total 0 hides the strip. */
    val knowingDone: Int = 0,
    val knowingTotal: Int = 0,
    /** A thin library with no picked artists yet: Home offers "Pick three artists you love". */
    val offerPicker: Boolean = false,
    /**
     * The saved plan has been looked for (read, or found missing, or the engine is not the brain). Until then Home does not know whether to
     * draw the plan or the legacy page, so it draws neither (no placeholder, no jump when the file arrives).
     */
    val resolved: Boolean = false,
)

/**
 * Home's plan (docs/brain-spec.md, 8.1, stage 2): reads `home_plan.json` first, before anything else, and shows it; plans in the
 * background when the day or the daypart changed (04:00 local) or the taste adjustments did, and shows the new plan the next
 * time Home appears: it never changes under the thumb. A `@Singleton` state holder (the repo convention), so the plan survives
 * the ViewModel and is shared with whatever else asks.
 *
 * It replaces the refresh in `HomeDiscoveryStateHolder` (which ranked the whole library on every Home start) whenever the brain
 * engine owns Home; with the legacy engine, or before the brain has enough to go on, that holder runs as it always did.
 */
@Singleton
class HomePlanStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: HomePlanStore,
    private val builder: HomePlanBuilder,
    private val brainSwitch: BrainSwitch,
    private val constraints: TasteConstraintAdapter,
    private val mixes: BrainMixes,
    private val recentContexts: RecentContexts,
    private val musicRepository: MusicRepository,
    private val playableStore: PlayableStore,
    private val brainPlayTags: BrainPlayTags,
    private val coldStart: com.theveloper.pixelplay.data.brain.ColdStart,
    private val pickedArtists: com.theveloper.pixelplay.data.brain.PickedArtists,
    private val clock: BrainClock,
    private val shelfStore: com.theveloper.pixelplay.data.brain.ShelfStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(HomePlanUi())
    val state: StateFlow<HomePlanUi> = mutableState.asStateFlow()

    init {
        // The strip of 8.10 follows the cold start's own progress; nothing is collected until something runs.
        scope.launch {
            coldStart.progress.collect { p ->
                mutableState.update { it.copy(knowingDone = p?.done ?: 0, knowingTotal = p?.total ?: 0) }
            }
        }
    }

    /**
     * True when the brain owns Home: the engine is on and a plan is saved. Reads the saved plan if it was not read yet (one small
     * file); never loads the brain. The legacy refresh asks this before it ranks the library.
     */
    suspend fun ownsHome(): Boolean {
        if (brainSwitch.engine() != BrainEngineChoice.BRAIN) return false
        lock.withLock {
            if (!readOnce) {
                readOnce = true
                current = store.read()
            }
        }
        return current != null
    }

    /** The songs of a "Jump back in" tile, library songs in the order they were queued; empty when none is left. */
    suspend fun songsOf(tile: TileUi): List<Song> = withContext(Dispatchers.IO) {
        if (tile.songIds.isEmpty()) return@withContext emptyList()
        val byId = musicRepository.getSongsByIds(tile.songIds.toList()).first().associateBy { it.id }
        tile.songIds.mapNotNull { byId[it] }
    }

    private val lock = Mutex()
    private var current: HomePlan? = null
    private var pending: HomePlan? = null
    private var readOnce = false
    private var buildJob: Job? = null

    /** The tags of what each shelf's tap plays, by shelf id then song id. */
    @Volatile private var tagsByShelf: Map<String, Map<String, BrainItemTag>> = emptyMap()
    /** Songs of the saved plan that the library no longer has, counted by the last render: any of them asks for a new plan. */
    @Volatile private var droppedInRender = 0
    @Volatile private var liveNumbers: Map<String, Int> = emptyMap()
    private var lastStaleRebuildMs = 0L
    @Volatile private var itemsByShelf: Map<String, List<PlanItem>> = emptyMap()
    @Volatile private var songsByShelf: Map<String, Map<String, Song>> = emptyMap()

    /**
     * Home appeared. Reads the saved plan the first time (one small file) and shows it; on a later appearance shows the plan that was
     * built meanwhile. Then, once the first seconds have passed, plans again if the day, the daypart or the taste changed.
     */
    fun onHomeShown() {
        scope.launch {
            lock.withLock {
                try {
                    if (brainSwitch.engine() != BrainEngineChoice.BRAIN) {
                        mutableState.update { it.copy(owned = false, resolved = true) }
                        return@withLock
                    }
                    if (!readOnce) {
                        readOnce = true
                        current = store.read()
                    } else {
                        pending?.let {
                            current = it
                            pending = null
                        }
                    }
                    publish(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Home: reading the plan failed")
                    mutableState.update { it.copy(resolved = true) }
                }
            }
            scheduleBuild()
            scheduleColdStart()
        }
    }

    private var coldJob: Job? = null

    /**
     * The first minute of a new listener (8.10): with no plan yet, eight radios for the day's seeds, then a plan. Only with the engine on
     * and a library to read, once the first seconds have passed. A thin library is offered the artist picker instead of waiting.
     */
    private fun scheduleColdStart() {
        if (coldJob?.isActive == true) return
        if (current != null) return
        coldJob = scope.launch {
            delay(QUIET_MS)
            try {
                if (brainSwitch.engine() != BrainEngineChoice.BRAIN) return@launch
                val songs = musicRepository.getAllSongsOnce().size
                val picked = pickedArtists.hasPicks()
                mutableState.update { it.copy(offerPicker = songs < THIN_LIBRARY && !picked) }
                if (songs > 0) coldStart.run()
                // Whatever came back is the first thing a plan can be made from.
                scheduleBuild(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Home: the first minute failed")
            }
        }
    }

    /** The listener picked artists: build from them now. */
    fun onArtistsPicked() {
        mutableState.update { it.copy(offerPicker = false) }
        scope.launch { scheduleBuild(force = true) }
    }

    /** Taste changed (a boost, a mute, "Not for me"): plan again at the next quiet moment. */
    fun invalidate() {
        scope.launch { scheduleBuild(force = true) }
    }

    private fun scheduleBuild(force: Boolean = false) {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            // No brain work in the first seconds of the process (12.1): Home has already painted from the file.
            delay(QUIET_MS)
            try {
                val now = clock.nowMs()
                val taste = constraints.signature()
                val saved = lock.withLock { pending ?: current }
                if (!force && !builder.isDue(saved, now, taste)) return@launch
                val built = builder.build() ?: return@launch
                lock.withLock {
                    if (current == null) {
                        // Nothing on screen to change under the thumb: show it at once.
                        current = built
                        publish(built)
                    } else {
                        pending = built
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Home: planning failed")
            }
        }
    }

    // ---- the plan as the screen draws it ------------------------------------------------------

    private suspend fun publish(plan: HomePlan?) {
        val tiles = tiles()
        if (plan == null) {
            // Without a plan: Jump back in and the library rows. The brain owns Home only once there is one.
            mutableState.update { it.copy(owned = false, tiles = tiles, resolved = true) }
            return
        }
        val ui = withContext(Dispatchers.Default) { render(plan, tiles) }
        // Songs that left the library since the plan was made: plan again (at most every ten minutes), so counts and shelves are true.
        val now = clock.nowMs()
        if (droppedInRender > 0 && now - lastStaleRebuildMs > STALE_REBUILD_GAP_MS) {
            lastStaleRebuildMs = now
            scope.launch { scheduleBuild(force = true) }
        }
        mutableState.update { it.copy(owned = ui.owned, resolved = true, dateLine = ui.dateLine, hero = ui.hero, shelves = ui.shelves, tiles = ui.tiles, allMixes = ui.allMixes, newShelves = ui.newShelves) }
        // The page was shown: its shelves are one visit for the shelf bandit (5.10). Off the main thread, never waited for.
        if (ui.owned && ui.shelves.isNotEmpty()) {
            val typeOf = plan.shelves.associate { it.id to it.type }
            val ids = ui.shelves.map { it.id }.filter { it in typeOf }
            val types = IntArray(ids.size) { typeOf[ids[it]]!! }
            scope.launch { shelfStore.onPlanShown(ids, types, plan.daypart) }
        }
    }

    private suspend fun tiles(): ImmutableList<TileUi> {
        val keys = recentContexts.last(RecentContexts.KEEP)
        val picked = HomePlanner.distinctContexts(keys.map { it.key }, TILE_COUNT)
        return picked.map { i -> keys[i].toTile() }.toImmutableList()
    }

    private fun RecentContext.toTile() = TileUi(key, title, art, kind, mixId, songIds.toImmutableList())

    private suspend fun render(plan: HomePlan, tiles: ImmutableList<TileUi>): HomePlanUi {
        // Songs: library ids in one read, the rest from their saved rows.
        val libraryIds = LinkedHashSet<String>()
        for (shelf in plan.shelves) for (item in shelf.items) item.songId?.let(libraryIds::add)
        plan.hero?.item?.songId?.let(libraryIds::add)
        val library = if (libraryIds.isEmpty()) emptyMap() else
            musicRepository.getSongsByIds(libraryIds.toList()).first().associateBy { it.id }
        val tags = HashMap<String, Map<String, BrainItemTag>>()
        val items = HashMap<String, List<PlanItem>>()
        val songs = HashMap<String, Map<String, Song>>()
        val shelves = ArrayList<HomeShelfUi>(plan.shelves.size)
        var dropped = 0
        // A Daily Mix is numbered by today's islands, not by the islands of the day the plan was made; one whose island is gone is not drawn.
        val numbers = HashMap<String, Int>()
        val gone = HashSet<String>()
        val allMixes = plan.mixes + plan.shelves.flatMap { it.mixes }
        for (m in allMixes) {
            if (m.kind != MixKinds.DAILY || !m.mixId.startsWith("daily:") || m.mixId in numbers || m.mixId in gone) continue
            val n = MixSpec.of(m.mixId)?.let { mixes.liveNumberOf(it) }
            if (n != null) numbers[m.mixId] = n else gone.add(m.mixId)
        }
        liveNumbers = numbers
        dropped += gone.size
        for (shelf in plan.shelves) {
            when {
                shelf.mixes.isNotEmpty() -> {
                    val cards = shelf.mixes.filter { it.mixId !in gone }
                    if (cards.isNotEmpty()) shelves.add(
                        HomeShelfUi.Mixes(shelf.id, text(shelf.titleKey, shelf.titleArg), text(shelf.subtitleKey, ""), cards.map(::mixCard).toImmutableList())
                    )
                }
                shelf.stations.isNotEmpty() -> shelves.add(
                    HomeShelfUi.Stations(
                        shelf.id, text(shelf.titleKey, shelf.titleArg), text(shelf.subtitleKey, ""),
                        shelf.stations.map { StationCardUi(it.kind, it.atlasId, it.name, it.count, it.covers.toImmutableList()) }.toImmutableList(),
                    )
                )
                else -> {
                    val list = ArrayList<Song>(shelf.items.size)
                    val reasons = HashMap<String, String>()
                    val tagMap = HashMap<String, BrainItemTag>()
                    val byId = HashMap<String, Song>()
                    val kept = ArrayList<PlanItem>()
                    for (item in shelf.items) {
                        val song = when {
                            item.songId != null -> library[item.songId]
                            item.row != null -> EngineTrack.from(item.row.row, item.row.rid).toSong()
                            else -> null
                        } ?: continue
                        list.add(song)
                        kept.add(item)
                        byId[song.id] = song
                        reason(item)?.let { reasons[song.id] = it }
                        tagMap[song.id] = tagOf(shelf, item)
                    }
                    // A library that lost songs since the plan was made can leave a shelf short: under the minimum it is hidden, not padded (8.1).
                    if (list.size < shelf.items.size) dropped += shelf.items.size - list.size
                    if (list.size < HomePlanner.MIN_TRACKS) continue
                    tags[shelf.id] = tagMap
                    items[shelf.id] = kept
                    songs[shelf.id] = byId
                    shelves.add(
                        HomeShelfUi.Tracks(
                            HomeMusicSection(
                                id = shelf.id, title = text(shelf.titleKey, shelf.titleArg), subtitle = text(shelf.subtitleKey, ""),
                                songs = list.toImmutableList(), reasons = reasons.toImmutableMap(),
                            )
                        )
                    )
                }
            }
        }
        droppedInRender = dropped
        tagsByShelf = tags
        itemsByShelf = items
        songsByShelf = songs
        val cards = plan.mixes.filter { it.mixId !in gone }.map(::mixCard).toImmutableList()
        val hero = plan.hero?.takeIf { it.mixId !in gone }?.let { hero(it, plan, library) }
        val newShelves = shelves.filter {
            (it is HomeShelfUi.Tracks && it.section.id.startsWith("new_from_loved")) ||
                (it is HomeShelfUi.Mixes)
        }.map { shelf ->
            if (shelf is HomeShelfUi.Mixes) {
                val fresh = shelf.cards.filter { it.kind == MixKinds.FIRST_LISTEN || it.kind == MixKinds.JUST_DROPPED }
                shelf.copy(cards = fresh.toImmutableList())
            } else shelf
        }.filterNot { it is HomeShelfUi.Mixes && it.cards.isEmpty() }.toImmutableList()
        return HomePlanUi(
            owned = true, dateLine = dateLine(plan), hero = hero, shelves = shelves.toImmutableList(),
            tiles = tiles, allMixes = cards, newShelves = newShelves,
        )
    }

    private fun dateLine(plan: HomePlan): String {
        val day = DayOfWeek.of(plan.weekday + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
        val part = context.getString(
            when (plan.daypart) {
                HomePlanner.DAYPART_MORNING -> R.string.home_daypart_morning
                HomePlanner.DAYPART_AFTERNOON -> R.string.home_daypart_afternoon
                HomePlanner.DAYPART_EVENING -> R.string.home_daypart_evening
                else -> R.string.home_daypart_night
            }
        )
        return "$day $part"
    }

    private fun hero(h: PlanHero, plan: HomePlan, library: Map<String, Song>): HeroUi? = when (h.kind) {
        HomePlanner.HERO_FIRST_LISTEN, HomePlanner.HERO_DAILY -> plan.mixes.firstOrNull { it.mixId == h.mixId }?.let {
            val card = mixCard(it)
            HeroUi(h.kind, card.title, card.subtitle, card.covers.firstOrNull(), it.mixId)
        }
        HomePlanner.HERO_RELEASE -> h.item?.let { item ->
            val song = item.row?.let { EngineTrack.from(it.row, it.rid).toSong() } ?: item.songId?.let(library::get)
            song?.let {
                HeroUi(
                    h.kind, it.title, context.getString(R.string.brain_why_new_release, h.artistName.ifBlank { it.artist }),
                    it.albumArtUriString, "", it,
                )
            }
        }
        else -> null
    }

    private fun mixCard(m: PlanMix): MixCardUi {
        val title = when (m.kind) {
            MixKinds.DAILY -> (liveNumbers[m.mixId] ?: m.number).let { n ->
                if (n > 0) context.getString(R.string.brain_mix_daily_n, n) else context.getString(R.string.brain_mix_daily)
            }
            MixKinds.YOUR -> context.getString(R.string.brain_mix_your)
            MixKinds.FIRST_LISTEN -> context.getString(R.string.brain_mix_first_listen)
            MixKinds.JUST_DROPPED -> context.getString(R.string.brain_mix_just_dropped)
            MixKinds.ON_REPEAT -> context.getString(R.string.brain_mix_on_repeat)
            else -> context.getString(R.string.brain_mix_rediscover)
        }
        val subtitle = when {
            m.kind == MixKinds.FIRST_LISTEN -> context.getString(R.string.brain_mix_first_listen_week, weekOf(m.builtAtMs))
            m.subtitle.isNotBlank() -> m.subtitle
            m.kind == MixKinds.YOUR -> context.getString(R.string.brain_mix_your_subtitle)
            m.kind == MixKinds.JUST_DROPPED -> context.getString(R.string.brain_mix_just_dropped_subtitle)
            else -> ""
        }
        return MixCardUi(m.mixId, m.kind, liveNumbers[m.mixId] ?: m.number, title, subtitle, m.count, m.covers.toImmutableList())
    }

    /** "16 Nov": the Monday of the week the list was built in (it is fixed all week, 8.2). */
    private fun weekOf(builtAtMs: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(builtAtMs - 4L * 3_600_000L).atZone(zone).toLocalDate()
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        return "${monday.dayOfMonth} ${monday.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }

    private fun text(key: String, arg: String): String = when (key) {
        "" -> ""
        "made_for_you" -> context.getString(R.string.brain_home_made_for_you)
        "because_listened" -> context.getString(R.string.brain_home_because_listened, arg)
        "because_listened_subtitle" -> context.getString(R.string.brain_home_because_listened_subtitle)
        "new_from_loved" -> context.getString(R.string.brain_home_new_from_loved)
        "new_from_loved_subtitle" -> context.getString(R.string.brain_home_new_releases_subtitle)
        "on_repeat" -> context.getString(R.string.brain_mix_on_repeat)
        "on_repeat_subtitle" -> context.getString(R.string.brain_home_on_repeat_subtitle)
        "rediscover" -> context.getString(R.string.brain_mix_rediscover)
        "rediscover_subtitle" -> context.getString(R.string.brain_home_rediscover_subtitle)
        "stations" -> context.getString(R.string.brain_home_stations)
        "stations_subtitle" -> context.getString(R.string.brain_home_stations_subtitle)
        "step_sideways" -> context.getString(R.string.brain_home_step_sideways)
        "step_sideways_subtitle" -> context.getString(R.string.brain_home_step_sideways_subtitle, arg)
        "more_like_album" -> context.getString(R.string.brain_home_more_like_album, arg)
        "more_like_album_subtitle" -> context.getString(R.string.brain_home_more_like_album_subtitle)
        "your_daypart" -> context.getString(daypartTitle(arg.toIntOrNull() ?: 0))
        "your_daypart_subtitle" -> context.getString(R.string.brain_home_daypart_subtitle)
        else -> ""
    }

    private fun daypartTitle(bucket: Int): Int = when (bucket) {
        0 -> R.string.brain_home_daypart_weekday_morning
        1 -> R.string.brain_home_daypart_weekday_day
        2 -> R.string.brain_home_daypart_weekday_evening
        3 -> R.string.brain_home_daypart_weekday_night
        4 -> R.string.brain_home_daypart_weekend_morning
        5 -> R.string.brain_home_daypart_weekend_day
        6 -> R.string.brain_home_daypart_weekend_evening
        else -> R.string.brain_home_daypart_weekend_night
    }

    private fun reason(item: PlanItem): String? {
        val name = item.whyName
        return when (item.whyKey) {
            WhyLine.RADIO_FANS -> name.takeIf { it.isNotBlank() }?.let { context.getString(R.string.brain_why_radio_fans, it) }
            WhyLine.ARTIST_MORE -> name.takeIf { it.isNotBlank() }?.let { context.getString(R.string.brain_why_artist_more, it) }
            WhyLine.NEW_RELEASE -> name.takeIf { it.isNotBlank() }?.let { context.getString(R.string.brain_why_new_release, it) }
            WhyLine.ADJACENT_GENRE -> context.getString(R.string.brain_why_adjacent_genre)
            else -> null
        }
    }

    private fun tagOf(shelf: PlanShelf, item: PlanItem): BrainItemTag = BrainItemTag(
        surface = Surface.nameOf(Surface.HOME_SHELF),
        surfaceId = shelf.id.take(40),
        recId = shelf.recId,
        slot = item.slot,
        arm = com.theveloper.pixelplay.brain.Arm.nameOf(item.arm),
        whyKey = WhyLine.nameOf(item.whyKey).takeIf { item.whyKey != WhyLine.NONE },
        whyArg = item.whyName.takeIf { it.isNotEmpty() },
        primaryArtist = item.row?.row?.artists?.firstOrNull()?.name,
        // The listener chose to play the shelf: its tracks are theirs, like a playlist's.
        chosen = true,
        control = shelf.control,
        modelVersion = shelf.model,
        explore = item.explore,
    )

    // ---- playing a shelf ----------------------------------------------------------------------

    /**
     * A card of a track shelf was tapped. Tracks from outside the library go into `playable` first so they can play, and the play carries
     * the shelf, each pick's arm and reason into the queue and the event log (7.2, 4.2).
     */
    suspend fun prepare(section: HomeMusicSection, start: Song, onReady: (List<Song>, Song) -> Unit) {
        try {
            val outside = withContext(Dispatchers.IO) {
                itemsByShelf[section.id].orEmpty().mapNotNull { it.row?.let { r -> EngineTrack.from(r.row, r.rid) } }
            }
            if (outside.isNotEmpty()) withContext(Dispatchers.IO) { playableStore.register(outside, clock.nowMs()) }
            brainPlayTags.expect(queueName = section.title, bySongId = tagsByShelf[section.id].orEmpty())
            scope.launch { shelfStore.onShelfActivated(section.id) }
            onReady(section.songs, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Home: could not prepare a shelf")
            mutableState.update { it.copy(message = context.getString(R.string.brain_home_could_not_play)) }
        }
    }

    /** The hero release was tapped: it plays alone (autoplay carries on from it). */
    suspend fun prepareSingle(song: Song, onReady: (List<Song>, Song) -> Unit) {
        withContext(Dispatchers.IO) {
            if (EngineTrack.isEngineOnly(song)) {
                val row = current?.hero?.item?.row
                if (row != null) playableStore.register(listOf(EngineTrack.from(row.row, row.rid)), clock.nowMs())
            }
        }
        onReady(listOf(song), song)
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    /** A shelf was on screen for a second (5.10). */
    fun onShelfSeen(shelfId: String) {
        scope.launch { shelfStore.onShelfSeen(shelfId) }
    }

    /** Something was started from a shelf: a card of Made for you or Stations, or a song of a shelf (5.10). */
    fun onShelfActivated(shelfId: String) {
        scope.launch { shelfStore.onShelfActivated(shelfId) }
    }

    private companion object {
        /** 12.1: no brain work before the first seconds have passed. */
        const val QUIET_MS = 2_500L
        const val TILE_COUNT = 6
        const val STALE_REBUILD_GAP_MS = 10L * 60 * 1000

        /** A library with fewer songs than this is "thin": it gets the artist picker (8.10). */
        const val THIN_LIBRARY = 30
    }
}
