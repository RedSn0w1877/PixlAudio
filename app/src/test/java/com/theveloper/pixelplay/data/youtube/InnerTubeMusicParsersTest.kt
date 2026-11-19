package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parsers against saved YouTube Music responses (`app/src/test/resources/innertube`, cut by
 * `tools/brain/cut_fixtures.py` from anonymous captures of famous public tracks).
 *
 * Each `<name>.expected.json` was written by that script's own plain extraction, not by these parsers,
 * so a parser bug cannot hide behind its own output. The iOS parser tests read the same files.
 */
class InnerTubeMusicParsersTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): JsonElement {
        val stream = javaClass.classLoader!!.getResourceAsStream("innertube/$name")
            ?: error("missing test resource innertube/$name")
        return json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun expectedArtists(row: JsonObject): List<RadioArtistRef> =
        (row["artists"] as? JsonArray).orEmpty().map { it.jsonObject }.map { RadioArtistRef(it.str("name")!!, it.str("id")) }

    private fun assertRows(expected: JsonArray, actual: List<RadioRow>, what: String) {
        assertEquals(expected.size, actual.size, "$what: row count")
        for (i in expected.indices) {
            val e = expected[i].jsonObject
            val a = actual[i]
            val at = "$what row $i (${e.str("title")})"
            assertEquals(e.str("videoId"), a.videoId, at)
            assertEquals(e.str("title"), a.title, at)
            assertEquals(expectedArtists(e), a.artists, at)
            assertEquals(e.str("album"), a.album, at)
            assertEquals(e.str("albumId"), a.albumBrowseId, at)
            assertEquals(e.int("year"), a.year, at)
            assertEquals(e.int("durationSec"), a.durationSec, at)
            assertEquals(e.str("thumb"), a.thumbnailUrl, at)
            assertEquals(e.str("explicit") == "true", a.explicit, at)
            assertEquals(e.str("type"), a.kind.name, at)
            assertEquals(e.str("plays")?.toLong(), a.playCount, at)
        }
    }

    private fun assertRadio(name: String): RadioPage {
        val expected = resource("$name.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parseRadio(resource("$name.json"))
        assertRows(expected["rows"]!!.jsonArray, page.rows, name)
        assertEquals(expected.str("continuation"), page.continuation, "$name: continuation")
        assertEquals(expected.str("relatedBrowseId"), page.relatedBrowseId, "$name: related tab")
        val chips = expected["chips"]!!.jsonArray.map { it.jsonObject }
            .map { RadioChip(it.str("label")!!, it.str("playlistId")!!, it.str("params")) }
        assertEquals(chips, page.chips, "$name: chips")
        return page
    }

    @Test
    fun `song radio - 50 rows, seed first, all official audio, chips, related tab, continuation`() {
        val page = assertRadio("radio_song")
        assertEquals(50, page.rows.size)
        assertEquals("J7p4bzqLvCw", page.rows[0].videoId)
        assertTrue(page.rows.all { it.isOfficialAudio })
        assertTrue(page.rows.all { it.durationSec in 60..900 })
        assertTrue(page.rows.all { it.artists.isNotEmpty() && it.artists[0].channelId != null })
        assertNotNull(page.continuation)
        assertEquals("MPTRt_4U7yfKKFZLv-1", page.relatedBrowseId)
        assertTrue(page.chips.isNotEmpty())
        assertFalse(InnerTubeMusicParsers.isJunkRadio(page.rows, "J7p4bzqLvCw", emptySet()))
    }

    @Test
    fun `continuation page - rows under playlistPanelContinuation and the next token`() {
        val page = assertRadio("radio_song_cont1")
        assertEquals(49, page.rows.size)
        assertNotNull(page.continuation)
        assertNull(page.relatedBrowseId)
    }

    @Test
    fun `pages are merged by video id - a page counts by its new ids, never by its length`() {
        val first = InnerTubeMusicParsers.parseRadio(resource("radio_song.json"))
        val second = InnerTubeMusicParsers.parseRadio(resource("radio_song_cont1.json"))
        val pager = InnerTubeMusicParsers.RadioPager()
        assertEquals(50, pager.accept(first))
        assertNotNull(pager.nextToken)
        val added = pager.accept(second)
        val overlap = second.rows.count { row -> first.rows.any { it.videoId == row.videoId } }
        assertEquals(second.rows.size - overlap, added)
        assertEquals(50 + added, pager.rows.size)
        assertEquals(pager.rows.size, pager.rows.map { it.videoId }.toSet().size)
        assertEquals(listOf(50, added), pager.newPerPage)

        // The same page again brings nothing new, twice in a row: the chain stops though a token exists.
        assertEquals(0, pager.accept(second))
        assertNotNull(pager.nextToken)
        assertEquals(0, pager.accept(second))
        assertNull(pager.nextToken)
    }

    @Test
    fun `the pager stops after three continuations and on a missing token`() {
        fun page(prefix: String, token: String?) = RadioPage(
            rows = List(10) { RadioRow(videoId = "$prefix$it", title = "t") },
            continuation = token,
        )
        val capped = InnerTubeMusicParsers.RadioPager()
        capped.accept(page("a", "t1"))
        assertEquals("t1", capped.nextToken)
        capped.accept(page("b", "t2"))
        capped.accept(page("c", "t3"))
        assertEquals("t3", capped.nextToken)
        capped.accept(page("d", "t4"))
        assertNull(capped.nextToken, "three continuations is the cap")
        assertEquals(40, capped.rows.size)

        val ended = InnerTubeMusicParsers.RadioPager()
        ended.accept(page("a", null))
        assertNull(ended.nextToken)
    }

    @Test
    fun `vietnamese radio - names keep their diacritics and the year comes from the byline`() {
        val page = assertRadio("radio_vpop")
        val row = page.rows[1]
        assertEquals("Chúng Ta Của Hiện Tại", row.title)
        assertEquals("Sơn Tùng M-TP", row.artists[0].name)
        assertEquals("UC3muIvzjhubNpJ4Pn_0kCQw", row.artists[0].channelId)
        assertEquals("MPREb_zxR43A84eOG", row.albumBrowseId)
        assertEquals(2020, row.year)
        assertEquals(302, row.durationSec)
        assertEquals(MusicVideoKind.ATV, row.kind)
    }

    @Test
    fun `artist radio - the song-radio shape, healthy for its own artist`() {
        val page = assertRadio("radio_artist")
        assertEquals(50, page.rows.size)
        val weeknd = "UClYV6hHlupm_S_ObS1W-DYw"
        assertFalse(InnerTubeMusicParsers.isJunkRadio(page.rows, "VtCJ_ABaHQY", setOf(weeknd)))
        // Asked about an artist who is not in it, the same list is junk.
        assertTrue(InnerTubeMusicParsers.isJunkRadio(page.rows, "VtCJ_ABaHQY", setOf("UC_nobody")))
    }

    @Test
    fun `junk detector - a short radio is junk`() {
        val page = assertRadio("radio_short")
        assertEquals(28, page.rows.size)
        assertTrue(InnerTubeMusicParsers.isJunkRadio(page.rows, page.rows[0].videoId, emptySet()))
    }

    @Test
    fun `junk detector - 40 rows with nothing by the seed artist in the first 25 is junk`() {
        fun row(i: Int, channel: String?) = RadioRow(
            videoId = "v$i", title = "t$i",
            artists = listOf(RadioArtistRef("Artist $channel", channel)), kind = MusicVideoKind.ATV,
        )
        val seed = row(0, "UCseed")
        val strangers = List(45) { row(it + 1, "UCother${it % 7}") }
        assertTrue(InnerTubeMusicParsers.isJunkRadio(listOf(seed) + strangers, "v0", emptySet()))

        // The seed artist at row 26 is outside the window; at row 25 it is inside.
        val late = strangers.toMutableList().also { it[25] = row(26, "UCseed") }
        assertTrue(InnerTubeMusicParsers.isJunkRadio(listOf(seed) + late, "v0", emptySet()))
        val inTime = strangers.toMutableList().also { it[24] = row(25, "UCseed") }
        assertFalse(InnerTubeMusicParsers.isJunkRadio(listOf(seed) + inTime, "v0", emptySet()))

        // A row without an artist id is kept but can never satisfy the test.
        val unlinked = strangers.toMutableList().also { it[3] = row(4, null) }
        assertTrue(InnerTubeMusicParsers.isJunkRadio(listOf(seed) + unlinked, "v0", emptySet()))
    }

    @Test
    fun `junk detector - a seed without a channel id falls back to its folded name`() {
        fun row(i: Int, name: String) =
            RadioRow(videoId = "v$i", title = "t$i", artists = listOf(RadioArtistRef(name, null)), kind = MusicVideoKind.ATV)
        val rows = listOf(row(0, "Sơn Tùng M-TP")) + List(44) { row(it + 1, if (it == 5) "SON TUNG M-TP" else "Other $it") }
        assertFalse(InnerTubeMusicParsers.isJunkRadio(rows, "v0", emptySet()))
        val none = listOf(row(0, "Sơn Tùng M-TP")) + List(44) { row(it + 1, "Other $it") }
        assertTrue(InnerTubeMusicParsers.isJunkRadio(none, "v0", emptySet()))
    }

    @Test
    fun `music-video rows are parsed but are not official audio`() {
        val page = assertRadio("radio_music_videos")
        assertTrue(page.rows.isNotEmpty())
        assertTrue(page.rows.none { it.isOfficialAudio })
        assertTrue(page.rows.any { it.kind == MusicVideoKind.OMV })
    }

    @Test
    fun `a response without the renderer is no data, not an error`() {
        val page = assertRadio("radio_invalid_id")
        assertTrue(page.rows.isEmpty())
        assertNull(page.continuation)
        // Even without a queue panel the response names the Related tab: the first fallback.
        assertNotNull(page.relatedBrowseId)
        assertEquals(RadioPage.EMPTY.rows, InnerTubeMusicParsers.parseRadio(json.parseToJsonElement("{}")).rows)
        assertEquals(emptyList<RadioRow>(), InnerTubeMusicParsers.parseRadio(json.parseToJsonElement("[1,2]")).rows)
        assertEquals(RelatedPage.EMPTY, InnerTubeMusicParsers.parseRelated(json.parseToJsonElement("{}")))
        assertEquals(ArtistPage.EMPTY, InnerTubeMusicParsers.parseArtistPage(json.parseToJsonElement("{}")))
    }

    @Test
    fun `wrapper rows are unwrapped, unplayable rows skipped, a missing type is unknown`() {
        val panel = """
            {"contents":{"x":{"playlistPanelRenderer":{"contents":[
              {"playlistPanelVideoWrapperRenderer":{
                "primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"primary0001","title":{"runs":[{"text":"Wrapped"}]},
                  "longBylineText":{"runs":[{"text":"Someone"},{"text":" • "},{"text":"An Album"},{"text":" • "},{"text":"1999"}]},
                  "lengthText":{"runs":[{"text":"1:02:03"}]}}},
                "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"video000001","title":{"runs":[{"text":"The video"}]}}}}]}},
              {"playlistPanelVideoRenderer":{"videoId":"blocked0001","title":{"runs":[{"text":"Gone"}]},"unplayableText":{"runs":[{"text":"x"}]}}},
              {"playlistPanelVideoRenderer":{"title":{"runs":[{"text":"No id"}]}}}
            ]}}}}
        """.trimIndent()
        val page = InnerTubeMusicParsers.parseRadio(json.parseToJsonElement(panel))
        assertEquals(listOf("primary0001"), page.rows.map { it.videoId })
        val row = page.rows[0]
        assertEquals(MusicVideoKind.UNKNOWN, row.kind)
        assertFalse(row.isOfficialAudio)
        // No run links to an artist: the plain run before the first bullet is the artist, without an id.
        assertEquals(listOf(RadioArtistRef("Someone", null)), row.artists)
        assertEquals(1999, row.year)
        assertEquals(3723, row.durationSec)
    }

    @Test
    fun `related tab - also-like songs, other performances, similar artists`() {
        val expected = resource("related.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parseRelated(resource("related.json"))
        assertRows(expected["youMightAlsoLike"]!!.jsonArray, page.youMightAlsoLike, "also like")
        assertRows(expected["otherPerformances"]!!.jsonArray, page.otherPerformances, "other performances")
        val similar = expected["similarArtists"]!!.jsonArray.map { it.jsonObject }.map { RadioArtistRef(it.str("name")!!, it.str("id")) }
        assertEquals(similar, page.similarArtists)
        assertEquals(20, page.youMightAlsoLike.size)
        assertEquals(8, page.otherPerformances.size)
        assertEquals(10, page.similarArtists.size)
        // Related rows carry no length.
        assertTrue(page.youMightAlsoLike.all { it.durationSec == 0 })
        assertEquals(listOf("Post Malone", "Swae Lee"), page.youMightAlsoLike[0].artists.map { it.name })
    }

    @Test
    fun `artist page - radio endpoint, five top songs with play counts, releases, fans also like`() {
        val expected = resource("artist_page.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parseArtistPage(resource("artist_page.json"))
        assertEquals(expected.str("name"), page.name)
        val radio = expected["radio"]!!.jsonObject
        assertEquals(RadioEndpoint(radio.str("videoId"), radio.str("playlistId")!!, radio.str("params")), page.radio)
        assertEquals(RadioEndpoint("VtCJ_ABaHQY", "RDEMHSpo_Uv9STIRtF73zMywLg", "wAEB"), page.radio)
        assertRows(expected["topSongs"]!!.jsonArray, page.topSongs, "top songs")
        assertEquals(5, page.topSongs.size)
        assertEquals(3_400_000_000L, page.topSongs[0].playCount)
        fun releases(key: String) = expected[key]!!.jsonArray.map { it.jsonObject }
            .map { Triple(it.str("browseId")!!, it.str("title")!!, it.int("year")) }
        fun List<ReleaseRef>.triples() = map { Triple(it.browseId, it.title, it.year) }
        assertEquals(releases("albums"), page.albums.triples())
        assertEquals(releases("singles"), page.singles.triples())
        assertEquals(10, page.albums.size)
        assertEquals(10, page.singles.size)
        val fans = expected["fansAlsoLike"]!!.jsonArray.map { it.jsonObject }.map { RadioArtistRef(it.str("name")!!, it.str("id")) }
        assertEquals(fans, page.fansAlsoLike)
        assertEquals(10, page.fansAlsoLike.size)
    }

    @Test
    fun `clocks and play counts`() {
        assertEquals(302, InnerTubeMusicParsers.parseClock("5:02"))
        assertEquals(3723, InnerTubeMusicParsers.parseClock("1:02:03"))
        assertEquals(0, InnerTubeMusicParsers.parseClock(""))
        assertEquals(0, InnerTubeMusicParsers.parseClock("live"))
        assertEquals(47_000_000L, InnerTubeMusicParsers.parsePlayCount("47M plays"))
        assertEquals(3_400_000_000L, InnerTubeMusicParsers.parsePlayCount("3.4B plays"))
        assertEquals(812L, InnerTubeMusicParsers.parsePlayCount("812 views"))
        assertEquals(1_500L, InnerTubeMusicParsers.parsePlayCount("1.5K plays"))
        assertNull(InnerTubeMusicParsers.parsePlayCount("The Weeknd"))
    }
}
