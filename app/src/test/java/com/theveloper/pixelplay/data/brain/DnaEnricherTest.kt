package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.source.DeezerSource
import com.theveloper.pixelplay.data.brain.source.ITunesSource
import com.theveloper.pixelplay.data.brain.source.KnowledgeHost
import com.theveloper.pixelplay.data.brain.source.ListenBrainzSource
import com.theveloper.pixelplay.data.brain.source.MusicBrainzSource
import com.theveloper.pixelplay.data.brain.source.WikidataSource
import com.theveloper.pixelplay.data.brain.store.DnaSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The DNA lanes' work (docs/brain-spec.md, 3.2 to 3.4, 3.7) over fixtures cut from real responses: what a track's row says after
 * the sources answered, that fresh rows are not asked again, that a source with nothing is remembered for 14 days and one that
 * could not be asked is not, and that a stopped run still keeps what it gathered.
 */
class DnaEnricherTest {
    private val atlas = KnowledgeFixtures.atlas
    private var now = 1_795_000_000_000L
    private val clock = BrainClock { now }
    private val tracks = FakeTrackDnaDao()
    private val artists = FakeArtistDnaDao()
    private val misses = FakeSourceMissDao()

    private fun http(): FakeKnowledgeHttp = FakeKnowledgeHttp().apply {
        route(KnowledgeHost.DEEZER, "/track/isrc:USUG11904206", "deezer_track_blinding_lights.json")
        route(KnowledgeHost.DEEZER, "/album/137217782", "deezer_album_after_hours.json")
        route(KnowledgeHost.MUSICBRAINZ, "/isrc/USUG11904206", "mb_isrc_blinding_lights.json")
        route(KnowledgeHost.MUSICBRAINZ, "/artist/c8b03190", "mb_artist_the_weeknd.json")
        route(KnowledgeHost.ITUNES, "itunes.apple.com/search", "itunes_search_blinding_lights.json")
        route(KnowledgeHost.LISTENBRAINZ, "metadata/recording", "lb_metadata_recordings.json")
        route(KnowledgeHost.WIKIDATA, "sparql", "wikidata_artists.json")
    }

    private fun enricher(h: FakeKnowledgeHttp) = DnaEnricher(
        DeezerSource(h), MusicBrainzSource(h), ListenBrainzSource(h), WikidataSource(h), ITunesSource(h),
        tracks, artists, misses, FixtureAtlas(), clock,
    )

    private fun target(rid: Long = 11L, isrc: String? = "USUG11904206") = DnaTarget(
        rid = rid, aid = 77L, spotifyId = "sp$rid", isrc = isrc, title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000,
        albumName = "After Hours", spotifyReleaseDate = "2020", spotifyGenres = emptyList(),
    )

    @Test
    fun `one track becomes a row with an id, a year, a popularity, a tempo and genres from several sources`() = runTest {
        val h = http()
        val report = enricher(h).enrich(listOf(target())) { true }
        assertEquals(1, report.tracks)
        val row = tracks.rows[11L]!!
        assertEquals(908604612L, row.deezerId)
        assertEquals("1a67e215-a19e-40c9-9b12-732de134bf5f", row.mbRecordingId)
        assertEquals(2019, row.firstYear, "MusicBrainz's first release beats Deezer's re-release date")
        assertEquals(TrackDna.YEAR_MUSICBRAINZ, row.yearSource)
        assertEquals(TrackDna.popFromDeezerRank(984_833L), row.popPermille)
        assertEquals(1708, row.tempoBpmX10)
        assertEquals(-88, row.gainDbX10)
        assertEquals(0, row.explicit)
        assertTrue(row.energy in 0..255 && row.valence in 0..255)
        val tags = Tags.decode(row.tags)
        assertFalse(tags.isEmpty, "R&B from Deezer's album and iTunes, and the artist's genres from MusicBrainz")
        assertTrue(tags.tags.size <= Tags.MAX_TAGS)
        val names = tags.tags.map { atlas.genre(it.atlasId)!!.name }
        assertTrue(names.any { it == "r&b" || it == "contemporary r&b" || it == "synth-pop" || it == "pop" || it == "dance-pop" }, "tags were $names")
        assertEquals(DnaSource.DEEZER or DnaSource.MUSICBRAINZ or DnaSource.ITUNES, row.fetchedMask and (DnaSource.DEEZER or DnaSource.MUSICBRAINZ or DnaSource.ITUNES))
        assertEquals(now, row.tagsAtMs)
        // The artist row was written with the artist-level facts.
        val artist = artists.rows[77L]!!
        assertEquals("c8b03190-306c-4120-bb0b-6f2ebfc06ea9", artist.mbid)
        assertEquals("CA", artist.country)
        assertEquals(4050205L, artist.deezerId)
    }

    @Test
    fun `a fresh row is not asked again, and an expired popularity asks only for what expired`() = runTest {
        val h = http()
        enricher(h).enrich(listOf(target())) { true }
        val first = h.requests.size
        assertTrue(first > 0)
        now += 3L * 24 * 60 * 60 * 1000
        enricher(h).enrich(listOf(target())) { true }
        assertEquals(first, h.requests.size, "three days later nothing has expired")
        now += 5L * 24 * 60 * 60 * 1000
        h.requests.clear()
        enricher(h).enrich(listOf(target())) { true }
        assertTrue(h.requests.isNotEmpty(), "a week old popularity is asked for again")
        assertEquals(0, h.count(KnowledgeHost.MUSICBRAINZ), "ids, year and the artist's genres are not asked for again")
        assertEquals(0, h.count(KnowledgeHost.ITUNES))
        assertEquals(2019, tracks.rows[11L]!!.firstYear, "what was known before is kept")
    }

    @Test
    fun `a source with nothing is remembered for fourteen days, doubling, and one that could not be asked is not`() = runTest {
        val h = http()
        h.routes.removeAll { it.host == KnowledgeHost.DEEZER }
        h.routeText(KnowledgeHost.DEEZER, "/track/isrc:", """{"error":{"type":"DataException","message":"no data","code":800}}""")
        h.routeText(KnowledgeHost.DEEZER, "/search", """{"data":[]}""")
        enricher(h).enrich(listOf(target(isrc = "USUG11904206"))) { true }
        val miss = misses.rows[("t:11" to DnaSource.numberOf(DnaSource.DEEZER))]!!
        assertEquals(now + 14L * 24 * 60 * 60 * 1000, miss.retryAfter)
        assertEquals(1, miss.misses)
        assertTrue(tracks.rows[11L]!!.fetchedMask and DnaSource.DEEZER != 0, "it was tried")
        // Not asked again while the miss stands.
        val deezerAsked = h.count(KnowledgeHost.DEEZER)
        now += 8L * 24 * 60 * 60 * 1000 // popularity would be due, the miss still stands
        enricher(h).enrich(listOf(target())) { true }
        assertEquals(deezerAsked, h.count(KnowledgeHost.DEEZER))

        // A source that is off or out of budget answers "not asked": no miss, no bit, no empty row for it.
        val off = http().apply { down.add(KnowledgeHost.DEEZER); down.add(KnowledgeHost.MUSICBRAINZ); down.add(KnowledgeHost.ITUNES) }
        val fresh = FakeTrackDnaDao()
        val e = DnaEnricher(
            DeezerSource(off), MusicBrainzSource(off), ListenBrainzSource(off), WikidataSource(off), ITunesSource(off),
            fresh, FakeArtistDnaDao(), FakeSourceMissDao(), FixtureAtlas(), clock,
        )
        val r = e.enrich(listOf(DnaTarget(5L, 0L, "sp5", "ISRC", "Nothing Known", "Nobody", durationMs = 100_000))) { true }
        assertEquals(0, r.tracks, "nothing was learned and nothing was known: no row")
        assertTrue(fresh.rows.isEmpty())

        // A track that already has a row is saved again when nothing could be asked, so lane 5 moves on to the next one.
        val early = tracks.rows[11L]!!
        now += 9L * 24 * 60 * 60 * 1000
        DnaEnricher(
            DeezerSource(off), MusicBrainzSource(off), ListenBrainzSource(off), WikidataSource(off), ITunesSource(off),
            tracks, artists, misses, FixtureAtlas(), clock,
        ).enrich(listOf(target())) { true }
        assertEquals(now, tracks.rows[11L]!!.updatedAtMs, "it moved to the back of lane 5's order")
        assertEquals(early.firstYear, tracks.rows[11L]!!.firstYear, "nothing known was lost")
        assertEquals(early.popPermille, tracks.rows[11L]!!.popPermille)
    }

    @Test
    fun `what the app already knows is saved even when no source can be asked`() = runTest {
        val off = http().apply { KnowledgeHost.entries.forEach { down.add(it) } }
        val e = DnaEnricher(
            DeezerSource(off), MusicBrainzSource(off), ListenBrainzSource(off), WikidataSource(off), ITunesSource(off),
            tracks, artists, misses, FixtureAtlas(), clock,
        )
        val t = DnaTarget(
            rid = 9L, aid = 3L, spotifyId = "sp9", isrc = null, title = "Ditto", artist = "NewJeans", durationMs = 185_000,
            spotifyReleaseDate = "2022-12-19", spotifyGenres = listOf("k-pop", "k-pop girl group"), chipGenres = emptyList(),
        )
        assertEquals(1, e.enrich(listOf(t)) { true }.tracks)
        val row = tracks.rows[9L]!!
        assertEquals(2022, row.firstYear)
        assertEquals(TrackDna.YEAR_SPOTIFY, row.yearSource)
        assertEquals(atlas.idForName("k-pop"), Tags.decode(row.tags).top, "Spotify's genre string is placed through the atlas")
        assertEquals(DnaSource.SPOTIFY_GENRES, row.fetchedMask)
        assertTrue(misses.rows.isEmpty())
    }

    @Test
    fun `unknown genres stay absent`() = runTest {
        val off = http().apply { KnowledgeHost.entries.forEach { down.add(it) } }
        val e = DnaEnricher(
            DeezerSource(off), MusicBrainzSource(off), ListenBrainzSource(off), WikidataSource(off), ITunesSource(off),
            tracks, artists, misses, FixtureAtlas(), clock,
        )
        val t = DnaTarget(
            rid = 12L, aid = 4L, spotifyId = "sp12", isrc = null, title = "Song", artist = "Someone", durationMs = 1,
            spotifyGenres = listOf("zzz not a genre", "pov: indie moonbeam"), chipGenres = listOf("popular", "2010s"),
        )
        e.enrich(listOf(t)) { true }
        val row = tracks.rows[12L]!!
        assertEquals(0, row.tags.size, "no tag means no genre shown")
        assertEquals(-1, row.energy)
        assertEquals(-1, row.valence)
    }

    @Test
    fun `a gate that closes stops the run and what was gathered is kept`() = runTest {
        val h = http()
        var calls = 0
        val report = enricher(h).enrich(listOf(target(11L), target(12L, isrc = null).let { DnaTarget(12L, 78L, "sp12", null, "Other", "Nobody", durationMs = 1) })) {
            calls++
            calls < 3
        }
        assertTrue(report.stopped)
        assertNotNull(tracks.rows[11L], "the first track finished before the gate closed")
    }

    @Test
    fun `a year only Deezer gave is not believed, and a rebuilt claim set replaces only what answered`() = runTest {
        val h = http()
        h.routes.removeAll { it.host == KnowledgeHost.MUSICBRAINZ || it.host == KnowledgeHost.ITUNES }
        val noSpotifyYear = DnaTarget(rid = 21L, aid = 5L, spotifyId = "sp21", isrc = "USUG11904206", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000)
        enricher(h).enrich(listOf(noSpotifyYear)) { true }
        assertEquals(0, tracks.rows[21L]!!.firstYear, "Deezer's date alone is not a year")
        assertEquals(TrackDna.YEAR_NONE, tracks.rows[21L]!!.yearSource)
    }
}
