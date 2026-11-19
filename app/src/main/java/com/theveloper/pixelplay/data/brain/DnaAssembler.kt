package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.source.DeezerAlbum
import com.theveloper.pixelplay.data.brain.source.DeezerTrack
import com.theveloper.pixelplay.data.brain.source.ITunesSong
import com.theveloper.pixelplay.data.brain.source.LbPopularity
import com.theveloper.pixelplay.data.brain.source.LbRecording
import com.theveloper.pixelplay.data.brain.source.MbArtist
import com.theveloper.pixelplay.data.brain.source.MbRecordingHit
import com.theveloper.pixelplay.data.brain.source.WdArtist
import com.theveloper.pixelplay.data.brain.store.ArtistDnaEntity
import com.theveloper.pixelplay.data.brain.store.DnaSource
import com.theveloper.pixelplay.data.brain.store.TrackDnaEntity

/** One recording the enrichment is about, with everything the app already knows about it before any request goes out. */
class DnaTarget(
    val rid: Long,
    /** The primary artist's id; 0 when unknown. */
    val aid: Long,
    val spotifyId: String?,
    val isrc: String?,
    val title: String,
    val artist: String,
    val artistDisplay: String = artist,
    val durationMs: Int,
    val albumName: String? = null,
    val spotifyArtistIds: List<String> = emptyList(),
    val spotifyReleaseDate: String? = null,
    val spotifyExplicit: Boolean? = null,
    /** The listener's own Spotify artist genres already synced (`taste_seeds.genres`): no request needed. */
    val spotifyGenres: List<String> = emptyList(),
    /** Genre chips of the track's own radio (3.4 source 4). */
    val chipGenres: List<String> = emptyList(),
    /** What the track's cached YouTube Music row says: year, play count, explicit. */
    val ytYear: Int = 0,
    val ytPlays: Long = 0L,
    val ytExplicit: Boolean? = null,
    val videoId: String? = null,
)

/** What the sources returned for one target in one pass. A null is "not asked or no answer"; [tried] says which sources answered at all. */
class DnaEvidence(
    val deezerTrack: DeezerTrack? = null,
    val deezerAlbum: DeezerAlbum? = null,
    val mb: MbRecordingHit? = null,
    val mbArtist: MbArtist? = null,
    val lb: LbRecording? = null,
    val lbPopularity: LbPopularity? = null,
    val wd: WdArtist? = null,
    val itunes: ITunesSong? = null,
    /** `DnaSource` bits: the sources that answered (found or "nothing") and so replace what they said before. */
    val tried: Int = 0,
)

/**
 * Evidence to a [TrackDnaEntity] and an [ArtistDnaEntity] (docs/brain-spec.md, 3.2 to 3.5). Pure: no network, no clock, no
 * database; the enricher gathers the evidence and stores the result. A source that answered replaces what it said before; one
 * that did not keeps its old claims, so a partial run never loses what an earlier run learned.
 *
 * What is cut, and written in the handoff: Discogs (source 5), the related-artists vote (7), the shared-playlist vote (9) and the
 * assistant (10) are not gathered, and `emb` waits for the embeddings.
 */
object DnaAssembler {
    // The providers a stored claim can come from. Several map to one spec source number (3.4).
    const val P_LB_RECORDING = 1
    const val P_MB_ARTIST = 2
    const val P_SPOTIFY = 3
    const val P_CHIP = 4
    const val P_ITUNES = 6
    const val P_DEEZER_ALBUM = 8
    const val P_LB_ARTIST = 12
    const val P_WD_ARTIST = 13

    fun sourceOf(provider: Int): Int = when (provider) {
        P_LB_RECORDING -> Tags.SRC_RECORDING
        P_MB_ARTIST, P_LB_ARTIST, P_WD_ARTIST -> Tags.SRC_ARTIST
        P_SPOTIFY -> Tags.SRC_SPOTIFY
        P_CHIP -> Tags.SRC_CHIP
        P_ITUNES -> Tags.SRC_ITUNES
        P_DEEZER_ALBUM -> Tags.SRC_DEEZER_ALBUM
        else -> 0
    }

    /** True for the providers that describe the artist, not the track. */
    fun isArtistLevel(provider: Int): Boolean = provider == P_MB_ARTIST || provider == P_LB_ARTIST || provider == P_WD_ARTIST || provider == P_SPOTIFY

    /**
     * Which stored providers this pass's evidence replaces: a provider is replaced only when it came back with something, so an
     * answer that was reused from the artist's stored row, or a response that failed, never wipes what was known.
     */
    private fun replacedBy(target: DnaTarget, e: DnaEvidence): Set<Int> {
        val out = HashSet<Int>()
        if (e.deezerAlbum != null) out.add(P_DEEZER_ALBUM)
        if (e.mbArtist != null) out.add(P_MB_ARTIST)
        if (e.lb != null) { out.add(P_LB_RECORDING); out.add(P_LB_ARTIST) }
        if (e.wd != null) out.add(P_WD_ARTIST)
        if (e.itunes != null) out.add(P_ITUNES)
        if (target.spotifyGenres.isNotEmpty()) out.add(P_SPOTIFY)
        if (target.chipGenres.isNotEmpty()) out.add(P_CHIP)
        return out
    }

    /** One stored claim: who said it, which atlas node, how sure (0..255). */
    class Claim(val provider: Int, val atlasId: Int, val conf: Int)

    fun encodeClaims(claims: List<Claim>): ByteArray {
        val out = ByteArray(claims.size * 4)
        for ((i, c) in claims.withIndex()) {
            out[i * 4] = c.provider.toByte()
            out[i * 4 + 1] = (c.atlasId ushr 8).toByte()
            out[i * 4 + 2] = c.atlasId.toByte()
            out[i * 4 + 3] = c.conf.toByte()
        }
        return out
    }

    fun decodeClaims(bytes: ByteArray?): List<Claim> {
        if (bytes == null || bytes.size < 4) return emptyList()
        return List(bytes.size / 4) { i ->
            Claim(
                provider = bytes[i * 4].toInt() and 0xFF,
                atlasId = ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 4 + 2].toInt() and 0xFF),
                conf = bytes[i * 4 + 3].toInt() and 0xFF,
            )
        }
    }

    private fun claimsFor(atlas: GenreAtlas, provider: Int, source: Int, table: String?, raws: List<Tags.Raw>): List<Claim> =
        Tags.inputs(atlas, source, table, raws).map { Claim(provider, it.atlasId, (it.conf * 255.0 + 0.5).toInt().coerceIn(0, 255)) }

    /** The claims this pass's evidence makes, by provider. */
    fun claimsOf(target: DnaTarget, e: DnaEvidence, atlas: GenreAtlas): List<Claim> {
        val out = ArrayList<Claim>()
        e.lb?.let { lb ->
            out += claimsFor(atlas, P_LB_RECORDING, Tags.SRC_RECORDING, null, lb.tags.filter { it.scope == "recording" }.map { Tags.Raw(it.tag, it.genreMbid, it.count) })
            out += claimsFor(atlas, P_LB_ARTIST, Tags.SRC_ARTIST, null, lb.tags.filter { it.scope == "artist" }.map { Tags.Raw(it.tag, it.genreMbid, it.count) })
        }
        e.mbArtist?.let { a -> out += claimsFor(atlas, P_MB_ARTIST, Tags.SRC_ARTIST, null, a.genres.map { Tags.Raw(it.name, it.mbid, it.count) }) }
        e.wd?.let { w ->
            val raws = w.genreMbids.mapIndexed { i, id -> Tags.Raw(w.genreLabels.getOrNull(i).orEmpty(), id) }
            out += claimsFor(atlas, P_WD_ARTIST, Tags.SRC_ARTIST, null, raws)
        }
        if (target.spotifyGenres.isNotEmpty()) out += claimsFor(atlas, P_SPOTIFY, Tags.SRC_SPOTIFY, null, target.spotifyGenres.map { Tags.Raw(it) })
        if (target.chipGenres.isNotEmpty()) out += claimsFor(atlas, P_CHIP, Tags.SRC_CHIP, "chip", target.chipGenres.map { Tags.Raw(it) })
        e.itunes?.let { if (it.genre.isNotBlank()) out += claimsFor(atlas, P_ITUNES, Tags.SRC_ITUNES, "itunes", listOf(Tags.Raw(it.genre))) }
        e.deezerAlbum?.let { a -> out += claimsFor(atlas, P_DEEZER_ALBUM, Tags.SRC_DEEZER_ALBUM, "deezer", a.genres.map { Tags.Raw(it) }) }
        return out
    }

    private fun merged(atlas: GenreAtlas, claims: List<Claim>, artistLevelOnly: Boolean): Tags.Vector =
        Tags.merge(
            atlas,
            claims.filter { !artistLevelOnly || isArtistLevel(it.provider) }
                .map { Tags.Input(sourceOf(it.provider), it.atlasId, it.conf / 255.0) },
        )

    /** Prior claims with the providers that answered now replaced by the fresh ones. */
    private fun replaceClaims(prior: List<Claim>, fresh: List<Claim>, drop: Set<Int>): List<Claim> =
        prior.filter { it.provider !in drop } + fresh

    private fun parseClaims(text: String): List<Pair<Int, Int>> = text.split(',').mapNotNull {
        val i = it.indexOf(':')
        if (i <= 0) return@mapNotNull null
        val a = it.substring(0, i).toIntOrNull() ?: return@mapNotNull null
        val b = it.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
        a to b
    }

    private fun yearSourceOf(tried: Int): Set<Int> {
        val out = HashSet<Int>()
        if (tried and DnaSource.MUSICBRAINZ != 0) out.add(TrackDna.YEAR_MUSICBRAINZ)
        if (tried and DnaSource.ITUNES != 0) out.add(TrackDna.YEAR_ITUNES)
        if (tried and DnaSource.DEEZER != 0) out.add(TrackDna.YEAR_DEEZER)
        return out
    }

    class Result(val track: TrackDnaEntity, val artist: ArtistDnaEntity?)

    /**
     * Builds the stored rows. [prior] and [priorArtist] are what is in the database (null on a first run); [nowMs] stamps the parts
     * that were refreshed; [maxYear] is the current year plus one.
     */
    fun assemble(
        target: DnaTarget,
        evidence: DnaEvidence,
        prior: TrackDnaEntity?,
        priorArtist: ArtistDnaEntity?,
        atlas: GenreAtlas,
        atlasVersion: Int,
        nowMs: Long,
        maxYear: Int,
    ): Result {
        val tried = evidence.tried or DnaSource.SPOTIFY_GENRES.takeIf { target.spotifyGenres.isNotEmpty() }.orZero() or
            DnaSource.YOUTUBE_CHIPS.takeIf { target.chipGenres.isNotEmpty() }.orZero()
        val fresh = claimsOf(target, evidence, atlas)
        val priorClaims = if (prior != null && prior.atlasVersion == atlasVersion) decodeClaims(prior.tagInputs) else emptyList()
        // The artist: only the artist-level claims, kept per artist so a track with no tags of its own can borrow them (3.4), and so
        // a second track by the same artist does not ask again for what the first one learned.
        val priorArtistClaims = if (priorArtist != null && priorArtist.atlasVersion == atlasVersion) decodeClaims(priorArtist.tagInputs) else emptyList()
        val drop = replacedBy(target, evidence)
        val artistClaims = replaceClaims(priorArtistClaims, fresh.filter { isArtistLevel(it.provider) }, drop).filter { isArtistLevel(it.provider) }
        val ownClaims = replaceClaims(priorClaims, fresh, drop)
        val have = ownClaims.map { it.provider }.toSet()
        val claims = ownClaims + artistClaims.filter { it.provider !in have }
        val ownTags = merged(atlas, claims, artistLevelOnly = false)
        val artistTags = merged(atlas, artistClaims, artistLevelOnly = true)
        val tags = if (!ownTags.isEmpty) ownTags else Tags.fromArtist(artistTags)

        // Year: every source's claim, the earliest believable wins; a source that answered replaces its old claim.
        val oldYear = prior?.yearClaims?.let(::parseClaims).orEmpty().filter { it.first !in yearSourceOf(tried) }
        val newYear = ArrayList<Pair<Int, Int>>()
        evidence.mb?.firstReleaseDate?.let { TrackDna.parseYear(it).takeIf { y -> y > 0 }?.let { y -> newYear.add(TrackDna.YEAR_MUSICBRAINZ to y) } }
        evidence.lb?.let { lb -> (TrackDna.parseYear(lb.firstReleaseDate).takeIf { it > 0 } ?: 0).takeIf { it > 0 }?.let { y -> newYear.add(TrackDna.YEAR_MUSICBRAINZ to y) } }
        evidence.itunes?.releaseDate?.let { TrackDna.parseYear(it).takeIf { y -> y > 0 }?.let { y -> newYear.add(TrackDna.YEAR_ITUNES to y) } }
        evidence.deezerTrack?.releaseDate?.let { TrackDna.parseYear(it).takeIf { y -> y > 0 }?.let { y -> newYear.add(TrackDna.YEAR_DEEZER to y) } }
        // Spotify's album date and the YouTube row's year are facts the app already holds: they are claims on every pass.
        val own = ArrayList<Pair<Int, Int>>()
        TrackDna.parseYear(target.spotifyReleaseDate).takeIf { it > 0 }?.let { own.add(TrackDna.YEAR_SPOTIFY to it) }
        if (target.ytYear > 0) own.add(TrackDna.YEAR_YOUTUBE to target.ytYear)
        val yearClaims = (oldYear.filter { it.first != TrackDna.YEAR_SPOTIFY && it.first != TrackDna.YEAR_YOUTUBE } + newYear + own)
        val year = TrackDna.mergeYear(yearClaims.map { TrackDna.YearClaim(it.first, it.second) }, maxYear)

        // Popularity: Deezer's rank, the YouTube play count, ListenBrainz's listeners.
        val oldPop = prior?.popClaims?.split(',')?.mapNotNull { it.takeIf { s -> s.length > 2 && s[1] == ':' } }.orEmpty()
        fun oldClaim(prefix: Char) = oldPop.firstOrNull { it[0] == prefix }?.substring(2)?.toIntOrNull() ?: -1
        val dz = evidence.deezerTrack?.let { TrackDna.popFromDeezerRank(it.rank) } ?: oldClaim('d')
        val yt = if (target.ytPlays > 0) TrackDna.popFromPlays(target.ytPlays) else oldClaim('y')
        val lb = evidence.lbPopularity?.let { TrackDna.popFromListeners(it.users) } ?: oldClaim('l')
        val pop = TrackDna.mergePopularity(dz, yt, lb)
        val popClaims = listOfNotNull(dz.takeIf { it >= 0 }?.let { "d:$it" }, yt.takeIf { it >= 0 }?.let { "y:$it" }, lb.takeIf { it >= 0 }?.let { "l:$it" }).joinToString(",")
        val popRefreshed = evidence.deezerTrack != null || evidence.lbPopularity != null || target.ytPlays > 0

        // Tempo and loudness: Deezer, when it has them.
        val bpm = evidence.deezerTrack?.bpm?.takeIf { it > 0.0 }?.let { (it * 10.0 + 0.5).toInt() }
        val tempo = bpm ?: prior?.tempoBpmX10 ?: 0
        val tempoSource = if (bpm != null) 1 else prior?.tempoSource ?: 0
        val gain = evidence.deezerTrack?.gain?.let { Math.floor(it * 10.0 + 0.5).toInt() } ?: prior?.gainDbX10 ?: TrackDna.GAIN_UNKNOWN

        val feel = TrackDna.feel(atlas, tags, gain)
        val moods = TrackDna.moods(atlas, tags)
        val flags = BrainText.cleanTitle(target.title).versionFlags

        val explicitFlag: Boolean? = target.spotifyExplicit ?: evidence.deezerTrack?.explicit ?: evidence.itunes?.explicit ?: target.ytExplicit
        val explicit = when (explicitFlag) { true -> 1; false -> 0; null -> prior?.explicit ?: -1 }

        val sources = claims.filter { sourceOf(it.provider) != 0 }.map { sourceOf(it.provider) }.toSet().size
        val confidence = if (tags.isEmpty) 0 else minOf(255, sources * 64)
        // The tags' clock moves only when a source that carries genres answered: a weekly popularity refresh must not keep them young.
        val tagsRefreshed = evidence.deezerAlbum != null || evidence.mbArtist != null || evidence.lb != null || evidence.wd != null || evidence.itunes != null

        val artistIds = if (target.aid != 0L) target.aid.toString() else prior?.artistIds.orEmpty()
        val track = TrackDnaEntity(
            rid = target.rid,
            spotifyId = target.spotifyId ?: prior?.spotifyId,
            isrc = target.isrc ?: evidence.deezerTrack?.isrc ?: evidence.mb?.isrcs?.firstOrNull() ?: prior?.isrc,
            deezerId = evidence.deezerTrack?.id ?: prior?.deezerId,
            mbRecordingId = evidence.mb?.mbid ?: prior?.mbRecordingId,
            ytAudioIds = listOfNotNull(target.videoId).plus(prior?.ytAudioIds?.split(',').orEmpty()).filter { it.isNotBlank() }.distinct().take(6).joinToString(","),
            artistIds = artistIds,
            albumKey = target.albumName?.takeIf { it.isNotBlank() }?.let { BrainHash.fnv1a64(BrainText.fold(it)) } ?: prior?.albumKey,
            title = target.title,
            artistDisplay = target.artistDisplay,
            versionFlags = flags,
            firstYear = year.year,
            yearSource = year.source,
            durationMs = if (target.durationMs > 0) target.durationMs else prior?.durationMs ?: 0,
            explicit = explicit,
            popPermille = pop,
            tempoBpmX10 = tempo,
            tempoSource = tempoSource,
            gainDbX10 = gain,
            scriptLang = TrackDna.scriptLang(target.title, priorArtist?.country.orEmpty().ifEmpty { evidence.mbArtist?.country.orEmpty() }),
            tags = Tags.encode(tags),
            moods = ByteArray(moods.size) { moods[it].toByte() },
            energy = feel.energy,
            valence = feel.valence,
            embVersion = prior?.embVersion ?: 0,
            confidence = confidence,
            fetchedMask = (prior?.fetchedMask ?: 0) or tried,
            updatedAtMs = nowMs,
            tagsAtMs = if (tagsRefreshed) nowMs else prior?.tagsAtMs ?: 0L,
            popAtMs = if (popRefreshed) nowMs else prior?.popAtMs ?: 0L,
            atlasVersion = atlasVersion,
            tagInputs = encodeClaims(claims),
            yearClaims = yearClaims.filter { it.first != TrackDna.YEAR_SPOTIFY && it.first != TrackDna.YEAR_YOUTUBE }.joinToString(",") { "${it.first}:${it.second}" },
            popClaims = popClaims,
        )

        val artist = if (target.aid == 0L) null else ArtistDnaEntity(
            aid = target.aid,
            spotifyId = target.spotifyArtistIds.firstOrNull() ?: priorArtist?.spotifyId,
            ytChannelId = priorArtist?.ytChannelId,
            deezerId = evidence.deezerTrack?.artistId?.takeIf { it != 0L } ?: priorArtist?.deezerId,
            mbid = evidence.mb?.artistMbid ?: evidence.mbArtist?.mbid ?: priorArtist?.mbid,
            wikidataId = evidence.wd?.wikidataId ?: priorArtist?.wikidataId,
            name = target.artist,
            country = evidence.mbArtist?.country?.takeIf { it.isNotBlank() } ?: priorArtist?.country.orEmpty(),
            tags = Tags.encode(artistTags),
            popPermille = priorArtist?.popPermille ?: -1,
            releaseIds = priorArtist?.releaseIds.orEmpty(),
            fetchedMask = (priorArtist?.fetchedMask ?: 0) or (tried and ARTIST_SOURCES),
            updatedAtMs = nowMs,
            tagsAtMs = if (evidence.mbArtist != null || evidence.lb != null || evidence.wd != null) nowMs else priorArtist?.tagsAtMs ?: 0L,
            atlasVersion = atlasVersion,
            tagInputs = encodeClaims(artistClaims),
        )
        return Result(track, artist)
    }

    private const val ARTIST_SOURCES = DnaSource.MUSICBRAINZ or DnaSource.LISTENBRAINZ or DnaSource.WIKIDATA or DnaSource.SPOTIFY_GENRES

    private fun Int?.orZero(): Int = this ?: 0
}
