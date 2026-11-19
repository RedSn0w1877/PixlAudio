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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stage 2a parsers (docs/brain-spec.md, 3.3: discography, album audio, playlist pages and their
 * continuations, `music/get_queue`, a songs search) against anonymous captures of a famous artist, cut by
 * `tools/brain/cut_fixtures.py --2a`. The `.expected.json` files come from that script's own plain
 * extraction, so a parser bug cannot hide behind its own output; the iOS tests read the same files.
 */
class InnerTubeMusicParsers2aTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): JsonElement {
        val stream = javaClass.classLoader!!.getResourceAsStream("innertube/$name")
            ?: error("missing test resource innertube/$name")
        return json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun assertRows(expected: JsonArray, actual: List<RadioRow>, what: String) {
        assertEquals(expected.size, actual.size, "$what: row count")
        for (i in expected.indices) {
            val e = expected[i].jsonObject
            val a = actual[i]
            val at = "$what row $i (${e.str("title")})"
            assertEquals(e.str("videoId"), a.videoId, at)
            assertEquals(e.str("title"), a.title, at)
            val artists = (e["artists"] as JsonArray).map { it.jsonObject }.map { RadioArtistRef(it.str("name")!!, it.str("id")) }
            assertEquals(artists, a.artists, at)
            assertEquals(e.str("album"), a.album, at)
            assertEquals(e.str("albumId"), a.albumBrowseId, at)
            assertEquals(e.int("year"), a.year, at)
            assertEquals(e.int("durationSec"), a.durationSec, at)
            assertEquals(e.str("explicit") == "true", a.explicit, at)
            assertEquals(e.str("type"), a.kind.name, at)
            assertEquals(e.str("plays")?.toLong(), a.playCount, at)
        }
    }

    @Test
    fun `discography - every card is a release with its kind, year and audio playlist`() {
        val expected = resource("discography.expected.json").jsonObject["releases"]!!.jsonArray
        val parsed = InnerTubeMusicParsers.parseDiscography(resource("discography.json"))
        assertEquals(expected.size, parsed.size)
        for (i in expected.indices) {
            val e = expected[i].jsonObject
            val a = parsed[i]
            assertEquals(e.str("browseId"), a.browseId)
            assertEquals(e.str("title"), a.title)
            assertEquals(e.int("year"), a.year)
            assertEquals(e.str("kind"), a.kind.name)
            assertEquals(e.str("audioPlaylistId"), a.audioPlaylistId)
            assertEquals(e.str("thumb"), a.thumbnailUrl)
        }
        assertEquals(ReleaseKind.ALBUM, parsed.first().kind)
        assertTrue(parsed.all { it.browseId.startsWith("MPREb_") && it.audioPlaylistId!!.startsWith("OLAK5uy_") })
        assertTrue(parsed.any { it.kind == ReleaseKind.SINGLE } && parsed.any { it.kind == ReleaseKind.EP })
    }

    @Test
    fun `discography - a response with no grid is no releases, not an error`() {
        assertTrue(InnerTubeMusicParsers.parseDiscography(resource("radio_invalid_id.json")).isEmpty())
        assertTrue(InnerTubeMusicParsers.parseDiscography(JsonObject(emptyMap())).isEmpty())
    }

    @Test
    fun `album page - the audio playlist is the canonical url's list, never the album page's own tracks`() {
        val expected = resource("album_audio.expected.json").jsonObject.str("audioPlaylistId")
        assertEquals(expected, InnerTubeMusicParsers.parseAlbumAudioPlaylistId(resource("album_audio.json")))
        assertTrue(expected!!.startsWith("OLAK5uy_"))
        assertNull(InnerTubeMusicParsers.parseAlbumAudioPlaylistId(resource("radio_invalid_id.json")))
    }

    @Test
    fun `album as a playlist - 14 official-audio rows, no continuation`() {
        val expected = resource("playlist_album.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parsePlaylistPage(resource("playlist_album.json"))
        assertRows(expected["rows"]!!.jsonArray, page.rows, "album playlist")
        assertEquals(14, page.rows.size)
        assertTrue(page.rows.all { it.isOfficialAudio })
        assertNull(page.continuation)
    }

    @Test
    fun `a long playlist - rows with play counts and the continuation token`() {
        val expected = resource("playlist_top_songs.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parsePlaylistPage(resource("playlist_top_songs.json"))
        assertRows(expected["rows"]!!.jsonArray, page.rows, "playlist page 1")
        assertEquals(expected.str("continuation"), page.continuation)
        assertTrue(page.continuation!!.isNotBlank())
        assertEquals(3_400_000_000L, page.rows[0].playCount)
    }

    @Test
    fun `a playlist continuation - appended items, the last page has no token`() {
        val expected = resource("playlist_cont.expected.json").jsonObject
        val page = InnerTubeMusicParsers.parsePlaylistPage(resource("playlist_cont.json"))
        assertRows(expected["rows"]!!.jsonArray, page.rows, "playlist continuation")
        assertNull(page.continuation)
    }

    @Test
    fun `a continuation that names the next one, in the new and the old shape`() {
        val appended = json.parseToJsonElement(
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_NEW"}}}}]}}]}"""
        )
        assertEquals("NEXT_NEW", InnerTubeMusicParsers.parsePlaylistPage(appended).continuation)
        val legacy = json.parseToJsonElement(
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[],
                "continuations":[{"nextContinuationData":{"continuation":"NEXT_OLD"}}]}}}"""
        )
        assertEquals("NEXT_OLD", InnerTubeMusicParsers.parsePlaylistPage(legacy).continuation)
    }

    @Test
    fun `a playlist row with no watch endpoint still has its video id from the playlist item data`() {
        val doc = json.parseToJsonElement(
            """{"contents":{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"abcdefghijk"},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A song"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"An artist"}]}}}],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:05"}]}}}]}}]}}}"""
        )
        val row = InnerTubeMusicParsers.parsePlaylistPage(doc).rows.single()
        assertEquals("abcdefghijk", row.videoId)
        assertEquals(185, row.durationSec)
    }

    @Test
    fun `an album page's own track list is not read as a playlist`() {
        // The album page of a release holds music-video ids when anonymous: only the audio playlist counts.
        assertTrue(InnerTubeMusicParsers.parsePlaylistPage(json.parseToJsonElement(
            """{"contents":{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"xxxxxxxxxxx"}}}]}}}"""
        )).rows.isEmpty())
    }

    @Test
    fun `get_queue - rows with artists, album and length, an unplayable id is left out`() {
        val expected = resource("get_queue.expected.json").jsonObject
        val rows = InnerTubeMusicParsers.parseGetQueue(resource("get_queue.json"))
        assertEquals(expected["rows"]!!.jsonArray.size, rows.size)
        for (i in rows.indices) {
            val e = expected["rows"]!!.jsonArray[i].jsonObject
            assertEquals(e.str("videoId"), rows[i].videoId)
            assertEquals(e.str("title"), rows[i].title)
            assertEquals(e.int("durationSec"), rows[i].durationSec)
            assertEquals(e.str("type"), rows[i].kind.name)
            assertEquals((e["artists"] as JsonArray).map { it.jsonObject.str("name") }, rows[i].artists.map { it.name })
        }
        // The invalid-id answer is an error object: no rows.
        assertTrue(InnerTubeMusicParsers.parseGetQueue(json.parseToJsonElement("""{"error":{"code":404}}""")).isEmpty())
    }

    @Test
    fun `songs search - rows keep the artist's channel id and the length from the byline`() {
        val expected = resource("search_songs.expected.json").jsonObject
        val rows = InnerTubeMusicParsers.parseSongSearch(resource("search_songs.json"))
        assertRows(expected["rows"]!!.jsonArray, rows, "songs search")
        assertEquals("UClYV6hHlupm_S_ObS1W-DYw", rows.first().artists.first().channelId)
        assertEquals(202, rows.first().durationSec)
    }

    @Test
    fun `the artist page cards carry their kind, and the audio playlist when the menu was kept`() {
        val page = InnerTubeMusicParsers.parseArtistPage(resource("artist_page.json"))
        assertTrue(page.albums.all { it.kind == ReleaseKind.ALBUM })
        assertTrue(page.singles.all { it.kind == ReleaseKind.SINGLE || it.kind == ReleaseKind.EP })
    }
}
