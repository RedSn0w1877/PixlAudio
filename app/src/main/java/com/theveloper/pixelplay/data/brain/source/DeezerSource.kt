package com.theveloper.pixelplay.data.brain.source

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deezer (docs/brain-spec.md, 3.3 B): track identity by ISRC (16 of 16 found in the probe) or by a name search, then the track's
 * popularity rank, release date, tempo and loudness, and its album's genres. Errors arrive as HTTP 200 with an `error` object;
 * code 4 is the quota (back off), 800 is "no data". Deezer's API terms are non-commercial: the off switch is
 * [KnowledgeHost.DEEZER].
 */
@Singleton
class DeezerSource @Inject constructor(private val http: KnowledgeHttp) {
    private val host = KnowledgeHost.DEEZER

    private fun <T> lookup(reply: KnowledgeHttp.Reply?, parse: (String?) -> DeezerReply<T>): Lookup<T> {
        if (reply == null || !reply.ok) return if (reply?.code == 404) Lookup.NotFound else Lookup.Unavailable
        val r = parse(reply.body)
        if (r.quota) {
            http.backOff(host, QUOTA_BACKOFF_MS)
            return Lookup.Unavailable
        }
        if (r.errorCode != 0 && r.errorCode != 800) return Lookup.Unavailable
        val v = r.value ?: return Lookup.NotFound
        return Lookup.Found(v)
    }

    /** `GET /track/isrc:{ISRC}`. */
    suspend fun trackByIsrc(isrc: String): Lookup<DeezerTrack> =
        lookup(http.get(host, "https://api.deezer.com/track/isrc:${SourceSupport.enc(isrc)}"), KnowledgeParsers::deezerTrack)

    /** `GET /track/{id}`. */
    suspend fun track(id: Long): Lookup<DeezerTrack> =
        lookup(http.get(host, "https://api.deezer.com/track/$id"), KnowledgeParsers::deezerTrack)

    /**
     * `GET /search?q={artist} {title}`: the first hit whose title and primary artist match and whose length is within 3 s.
     * (Field-qualified search returns nothing today.)
     */
    suspend fun search(want: TrackMatch.Want): Lookup<DeezerTrackRef> {
        val reply = http.get(host, "https://api.deezer.com/search?q=${SourceSupport.enc(want.artist + " " + want.title)}&limit=5")
        return when (val found = lookup(reply, KnowledgeParsers::deezerSearch)) {
            is Lookup.Found -> found.value.firstOrNull {
                TrackMatch.titleMatches(want, it.title) && TrackMatch.artistMatches(want, listOf(it.artistName)) &&
                    TrackMatch.durationClose(want.durationMs, it.durationSec * 1000)
            }?.let { Lookup.Found(it) } ?: Lookup.NotFound
            Lookup.NotFound -> Lookup.NotFound
            Lookup.Unavailable -> Lookup.Unavailable
        }
    }

    /** `GET /album/{id}`: its genres. */
    suspend fun album(id: Long): Lookup<DeezerAlbum> =
        lookup(http.get(host, "https://api.deezer.com/album/$id"), KnowledgeParsers::deezerAlbum)

    companion object {
        const val QUOTA_BACKOFF_MS = 60_000L
    }
}
