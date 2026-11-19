package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.source.ITunesSong
import com.theveloper.pixelplay.data.brain.source.MbArtist
import com.theveloper.pixelplay.data.brain.source.MbGenreVote
import com.theveloper.pixelplay.data.brain.store.DnaSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Evidence to a stored row (docs/brain-spec.md, 3.2 to 3.5), without any network or database. */
class DnaAssemblerTest {
    private val atlas = KnowledgeFixtures.atlas
    private val now = 1_795_000_000_000L
    private fun id(n: String) = atlas.idForName(n).also { assertTrue(it >= 0, n) }
    private fun target(rid: Long = 1L, aid: Long = 5L) = DnaTarget(rid, aid, "sp$rid", null, "Song $rid", "Band", durationMs = 180_000)

    private fun vote(name: String, count: Int) = MbGenreVote(name, atlas.genre(id(name))!!.mbid, count)

    @Test
    fun `claims survive a round trip through their four bytes`() {
        val claims = listOf(DnaAssembler.Claim(DnaAssembler.P_MB_ARTIST, 1500, 255), DnaAssembler.Claim(DnaAssembler.P_ITUNES, 2200, 3))
        val back = DnaAssembler.decodeClaims(DnaAssembler.encodeClaims(claims))
        assertEquals(listOf(1500 to 255, 2200 to 3), back.map { it.atlasId to it.conf })
        assertEquals(listOf(DnaAssembler.P_MB_ARTIST, DnaAssembler.P_ITUNES), back.map { it.provider })
        assertTrue(DnaAssembler.decodeClaims(null).isEmpty())
        assertTrue(DnaAssembler.decodeClaims(ByteArray(3)).isEmpty())
    }

    @Test
    fun `a source that answers replaces its own claims and leaves the others`() {
        val first = DnaAssembler.assemble(
            target(), DnaEvidence(
                mbArtist = MbArtist("m1", "Band", "GB", listOf(vote("shoegaze", 10)), emptyList()),
                itunes = ITunesSong("Song 1", "Band", 180_000, "Alternative", "1991-01-01T00:00:00Z", false), tried = DnaSource.MUSICBRAINZ or DnaSource.ITUNES,
            ),
            null, null, atlas, 1, now, 2027,
        )
        val topFirst = Tags.decode(first.track.tags).top
        assertEquals(id("shoegaze"), topFirst)
        // Later MusicBrainz says something else; iTunes is not asked again and keeps its claim.
        val second = DnaAssembler.assemble(
            target(), DnaEvidence(mbArtist = MbArtist("m1", "Band", "GB", listOf(vote("techno", 10)), emptyList()), tried = DnaSource.MUSICBRAINZ),
            first.track, first.artist, atlas, 1, now + 1, 2027,
        )
        val ids = Tags.decode(second.track.tags).tags.map { it.atlasId }
        assertTrue(id("techno") in ids)
        assertTrue(id("shoegaze") !in ids, "the artist's old genres were replaced")
        assertTrue(id("alternative rock") in ids, "iTunes' claim stayed")
    }

    @Test
    fun `a second track by the same artist inherits the artist's genres with no request`() {
        val a = DnaAssembler.assemble(
            target(1L), DnaEvidence(mbArtist = MbArtist("m1", "Band", "GB", listOf(vote("shoegaze", 4)), emptyList()), tried = DnaSource.MUSICBRAINZ),
            null, null, atlas, 1, now, 2027,
        )
        val b = DnaAssembler.assemble(target(2L), DnaEvidence(tried = DnaSource.MUSICBRAINZ), null, a.artist, atlas, 1, now, 2027)
        assertEquals(id("shoegaze"), Tags.decode(b.track.tags).top)
        assertEquals(Tags.decode(a.artist!!.tags).top, Tags.decode(b.artist!!.tags).top)
    }

    @Test
    fun `an atlas that changed version is not merged with old ids`() {
        val a = DnaAssembler.assemble(
            target(), DnaEvidence(mbArtist = MbArtist("m1", "Band", "GB", listOf(vote("shoegaze", 4)), emptyList()), tried = DnaSource.MUSICBRAINZ),
            null, null, atlas, 1, now, 2027,
        )
        val b = DnaAssembler.assemble(target(), DnaEvidence(), a.track, a.artist, atlas, 2, now, 2027)
        assertEquals(0, b.track.tags.size, "the old claims used ids of another atlas: they are dropped, not guessed")
        assertEquals(2, b.track.atlasVersion)
    }

    @Test
    fun `energy and valence follow the tags and are unknown without them`() {
        val none = DnaAssembler.assemble(target(), DnaEvidence(), null, null, atlas, 1, now, 2027)
        assertEquals(-1, none.track.energy)
        assertEquals(-1, none.track.popPermille)
        assertEquals(0, none.track.firstYear)
        val dance = DnaAssembler.assemble(
            target(), DnaEvidence(itunes = ITunesSong("Song 1", "Band", 0, "Dance", null, null), tried = DnaSource.ITUNES), null, null, atlas, 1, now, 2027,
        )
        assertTrue(dance.track.energy > 150, "dance music is energetic: ${dance.track.energy}")
        val ambient = DnaAssembler.assemble(
            target(), DnaEvidence(itunes = ITunesSong("Song 1", "Band", 0, "Ambient", null, null), tried = DnaSource.ITUNES), null, null, atlas, 1, now, 2027,
        )
        assertTrue(ambient.track.energy < 100, "ambient is calm: ${ambient.track.energy}")
        assertTrue(TrackDna.UNKNOWN == -1)
    }

    @Test
    fun `the title's own version flags and the year the app already knows are kept`() {
        val t = DnaTarget(3L, 0L, "sp3", null, "Song (Live)", "Band", durationMs = 1, spotifyReleaseDate = "1999-05-05", ytYear = 1997, ytPlays = 47_000_000L, spotifyExplicit = true)
        val r = DnaAssembler.assemble(t, DnaEvidence(), null, null, atlas, 1, now, 2027)
        assertEquals(1, r.track.versionFlags and 1, "live")
        assertEquals(1997, r.track.firstYear)
        assertEquals(TrackDna.YEAR_YOUTUBE, r.track.yearSource)
        assertEquals(791, r.track.popPermille)
        assertEquals(1, r.track.explicit)
        assertEquals(null, r.artist, "no artist id, no artist row")
    }
}
