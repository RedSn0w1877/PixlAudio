package com.theveloper.pixelplay.data.brain.source

import javax.inject.Inject
import javax.inject.Singleton

/**
 * iTunes Search (docs/brain-spec.md, 3.3 B and 3.4 row 6): a song's `primaryGenreName` and release date, found by name. One
 * request per 3.5 s, 60 a day; 403 means slow down. A genre string here is mapped through the atlas's "itunes" table (about 80
 * names that matter), and a string the table does not know stays unknown.
 */
@Singleton
class ITunesSource @Inject constructor(private val http: KnowledgeHttp) {
    private val host = KnowledgeHost.ITUNES

    suspend fun search(want: TrackMatch.Want): Lookup<ITunesSong> {
        val reply = http.get(host, "https://itunes.apple.com/search?term=${SourceSupport.enc(want.artist + " " + want.title)}&entity=song&limit=5")
        return when (val all = SourceSupport.classify(reply) { KnowledgeParsers.itunes(it) }) {
            is Lookup.Found -> all.value.firstOrNull {
                TrackMatch.titleMatches(want, it.trackName) && TrackMatch.artistMatches(want, listOf(it.artistName)) &&
                    TrackMatch.durationClose(want.durationMs, it.durationMs)
            }?.let { Lookup.Found(it) } ?: Lookup.NotFound
            Lookup.NotFound -> Lookup.NotFound
            Lookup.Unavailable -> Lookup.Unavailable
        }
    }
}
