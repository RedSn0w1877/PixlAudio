package com.theveloper.pixelplay.data.songtap.ring

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsers
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** What a ring code's lookup becomes: the card's title, artist and art come from the video, and only from fields the card may show. */
class RingCodeSongsTest {
    private fun row(
        title: String = "Never Gonna Give You Up",
        artists: List<RadioArtistRef> = listOf(RadioArtistRef("Rick Astley")),
        thumb: String? = "https://lh3.googleusercontent.com/abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz=w544-h544-l90-rj",
        durationSec: Int = 213,
    ) = RadioRow(videoId = "dQw4w9WgXcQ", title = title, artists = artists, thumbnailUrl = thumb, durationSec = durationSec)

    @Test
    fun `a row becomes a payload with the video id and the text the card shows`() {
        val p = RingCodeSongs.payloadFrom(row(), nowMs = 5_000L)!!
        assertThat(p.ytVideoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(p.spotifyId).isNull()
        assertThat(p.title).isEqualTo("Never Gonna Give You Up")
        assertThat(p.artist).isEqualTo("Rick Astley")
        assertThat(p.durationMs).isEqualTo(213_000)
        assertThat(p.ts).isEqualTo(5_000L)
        assertThat(p.songKey).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `the cover is kept when it is on an allowed host, even when it is longer than a beam would carry`() {
        assertThat(RingCodeSongs.payloadFrom(row(), 0L)!!.artUrl).startsWith("https://lh3.googleusercontent.com/")
        assertThat(RingCodeSongs.payloadFrom(row(), 0L)!!.artUrl!!.length).isGreaterThan(110)
    }

    @Test
    fun `a cover on any other host, or not https, becomes the video's own thumbnail`() {
        for (bad in listOf("https://evil.example.com/a.jpg", "http://i.ytimg.com/vi/x/a.jpg", "https://i.ytimg.com", "javascript:alert(1)", "https://i.ytimg.com/a b.jpg", null)) {
            assertThat(RingCodeSongs.payloadFrom(row(thumb = bad), 0L)!!.artUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        }
    }

    @Test
    fun `text is cleaned the way a beam's is`() {
        val p = RingCodeSongs.payloadFrom(row(title = "Song\u202E\u0007 name\u200B"), 0L)!!
        assertThat(p.title).doesNotContain("\u202E")
        assertThat(p.title).doesNotContain("\u0007")
        assertThat(p.title).doesNotContain("\u200B")
    }

    @Test
    fun `no title is no song, and no artist is an unknown artist`() {
        assertThat(RingCodeSongs.payloadFrom(row(title = "  "), 0L)).isNull()
        assertThat(RingCodeSongs.payloadFrom(row(artists = emptyList()), 0L)!!.artist).isEqualTo("Unknown artist")
    }

    @Test
    fun `a real get_queue answer is parsed into the song the ring code names`() {
        val json = """
            {"queueDatas":[{"content":{"playlistPanelVideoRenderer":{
              "videoId":"dQw4w9WgXcQ",
              "title":{"runs":[{"text":"Never Gonna Give You Up"}]},
              "longBylineText":{"runs":[{"text":"Rick Astley","navigationEndpoint":{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}},{"text":" • "},{"text":"Whenever You Need Somebody"},{"text":" • "},{"text":"1987"}]},
              "lengthText":{"runs":[{"text":"3:33"}]},
              "thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/small=w60-h60","width":60,"height":60},{"url":"https://lh3.googleusercontent.com/big=w544-h544","width":544,"height":544}]}
            }}}]}
        """.trimIndent()
        val rows = InnerTubeMusicParsers.parseGetQueue(Json { ignoreUnknownKeys = true }.parseToJsonElement(json))
        assertThat(rows).hasSize(1)
        val p = RingCodeSongs.payloadFrom(rows.single(), 0L)!!
        assertThat(p.title).isEqualTo("Never Gonna Give You Up")
        assertThat(p.artist).isEqualTo("Rick Astley")
        assertThat(p.durationMs).isEqualTo(213_000)
        assertThat(p.artUrl).isEqualTo("https://lh3.googleusercontent.com/big=w544-h544")
    }
}
