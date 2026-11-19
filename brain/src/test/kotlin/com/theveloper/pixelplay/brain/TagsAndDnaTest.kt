package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The tag vector (3.4) and the track DNA functions (3.2, 3.3, 3.5), checked by hand-worked cases on the real atlas. */
class TagsAndDnaTest {
    private val atlas by lazy { GenreAtlas.parse(Stage2bGolden.atlasText(File("."))) }
    private fun id(name: String): Int = atlas.idForName(name).also { assertTrue(it != GenreAtlas.NONE, name) }
    private fun input(source: Int, name: String, conf: Double = 1.0) = Tags.Input(source, id(name), conf)

    @Test
    fun `no claims is unknown, never a guess`() {
        assertTrue(Tags.merge(atlas, emptyList()).isEmpty)
        // One weak source alone stays under the 0.25 floor: 0.25 * 0.5 = 0.125.
        assertTrue(Tags.merge(atlas, listOf(input(Tags.SRC_ASSISTANT, "pop", 0.5))).isEmpty)
        // An id the atlas does not have, and a source that does not exist, are ignored.
        assertTrue(Tags.merge(atlas, listOf(Tags.Input(1, 31000, 1.0), Tags.Input(11, id("pop"), 1.0))).isEmpty)
    }

    @Test
    fun `agreement raises a weight and the vector is unit length before quantising`() {
        val one = Tags.merge(atlas, listOf(input(Tags.SRC_RECORDING, "shoegaze")))
        assertEquals(id("shoegaze"), one.top)
        val two = Tags.merge(atlas, listOf(input(Tags.SRC_RECORDING, "shoegaze"), input(Tags.SRC_ARTIST, "shoegaze"), input(Tags.SRC_RECORDING, "dream pop", 0.3)))
        assertEquals(id("shoegaze"), two.top)
        var sumSq = 0.0
        for (t in two.tags) sumSq += (t.q / 255.0) * (t.q / 255.0)
        assertTrue(sumSq in 0.97..1.03, "sum of squares $sumSq")
        assertTrue(two.tags.size <= Tags.MAX_TAGS)
    }

    @Test
    fun `a parent is spread to and the specific one outweighs the general one`() {
        val v = Tags.merge(atlas, listOf(input(Tags.SRC_RECORDING, "dance-pop")))
        val dance = v.tags.first { it.atlasId == id("dance-pop") }.q
        val pop = v.tags.firstOrNull { it.atlasId == id("pop") }?.q ?: 0
        assertTrue(pop > 0, "the parent is there")
        assertTrue(dance > pop, "dance-pop $dance vs pop $pop")
    }

    @Test
    fun `late sources run only when the first ones are weak`() {
        // A strong source answers: the related-artists vote is ignored.
        val strong = Tags.merge(atlas, listOf(input(Tags.SRC_RECORDING, "trap"), input(Tags.SRC_RELATED_ARTISTS, "drill")))
        assertTrue(strong.tags.none { it.atlasId == id("drill") })
        // Alone, the late source is allowed and 0.35 clears the floor.
        val weak = Tags.merge(atlas, listOf(input(Tags.SRC_RELATED_ARTISTS, "drill")))
        assertEquals(id("drill"), weak.top)
    }

    @Test
    fun `order of the claims and of repeats does not matter`() {
        val a = listOf(input(1, "k-pop"), input(6, "dance"), input(3, "pop", 0.4), input(4, "dance-pop", 0.7))
        val b = a.reversed() + input(1, "k-pop", 0.2)
        assertEquals(Tags.merge(atlas, a).toString(), Tags.merge(atlas, b).toString())
    }

    @Test
    fun `a track without tags borrows its artist's at 0 point 7`() {
        val artist = Tags.merge(atlas, listOf(input(Tags.SRC_ARTIST, "v-pop"), input(Tags.SRC_ARTIST, "vietnamese bolero", 0.5)))
        val track = Tags.fromArtist(artist)
        assertEquals(artist.tags.size, track.tags.size)
        for ((a, t) in artist.tags.zip(track.tags)) assertEquals(Math.floor(a.q * 0.7 + 0.5).toInt(), t.q)
        assertTrue(Tags.fromArtist(Tags.Vector.EMPTY).isEmpty)
    }

    @Test
    fun `the blob round trips`() {
        val v = Tags.merge(atlas, listOf(input(1, "k-pop"), input(2, "dance"), input(3, "hip hop", 0.5)))
        assertEquals(v.toString(), Tags.decode(Tags.encode(v)).toString())
        assertTrue(Tags.decode(null).isEmpty)
        assertTrue(Tags.decode(ByteArray(2)).isEmpty)
    }

    @Test
    fun `tagdot is 1 for the same vector and 0 for none shared`() {
        val a = Tags.merge(atlas, listOf(input(1, "shoegaze")))
        val b = Tags.merge(atlas, listOf(input(1, "techno")))
        assertTrue(Tags.tagdot(a, a) in 0.97..1.03)
        assertEquals(0.0, Tags.tagdot(a, Tags.Vector.EMPTY))
        assertTrue(Tags.tagdot(a, b) < 0.3)
    }

    @Test
    fun `raw strings are placed by id first and a source table second`() {
        val shoegazeMbid = atlas.genre(id("shoegaze"))!!.mbid
        val ins = Tags.inputs(
            atlas, Tags.SRC_RECORDING, null,
            listOf(Tags.Raw("something else", shoegazeMbid, 10), Tags.Raw("dream pop", "", 5), Tags.Raw("junk words", "", 1), Tags.Raw("zero", "", 0)),
        )
        assertEquals(2, ins.size)
        assertEquals(1.0, ins[0].conf)
        assertEquals(0.5, ins[1].conf)
        val dz = Tags.inputs(atlas, Tags.SRC_DEEZER_ALBUM, "deezer", listOf(Tags.Raw("All"), Tags.Raw("Pop")))
        assertEquals(1, dz.size)
        assertEquals(id("pop"), dz[0].atlasId)
    }

    // ── DNA ──────────────────────────────────────────────────────────────────

    @Test
    fun `the year is the earliest believable, and never Deezer alone`() {
        fun y(vararg c: Pair<Int, Int>) = TrackDna.mergeYear(c.map { TrackDna.YearClaim(it.first, it.second) }, 2027)
        assertEquals(1991, y(TrackDna.YEAR_MUSICBRAINZ to 1991, TrackDna.YEAR_DEEZER to 2011).year)
        assertEquals(TrackDna.YEAR_MUSICBRAINZ, y(TrackDna.YEAR_MUSICBRAINZ to 1991, TrackDna.YEAR_DEEZER to 2011).source)
        assertEquals(0, y(TrackDna.YEAR_DEEZER to 2011).year)
        assertEquals(1999, y(TrackDna.YEAR_DEEZER to 1999, TrackDna.YEAR_ITUNES to 2005).year) // the earlier one, Deezer's, once another source agrees the track exists
        assertEquals(0, y(TrackDna.YEAR_MUSICBRAINZ to 1850, TrackDna.YEAR_ITUNES to 2300).year)
        assertEquals(2027, y(TrackDna.YEAR_SPOTIFY to 2027).year)
        assertEquals(1994, TrackDna.parseYear("1994-05-01"))
        assertEquals(0, TrackDna.parseYear("19940"))
        assertEquals(0, TrackDna.parseYear(null))
    }

    @Test
    fun `popularity follows the spec's formulas`() {
        assertEquals(541, TrackDna.popFromDeezerRank(541_811))
        assertEquals(1000, TrackDna.popFromDeezerRank(5_000_000))
        assertEquals(-1, TrackDna.popFromDeezerRank(0))
        // 47M plays: 1000 * log10(47,000,001) / 9.7 = 791.
        assertEquals(791, TrackDna.popFromPlays(47_000_000))
        assertEquals(1000, TrackDna.popFromPlays(9_999_999_999L))
        assertEquals(-1, TrackDna.popFromPlays(0))
        assertEquals(TrackDna.popFromListeners(100_000), 943)
        assertEquals(300, TrackDna.mergePopularity(300, 800, 100))
        assertEquals(800, TrackDna.mergePopularity(-1, 800, 100))
        assertEquals(-1, TrackDna.mergePopularity(-1, -1, -1))
        assertEquals(47_000_000L, TrackDna.parsePlayCount("47M plays"))
        assertEquals(1_200_000_000L, TrackDna.parsePlayCount("1.2B views"))
        assertEquals(12_345L, TrackDna.parsePlayCount("12,345 plays"))
        assertEquals(3_400L, TrackDna.parsePlayCount("3.4K"))
        assertEquals(0L, TrackDna.parsePlayCount("plays"))
    }

    @Test
    fun `energy and valence are the tag weighted mean, nudged by loudness`() {
        val kpop = Tags.merge(atlas, listOf(input(1, "k-pop")))
        val d = atlas.descriptor(id("k-pop"))!!
        val plain = TrackDna.feel(atlas, kpop, TrackDna.GAIN_UNKNOWN)
        assertTrue(plain.known)
        assertTrue(plain.energy in (d.energy - 25)..(d.energy + 25), "energy ${plain.energy} vs ${d.energy}")
        // A quiet master (-14.0 dB) gets less energy than a loud one (-4.0 dB); -9 dB is the neutral point.
        val quiet = TrackDna.feel(atlas, kpop, -140).energy
        val neutral = TrackDna.feel(atlas, kpop, -90).energy
        val loud = TrackDna.feel(atlas, kpop, -40).energy
        assertEquals(plain.energy, neutral)
        assertTrue(quiet < neutral && neutral < loud)
        assertEquals(neutral - 20, quiet)
        assertEquals(neutral + 20, loud)
        val none = TrackDna.feel(atlas, Tags.Vector.EMPTY, -40)
        assertEquals(-1, none.energy)
        assertEquals(-1, none.valence)
        assertTrue(TrackDna.moods(atlas, kpop).size in 1..4)
    }

    @Test
    fun `the language of a title`() {
        assertEquals(TrackDna.SCRIPT_VIETNAMESE, TrackDna.scriptLang("Chúng Ta Của Hiện Tại", ""))
        assertEquals(TrackDna.SCRIPT_KOREAN, TrackDna.scriptLang("사랑해", ""))
        assertEquals(TrackDna.SCRIPT_JAPANESE, TrackDna.scriptLang("ひまわりの約束", ""))
        assertEquals(TrackDna.SCRIPT_CHINESE, TrackDna.scriptLang("夜曲", ""))
        assertEquals(TrackDna.SCRIPT_LATIN, TrackDna.scriptLang("Ditto", "US"))
        assertEquals(TrackDna.SCRIPT_VIETNAMESE, TrackDna.scriptLang("Hanoi", "VN"))
        assertEquals(TrackDna.SCRIPT_UNKNOWN, TrackDna.scriptLang("1234", "VN"))
        assertEquals(TrackDna.SCRIPT_UNKNOWN, TrackDna.scriptLang("", ""))
    }
}
