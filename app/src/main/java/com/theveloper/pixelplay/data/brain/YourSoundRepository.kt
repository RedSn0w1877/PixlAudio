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
    private val identity: TrackResolver,
    private val switch: BrainSwitch,
    private val historyDao: dagger.Lazy<com.theveloper.pixelplay.data.history.ListeningHistoryDao>,
    private val feedback: dagger.Lazy<com.theveloper.pixelplay.data.brain.store.FeedbackDao>,
    private val homeRows: dagger.Lazy<BrainHomeRows>,
    private val homePlans: dagger.Lazy<HomePlanStore>,
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
        /** Plays that moved the weights since they were last at the prior; "Reset what I've learned" is offered only above 0. */
        val modelUpdates: Long,
        val computedAtMs: Long,
    ) {
        val isEmpty: Boolean get() = plays == 0
    }

    @Volatile private var cached: State? = null

    /** Bumped by a reset: a figure that was being worked out before it is not kept (it would still show the old weights). */
    @Volatile private var generation = 0

    /** The figures, from the cache when they are under [CACHE_MS] old. */
    suspend fun load(force: Boolean = false): State = withContext(Dispatchers.IO) {
        val now = clock.nowMs()
        cached?.let { if (!force && now - it.computedAtMs in 0 until CACHE_MS) return@withContext it }
        val before = generation
        val state = compute(now)
        if (before == generation) cached = state
        state
    }

    /** "Reset what I've learned": the weights go back to the prior, the log, the taste changes and the exclusions stay (8.6). */
    suspend fun resetLearned() {
        generation++
        engine.resetLearned()
        shelfStore.reset()
        // Lists that the learned weights ranked go with them: the saved Home rows and the Home plan are built again with the standard recipe
        // (a saved mix notices by itself, see BrainMixes.isStale).
        runCatching { homeRows.get().dropLearned() }
        runCatching { homePlans.get().clear() }
        generation++
        cached = null
    }

    private suspend fun compute(now: Long): State {
        // The model's own figures first: they come from the replay's graded plays, not from this screen's counting.
        val (summary, useLearned, updates) = engine.withState { st ->
            Triple(st.learner.summary, st.learner.useLearned, st.learner.model.updates)
        }
        val model = ModelVoice.of(summary, useLearned, updates)

        // The log and the imported history as one stream, in order, with merged recordings mapped to their survivor (as a rebuild reads them): a
        // counted play is a real listen (not a skip, not excluded, not silent).
        val rids = ArrayList<Long>()
        val aids = ArrayList<Long>()
        val starts = ArrayList<Long>()
        val tzs = ArrayList<Int>()
        // The finish rate of the picks, kept by time so the two groups can be compared over the same weeks (5.12 point 8).
        val controlPlays = ArrayList<Long>()
        val controlFinished = ArrayList<Boolean>()
        val learnedPlays = ArrayList<Long>()
        val learnedFinished = ArrayList<Boolean>()
        // "Lately" (8.6 point 8): the affinity tables as they are now and as they stood two weeks ago, both made from the log in order.
        val lateCutoff = now - YourSound.LATELY_DAYS * com.theveloper.pixelplay.brain.BrainMath.DAY_MS
        val nowTables = Affinity.State()
        val earlierTables = Affinity.State()
        var beforeCutoffPlays = 0
        val tracker = GradeTracker(nowTables.completions)
        val tagRids = ArrayList<Long>()
        val tagTimes = ArrayList<Long>()
        val tagR = ArrayList<Double>()
        val tagC = ArrayList<Double>()
        val merges = runCatching { identity.merges() }.getOrDefault(emptyMap())
        val stream = ReplayMerger(store).merged(importedPlays(), now, excludedSongs(merges)) { merges[it] ?: it }.events
        while (stream.hasNext()) {
            val e = stream.next()
            val t = if (e.startedAtMs > now) now else e.startedAtMs
            val graded = tracker.grade(e)
            if (e.has(EventFlags.LEARN_EXCLUDED) || e.has(EventFlags.NEVER_AUDIBLE)) continue
            Affinity.applyEvent(nowTables, e, graded, t)
            if (t <= lateCutoff) {
                Affinity.applyEvent(earlierTables, e, graded, t)
                beforeCutoffPlays++
            }
            tagRids.add(e.rid); tagTimes.add(t); tagR.add(graded.r); tagC.add(graded.cOther)
            val listened = e.endReason == EndReason.TRACK_DONE || e.playedMs < 0 || e.playedMs >= LISTEN_MS
            if (listened) {
                rids.add(e.rid)
                aids.add(e.primaryAid)
                starts.add(e.startedAtMs)
                // An imported play has no timezone: it is read in the device's, as the spec says for dayparts and months (5.10).
                tzs.add(if (e.tzOffsetMin == PlayEvent.TZ_UNKNOWN) deviceOffsetMin(e.startedAtMs) else e.tzOffsetMin)
            }
            if (Reward.isEngineSurface(e.surface) && e.origin == com.theveloper.pixelplay.brain.Origin.LIVE) {
                val g = Reward.grade(e, Reward.Context())
                if (g.c > 0.0) {
                    val finished = g.r >= Reward.COMPLETION_R
                    if (e.has(EventFlags.CONTROL_SESSION)) {
                        controlPlays.add(e.startedAtMs); controlFinished.add(finished)
                    } else if (e.modelVersion == BrainEventRecorder.MODEL_LEARNED) {
                        learnedPlays.add(e.startedAtMs); learnedFinished.add(finished)
                    }
                }
            }
        }
        val plays = YourSound.withFirsts(
            rids.toLongArray(), aids.toLongArray(), starts.toLongArray(), tzs.toIntArray(),
        )
        val set = islandStore.current()
        val memberOf = set?.memberOf ?: emptyMap()
        val islandOf = { rid: Long -> memberOf[rid] ?: -1 }
        val atlas = atlasProvider.atlas()

        // Island names: the same words the Daily Mixes carry (an island is named only when enough of it has a genre).
        // Each island is named once (a null answer is remembered too: naming walks the library, and an island with too few tagged tracks stays unnamed).
        val names = HashMap<Int, String?>()
        suspend fun nameOf(id: Int): String? {
            if (names.containsKey(id)) return names[id]
            val island = set?.islands?.firstOrNull { it.id == id }
            val name = if (island == null) null else try {
                mixes.get().islandSubtitle(island, now).takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: naming an island for Your sound failed")
                null
            }
            names[id] = name
            return name
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

        val islandShares = YourSound.islands(plays, islandOf, now, minPlays = YourSound.ISLANDS_MIN_PLAYS, minCoveragePermille = YourSound.ISLANDS_MIN_COVERAGE_PERMILLE)
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
        val dayparts = YourSound.dayparts(plays, YourSound.DAYPART_MIN_PLAYS, islandOf).map { d ->
            DaypartRow(d.daypart, d.plays, if (d.leadingIslandId >= 0) nameOf(d.leadingIslandId) else null)
        }
        val months = YourSound.months(plays, YourSound.MONTH_MIN_PLAYS, islandOf).map { m ->
            MonthRow(m.month, m.plays, m.islandPermille.take(3).map { nameOf(it.first) to it.second })
        }

        val genreRows = if (atlas == null) emptyList() else YourSound.genres(plays, 8, YourSound.GENRES_MIN_PLAYS, YourSound.GENRES_MIN_COVERAGE_PERMILLE) { topTag[it] ?: GenreAtlas.NONE }.mapNotNull { (id, share) ->
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
        val genreTree = if (atlas == null) emptyList() else YourSound.genreTree(plays, 8, { topTag[it] ?: GenreAtlas.NONE }, ::rootOf, YourSound.GENRES_MIN_PLAYS, YourSound.GENRES_MIN_COVERAGE_PERMILLE).mapNotNull { fam ->
            val name = AtlasNames.genreName(atlas, fam.rootId) ?: return@mapNotNull null
            GenreFamilyRow(fam.rootId, name, fam.permille, fam.children.mapNotNull { (id, share) -> AtlasNames.genreName(atlas, id)?.let { GenreRow(id, it, share) } })
        }
        val decades = YourSound.decades(plays, YourSound.ERAS_MIN_PLAYS) { year[it] ?: 0 }
        val novelty = YourSound.novelty(plays, now).takeIf { it.plays >= YourSound.NOVELTY_MIN_PLAYS }

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
        // "Up" needs something to be up from: with fewer than 50 plays from before the cutoff there is no baseline, and nothing is said.
        val moves = if (beforeCutoffPlays < YourSound.LATELY_MIN_BASELINE_PLAYS) emptyList()
        else YourSound.lately(earlierTables, nowTables, lateCutoff, now)
        artistNamesOf(moves.filter { it.family == Family.ARTIST }.map { it.key })
        val up = ArrayList<String>()
        val down = ArrayList<String>()
        for (m in moves) {
            val name = if (m.family == Family.ARTIST) artistNames[m.key] else atlas?.let { AtlasNames.genreName(it, m.key.toInt()) }
            if (name == null) continue
            if (m.delta > 0.0) up.add(name) else down.add(name)
        }
        val lately = Lately(up, down)

        // The two rates are compared over the same weeks: from the first play ranked with the weights in use (those exist only while the gate
        // has them on), and only when BOTH sides have at least 300 plays in that span. Otherwise no rate is shown.
        var control: Control? = null
        if (controlPlays.isNotEmpty() || learnedPlays.isNotEmpty()) {
            val since = learnedPlays.minOrNull() ?: Long.MAX_VALUE
            var cN = 0
            var cF = 0
            for (i in controlPlays.indices) if (controlPlays[i] >= since) { cN++; if (controlFinished[i]) cF++ }
            val lN = learnedPlays.size
            val lF = learnedFinished.count { it }
            val enough = cN >= Gate.CONTROL_MIN_PLAYS && lN >= Gate.CONTROL_MIN_PLAYS
            control = Control(
                cN,
                if (enough) ((cF.toDouble() / cN) * 1000.0 + 0.5).toInt() else null,
                lN,
                if (enough) ((lF.toDouble() / lN) * 1000.0 + 0.5).toInt() else null,
            )
        }
        return State(plays.size, islandRows, genreRows, genreTree, lately, decades, dayparts, months, novelty, model, control, updates, now)
    }

    /** Imported history (Spotify and YouTube Music exports) as events; the Spotify ones are left out when "Use my Spotify music" is off. */
    private suspend fun importedPlays(): List<PlayEvent> {
        val shapes = runCatching { switch.spotifyShapesPicks() }.getOrDefault(true)
        val adapter = ImportedPlayAdapter(ImportedPlaySource.OfDao(historyDao.get()), identity)
        val all = runCatching { adapter.events { 0 } }.getOrDefault(emptyList())
        return if (shapes) all else all.filter { it.origin != com.theveloper.pixelplay.brain.Origin.IMPORT_SPOTIFY }
    }

    /** "Don't count this" on a song, as a rebuild reads it: surviving recording -> when it was said. */
    private suspend fun excludedSongs(merges: Map<Long, Long>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (row in runCatching { feedback.get().active() }.getOrDefault(emptyList())) {
            if (row.kind != FeedbackKinds.DONT_COUNT || row.targetKind != FeedbackKinds.TARGET_SONG) continue
            val survivor = merges[row.targetId] ?: row.targetId
            out[survivor] = maxOf(out[survivor] ?: 0L, row.createdAtMs)
        }
        return out
    }

    private fun deviceOffsetMin(atMs: Long): Int = java.util.TimeZone.getDefault().getOffset(atMs) / 60_000

    private companion object {
        const val CACHE_MS = 5L * 60_000L
        const val DNA_CHUNK = 800
        const val LISTEN_MS = 30_000
    }
}
