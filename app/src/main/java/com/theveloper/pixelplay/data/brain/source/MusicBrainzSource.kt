package com.theveloper.pixelplay.data.brain.source

import javax.inject.Inject
import javax.inject.Singleton

/**
 * MusicBrainz (docs/brain-spec.md, 3.3 B and 3.4): the recording behind an ISRC (its earliest `first-release-date` is the year
 * the brain believes first), a recording found by name when there is no ISRC, and an artist's genres. One request per 1.1 s and
 * about one call in three answers 503 at that rate, which the HTTP layer backs off from. Core data is CC0; tag votes are
 * CC BY-NC-SA, so they are used at run time only: the off switch is [KnowledgeHost.MUSICBRAINZ].
 */
@Singleton
class MusicBrainzSource @Inject constructor(private val http: KnowledgeHttp) {
    private val host = KnowledgeHost.MUSICBRAINZ

    /**
     * `GET /ws/2/isrc/{ISRC}?inc=artists`: the best recording of the ISRC the listener's track can be. Of those that pass the
     * title, artist and version checks, the one with the earliest first release date (a remix that shares an ISRC is refused
     * unless the listener's track is a remix too).
     */
    suspend fun recordingByIsrc(isrc: String, want: TrackMatch.Want): Lookup<MbRecordingHit> {
        val reply = http.get(host, "https://musicbrainz.org/ws/2/isrc/${SourceSupport.enc(isrc)}?inc=artists&fmt=json")
        return pick(reply, want, requireScore = false)
    }

    /** `GET /ws/2/recording?query=recording:"{title}" AND artist:"{artist}"`: for a track with no ISRC. */
    suspend fun searchRecording(want: TrackMatch.Want): Lookup<MbRecordingHit> {
        val q = "recording:\"${want.title.replace("\"", "")}\" AND artist:\"${want.artist.replace("\"", "")}\""
        val reply = http.get(host, "https://musicbrainz.org/ws/2/recording?query=${SourceSupport.enc(q)}&fmt=json&limit=5")
        return pick(reply, want, requireScore = true)
    }

    private fun pick(reply: KnowledgeHttp.Reply?, want: TrackMatch.Want, requireScore: Boolean): Lookup<MbRecordingHit> =
        when (val all = SourceSupport.classify(reply) { KnowledgeParsers.mbRecordings(it) }) {
            is Lookup.Found -> all.value.asSequence()
                .filter { !requireScore || it.score >= MIN_SEARCH_SCORE }
                .filter { TrackMatch.titleMatches(want, it.title) }
                .filter { it.artistNames.isEmpty() || TrackMatch.artistMatches(want, it.artistNames) }
                .filter { TrackMatch.durationClose(want.durationMs, it.lengthMs) }
                .filter { !TrackMatch.disambiguationRefuses(want, it.disambiguation) }
                .sortedBy { it.firstReleaseDate ?: "9999" }
                .firstOrNull()?.let { Lookup.Found(it) } ?: Lookup.NotFound
            Lookup.NotFound -> Lookup.NotFound
            Lookup.Unavailable -> Lookup.Unavailable
        }

    /** `GET /ws/2/artist/{mbid}?inc=genres+tags`: the artist's genres with their vote counts, and the country. */
    suspend fun artist(mbid: String): Lookup<MbArtist> =
        SourceSupport.classify(http.get(host, "https://musicbrainz.org/ws/2/artist/${SourceSupport.enc(mbid)}?inc=genres+tags&fmt=json")) {
            KnowledgeParsers.mbArtist(it)
        }

    companion object {
        const val MIN_SEARCH_SCORE = 85
    }
}
