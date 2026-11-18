package com.theveloper.pixelplay.data.service.player

import android.os.Bundle
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainExtras
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainRepository
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.LibraryIndex
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.RadioSeed
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
)

/**
 * Autoplay: when two or fewer tracks are left, five more are appended, so music does not stop at the
 * end of a queue (docs/brain-spec.md, 7.1).
 *
 * Runs inside `MusicService`'s process and is driven by it: [onTransition] after every track change,
 * [onQueueEnded] as the backstop, [onTransitionFinished] to release an edit held during a crossfade.
 * All state here is confined to the main thread; pool building (database, network, text folding) runs
 * on background dispatchers and comes back with a finished pool.
 *
 * Rules this class keeps:
 *  - The trigger asks the in-memory pool, never the network. A radio request starts no earlier than
 *    1.5 s into a track, at most two at a time, under the YouTube budget.
 *  - The count is the FULL queue (`engine.getFullQueue()`), not the player's window.
 *  - One queue edit per top-up, never during a `DualPlayerEngine` transition.
 *  - Only tracks the listener chose are anchors. Engine picks that merely played are not, so an hour
 *    of autoplay is still about what the listener started with.
 *  - Tracks the listener queued are never moved or removed. Engine items are only ever appended.
 *  - Off under repeat, during Cast and Spotify Connect, and while a saved queue is being restored.
 *
 * Stage 1a picks in radio order under the hard constraints. There is no scorer and no session state
 * yet (stage 1b), so a skip does not change what comes next.
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
        /** The artist the pick is explained by: the anchor's primary artist. */
        val whyArtist: String,
    )

    private class Anchor(val mediaId: String, val title: String, val item: MediaItem)

    private class BuiltPool(val entries: List<PoolEntry>, val fromLibrary: Boolean)

    private val _uiState = MutableStateFlow(AutoplayUiState())
    val uiState: StateFlow<AutoplayUiState> = _uiState.asStateFlow()

    private var host: Host? = null
    private var settingsJob: Job? = null
    private var enabled = true

    @Volatile
    private var youTubeSourceEnabled = true

    private var pool: MutableList<PoolEntry> = ArrayList()
    private var poolKey = ""
    private var poolFromLibrary = false
    private var poolDeepened = false
    private var fillJob: Job? = null
    private var fillKey = ""
    private var pendingAppend: List<MediaItem>? = null
    private var queueEndedAtMs = 0L
    private var batchSeq = 0
    private var emptyBuildKey = ""
    private var emptyBuildAtMs = 0L

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
                    if (on) evaluate(settle = true) else cancelWork()
                }
            }
            launch { brainSwitch.youTubeSourceEnabledFlow.collect { youTubeSourceEnabled = it } }
        }
    }

    @MainThread
    fun detach() {
        settingsJob?.cancel()
        settingsJob = null
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

    /** The crossfade hand-over is over: an append held back during it goes in now. */
    @MainThread
    fun onTransitionFinished() {
        val items = pendingAppend ?: return
        pendingAppend = null
        if (host == null || !enabled) return
        engine.appendToQueueEnd(items)
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
        val decision = AutoplayPolicy.decide(
            enabled, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(), queue.size, index
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP || player.hasNextMediaItem()) return
        val anchors = anchorsOf(queue, index)
        if (anchors.isEmpty()) return
        adoptPoolFor(anchors)
        val endedAt = clock.nowMs()
        queueEndedAtMs = endedAt
        if (appendFromPool(queue) && resumeAfterQueueEnd()) return
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
        val decision = AutoplayPolicy.decide(
            enabled, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(), queue.size, index
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP) {
            if (decision != AutoplayPolicy.Decision.ENOUGH_QUEUED) cancelWork()
            return
        }
        val anchors = anchorsOf(queue, index)
        if (anchors.isEmpty()) return
        adoptPoolFor(anchors)
        if (appendFromPool(queue)) {
            if (pool.size < AutoplayPolicy.POOL_REFILL_BELOW && !poolFromLibrary && !poolDeepened) {
                startFill(anchors, settle = true, deepen = true)
            }
            return
        }
        // Nothing fits from what is in memory: build it, or once per set of anchors rebuild it deeper
        // (continuation pages, then the library behind them). After that the queue is allowed to end.
        if (pool.isNotEmpty() && poolDeepened) return
        startFill(anchors, settle, deepen = pool.isNotEmpty())
    }

    /** A pool belongs to its anchors: other anchors, another pool. */
    private fun adoptPoolFor(anchors: List<Anchor>) {
        val key = anchors.joinToString("|") { it.mediaId }
        if (key == poolKey) return
        poolKey = key
        pool = ArrayList()
        poolFromLibrary = false
        poolDeepened = false
        if (fillKey != key) {
            fillJob?.cancel()
            fillJob = null
        }
    }

    private fun anchorsOf(queue: List<MediaItem>, index: Int): List<Anchor> =
        AutoplayPolicy.anchorIndices(
            currentAbsoluteIndex = index,
            queueSize = queue.size,
            engineOwned = { BrainExtras.isEngineOwned(queue[it].mediaMetadata.extras) },
            mediaIdAt = { queue[it].mediaId },
        ).map { i ->
            val item = queue[i]
            Anchor(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item)
        }

    // ── Append ───────────────────────────────────────────────────────────────

    /**
     * Appends one batch from the in-memory pool. True when a batch went in (or is held for the end of a
     * transition). No I/O: a few hundred comparisons over what is already in memory.
     */
    private fun appendFromPool(queue: List<MediaItem>): Boolean {
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
        // 6.4: nothing played in the last four hours. Until the event log records (stage 1b) the
        // existing playback history answers this.
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
        val surfaceId = poolKey.substringBefore('|').take(BrainExtras.MAX_SURFACE_ID_LENGTH)
        val items = ArrayList<MediaItem>(picked.size)
        for (slot in picked.indices) {
            val entry = entries[picked[slot].mediaId] ?: continue
            items.add(mediaItemFor(entry, recId, slot, surfaceId))
        }
        if (items.isEmpty()) return false
        if (AutoplayPolicy.mustDeferQueueEdit(engine.isTransitionRunning())) {
            pendingAppend = items
        } else {
            engine.appendToQueueEnd(items)
        }
        _uiState.value = _uiState.value.copy(fromLibrary = poolFromLibrary)
        return true
    }

    private fun mediaItemFor(entry: PoolEntry, recId: Long, slot: Int, surfaceId: String): MediaItem {
        val base = MediaItemBuilder.build(entry.song)
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle.EMPTY)
        BrainExtras.write(
            extras,
            BrainItemTag(
                surface = Surface.nameOf(Surface.AUTOPLAY),
                surfaceId = surfaceId,
                recId = recId,
                slot = slot,
                arm = Arm.nameOf(entry.candidate.arm),
            )
        )
        extras.putInt(BrainExtras.RETR_Q, -1)
        extras.putInt(BrainExtras.RADIO_RANK, if (entry.candidate.radioRank > 0) entry.candidate.radioRank else -1)
        if (entry.whyArtist.isNotBlank()) extras.putString(BrainExtras.WHY_ARG, entry.whyArtist)
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
        fillJob = host.scope.launch {
            try {
                if (settle && !waitUntilSettled(currentMediaId)) return@launch
                val queueIds = engine.getFullQueue().map { it.mediaId }
                val built = withContext(Dispatchers.Default) { buildPool(anchors, deepen, queueIds) }
                if (key != poolKey) return@launch
                pool = built.entries.toMutableList()
                poolFromLibrary = built.fromLibrary
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
            enabled, player.repeatMode, host.isRemoteSession(), host.isRestoringQueue(),
            queue.size, engine.getCurrentAbsoluteIndex()
        )
        if (decision != AutoplayPolicy.Decision.TOP_UP || pendingAppend != null) return
        if (queueEndedAtMs != 0L) {
            // Backstop: outside the window, or after a pause, the queue stays ended and nothing is added.
            if (!AutoplayPolicy.backstopMayResume(queueEndedAtMs, clock.nowMs(), player.playWhenReady)) {
                queueEndedAtMs = 0L
                setFinding(false)
                return
            }
            if (appendFromPool(queue)) resumeAfterQueueEnd() else setFinding(false)
            return
        }
        appendFromPool(queue)
    }

    /** Background. Database, network and text folding all happen here; the result is a finished pool. */
    private suspend fun buildPool(anchors: List<Anchor>, deepen: Boolean, queueIds: List<String>): BuiltPool {
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
        val ignoredRids = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }
            .getOrDefault(HashSet())
        val allowed: (Song) -> Boolean = { song ->
            !blocked.isBlocked(song) && !adjustments.excludes(song) && !adjustments.blocks(song)
        }

        val online = networkConditions.current().network != StreamPrefetchPolicy.Network.OFFLINE
        val allowNetwork = AutoplayPolicy.mayUseNetwork(online, youTubeSourceEnabled)

        var seedFlags = 0
        val anchorArtistKeys = LinkedHashSet<String>()
        val seeds = ArrayList<Pair<RadioSeed, String>>(anchors.size)
        for (anchor in anchors) {
            seedFlags = seedFlags or BrainText.cleanTitle(anchor.title).versionFlags
            val librarySong = libraryById[anchor.mediaId]
            val primaryArtist = librarySong?.let(LibraryIndex::primaryArtistName).orEmpty()
            if (primaryArtist.isNotBlank()) anchorArtistKeys.add(BrainText.fold(primaryArtist))
            seedFor(anchor, librarySong, primaryArtist)?.let { seeds.add(it to primaryArtist) }
        }

        val lists: List<List<PoolEntry>> = coroutineScope {
            seeds.mapIndexed { i, (seed, whyArtist) ->
                async {
                    val radio = brainRepository.radioFor(
                        seed = seed,
                        allowNetwork = allowNetwork,
                        continuations = if (deepen && i == 0) DEEPEN_CONTINUATIONS else 0,
                    ) ?: return@async emptyList<PoolEntry>()
                    val artistRadio = radio.kind == RadioListEntity.KIND_ARTIST
                    val list = ArrayList<PoolEntry>(radio.rows.size)
                    for (rank in radio.rows.indices) {
                        entryFor(radio.rows[rank], rank + 1, index, everPlayed, whyArtist, artistRadio)?.let(list::add)
                    }
                    list
                }
            }.awaitAll()
        }

        val byMediaId = HashMap<String, PoolEntry>()
        for (list in lists) for (entry in list) byMediaId.putIfAbsent(entry.candidate.mediaId, entry)
        val ordered = AutoplayPolicy.interleave(lists.map { list -> list.map { it.candidate } })
        val entries = ArrayList<PoolEntry>(ordered.size)
        for (candidate in ordered) {
            if (!AutoplayPolicy.passesFilters(candidate, seedFlags)) continue
            if (candidate.rid in ignoredRids) continue
            val entry = byMediaId[candidate.mediaId] ?: continue
            if (!allowed(entry.song)) continue
            entries.add(entry)
        }

        if (entries.isNotEmpty()) {
            // Every engine track of the pool goes into `playable` BEFORE it can reach the player: the
            // stream proxy asks for its video id the moment ExoPlayer opens `spotify://<synthId>`.
            playableStore.register(entries.mapNotNull { it.engineTrack }, now)
            prunePlayableOncePerDay(queueIds, now)
            if (deepen && entries.size < AutoplayPolicy.POOL_REFILL_BELOW) {
                // The radios are running dry even with their continuation pages: the library carries on
                // behind them, so the queue does not end while there is anything left to play.
                val known = entries.mapTo(HashSet()) { it.candidate.mediaId }
                libraryPool(library, index, anchors, anchorArtistKeys, everPlayed, allowed)
                    .filterTo(entries) { known.add(it.candidate.mediaId) }
            }
            return BuiltPool(entries, fromLibrary = false)
        }
        return BuiltPool(libraryPool(library, index, anchors, anchorArtistKeys, everPlayed, allowed), fromLibrary = true)
    }

    private fun entryFor(
        stored: StoredRadioRow,
        rank: Int,
        index: LibraryIndex,
        everPlayed: Set<String>,
        anchorArtist: String,
        artistRadio: Boolean,
    ): PoolEntry? {
        val row = stored.row
        val librarySong = index.match(row)
        if (librarySong != null) {
            return PoolEntry(
                candidate = AutoplayPolicy.Candidate(
                    mediaId = librarySong.id,
                    rid = stored.rid,
                    artistKey = LibraryIndex.artistKeyOf(librarySong),
                    albumKey = albumKeyOf(librarySong.album),
                    durationMs = librarySong.duration,
                    versionFlags = BrainText.cleanTitle(librarySong.title).versionFlags,
                    officialAudio = true,
                    inLibrary = true,
                    arm = AutoplayPolicy.libraryArm(librarySong.id in everPlayed),
                    radioRank = rank,
                ),
                song = librarySong,
                engineTrack = null,
                whyArtist = anchorArtist,
            )
        }
        if (row.artists.isEmpty()) return null
        val track = EngineTrack.from(row, stored.rid)
        return PoolEntry(
            candidate = AutoplayPolicy.Candidate(
                mediaId = track.songId.toString(),
                rid = stored.rid,
                artistKey = BrainRepository.artistKeyOf(row),
                albumKey = row.album?.let(::albumKeyOf),
                durationMs = track.durationMs,
                versionFlags = BrainText.cleanTitle(row.title).versionFlags,
                officialAudio = row.isOfficialAudio,
                inLibrary = false,
                arm = if (artistRadio) Arm.ARTIST else Arm.RADIO,
                radioRank = rank,
            ),
            song = track.toSong(),
            engineTrack = track,
            whyArtist = anchorArtist,
        )
    }

    /**
     * What a radio request needs to know about an anchor. Null when the anchor has no known YouTube
     * audio id yet (a library track that was never matched).
     */
    private suspend fun seedFor(anchor: Anchor, librarySong: Song?, primaryArtist: String): RadioSeed? {
        val extras = anchor.item.mediaMetadata.extras
        val spotifyId = librarySong?.spotifyId
            ?: extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_SPOTIFY_ID)
            ?: anchor.item.localConfiguration?.uri?.toString()
                ?.takeIf { it.startsWith(EngineTrack.CONTENT_URI_PREFIX) }
                ?.removePrefix(EngineTrack.CONTENT_URI_PREFIX)?.substringBefore('/')
            ?: return null
        val videoId = runCatching { spotifyDao.getMatchedVideoId(spotifyId) }.getOrNull()
            ?: playableStore.videoIdFor(spotifyId)
            ?: return null
        val durationMs = librarySong?.duration
            ?: extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L)
            ?: 0L
        return RadioSeed(
            mediaId = anchor.mediaId,
            videoId = videoId,
            title = librarySong?.title ?: anchor.title,
            primaryArtist = primaryArtist,
            durationMs = durationMs,
            // A YouTube Music import carries a synthetic id (22 hex characters), not a Spotify track id.
            spotifyId = spotifyId.takeUnless(::isSyntheticId),
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
                out.add(PoolEntry(candidate, song, engineTrack = null, whyArtist = ""))
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

    // ── Small things ─────────────────────────────────────────────────────────

    private fun cancelWork() {
        fillJob?.cancel()
        fillJob = null
        pendingAppend = null
        queueEndedAtMs = 0L
        setFinding(false)
    }

    private fun setFinding(finding: Boolean) {
        val state = _uiState.value
        if (state.finding != finding) _uiState.value = state.copy(finding = finding)
    }

    private fun albumKeyOf(album: String): String? =
        BrainText.fold(album).takeIf { it.isNotEmpty() && it != "unknown album" }

    private fun isSyntheticId(id: String): Boolean =
        id.length == 22 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private companion object {
        /** Folded primary artist and album of an engine-owned queue item, for the spacing rules. */
        const val EXTRA_ARTIST_KEY = "brain.artistKey"
        const val EXTRA_ALBUM_KEY = "brain.albumKey"

        const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        const val SIXTY_DAYS_MS = 60L * ONE_DAY_MS
        const val LIBRARY_INDEX_TTL_MS = 10L * 60 * 1000
        const val LIBRARY_PICKS = 40
        const val DEEPEN_CONTINUATIONS = 2
        const val SETTLE_POLLS = 8
        const val EMPTY_BUILD_RETRY_MS = 5L * 60 * 1000
    }
}
