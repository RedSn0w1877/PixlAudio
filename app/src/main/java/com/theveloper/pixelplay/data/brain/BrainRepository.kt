package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.brain.source.FetchedRadio
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.source.RadioSource
import com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase
import com.theveloper.pixelplay.data.brain.store.RadioListDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.RadioRowCodec
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsers
import com.theveloper.pixelplay.data.youtube.RadioChip
import com.theveloper.pixelplay.data.youtube.RadioRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** A track the brain can ask a radio for. */
data class RadioSeed(
    /** The media id the track plays under. */
    val mediaId: String,
    /** The YouTube audio id to ask with. */
    val videoId: String,
    val title: String,
    /** ONE artist name: the primary one, never a joined display string. */
    val primaryArtist: String,
    val durationMs: Long,
    /** A real Spotify track id; null for a track that only has a synthetic id. */
    val spotifyId: String? = null,
)

/** A radio out of the cache, or just fetched and stored. */
class StoredRadio(
    val seedRid: Long,
    /** One of `RadioListEntity.KIND_*`: which fallback produced the rows. */
    val kind: Int,
    /** Seed excluded, deduplicated by recording, in the source's own order. */
    val rows: List<StoredRadioRow>,
    val fromCache: Boolean,
)

/**
 * The brain's orchestration (docs/brain-spec.md, 2.1): in stage 1a, the radio cache. It answers "what
 * is related to this track" from `radio_list` when it can and from YouTube Music when it may, applies
 * the junk detector and its fallbacks (3.3, rule 6), resolves every row to a recording id and stores
 * the parsed rows.
 *
 * Nothing here runs at app start. Every call is off the main thread and bounded: at most one first
 * page, one retry and the fallbacks of one seed per call, all under the YouTube budget.
 */
@Singleton
class BrainRepository @Inject constructor(
    private val radioLists: RadioListDao,
    private val identity: TrackResolver,
    private val radioSource: RadioSource,
    private val clock: BrainClock,
    /** Every stored radio also becomes edges of the persisted graph (3.6); the default writes none. */
    private val edges: RadioEdges = RadioEdges.None,
) {
    // One seed is fetched once: a second caller for the same seed waits and then reads the cache.
    private val seedLocks = Array(8) { Mutex() }

    // Seeds whose radio came back with no rows at all, by rid, with when. Memory only: see [radioForLocked].
    private val emptyAnswerAtMs = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Related music for [seed]. With [allowNetwork] false (offline, the YouTube source switched off) only
     * the cache answers, however old. Null means "nothing known": the caller falls back to the library.
     */
    suspend fun radioFor(seed: RadioSeed, allowNetwork: Boolean, continuations: Int = 0): StoredRadio? {
        val seedRid = try {
            identity.resolveTrack(
                Ids.TrackKeys(
                    spotifyId = seed.spotifyId,
                    videoId = seed.videoId,
                    nameKey = Ids.nameKey(seed.primaryArtist, seed.title),
                ),
                seed.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: could not resolve the seed")
            return null
        }
        if (seedRid == 0L) return null

        val lock = seedLocks[java.lang.Long.remainderUnsigned(seedRid, seedLocks.size.toLong()).toInt()]
        return lock.withLock {
            try {
                radioForLocked(seed, seedRid, allowNetwork, continuations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: radio lookup failed")
                null
            }
        }
    }

    private suspend fun radioForLocked(
        seed: RadioSeed,
        seedRid: Long,
        allowNetwork: Boolean,
        continuations: Int,
    ): StoredRadio? {
        val now = clock.nowMs()
        // A request for continuation pages is stored beside the first-page list, under its own chip key,
        // so it is fetched once and then answered from the cache like any other list.
        val songChip = if (continuations > 0) DEEP_CHIP else ""
        var stale: RadioListEntity? = null
        val songMarker = radioLists.get(seedRid, RadioListEntity.KIND_SONG, "")
        for (kind in KINDS) {
            val chip = if (kind == RadioListEntity.KIND_SONG) songChip else ""
            val cached = radioLists.get(seedRid, kind, chip) ?: continue
            if (!cached.healthy) continue
            val age = now - cached.fetchedAt
            if (age in 0 until CACHE_TTL_MS) {
                radioLists.touch(seedRid, kind, chip, now)
                return StoredRadio(seedRid, kind, RadioRowCodec.decodeRows(cached.rowsBlob), fromCache = true)
            }
            if (stale == null) stale = cached
        }
        // Offline or over budget, a first-page list still answers a request for more.
        if (stale == null && songChip.isNotEmpty() && songMarker != null && songMarker.healthy) stale = songMarker
        fun staleOrNull(): StoredRadio? = stale?.let {
            StoredRadio(seedRid, it.kind, RadioRowCodec.decodeRows(it.rowsBlob), fromCache = true)
        }

        if (!allowNetwork) return staleOrNull()
        // A seed whose radio was junk is not asked again for a day.
        if (songMarker != null && !songMarker.healthy && now - songMarker.fetchedAt in 0 until JUNK_RETRY_MS) {
            return staleOrNull()
        }
        if (radioLists.fetchedSince(now - HostBudget.DAY_MS) >= HostBudget.DAILY_LIMIT) return staleOrNull()
        emptyAnswerAtMs[seedRid]?.let { at ->
            if (now - at in 0 until EMPTY_ANSWER_RETRY_MS) return staleOrNull()
        }

        val fetched = radioSource.songRadio(seed.videoId, continuations)
        if (fetched != null && !isJunk(fetched, seed)) {
            return store(seedRid, RadioListEntity.KIND_SONG, withoutSeed(fetched.rows, seed), fetched.chips, now, songChip)
        }
        if (fetched == null) return staleOrNull() // transport failure or no budget: nothing learned
        if (fetched.rows.isEmpty() && fetched.relatedBrowseId == null) {
            // An answer with nothing of a radio page in it (no rows, no Related tab): a glitch
            // upstream or a bot wall, not a junk radio. Nothing was learned about the seed, so the
            // list already stored stays and the seed is not marked as junk for a day. It is only left
            // alone for a while, in memory. (A page that names its Related tab but has no rows is a
            // real page: that one takes the junk path below.)
            if (emptyAnswerAtMs.size > EMPTY_ANSWER_LIMIT) emptyAnswerAtMs.clear()
            emptyAnswerAtMs[seedRid] = now
            return staleOrNull()
        }

        // Junk (3.3, rule 6): remember it, then the Related tab, another audio id, the artist radio.
        radioLists.upsert(
            RadioListEntity(
                seedRid = seedRid, kind = RadioListEntity.KIND_SONG, chip = "", fetchedAt = now, lastUsedAt = now,
                healthy = false, rowsBlob = EMPTY, chipsBlob = EMPTY,
            )
        )

        fetched.relatedBrowseId?.let { browseId ->
            val related = radioSource.related(browseId)
            if (related != null) {
                val same = HashSet<String>()
                related.otherPerformances.mapTo(same) { it.videoId }
                val rows = related.youMightAlsoLike.filter { it.videoId !in same }
                if (rows.size >= MIN_FALLBACK_ROWS) {
                    return store(seedRid, RadioListEntity.KIND_RELATED, withoutSeed(rows, seed), emptyList(), now)
                }
            }
        }

        identity.otherVideoIds(seedRid, seed.videoId).firstOrNull()?.let { otherId ->
            val other = radioSource.songRadio(otherId, continuations)
            if (other != null && !isJunk(other, seed.copy(videoId = otherId))) {
                return store(seedRid, RadioListEntity.KIND_SONG, withoutSeed(other.rows, seed), other.chips, now, songChip)
            }
        }

        val seedRow = fetched.rows.firstOrNull { it.videoId == seed.videoId } ?: fetched.rows.firstOrNull()
        val channelId = seedRow?.artists?.firstOrNull { it.channelId != null }?.channelId
        if (channelId != null) {
            val endpoint = radioSource.artistPage(channelId)?.radio
            val artistRadio = endpoint?.let { radioSource.artistRadio(it) }
            if (artistRadio != null &&
                !InnerTubeMusicParsers.isJunkRadio(artistRadio.rows, endpoint.videoId, setOf(channelId))
            ) {
                return store(seedRid, RadioListEntity.KIND_ARTIST, withoutSeed(artistRadio.rows, seed), emptyList(), now)
            }
        }
        return staleOrNull()
    }

    /**
     * The radio of the artist of [seed] (8.3, 6.2 "Station from an artist"): the artist page's top songs
     * followed by the artist radio the page's own button starts. Null when nothing is known and the
     * network may not be asked, or the artist has no channel the brain can find.
     *
     * Costs, the first time: at most one `next` to learn the channel id (only when no cached radio row
     * names the artist), one `browse` and one `next`. After that it is the cache, for two weeks.
     */
    suspend fun artistRadioFor(seed: RadioSeed, allowNetwork: Boolean): StoredRadio? {
        val seedRid = try {
            identity.resolveTrack(
                Ids.TrackKeys(
                    spotifyId = seed.spotifyId,
                    videoId = seed.videoId,
                    nameKey = Ids.nameKey(seed.primaryArtist, seed.title),
                ),
                seed.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: could not resolve the seed")
            return null
        }
        if (seedRid == 0L) return null
        val lock = seedLocks[java.lang.Long.remainderUnsigned(seedRid, seedLocks.size.toLong()).toInt()]
        return lock.withLock {
            try {
                artistRadioForLocked(seed, seedRid, allowNetwork)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: artist radio lookup failed")
                null
            }
        }
    }

    private suspend fun artistRadioForLocked(seed: RadioSeed, seedRid: Long, allowNetwork: Boolean): StoredRadio? {
        val now = clock.nowMs()
        val cached = radioLists.get(seedRid, RadioListEntity.KIND_ARTIST, ARTIST_STATION_CHIP)
        fun cachedOrNull(): StoredRadio? = cached?.takeIf { it.healthy }?.let {
            StoredRadio(seedRid, RadioListEntity.KIND_ARTIST, RadioRowCodec.decodeRows(it.rowsBlob), fromCache = true)
        }
        if (cached != null && cached.healthy && now - cached.fetchedAt in 0 until CACHE_TTL_MS) {
            radioLists.touch(seedRid, RadioListEntity.KIND_ARTIST, ARTIST_STATION_CHIP, now)
            return cachedOrNull()
        }
        if (!allowNetwork) return cachedOrNull()
        // An artist with no radio is not asked again for a day.
        if (cached != null && !cached.healthy && now - cached.fetchedAt in 0 until JUNK_RETRY_MS) return null
        if (radioLists.fetchedSince(now - HostBudget.DAY_MS) >= HostBudget.DAILY_LIMIT) return cachedOrNull()

        val channelId = channelIdFor(seed, seedRid) ?: return cachedOrNull()
        val page = radioSource.artistPage(channelId) ?: return cachedOrNull()
        val radio = page.radio?.let { radioSource.artistRadio(it) }
        val rows = ArrayList<RadioRow>(page.topSongs.size + (radio?.rows?.size ?: 0))
        val seenVideoIds = HashSet<String>()
        for (row in page.topSongs) if (seenVideoIds.add(row.videoId)) rows.add(row)
        if (radio != null && !InnerTubeMusicParsers.isJunkRadio(radio.rows, page.radio.videoId, setOf(channelId))) {
            for (row in radio.rows) if (seenVideoIds.add(row.videoId)) rows.add(row)
        }
        if (rows.size < MIN_FALLBACK_ROWS) {
            radioLists.upsert(
                RadioListEntity(
                    seedRid = seedRid, kind = RadioListEntity.KIND_ARTIST, chip = ARTIST_STATION_CHIP, fetchedAt = now,
                    lastUsedAt = now, healthy = false, rowsBlob = EMPTY, chipsBlob = EMPTY,
                )
            )
            return null
        }
        return store(seedRid, RadioListEntity.KIND_ARTIST, withoutSeed(rows, seed), emptyList(), now, ARTIST_STATION_CHIP)
    }

    /**
     * The `UC...` id of [seed]'s primary artist: from a cached radio row by that artist, else from the
     * seed's own row of its radio (one `next`).
     */
    private suspend fun channelIdFor(seed: RadioSeed, seedRid: Long): String? {
        val artistKey = BrainText.fold(seed.primaryArtist)
        if (artistKey.isEmpty()) return null
        fun channelIn(rows: List<RadioRow>): String? {
            for (row in rows) {
                val first = row.artists.firstOrNull() ?: continue
                if (first.channelId != null && BrainText.fold(first.name) == artistKey) return first.channelId
            }
            return null
        }
        for (kind in KINDS) {
            val cached = radioLists.get(seedRid, kind, "") ?: continue
            if (!cached.healthy) continue
            channelIn(RadioRowCodec.decodeRows(cached.rowsBlob).map { it.row })?.let { return it }
        }
        val fetched = radioSource.songRadio(seed.videoId, 0) ?: return null
        return channelIn(fetched.rows)
            ?: fetched.rows.firstOrNull { it.videoId == seed.videoId }?.artists?.firstOrNull { it.channelId != null }?.channelId
    }

    private fun isJunk(fetched: FetchedRadio, seed: RadioSeed): Boolean {
        // A library track can be matched to a music video, and the radio of a music video is music
        // videos. Stage 1 only plays official audio, so such a radio has nothing to offer: it takes the
        // junk path, whose first stop (the Related tab) lists official-audio songs.
        var officialAudio = 0
        for (row in fetched.rows) if (row.isOfficialAudio) officialAudio++
        if (officialAudio < MIN_OFFICIAL_AUDIO_ROWS) return true
        return InnerTubeMusicParsers.isJunkRadio(
            rows = fetched.rows,
            seedVideoId = seed.videoId,
            seedArtistIds = emptySet(),
            seedArtistNames = listOf(seed.primaryArtist),
        )
    }

    /** The seed itself is the first row of its own radio; it is not a candidate. */
    private fun withoutSeed(rows: List<RadioRow>, seed: RadioSeed): List<RadioRow> {
        val seedKey = Ids.nameKey(seed.primaryArtist, seed.title)
        return rows.filter { row ->
            row.videoId != seed.videoId &&
                (seedKey == null || seedKey != row.artists.firstOrNull()?.let { Ids.nameKey(it.name, row.title) })
        }
    }

    private suspend fun store(
        seedRid: Long,
        kind: Int,
        rows: List<RadioRow>,
        chips: List<RadioChip>,
        now: Long,
        chip: String = "",
    ): StoredRadio {
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
        // Pages are merged by video id by the pager; here by recording, because one recording shows up
        // under several audio ids and a radio mixes them.
        val seen = HashSet<Long>(rows.size * 2)
        seen.add(seedRid)
        val stored = ArrayList<StoredRadioRow>(rows.size)
        for (i in rows.indices) {
            val rid = rids[i]
            if (rid == 0L || !seen.add(rid)) continue
            stored.add(StoredRadioRow(rows[i], rid))
        }
        radioLists.upsert(
            RadioListEntity(
                seedRid = seedRid, kind = kind, chip = chip, fetchedAt = now, lastUsedAt = now, healthy = true,
                rowsBlob = RadioRowCodec.encodeRows(stored), chipsBlob = RadioRowCodec.encodeChips(chips),
            )
        )
        if (radioLists.count() > MusicBrainDatabase.MAX_RADIO_LISTS) radioLists.trimTo(MusicBrainDatabase.MAX_RADIO_LISTS)
        // An artist station's list is the artist's top songs, not a ranking by closeness to the seed:
        // it is not an edge of the graph.
        if (chip != ARTIST_STATION_CHIP) edges.record(seedRid, kind, LongArray(stored.size) { stored[it].rid }, now)
        return StoredRadio(seedRid, kind, stored, fromCache = false)
    }

    /**
     * The list a mix reads for the library track [seedRid]: what the cache holds, however old, never
     * the network (a mix is built in the background from what the worker and the listening left
     * behind). A song radio is preferred over the Related tab; null when neither is stored healthy.
     */
    suspend fun cachedRadio(seedRid: Long): StoredRadio? {
        for (kind in intArrayOf(RadioListEntity.KIND_SONG, RadioListEntity.KIND_RELATED)) {
            val row = radioLists.get(seedRid, kind, "")?.takeIf { it.healthy } ?: continue
            val rows = RadioRowCodec.decodeRows(row.rowsBlob)
            if (rows.isNotEmpty()) return StoredRadio(seedRid, kind, rows, fromCache = true)
        }
        return null
    }

    /** The artist top songs and radio the artist station of [seedRid] cached, however old; empty when none. */
    suspend fun cachedArtistRadio(seedRid: Long): List<StoredRadioRow> {
        val row = radioLists.get(seedRid, RadioListEntity.KIND_ARTIST, ARTIST_STATION_CHIP)?.takeIf { it.healthy } ?: return emptyList()
        return RadioRowCodec.decodeRows(row.rowsBlob)
    }

    companion object {
        /** A radio is good for two weeks: related music does not change faster than that. */
        const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val JUNK_RETRY_MS = 24L * 60 * 60 * 1000

        /** A seed whose radio came back empty (no rows at all) is not asked again for this long. */
        const val EMPTY_ANSWER_RETRY_MS = 30L * 60 * 1000
        const val EMPTY_ANSWER_LIMIT = 256
        const val MIN_FALLBACK_ROWS = 5

        /** A song radio with fewer official-audio rows than this is treated as junk. */
        const val MIN_OFFICIAL_AUDIO_ROWS = 10

        /** The `radio_list.chip` value of a song radio stored with its continuation pages. */
        const val DEEP_CHIP = "+pages"

        /** The `radio_list.chip` value of an artist station's list (top songs, then the artist radio). */
        const val ARTIST_STATION_CHIP = "+artist"

        private val KINDS = intArrayOf(RadioListEntity.KIND_SONG, RadioListEntity.KIND_RELATED, RadioListEntity.KIND_ARTIST)
        private val EMPTY = ByteArray(0)

        /** The folded primary artist of a radio row; "" when the row names nobody. */
        fun artistKeyOf(row: RadioRow): String = row.artists.firstOrNull()?.name?.let(BrainText::fold).orEmpty()
    }
}

/** The wall clock, as a seam for tests. */
fun interface BrainClock {
    fun nowMs(): Long
}
