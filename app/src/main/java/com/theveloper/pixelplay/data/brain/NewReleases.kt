package com.theveloper.pixelplay.data.brain

import android.content.Context
import android.util.AtomicFile
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.brain.source.RadioSource
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.ReleaseKind
import com.theveloper.pixelplay.data.youtube.ReleaseRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * What the brain knows about the releases of one artist the listener plays (docs/brain-spec.md, 6.3): the
 * `MPREb_` ids it has seen, and the folded titles of those releases (a "new" id whose title matches an older
 * release is a re-release, not news).
 */
@Serializable
data class ArtistReleaseState(
    val channelId: String,
    val name: String,
    val knownIds: List<String> = emptyList(),
    val knownTitles: List<String> = emptyList(),
    val checkedAtMs: Long = 0L,
)

/** One release that was new when the artist was checked, with its playable tracks. */
@Serializable
data class FoundRelease(
    val channelId: String,
    val artistName: String,
    val browseId: String,
    val title: String,
    val year: Int,
    val kind: ReleaseKind = ReleaseKind.UNKNOWN,
    val foundAtMs: Long,
    val rows: List<StoredRadioRow>,
)

/** The whole saved state of the New releases row: one small file, never the database. */
@Serializable
data class NewReleaseFile(
    val artists: List<ArtistReleaseState> = emptyList(),
    val found: List<FoundRelease> = emptyList(),
    /** When the releases lane spent a network request, newest last: the daily budget counts these. */
    val requests: List<Long> = emptyList(),
)

/** The pure rules of 6.3: what is new, and which cards of it a row shows. Unit-tested without any I/O. */
object NewReleaseRules {
    /** A carousel on an artist page holds 10 cards; at 10 it may be cut short, so the discography is read. */
    const val CAROUSEL_FULL = 10

    /** At most this many new releases of one artist are looked into per check. */
    const val MAX_NEW_PER_ARTIST = 2

    /** A found release stays on the row this long (feature 39 of 5.8: "first seen in the last 28 days"). */
    const val FRESH_MS = 28L * 24 * 60 * 60 * 1000

    /** An artist checked this recently is not asked again. */
    const val RECHECK_MS = 20L * 60 * 60 * 1000

    const val MAX_KNOWN_IDS = 300
    const val MAX_FOUND = 40
    const val CARDS = 12
    const val MAX_CARDS_PER_RELEASE = 3
    const val MAX_CARDS_PER_ARTIST = 2

    /** A row with fewer cards than this is hidden, not padded (8.1 page rules). */
    const val MIN_CARDS = 6

    const val MIN_DURATION_S = 60
    const val MAX_DURATION_S = 900

    fun titleKey(title: String): String = BrainText.fold(BrainText.cleanTitle(title).title)

    /** Release dates are year-only on YouTube Music: the year must be this one (or last, during January). */
    fun isCurrentYear(year: Int, nowYear: Int, nowMonth: Int): Boolean =
        year == nowYear || (nowMonth == 1 && year == nowYear - 1)

    class Diff(
        /** The releases to look into, newest first. Empty on a baseline. */
        val surfaced: List<ReleaseRef>,
        val knownIds: List<String>,
        val knownTitles: List<String>,
        val baseline: Boolean,
    )

    /**
     * Compares [found] with what was known. The first look at an artist is a baseline and surfaces nothing.
     * Afterwards an id not seen before is news unless its folded title matches an older release of the same
     * artist (a re-release under a new id), its year is not the current one, or an earlier card of this same
     * check already carries the title. Every id seen is remembered, news or not, so it is judged once.
     */
    fun diff(known: ArtistReleaseState?, found: List<ReleaseRef>, nowYear: Int, nowMonth: Int): Diff {
        val ids = LinkedHashSet<String>()
        known?.knownIds?.let(ids::addAll)
        val titles = LinkedHashSet<String>()
        known?.knownTitles?.let(titles::addAll)
        if (known == null) {
            for (release in found) {
                ids.add(release.browseId)
                titles.add(titleKey(release.title))
            }
            return Diff(emptyList(), capped(ids), capped(titles), baseline = true)
        }
        val surfaced = ArrayList<ReleaseRef>(2)
        val titlesAtStart = HashSet(titles)
        val seenNow = HashSet<String>()
        for (release in found) {
            if (release.browseId in known.knownIds) continue
            ids.add(release.browseId)
            val key = titleKey(release.title)
            val repeat = key.isEmpty() || key in titlesAtStart || !seenNow.add(key)
            titles.add(key)
            if (repeat || !isCurrentYear(release.year, nowYear, nowMonth)) continue
            surfaced.add(release)
        }
        return Diff(surfaced, capped(ids), capped(titles), baseline = false)
    }

    private fun capped(set: LinkedHashSet<String>): List<String> =
        if (set.size <= MAX_KNOWN_IDS) set.toList() else set.toList().takeLast(MAX_KNOWN_IDS)

    /** The tracks of a release worth a card: official audio of a sane length, with an artist. */
    fun usable(row: RadioRow): Boolean =
        row.isOfficialAudio && row.artists.isNotEmpty() &&
            (row.durationSec == 0 || row.durationSec in MIN_DURATION_S..MAX_DURATION_S)

    class Card(val release: FoundRelease, val stored: StoredRadioRow)

    /**
     * The cards of the row: the newest releases first, up to [MAX_CARDS_PER_RELEASE] tracks of each, at most
     * [MAX_CARDS_PER_ARTIST] per artist, [CARDS] in all, a recording once.
     */
    fun cards(found: List<FoundRelease>, nowMs: Long, accept: (StoredRadioRow) -> Boolean = { true }): List<Card> {
        val out = ArrayList<Card>(CARDS)
        val perArtist = HashMap<String, Int>()
        val rids = HashSet<Long>()
        val fresh = found.filter { nowMs - it.foundAtMs in 0 until FRESH_MS }.sortedByDescending { it.foundAtMs }
        for (release in fresh) {
            var taken = 0
            for (stored in release.rows) {
                if (out.size >= CARDS) return out
                if (taken >= MAX_CARDS_PER_RELEASE) break
                if ((perArtist[release.channelId] ?: 0) >= MAX_CARDS_PER_ARTIST) break
                if (stored.rid == 0L || stored.rid in rids || !accept(stored)) continue
                rids.add(stored.rid)
                perArtist[release.channelId] = (perArtist[release.channelId] ?: 0) + 1
                out.add(Card(release, stored))
                taken++
            }
        }
        return out
    }
}

/** Where the New releases state is kept; a file in the app, memory in tests. */
interface ReleaseFileStore {
    fun read(): NewReleaseFile
    fun write(file: NewReleaseFile)
}

@Singleton
class AtomicReleaseFileStore @Inject constructor(
    @ApplicationContext context: Context,
) : ReleaseFileStore {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    override fun read(): NewReleaseFile = runCatching {
        if (file.baseFile.length() !in 1..MAX_FILE_BYTES) return@runCatching NewReleaseFile()
        file.openRead().bufferedReader().use { json.decodeFromString(NewReleaseFile.serializer(), it.readText()) }
    }.getOrDefault(NewReleaseFile())

    override fun write(file: NewReleaseFile) {
        var output: FileOutputStream? = null
        try {
            output = this.file.startWrite()
            output.write(json.encodeToString(NewReleaseFile.serializer(), file).toByteArray(Charsets.UTF_8))
            this.file.finishWrite(output)
        } catch (e: Exception) {
            this.file.failWrite(output)
            Timber.d(e, "brain: the new releases could not be saved")
        }
    }

    private companion object {
        const val FILE_NAME = "brain_new_releases_v1.json"
        const val MAX_FILE_BYTES = 1024L * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What Home and the player need to play a brain row: its cards, and what each carries into the queue. */
interface PlayableBrainRow {
    val id: String
    val items: List<BrainHomeItem>
    fun tags(): Map<String, BrainItemTag>
}

/** The "New from artists you play" row (docs/brain-spec.md, 6.3, 8.1). */
class BrainReleaseRow(
    override val id: String,
    val recId: Long,
    override val items: List<BrainHomeItem>,
    /** The artist each card is about, by song id: its reason line reads "New from <artist>". */
    val artistBySongId: Map<String, String>,
) : PlayableBrainRow {
    override fun tags(): Map<String, BrainItemTag> {
        val out = HashMap<String, BrainItemTag>(items.size * 2)
        for (item in items) {
            out[item.song.id] = BrainItemTag(
                surface = Surface.nameOf(Surface.HOME_SHELF),
                surfaceId = id,
                recId = recId,
                slot = item.slot,
                arm = Arm.nameOf(Arm.NEW),
                whyKey = WhyLine.nameOf(WhyLine.NEW_RELEASE),
                whyArg = artistBySongId[item.song.id],
                primaryArtist = item.track.artists.firstOrNull()?.name,
                chosen = true,
                retrQ = item.retrQ,
                radioRank = item.radioRank,
            )
        }
        return out
    }

    companion object {
        const val ID = "brain_new_releases"
        private const val REC_TAG = 0xF2L
        fun recIdOf(builtAtMs: Long): Long = (builtAtMs shl 8) or REC_TAG
    }
}

/**
 * New releases by the artists the listener plays (docs/brain-spec.md, 6.3), found by the refresh worker and
 * saved in one small file that Home reads without the database.
 *
 * [check] asks about one artist: the artist page (cards of the albums and of the singles and EPs), the whole
 * discography when a carousel was full, the audio playlist of each release that is news (at most two per
 * artist), and nothing else. Every request goes through the [RadioSource]'s [com.theveloper.pixelplay.data.brain.source.HostBudget]
 * and is noted in the file, so the worker's daily share counts them.
 */
@Singleton
class NewReleases @Inject constructor(
    private val store: ReleaseFileStore,
    private val source: RadioSource,
    private val identity: TrackResolver,
    private val clock: BrainClock,
) {
    private val lock = Mutex()

    @Volatile
    private var memory: NewReleaseFile? = null

    private suspend fun state(): NewReleaseFile =
        memory ?: withContext(Dispatchers.IO) { store.read() }.also { memory = it }

    /** The artists to check next: those never checked first, then the longest ago. */
    suspend fun checkOrder(channelsByName: List<Pair<String, String?>>): List<Pair<String, String?>> {
        val file = state()
        val checked = HashMap<String, Long>()
        for (artist in file.artists) checked[BrainText.fold(artist.name)] = artist.checkedAtMs
        return channelsByName.sortedBy { checked[BrainText.fold(it.first)] ?: Long.MIN_VALUE }
    }

    /** The channel id learned for [artistName] in an earlier check; null when none. */
    suspend fun channelOf(artistName: String): String? {
        val key = BrainText.fold(artistName)
        return state().artists.firstOrNull { BrainText.fold(it.name) == key }?.channelId
    }

    /** Requests the releases lane spent since [sinceMs]. */
    suspend fun requestsSince(sinceMs: Long): Int = state().requests.count { it >= sinceMs }

    /** True when [channelId] was checked recently enough that asking again would only repeat the answer. */
    suspend fun isFresh(channelId: String): Boolean {
        val now = clock.nowMs()
        val known = state().artists.firstOrNull { it.channelId == channelId } ?: return false
        return now - known.checkedAtMs in 0 until NewReleaseRules.RECHECK_MS
    }

    /**
     * Looks at one artist. [libraryHas] says whether the listener already owns a row (a release's track they
     * have is not news to them). The result is [BrainRefreshRunner.Fetch.NETWORK] when a request was made,
     * [BrainRefreshRunner.Fetch.CACHED] when none was needed, [BrainRefreshRunner.Fetch.NONE] when the
     * source gave nothing.
     */
    suspend fun check(
        channelId: String,
        artistName: String,
        libraryHas: (RadioRow) -> Boolean = { false },
    ): BrainRefreshRunner.Fetch = lock.withLock {
        val now = clock.nowMs()
        val file = state()
        val known = file.artists.firstOrNull { it.channelId == channelId }
        if (known != null && now - known.checkedAtMs in 0 until NewReleaseRules.RECHECK_MS) {
            return@withLock BrainRefreshRunner.Fetch.CACHED
        }
        var spent = 0
        val page = source.artistPage(channelId)
        spent++
        if (page == null) {
            save(file.copy(requests = withRequests(file.requests, now, spent)))
            return@withLock BrainRefreshRunner.Fetch.NONE
        }
        var releases: List<ReleaseRef> = page.albums + page.singles
        if (page.albums.size >= NewReleaseRules.CAROUSEL_FULL || page.singles.size >= NewReleaseRules.CAROUSEL_FULL) {
            // A full carousel may be cut short: the whole discography is the list that can be diffed.
            spent++
            source.discography(channelId)?.let { all ->
                val byId = LinkedHashMap<String, ReleaseRef>()
                for (release in all) byId.putIfAbsent(release.browseId, release)
                // The cards of the artist page carry what the grid's may not: keep the richer one.
                for (release in releases) byId.putIfAbsent(release.browseId, release)
                releases = byId.values.toList()
            }
        }
        val (year, month) = yearAndMonth(now)
        val diff = NewReleaseRules.diff(known, releases, year, month)

        val newFound = ArrayList<FoundRelease>(NewReleaseRules.MAX_NEW_PER_ARTIST)
        val unreadable = HashSet<String>()
        for (release in diff.surfaced.take(NewReleaseRules.MAX_NEW_PER_ARTIST)) {
            spent += if (release.audioPlaylistId != null) 1 else 2
            val rows = source.releaseTracks(release)
            if (rows == null) {
                // Not read: it stays "not seen", so the next check tries it again.
                unreadable.add(release.browseId)
                continue
            }
            val stored = storedRows(rows.filter { NewReleaseRules.usable(it) && !libraryHas(it) })
            if (stored.isNotEmpty()) {
                newFound.add(
                    FoundRelease(
                        channelId = channelId, artistName = artistName, browseId = release.browseId,
                        title = release.title, year = release.year, kind = release.kind, foundAtMs = now, rows = stored,
                    )
                )
            }
        }
        // A release that could not be read is forgotten whole, its title too: tried again, it must not be taken
        // for a re-release of itself.
        val unreadableTitles = diff.surfaced.filter { it.browseId in unreadable }.mapTo(HashSet()) { NewReleaseRules.titleKey(it.title) }
        val nextKnown = ArtistReleaseState(
            channelId = channelId,
            name = artistName,
            knownIds = diff.knownIds.filterNot { it in unreadable },
            knownTitles = diff.knownTitles.filterNot { it in unreadableTitles },
            checkedAtMs = now,
        )
        val artists = file.artists.filterNot { it.channelId == channelId } + nextKnown
        val kept = file.found.filter { now - it.foundAtMs in 0 until NewReleaseRules.FRESH_MS && newFound.none { n -> n.browseId == it.browseId } }
        val found = (newFound + kept).sortedByDescending { it.foundAtMs }.take(NewReleaseRules.MAX_FOUND)
        save(NewReleaseFile(artists, found, withRequests(file.requests, now, spent)))
        BrainRefreshRunner.Fetch.NETWORK
    }

    private suspend fun storedRows(rows: List<RadioRow>): List<StoredRadioRow> {
        if (rows.isEmpty()) return emptyList()
        val rids = identity.resolveTracks(
            rows.map { row ->
                TrackRequest(
                    Ids.TrackKeys(
                        videoId = row.videoId,
                        nameKey = row.artists.firstOrNull()?.let { Ids.nameKey(it.name, row.title) },
                    ),
                    row.durationSec * 1000,
                )
            }
        )
        val out = ArrayList<StoredRadioRow>(rows.size)
        for (i in rows.indices) if (rids[i] != 0L) out.add(StoredRadioRow(rows[i], rids[i]))
        return out
    }

    private suspend fun save(next: NewReleaseFile) {
        memory = next
        withContext(Dispatchers.IO) { store.write(next) }
    }

    private fun withRequests(old: List<Long>, now: Long, spent: Int): List<Long> {
        val recent = old.filter { now - it in 0 until DAY_MS }
        return recent + List(spent) { now }
    }

    private fun yearAndMonth(nowMs: Long): Pair<Int, Int> {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        return date.year to date.monthValue
    }

    /**
     * The row for Home, from the saved file only. Cards the listener has since muted, blocked, or already
     * has are left out; null when fewer than [NewReleaseRules.MIN_CARDS] are left.
     */
    suspend fun row(blocked: BlockedArtistIndex, adjustments: TasteAdjustments, library: LibraryIndex? = null): BrainReleaseRow? {
        val now = clock.nowMs()
        val file = state()
        if (file.found.isEmpty()) return null
        val cards = NewReleaseRules.cards(file.found, now) { stored ->
            val song = EngineTrack.from(stored.row, stored.rid).toSong()
            !blocked.isBlocked(song) && !adjustments.excludes(song) && !adjustments.blocks(song) &&
                (library == null || library.match(stored.row) == null)
        }
        if (cards.size < NewReleaseRules.MIN_CARDS) return null
        val items = ArrayList<BrainHomeItem>(cards.size)
        val artists = HashMap<String, String>(cards.size * 2)
        for ((slot, card) in cards.withIndex()) {
            val track = EngineTrack.from(card.stored.row, card.stored.rid)
            val song = track.toSong()
            items.add(BrainHomeItem(track, song, Arm.NEW, slot, retrQ = -1, radioRank = -1))
            artists[song.id] = card.release.artistName
        }
        val builtAt = cards.maxOf { it.release.foundAtMs }
        return BrainReleaseRow(BrainReleaseRow.ID, BrainReleaseRow.recIdOf(builtAt), items, artists)
    }

    /**
     * The cards of "New from artists you play" with their releases, from the saved file only (stage 2b: Just Dropped and
     * the Home shelf read the same cards). Muted, blocked and already-owned music is left out.
     */
    suspend fun cards(blocked: BlockedArtistIndex, adjustments: TasteAdjustments, library: LibraryIndex? = null): List<NewReleaseRules.Card> {
        val now = clock.nowMs()
        val file = state()
        if (file.found.isEmpty()) return emptyList()
        return NewReleaseRules.cards(file.found, now) { stored ->
            val song = EngineTrack.from(stored.row, stored.rid).toSong()
            !blocked.isBlocked(song) && !adjustments.excludes(song) && !adjustments.blocks(song) &&
                (library == null || library.match(stored.row) == null)
        }
    }

    /** Every card of [row] must be in `playable` before its media item reaches the player (7.2). */
    fun songsOf(row: BrainReleaseRow): List<Song> = row.items.map { it.song }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
