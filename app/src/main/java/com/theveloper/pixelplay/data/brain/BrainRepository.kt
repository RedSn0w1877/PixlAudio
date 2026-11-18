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
) {
    // One seed is fetched once: a second caller for the same seed waits and then reads the cache.
    private val seedLocks = Array(8) { Mutex() }

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

        val fetched = radioSource.songRadio(seed.videoId, continuations)
        if (fetched != null && !isJunk(fetched, seed)) {
            return store(seedRid, RadioListEntity.KIND_SONG, withoutSeed(fetched.rows, seed), fetched.chips, now, songChip)
        }
        if (fetched == null) return staleOrNull() // transport failure or no budget: nothing learned

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

    private fun isJunk(fetched: FetchedRadio, seed: RadioSeed): Boolean =
        InnerTubeMusicParsers.isJunkRadio(
            rows = fetched.rows,
            seedVideoId = seed.videoId,
            seedArtistIds = emptySet(),
            seedArtistNames = listOf(seed.primaryArtist),
        )

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
        return StoredRadio(seedRid, kind, stored, fromCache = false)
    }

    companion object {
        /** A radio is good for two weeks: related music does not change faster than that. */
        const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val JUNK_RETRY_MS = 24L * 60 * 60 * 1000
        const val MIN_FALLBACK_ROWS = 5

        /** The `radio_list.chip` value of a song radio stored with its continuation pages. */
        const val DEEP_CHIP = "+pages"

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
