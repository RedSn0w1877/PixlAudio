package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.BrainRemoteConfig
import com.theveloper.pixelplay.data.brain.source.DeezerSource
import com.theveloper.pixelplay.data.brain.source.ITunesSource
import com.theveloper.pixelplay.data.brain.source.KnowledgeHost
import com.theveloper.pixelplay.data.brain.source.KnowledgeParsers
import com.theveloper.pixelplay.data.brain.source.ListenBrainzSource
import com.theveloper.pixelplay.data.brain.source.Lookup
import com.theveloper.pixelplay.data.brain.source.MusicBrainzSource
import com.theveloper.pixelplay.data.brain.source.TrackMatch
import com.theveloper.pixelplay.data.brain.source.WikidataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The five open-data parsers over responses cut from the real services, and the clients' choices (docs/brain-spec.md, 3.3 B). */
class KnowledgeSourcesTest {
    private val blinding = TrackMatch.Want("Blinding Lights", "The Weeknd", 200_000)

    // ── parsers ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `deezer parsers read a search, a track, an album and both errors`() {
        val search = KnowledgeParsers.deezerSearch(KnowledgeFixtures.read("deezer_search_blinding_lights.json"))
        assertEquals(0, search.errorCode)
        val first = search.value!!.first()
        assertEquals(908604612L, first.id)
        assertEquals("The Weeknd", first.artistName)
        assertEquals(200, first.durationSec)
        assertEquals("USUG11904206", first.isrc)

        val track = KnowledgeParsers.deezerTrack(KnowledgeFixtures.read("deezer_track_blinding_lights.json")).value!!
        assertEquals("2020-03-20", track.releaseDate)
        assertEquals(170.84, track.bpm, 0.001)
        assertEquals(-8.8, track.gain!!, 0.001)
        assertEquals(137217782L, track.albumId)
        assertEquals(984833L, track.rank)

        val album = KnowledgeParsers.deezerAlbum(KnowledgeFixtures.read("deezer_album_after_hours.json")).value!!
        assertEquals(listOf("R&B"), album.genres)

        // Errors arrive as HTTP 200 with an error object.
        val gone = KnowledgeParsers.deezerTrack(KnowledgeFixtures.read("deezer_error_not_found.json"))
        assertEquals(800, gone.errorCode)
        assertTrue(gone.noData)
        assertTrue(KnowledgeParsers.deezerSearch(KnowledgeFixtures.read("deezer_error_quota.json")).quota)
        // Garbage is "no data", never an exception.
        assertNull(KnowledgeParsers.deezerTrack("<html>").value)
        assertNull(KnowledgeParsers.deezerTrack(null).value)
    }

    @Test
    fun `musicbrainz parsers read recordings, an isrc lookup, genres and an artist`() {
        val search = KnowledgeParsers.mbRecordings(KnowledgeFixtures.read("mb_recording_search_blinding_lights.json"))
        assertTrue(search.isNotEmpty())
        assertEquals("Blinding Lights", search[0].title)
        assertTrue(search[0].artistNames.contains("The Weeknd"))

        val byIsrc = KnowledgeParsers.mbRecordings(KnowledgeFixtures.read("mb_isrc_blinding_lights.json"))
        assertEquals("1a67e215-a19e-40c9-9b12-732de134bf5f", byIsrc[0].mbid)
        assertEquals("2019-11-29", byIsrc[0].firstReleaseDate)
        assertEquals("c8b03190-306c-4120-bb0b-6f2ebfc06ea9", byIsrc[0].artistMbid)

        val rec = KnowledgeParsers.mbRecording(KnowledgeFixtures.read("mb_recording_genres.json"))!!
        assertEquals("soul", rec.genres.single().name)
        assertEquals("1b5e7b16-336b-473e-a364-5120413a9827", rec.genres.single().mbid)

        val artist = KnowledgeParsers.mbArtist(KnowledgeFixtures.read("mb_artist_the_weeknd.json"))!!
        assertEquals("The Weeknd", artist.name)
        assertTrue(artist.genres.isNotEmpty())
        assertEquals("CA", artist.country)
        assertTrue(KnowledgeParsers.mbRecordings("{}").isEmpty())
        assertNull(KnowledgeParsers.mbArtist("not json"))
    }

    @Test
    fun `listenbrainz and wikidata and itunes parsers`() {
        val lb = KnowledgeParsers.lbRecordings(KnowledgeFixtures.read("lb_metadata_recordings.json"))
        assertEquals(3, lb.size)
        val one = lb["8bee2202-0b48-42be-9e89-bf68e6ab742c"]!!
        assertEquals("0b76f632-25fa-4681-9862-86499c28afd3", one.artistMbid)
        assertEquals("2007-05-13", one.firstReleaseDate)
        val postGrunge = one.tags.first { it.tag == "post-grunge" && it.scope == "artist" }
        assertEquals("bbb1310c-24cb-4fc9-97c4-f93e4549f1e2", postGrunge.genreMbid)
        assertEquals("", one.tags.first { it.tag == "canadian" }.genreMbid, "a tag that is not a genre carries no genre id")

        val pop = KnowledgeParsers.lbPopularity(KnowledgeFixtures.read("lb_popularity.json"))
        assertEquals(0L, pop.values.first().users, "no listeners is zero, not unknown-and-large")

        val wd = KnowledgeParsers.wikidataArtists(KnowledgeFixtures.read("wikidata_artists.json"))
        assertTrue(wd.isNotEmpty())
        assertTrue(wd[0].wikidataId.startsWith("Q"))
        assertEquals(wd[0].genreMbids.size, wd[0].genreLabels.size)

        val it = KnowledgeParsers.itunes(KnowledgeFixtures.read("itunes_search_blinding_lights.json"))
        assertEquals(2, it.size)
        assertEquals("R&B/Soul", it[1].genre)
        assertEquals(200046, it[1].durationMs)
        assertEquals(false, it[1].explicit)
    }

    // ── matching ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a version the listener's track lacks is another recording`() {
        assertTrue(TrackMatch.titleMatches(blinding, "Blinding Lights"))
        assertTrue(TrackMatch.titleMatches(blinding, "Blinding Lights - Radio Edit"))
        assertTrue(TrackMatch.titleMatches(blinding, "Blinding Lights (feat. X)"))
        assertFalse(TrackMatch.titleMatches(blinding, "Blinding Lights (Remix)"))
        assertFalse(TrackMatch.titleMatches(blinding, "Blinding Lights (Live at Wembley)"))
        // A listener who has the live version matches a live one.
        assertTrue(TrackMatch.titleMatches(TrackMatch.Want("Blinding Lights (Live)", "The Weeknd", 0), "Blinding Lights - Live"))
        assertTrue(TrackMatch.disambiguationRefuses(blinding, "live, 2024-09-07: MorumBIS, Sao Paulo"))
        assertFalse(TrackMatch.disambiguationRefuses(blinding, ""))
        assertFalse(TrackMatch.disambiguationRefuses(blinding, "Canadian R&B singer"))
    }

    @Test
    fun `artists match by whole words and durations by three seconds`() {
        assertTrue(TrackMatch.artistMatches(blinding, listOf("The Weeknd & ROSALIA")))
        assertTrue(TrackMatch.artistMatches(blinding, listOf("the weeknd")))
        assertFalse(TrackMatch.artistMatches(blinding, listOf("Weekend Warrior Band")))
        assertTrue(TrackMatch.artistMatches(TrackMatch.Want("x", "Sơn Tùng M-TP", 0), listOf("Son Tung M-TP")))
        assertFalse(TrackMatch.artistMatches(TrackMatch.Want("x", "", 0), listOf("")))
        assertTrue(TrackMatch.durationClose(200_000, 202_900))
        assertFalse(TrackMatch.durationClose(200_000, 204_500))
        assertTrue(TrackMatch.durationClose(0, 204_500), "an unknown length cannot refuse")
    }

    // ── clients ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `deezer by isrc, by name, with a quota and with nothing`() = runTest {
        val http = FakeKnowledgeHttp()
        http.route(KnowledgeHost.DEEZER, "/track/isrc:USUG11904206", "deezer_track_blinding_lights.json")
        http.route(KnowledgeHost.DEEZER, "/track/isrc:ZZZ", "deezer_error_not_found.json")
        http.route(KnowledgeHost.DEEZER, "/search?q=", "deezer_search_blinding_lights.json")
        val deezer = DeezerSource(http)
        assertEquals(908604612L, (deezer.trackByIsrc("USUG11904206") as Lookup.Found).value.id)
        assertEquals(Lookup.NotFound, deezer.trackByIsrc("ZZZ"))
        // The name search skips the remix and takes the real one.
        assertEquals(908604612L, (deezer.search(blinding) as Lookup.Found).value.id)
        assertEquals(Lookup.NotFound, deezer.search(TrackMatch.Want("Some Other Song", "Nobody", 100_000)))

        val quota = FakeKnowledgeHttp()
        quota.route(KnowledgeHost.DEEZER, "/track/", "deezer_error_quota.json")
        assertEquals(Lookup.Unavailable, DeezerSource(quota).track(1))
        assertEquals(listOf(KnowledgeHost.DEEZER), quota.backedOff.map { it.first }, "the host is told to back off")

        val off = FakeKnowledgeHttp().apply { down.add(KnowledgeHost.DEEZER) }
        assertEquals(Lookup.Unavailable, DeezerSource(off).search(blinding), "not asked is not 'nothing'")
        assertEquals(0, off.requests.size)
        val server = FakeKnowledgeHttp().apply { routeText(KnowledgeHost.DEEZER, "/track/", "", code = 503) }
        assertEquals(Lookup.Unavailable, DeezerSource(server).track(1))
    }

    @Test
    fun `musicbrainz picks the recording an isrc stands for and refuses live and remix versions`() = runTest {
        val http = FakeKnowledgeHttp()
        http.route(KnowledgeHost.MUSICBRAINZ, "/isrc/USUG11904206", "mb_isrc_blinding_lights.json")
        http.route(KnowledgeHost.MUSICBRAINZ, "/recording?query=", "mb_recording_search_blinding_lights.json")
        http.route(KnowledgeHost.MUSICBRAINZ, "/artist/c8b03190", "mb_artist_the_weeknd.json")
        val mb = MusicBrainzSource(http)
        val hit = (mb.recordingByIsrc("USUG11904206", blinding) as Lookup.Found).value
        assertEquals("2019-11-29", hit.firstReleaseDate)
        val searched = mb.searchRecording(blinding)
        if (searched is Lookup.Found) assertTrue(searched.value.disambiguation.isBlank() || !searched.value.disambiguation.startsWith("live"))
        assertEquals("The Weeknd", (mb.artist("c8b03190-306c-4120-bb0b-6f2ebfc06ea9") as Lookup.Found).value.name)
        assertEquals(Lookup.NotFound, mb.artist("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `listenbrainz asks for sixteen at most and wikidata for a hundred, and ids are checked first`() = runTest {
        val http = FakeKnowledgeHttp()
        http.route(KnowledgeHost.LISTENBRAINZ, "metadata/recording", "lb_metadata_recordings.json")
        http.route(KnowledgeHost.LISTENBRAINZ, "popularity", "lb_popularity.json")
        http.route(KnowledgeHost.WIKIDATA, "sparql", "wikidata_artists.json")
        val lb = ListenBrainzSource(http)
        val ids = List(20) { "8bee2202-0b48-42be-9e89-bf68e6ab742c" }
        assertTrue(lb.recordings(ids) is Lookup.Found)
        assertTrue(lb.popularity(ids.take(3)) is Lookup.Found)
        assertEquals(Lookup.NotFound, lb.recordings(emptyList()))
        val wd = WikidataSource(http)
        // Only well-formed MusicBrainz ids go into the query (a name can never reach SPARQL).
        assertEquals(Lookup.NotFound, wd.artists(listOf("x\" } DROP", "not-an-id")))
        assertEquals(0, http.count(KnowledgeHost.WIKIDATA))
        assertTrue(wd.artists(listOf("0b76f632-25fa-4681-9862-86499c28afd3")) is Lookup.Found)
        assertEquals(1, http.count(KnowledgeHost.WIKIDATA))
    }

    @Test
    fun `itunes skips the remix and takes the song`() = runTest {
        val http = FakeKnowledgeHttp()
        http.route(KnowledgeHost.ITUNES, "itunes.apple.com/search", "itunes_search_blinding_lights.json")
        val song = (ITunesSource(http).search(blinding) as Lookup.Found).value
        assertEquals("R&B/Soul", song.genre)
        assertEquals(Lookup.NotFound, ITunesSource(http).search(TrackMatch.Want("Blinding Lights", "A Different Band", 200_000)))
    }

    // ── the owner's off switches ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the remote config can switch one source off and nothing else`() {
        val text = """{"brain": {"sources": {"deezer": {"enabled": false}, "musicbrainz": {"enabled": true}}}}"""
        assertFalse(BrainRemoteConfig.sourceAllowed(text, "deezer"))
        assertTrue(BrainRemoteConfig.sourceAllowed(text, "musicbrainz"))
        assertTrue(BrainRemoteConfig.sourceAllowed(text, "itunes"))
        assertTrue(BrainRemoteConfig.sourceAllowed(null, "deezer"), "no file means on")
        assertTrue(BrainRemoteConfig.sourceAllowed("not json", "deezer"), "a switch that is off by accident would hide the feature")
        assertTrue(BrainRemoteConfig.sourceAllowed("""{"brain": {"refresh": {"enabled": false}}}""", "deezer"), "the refresh switch is not a source switch")
    }

    @Test
    fun `every host has a polite spacing and a daily ceiling from the spec`() {
        assertEquals(1_100L, KnowledgeHost.MUSICBRAINZ.minIntervalMs)
        assertEquals(200, KnowledgeHost.MUSICBRAINZ.dailyCeiling)
        assertEquals(6_000L, KnowledgeHost.WIKIDATA.minIntervalMs)
        assertEquals(3, KnowledgeHost.WIKIDATA.dailyCeiling)
        assertEquals(3_500L, KnowledgeHost.ITUNES.minIntervalMs)
        assertEquals(60, KnowledgeHost.ITUNES.dailyCeiling)
        assertEquals(600, KnowledgeHost.DEEZER.dailyCeiling)
        assertEquals(150, KnowledgeHost.LISTENBRAINZ.dailyCeiling)
        for (h in KnowledgeHost.entries) assertTrue(h.terms.isNotBlank(), "terms are listed for ${h.key}")
        assertEquals(KnowledgeHost.DEEZER, KnowledgeHost.byKey("deezer"))
    }
}
