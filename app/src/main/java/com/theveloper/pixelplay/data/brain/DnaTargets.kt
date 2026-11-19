package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.ChipLabels
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.SpotifyFactDao
import com.theveloper.pixelplay.data.brain.store.SpotifyFactEntity
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.history.ListeningHistoryDao
import com.theveloper.pixelplay.data.history.SeedKind
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Who the DNA lanes work on (docs/brain-spec.md, 3.7): lane 3 the tracks the listener plays most, lane 4 the rest of the library,
 * lane 5 the rows whose tags or popularity have expired. Only the library is enriched in this milestone: the graph nodes outside it
 * wait for the milestone that shows them.
 *
 * A target carries what the app already knows, so a source is only asked what it can add: the ISRC (from the Spotify sync), the
 * Spotify artist genres already synced with the top and followed artists, the genre chips of the track's own radio, and the year
 * and play count of its cached YouTube Music row. With "Use my Spotify music to shape picks" off, no Spotify fact or genre is used.
 */
@Singleton
class DnaTargets @Inject constructor(
    private val musicRepository: dagger.Lazy<MusicRepository>,
    private val identity: BrainIdentity,
    private val aliasDao: AliasDao,
    private val brain: BrainEngine,
    private val spotifyFacts: SpotifyFactDao,
    private val historyDao: dagger.Lazy<ListeningHistoryDao>,
    private val spotifyDao: dagger.Lazy<SpotifyDao>,
    private val tracks: TrackDnaDao,
    private val artists: ArtistDnaDao,
    private val repository: BrainRepository,
    private val switch: BrainSwitch,
    private val clock: BrainClock,
) {
    /** Lane 3: the tracks played most (decayed engagement), without DNA yet. */
    suspend fun mostPlayed(limit: Int): List<DnaTarget> = guarded {
        val now = clock.nowMs()
        val ranked = brain.withState { state ->
            val keys = state.affinity.sortedKeys(Family.TRACK)
            val weights = HashMap<Long, Double>(keys.size * 2)
            for (k in keys) weights[k] = Affinity.weight(state.affinity, Family.TRACK, k, now)
            keys.sortedWith { a, b ->
                val c = weights[b]!!.compareTo(weights[a]!!)
                if (c != 0) c else a.compareTo(b)
            }.take(RANKED_WINDOW)
        }
        if (ranked.isEmpty()) return@guarded emptyList()
        // Whoever has a row already is skipped by id, cheaply, so a later round reaches further down the list.
        val haveRow = HashSet<Long>()
        for (chunk in ranked.chunked(CHUNK)) tracks.getAll(chunk).forEach { haveRow.add(it.rid) }
        val library = libraryBySpotifyId()
        val picked = ArrayList<Pair<Long, Song>>()
        withContext(Dispatchers.IO) {
            for (rid in ranked) {
                if (picked.size >= limit) break
                if (rid in haveRow) continue
                val spotifyId = aliasDao.trackKeys(rid).firstOrNull { it.startsWith("s:") }?.substring(2) ?: continue
                val song = library[spotifyId] ?: continue
                picked.add(rid to song)
            }
        }
        val missing = picked
        build(missing.map { it.second }, missing.map { it.first }.toLongArray())
    }

    /** Lane 4: the rest of the library, in the library's own order, without DNA yet. */
    suspend fun restOfLibrary(limit: Int): List<DnaTarget> = guarded {
        val library = libraryBySpotifyId().values.toList()
        if (library.isEmpty()) return@guarded emptyList()
        val known = knownSpotifyIds(library.map { it.spotifyId!! })
        val missing = library.filter { it.spotifyId!! !in known }.take(limit)
        if (missing.isEmpty()) return@guarded emptyList()
        val requests = ArrayList<TrackRequest>(missing.size)
        val kept = ArrayList<Song>(missing.size)
        for (song in missing) {
            val f = LibraryTrackCatalog.factsOf(song) ?: continue
            requests.add(TrackRequest(f.keys, song.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
            kept.add(song)
        }
        val rids = identity.resolveTracks(requests)
        build(kept, rids)
    }

    /** Lane 5: rows whose tags (90 days) or popularity (7 days) have expired, the stalest first. */
    suspend fun expired(limit: Int): List<DnaTarget> = guarded {
        val now = clock.nowMs()
        val rows = tracks.expired(now - DnaEnricher.TAGS_TTL_MS, now - DnaEnricher.POPULARITY_TTL_MS, now - RETRY_UNASKED_MS, limit)
        if (rows.isEmpty()) return@guarded emptyList()
        val aids = rows.mapNotNull { it.artistIds.substringBefore(',').toLongOrNull() }.distinct()
        val names = artists.getAll(aids).associate { it.aid to it.name }
        val shapes = switch.spotifyShapesPicks()
        val genres = if (shapes) spotifyGenresByArtistId() else emptyMap()
        val facts = if (shapes) spotifyFacts.get(rows.mapNotNull { it.spotifyId }).associateBy { it.spotifyId } else emptyMap()
        rows.map { r ->
            val aid = r.artistIds.substringBefore(',').toLongOrNull() ?: 0L
            val fact = r.spotifyId?.let { facts[it] }
            val artistIdList = fact?.artistIds?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            targetOf(
                rid = r.rid, aid = aid, spotifyId = r.spotifyId, isrc = r.isrc, title = r.title,
                artist = names[aid] ?: r.artistDisplay.substringBefore(", "), artistDisplay = r.artistDisplay, durationMs = r.durationMs,
                albumName = null, fact = fact, spotifyGenres = artistIdList.firstOrNull()?.let { genres[it] }.orEmpty(), songYear = 0, spotifyOff = !shapes,
            )
        }
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun libraryBySpotifyId(): Map<String, Song> {
        val all = musicRepository.get().getAllSongsOnce()
        val out = LinkedHashMap<String, Song>(all.size * 2)
        for (s in all) {
            val id = s.spotifyId ?: continue
            if (LibraryTrackCatalog.isSyntheticId(id)) continue
            out.putIfAbsent(id, s)
        }
        return out
    }

    private suspend fun knownSpotifyIds(ids: List<String>): Set<String> {
        val out = HashSet<String>()
        for (chunk in ids.chunked(CHUNK)) out.addAll(tracks.knownSpotifyIds(chunk))
        return out
    }

    private suspend fun spotifyGenresByArtistId(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        val seeds = runCatching { historyDao.get().allSeeds() }.getOrDefault(emptyList())
        for (seed in seeds) {
            val kind = SeedKind.fromKey(seed.kind) ?: continue
            if (kind != SeedKind.TOP_ARTIST && kind != SeedKind.FOLLOWED_ARTIST) continue
            val id = seed.spotifyId ?: continue
            val list = seed.genres?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (list.isNotEmpty()) out.putIfAbsent(id, list)
        }
        return out
    }

    private suspend fun build(songs: List<Song>, rids: LongArray): List<DnaTarget> {
        if (songs.isEmpty()) return emptyList()
        val shapes = switch.spotifyShapesPicks()
        val ids = songs.mapNotNull { it.spotifyId }
        val facts: Map<String, SpotifyFactEntity> = if (shapes) {
            ids.chunked(CHUNK).flatMap { spotifyFacts.get(it) }.associateBy { it.spotifyId }
        } else emptyMap()
        val isrcs = HashMap<String, String>()
        for (f in facts.values) f.isrc?.let { isrcs[f.spotifyId] = it }
        val missingIsrc = ids.filter { it !in isrcs }
        if (missingIsrc.isNotEmpty()) {
            runCatching { missingIsrc.chunked(CHUNK).forEach { c -> spotifyDao.get().getIsrcs(c).forEach { isrcs.putIfAbsent(it.spotifyId, it.isrc) } } }
        }
        val genres = if (shapes) spotifyGenresByArtistId() else emptyMap()
        val aids = identity.resolveArtistNames(songs.map { LibraryIndex.primaryArtistName(it) })
        val out = ArrayList<DnaTarget>(songs.size)
        for (i in songs.indices) {
            val song = songs[i]
            val rid = rids.getOrElse(i) { 0L }
            if (rid == 0L) continue
            val fact = song.spotifyId?.let { facts[it] }
            val primaryArtistId = fact?.artistIds?.substringBefore(',')?.takeIf { it.isNotBlank() }
            val g = ArrayList<String>()
            primaryArtistId?.let { genres[it] }?.let(g::addAll)
            if (shapes && !song.genre.isNullOrBlank()) g.add(song.genre!!)
            out.add(
                targetOf(
                    rid = rid, aid = aids[i], spotifyId = song.spotifyId, isrc = song.spotifyId?.let { isrcs[it] },
                    title = song.title, artist = LibraryIndex.primaryArtistName(song), artistDisplay = song.displayArtist,
                    durationMs = song.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), albumName = song.album,
                    fact = fact, spotifyGenres = g.distinct(), songYear = if (shapes) song.year else 0, spotifyOff = !shapes,
                ),
            )
        }
        return out
    }

    private suspend fun targetOf(
        rid: Long,
        aid: Long,
        spotifyId: String?,
        isrc: String?,
        title: String,
        artist: String,
        artistDisplay: String,
        durationMs: Int,
        albumName: String?,
        fact: SpotifyFactEntity?,
        spotifyGenres: List<String>,
        songYear: Int,
        spotifyOff: Boolean = false,
    ): DnaTarget {
        val chips = runCatching {
            repository.chipTagsOf(rid).filter { it.kind == ChipLabels.Kind.GENRE }.map { it.key }
        }.getOrDefault(emptyList())
        val row = runCatching { repository.cachedSeedRow(rid) }.getOrNull()
        return DnaTarget(
            rid = rid, aid = aid, spotifyId = spotifyId, isrc = isrc, title = title, artist = artist, artistDisplay = artistDisplay,
            durationMs = durationMs, albumName = albumName,
            spotifyArtistIds = fact?.artistIds?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            spotifyReleaseDate = fact?.releaseDate ?: songYear.takeIf { it in 1900..2200 }?.toString(),
            spotifyExplicit = fact?.explicit,
            spotifyGenres = spotifyGenres,
            chipGenres = chips,
            ytYear = row?.year ?: 0,
            ytPlays = row?.playCount ?: 0L,
            ytExplicit = row?.explicit,
            videoId = row?.videoId,
            spotifyOff = spotifyOff,
        )
    }

    private suspend fun guarded(block: suspend () -> List<DnaTarget>): List<DnaTarget> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: DNA targets could not be listed")
            emptyList()
        }

    private companion object {
        const val CHUNK = 800

        /** A row some of whose sources have not answered is looked at again after a day. */
        const val RETRY_UNASKED_MS = 24L * 60 * 60 * 1000

        /** How far down the most played list lane 3 reads for tracks without DNA. */
        const val RANKED_WINDOW = 1_000
    }
}
