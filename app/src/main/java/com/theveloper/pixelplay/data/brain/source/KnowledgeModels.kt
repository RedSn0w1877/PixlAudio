package com.theveloper.pixelplay.data.brain.source

/**
 * The open-data hosts of the brain's knowledge layer (docs/brain-spec.md, 3.3 B and 3.7), with the spec's rate, daily ceiling and
 * the owner's third-party-terms decision: each has its own off switch (local and remote), and each is used at run time only.
 *
 * [terms] is what the handoff lists so the owner can switch a source off before charging anyone.
 */
enum class KnowledgeHost(
    val key: String,
    val host: String,
    /** Spacing between two requests: the polite rate of the host. */
    val minIntervalMs: Long,
    /** Requests a day, across every caller (3.7: "Daily ceiling"). */
    val dailyCeiling: Int,
    /** Requests that may go out back to back. */
    val burst: Int,
    /** Where the listener's tags come from, in words the Developer screen shows. */
    val label: String,
    val terms: String,
) {
    DEEZER(
        "deezer", "api.deezer.com", 200L, 600, 10, "Deezer",
        "Non-commercial API terms. Album genres, popularity, release date, tempo and loudness. Used at run time only, never stored beyond the device.",
    ),
    MUSICBRAINZ(
        "musicbrainz", "musicbrainz.org", 1_100L, 200, 3, "MusicBrainz",
        "Core data CC0; tag votes CC BY-NC-SA, so the votes are fetched at run time and never bundled. One request per 1.1 s and an app User-Agent, as MetaBrainz asks.",
    ),
    LISTENBRAINZ(
        "listenbrainz", "api.listenbrainz.org", 1_000L, 150, 3, "ListenBrainz",
        "Open data, derived from MusicBrainz tags (CC BY-NC-SA votes); popularity is the listener count. Used at run time only.",
    ),
    WIKIDATA(
        "wikidata", "query.wikidata.org", 6_000L, 3, 1, "Wikidata",
        "CC0. One SPARQL query per 100 artists.",
    ),
    ITUNES(
        "itunes", "itunes.apple.com", 3_500L, 60, 2, "iTunes Search",
        "Apple's search API terms: genre and release date of a song, looked up by name, one request per 3.5 s.",
    );

    companion object {
        fun byKey(key: String): KnowledgeHost? = entries.firstOrNull { it.key == key }
    }
}

// ── What the parsers read out of a response ─────────────────────────────────────────────────────────────────────────────

/** Deezer answers errors as HTTP 200 with an `error` object: [errorCode] is 0 for a real answer, 4 quota, 800 no data. */
class DeezerReply<T>(val value: T?, val errorCode: Int = 0) {
    val quota: Boolean get() = errorCode == 4
    val noData: Boolean get() = errorCode == 800 || (errorCode == 0 && value == null)
}

class DeezerTrackRef(
    val id: Long,
    val title: String,
    val artistName: String,
    val artistId: Long,
    val durationSec: Int,
    val rank: Long,
    val albumId: Long,
    val isrc: String?,
)

class DeezerTrack(
    val id: Long,
    val isrc: String?,
    val title: String,
    val durationSec: Int,
    val rank: Long,
    val releaseDate: String?,
    val bpm: Double,
    /** Loudness in dB; null when Deezer sent none. */
    val gain: Double?,
    val explicit: Boolean?,
    val albumId: Long,
    val artistId: Long,
    val artistName: String,
)

class DeezerAlbum(val id: Long, val genres: List<String>, val releaseDate: String?)

class MbGenreVote(val name: String, val mbid: String, val count: Int)

class MbRecordingHit(
    val mbid: String,
    val score: Int,
    val title: String,
    val disambiguation: String,
    val lengthMs: Int,
    val artistNames: List<String>,
    val artistMbid: String?,
    val firstReleaseDate: String?,
    val isrcs: List<String>,
)

class MbRecording(val mbid: String, val title: String, val firstReleaseDate: String?, val genres: List<MbGenreVote>, val tags: List<MbGenreVote>)

class MbArtist(val mbid: String, val name: String, val country: String, val genres: List<MbGenreVote>, val tags: List<MbGenreVote>)

/** A ListenBrainz tag: [scope] is `recording`, `artist` or `release_group`; [genreMbid] is empty when the tag is not a genre. */
class LbTag(val tag: String, val count: Int, val genreMbid: String, val scope: String)

class LbRecording(val mbid: String, val tags: List<LbTag>, val artistMbid: String?, val firstReleaseDate: String?, val year: Int)

class LbPopularity(val mbid: String, val listens: Long, val users: Long)

class WdArtist(val mbid: String, val wikidataId: String, val genreMbids: List<String>, val genreLabels: List<String>)

class ITunesSong(
    val trackName: String,
    val artistName: String,
    val durationMs: Int,
    val genre: String,
    val releaseDate: String?,
    val explicit: Boolean?,
)

// ── What a source call returns ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * [Found]: the source knows it. [NotFound]: the source answered and has nothing (the negative cache will keep the
 * enricher away for 14 days, doubling to 120). [Unavailable]: not asked or not answered (switched off, budget spent,
 * offline, an error, a quota): nothing is recorded, the next run tries again.
 */
sealed interface Lookup<out T> {
    class Found<T>(val value: T) : Lookup<T>
    data object NotFound : Lookup<Nothing>
    data object Unavailable : Lookup<Nothing>
}
