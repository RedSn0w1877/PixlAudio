package com.theveloper.pixelplay.data.service.player

import android.os.Bundle
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.ListBuilder
import com.theveloper.pixelplay.brain.Retrieval
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.brain.StationPlanner
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.AnchorInfo
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainEventRecorder
import com.theveloper.pixelplay.data.brain.BrainExtras
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainRepository
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.LibraryIndex
import com.theveloper.pixelplay.data.brain.LibraryTrackCatalog
import com.theveloper.pixelplay.data.brain.Novelty
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.RadioSeed
import com.theveloper.pixelplay.data.brain.Reaction
import com.theveloper.pixelplay.data.brain.Station
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.brain.TrackRequest
import com.theveloper.pixelplay.data.brain.TrackResolver
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import com.theveloper.pixelplay.utils.MediaItemBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the queue shows on the Autoplay line. */
data class AutoplayUiState(
    /** The listener's switch. */
    val enabled: Boolean = true,
    /** The queue ended and picks are being looked for (the backstop window). */
    val finding: Boolean = false,
    /** The picks come from the library alone (offline, or the YouTube source is off or had nothing). */
    val fromLibrary: Boolean = false,
    /** Three skips in a row: the engine turned to something else (shown for a short while). */
    val changedDirection: Boolean = false,
    /** The queue is a station (8.3): what it is named after, a song's title or an artist's name. */
    val stationTitle: String? = null,
    val stationIsArtist: Boolean = false,
    /** The Familiar / Blend / Discover switch: `Novelty.FAMILIAR`, `BLEND` or `DISCOVER`. */
    val novelty: Int = Novelty.BLEND,
)

/**
 * Autoplay: when two or fewer tracks are left, five more are appended, so music does not stop at the
 * end of a queue (docs/brain-spec.md, 7.1), and what the listener does with them changes what comes
 * next (7.3).
 *
 * Runs inside `MusicService`'s process and is driven by it: [onTransition] after every track change,
 * [onQueueEnded] as the backstop, [onTransitionFinished] to release an edit held during a crossfade.
 * The event recorder tells it what each finished play did ([Reaction]). All state here is confined to
 * the main thread; pool building and planning run on background dispatchers and come back finished.
 *
 * Rules this class keeps:
 *  - The trigger asks the in-memory pool, never the network. A radio request starts no earlier than
 *    1.5 s into a track, at most two at a time, under the YouTube budget.
 *  - The count is the FULL queue (`engine.getFullQueue()`), not the player's window.
 *  - One queue edit per top-up and one per re-score, never during a `DualPlayerEngine` transition.
 *  - Only the listener's own choices (and engine picks they liked) are anchors. Engine picks that
 *    merely played are not, so an hour of autoplay is still about what the listener started with.
 *  - Tracks the listener queued are never moved or removed.
 *  - A re-score never touches the track that is starting or a track the prefetcher has warmed
 *    (decision D8): it replaces the engine's tail behind them, in one edit, only when it changed.
 *  - Off under repeat, during Cast and Spotify Connect, and while a saved queue is being restored.
 *  - A station (8.3) is the same machinery with one fixed anchor, its seed: the first batch is 20, the
 *    planner runs in station mode, and it keeps going with the Autoplay switch off, because the
 *    listener asked for it. It ends when its seed track leaves the queue.
 *
 * Which tracks go in, and in what order, is `StationPlanner` in `:brain`: pure, golden-tested, and
 * with an empty state the radio's own order. The library-only pool (offline, YouTube source off) has
 * no recording ids and keeps the plain order of [AutoplayPolicy.pickBatch].
 */
@Singleton
class AutoplayController @Inject constructor(
    private val engine: DualPlayerEngine,
    private val brainRepository: BrainRepository,
    private val playableStore: PlayableStore,
    private val musicRepository: MusicRepository,
    private val spotifyDao: SpotifyDao,
    private val engagementDao: EngagementDao,
    private val dailyMixManager: DailyMixManager,
    private val blockedArtists: BlockedArtistsRepository,
    private val tasteAdjustments: TasteAdjustmentRepository,
    private val listeningStatsTracker: ListeningStatsTracker,
    private val playEventDao: PlayEventDao,
    private val brainSwitch: BrainSwitch,
    private val networkConditions: StreamNetworkConditions,
    private val clock: BrainClock,
    private val brain: BrainEngine,
    private val recorder: BrainEventRecorder,
    private val identity: TrackResolver,
    private val stations: StationStore,
) {
    /** What the service tells the controller about itself. */
    interface Host {
        /** A main-thread scope that dies with the service. */
        val scope: CoroutineScope

        /** Cast or Spotify Connect is playing. */
        fun isRemoteSession(): Boolean

        fun isRestoringQueue(): Boolean
    }

    private class PoolEntry(
        val candidate: AutoplayPolicy.Candidate,
        val song: Song,
        /** Set for a track from outside the library: it must be in `playable` before it can play. */
        val engineTrack: EngineTrack?,
        /** The artist the pick is explained by when the planner names none: the anchor's primary artist. */
        val whyArtist: String,
        /** ONE artist name: the track's own primary artist. */
        val primaryArtist: String,
        /** The same track as the pure core sees it; null in the library-only pool. */
        val brain: Candidate?,
    )

    /** A track the pool is seeded from: an item of the queue, or an anchor of the session. */
    private class Anchor(val mediaId: String, val title: String, val item: MediaItem?, val info: AnchorInfo?)

    private class BuiltPool(
        val entries: List<PoolEntry>,
        val fromLibrary: Boolean,
        /** The radios the entries came from, newest anchor first; empty for the library-only pool. */
        val seeds: List<Retrieval.SeedList> = emptyList(),
        /** Artist names by aid, for the reason line. */
        val artistNames: Map<Long, String> = emptyMap(),
    )

    /** What one plan needs, gathered on the main thread. */
    private class PlanInputs(
        val candidates: List<Candidate>,
        val entriesByRid: Map<Long, PoolEntry>,
        val prior: List<ListBuilder.Prior>,
        val excludedRids: Set<Long>,
        val seeds: List<Retrieval.SeedList>,
        val nuMultiplier: Double,
        val nowMs: Long,
        val tzOffsetMin: Int,
        val batch: Int,
        /** The station the plan is for; null for plain autoplay. */
        val station: Station?,
        /** The station has not been given a batch yet. */
        val firstStationBatch: Boolean,
    )

    private val _uiState = MutableStateFlow(AutoplayUiState())
    val uiState: StateFlow<AutoplayUiState> = _uiState.asStateFlow()

    private var host: Host? = null
    private var settingsJob: Job? = null
    private var enabled = true

    /** The station the current queue is, as last read from the queue ([refreshStation]); null for none. */
    private var station: Station? = null

    /** What the Familiar / Blend / Discover switch multiplies the novelty share by. */
    private var noveltyFactor = 1.0
    private var tasteJob: Job? = null
    private var pendingTasteFilter = false
    private var tasteDirty = false

    /** Batches appended for the current [station]: its first one is the long one (6.2). */
    private var stationBatches = 0

    /** Autoplay is switched on, or the queue is a station (which the listener asked for by hand). */
    private val active: Boolean get() = enabled || station != null

    @Volatile
    private var youTubeSourceEnabled = true

    private var pool: MutableList<PoolEntry> = ArrayList()
    private var poolKey = ""
    private var poolFromLibrary = false
    private var poolDeepened = false
    private var poolSeeds: List<Retrieval.SeedList> = emptyList()
    private var poolArtistNames: Map<Long, String> = emptyMap()
    private var fillJob: Job? = null
    private var fillKey = ""
    private var appendJob: Job? = null
    private var rescoreJob: Job? = null
    private var pendingAppend: List<MediaItem>? = null
    private var pendingRescore = false
    private var queueEndedAtMs = 0L
    private var batchSeq = 0
    private var emptyBuildKey = ""
    private var emptyBuildAtMs = 0L

    /** Engine items that went into the queue, by media id: a re-score needs to know what they are. */
    private val served = LinkedHashMap<String, PoolEntry>()

    // What the event recorder last said about the session.
    private var sessionAnchors: List<AnchorInfo> = emptyList()
    private var sessionAnchorsAtMs = 0L
    private var needsOtherDirection = false
    private var sessionTurned = false
    private val likedTitles = HashMap<Long, String>()
    private var noteJob: Job? = null

    // Touched only by pool builds, which run one at a time.
    private var libraryIndex: LibraryIndex? = null
    private var libraryIndexBuiltAtMs = 0L
    private var lastPruneAtMs = 0L

    // ── Service hooks (main thread) ──────────────────────────────────────────

    @MainThread
    fun attach(host: Host) {
        this.host = host
        settingsJob?.cancel()
        settingsJob = host.scope.launch {
            launch {
                brainSwitch.autoplayEnabledFlow.collect { on ->
                    enabled = on
                    _uiState.value = _uiState.value.copy(enabled = on)
                    if (active) evaluate(settle = true) else cancelWork()
                }
            }
            // A station was started or ended: the trigger looks at the queue again, and once more a
            // moment later, because a station started from the track that is playing first drops what
            // was queued behind it, and that edit announces no track change.
            launch {
                stations.current.collectLatest { started ->
                    evaluate(settle = true)
                    if (started != null) {
                        delay(STATION_START_RECHECK_MS)
                        evaluate(settle = true)
                    }
                }
            }
            launch {
                stations.novelty.collect { level ->
                    val factor = Novelty.factor(level)
                    val changed = factor != noveltyFactor
                    noveltyFactor = factor
                    _uiState.value = _uiState.value.copy(novelty = level)
                    // The switch applies from the next track: the engine's tail is planned again.
                    if (changed) rescoreTail()
                }
            }
            // "Not for me", "Less from ...", a mute or a block: what it excludes leaves the pool and
            // the engine's tail now (8.5). The first value is the state at start, not a change.
            launch { tasteAdjustments.version.drop(1).collect { onTasteChanged() } }
            launch { brainSwitch.youTubeSourceEnabledFlow.collect { youTubeSourceEnabled = it } }
            // What each finished play did to the session (7.3). The flow is silent until music plays.
            launch { recorder.reactions.collect { onReaction(it) } }
        }
    }

    @MainThread
    fun detach() {
        settingsJob?.cancel()
        settingsJob = null
        tasteJob?.cancel()
        tasteJob = null
        cancelWork()
        host = null
    }

    /** A new track became current. Called right after the blocked-artist guard. */
    @MainThread
    fun onTransition() {
        queueEndedAtMs = 0L
        setFinding(false)
        evaluate(settle = true)
    }

    /** Playback started or resumed: a pool that could not settle while paused gets its chance. */
    @MainThread
    fun onPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && pool.isEmpty() && fillJob?.isActive != true) evaluate(settle = true)
    }

    /** The crossfade hand-over is over: a queue edit held back during it goes in now. */
    @MainThread
    fun onTransitionFinished() {
        val items = pendingAppend
        pendingAppend = null
        if (host == null || !active) {
            pendingRescore = false
            pendingTasteFilter = false
            return
        }
        if (items != null) engine.appendToQueueEnd(items)
        if (pendingTasteFilter) {
            pendingTasteFilter = false
            onTasteChanged()
        }
        if (pendingRescore) {
            pendingRescore = false
            rescoreTail()
        }
    }

    /**
     * The backstop: the player reached the end of the queue. If picks are ready they go in and playback
     * goes on; otherwise they are looked for at once (nothing is playing, so nothing competes) and
     * resume playback only if they land inside the window and the listener did not press pause.
     */
    @MainThread
    fun onQueueEnded() {
        val host = host ?: return
        val player = engine.masterPlayer
        val queue = engine.getFullQueue()
        val index = engine.getCurrentAbsoluteIndex()
        refreshStation(queue)
        val decision = AutoplayPolicy.decide(
            active, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(), queue.size, index
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP || player.hasNextMediaItem()) return
        val anchors = anchorsOf(queue, index)
        if (anchors.isEmpty()) return
        adoptPoolFor(anchors)
        val endedAt = clock.nowMs()
        queueEndedAtMs = endedAt
        appendFromPool(queue) { appended ->
            // A batch that went in has already resumed playback (see commit).
            if (appended && queueEndedAtMs == 0L) return@appendFromPool
            setFinding(true)
            startFill(anchors, settle = false, deepen = false)
            host.scope.launch {
                delay(AutoplayPolicy.BACKSTOP_WINDOW_MS + 250L)
                if (queueEndedAtMs == endedAt) {
                    // Nothing landed in time: the queue simply ended, as it always did.
                    queueEndedAtMs = 0L
                    setFinding(false)
                }
            }
        }
    }

    // ── Reactions (7.3) ──────────────────────────────────────────────────────

    /** A play ended and the session changed. Decides whether the engine's tail must be re-scored. */
    @MainThread
    private fun onReaction(reaction: Reaction) {
        sessionAnchors = reaction.anchors
        sessionAnchorsAtMs = clock.nowMs()
        needsOtherDirection = reaction.needsOtherDirection
        sessionTurned = reaction.turned
        if (reaction.liked) {
            if (likedTitles.size > 32) likedTitles.clear()
            likedTitles[reaction.event.rid] = reaction.title
        }
        val host = host
        if (host != null) refreshStation(engine.getFullQueue())
        // A station stays on its seed: three skips there thin out what was skipped (the session's
        // blocks), they do not turn the station into another one.
        if (reaction.changedDirection && station == null) showDirectionNote()
        if (host == null || !active) return
        if (reaction.changedDirection && station == null && !host.isRemoteSession() && !host.isRestoringQueue()) {
            // Turning is not waiting for the queue to run low: the pool of the new direction is built
            // now, and when it is ready the engine's tail is re-scored against it ([onPoolReady]).
            val anchors = anchorsOf(engine.getFullQueue(), engine.getCurrentAbsoluteIndex())
            if (anchors.isNotEmpty()) {
                adoptPoolFor(anchors)
                if (pool.isEmpty()) startFill(anchors, settle = true, deepen = false)
            }
        }
        // A skip, a like or a change of direction changes what should come next; a plain completion
        // does not move the tail.
        if (reaction.graded.h > 0.0 || reaction.liked || reaction.changedDirection) rescoreTail()
        // New anchors mean another pool; the trigger decides whether anything is needed now.
        evaluate(settle = true)
    }

    private fun showDirectionNote() {
        val host = host ?: return
        _uiState.value = _uiState.value.copy(changedDirection = true)
        noteJob?.cancel()
        noteJob = host.scope.launch {
            delay(DIRECTION_NOTE_MS)
            _uiState.value = _uiState.value.copy(changedDirection = false)
        }
    }

    /**
     * Re-scores the engine-owned end of the queue against the pool, as ONE queue edit, and only when
     * the result differs. The track after the current one is never touched, nor the one after that on
     * unmetered Wi-Fi: the prefetcher has warmed them (2.3 step 7, decision D8).
     */
    @MainThread
    private fun rescoreTail() {
        val host = host ?: return
        if (!active || poolFromLibrary || poolSeeds.isEmpty() || host.isRemoteSession() || host.isRestoringQueue()) return
        if (rescoreJob?.isActive == true || appendJob?.isActive == true) {
            pendingRescore = true
            return
        }
        if (AutoplayPolicy.mustDeferQueueEdit(engine.isTransitionRunning())) {
            pendingRescore = true
            return
        }
        val queue = engine.getFullQueue()
        val current = engine.getCurrentAbsoluteIndex()
        val conditions = networkConditions.current()
        val start = AutoplayPolicy.rescoreStart(
            queueSize = queue.size,
            currentAbsoluteIndex = current,
            warmedAhead = AutoplayPolicy.warmedAhead(
                unmeteredWifi = conditions.network == StreamPrefetchPolicy.Network.WIFI && !conditions.metered
            ),
            engineOwned = { BrainExtras.isEngineOwned(queue[it].mediaMetadata.extras) },
        )
        if (start < 0) return
        val tailEntries = ArrayList<PoolEntry>(queue.size - start)
        for (i in start until queue.size) {
            // An engine item this process did not put there (a restored queue): leave the tail alone.
            tailEntries.add(served[queue[i].mediaId] ?: return)
        }
        if (tailEntries.any { it.brain == null }) return
        val tailRids = LongArray(tailEntries.size) { tailEntries[it].brain!!.rid }
        val tailIds = queue.subList(start, queue.size).map { it.mediaId }
        val inputs = planInputs(queue, start, tailEntries)
        val key = poolKey
        rescoreJob = host.scope.launch {
            val plan = try {
                planOnBrain(inputs, count = tailRids.size, bridge = false, tail = tailRids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "autoplay: re-scoring the tail failed")
                null
            } ?: return@launch
            if (key != poolKey || this@AutoplayController.host == null || !active) return@launch
            // The queue may have moved on while the plan was made: only an unchanged tail is replaced,
            // and never during a hand-over.
            val now = engine.getFullQueue()
            val protectedUntil = engine.getCurrentAbsoluteIndex() + 1
            if (now.size != start + tailIds.size || start <= protectedUntil ||
                AutoplayPolicy.mustDeferQueueEdit(engine.isTransitionRunning())
            ) return@launch
            for (i in tailIds.indices) if (now[start + i].mediaId != tailIds[i]) return@launch

            batchSeq = (batchSeq + 1) and 0xff
            val recId = (inputs.nowMs shl 8) or batchSeq.toLong()
            val items = ArrayList<MediaItem>(plan.picks.size)
            val kept = HashSet<String>()
            for (pick in plan.picks) {
                val entry = inputs.entriesByRid[pick.rid] ?: continue
                items.add(mediaItemFor(entry, recId, pick.slot, inputs.station, pick))
                kept.add(entry.candidate.mediaId)
            }
            if (!engine.replaceQueueTail(start, items)) return@launch
            // What left the tail goes back to the pool; what came from the pool is now served.
            for (entry in tailEntries) {
                if (entry.candidate.mediaId !in kept) {
                    served.remove(entry.candidate.mediaId)
                    pool.add(entry)
                }
            }
            pool.removeAll { it.candidate.mediaId in kept }
            for (pick in plan.picks) inputs.entriesByRid[pick.rid]?.let { remember(it) }
        }.also { job ->
            job.invokeOnCompletion {
                // A second reaction arrived while this one was planned: look once more.
                if (rescoreJob === job) rescoreJob = null
            }
        }
    }

    // ── Trigger ──────────────────────────────────────────────────────────────

    private fun evaluate(settle: Boolean) {
        val host = host ?: return
        if (pendingAppend != null) {
            // Held for a transition that is over (or was cancelled without a callback): release it.
            if (!engine.isTransitionRunning()) onTransitionFinished()
            return
        }
        val player = engine.masterPlayer
        val queue = engine.getFullQueue()
        val index = engine.getCurrentAbsoluteIndex()
        refreshStation(queue)
        val decision = AutoplayPolicy.decide(
            active, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(), queue.size, index
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP) {
            if (decision != AutoplayPolicy.Decision.ENOUGH_QUEUED) cancelWork()
            return
        }
        val anchors = anchorsOf(queue, index)
        if (anchors.isEmpty()) return
        adoptPoolFor(anchors)
        appendFromPool(queue) { appended ->
            if (appended) {
                if (pool.size < AutoplayPolicy.POOL_REFILL_BELOW && !poolFromLibrary && !poolDeepened) {
                    startFill(anchors, settle = true, deepen = true)
                }
                return@appendFromPool
            }
            // Nothing fits from what is in memory: build it, or once per set of anchors rebuild it deeper
            // (continuation pages, then the library behind them). After that the queue is allowed to end.
            if (pool.isNotEmpty() && poolDeepened) return@appendFromPool
            startFill(anchors, settle, deepen = pool.isNotEmpty())
        }
    }

    /** A pool belongs to its anchors: other anchors, another pool. */
    private fun adoptPoolFor(anchors: List<Anchor>) {
        val station = station
        val key = anchors.joinToString("|") { it.mediaId } + when {
            station != null -> "|station:" + station.id
            needsOtherDirection -> "|turn"
            else -> ""
        }
        if (key == poolKey) return
        poolKey = key
        pool = ArrayList()
        poolFromLibrary = false
        poolDeepened = false
        poolSeeds = emptyList()
        poolArtistNames = emptyMap()
        if (fillKey != key) {
            fillJob?.cancel()
            fillJob = null
        }
    }

    /**
     * What the pool is seeded from (7.1 "Seed set"): the track the listener chose and is hearing now,
     * then the session's anchors as the recorder last gave them, three at most. While the session has
     * none yet, the last track the listener chose at or before the current one.
     */
    private fun anchorsOf(queue: List<MediaItem>, index: Int): List<Anchor> {
        // A station has one anchor for as long as it lasts: its seed (8.3).
        station?.let { current ->
            for (item in queue) {
                if (item.mediaId == current.seedMediaId) {
                    return listOf(Anchor(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item, null))
                }
            }
            return emptyList()
        }
        val fresh = sessionAnchors.isNotEmpty() && clock.nowMs() - sessionAnchorsAtMs in 0 until Session.GAP_MS
        if (fresh) {
            val anchors = ArrayList<Anchor>(Session.MAX_ANCHORS)
            // The track the listener chose and is hearing right now leads, though the session has not
            // heard it out yet: tap one song after an album, and autoplay follows the song. Not after
            // a change of direction, which follows the one anchor it turned to.
            val current = queue.getOrNull(index)
            if (!sessionTurned && current != null && !BrainExtras.isEngineOwned(current.mediaMetadata.extras) &&
                sessionAnchors.none { it.mediaId == current.mediaId }
            ) {
                anchors.add(Anchor(current.mediaId, current.mediaMetadata.title?.toString().orEmpty(), current, null))
            }
            for (info in sessionAnchors) {
                if (anchors.size >= Session.MAX_ANCHORS) break
                anchors.add(Anchor(info.mediaId, info.title, null, info))
            }
            return anchors
        }
        return AutoplayPolicy.anchorIndices(
            currentAbsoluteIndex = index,
            queueSize = queue.size,
            engineOwned = { BrainExtras.isEngineOwned(queue[it].mediaMetadata.extras) },
            mediaIdAt = { queue[it].mediaId },
            max = 1,
        ).map { i ->
            val item = queue[i]
            Anchor(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item, null)
        }
    }

    // ── Append ───────────────────────────────────────────────────────────────

    /**
     * Appends one batch from the in-memory pool and tells [onDone] whether one went in (or is held for
     * the end of a transition). No network. The plan itself is a few milliseconds of arithmetic on a
     * background thread; the library-only pool is picked in place.
     */
    private fun appendFromPool(queue: List<MediaItem>, onDone: (Boolean) -> Unit) {
        val host = host
        if (host == null || appendJob?.isActive == true) return
        if (pool.isEmpty()) {
            onDone(false)
            return
        }
        if (poolFromLibrary || poolSeeds.isEmpty()) {
            onDone(appendInPoolOrder(queue))
            return
        }
        val inputs = planInputs(queue, queue.size, emptyList())
        if (inputs.candidates.isEmpty()) {
            onDone(false)
            return
        }
        val key = poolKey
        val queueSize = queue.size
        appendJob = host.scope.launch {
            val plan = try {
                planOnBrain(
                    inputs,
                    // A station opens with a long first batch (6.2); its seed is what was just heard, so
                    // it needs no bridge.
                    count = if (inputs.firstStationBatch) STATION_FIRST_BATCH else AutoplayPolicy.BATCH_SIZE,
                    bridge = inputs.station == null && inputs.prior.isEmpty(),
                    tail = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "autoplay: planning a batch failed")
                null
            }
            appendJob = null
            if (key != poolKey || this@AutoplayController.host == null || !active) return@launch
            // Someone else changed the queue while the plan was made: the trigger looks again.
            if (engine.getFullQueue().size != queueSize) {
                evaluate(settle = true)
                return@launch
            }
            onDone(plan != null && commit(plan, inputs))
            runPendingRescore()
        }
    }

    /** A re-score that had to wait for a plan in flight runs once that plan is done. */
    private fun runPendingRescore() {
        if (!pendingRescore || engine.isTransitionRunning()) return
        pendingRescore = false
        rescoreTail()
    }

    /** Everything a plan reads, taken from the queue and the pool on the main thread. */
    private fun planInputs(queue: List<MediaItem>, tailStart: Int, tailEntries: List<PoolEntry>): PlanInputs {
        val now = clock.nowMs()
        val queuedIds = HashSet<String>(queue.size * 2 + 64)
        val prior = ArrayList<ListBuilder.Prior>()
        val excludedRids = HashSet<Long>()
        var listenerItems = 0
        for (i in queue.indices) {
            val item = queue[i]
            val extras = item.mediaMetadata.extras
            if (!BrainExtras.isEngineOwned(extras)) listenerItems++
            if (i >= tailStart) continue
            queuedIds.add(item.mediaId)
            val entry = served[item.mediaId]
            val candidate = entry?.brain
            if (candidate != null) {
                excludedRids.add(candidate.rid)
                if (BrainExtras.isEngineOwned(extras)) {
                    prior.add(ListBuilder.Prior(candidate.rid, candidate.aid, candidate.albumKey, !candidate.inLibrary))
                }
            }
        }
        // 6.4: nothing played in the last four hours. The affinity tables answer that for anything
        // the brain recorded; the playback history covers a play it could not identify.
        val since = now - FOUR_HOURS_MS
        for (entry in listeningStatsTracker.playbackHistory.value) {
            if (entry.timestamp >= since) queuedIds.add(entry.songId)
        }
        val candidates = ArrayList<Candidate>(pool.size + tailEntries.size)
        val entriesByRid = HashMap<Long, PoolEntry>((pool.size + tailEntries.size) * 2)
        for (entry in tailEntries) {
            val candidate = entry.brain ?: continue
            if (entriesByRid.putIfAbsent(candidate.rid, entry) == null) candidates.add(candidate)
        }
        for (entry in pool) {
            val candidate = entry.brain ?: continue
            if (entry.candidate.mediaId in queuedIds) {
                // A track the listener queued themselves is also in the pool: it may not be picked again.
                excludedRids.add(candidate.rid)
                continue
            }
            if (entriesByRid.putIfAbsent(candidate.rid, entry) == null) candidates.add(candidate)
        }
        batchSeq = (batchSeq + 1) and 0xff
        return PlanInputs(
            candidates = candidates,
            entriesByRid = entriesByRid,
            prior = if (prior.size > 50) prior.subList(prior.size - 50, prior.size) else prior,
            excludedRids = excludedRids,
            seeds = poolSeeds,
            // After a single track the listener asked for "more like this"; after an album or a
            // playlist autoplay stays closer to home; a station leans further out (6.2). The listener's
            // Familiar / Blend / Discover switch scales whichever applies.
            nuMultiplier = noveltyFactor * when {
                station != null -> STATION_NU
                listenerItems <= 1 -> 1.0
                else -> 0.8
            },
            nowMs = now,
            tzOffsetMin = java.util.TimeZone.getDefault().getOffset(now) / 60_000,
            batch = batchSeq,
            station = station,
            firstStationBatch = station != null && stationBatches == 0,
        )
    }

    /** Runs the pure planner on the brain's state. [tail] non-null asks for a patch of that tail. */
    private suspend fun planOnBrain(inputs: PlanInputs, count: Int, bridge: Boolean, tail: LongArray?): StationPlanner.Plan? =
        withContext(Dispatchers.Default) {
            brain.withState { state ->
                // What the session's skip rules need to know about a station: its seed artist is thinned
                // out after skips, never blocked (5.5).
                val seedList = inputs.seeds.firstOrNull()
                if (inputs.station != null && seedList != null) {
                    state.session.seed = seedList.seedRid
                    state.session.seedAid = seedList.seedAid
                    state.session.seedKind = inputs.station.kind
                } else {
                    state.session.seed = 0L
                    state.session.seedAid = 0L
                    state.session.seedKind = Session.SEED_NONE
                }
                val graph = StationPlanner.buildGraph(inputs.seeds, inputs.candidates, state.session)
                val request = StationPlanner.Request(
                    mode = if (inputs.station != null) StationPlanner.MODE_STATION else StationPlanner.MODE_AUTOPLAY,
                    count = count,
                    candidates = inputs.candidates,
                    seeds = inputs.seeds,
                    prior = inputs.prior,
                    nowMs = inputs.nowMs,
                    tzOffsetMin = inputs.tzOffsetMin,
                    nuMultiplier = inputs.nuMultiplier,
                    // 6.2: a station's ties break by its seed and the day; autoplay's by the session.
                    tieSeed = if (inputs.station != null && seedList != null) {
                        seedList.seedRid xor BrainMath.localDay(inputs.nowMs, inputs.tzOffsetMin) xor inputs.batch.toLong()
                    } else {
                        state.session.sessionId xor inputs.batch.toLong()
                    },
                    excludedRids = inputs.excludedRids,
                    bridge = bridge,
                )
                val plan = if (tail == null) {
                    StationPlanner.plan(request, state.affinity, state.session, graph)
                } else {
                    Session.patchQueue(state.session, tail, request, state.affinity, graph)
                }
                if (plan != null) StationPlanner.consume(state.session, plan)
                plan
            }
        }

    /** Puts a plan's picks at the end of the queue in one edit. True when anything went in. */
    private fun commit(plan: StationPlanner.Plan, inputs: PlanInputs): Boolean {
        if (plan.picks.isEmpty()) return false
        val recId = (inputs.nowMs shl 8) or inputs.batch.toLong()
        val items = ArrayList<MediaItem>(plan.picks.size)
        val pickedIds = HashSet<String>()
        for (pick in plan.picks) {
            val entry = inputs.entriesByRid[pick.rid] ?: continue
            items.add(mediaItemFor(entry, recId, pick.slot, inputs.station, pick))
            pickedIds.add(entry.candidate.mediaId)
            remember(entry)
        }
        if (items.isEmpty()) return false
        pool.removeAll { it.candidate.mediaId in pickedIds }
        if (inputs.station != null && inputs.station == station) stationBatches++
        deliver(items)
        return true
    }

    /**
     * The library-only pool (offline, the YouTube source off, or no radio had anything): in pool order
     * under the hard constraints, as in stage 1a.
     */
    private fun appendInPoolOrder(queue: List<MediaItem>): Boolean {
        if (pool.isEmpty()) return false
        val now = clock.nowMs()
        val excludedMediaIds = HashSet<String>(queue.size * 2 + 64)
        val tail = ArrayList<AutoplayPolicy.Slot>()
        for (item in queue) {
            excludedMediaIds.add(item.mediaId)
            val extras = item.mediaMetadata.extras
            if (BrainExtras.isEngineOwned(extras)) {
                tail.add(
                    AutoplayPolicy.Slot(
                        artistKey = extras?.getString(EXTRA_ARTIST_KEY).orEmpty(),
                        albumKey = extras?.getString(EXTRA_ALBUM_KEY),
                    )
                )
            }
        }
        val since = now - FOUR_HOURS_MS
        for (entry in listeningStatsTracker.playbackHistory.value) {
            if (entry.timestamp >= since) excludedMediaIds.add(entry.songId)
        }
        val picked = AutoplayPolicy.pickBatch(
            pool = pool.map { it.candidate },
            engineTail = if (tail.size > 50) tail.subList(tail.size - 50, tail.size) else tail,
            excludedMediaIds = excludedMediaIds,
            excludedRids = emptySet(),
        )
        if (picked.isEmpty()) return false

        val pickedIds = picked.mapTo(HashSet()) { it.mediaId }
        val entries = pool.filter { it.candidate.mediaId in pickedIds }.associateBy { it.candidate.mediaId }
        pool.removeAll { it.candidate.mediaId in pickedIds }

        batchSeq = (batchSeq + 1) and 0xff
        val recId = (now shl 8) or batchSeq.toLong()
        val items = ArrayList<MediaItem>(picked.size)
        for (slot in picked.indices) {
            val entry = entries[picked[slot].mediaId] ?: continue
            items.add(mediaItemFor(entry, recId, tail.size + slot, station, null))
            remember(entry)
        }
        if (items.isEmpty()) return false
        deliver(items)
        return true
    }

    /** Appends now, or holds the items until a running hand-over is over; then resumes an ended queue. */
    private fun deliver(items: List<MediaItem>) {
        if (AutoplayPolicy.mustDeferQueueEdit(engine.isTransitionRunning())) {
            pendingAppend = items
        } else {
            engine.appendToQueueEnd(items)
        }
        _uiState.value = _uiState.value.copy(fromLibrary = poolFromLibrary)
        if (queueEndedAtMs != 0L) resumeAfterQueueEnd()
    }

    private fun remember(entry: PoolEntry) {
        served[entry.candidate.mediaId] = entry
        if (served.size > SERVED_LIMIT) {
            val oldest = served.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun mediaItemFor(entry: PoolEntry, recId: Long, slot: Int, station: Station?, pick: StationPlanner.Pick?): MediaItem {
        val base = MediaItemBuilder.build(entry.song)
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle.EMPTY)
        BrainExtras.write(
            extras,
            BrainItemTag(
                surface = Surface.nameOf(if (station != null) Surface.STATION else Surface.AUTOPLAY),
                surfaceId = station?.id ?: poolKey.substringBefore('|').take(BrainExtras.MAX_SURFACE_ID_LENGTH),
                recId = recId,
                slot = slot,
                arm = Arm.nameOf(pick?.arm ?: entry.candidate.arm),
            )
        )
        extras.putInt(BrainExtras.RETR_Q, pick?.retrQ ?: -1)
        extras.putInt(BrainExtras.RADIO_RANK, pick?.radioRank ?: if (entry.candidate.radioRank > 0) entry.candidate.radioRank else -1)
        // The reason line (8.8): a key from the pure core and the name its argument stands for.
        val whyName = when (pick?.whyKey) {
            null -> entry.whyArtist
            WhyLine.RADIO_FANS, WhyLine.ARTIST_MORE -> poolArtistNames[pick.whyArg] ?: entry.whyArtist
            WhyLine.BECAUSE_LIKED -> likedTitles[pick.whyArg].orEmpty()
            else -> ""
        }
        if (pick != null && pick.whyKey != WhyLine.NONE) {
            extras.putString(BrainExtras.WHY_KEY, WhyLine.nameOf(pick.whyKey))
        } else if (pick == null && entry.candidate.radioRank > 0 && whyName.isNotBlank()) {
            // Picked in pool order (no planner): a row of an anchor's radio is still what that anchor's
            // artist's listeners play. A plain library pick says nothing; the line says "From your library".
            extras.putString(BrainExtras.WHY_KEY, WhyLine.nameOf(WhyLine.RADIO_FANS))
        }
        if (whyName.isNotBlank()) extras.putString(BrainExtras.WHY_ARG, whyName)
        if (entry.primaryArtist.isNotBlank()) extras.putString(BrainExtras.PRIMARY_ARTIST, entry.primaryArtist)
        extras.putString(EXTRA_ARTIST_KEY, entry.candidate.artistKey)
        entry.candidate.albumKey?.let { extras.putString(EXTRA_ALBUM_KEY, it) }
        return base.buildUpon()
            .setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    /** After the backstop appended: start the first appended track, if the listener still wants music. */
    private fun resumeAfterQueueEnd(): Boolean {
        val endedAt = queueEndedAtMs
        if (endedAt == 0L || pendingAppend != null) return false
        val player = engine.masterPlayer
        if (!AutoplayPolicy.backstopMayResume(endedAt, clock.nowMs(), player.playWhenReady)) return false
        if (player.playbackState != Player.STATE_ENDED || !player.hasNextMediaItem()) return false
        queueEndedAtMs = 0L
        setFinding(false)
        player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        return true
    }

    // ── Pool ─────────────────────────────────────────────────────────────────

    private fun startFill(anchors: List<Anchor>, settle: Boolean, deepen: Boolean) {
        val host = host ?: return
        if (fillJob?.isActive == true && fillKey == poolKey) return
        val key = poolKey
        // A build that found nothing at all is not repeated on every play/pause for a while.
        if (key == emptyBuildKey && clock.nowMs() - emptyBuildAtMs in 0 until EMPTY_BUILD_RETRY_MS) {
            setFinding(false)
            return
        }
        fillKey = key
        val currentMediaId = engine.masterPlayer.currentMediaItem?.mediaId
        val station = station
        val otherDirection = needsOtherDirection && station == null
        fillJob = host.scope.launch {
            try {
                if (settle && !waitUntilSettled(currentMediaId)) return@launch
                val queueIds = engine.getFullQueue().map { it.mediaId }
                val built = withContext(Dispatchers.Default) { buildPool(anchors, deepen, queueIds, otherDirection, station) }
                if (key != poolKey) return@launch
                pool = built.entries.toMutableList()
                poolFromLibrary = built.fromLibrary
                poolSeeds = built.seeds
                poolArtistNames = built.artistNames
                if (deepen) poolDeepened = true
                if (built.entries.isEmpty()) {
                    emptyBuildKey = key
                    emptyBuildAtMs = clock.nowMs()
                }
                onPoolReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "autoplay: building the pool failed")
            }
        }
    }

    /**
     * Waits until the current track is [AutoplayPolicy.SETTLE_MS] in, so a radio request never competes
     * with the first bytes of the track. False when the track changed (the new one re-triggers) or it
     * stayed paused: [onPlayingChanged] and the backstop pick that case up, without a polling loop.
     */
    private suspend fun waitUntilSettled(mediaId: String?): Boolean {
        repeat(SETTLE_POLLS) {
            val player = engine.masterPlayer
            if (player.currentMediaItem?.mediaId != mediaId) return false
            val position = player.currentPosition
            if (AutoplayPolicy.isSettled(position)) return true
            delay((AutoplayPolicy.SETTLE_MS - position).coerceIn(100L, AutoplayPolicy.SETTLE_MS))
        }
        return false
    }

    private fun onPoolReady() {
        val host = host ?: return
        val player = engine.masterPlayer
        val queue = engine.getFullQueue()
        val decision = AutoplayPolicy.decide(
            active, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(),
            queue.size, engine.getCurrentAbsoluteIndex()
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP || pendingAppend != null) {
            // The queue is long enough, but its engine tail may be from the direction just left.
            if (decision == AutoplayPolicy.Decision.ENOUGH_QUEUED) rescoreTail()
            return
        }
        if (queueEndedAtMs != 0L) {
            // Backstop: outside the window, or after a pause, the queue stays ended and nothing is added.
            if (!AutoplayPolicy.backstopMayResume(queueEndedAtMs, clock.nowMs(), player.playWhenReady)) {
                queueEndedAtMs = 0L
                setFinding(false)
                return
            }
            appendFromPool(queue) { appended -> if (!appended) setFinding(false) }
            return
        }
        appendFromPool(queue) { }
    }

    /** Background. Database, network and text folding all happen here; the result is a finished pool. */
    private suspend fun buildPool(
        anchors: List<Anchor>,
        deepen: Boolean,
        queueIds: List<String>,
        otherDirection: Boolean,
        station: Station? = null,
    ): BuiltPool {
        val now = clock.nowMs()
        val library = musicRepository.getAllSongsOnce()
        val index = libraryIndexFor(library, now)
        val libraryById = HashMap<String, Song>(library.size * 2)
        for (song in library) libraryById[song.id] = song
        val blocked = blockedArtists.current
        val adjustments = tasteAdjustments.snapshot(now)
        val everPlayed = runCatching {
            engagementDao.getAllEngagements().filter { it.playCount > 0 }.mapTo(HashSet()) { it.songId }
        }.getOrDefault(HashSet())
        val favourites = runCatching { musicRepository.getFavoriteSongIdsOnce() }.getOrDefault(emptySet())
        val ignoredRids = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }
            .getOrDefault(HashSet())
        val allowed: (Song) -> Boolean = { song ->
            !blocked.isBlocked(song) && !adjustments.excludes(song) && !adjustments.blocks(song)
        }

        val online = networkConditions.current().network != StreamPrefetchPolicy.Network.OFFLINE
        val allowNetwork = AutoplayPolicy.mayUseNetwork(online, youTubeSourceEnabled)

        // Three skips in a row with nothing else to turn to: the listener's best-loved library artist
        // outside the ones just skipped (7.3).
        val seedAnchors = if (otherDirection) {
            otherDirectionAnchor(library, index)?.let(::listOf) ?: anchors
        } else {
            anchors
        }

        var seedFlags = 0
        val anchorArtistKeys = LinkedHashSet<String>()
        val seeds = ArrayList<Pair<RadioSeed, String>>(seedAnchors.size)
        for (anchor in seedAnchors) {
            seedFlags = seedFlags or BrainText.cleanTitle(anchor.title).versionFlags
            val librarySong = libraryById[anchor.mediaId]
            val primaryArtist = librarySong?.let(LibraryIndex::primaryArtistName)
                ?: anchor.info?.primaryArtist
                ?: anchor.item?.mediaMetadata?.extras?.getString(BrainExtras.PRIMARY_ARTIST)
                ?: ""
            if (primaryArtist.isNotBlank()) anchorArtistKeys.add(BrainText.fold(primaryArtist))
            seedFor(anchor, librarySong, primaryArtist)?.let { seeds.add(it to primaryArtist) }
        }

        class Fetched(val entries: List<PoolEntry>, val seedRid: Long, val rids: LongArray, val related: Boolean, val artist: String)

        val lists: List<Fetched?> = coroutineScope {
            seeds.mapIndexed { i, (seed, whyArtist) ->
                async {
                    val radio = brainRepository.radioFor(
                        seed = seed,
                        allowNetwork = allowNetwork,
                        continuations = if (deepen && i == 0) DEEPEN_CONTINUATIONS else 0,
                    ) ?: return@async null
                    val artistRadio = radio.kind == RadioListEntity.KIND_ARTIST
                    val list = ArrayList<PoolEntry>(radio.rows.size)
                    for (rank in radio.rows.indices) {
                        entryFor(radio.rows[rank], rank + 1, index, everPlayed, favourites, adjustments, whyArtist, artistRadio)?.let(list::add)
                    }
                    Fetched(list, radio.seedRid, LongArray(radio.rows.size) { radio.rows[it].rid }, radio.kind == RadioListEntity.KIND_RELATED, whyArtist)
                }
            }.awaitAll()
        }
        var fetched = lists.filterNotNull()
        if (station != null && station.isArtist && seeds.isNotEmpty()) {
            // An artist station leads with the artist's own radio (6.2): the artist page's top songs and
            // the radio its button starts. Asked after the seed's song radio, whose rows usually name
            // the artist's channel, so the first request is not made twice. Only the artist's own
            // tracks count as "more from" them; the rest are what their listeners also play.
            val (seed, whyArtist) = seeds[0]
            val artistKey = BrainText.fold(whyArtist)
            brainRepository.artistRadioFor(seed, allowNetwork)?.let { radio ->
                val list = ArrayList<PoolEntry>(radio.rows.size)
                for (rank in radio.rows.indices) {
                    val own = artistKey.isNotEmpty() && BrainRepository.artistKeyOf(radio.rows[rank].row) == artistKey
                    entryFor(radio.rows[rank], rank + 1, index, everPlayed, favourites, adjustments, whyArtist, own)?.let(list::add)
                }
                fetched = listOf(Fetched(list, radio.seedRid, LongArray(radio.rows.size) { radio.rows[it].rid }, false, whyArtist)) + fetched
            }
        }

        val byMediaId = HashMap<String, PoolEntry>()
        for (list in fetched) for (entry in list.entries) byMediaId.putIfAbsent(entry.candidate.mediaId, entry)
        val ordered = AutoplayPolicy.interleave(fetched.map { list -> list.entries.map { it.candidate } })
        val entries = ArrayList<PoolEntry>(ordered.size)
        for (candidate in ordered) {
            if (!AutoplayPolicy.passesFilters(candidate, seedFlags)) continue
            if (candidate.rid in ignoredRids) continue
            val entry = byMediaId[candidate.mediaId] ?: continue
            if (!allowed(entry.song)) continue
            entries.add(entry)
        }

        if (entries.isNotEmpty()) {
            // Library tracks by the anchors' artists are candidates too (6.1 FAV and DEEP), whether or
            // not a radio names them.
            val known = entries.mapTo(HashSet()) { it.candidate.mediaId }
            val anchorIds = seedAnchors.mapTo(HashSet()) { it.mediaId }
            val extra = ArrayList<Song>()
            for (artistKey in anchorArtistKeys) {
                var taken = 0
                for (song in index.byPrimaryArtist(artistKey)) {
                    if (taken >= LIBRARY_PER_ANCHOR_ARTIST) break
                    if (song.id in anchorIds || !known.add(song.id) || !allowed(song)) continue
                    extra.add(song)
                    taken++
                }
            }
            val extraFacts = extra.map(LibraryTrackCatalog::factsOf)
            val extraRids = identity.resolveTracks(
                extraFacts.map { f ->
                    TrackRequest(f?.keys ?: com.theveloper.pixelplay.brain.Ids.TrackKeys(), f?.durationMs?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0)
                }
            )
            for (i in extra.indices) {
                if (extraRids[i] == 0L || extraRids[i] in ignoredRids) continue
                val entry = libraryEntry(extra[i], extraRids[i], 0, everPlayed, favourites, adjustments, "")
                if (AutoplayPolicy.passesFilters(entry.candidate, seedFlags)) entries.add(entry)
            }

            // Artists by id: stage 1 joins them by folded name (3.1), through the alias table.
            val names = LinkedHashSet<String>()
            for (list in fetched) if (list.artist.isNotBlank()) names.add(list.artist)
            for (entry in entries) if (entry.primaryArtist.isNotBlank()) names.add(entry.primaryArtist)
            val nameList = names.toList()
            val aids = identity.resolveArtistNames(nameList)
            val aidByName = HashMap<String, Long>(nameList.size * 2)
            val nameByAid = HashMap<Long, String>(nameList.size * 2)
            for (i in nameList.indices) {
                aidByName[nameList[i]] = aids[i]
                if (aids[i] != 0L) nameByAid.putIfAbsent(aids[i], nameList[i])
            }
            val withIds = entries.map { entry ->
                val candidate = entry.brain ?: return@map entry
                PoolEntry(
                    entry.candidate, entry.song, entry.engineTrack, entry.whyArtist, entry.primaryArtist,
                    Candidate(
                        rid = candidate.rid, aid = aidByName[entry.primaryArtist] ?: 0L, albumKey = candidate.albumKey,
                        inLibrary = candidate.inLibrary, liked = candidate.liked,
                        fromArtistRadio = candidate.fromArtistRadio, boostLn = candidate.boostLn,
                    ),
                )
            }
            val seedLists = fetched.map { list ->
                Retrieval.SeedList(
                    seedRid = list.seedRid,
                    seedAid = aidByName[list.artist] ?: 0L,
                    rids = list.rids,
                    edgeFactor = if (list.related) Graph.RELATED else Graph.RADIO,
                )
            }

            // Every engine track of the pool goes into `playable` BEFORE it can reach the player: the
            // stream proxy asks for its video id the moment ExoPlayer opens `spotify://<synthId>`.
            playableStore.register(withIds.mapNotNull { it.engineTrack }, now)
            prunePlayableOncePerDay(queueIds, now)
            if (deepen && withIds.size < AutoplayPolicy.POOL_REFILL_BELOW) {
                // The radios are running dry even with their continuation pages: the library carries on
                // behind them, so the queue does not end while there is anything left to play. That
                // pool has no recording ids, so it is picked in its own order.
                val all = ArrayList(withIds)
                val seen = all.mapTo(HashSet()) { it.candidate.mediaId }
                libraryPool(library, index, seedAnchors, anchorArtistKeys, everPlayed, allowed)
                    .filterTo(all) { seen.add(it.candidate.mediaId) }
                return BuiltPool(all, fromLibrary = false)
            }
            return BuiltPool(withIds, fromLibrary = false, seeds = seedLists, artistNames = nameByAid)
        }
        return BuiltPool(libraryPool(library, index, seedAnchors, anchorArtistKeys, everPlayed, allowed), fromLibrary = true)
    }

    private fun entryFor(
        stored: StoredRadioRow,
        rank: Int,
        index: LibraryIndex,
        everPlayed: Set<String>,
        favourites: Set<String>,
        adjustments: com.theveloper.pixelplay.data.recommendation.TasteAdjustments,
        anchorArtist: String,
        artistRadio: Boolean,
    ): PoolEntry? {
        val row = stored.row
        val librarySong = index.match(row)
        if (librarySong != null) return libraryEntry(librarySong, stored.rid, rank, everPlayed, favourites, adjustments, anchorArtist)
        if (row.artists.isEmpty()) return null
        val track = EngineTrack.from(row, stored.rid)
        val song = track.toSong()
        val albumKey = row.album?.let(::albumKeyOf)
        return PoolEntry(
            candidate = AutoplayPolicy.Candidate(
                mediaId = track.songId.toString(),
                rid = stored.rid,
                artistKey = BrainRepository.artistKeyOf(row),
                albumKey = albumKey,
                durationMs = track.durationMs,
                versionFlags = BrainText.cleanTitle(row.title).versionFlags,
                officialAudio = row.isOfficialAudio,
                inLibrary = false,
                arm = if (artistRadio) Arm.ARTIST else Arm.RADIO,
                radioRank = rank,
            ),
            song = song,
            engineTrack = track,
            whyArtist = anchorArtist,
            primaryArtist = row.artists.first().name,
            brain = Candidate(
                rid = stored.rid, aid = 0L, albumKey = albumHash(albumKey), inLibrary = false,
                fromArtistRadio = artistRadio, boostLn = boostLn(adjustments, song),
            ),
        )
    }

    private fun libraryEntry(
        song: Song,
        rid: Long,
        rank: Int,
        everPlayed: Set<String>,
        favourites: Set<String>,
        adjustments: com.theveloper.pixelplay.data.recommendation.TasteAdjustments,
        anchorArtist: String,
    ): PoolEntry {
        val albumKey = albumKeyOf(song.album)
        return PoolEntry(
            candidate = AutoplayPolicy.Candidate(
                mediaId = song.id,
                rid = rid,
                artistKey = LibraryIndex.artistKeyOf(song),
                albumKey = albumKey,
                durationMs = song.duration,
                versionFlags = BrainText.cleanTitle(song.title).versionFlags,
                officialAudio = true,
                inLibrary = true,
                arm = AutoplayPolicy.libraryArm(song.id in everPlayed),
                radioRank = rank,
            ),
            song = song,
            engineTrack = null,
            whyArtist = anchorArtist,
            primaryArtist = LibraryIndex.primaryArtistName(song),
            brain = Candidate(
                rid = rid, aid = 0L, albumKey = albumHash(albumKey), inLibrary = true,
                liked = song.isFavorite || song.id in favourites, boostLn = boostLn(adjustments, song),
            ),
        )
    }

    /** The listener's boost / reduce for a song, as the planner adds it: `ln(product)`, 0 for none (5.11). */
    private fun boostLn(adjustments: com.theveloper.pixelplay.data.recommendation.TasteAdjustments, song: Song): Double {
        if (adjustments.isEmpty) return 0.0
        val multiplier = adjustments.combinedMultiplier(song)
        return if (multiplier == 1.0 || multiplier <= 0.0) 0.0 else BrainMath.ln(multiplier)
    }

    /**
     * The seed for a change of direction when the session has no other anchor: a matched library
     * track by the library artist the listener finishes most, outside the artists just skipped.
     */
    private suspend fun otherDirectionAnchor(library: List<Song>, index: LibraryIndex): Anchor? {
        if (library.isEmpty()) return null
        val keys = index.artistKeys().toList()
        val names = keys.map { key -> index.byPrimaryArtist(key).firstOrNull()?.let(LibraryIndex::primaryArtistName).orEmpty() }
        val aids = identity.resolveArtistNames(names)
        val now = clock.nowMs()
        val order: List<Int> = brain.withState { state ->
            val p0 = com.theveloper.pixelplay.brain.Affinity.p0(state.affinity, now)
            keys.indices
                .filter { aids[it] != 0L && state.session.skipsOf(aids[it]) == 0 && !state.session.isBlocked(aids[it]) }
                .map { it to com.theveloper.pixelplay.brain.Affinity.aff(state.affinity, com.theveloper.pixelplay.brain.Family.ARTIST, aids[it], now, p0) }
                .filter { it.second > 0.0 }
                .sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else aids[a.first].compareTo(aids[b.first]) }
                .take(OTHER_DIRECTION_TRIES)
                .map { it.first }
        }
        for (i in order) {
            for (song in index.byPrimaryArtist(keys[i]).take(OTHER_DIRECTION_TRIES)) {
                val spotifyId = song.spotifyId ?: continue
                if (runCatching { spotifyDao.getMatchedVideoId(spotifyId) }.getOrNull() == null) continue
                return Anchor(
                    song.id, song.title, null,
                    AnchorInfo(0L, song.id, song.title, names[i], spotifyId, song.duration),
                )
            }
        }
        return null
    }

    /**
     * What a radio request needs to know about an anchor. Null when the anchor has no known YouTube
     * audio id yet (a library track that was never matched).
     */
    private suspend fun seedFor(anchor: Anchor, librarySong: Song?, primaryArtist: String): RadioSeed? {
        val extras = anchor.item?.mediaMetadata?.extras
        val spotifyId = librarySong?.spotifyId
            ?: anchor.info?.spotifyId
            ?: extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_SPOTIFY_ID)
            ?: anchor.item?.localConfiguration?.uri?.toString()
                ?.takeIf { it.startsWith(EngineTrack.CONTENT_URI_PREFIX) }
                ?.removePrefix(EngineTrack.CONTENT_URI_PREFIX)?.substringBefore('/')
            ?: return null
        val videoId = runCatching { spotifyDao.getMatchedVideoId(spotifyId) }.getOrNull()
            ?: playableStore.videoIdFor(spotifyId)
            ?: return null
        val durationMs = librarySong?.duration
            ?: anchor.info?.durationMs
            ?: extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L)
            ?: 0L
        return RadioSeed(
            mediaId = anchor.mediaId,
            videoId = videoId,
            title = librarySong?.title ?: anchor.title,
            primaryArtist = primaryArtist,
            durationMs = durationMs,
            // A YouTube Music import carries a synthetic id (22 hex characters), not a Spotify track id.
            spotifyId = spotifyId.takeUnless(LibraryTrackCatalog::isSyntheticId),
        )
    }

    /**
     * Offline, the YouTube source switched off, or no radio had anything: tracks by the anchors' primary
     * artists first, then the existing ranking's picks. The hard constraints still apply on the way out.
     */
    private suspend fun libraryPool(
        library: List<Song>,
        index: LibraryIndex,
        anchors: List<Anchor>,
        anchorArtistKeys: Set<String>,
        everPlayed: Set<String>,
        allowed: (Song) -> Boolean,
    ): List<PoolEntry> {
        if (library.isEmpty()) return emptyList()
        val anchorIds = anchors.mapTo(HashSet()) { it.mediaId }
        val songs = LinkedHashMap<String, Song>()
        // A fixed order per set of anchors, different between sets: SplitMix64 keyed by the anchors.
        val seed = BrainHash.fnv1a64(anchors.joinToString("|") { it.mediaId })
        for (artistKey in anchorArtistKeys) {
            index.byPrimaryArtist(artistKey)
                .sortedWith { a, b ->
                    java.lang.Long.compareUnsigned(
                        BrainHash.splitmix64(BrainHash.fnv1a64(a.id) xor seed),
                        BrainHash.splitmix64(BrainHash.fnv1a64(b.id) xor seed),
                    )
                }
                .forEach { songs.putIfAbsent(it.id, it) }
        }
        val picks = runCatching {
            dailyMixManager.personalizedPicks(library, musicRepository.getFavoriteSongIdsOnce(), LIBRARY_PICKS)
        }.getOrDefault(emptyList())
        for (pick in picks) songs.putIfAbsent(pick.song.id, pick.song)

        val out = ArrayList<PoolEntry>(songs.size)
        for (song in songs.values) {
            if (song.id in anchorIds || !allowed(song)) continue
            val candidate = AutoplayPolicy.Candidate(
                mediaId = song.id,
                rid = 0L,
                artistKey = LibraryIndex.artistKeyOf(song),
                albumKey = albumKeyOf(song.album),
                durationMs = song.duration,
                versionFlags = 0,
                officialAudio = true,
                inLibrary = true,
                arm = AutoplayPolicy.libraryArm(song.id in everPlayed),
                radioRank = 0,
            )
            if (AutoplayPolicy.passesFilters(candidate, 0)) {
                out.add(PoolEntry(candidate, song, engineTrack = null, whyArtist = "", primaryArtist = LibraryIndex.primaryArtistName(song), brain = null))
            }
        }
        return out
    }

    private fun libraryIndexFor(library: List<Song>, now: Long): LibraryIndex {
        val cached = libraryIndex
        if (cached != null && cached.size == library.size && now - libraryIndexBuiltAtMs in 0 until LIBRARY_INDEX_TTL_MS) {
            return cached
        }
        return LibraryIndex(library).also {
            libraryIndex = it
            libraryIndexBuiltAtMs = now
        }
    }

    /** `playable` rows nobody used for 60 days go, except what the queue or the history still names. */
    private suspend fun prunePlayableOncePerDay(queueIds: List<String>, now: Long) {
        if (now - lastPruneAtMs in 0 until ONE_DAY_MS) return
        lastPruneAtMs = now
        try {
            val keep = HashSet<String>(queueIds)
            val since = now - SIXTY_DAYS_MS
            for (entry in listeningStatsTracker.playbackHistory.value) {
                if (entry.timestamp >= since) keep.add(entry.songId)
            }
            playableStore.prune(keep, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "autoplay: pruning playable failed")
        }
    }

    // ── Station (8.3) and taste changes (8.5) ────────────────────────────────

    /**
     * Reads off the queue whether it is still the station the listener started: it is, for as long as
     * its seed track is in the queue. A queue that does not hold the
     * seed (the listener played something else by a path the dispatch did not see) ends the station,
     * except in the first moments after the start, when the seed is still on its way to the player.
     */
    private fun refreshStation(queue: List<MediaItem>) {
        val wanted = stations.current.value
        var next: Station? = null
        if (wanted != null) {
            // By media id alone: a station can be started from an autoplay pick that is playing.
            for (item in queue) {
                if (item.mediaId == wanted.seedMediaId) {
                    next = wanted
                    break
                }
            }
            if (next == null && queue.isNotEmpty() && host?.isRestoringQueue() != true &&
                clock.nowMs() - wanted.startedAtMs !in 0 until StationStore.START_GRACE_MS
            ) {
                stations.clear()
            }
        }
        if (next != station) {
            station = next
            stationBatches = 0
            _uiState.value = _uiState.value.copy(stationTitle = next?.title, stationIsArtist = next?.isArtist == true)
        }
    }

    /**
     * The listener muted, blocked or reduced something. What is now excluded leaves the pool at once,
     * and the engine's upcoming picks that match leave the queue in one edit (never the listener's own
     * items, never during a hand-over). A reduce changes scores, so the tail is planned again.
     */
    @MainThread
    private fun onTasteChanged() {
        val host = host ?: return
        if (tasteJob?.isActive == true) {
            tasteDirty = true
            return
        }
        tasteJob = host.scope.launch {
            try {
                // Which tracks the engine holds are now excluded is worked out off the main thread
                // (it folds every artist name); the main thread only drops them by id.
                val held = ArrayList<PoolEntry>(pool.size + served.size)
                held.addAll(pool)
                held.addAll(served.values)
                val blocked = blockedArtists.current
                val goneIds: Set<String> = withContext(Dispatchers.Default) {
                    val adjustments = tasteAdjustments.snapshot(clock.nowMs())
                    val out = HashSet<String>()
                    for (entry in held) {
                        val song = entry.song
                        if (blocked.isBlocked(song) || adjustments.excludes(song) || adjustments.blocks(song)) {
                            out.add(entry.candidate.mediaId)
                        }
                    }
                    out
                }
                if (this@AutoplayController.host == null) return@launch
                if (goneIds.isNotEmpty()) {
                    pool.removeAll { it.candidate.mediaId in goneIds }
                    if (AutoplayPolicy.mustDeferQueueEdit(engine.isTransitionRunning())) {
                        pendingTasteFilter = true
                        return@launch
                    }
                    val queue = engine.getFullQueue()
                    val current = engine.getCurrentAbsoluteIndex()
                    // Only the unbroken run of engine items at the end of the queue is the engine's to edit.
                    var runStart = queue.size
                    while (runStart - 1 > current && BrainExtras.isEngineOwned(queue[runStart - 1].mediaMetadata.extras)) runStart--
                    var firstGone = -1
                    for (i in runStart until queue.size) {
                        if (queue[i].mediaId in goneIds) {
                            firstGone = i
                            break
                        }
                    }
                    if (firstGone > current) {
                        val keep = ArrayList<MediaItem>(queue.size - firstGone)
                        for (i in firstGone until queue.size) {
                            if (queue[i].mediaId in goneIds) served.remove(queue[i].mediaId) else keep.add(queue[i])
                        }
                        engine.replaceQueueTail(firstGone, keep)
                    }
                }
                rescoreTail()
                evaluate(settle = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "autoplay: applying a taste change failed")
            } finally {
                // A second change arrived while this one was applied: look once more.
                if (tasteDirty) {
                    tasteDirty = false
                    tasteJob = null
                    onTasteChanged()
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (tasteJob === job) tasteJob = null
            }
        }
    }

    // ── Small things ─────────────────────────────────────────────────────────

    private fun cancelWork() {
        fillJob?.cancel()
        fillJob = null
        appendJob?.cancel()
        appendJob = null
        rescoreJob?.cancel()
        rescoreJob = null
        pendingAppend = null
        pendingRescore = false
        queueEndedAtMs = 0L
        setFinding(false)
    }

    private fun setFinding(finding: Boolean) {
        val state = _uiState.value
        if (state.finding != finding) _uiState.value = state.copy(finding = finding)
    }

    private fun albumKeyOf(album: String): String? =
        BrainText.fold(album).takeIf { it.isNotEmpty() && it != "unknown album" }

    private fun albumHash(albumKey: String?): Long = if (albumKey == null) 0L else BrainHash.fnv1a64(albumKey)

    private companion object {
        /** Folded primary artist and album of an engine-owned queue item, for the spacing rules. */
        const val EXTRA_ARTIST_KEY = "brain.artistKey"
        const val EXTRA_ALBUM_KEY = "brain.albumKey"

        const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        const val SIXTY_DAYS_MS = 60L * ONE_DAY_MS
        const val LIBRARY_INDEX_TTL_MS = 10L * 60 * 1000
        const val LIBRARY_PICKS = 40
        const val LIBRARY_PER_ANCHOR_ARTIST = 30
        const val DEEPEN_CONTINUATIONS = 2
        const val SETTLE_POLLS = 8
        const val EMPTY_BUILD_RETRY_MS = 5L * 60 * 1000
        const val SERVED_LIMIT = 400
        const val DIRECTION_NOTE_MS = 12_000L
        const val OTHER_DIRECTION_TRIES = 5

        /** 6.2: a station's first batch, and its novelty share against autoplay's. */
        const val STATION_FIRST_BATCH = 20
        const val STATION_NU = 1.2
        const val STATION_START_RECHECK_MS = 750L
    }
}
