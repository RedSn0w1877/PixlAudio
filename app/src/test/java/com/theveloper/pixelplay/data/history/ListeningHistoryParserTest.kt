package com.theveloper.pixelplay.data.history

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListeningHistoryParserTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/listening_history/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private fun parse(input: InputStream, name: String?): Pair<List<ImportedPlay>, ListeningHistoryParser.Stats> {
        val plays = mutableListOf<ImportedPlay>()
        val stats = ListeningHistoryParser.Stats()
        runBlocking { ListeningHistoryParser.parse(input, name, stats) { plays += it } }
        return plays to stats
    }

    private fun parseFixture(name: String) = parse(ByteArrayInputStream(fixture(name)), name)

    @Test
    fun `extended Spotify history keeps tracks, drops podcasts and reads how each play ended`() {
        val (plays, stats) = parseFixture("Streaming_History_Audio_2023-2024_0.json")

        assertEquals(3, plays.size)
        assertEquals(1, stats.ignored) // the podcast episode
        val blinding = plays[0]
        assertEquals(HistorySource.SPOTIFY_EXPORT, blinding.source)
        assertEquals(HistoryFormat.SPOTIFY_EXTENDED, blinding.format)
        assertEquals("Blinding Lights", blinding.trackName)
        assertEquals("The Weeknd", blinding.artistName)
        assertEquals("After Hours", blinding.albumName)
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b", blinding.spotifyTrackId)
        assertEquals(214_000L, blinding.msPlayed)
        assertEquals(java.time.Instant.parse("2024-03-01T18:22:41Z").toEpochMilli(), blinding.playedAtMs)
        assertEquals(PlayOutcome.COMPLETED, blinding.outcome)
        assertTrue(blinding.voluntary)
        assertTrue(blinding.qualified)

        val skipped = plays[1]
        assertEquals("Save Your Tears", skipped.trackName)
        assertEquals(PlayOutcome.SKIPPED, skipped.outcome)
        assertFalse(skipped.qualified)

        val partial = plays[2]
        assertEquals("Levitating", partial.trackName)
        assertEquals(PlayOutcome.PARTIAL, partial.outcome)
        assertTrue(partial.qualified)
    }

    @Test
    fun `basic Spotify history reads minute timestamps as UTC and skips unknown rows`() {
        val (plays, stats) = parseFixture("StreamingHistory_music_0.json")

        assertEquals(2, plays.size)
        assertEquals(1, stats.ignored)
        val antiHero = plays[0]
        assertEquals(HistoryFormat.SPOTIFY_BASIC, antiHero.format)
        assertEquals("Anti-Hero", antiHero.trackName)
        assertEquals("Taylor Swift", antiHero.artistName)
        assertNull(antiHero.spotifyTrackId)
        assertEquals(java.time.Instant.parse("2023-04-01T18:22:00Z").toEpochMilli(), antiHero.playedAtMs)
        assertEquals(PlayOutcome.PARTIAL, antiHero.outcome)
        // A 4-second play with no reason recorded is an early skip.
        assertEquals(PlayOutcome.SKIPPED, plays[1].outcome)
        assertFalse(plays[1].qualified)
    }

    @Test
    fun `Takeout keeps YouTube Music and recognisable music videos only`() {
        val (plays, stats) = parseFixture("watch-history.json")

        assertEquals(listOf("abcDEF12345", "T6eK-2OQtew", "nfWlot6h_JM"), plays.map { it.videoId })
        assertEquals(3, stats.ignored) // a how-to video, a removed video and a search
        val espresso = plays[0]
        assertEquals(HistorySource.TAKEOUT, espresso.source)
        assertEquals("Espresso", espresso.trackName)
        assertEquals("Sabrina Carpenter", espresso.artistName) // " - Topic" dropped
        assertEquals(java.time.Instant.parse("2024-06-10T20:15:03.512Z").toEpochMilli(), espresso.playedAtMs)
        assertEquals(PlayOutcome.UNKNOWN, espresso.outcome)
        assertTrue(espresso.qualified)

        // A VEVO channel name is not an artist: the upload title carries it.
        val vevo = plays[2]
        assertEquals("Taylor Swift", vevo.artistName)
        assertEquals("Shake It Off (Official Video)", vevo.trackName)
        assertEquals("taylor swift|shake it off", HistoryText.recordingKey(vevo.artistName, vevo.trackName))
        // "Artist - Song" titles on a Topic channel match the library by the song alone.
        assertEquals("kendrick lamar|not like us", HistoryText.recordingKey(plays[1].artistName, plays[1].trackName))
    }

    @Test
    fun `a ZIP is read entry by entry and video history inside it is ignored`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("Spotify Extended Streaming History/Streaming_History_Audio_2023-2024_0.json"))
                out.write(fixture("Streaming_History_Audio_2023-2024_0.json"))
                out.closeEntry()
                out.putNextEntry(ZipEntry("Spotify Extended Streaming History/Streaming_History_Video_2023.json"))
                out.write(fixture("Streaming_History_Audio_2023-2024_0.json"))
                out.closeEntry()
                out.putNextEntry(ZipEntry("Spotify Extended Streaming History/ReadMeFirst.pdf"))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
                out.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.json"))
                out.write(fixture("watch-history.json"))
                out.closeEntry()
            }
        }.toByteArray()

        val files = mutableListOf<String>()
        val plays = mutableListOf<ImportedPlay>()
        val stats = ListeningHistoryParser.Stats()
        runBlocking {
            ListeningHistoryParser.parse(ByteArrayInputStream(zip), "my_spotify_data.zip", stats, onFile = { files += it }) { plays += it }
        }

        assertEquals(listOf("Streaming_History_Audio_2023-2024_0.json", "watch-history.json"), files)
        assertEquals(6, plays.size)
        assertEquals(2, stats.recognizedFiles)
    }

    @Test
    fun `a broken file loses only itself`() {
        val broken = """[{"ts":"2024-03-01T18:22:41Z","ms_played":200000,"master_metadata_track_name":"A","master_metadata_album_artist_name":"B"},{"ts":""".toByteArray()
        val (plays, stats) = parse(ByteArrayInputStream(broken), "x.json")
        assertEquals(1, plays.size)
        assertEquals(1, stats.failedFiles)
    }

    @Test
    fun `an object document or HTML history is not a play list`() {
        val (plays, stats) = parse(ByteArrayInputStream("""{"playlists":[]}""".toByteArray()), "Playlist1.json")
        assertTrue(plays.isEmpty())
        assertEquals(1, stats.failedFiles)

        val (htmlPlays, htmlStats) = parse(ByteArrayInputStream("<html></html>".toByteArray()), "watch-history.html")
        assertTrue(htmlPlays.isEmpty())
        assertTrue(htmlStats.sawHtmlHistory)
    }

    @Test
    fun `re-importing the same file produces the same keys`() {
        val first = parseFixture("Streaming_History_Audio_2023-2024_0.json").first.map { it.dedupeKey }
        val second = parseFixture("Streaming_History_Audio_2023-2024_0.json").first.map { it.dedupeKey }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
    }

    @Test
    fun `the same Spotify play from the export and the Web API share a key`() {
        val exported = ListeningHistoryParser.classify(
            mapOf(
                "ts" to "2024-03-01T18:22:41Z", "ms_played" to "214000",
                "master_metadata_track_name" to "Blinding Lights", "master_metadata_album_artist_name" to "The Weeknd",
                "spotify_track_uri" to "spotify:track:0VjIjW4GlUZAMYd2vXMi3b",
            )
        )!!
        val api = ImportedPlay(
            source = HistorySource.SPOTIFY_API, format = HistoryFormat.SPOTIFY_API,
            playedAtMs = java.time.Instant.parse("2024-03-01T18:22:41.873Z").toEpochMilli(),
            trackName = "Blinding Lights", artistName = "The Weeknd", spotifyTrackId = "0VjIjW4GlUZAMYd2vXMi3b",
        )
        assertEquals(exported.dedupeKey, api.dedupeKey)
        assertNotEquals(exported.dedupeKey, api.copy(playedAtMs = api.playedAtMs + 60_000).dedupeKey)
    }
}
