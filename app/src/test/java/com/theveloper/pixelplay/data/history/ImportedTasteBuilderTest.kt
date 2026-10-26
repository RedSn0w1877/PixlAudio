package com.theveloper.pixelplay.data.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportedTasteBuilderTest {
    private val now = 1_750_000_000_000L
    private val day = 86_400_000L

    private val library = listOf(
        LibraryTrack(songId = "-1", spotifyId = "0VjIjW4GlUZAMYd2vXMi3b", title = "Blinding Lights", artist = "The Weeknd"),
        LibraryTrack(songId = "-2", spotifyId = "463CkQjx2Zk1yXoBuierM9", title = "Levitating (feat. DaBaby)", artist = "Dua Lipa, DaBaby"),
        LibraryTrack(songId = "-3", spotifyId = "6UelLqGlWMcVH1E5c4H7lY", title = "Watermelon Sugar", artist = "Harry Styles"),
    )
    private val index = LibraryIndex.build(library, videoToSpotifyId = mapOf("matchedVid1" to "6UelLqGlWMcVH1E5c4H7lY"))

    private fun row(
        source: HistorySource,
        title: String,
        artist: String,
        ageDays: Long = 0,
        spotifyId: String? = null,
        videoId: String? = null,
        msPlayed: Long? = 200_000,
        skipped: Boolean? = null,
        reasonEnd: String? = null,
    ): HistoryRow {
        val play = ImportedPlay(
            source = source,
            format = if (source.isSpotify) HistoryFormat.SPOTIFY_EXTENDED else HistoryFormat.TAKEOUT,
            playedAtMs = now - ageDays * day,
            trackName = title,
            artistName = artist,
            spotifyTrackId = spotifyId,
            videoId = videoId,
            msPlayed = msPlayed,
            skipped = skipped,
            reasonEnd = reasonEnd,
        )
        return ImportedPlayEntity.from(play).toRow()
    }

    @Test
    fun `plays map by Spotify id, matched video id and artist plus cleaned title`() {
        assertEquals("-1", index.songIdFor("0VjIjW4GlUZAMYd2vXMi3b", null, "Whoever", "Whatever"))
        assertEquals("-3", index.songIdFor(null, "matchedVid1", "Harry Styles", "Watermelon Sugar (Official Video)"))
        // Featured credit and "(feat. …)" do not stop a title match; any credited artist works.
        assertEquals("-2", index.songIdFor(null, null, "Dua Lipa", "Levitating"))
        assertEquals("-2", index.songIdFor(null, "unknownVid1", "DaBaby", "Levitating - Remastered 2021"))
        assertNull(index.songIdFor(null, null, "Someone Else", "Levitating"))
    }

    @Test
    fun `a song saved from YouTube Music maps by its synthetic id`() {
        val synthetic = LibraryIndex.youTubeMusicSyntheticId("ytVideo0001")
        val withYouTubeSong = LibraryIndex.build(library + LibraryTrack("-9", synthetic, "Some Upload", "Some Channel"))
        assertEquals("-9", withYouTubeSong.songIdFor(null, "ytVideo0001", "x", "y"))
    }

    @Test
    fun `Spotify plays weigh about three times a YouTube Music play`() {
        val builder = ImportedTasteBuilder(index, now)
        repeat(20) { builder.add(row(HistorySource.SPOTIFY_EXPORT, "Blinding Lights", "The Weeknd", spotifyId = "0VjIjW4GlUZAMYd2vXMi3b")) }
        repeat(20) { builder.add(row(HistorySource.TAKEOUT, "Watermelon Sugar", "Harry Styles", videoId = "matchedVid1", msPlayed = null)) }
        val taste = builder.build()

        assertEquals(20, taste.history.getValue("-1").plays)
        assertEquals(7, taste.history.getValue("-3").plays) // 20 × 0.35
        assertEquals(20, taste.signals.getValue("-1").sessions)
        assertEquals(7, taste.signals.getValue("-3").sessions)
        assertEquals(1.0, taste.artistPriors.getValue("the weeknd"), 1e-9)
        assertEquals(ListeningHistoryWeights.YOUTUBE_MUSIC, taste.artistPriors.getValue("harry styles"), 1e-6)
    }

    @Test
    fun `old plays count less than recent ones (half-life 180 days)`() {
        val builder = ImportedTasteBuilder(index, now)
        repeat(8) { builder.add(row(HistorySource.SPOTIFY_EXPORT, "Blinding Lights", "The Weeknd", ageDays = 180, spotifyId = "0VjIjW4GlUZAMYd2vXMi3b")) }
        repeat(8) { builder.add(row(HistorySource.SPOTIFY_EXPORT, "Watermelon Sugar", "Harry Styles", ageDays = 0, spotifyId = "6UelLqGlWMcVH1E5c4H7lY")) }
        val taste = builder.build()

        assertEquals(4, taste.history.getValue("-1").plays)
        assertEquals(8, taste.history.getValue("-3").plays)
        assertEquals(now - 180 * day, taste.history.getValue("-1").lastPlayedMs)
    }

    @Test
    fun `early skips feed the skip signal and pull the artist down`() {
        val builder = ImportedTasteBuilder(index, now)
        repeat(6) { builder.add(row(HistorySource.SPOTIFY_EXPORT, "Levitating", "Dua Lipa", spotifyId = "463CkQjx2Zk1yXoBuierM9", msPlayed = 8_000, skipped = true, reasonEnd = "fwdbtn")) }
        builder.add(row(HistorySource.SPOTIFY_EXPORT, "Blinding Lights", "The Weeknd", spotifyId = "0VjIjW4GlUZAMYd2vXMi3b", reasonEnd = "trackdone"))
        val taste = builder.build()

        val signal = taste.signals.getValue("-2")
        assertEquals(6, signal.sessions)
        assertEquals(6, signal.earlySkips)
        assertEquals(0, signal.completions)
        // Skips are not plays.
        assertEquals(0, taste.history.getValue("-2").plays)
        assertTrue(taste.artistPriors.getValue("dua lipa") < 0.0)
    }

    @Test
    fun `plays of songs not in the library still shape artist affinity`() {
        val builder = ImportedTasteBuilder(index, now)
        repeat(5) { builder.add(row(HistorySource.SPOTIFY_EXPORT, "Not In Library", "Phoebe Bridgers", spotifyId = "1111111111111111111111")) }
        val taste = builder.build()

        assertTrue(taste.signals.isEmpty())
        assertEquals(5, taste.unmappedPlays)
        assertEquals(1.0, taste.artistPriors.getValue("phoebe bridgers"), 1e-9)
    }

    @Test
    fun `Spotify top lists and followed artists seed affinity, top tracks add plays`() {
        val builder = ImportedTasteBuilder(index, now)
        builder.addSeed(TasteSeed(SeedKind.TOP_ARTIST, "short_term", position = 0, listSize = 50, artistName = "Harry Styles"))
        builder.addSeed(TasteSeed(SeedKind.FOLLOWED_ARTIST, "", position = 0, listSize = 10, artistName = "Phoebe Bridgers"))
        builder.addSeed(TasteSeed(SeedKind.TOP_TRACK, "short_term", 0, 50, "The Weeknd", "Blinding Lights", "0VjIjW4GlUZAMYd2vXMi3b"))
        val taste = builder.build()

        assertEquals(1.0, taste.artistPriors.getValue("harry styles"), 1e-9)
        assertNotNull(taste.artistPriors["phoebe bridgers"])
        assertEquals(4, taste.history.getValue("-1").plays)
    }
}
