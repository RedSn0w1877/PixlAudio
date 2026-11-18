package com.theveloper.pixelplay.data.brain

import android.content.Context
import android.util.AtomicFile
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.Retrieval
import com.theveloper.pixelplay.brain.SessionState
import com.theveloper.pixelplay.brain.StationPlanner
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.player.AutoplayPolicy
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import com.theveloper.pixelplay.data.stream.StreamPrefetchPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** One card of a "Because you listened to" row, as it is saved. */
@Serializable
data class StoredHomeItem(
    val stored: StoredRadioRow,
    val arm: Int,
    val slot: Int,
    val retrQ: Int = -1,
    val radioRank: Int = -1,
)

@Serializable
data class StoredHomeRow(
    val anchor: HomeAnchor,
    val recId: Long,
    val items: List<StoredHomeItem>,
)

@Serializable
data class StoredHomeRows(
    val builtAtMs: Long = 0L,
    /** What the rows were built from; the same signature means the same rows. */
    val signature: String = "",
    val rows: List<StoredHomeRow> = emptyList(),
)

/** One card: a track the listener does not have, ready to play through the `playable` path. */
class BrainHomeItem(
    val track: EngineTrack,
    val song: Song,
    val arm: Int,
    val slot: Int,
    val retrQ: Int,
    val radioRank: Int,
)

/** A "Because you listened to X" row (docs/brain-spec.md, 8.1). */
class BrainHomeRow(
    /** Also the `surfaceId` its plays are logged under. */
    val id: String,
    val anchor: HomeAnchor,
    val recId: Long,
    val items: List<BrainHomeItem>,
) {
    /** What every item of the row carries into the queue when a card is played. */
    fun tags(): Map<String, BrainItemTag> {
        val out = HashMap<String, BrainItemTag>(items.size * 2)
        for (item in items) {
            out[item.song.id] = BrainItemTag(
                surface = Surface.nameOf(Surface.HOME_SHELF),
                surfaceId = id,
                recId = recId,
                slot = item.slot,
                arm = Arm.nameOf(item.arm),
                whyKey = WhyLine.nameOf(WhyLine.BECAUSE_LISTENED),
                whyArg = anchor.title,
                primaryArtist = item.track.artists.firstOrNull()?.name,
                // The listener chose to play the row: its tracks are theirs, like a playlist's.
                chosen = true,
                retrQ = item.retrQ,
                radioRank = item.radioRank,
            )
        }
        return out
    }

    companion object {
        const val ID_PREFIX = "because_"

        fun idOf(anchorRid: Long): String = ID_PREFIX + java.lang.Long.toHexString(anchorRid)
    }
}

/**
 * Builds and keeps the "Because you listened to X" rows of Home (docs/brain-spec.md, 8.1): for the
 * two newest anchors by different artists, X's cached radio, filtered (6.1) and scored (5.7), twelve
 * cards of music the listener does not have.
 *
 * Home shows the saved rows at once (a small file, no database) and never waits for anything here.
 * A rebuild happens only once the brain is in memory in this process (so never at app start), only
 * when the anchors or the listener's taste changes moved, and asks the network for at most one radio
 * per anchor, under the YouTube budget. Its result is what the next visit to Home shows.
 */
@Singleton
class BrainHomeRows @Inject constructor(
    @ApplicationContext private val context: Context,
    private val anchorStore: HomeAnchorStore,
    private val brainRepository: BrainRepository,
    private val brain: BrainEngine,
    private val identity: TrackResolver,
    private val musicRepository: MusicRepository,
    private val blockedArtists: BlockedArtistsRepository,
    private val tasteAdjustments: TasteAdjustmentRepository,
    private val playEventDao: PlayEventDao,
    private val spotifyDao: SpotifyDao,
    private val playableStore: PlayableStore,
    private val brainSwitch: BrainSwitch,
    private val networkConditions: StreamNetworkConditions,
    private val clock: BrainClock,
) {
    private val lock = Mutex()
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    @Volatile
    private var memory: StoredHomeRows? = null

    /** The saved rows, newest anchor first. Reads one small file the first time; never the database. */
    suspend fun cached(): List<BrainHomeRow> = withContext(Dispatchers.IO) {
        val stored = memory ?: lock.withLock { memory ?: readFile().also { memory = it } }
        present(stored, clock.nowMs())
    }

    /**
     * Rebuilds the rows when they are due. Null when nothing was rebuilt: the brain has not been used
     * in this process yet, or the rows are still what the anchors and the taste changes ask for.
     */
    suspend fun refreshIfDue(): List<BrainHomeRow>? = withContext(Dispatchers.IO) {
        if (!brain.isLoaded) return@withContext null
        lock.withLock {
            try {
                val now = clock.nowMs()
                val current = memory ?: readFile().also { memory = it }
                val anchors = HomeAnchorStore.pick(anchorStore.read(), now, MAX_ROWS)
                val signature = signatureOf(anchors, tasteAdjustments.version.value, blockedArtists.current.size)
                if (signature == current.signature && now - current.builtAtMs in 0 until MAX_AGE_MS) return@withLock null
                // Rows that could not be built yet (a radio that is not cached, offline) are tried again
                // on a later visit, not on every one.
                if (signature == lastFailedSignature && now - lastFailedAtMs in 0 until RETRY_MS) return@withLock null
                val rows = build(anchors, now)
                if (rows.size < anchors.size) {
                    lastFailedSignature = signature
                    lastFailedAtMs = now
                }
                // A row that could not be rebuilt keeps its saved cards while its anchor is still wanted.
                val kept = current.rows.filter { old -> anchors.any { it.rid == old.anchor.rid } && rows.none { it.anchor.rid == old.anchor.rid } }
                val merged = anchors.mapNotNull { anchor ->
                    rows.firstOrNull { it.anchor.rid == anchor.rid } ?: kept.firstOrNull { it.anchor.rid == anchor.rid }
                }
                val next = StoredHomeRows(
                    builtAtMs = now,
                    signature = if (rows.size == anchors.size) signature else current.signature,
                    rows = merged,
                )
                memory = next
                if (next.rows == current.rows) {
                    // The same cards: nothing new to show. Only the note of what they were built from moved.
                    if (next.signature != current.signature) writeFile(next)
                    return@withLock null
                }
                writeFile(next)
                present(next, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: building the Home rows failed; the saved rows stay")
                null
            }
        }
    }

    /** Every card of [row] must be in `playable` before its media item reaches the player (7.2). */
    suspend fun prepareForPlayback(row: BrainHomeRow) {
        playableStore.register(row.items.map { it.track }, clock.nowMs())
    }

    private var lastFailedSignature = ""
    private var lastFailedAtMs = 0L

    private suspend fun build(anchors: List<HomeAnchor>, now: Long): List<StoredHomeRow> {
        if (anchors.isEmpty()) return emptyList()
        val library = musicRepository.getAllSongsOnce()
        val index = LibraryIndex(library)
        val blocked = blockedArtists.current
        val adjustments = tasteAdjustments.snapshot(now)
        val ignored = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
        val online = networkConditions.current().network != StreamPrefetchPolicy.Network.OFFLINE
        val allowNetwork = AutoplayPolicy.mayUseNetwork(online, brainSwitch.youTubeSourceEnabledFlow.first())
        val tzOffsetMin = java.util.TimeZone.getDefault().getOffset(now) / 60_000

        val out = ArrayList<StoredHomeRow>(anchors.size)
        // A track appears once on the page.
        val usedRids = HashSet<Long>()
        for ((rowIndex, anchor) in anchors.withIndex()) {
            val seed = seedFor(anchor) ?: continue
            val radio = brainRepository.radioFor(seed, allowNetwork) ?: continue
            val artistRadio = radio.kind == RadioListEntity.KIND_ARTIST
            val seedFlags = BrainText.cleanTitle(anchor.title).versionFlags

            val rows = ArrayList<StoredRadioRow>(radio.rows.size)
            val ranks = ArrayList<Int>(radio.rows.size)
            val songs = ArrayList<Song>(radio.rows.size)
            for (rank in radio.rows.indices) {
                val stored = radio.rows[rank]
                val row = stored.row
                // Music the listener does not have: a recording of the library is not a card here.
                if (row.artists.isEmpty() || stored.rid == 0L || index.match(row) != null) continue
                if (stored.rid in ignored || stored.rid in usedRids) continue
                val track = EngineTrack.from(row, stored.rid)
                val policy = AutoplayPolicy.Candidate(
                    mediaId = track.songId.toString(),
                    rid = stored.rid,
                    artistKey = BrainRepository.artistKeyOf(row),
                    albumKey = null,
                    durationMs = track.durationMs,
                    versionFlags = BrainText.cleanTitle(row.title).versionFlags,
                    officialAudio = row.isOfficialAudio,
                    inLibrary = false,
                    arm = if (artistRadio) Arm.ARTIST else Arm.RADIO,
                    radioRank = rank + 1,
                )
                if (!AutoplayPolicy.passesFilters(policy, seedFlags)) continue
                val song = track.toSong()
                if (blocked.isBlocked(song) || adjustments.excludes(song) || adjustments.blocks(song)) continue
                rows.add(stored)
                ranks.add(rank + 1)
                songs.add(song)
            }
            if (rows.size < MIN_CARDS) continue

            val names = ArrayList<String>(rows.size + 1)
            names.add(anchor.primaryArtist)
            for (stored in rows) names.add(stored.row.artists.first().name)
            val aids = identity.resolveArtistNames(names)
            val candidates = ArrayList<Candidate>(rows.size)
            for (i in rows.indices) {
                candidates.add(
                    Candidate(
                        rid = rows[i].rid,
                        aid = aids[i + 1],
                        albumKey = albumHash(rows[i].row.album),
                        inLibrary = false,
                        fromArtistRadio = artistRadio,
                        boostLn = boostLn(adjustments, songs[i]),
                    )
                )
            }
            val seedList = Retrieval.SeedList(
                seedRid = radio.seedRid,
                seedAid = aids[0],
                rids = LongArray(radio.rows.size) { radio.rows[it].rid },
                edgeFactor = if (radio.kind == RadioListEntity.KIND_RELATED) Graph.RELATED else Graph.RADIO,
            )
            val plan = brain.withState { state ->
                // Home is not part of the listening session: a fresh one, so a skip a minute ago does
                // not reshuffle the page.
                val session = SessionState()
                val graph = StationPlanner.buildGraph(listOf(seedList), candidates, session)
                StationPlanner.plan(
                    StationPlanner.Request(
                        mode = StationPlanner.MODE_HOME_ROW,
                        count = CARDS,
                        candidates = candidates,
                        seeds = listOf(seedList),
                        nowMs = now,
                        tzOffsetMin = tzOffsetMin,
                        tieSeed = radio.seedRid xor BrainMath.localDay(now, tzOffsetMin),
                        excludedRids = usedRids,
                    ),
                    state.affinity,
                    session,
                    graph,
                )
            }
            if (plan.picks.size < MIN_CARDS) continue
            val byRid = HashMap<Long, Int>(rows.size * 2)
            for (i in rows.indices) byRid[rows[i].rid] = i
            val items = ArrayList<StoredHomeItem>(plan.picks.size)
            for (pick in plan.picks) {
                val i = byRid[pick.rid] ?: continue
                items.add(StoredHomeItem(rows[i], pick.arm, items.size, pick.retrQ, ranks[i]))
                usedRids.add(pick.rid)
            }
            out.add(StoredHomeRow(anchor, recId = (now shl 8) or (HOME_REC_TAG + rowIndex).toLong(), items = items))
        }
        return out
    }

    /** What a radio request needs to know about an anchor; null when it has no known YouTube audio id. */
    private suspend fun seedFor(anchor: HomeAnchor): RadioSeed? {
        val spotifyId = anchor.spotifyId ?: return null
        val videoId = runCatching { spotifyDao.getMatchedVideoId(spotifyId) }.getOrNull()
            ?: playableStore.videoIdFor(spotifyId)
            ?: return null
        return RadioSeed(
            mediaId = anchor.mediaId,
            videoId = videoId,
            title = anchor.title,
            primaryArtist = anchor.primaryArtist,
            durationMs = anchor.durationMs,
            spotifyId = spotifyId.takeUnless(LibraryTrackCatalog::isSyntheticId),
        )
    }

    private fun present(stored: StoredHomeRows, now: Long): List<BrainHomeRow> {
        val out = ArrayList<BrainHomeRow>(stored.rows.size)
        for (row in stored.rows) {
            if (now - row.anchor.heardAtMs !in 0 until HomeAnchorStore.MAX_AGE_MS) continue
            val items = row.items.map { item ->
                val track = EngineTrack.from(item.stored.row, item.stored.rid)
                BrainHomeItem(track, track.toSong(), item.arm, item.slot, item.retrQ, item.radioRank)
            }
            if (items.size >= MIN_CARDS) out.add(BrainHomeRow(BrainHomeRow.idOf(row.anchor.rid), row.anchor, row.recId, items))
        }
        return out
    }

    private fun readFile(): StoredHomeRows = runCatching {
        if (file.baseFile.length() !in 1..MAX_FILE_BYTES) return@runCatching StoredHomeRows()
        file.openRead().bufferedReader().use { json.decodeFromString(StoredHomeRows.serializer(), it.readText()) }
    }.getOrDefault(StoredHomeRows())

    private fun writeFile(rows: StoredHomeRows) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(json.encodeToString(StoredHomeRows.serializer(), rows).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            Timber.d(e, "brain: the Home rows could not be saved")
        }
    }

    private fun albumHash(album: String?): Long {
        val key = album?.let(BrainText::fold)?.takeIf { it.isNotEmpty() && it != "unknown album" } ?: return 0L
        return BrainHash.fnv1a64(key)
    }

    private fun boostLn(adjustments: TasteAdjustments, song: Song): Double {
        if (adjustments.isEmpty) return 0.0
        val multiplier = adjustments.combinedMultiplier(song)
        return if (multiplier == 1.0 || multiplier <= 0.0) 0.0 else BrainMath.ln(multiplier)
    }

    companion object {
        const val FILE_NAME = "brain_home_rows_v1.json"

        /** At most two rows, for the two newest anchors by different artists. */
        const val MAX_ROWS = 2
        const val CARDS = 12

        /** A row with fewer playable cards than this is hidden, not padded. */
        const val MIN_CARDS = 6

        const val MAX_AGE_MS = 6L * 60 * 60 * 1000
        const val RETRY_MS = 10L * 60 * 1000
        private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
        private const val MAX_FILE_BYTES = 512L * 1024

        /** Low byte of a Home row's `recId`: apart from the autoplay batches' sequence numbers of the same millisecond. */
        private const val HOME_REC_TAG = 0xF0

        private val json = Json { ignoreUnknownKeys = true }

        fun signatureOf(anchors: List<HomeAnchor>, tasteVersion: Long, blockedCount: Int): String =
            anchors.joinToString(",") { java.lang.Long.toHexString(it.rid) } + "|" + tasteVersion + "|" + blockedCount
    }
}
