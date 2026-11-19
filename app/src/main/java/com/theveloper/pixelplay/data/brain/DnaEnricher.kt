package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.data.brain.source.DeezerAlbum
import com.theveloper.pixelplay.data.brain.source.DeezerSource
import com.theveloper.pixelplay.data.brain.source.DeezerTrack
import com.theveloper.pixelplay.data.brain.source.ITunesSource
import com.theveloper.pixelplay.data.brain.source.ListenBrainzSource
import com.theveloper.pixelplay.data.brain.source.LbPopularity
import com.theveloper.pixelplay.data.brain.source.LbRecording
import com.theveloper.pixelplay.data.brain.source.Lookup
import com.theveloper.pixelplay.data.brain.source.MbArtist
import com.theveloper.pixelplay.data.brain.source.MbRecordingHit
import com.theveloper.pixelplay.data.brain.source.MusicBrainzSource
import com.theveloper.pixelplay.data.brain.source.TrackMatch
import com.theveloper.pixelplay.data.brain.source.WdArtist
import com.theveloper.pixelplay.data.brain.source.WikidataSource
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.ArtistDnaEntity
import com.theveloper.pixelplay.data.brain.store.DnaSource
import com.theveloper.pixelplay.data.brain.store.SourceMissDao
import com.theveloper.pixelplay.data.brain.store.SourceMissEntity
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * The enrichment lanes 3 to 5 of docs/brain-spec.md, 3.7: for each track, ask the open-data sources in the fallback order of 3.3,
 * keep what they say in `track_dna` and `artist_dna`, and never ask twice for what is fresh.
 *
 *  - **Order.** Deezer (by ISRC, else by name), MusicBrainz (the recording and its first year, then the artist's genres), iTunes
 *    (by name), then one ListenBrainz batch for the recordings found and one Wikidata query for their artists. The Spotify artist
 *    genres and the radio's genre chips need no request and are read from [DnaTarget].
 *  - **Fresh is never asked again.** Ids, year and tempo never expire; tags 90 days; popularity 7 days; a source that had nothing
 *    is not asked again for 14 days, doubling to 120 (`source_miss`).
 *  - **One door.** Every request goes through `KnowledgeHttp`: the source's own off switch (local and remote), its daily budget
 *    and ledger, its spacing and its back-off. A source that cannot be asked is skipped for this track and tried again next run.
 *  - **Stoppable.** [gate] is asked before each track and before each batch call; false stops the run and what was gathered is
 *    still saved.
 */
@Singleton
class DnaEnricher @Inject constructor(
    private val deezer: DeezerSource,
    private val musicBrainz: MusicBrainzSource,
    private val listenBrainz: ListenBrainzSource,
    private val wikidata: WikidataSource,
    private val itunes: ITunesSource,
    private val tracks: TrackDnaDao,
    private val artists: ArtistDnaDao,
    private val misses: SourceMissDao,
    private val atlasProvider: AtlasProvider,
    private val clock: BrainClock,
) {
    class Report(val tracks: Int, val stopped: Boolean, val noAtlas: Boolean = false)

    private class State(val target: DnaTarget, val prior: TrackDnaEntity?, val priorArtist: ArtistDnaEntity?) {
        var tried = 0
        var deezerTrack: DeezerTrack? = null
        var deezerAlbum: DeezerAlbum? = null
        var mb: MbRecordingHit? = null
        var mbArtist: MbArtist? = null
        var lb: LbRecording? = null
        var lbPop: LbPopularity? = null
        var wd: WdArtist? = null
        var itunes: com.theveloper.pixelplay.data.brain.source.ITunesSong? = null
        val want = TrackMatch.Want(target.title, target.artist, target.durationMs)
        val entity = "t:${target.rid}"
        var recordingMbid: String? = prior?.mbRecordingId
        var artistMbid: String? = priorArtist?.mbid

        fun evidence() = DnaEvidence(deezerTrack, deezerAlbum, mb, mbArtist, lb, lbPop, wd, itunes, tried)
    }

    /** True when [bit] has to be asked for this row now: never tried, or its part of the row has expired. */
    private fun stale(mask: Int, bit: Int, stampMs: Long, ttlMs: Long, now: Long, hasRow: Boolean): Boolean =
        !hasRow || mask and bit == 0 || (stampMs > 0 && now - stampMs > ttlMs)

    suspend fun enrich(targets: List<DnaTarget>, gate: suspend () -> Boolean): Report {
        if (targets.isEmpty()) return Report(0, false)
        val atlas = atlasProvider.atlas() ?: return Report(0, false, noAtlas = true)
        val version = atlas.version
        val now = clock.nowMs()
        val states = ArrayList<State>(targets.size)
        val priors = tracks.getAll(targets.map { it.rid }).associateBy { it.rid }
        val priorArtists = artists.getAll(targets.map { it.aid }.filter { it != 0L }.distinct()).associateBy { it.aid }
        val blockedBySource = HashMap<Int, Set<String>>()
        val entities = targets.map { "t:${it.rid}" }
        suspend fun blocked(bit: Int, entity: String): Boolean {
            val n = DnaSource.numberOf(bit)
            val set = blockedBySource.getOrPut(n) { misses.blocked(n, now, entities).toSet() }
            return entity in set
        }

        val albumCache = HashMap<Long, Lookup<DeezerAlbum>>()
        val artistCache = HashMap<String, Lookup<MbArtist>>()
        var stopped = false
        for (t in targets) {
            val prior = priors[t.rid]
            val st = State(t, prior, priorArtists[t.aid])
            states.add(st)
            if (!gate()) { stopped = true; break }

            // 1. Deezer.
            val needDeezer = stale(prior?.fetchedMask ?: 0, DnaSource.DEEZER, prior?.popAtMs ?: 0L, POPULARITY_TTL_MS, now, prior != null) && !blocked(DnaSource.DEEZER, st.entity)
            if (needDeezer) {
                val isrc = t.isrc ?: prior?.isrc
                var track: Lookup<DeezerTrack> = Lookup.NotFound
                if (isrc != null) track = deezer.trackByIsrc(isrc)
                if (track is Lookup.NotFound) {
                    when (val ref = deezer.search(st.want)) {
                        is Lookup.Found -> track = deezer.track(ref.value.id)
                        Lookup.NotFound -> {}
                        Lookup.Unavailable -> track = Lookup.Unavailable
                    }
                }
                when (track) {
                    is Lookup.Found -> {
                        st.deezerTrack = track.value
                        st.tried = st.tried or DnaSource.DEEZER
                        val albumId = track.value.albumId
                        // The album's genres are tags (90 days), the rank is popularity (7 days): a weekly rank refresh does not
                        // ask for the album again, or the tags would never expire.
                        val tagsDue = prior == null || prior.fetchedMask and DnaSource.DEEZER == 0 || prior.tagsAtMs == 0L || now - prior.tagsAtMs > TAGS_TTL_MS
                        if (albumId != 0L && tagsDue) {
                            val album = albumCache.getOrPut(albumId) { deezer.album(albumId) }
                            if (album is Lookup.Found) st.deezerAlbum = album.value
                        }
                    }
                    Lookup.NotFound -> { st.tried = st.tried or DnaSource.DEEZER; recordMiss(st.entity, DnaSource.DEEZER, now) }
                    Lookup.Unavailable -> {}
                }
            }
            if (!gate()) { stopped = true; break }

            // 2. MusicBrainz: the recording (its id and first year), then its artist's genres.
            val needMb = stale(prior?.fetchedMask ?: 0, DnaSource.MUSICBRAINZ, prior?.tagsAtMs ?: 0L, TAGS_TTL_MS, now, prior != null) && !blocked(DnaSource.MUSICBRAINZ, st.entity)
            if (needMb) {
                var answered = false
                var hit: Lookup<MbRecordingHit> = Lookup.NotFound
                if (st.recordingMbid == null) {
                    val isrc = t.isrc ?: prior?.isrc ?: st.deezerTrack?.isrc
                    if (isrc != null) hit = musicBrainz.recordingByIsrc(isrc, st.want)
                    if (hit is Lookup.NotFound) hit = musicBrainz.searchRecording(st.want)
                    when (hit) {
                        is Lookup.Found -> { st.mb = hit.value; st.recordingMbid = hit.value.mbid; st.artistMbid = hit.value.artistMbid ?: st.artistMbid; answered = true }
                        Lookup.NotFound -> answered = true
                        Lookup.Unavailable -> {}
                    }
                } else answered = true
                val am = st.artistMbid
                if (am != null && st.priorArtistFresh(now, DnaSource.MUSICBRAINZ)) {
                    // The artist's genres are already stored and fresh: no request. (assemble() keeps the stored claims.)
                } else if (am != null) {
                    when (val a = artistCache.getOrPut(am) { musicBrainz.artist(am) }) {
                        is Lookup.Found -> { st.mbArtist = a.value; answered = true }
                        Lookup.NotFound -> answered = true
                        Lookup.Unavailable -> answered = false
                    }
                }
                if (answered) {
                    st.tried = st.tried or DnaSource.MUSICBRAINZ
                    if (st.mb == null && st.recordingMbid == null) recordMiss(st.entity, DnaSource.MUSICBRAINZ, now)
                }
            }

            // 3. iTunes: the song's genre and release date, by name.
            val needItunes = stale(prior?.fetchedMask ?: 0, DnaSource.ITUNES, prior?.tagsAtMs ?: 0L, TAGS_TTL_MS, now, prior != null) && !blocked(DnaSource.ITUNES, st.entity)
            if (needItunes) {
                if (!gate()) { stopped = true; break }
                when (val s = itunes.search(st.want)) {
                    is Lookup.Found -> { st.itunes = s.value; st.tried = st.tried or DnaSource.ITUNES }
                    Lookup.NotFound -> { st.tried = st.tried or DnaSource.ITUNES; recordMiss(st.entity, DnaSource.ITUNES, now) }
                    Lookup.Unavailable -> {}
                }
            }
        }

        if (!stopped) stopped = !batchPasses(states, atlas, now, gate, ::blocked)
        var saved = 0
        for (st in states) if (persist(st, atlas, version)) saved++
        return Report(saved, stopped)
    }

    /** The ListenBrainz batch (recording and artist genre tags, popularity) and the Wikidata query (artist genres). */
    private suspend fun batchPasses(
        states: List<State>,
        atlas: GenreAtlas,
        now: Long,
        gate: suspend () -> Boolean,
        blocked: suspend (Int, String) -> Boolean,
    ): Boolean {
        // ListenBrainz: 16 recordings a request.
        val lbStates = states.filter {
            val id = it.recordingMbid
            id != null && stale(it.prior?.fetchedMask ?: 0, DnaSource.LISTENBRAINZ, it.prior?.tagsAtMs ?: 0L, TAGS_TTL_MS, now, it.prior != null) &&
                !blocked(DnaSource.LISTENBRAINZ, it.entity)
        }
        for (chunk in lbStates.chunked(com.theveloper.pixelplay.data.brain.source.ListenBrainzSource.BATCH)) {
            if (!gate()) return false
            val ids = chunk.mapNotNull { it.recordingMbid }.distinct()
            when (val r = listenBrainz.recordings(ids)) {
                is Lookup.Found -> for (st in chunk) {
                    st.lb = r.value[st.recordingMbid]
                    st.tried = st.tried or DnaSource.LISTENBRAINZ
                    if (st.lb == null) recordMiss(st.entity, DnaSource.LISTENBRAINZ, now)
                }
                Lookup.NotFound -> for (st in chunk) { st.tried = st.tried or DnaSource.LISTENBRAINZ; recordMiss(st.entity, DnaSource.LISTENBRAINZ, now) }
                Lookup.Unavailable -> {}
            }
            // Popularity from listeners is the last resort: only for tracks no other source ranked.
            val unranked = chunk.filter { it.deezerTrack == null && (it.prior?.popPermille ?: -1) < 0 && it.target.ytPlays <= 0 }
            if (unranked.isNotEmpty() && gate()) {
                (listenBrainz.popularity(unranked.mapNotNull { it.recordingMbid }) as? Lookup.Found)?.let { p ->
                    for (st in unranked) st.lbPop = p.value[st.recordingMbid]
                }
            }
        }
        // Wikidata: one query for up to 100 artists, once a day or so.
        val wdStates = states.filter {
            val a = it.artistMbid
            a != null && !it.priorArtistFresh(now, DnaSource.WIKIDATA)
        }
        val wdIds = wdStates.mapNotNull { it.artistMbid }.distinct()
        if (wdIds.isNotEmpty()) {
            if (!gate()) return false
            when (val r = wikidata.artists(wdIds)) {
                is Lookup.Found -> {
                    val byMbid = r.value.associateBy { it.mbid }
                    for (st in wdStates) { st.wd = byMbid[st.artistMbid]; st.tried = st.tried or DnaSource.WIKIDATA }
                }
                Lookup.NotFound -> for (st in wdStates) st.tried = st.tried or DnaSource.WIKIDATA
                Lookup.Unavailable -> {}
            }
        }
        return true
    }

    private fun State.priorArtistFresh(now: Long, bit: Int): Boolean {
        val a = priorArtist ?: return false
        return a.fetchedMask and bit != 0 && a.tagsAtMs > 0 && now - a.tagsAtMs <= TAGS_TTL_MS
    }

    /**
     * Saves one target's row; false when nothing was learned about a track that has no row yet (no source answered and the app knew
     * nothing), or the save failed. A track that has a row is saved again even when no source could be asked: its `updatedAtMs` moves,
     * so lane 5 does not keep offering the same rows first while a source is off or spent.
     */
    private suspend fun persist(st: State, atlas: GenreAtlas, version: Int): Boolean {
        val t = st.target
        val ownFacts = t.spotifyGenres.isNotEmpty() || t.chipGenres.isNotEmpty() || t.ytYear > 0 || t.ytPlays > 0 || !t.spotifyReleaseDate.isNullOrBlank()
        if (st.tried == 0 && !ownFacts && st.prior == null) return false
        return try {
            val now = clock.nowMs()
            val r = DnaAssembler.assemble(st.target, st.evidence(), st.prior, st.priorArtist, atlas, version, now, currentYear(now) + 1)
            tracks.upsert(r.track)
            r.artist?.let { artists.upsert(it) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: DNA of %d not saved", st.target.rid)
            false
        }
    }

    private suspend fun recordMiss(entity: String, bit: Int, now: Long) {
        val source = DnaSource.numberOf(bit)
        val old = misses.get(entity, source)
        val n = (old?.misses ?: 0) + 1
        var wait = MISS_FIRST_MS
        for (i in 1 until n) { wait *= 2; if (wait >= MISS_CAP_MS) { wait = MISS_CAP_MS; break } }
        misses.put(SourceMissEntity(entity, source, now + wait, n))
    }

    companion object {
        const val POPULARITY_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val TAGS_TTL_MS = 90L * 24 * 60 * 60 * 1000
        const val MISS_FIRST_MS = 14L * 24 * 60 * 60 * 1000
        const val MISS_CAP_MS = 120L * 24 * 60 * 60 * 1000

        /** The calendar year of [ms] (UTC): precise enough for "no year after next year". */
        fun currentYear(ms: Long): Int {
            val days = Math.floorDiv(ms, 86_400_000L)
            // Civil-from-days (Howard Hinnant), year part only.
            val z = days + 719468
            val era = Math.floorDiv(z, 146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            var y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            if (mp >= 10) y++
            return y.toInt()
        }
    }
}
