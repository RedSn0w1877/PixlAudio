package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.GenrePage
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.ModelVoice
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.ServedVector
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.BrainPlayTags
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.GenreStations
import com.theveloper.pixelplay.data.brain.LibraryIndex
import com.theveloper.pixelplay.data.brain.LibraryTrackCatalog
import com.theveloper.pixelplay.data.brain.Novelty
import com.theveloper.pixelplay.data.brain.RadioSeed
import com.theveloper.pixelplay.data.brain.Station
import com.theveloper.pixelplay.data.brain.StationCard
import com.theveloper.pixelplay.data.brain.StationSeeds
import com.theveloper.pixelplay.data.brain.YourSoundRepository
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.store.AliasDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.recommendation.TasteAction
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentMath
import com.theveloper.pixelplay.data.recommendation.TasteRequest
import com.theveloper.pixelplay.data.recommendation.TasteSetBy
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [MusicBackend] over the brain's real tables (docs/brain-spec.md, 9.2): the alias table and the library for names, the engine's own pool and
 * scorer for picks ([AssistantMixer]), `track_dna`, the atlas and the graph for facts, Your sound for taste, the saved releases file for new
 * music. Nothing here is a model's opinion and nothing is invented: a value the tables do not hold is null, and the tool says so.
 *
 * Network use is the existing, bounded kind: a name the alias table and the library do not know costs one YouTube Music songs search under the
 * day's [HostBudget] (the same bucket autoplay and the radios share); when the day's requests are spent the item is skipped, not retried.
 * Every call runs off the main thread; only handing songs to the player is posted to it.
 */
@Singleton
class BrainAssistantEngine @Inject constructor(
    private val mixes: BrainMixes,
    private val mixer: AssistantMixer,
    private val brain: BrainEngine,
    private val aliasDao: AliasDao,
    private val graphEdgeDao: GraphEdgeDao,
    private val spotifyDao: SpotifyDao,
    private val innerTube: InnerTubeClient,
    private val matcher: TrackMatcher,
    private val hostBudget: HostBudget,
    private val yourSound: YourSoundRepository,
    private val genreStations: GenreStations,
    private val playlists: PlaylistPreferencesRepository,
    private val feedback: BrainFeedback,
    private val playTags: BrainPlayTags,
    private val servedFactors: com.theveloper.pixelplay.data.brain.ServedFactors,
    private val assistCache: com.theveloper.pixelplay.data.brain.AssistCache,
) : MusicBackend {

    // ---- what this process issued -----------------------------------------------------------------------

    /** A track a tool returned, by ref: what a later call needs to use it (play it, seed from it, explain it). */
    private class Issued(
        val ref: String,
        val song: Song?,
        val rid: Long,
        val track: EngineTrack?,
        val primaryArtist: String,
        val tag: BrainItemTag?,
        val servedX: ByteArray?,
    )

    private val issued = ConcurrentHashMap<String, Issued>()
    private val tagBySongId = ConcurrentHashMap<String, BrainItemTag>()
    private val builtMixes = object : LinkedHashMap<String, BuiltRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BuiltRecord>?): Boolean = size > MAX_MIXES
    }

    private class BuiltRecord(val query: AssistantRules.Query, val songs: List<Song>)

    private fun remember(entry: Issued) {
        if (issued.size > MAX_ISSUED) issued.clear()
        issued[entry.ref] = entry
        entry.song?.let { song -> entry.tag?.let { tagBySongId[song.id] = it } }
    }

    // ---- refs ---------------------------------------------------------------------------------------------

    private fun isRealSpotifyId(id: String?): Boolean =
        id != null && id.length == AssistantRules.SPOTIFY_ID_LENGTH && !LibraryTrackCatalog.isSyntheticId(id) && id.all { it.isLetterOrDigit() && it.code < 128 }

    private fun isVideoId(id: String?): Boolean =
        id != null && id.length == AssistantRules.YOUTUBE_ID_LENGTH && id.all { (it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '-' }

    /** The ref a library or engine song is known by, or null when it has neither a Spotify id nor a YouTube audio id. */
    private suspend fun refFor(song: Song, track: EngineTrack?): String? {
        if (track != null) return track.videoId.takeIf(::isVideoId)?.let(AssistantRules::youTubeRef)
        val sid = song.spotifyId
        if (isRealSpotifyId(sid)) return AssistantRules.spotifyRef(sid!!)
        val video = mixes.playableStore.bySongId(song.id)?.videoId
            ?: sid?.let { runCatching { spotifyDao.getMatchedVideoId(it) }.getOrNull() }
        return video?.takeIf(::isVideoId)?.let(AssistantRules::youTubeRef)
    }

    private fun infoOf(ref: String, song: Song?, track: EngineTrack?, title: String, artist: String, year: Int, inLibrary: Boolean): TrackInfo =
        TrackInfo(
            ref = ref, title = title, artist = artist, year = year, inLibrary = inLibrary,
            album = (song?.album ?: track?.album).orEmpty().takeIf { !it.equals("Unknown Album", true) }.orEmpty(),
        )

    private suspend fun infoOfSong(song: Song, track: EngineTrack?, rid: Long, tag: BrainItemTag?, servedX: ByteArray?, year: Int): TrackInfo? {
        val ref = refFor(song, track) ?: return null
        val primary = LibraryIndex.primaryArtistName(song)
        remember(Issued(ref, song, rid, track, primary, tag, servedX))
        return infoOf(ref, song, track, song.title, song.displayArtist, if (year > 0) year else song.year, inLibrary = track == null)
    }

    override suspend fun songForRef(ref: String): Song? {
        issued[ref]?.song?.let { return it }
        val parsed = AssistantRules.parseRef(ref) ?: return null
        return withContext(Dispatchers.IO) {
            if (parsed.kind == AssistantRules.REF_SPOTIFY) {
                val id = SpotifyRepository.unifiedSongId(parsed.id).toString()
                mixes.musicRepository.getSongsByIds(listOf(id)).first().firstOrNull()
            } else {
                val synth = SpotifyRepository.syntheticIdForVideo(parsed.id)
                val songId = SpotifyRepository.unifiedSongId(synth)
                mixes.musicRepository.getSongsByIds(listOf(songId.toString())).first().firstOrNull()
                    ?: mixes.playableStore.bySongId(songId.toString())?.let(EngineTrack::songOf)
                    // The row aged out of `playable` but the recording is still known by its DNA: rebuild the track and register it again.
                    ?: engineTrackFromDna(parsed.id)?.let { t ->
                        mixes.playableStore.register(listOf(t), mixes.clock.nowMs())
                        t.toSong()
                    }
            }
        }
    }

    private suspend fun engineTrackFromDna(videoId: String): EngineTrack? {
        val key = Ids.TrackKeys(videoId = videoId).y ?: return null
        val rid = aliasDao.trackId(key) ?: return null
        val row = mixes.trackDnaDao.get(rid) ?: return null
        val synth = EngineTrack.synthIdOf(videoId)
        return EngineTrack(
            synthId = synth, songId = SpotifyRepository.unifiedSongId(synth), rid = rid, videoId = videoId, title = row.title,
            artists = listOf(RadioArtistRef(row.artistDisplay.ifBlank { EngineTrack.UNKNOWN_ARTIST })), album = null,
            durationMs = row.durationMs.toLong(), artUrl = null,
        )
    }

    override fun expectTags(queueName: String, songs: List<Song>) {
        val map = HashMap<String, BrainItemTag>()
        for (s in songs) tagBySongId[s.id]?.let { map[s.id] = it }
        if (map.isNotEmpty()) playTags.expect(queueName = queueName, bySongId = map)
    }

    // ---- resolve_tracks ---------------------------------------------------------------------------------

    override suspend fun resolve(items: List<Pair<String, String>>): List<Lookup> = withContext(Dispatchers.IO) {
        val songs = mixes.musicRepository.getAllSongsOnce()
        val index = LibraryIndex(songs)
        items.map { (title, artist) -> resolveOne(title, artist, index) }
    }

    private suspend fun resolveOne(title: String, artist: String, index: LibraryIndex): Lookup {
        val cleanWanted = BrainText.cleanTitle(title)
        // 1. The listener's own library: free, and in_library is true.
        val artistKey = BrainText.fold(artist)
        if (artistKey.isNotEmpty()) {
            val hit = index.byPrimaryArtist(artistKey).firstOrNull { sameRecording(cleanWanted, BrainText.cleanTitle(it.title)) }
            if (hit != null) {
                val info = infoOfSong(hit, null, 0L, null, null, 0)
                if (info != null) return Lookup(info)
            }
        }
        // 2. The alias table (tracks the brain has met: radios, imports, earlier asks).
        val nameKey = Ids.nameKey(artist, title)
        if (nameKey != null) {
            val rid = aliasDao.trackId(nameKey)?.let { runCatching { mixes.identity.survivorOf(it) }.getOrDefault(it) }
            if (rid != null) fromAlias(rid)?.let { return Lookup(it) }
        }
        // 3. YouTube Music songs search, under the day's budget; the same matcher thresholds playback uses.
        return searchYouTube(title, artist)
    }

    private fun sameRecording(want: BrainText.CleanTitle, got: BrainText.CleanTitle): Boolean =
        want.title == got.title && (got.identityFlags and want.identityFlags.inv()) == 0

    private suspend fun fromAlias(rid: Long): TrackInfo? {
        val row = mixes.trackDnaDao.get(rid) ?: return null
        val video = row.ytAudioIds.split(',').firstOrNull { isVideoId(it) }
        val ref = when {
            video != null -> AssistantRules.youTubeRef(video)
            isRealSpotifyId(row.spotifyId) -> AssistantRules.spotifyRef(row.spotifyId!!)
            else -> mixes.seedDao.playableByRid(listOf(rid)).firstOrNull()?.videoId?.takeIf(::isVideoId)?.let(AssistantRules::youTubeRef)
        } ?: return null
        val primary = row.artistDisplay.substringBefore(", ")
        remember(Issued(ref, null, rid, null, primary, null, null))
        return infoOf(ref, null, null, row.title, row.artistDisplay, row.firstYear, inLibrary = false)
    }

    private suspend fun searchYouTube(title: String, artist: String): Lookup {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val results = try {
            hostBudget.withPermit { innerTube.searchSongs(query, SEARCH_LIMIT) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "assistant: the songs search failed")
            return Lookup(null)
        } ?: return Lookup(null, skipped = true)
        // Scored like playback does (title, artist, duration, version words); the accept threshold is the matcher's own.
        val wanted = SpotifySongEntity(
            id = "assistant", spotifyId = "", playlistId = "", title = title, artist = artist.ifBlank { "" }, album = "", albumId = null,
            durationMs = 0L, albumArtUrl = null, isrc = null, dateAdded = 0L,
        )
        var best: com.theveloper.pixelplay.data.youtube.YouTubeSearchResult? = null
        var bestScore = 0f
        for (r in results) {
            val s = if (artist.isBlank()) titleOnlyScore(title, r) else matcher.score(wanted, r)
            if (s > bestScore) {
                best = r
                bestScore = s
            }
        }
        // The matcher's bar was set for playback, where the song's length adds up to 0.30. Here the length is not known, so a candidate must make
        // it up with the title: with an exact artist, a title that is at least 92 percent the same (not "Love" for "Lose").
        val bar = if (artist.isBlank()) TrackMatcher.MIN_ACCEPT_SCORE else MIN_SCORE_NO_DURATION
        val hit = best?.takeIf { bestScore >= bar && isVideoId(it.videoId) } ?: return Lookup(null)
        val keys = Ids.TrackKeys(videoId = hit.videoId, nameKey = Ids.nameKey(hit.artist, hit.title))
        val duration = ((hit.durationSeconds ?: 0) * 1000L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val rid = runCatching { mixes.identity.resolveTrack(keys, duration.toInt()) }.getOrDefault(0L)
        val row = RadioRow(
            videoId = hit.videoId, title = hit.title, artists = listOf(RadioArtistRef(hit.artist)), album = hit.album,
            durationSec = hit.durationSeconds ?: 0, thumbnailUrl = hit.thumbnailUrl, kind = MusicVideoKind.ATV,
        )
        val track = EngineTrack.from(row, rid)
        // In `playable` BEFORE a media item can reach the player (7.2).
        runCatching { mixes.playableStore.register(listOf(track), mixes.clock.nowMs()) }
        val info = infoOfSong(track.toSong(), track, rid, null, null, 0) ?: return Lookup(null)
        return Lookup(info)
    }

    /** With no artist given, only a near-exact title counts (the matcher needs an artist to vouch for a candidate). */
    private fun titleOnlyScore(title: String, r: com.theveloper.pixelplay.data.youtube.YouTubeSearchResult): Float {
        val a = BrainText.cleanTitle(title)
        val b = BrainText.cleanTitle(r.title)
        return if (a.title.isNotEmpty() && sameRecording(a, b) && !r.isMusicVideo) TrackMatcher.MIN_ACCEPT_SCORE else 0f
    }

    // ---- recommend / build_mix --------------------------------------------------------------------------

    private suspend fun materialOf(ref: String): SeedMaterial? {
        val parsed = AssistantRules.parseRef(ref) ?: return null
        val known = issued[ref]
        val song = known?.song ?: songForRef(ref) ?: return null
        val primary = known?.primaryArtist ?: LibraryIndex.primaryArtistName(song)
        val track = known?.track
        val rid = known?.rid?.takeIf { it != 0L } ?: runCatching {
            val facts = LibraryTrackCatalog.factsOf(song)
            val keys = facts?.keys ?: Ids.TrackKeys(videoId = if (parsed.kind == AssistantRules.REF_YOUTUBE) parsed.id else null, nameKey = Ids.nameKey(primary, song.title))
            mixes.identity.resolveTrack(keys, song.duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        }.getOrDefault(0L)
        if (rid == 0L) return null
        val aid = mixes.identity.resolveArtistNames(listOf(primary)).firstOrNull() ?: 0L
        val video = if (parsed.kind == AssistantRules.REF_YOUTUBE) parsed.id
        else mixes.playableStore.bySongId(song.id)?.videoId ?: song.spotifyId?.let { runCatching { spotifyDao.getMatchedVideoId(it) }.getOrNull() }
        val radioSeed = video?.let {
            RadioSeed(
                mediaId = song.id, videoId = it, title = song.title, primaryArtist = primary, durationMs = song.duration,
                spotifyId = song.spotifyId?.takeIf(::isRealSpotifyId),
            )
        }
        return SeedMaterial(ref, rid, aid, song.title, primary, song, track, radioSeed)
    }

    private suspend fun planFor(query: AssistantRules.Query, bump: Int): Pair<AssistantPlan, List<SeedMaterial>>? {
        val seeds = query.seedRefs.mapNotNull { materialOf(it) }
        val unmaterialised = query.seedRefs.filter { ref -> seeds.none { it.ref == ref } }
        val excluded = HashSet<String>()
        for (ref in query.excludeArtistRefs) {
            val known = issued[ref]
            val name = known?.primaryArtist ?: songForRef(ref)?.let { LibraryIndex.primaryArtistName(it) }
            if (!name.isNullOrBlank()) excluded.add(BrainText.fold(name))
        }
        val plan = mixer.plan(query, seeds, excluded, AssistantMixer.regenOf(query, bump)) ?: return null
        if (unmaterialised.isNotEmpty()) plan.seedsNotUsed = unmaterialised + plan.seedsNotUsed
        return plan to seeds
    }

    private suspend fun picksOf(plan: AssistantPlan, surfaceId: String, now: Long, songsOut: MutableList<Song>? = null): List<PickInfo> {
        val recId = (now shl 8) or ASSISTANT_REC_TAG
        val out = ArrayList<PickInfo>(plan.picks.size)
        for ((slot, p) in plan.picks.withIndex()) {
            val primary = LibraryIndex.primaryArtistName(p.song)
            val tag = BrainItemTag(
                surface = Surface.nameOf(Surface.ASSISTANT),
                surfaceId = surfaceId,
                recId = recId,
                slot = slot,
                arm = Arm.nameOf(p.arm),
                whyKey = if (p.arm == Arm.ASK) WhyLine.nameOf(WhyLine.ASKED) else WhyLine.nameOf(p.whyKey).takeIf { p.whyKey != WhyLine.NONE && p.whyName.isNotEmpty() },
                whyArg = p.whyName.takeIf { it.isNotEmpty() },
                primaryArtist = primary,
                // The listener asked for this list and plays it: its tracks are theirs, as a playlist's are.
                chosen = true,
                retrQ = p.retrQ,
                radioRank = p.radioRank,
                control = plan.control,
                modelVersion = plan.modelVersion,
                explore = p.explore,
                servedX = p.servedX?.let(ServedVector::toHex),
            )
            val info = infoOfSong(p.song, p.track, p.rid, tag, p.servedX, p.year) ?: continue
            val whyKey = if (p.arm == Arm.ASK) WhyLine.nameOf(WhyLine.ASKED) else if (p.whyKey != WhyLine.NONE && p.whyName.isNotEmpty()) WhyLine.nameOf(p.whyKey) else ""
            out.add(PickInfo(info, whyKey, if (p.whyName.isNotEmpty()) listOf(p.whyName) else emptyList()))
            songsOut?.add(p.song)
        }
        return out
    }

    override suspend fun recommend(query: AssistantRules.Query): Recommendation = withContext(Dispatchers.IO) {
        val (plan, _) = planFor(query, 0) ?: return@withContext Recommendation(emptyList(), emptyList(), coldStart = !mixes.hasState())
        val picks = picksOf(plan, "assistant", mixes.clock.nowMs())
        Recommendation(picks.take(query.count), plan.unsupported, plan.coldStart, plan.seedsNotUsed)
    }

    override suspend fun buildMix(query: AssistantRules.Query): BuiltMix? = withContext(Dispatchers.IO) {
        val (plan, _) = planFor(query, 0) ?: return@withContext null
        if (plan.picks.isEmpty()) return@withContext null
        val now = mixes.clock.nowMs()
        val mixId = java.lang.Long.toHexString(BrainHash.fnv1a64("assistant|${query.title}|$now|${plan.picks.firstOrNull()?.rid}"))
        // The songs Play and Save use are exactly the picks the card shows (a pick with no resolvable track is in neither).
        val songs = ArrayList<Song>(plan.picks.size)
        val picks = picksOf(plan, mixId, now, songs)
        if (picks.isEmpty()) return@withContext null
        // Tracks from outside the library go into `playable` now, so Play and Save work from the card.
        val engineTracks = plan.picks.mapNotNull { it.track }
        if (engineTracks.isNotEmpty()) runCatching { mixes.playableStore.register(engineTracks, now) }
        synchronized(builtMixes) { builtMixes[mixId] = BuiltRecord(query, songs) }
        built(mixId, query, plan, picks)
    }

    private fun built(mixId: String, query: AssistantRules.Query, plan: AssistantPlan, picks: List<PickInfo>): BuiltMix {
        val byArtist = LinkedHashMap<String, Int>()
        for (p in picks) byArtist.merge(p.track.artist.substringBefore(", "), 1, Int::plus)
        val topArtistName = byArtist.entries.maxWithOrNull(compareBy({ it.value }, { -it.key.length }))?.key.orEmpty()
        val topArtistRef = picks.firstOrNull { it.track.artist.substringBefore(", ") == topArtistName }?.track?.ref.orEmpty()
        val byGenre = HashMap<Int, Int>()
        for (p in plan.picks) for (g in p.genreIds) byGenre.merge(g, 1, Int::plus)
        val atlas = mixes.atlasProvider.loadedOrNull()
        // A genre with some depth (a root family is too broad to be a chip); ties go to the lower id.
        val topGenreId = byGenre.entries.filter { (atlas?.depth(it.key) ?: 0) >= 1 }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: 0
        return BuiltMix(
            mixId = mixId, title = query.title.ifBlank { "Made for you" }, tracks = picks, unsupportedFilters = plan.unsupported, query = query,
            topArtistRef = topArtistRef, topArtistName = topArtistName, topGenreId = topGenreId, topGenreName = atlas?.genre(topGenreId)?.name.orEmpty(),
            seedsNotUsed = plan.seedsNotUsed,
            chipUnsupported = (plan.unsupported + listOfNotNull(
                AssistantRules.FILTER_TEMPO.takeIf { !plan.tempoKnown }, AssistantRules.FILTER_ENERGY.takeIf { !plan.energyKnown },
            )).distinct(),
        )
    }

    override suspend fun adjustMix(mixId: String, chip: AssistantRules.Chip): BuiltMix? {
        val record = synchronized(builtMixes) { builtMixes[mixId] } ?: return null
        val next = AssistantRules.applyChip(record.query, chip)
        return withContext(Dispatchers.IO) {
            val (plan, _) = planFor(next, 1) ?: return@withContext null
            if (plan.picks.isEmpty()) return@withContext null
            val now = mixes.clock.nowMs()
            val id = java.lang.Long.toHexString(BrainHash.fnv1a64("assistant|${next.title}|$now|${plan.picks.firstOrNull()?.rid}"))
            val songs = ArrayList<Song>(plan.picks.size)
            val picks = picksOf(plan, id, now, songs)
            if (picks.isEmpty()) return@withContext null
            val engineTracks = plan.picks.mapNotNull { it.track }
            if (engineTracks.isNotEmpty()) runCatching { mixes.playableStore.register(engineTracks, now) }
            // The card now shows the new list: it takes the old one's place, so a card never loses its record to the chips of another.
            synchronized(builtMixes) {
                builtMixes.remove(mixId)
                builtMixes[id] = BuiltRecord(next, songs)
            }
            built(id, next, plan, picks)
        }
    }

    override suspend fun songsForMix(mixId: String): List<Song>? =
        synchronized(builtMixes) { builtMixes[mixId]?.songs }

    // ---- start_station ----------------------------------------------------------------------------------

    override suspend fun startStation(seedRef: String, seedKind: String, mode: String, player: TaizoPlayer?): StationOutcome {
        if (player == null) return StationOutcome(false, null, "The player isn't ready. Open PixlAudio's player and try again.")
        val now = mixes.clock.nowMs()
        val level = when (mode) {
            "familiar" -> Novelty.FAMILIAR
            "discover" -> Novelty.DISCOVER
            else -> Novelty.BLEND
        }
        val started: Pair<Station, Song>? = withContext(Dispatchers.IO) {
            when (seedKind) {
                "song" -> songForRef(seedRef)?.let { song ->
                    Station.song(song.id, song.title, LibraryIndex.primaryArtistName(song), now) to song
                }
                "artist" -> artistSeed(seedRef, allowNetwork = true)?.let { song -> Station.artist(song.id, seedRef.trim(), now) to song }
                // The listener's own artists only: no search is made (the offline sentence path asks this first).
                "library_artist" -> artistSeed(seedRef, allowNetwork = false)?.let { song -> Station.artist(song.id, LibraryIndex.primaryArtistName(song), now) to song }
                "genre" -> genreSeed(seedRef, now)
                "album" -> sourceSeed(Station.SOURCE_ALBUM, seedRef, now)
                "playlist" -> sourceSeed(Station.SOURCE_PLAYLIST, seedRef, now)
                else -> null
            }
        }
        val (station, seed) = started ?: return StationOutcome(false, null, "Nothing to start a station from was found for that seed.")
        val info = infoOfSong(seed, null, 0L, null, null, 0)
        mixes.stations.setNovelty(level)
        mixes.stations.start(station)
        val queueName = "Station: ${station.title}"
        playTags.expect(
            queueName = queueName,
            default = BrainItemTag(surface = Surface.nameOf(Surface.STATION), surfaceId = station.id),
            startsStation = true,
        )
        withContext(Dispatchers.Main) { player.playSongs(listOf(seed), seed, queueName) }
        return StationOutcome(true, info, title = station.title)
    }

    /** The listener's most played song by [artistName], else a track from the artist's own page radio (one budgeted lookup). */
    private suspend fun artistSeed(artistName: String, allowNetwork: Boolean): Song? {
        val key = BrainText.fold(artistName)
        if (key.isEmpty()) return null
        val index = LibraryIndex(mixes.musicRepository.getAllSongsOnce())
        index.byPrimaryArtist(key).firstOrNull()?.let { return it }
        if (!allowNetwork) return null
        val started = mixes.brainRepository.beginArtistStation(artistName) ?: return null
        val track = EngineTrack.from(started.seedRow, started.seedRid)
        mixes.playableStore.register(listOf(track), mixes.clock.nowMs())
        return track.toSong()
    }

    private suspend fun genreSeed(seedRef: String, now: Long): Pair<Station, Song>? {
        val atlas = mixes.atlasProvider.atlas() ?: return null
        val id = seedRef.trim().toIntOrNull() ?: atlas.idForName(seedRef).takeIf { it != com.theveloper.pixelplay.brain.GenreAtlas.NONE } ?: return null
        val name = atlas.genre(id)?.name ?: return null
        val songs = genreStations.songsFor(StationCard.KIND_GENRE, id, now)
        val seeds = StationSeeds.pick(songs, emptyMap(), GenreStations.SEEDS)
        if (seeds.isEmpty()) return null
        return Station.fromSource(Station.SOURCE_GENRE, seeds.map { it.id }, name, now) to seeds[0]
    }

    private suspend fun sourceSeed(source: Int, name: String, now: Long): Pair<Station, Song>? {
        val key = BrainText.fold(name)
        if (key.isEmpty()) return null
        val songs = if (source == Station.SOURCE_ALBUM) {
            mixes.musicRepository.getAllSongsOnce().filter { BrainText.fold(it.album) == key }
        } else {
            val list = playlists.userPlaylistsFlow.first().firstOrNull { BrainText.fold(it.name) == key } ?: return null
            if (list.songIds.isEmpty()) emptyList() else mixes.musicRepository.getSongsByIds(list.songIds).first()
        }
        val seeds = StationSeeds.pick(songs, emptyMap(), if (source == Station.SOURCE_ALBUM) StationSeeds.ALBUM_SEEDS else StationSeeds.PLAYLIST_SEEDS)
        if (seeds.isEmpty()) return null
        return Station.fromSource(source, seeds.map { it.id }, name.trim(), now) to seeds[0]
    }

    // ---- steer_session ----------------------------------------------------------------------------------

    override suspend fun steer(change: String, target: String, scope: String, player: TaizoPlayer?): SteerOutcome {
        if (scope == "always") return steerForGood(change, target)
        if (player == null) return SteerOutcome(false, 0, "The player isn't ready.")
        val current = player.currentSong() ?: return SteerOutcome(false, 0, "Nothing is playing, so there is no queue to steer.")
        val base = refFor(current, null)
        val seeds = if (base != null) listOf(base) else emptyList()
        var q = AssistantRules.normalize(
            title = "", seedRefs = seeds, genreIds = emptyList(), moodIds = emptyList(), yearFrom = 0, yearTo = 0, energy = AssistantRules.ANY,
            tempo = AssistantRules.ANY, novelty = AssistantRules.ANY, noveltySharePct = -1, count = STEER_COUNT, excludeArtistRefs = emptyList(),
            minCount = 1, maxCount = AssistantRules.MAX_RECOMMEND, defaultCount = STEER_COUNT,
        )
        q = when (change) {
            "energy_up" -> q.copy(energy = AssistantRules.HIGHER)
            "energy_down" -> q.copy(energy = AssistantRules.LOWER)
            "more_familiar" -> q.copy(novelty = AssistantRules.FAMILIAR)
            "more_new" -> q.copy(novelty = AssistantRules.NEW)
            "more_genre" -> target.toIntOrNull()?.let { q.copy(genreIds = listOf(it)) } ?: return SteerOutcome(false, 0, "A genre id is needed (from lookup_genre).")
            "less_genre" -> target.toIntOrNull()?.let { q.copy(excludeGenreIds = listOf(it)) } ?: return SteerOutcome(false, 0, "A genre id is needed (from lookup_genre).")
            "more_artist" -> {
                val ref = AssistantRules.parseRef(target)?.toString() ?: return SteerOutcome(false, 0, "A track ref of the artist is needed.")
                q.copy(seedRefs = listOf(ref))
            }
            "less_artist" -> {
                val ref = AssistantRules.parseRef(target)?.toString() ?: return SteerOutcome(false, 0, "A track ref of the artist is needed.")
                q.copy(excludeArtistRefs = listOf(ref))
            }
            else -> return SteerOutcome(false, 0, "Unknown change.")
        }
        val rec = recommend(q)
        // An energy change the data cannot answer (no energy known for the pool) would only add songs that are not any lower or higher:
        // it is not applied, and the model is told, instead of reporting a change that did not happen.
        if ((change == "energy_up" || change == "energy_down") && AssistantRules.FILTER_ENERGY in rec.unsupportedFilters) {
            return SteerOutcome(false, 0, "PixlAudio does not know how energetic these songs are yet, so it cannot change the energy of this queue. Nothing was changed.")
        }
        val songs = rec.picks.mapNotNull { songForRef(it.track.ref) }
        if (songs.isEmpty()) {
            return SteerOutcome(false, 0, "PixlAudio found nothing to change the queue with yet. Nothing was changed.")
        }
        expectTags("Taizo", songs)
        withContext(Dispatchers.Main) { player.addToQueue(songs, playNext = true) }
        return SteerOutcome(
            true, songs.size,
            "Put ${songs.size} songs built for this change next. The rest of the queue after them is unchanged.",
        )
    }

    /** A lasting change: a taste adjustment (undoable in Settings) or the novelty switch. */
    private suspend fun steerForGood(change: String, target: String): SteerOutcome = withContext(Dispatchers.IO) {
        when (change) {
            "more_familiar" -> { mixes.stations.setNovelty(Novelty.FAMILIAR); SteerOutcome(true, 0, "The Familiar / Blend / Discover switch is now on Familiar.") }
            "more_new" -> { mixes.stations.setNovelty(Novelty.DISCOVER); SteerOutcome(true, 0, "The Familiar / Blend / Discover switch is now on Discover.") }
            "less_artist" -> {
                val name = artistNameOf(target) ?: return@withContext SteerOutcome(false, 0, "Which artist? Pass a track ref of theirs or their name.")
                val id = feedback.lessFrom(name)
                SteerOutcome(id != null, 0, if (id != null) "Reduced $name in what PixlAudio picks (undo in Settings)." else "That change could not be stored.")
            }
            "more_artist" -> {
                val name = artistNameOf(target) ?: return@withContext SteerOutcome(false, 0, "Which artist? Pass a track ref of theirs or their name.")
                val outcome = mixes.tasteAdjustments.apply(
                    listOf(
                        TasteRequest(
                            action = TasteAction.BOOST, target = TasteTarget.ARTIST, key = com.theveloper.pixelplay.data.recommendation.TasteAdjustments.nameKey(name),
                            altKey = null, label = name, strength = TasteAdjustmentMath.DEFAULT_STRENGTH, reason = "Asked in chat", expiresDays = null,
                        )
                    ),
                    TasteSetBy.TAIZO,
                )
                val changed = outcome.changes.any { it.changed }
                SteerOutcome(changed, 0, if (changed) "Boosted $name in what PixlAudio picks (undo in Settings)." else "That change could not be stored.")
            }
            "more_genre", "less_genre" -> {
                if (target.isBlank()) return@withContext SteerOutcome(false, 0, "Which genre? Pass its id from lookup_genre.")
                val atlas = mixes.atlasProvider.atlas()
                // Only a genre of the atlas is stored (a made-up name would be saved and never match a track).
                val name = if (atlas == null) target
                else target.toIntOrNull()?.let { atlas.genre(it)?.name }
                    ?: atlas.idForName(target).takeIf { it != com.theveloper.pixelplay.brain.GenreAtlas.NONE }?.let { atlas.genre(it)?.name }
                    ?: return@withContext SteerOutcome(false, 0, "That is not a genre in PixlAudio's atlas, so nothing was changed. Look it up with lookup_genre first.")
                val id = feedback.adjustGenre(name, more = change == "more_genre")
                SteerOutcome(id != null, 0, if (id != null) "Changed how much $name PixlAudio picks (undo in Settings)." else "That change could not be stored.")
            }
            else -> SteerOutcome(false, 0, "Energy has no lasting setting; it can only change this queue.")
        }
    }

    private suspend fun artistNameOf(target: String): String? =
        if (AssistantRules.parseRef(target) != null) (issued[target]?.primaryArtist ?: songForRef(target)?.let { LibraryIndex.primaryArtistName(it) })
        else target.trim().takeIf { it.isNotEmpty() }

    // ---- explain_track ----------------------------------------------------------------------------------

    override suspend fun explainTrack(ref: String, player: TaizoPlayer?): TrackExplanation? = withContext(Dispatchers.IO) {
        var song: Song? = null
        var theRef = ref
        if (ref == "now") {
            song = player?.currentSong() ?: return@withContext null
            theRef = refFor(song, null) ?: "now"
        } else {
            song = issued[ref]?.song ?: songForRef(ref)
        }
        val known = issued[theRef]
        // The recording id: what the pick was served as, or what the alias table says about the song.
        val rid = known?.rid?.takeIf { it != 0L } ?: song?.let { s ->
            runCatching {
                val keys = LibraryTrackCatalog.factsOf(s)?.keys ?: Ids.TrackKeys(nameKey = Ids.nameKey(LibraryIndex.primaryArtistName(s), s.title))
                keys.s?.let { aliasDao.trackId(it) } ?: keys.n?.let { aliasDao.trackId(it) }
            }.getOrNull()
        } ?: return@withContext null
        val dna = mixes.trackDnaDao.get(rid)
        val title = song?.title ?: dna?.title ?: return@withContext null
        val artist = song?.displayArtist ?: dna?.artistDisplay.orEmpty()
        val events = runCatching { mixes.playEventDao.byRecordingsBlocking(listOf(rid)) }.getOrDefault(emptyList())
        val heard = events.filter { it.flags and EventFlags.NEVER_AUDIBLE == 0 }
        val plays = if (events.isEmpty()) null else heard.size
        val rate = if (heard.size >= TrackExplanation.MIN_PLAYS_FOR_RATE) heard.count { it.endReason == EndReason.TRACK_DONE }.toDouble() / heard.size else null
        // The vector the pick was served with: a pick made in this chat, else the newest played event that stored one.
        val servedX = known?.servedX ?: events.filter { it.servedX != null }.maxByOrNull { it.startedAtMs }?.servedX
        val armCode = known?.tag?.arm?.let { Arm.codeOf(it) } ?: events.filter { it.arm != Arm.NONE }.maxByOrNull { it.startedAtMs }?.arm
        val servedModel = known?.tag?.modelVersion?.takeIf { known?.servedX != null }
            ?: events.filter { it.servedX != null }.maxByOrNull { it.startedAtMs }?.modelVersion ?: -1
        // The weights the pick was ranked with (the learned ones only for a pick they ranked, and only while the gate still has them on).
        val factors = ServedVector.dequantize(servedX)?.let { x -> servedFactors.top(x, servedModel) }
        val atlas = mixes.atlasProvider.atlas()
        val genres = if (dna == null || atlas == null) null else {
            val v = Tags.decode(dna.tags)
            if (v.isEmpty) null else v.tags.sortedByDescending { it.q }.take(3).mapNotNull { atlas.genre(it.atlasId)?.name }.takeIf { it.isNotEmpty() }
        }
        val tag = known?.tag
        TrackExplanation(
            ref = theRef, title = title, artist = artist,
            arm = armCode?.takeIf { it != Arm.NONE }?.let { Arm.nameOf(it) },
            whyKey = tag?.whyKey, whyArgs = listOfNotNull(tag?.whyArg),
            factors = factors, genres = genres,
            year = dna?.firstYear?.takeIf { it > 0 } ?: song?.year ?: 0,
            plays = plays, completionRate = rate,
            // The genres of a recording the optional batch job tagged were suggested by the assistant, not read from a source.
            genresSuggested = runCatching { rid in assistCache.read().tagRids }.getOrDefault(false),
            primaryArtist = song?.let { LibraryIndex.primaryArtistName(it) } ?: known?.primaryArtist ?: artist.substringBefore(", "),
        )
    }

    // ---- explain_taste ----------------------------------------------------------------------------------

    override suspend fun explainTaste(scope: String): TasteExplanation {
        val s = yourSound.load()
        val lines = ArrayList<TasteLine>()
        val unknown = ArrayList<String>()
        if (s.isEmpty) {
            unknown.add("No plays are recorded yet, so there is nothing to say about the user's taste.")
            return TasteExplanation(scope, lines, unknown)
        }
        when (scope) {
            "islands" -> {
                if (s.islands.isEmpty()) unknown.add("Taste islands need about ${com.theveloper.pixelplay.brain.YourSound.ISLANDS_MIN_PLAYS} plays in the last 28 days.")
                for (i in s.islands) {
                    val name = i.name ?: continue
                    lines.add(TasteLine(name, "${(i.permille + 5) / 10}% of recent plays", listOf(i.topArtists.take(3).joinToString(", "), i.topSongs.take(2).joinToString(", ")).filter { it.isNotEmpty() }.joinToString("; ")))
                }
            }
            "genres" -> {
                if (s.genres.isEmpty()) unknown.add("Genres need about ${com.theveloper.pixelplay.brain.YourSound.GENRES_MIN_PLAYS} plays whose genres are known.")
                for (g in s.genres.take(EXPLAIN_ROWS)) lines.add(TasteLine(g.name, "${(g.permille + 5) / 10}% of plays"))
            }
            "dayparts" -> {
                if (s.dayparts.isEmpty()) unknown.add("Times of day need about ${com.theveloper.pixelplay.brain.YourSound.DAYPART_MIN_PLAYS} plays in a part of the day.")
                for (d in s.dayparts) lines.add(TasteLine(DAYPART_NAMES.getOrElse(d.daypart) { "daypart ${d.daypart}" }, "${d.plays} plays", d.leadingIsland?.let { "most often: $it" }.orEmpty()))
            }
            "recent_moves" -> {
                if (s.lately.isEmpty) unknown.add("Recent moves need about ${com.theveloper.pixelplay.brain.YourSound.LATELY_MIN_BASELINE_PLAYS} plays before the last two weeks.")
                for (n in s.lately.up) lines.add(TasteLine(n, "up in the last 14 days"))
                for (n in s.lately.down) lines.add(TasteLine(n, "down in the last 14 days"))
            }
            "model" -> {
                val m = s.model
                lines.add(
                    when (m.kind) {
                        ModelVoice.GETTING_TO_KNOW -> TasteLine("state", "still getting to know the user", "${m.gradedPlays} plays graded so far")
                        ModelVoice.STANDARD_RECIPE -> TasteLine("state", "using the standard recipe", "${m.gradedPlays} plays graded; the learned weights are not better yet")
                        else -> TasteLine("state", "learned weights in use", "${m.gradedPlays} plays graded, ${m.learnedFrom} moved the weights")
                    }
                )
                s.control?.let { c -> if (c.controlPlays > 0) lines.add(TasteLine("control sessions", "${c.controlPlays} plays ranked with the standard recipe")) }
                unknown.add("No real-data accuracy figure is stated here beyond the user's own graded plays on this phone.")
            }
            else -> {
                lines.add(TasteLine("plays recorded", s.plays.toString()))
                s.genres.take(3).forEach { lines.add(TasteLine("top genre", it.name, "${(it.permille + 5) / 10}% of plays")) }
                s.islands.take(3).forEach { i -> i.name?.let { lines.add(TasteLine("taste island", it, "${(i.permille + 5) / 10}% of recent plays")) } }
                s.novelty?.let { lines.add(TasteLine("new music", "${(it.permille + 5) / 10}% of plays were first listens")) }
                if (s.genres.isEmpty()) unknown.add("Genres are not known yet.")
                if (s.islands.isEmpty()) unknown.add("Taste islands are not known yet.")
            }
        }
        if (lines.isEmpty() && unknown.isEmpty()) unknown.add("Nothing is known for this part yet.")
        return TasteExplanation(scope, lines, unknown)
    }

    // ---- lookup_genre -----------------------------------------------------------------------------------

    override suspend fun lookupGenre(query: String): GenreLookup {
        val atlas = mixes.atlasProvider.atlas() ?: return GenreLookup(null, emptyList())
        val folded = BrainText.fold(query).trim()
        if (folded.isEmpty()) return GenreLookup(null, emptyList())
        val byNumber = query.trim().toIntOrNull()?.takeIf { atlas.genre(it) != null }
        val id = byNumber ?: atlas.idForName(query).takeIf { it != com.theveloper.pixelplay.brain.GenreAtlas.NONE }
        if (id != null) {
            val page = GenrePage.of(atlas, id) ?: return GenreLookup(null, emptyList())
            val g = atlas.genre(id)!!
            fun refs(ids: IntArray) = ids.toList().mapNotNull { atlas.genre(it)?.let { x -> GenreRef(x.id, x.name) } }
            val moods = g.descriptor?.moods?.toList()?.mapNotNull { atlas.moods.getOrNull(it) }.orEmpty()
            return GenreLookup(
                GenreInfo(
                    id = g.id, name = g.name, description = page.description.orEmpty(), parents = refs(page.parents), children = refs(page.children),
                    related = refs(page.related), influences = refs(page.influences), region = g.regions.firstOrNull { it.isNotBlank() }.orEmpty(),
                    era = if (g.eraStart in 1000..2999) g.eraStart else 0, descriptors = moods,
                ),
                emptyList(),
            )
        }
        // A mood: its id is its place in the atlas's mood list plus one (0 is "none").
        val moodIndex = atlas.moods.indexOfFirst { BrainText.fold(it).trim() == folded }
        if (moodIndex >= 0) {
            return GenreLookup(
                GenreInfo(
                    id = moodIndex + 1, name = atlas.moods[moodIndex], description = "", parents = emptyList(), children = emptyList(),
                    related = emptyList(), influences = emptyList(), region = "", era = 0, descriptors = emptyList(), isMood = true,
                ),
                emptyList(),
            )
        }
        // Not there: the five nearest names (whole-word containment either way, shortest name first). Never a guess at one id.
        val near = ArrayList<GenreRef>()
        for (gid in atlas.allIds) {
            val g = atlas.genre(gid) ?: continue
            val n = BrainText.fold(g.name).trim()
            if (n.isNotEmpty() && (" $n ".contains(" $folded ") || " $folded ".contains(" $n ") || n.startsWith(folded) || folded.startsWith(n))) near.add(GenreRef(g.id, g.name))
        }
        near.sortWith(compareBy({ it.name.length }, { it.id }))
        return GenreLookup(null, near.take(5))
    }

    // ---- similar_artists --------------------------------------------------------------------------------

    override suspend fun similarArtists(artist: String, count: Int, onlyUnplayed: Boolean): SimilarArtists = withContext(Dispatchers.IO) {
        val key = BrainText.fold(artist)
        if (key.isEmpty()) return@withContext SimilarArtists(emptyList(), "No artist was given.")
        // The artist's recordings the brain knows: library songs first, then alias-resolved names; the graph's edges are between recordings.
        val songs = LibraryIndex(mixes.musicRepository.getAllSongsOnce()).byPrimaryArtist(key).take(SEED_TRACKS)
        val rids = ArrayList<Long>()
        for (s in songs) {
            val keys = LibraryTrackCatalog.factsOf(s)?.keys ?: continue
            (keys.s ?: keys.n)?.let { k -> aliasDao.trackId(k)?.let { rids.add(it) } }
        }
        if (rids.isEmpty()) return@withContext SimilarArtists(emptyList(), "PixlAudio has no recordings of that artist in its graph yet, so it cannot say who is close.")
        val edges = graphEdgeDao.touching(rids.distinct())
        val own = rids.toHashSet()
        val weight = HashMap<Long, Double>()
        val sources = HashMap<Long, LinkedHashSet<String>>()
        for (e in edges) {
            val other = if (e.src in own) e.dst else e.src
            if (other in own) continue
            weight.merge(other, e.wQ16 / com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity.UNIT, Double::plus)
            sources.getOrPut(other) { LinkedHashSet() }.add(
                when (e.type) {
                    com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity.TYPE_RELATED -> "related list"
                    com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity.TYPE_CHIP -> "radio chip"
                    else -> "radio"
                }
            )
        }
        if (weight.isEmpty()) return@withContext SimilarArtists(emptyList(), "The graph has no neighbours of that artist yet.")
        val rows = mixes.trackDnaDao.getAll(weight.keys.toList()).associateBy { it.rid }
        val played = if (onlyUnplayed) brain.withState { st ->
            st.affinity.sortedKeys(com.theveloper.pixelplay.brain.Family.ARTIST).toHashSet()
        } else emptySet()
        val byArtist = LinkedHashMap<String, Triple<Double, LinkedHashSet<String>, Long>>()
        for ((rid, w) in weight) {
            val row = rows[rid] ?: continue
            val name = row.artistDisplay.substringBefore(", ").trim()
            if (name.isEmpty() || BrainText.fold(name) == key) continue
            if (onlyUnplayed) {
                val aid = mixes.identity.resolveArtistNames(listOf(name)).firstOrNull() ?: 0L
                if (aid in played) continue
            }
            val old = byArtist[name]
            byArtist[name] = Triple((old?.first ?: 0.0) + w, (old?.second ?: LinkedHashSet()).also { it.addAll(sources[rid].orEmpty()) }, old?.third ?: rid)
        }
        val top = byArtist.entries.sortedWith(compareByDescending<Map.Entry<String, Triple<Double, LinkedHashSet<String>, Long>>> { it.value.first }.thenBy { it.key })
            .take(count)
        val best = top.firstOrNull()?.value?.first ?: 1.0
        val out = top.map { (name, v) ->
            val ref = v.third.let { rid -> rows[rid]?.let { r -> r.ytAudioIds.split(',').firstOrNull { isVideoId(it) }?.let(AssistantRules::youTubeRef) ?: r.spotifyId?.takeIf(::isRealSpotifyId)?.let(AssistantRules::spotifyRef) } }.orEmpty()
            SimilarArtist(name, ref, (v.first / best).coerceIn(0.0, 1.0), v.second.toList())
        }
        SimilarArtists(out, if (out.isEmpty()) "Nobody close to them is known yet." else "Listed closest first, from the radios PixlAudio has cached. There is no percentage: do not give one.")
    }

    // ---- new_releases -----------------------------------------------------------------------------------

    override suspend fun newReleases(scope: String, count: Int): ReleaseList = withContext(Dispatchers.IO) {
        if (scope != "my_artists") {
            return@withContext ReleaseList(emptyList(), "PixlAudio only tracks new releases from the artists the user plays, so $scope is not available.")
        }
        val now = mixes.clock.nowMs()
        val cards = mixes.newReleases.cards(mixes.blockedArtists.current, mixes.tasteAdjustments.snapshot(now), LibraryIndex(mixes.musicRepository.getAllSongsOnce()))
        if (cards.isEmpty()) return@withContext ReleaseList(emptyList(), "No new releases from the user's artists have been found yet. The refresh looks again in the background.")
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ReleaseInfo>()
        for (c in cards) {
            if (!seen.add(c.release.browseId)) continue
            val track = EngineTrack.from(c.stored.row, c.stored.rid)
            val ref = AssistantRules.youTubeRef(track.videoId).takeIf { isVideoId(track.videoId) }.orEmpty()
            if (ref.isNotEmpty()) {
                runCatching { mixes.playableStore.register(listOf(track), now) }
                remember(Issued(ref, track.toSong(), c.stored.rid, track, c.release.artistName, null, null))
            }
            out.add(ReleaseInfo(c.release.title, c.release.artistName, c.release.kind.name.lowercase(), c.release.year, ref))
            if (out.size >= count) break
        }
        ReleaseList(out)
    }

    private companion object {
        const val MAX_ISSUED = 4_000
        const val MAX_MIXES = 16
        const val SEARCH_LIMIT = 8
        const val MIN_SCORE_NO_DURATION = 0.62f
        const val SEED_TRACKS = 6
        const val STEER_COUNT = 5
        const val EXPLAIN_ROWS = 8
        const val ASSISTANT_REC_TAG = 0xF4L
        val DAYPART_NAMES = listOf("morning", "afternoon", "evening", "night")
    }
}
