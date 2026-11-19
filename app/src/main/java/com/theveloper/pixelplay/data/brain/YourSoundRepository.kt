package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Gate
import com.theveloper.pixelplay.brain.GradeTracker
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.ModelVoice
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.YourSound
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Your sound" (docs/brain-spec.md, 8.6): what the listener's own plays say, worked out on the phone from the event log, the islands and
 * the track facts, and what the model may honestly claim about itself ([ModelVoice]). Off the main thread, when the screen is opened,
 * cached in memory for a few minutes; nothing here runs at app start. A section with nothing to say is empty and the screen leaves it out.
 */
@Singleton
class YourSoundRepository @Inject constructor(
    private val engine: BrainEngine,
    private val store: BrainStore,
    private val islandStore: IslandStore,
    private val mixes: dagger.Lazy<BrainMixes>,
    private val trackDna: TrackDnaDao,
    private val artistDna: ArtistDnaDao,
    private val atlasProvider: AtlasProvider,
    private val clock: BrainClock,
    private val shelfStore: ShelfStore,
) {
    /**
     * One island of "Right now": its share, and what tapping it shows (8.6 point 1): its top artists and songs by name (a name the brain does
     * not know is left out), and the genre More / Less and "Play station" act on ([genreId] is -1 when the island has no genre the app may name).
     */
    class IslandRow(
        val id: Int,
        val name: String?,
        val permille: Int,
        val topArtists: List<String> = emptyList(),
        val topSongs: List<String> = emptyList(),
        val genreId: Int = -1,
        val genreName: String? = null,
    )

    /** A family of the genre tree with the genres played inside it (8.6 point 2). */
    class GenreFamilyRow(val rootId: Int, val name: String, val permille: Int, val children: List<GenreRow>)

    /** "Lately" (8.6 point 8): the names whose affinity moved most in 14 days. */
    class Lately(val up: List<String>, val down: List<String>) {
        val isEmpty: Boolean get() = up.isEmpty() && down.isEmpty()
    }
    class GenreRow(val atlasId: Int, val name: String, val permille: Int)
    class DaypartRow(val daypart: Int, val plays: Int, val leadingIsland: String?)
    class MonthRow(val month: Int, val plays: Int, val islands: List<Pair<String?, Int>>)

    /** The completion rate of the picks ranked with the prior in control sessions, next to the learned weights' (5.12 point 8). */
    class Control(val controlPlays: Int, val controlPermille: Int?, val learnedPlays: Int, val learnedPermille: Int?)

    class State(
        val plays: Int,
        val islands: List<IslandRow>,
        val genres: List<GenreRow>,
        val genreTree: List<GenreFamilyRow>,
        val lately: Lately,
        val decades: List<YourSound.Decade>,
        val dayparts: List<DaypartRow>,
        val months: List<MonthRow>,
        val novelty: YourSound.Novelty?,
        val model: ModelVoice.State,
        val control: Control?,
        val computedAtMs: Long,
    ) {
        val isEmpty: Boolean get() = plays == 0
    }

    @Volatile private var cached: State? = null

    /** The figures, from the cache when they are under [CACHE_MS] old. */
    suspend fun load(force: Boolean = false): State = withContext(Dispatchers.IO) {
        val now = clock.nowMs()
        cached?.let { if (!force && now - it.computedAtMs in 0 until CACHE_MS) return@withContext it }
        val state = compute(now)
        cached = state
        state
    }

    /** "Reset what I've learned": the weights go back to the prior, the log, the taste changes and the exclusions stay (8.6). */
    suspend fun resetLearned() {
        engine.resetLearned()
        shelfStore.reset()
        cached = null
    }

    private suspend fun compute(now: Long): State {
        // The model's own figures first: they come from the replay's graded plays, not from this screen's counting.
        val (summary, useLearned, updates) = engine.withState { st ->
            Triple(st.learner.summary, st.learner.useLearned, st.learner.model.updates)
        }
        val model = ModelVoice.of(summary, useLearned, updates)

        // The log, in order: a counted play is a real listen (not a skip, not excluded, not silent).
        val rids = ArrayList<Long>()
        val aids = ArrayList<Long>()
        val starts = ArrayList<Long>()
        val tzs = ArrayList<Int>()
        var controlPlays = 0
        var controlFinished = 0
        var learnedPlays = 0
        var learnedFinished = 0
        // "Lately" (8.6 point 8): the affinity tables as they are now and as they stood two weeks ago, both made from the log in order.
        val lateCutoff = now - YourSound.LATELY_DAYS * com.theveloper.pixelplay.brain.BrainMath.DAY_MS
        val nowTables = Affinity.State()
        val earlierTables = Affinity.State()
        val tracker = GradeTracker(nowTables.completions)
        val tagRids = ArrayList<Long>()
        val tagTimes = ArrayList<Long>()
        val tagR = ArrayList<Double>()
        val tagC = ArrayList<Double>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = store.eventsAfter(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().eventId
            for (e in page) {
                val t = if (e.startedAtMs > now) now else e.startedAtMs
                val graded = tracker.grade(e)
                if (!e.has(EventFlags.LEARN_EXCLUDED) && !e.has(EventFlags.NEVER_AUDIBLE)) {
                    Affinity.applyEvent(nowTables, e, graded, t)
                    if (t <= lateCutoff) Affinity.applyEvent(earlierTables, e, graded, t)
                    tagRids.add(e.rid); tagTimes.add(t); tagR.add(graded.r); tagC.add(graded.cOther)
                }
                if (e.has(EventFlags.LEARN_EXCLUDED) || e.has(EventFlags.NEVER_AUDIBLE)) continue
                val listened = e.endReason == EndReason.TRACK_DONE || e.playedMs < 0 || e.playedMs >= LISTEN_MS
                if (listened) {
                    rids.add(e.rid)
                    aids.add(e.primaryAid)
                    starts.add(e.startedAtMs)
                    tzs.add(if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else e.tzOffsetMin)
                }
                if (Reward.isEngineSurface(e.surface) && e.origin == com.theveloper.pixelplay.brain.Origin.LIVE) {
                    val g = Reward.grade(e, Reward.Context())
                    if (g.c > 0.0) {
                        val finished = g.r >= Reward.COMPLETION_R
                        if (e.has(EventFlags.CONTROL_SESSION)) {
                            controlPlays++
                            if (finished) controlFinished++
                        } else if (e.modelVersion == BrainEventRecorder.MODEL_LEARNED) {
                            learnedPlays++
                            if (finished) learnedFinished++
                        }
                    }
                }
            }
            if (page.size < PAGE) break
        }
        val plays = YourSound.withFirsts(
            rids.toLongArray(), aids.toLongArray(), starts.toLongArray(), tzs.toIntArray(),
        )
        val set = islandStore.current()
        val memberOf = set?.memberOf ?: emptyMap()
        val islandOf = { rid: Long -> memberOf[rid] ?: -1 }
        val atlas = atlasProvider.atlas()

        // Island names: the same words the Daily Mixes carry (an island is named only when enough of it has a genre).
        val names = HashMap<Int, String?>()
        suspend fun nameOf(id: Int): String? = names.getOrPut(id) {
            val island = set?.islands?.firstOrNull { it.id == id } ?: return@getOrPut null
            try {
                mixes.get().islandSubtitle(island, now).takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: naming an island for Your sound failed")
                null
            }
        }

        // The track facts of every recording played: tags (genres), years, titles. Unknown stays unknown.
        val topTag = HashMap<Long, Int>()
        val tagVectors = HashMap<Long, Tags.Vector>()
        val year = HashMap<Long, Int>()
        val title = HashMap<Long, String>()
        val distinct = (rids + tagRids).toHashSet().toLongArray().also { it.sort() }
        for (chunk in distinct.toList().chunked(DNA_CHUNK)) {
            for (row in trackDna.getAll(chunk)) {
                val v = Tags.decode(row.tags)
                if (!v.isEmpty) {
                    topTag[row.rid] = v.top
                    tagVectors[row.rid] = v
                }
                if (row.firstYear > 0) year[row.rid] = row.firstYear
                if (row.title.isNotBlank()) title[row.rid] = if (row.artistDisplay.isBlank()) row.title else "${row.title} · ${row.artistDisplay}"
            }
        }

        val islandShares = YourSound.islands(plays, islandOf, now)
        // Artist names, for the islands' top artists and for "Lately": the names the brain knows (an unknown one is left out).
        val wantedAids = LinkedHashSet<Long>()
        for (s in islandShares) for (a in s.topAids) wantedAids.add(a)
        val artistNames = HashMap<Long, String>()
        suspend fun artistNamesOf(aidsToRead: Collection<Long>) {
            for (chunk in aidsToRead.toList().chunked(DNA_CHUNK)) {
                for (row in artistDna.getAll(chunk)) if (row.name.isNotBlank() && AtlasNames.isShown(row.name)) artistNames[row.aid] = row.name
            }
        }
        artistNamesOf(wantedAids)

        val islandRows = islandShares.map { s ->
            val island = set?.islands?.firstOrNull { it.id == s.islandId }
            val genreId = island?.topTags?.firstOrNull { atlas != null && AtlasNames.genreName(atlas, it) != null } ?: -1
            IslandRow(
                id = s.islandId,
                name = nameOf(s.islandId),
                permille = s.permille,
                topArtists = s.topAids.toList().mapNotNull { artistNames[it] },
                topSongs = s.topRids.toList().mapNotNull { title[it] },
                genreId = genreId,
                genreName = if (genreId >= 0 && atlas != null) AtlasNames.genreName(atlas, genreId) else null,
            )
        }
        val dayparts = YourSound.dayparts(plays, islandOf).map { d ->
            DaypartRow(d.daypart, d.plays, if (d.leadingIslandId >= 0) nameOf(d.leadingIslandId) else null)
        }
        val months = YourSound.months(plays, islandOf).map { m ->
            MonthRow(m.month, m.plays, m.islandPermille.take(3).map { nameOf(it.first) to it.second })
        }

        val genreRows = if (atlas == null) emptyList() else YourSound.genres(plays) { topTag[it] ?: GenreAtlas.NONE }.mapNotNull { (id, share) ->
            AtlasNames.genreName(atlas, id)?.let { GenreRow(id, it, share) }
        }
        // The atlas tree: families (roots) with the genres played inside them. A family or genre the app does not name is absent.
        val rootCache = HashMap<Int, Int>()
        fun rootOf(id: Int): Int = rootCache.getOrPut(id) {
            val a = atlas ?: return@getOrPut id
            var best = id
            for (anc in a.ancestors(id)) if ((a.genre(anc)?.depth ?: 1) == 0) { best = anc; break }
            best
        }
        val genreTree = if (atlas == null) emptyList() else YourSound.genreTree(plays, 8, { topTag[it] ?: GenreAtlas.NONE }, ::rootOf).mapNotNull { fam ->
            val name = AtlasNames.genreName(atlas, fam.rootId) ?: return@mapNotNull null
            GenreFamilyRow(fam.rootId, name, fam.permille, fam.children.mapNotNull { (id, share) -> AtlasNames.genreName(atlas, id)?.let { GenreRow(id, it, share) } })
        }
        val decades = YourSound.decades(plays) { year[it] ?: 0 }
        val novelty = YourSound.novelty(plays, now).takeIf { it.plays > 0 }

        // "Lately": add the tags to the two sets of tables, then read the largest moves.
        if (atlas != null) {
            for (i in tagRids.indices) {
                val v = tagVectors[tagRids[i]] ?: continue
                val t = tagTimes[i]
                for (tg in v.tags) {
                    Affinity.apply(nowTables, Family.TAG, tg.atlasId.toLong(), tagR[i], tagC[i], tg.q.toDouble() / 255.0, t)
                    if (t <= lateCutoff) Affinity.apply(earlierTables, Family.TAG, tg.atlasId.toLong(), tagR[i], tagC[i], tg.q.toDouble() / 255.0, t)
                }
            }
        }
        val moves = YourSound.lately(earlierTables, nowTables, lateCutoff, now)
        artistNamesOf(moves.filter { it.family == Family.ARTIST }.map { it.key })
        val up = ArrayList<String>()
        val down = ArrayList<String>()
        for (m in moves) {
            val name = if (m.family == Family.ARTIST) artistNames[m.key] else atlas?.let { AtlasNames.genreName(it, m.key.toInt()) }
            if (name == null) continue
            if (m.delta > 0.0) up.add(name) else down.add(name)
        }
        val lately = Lately(up, down)

        val control = if (controlPlays + learnedPlays == 0) null else Control(
            controlPlays,
            Gate.shownRate(controlFinished, controlPlays)?.let { (it * 1000.0 + 0.5).toInt() },
            learnedPlays,
            // The learned rate is shown only next to a control rate that has enough plays.
            if (controlPlays >= Gate.CONTROL_MIN_PLAYS && learnedPlays > 0) ((learnedFinished.toDouble() / learnedPlays) * 1000.0 + 0.5).toInt() else null,
        )
        return State(plays.size, islandRows, genreRows, genreTree, lately, decades, dayparts, months, novelty, model, control, now)
    }

    private companion object {
        const val CACHE_MS = 5L * 60_000L
        const val PAGE = 2_000
        const val DNA_CHUNK = 800
        const val LISTEN_MS = 30_000
    }
}
