package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.SpotifySongEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Titles and credits as Spotify and YouTube Music really write them. Before the fix a Spotify
 * title carrying "(with X)", "(feat. X)" or a remaster suffix scored a title similarity below
 * the 0.55 floor against the plain YouTube title and the song was rejected outright.
 */
class TrackMatcherAnnotationsTest {

    private val matcher = TrackMatcher(mockk())

    private fun song(title: String, artist: String, durationMs: Long, album: String = "Album") =
        SpotifySongEntity("row", "track", "playlist", title, artist, album, null, durationMs, null, null, 0L)

    private fun candidate(title: String, artist: String, seconds: Int, album: String? = null) =
        YouTubeSearchResult("abcdefghijk", title, artist, album, seconds)

    private fun accepted(song: SpotifySongEntity, candidate: YouTubeSearchResult) =
        matcher.score(song, candidate) >= TrackMatcher.MIN_ACCEPT_SCORE

    // --- the titles from the owner's report ---

    @Test fun `Starboy with both credited artists is an early accept`() {
        val s = song("Starboy", "The Weeknd, Daft Punk", 230_000, "Starboy")
        val c = candidate("Starboy", "The Weeknd & Daft Punk", 230, "Starboy")
        assertTrue(matcher.score(s, c) >= 0.88f)
    }

    @Test fun `with-credit in the Spotify title does not reject the plain YouTube title`() {
        val s = song("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141_000)
        assertTrue(accepted(s, candidate("Stay", "The Kid LAROI & Justin Bieber", 141)))
    }

    @Test fun `the other order of a collaboration still matches`() {
        val s = song("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141_000)
        assertTrue(accepted(s, candidate("Stay", "Justin Bieber & The Kid LAROI", 141)))
    }

    @Test fun `remastered suffix on one side does not reject`() {
        val s = song("Bohemian Rhapsody - Remastered 2011", "Queen", 354_000)
        assertTrue(accepted(s, candidate("Bohemian Rhapsody", "Queen", 354)))
        assertTrue(accepted(s, candidate("Bohemian Rhapsody (Remastered 2011)", "Queen", 354)))
    }

    @Test fun `year-first remaster suffix does not reject`() {
        val s = song("Hotel California - 2013 Remaster", "Eagles", 391_000)
        assertTrue(accepted(s, candidate("Hotel California", "Eagles", 391)))
        assertTrue(accepted(s, candidate("Hotel California (2013 Remaster)", "Eagles", 391)))
    }

    @Test fun `feat credit on the YouTube side does not reject the plain Spotify title`() {
        val s = song("Empire State Of Mind", "JAY-Z, Alicia Keys", 276_000)
        assertTrue(accepted(s, candidate("Empire State of Mind (feat. Alicia Keys)", "JAY-Z", 276)))
    }

    @Test fun `any credited artist can vouch for the candidate`() {
        val s = song("One Dance", "Drake, WizKid, Kyla", 173_000)
        assertTrue(accepted(s, candidate("One Dance", "Drake & WizKid & Kyla", 173)))
        assertTrue(accepted(s, candidate("One Dance", "Kyla", 173)))
    }

    // --- the safeguards still hold ---

    @Test fun `a stripped title does not let another artist through`() {
        val s = song("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141_000)
        assertEquals(0f, matcher.score(s, candidate("Stay", "Rihanna", 141)))
    }

    @Test fun `a live or remix upload still loses to the studio recording`() {
        val s = song("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141_000)
        val studio = candidate("Stay", "The Kid LAROI & Justin Bieber", 141)
        val live = candidate("Stay (Live)", "The Kid LAROI & Justin Bieber", 141)
        assertTrue(matcher.score(s, studio) > matcher.score(s, live))
    }

    @Test fun `a different song by the same artist is still rejected`() {
        val s = song("Hotel California - 2013 Remaster", "Eagles", 391_000)
        assertEquals(0f, matcher.score(s, candidate("Take It Easy", "Eagles", 391)))
    }

    // --- the stripping itself ---

    @Test fun `annotations are stripped`() {
        assertEquals("Starboy", TrackMatcher.stripTitleAnnotations("Starboy (feat. Daft Punk)"))
        assertEquals("Stay", TrackMatcher.stripTitleAnnotations("Stay (with Justin Bieber)"))
        assertEquals("Stay", TrackMatcher.stripTitleAnnotations("Stay [ft. Justin Bieber]"))
        assertEquals("Bohemian Rhapsody", TrackMatcher.stripTitleAnnotations("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Hotel California", TrackMatcher.stripTitleAnnotations("Hotel California - 2013 Remaster"))
        assertEquals("Hotel California", TrackMatcher.stripTitleAnnotations("Hotel California (2013 Remaster)"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song - 2011 Digital Remaster"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song [Remastered]"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song (Single Version)"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song (feat. A) - Remastered 2011"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song ft. Drake"))
        assertEquals("Song", TrackMatcher.stripTitleAnnotations("Song - feat. Drake"))
    }

    @Test fun `real titles that merely look like annotations are kept`() {
        listOf(
            "Without You", "Love (Without You)", "Live Forever", "Featuring Me", "Mono",
            "Remastered", "Can't Stop - Live", "Song (Remix)", "Song (Live)", "Song - Acoustic"
        ).forEach { assertEquals(it, TrackMatcher.stripTitleAnnotations(it)) }
    }

    @Test fun `a title made only of an annotation falls back to itself`() {
        assertEquals("(feat. X)", TrackMatcher.stripTitleAnnotations("(feat. X)"))
        assertTrue(TrackMatcher.normalizeTitle("(feat. X)").isNotBlank())
    }

    @Test fun `normalize itself is unchanged for other callers`() {
        // SpotifyConnectResolver builds its keys on normalize(); annotations must survive it.
        assertEquals("stay with justin bieber", TrackMatcher.normalize("Stay (with Justin Bieber)"))
    }

    // --- searching and excluding ---

    @Test fun `search queries use the title without annotations`() = runTest {
        val queries = mutableListOf<String>()
        val client = mockk<InnerTubeClient>()
        coEvery { client.searchSongs(any(), any()) } coAnswers { queries += firstArg<String>(); emptyList() }
        coEvery { client.searchVideos(any(), any()) } coAnswers { queries += firstArg<String>(); emptyList() }

        val s = song("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141_000, "F*CK LOVE 3")
        assertNull(TrackMatcher(client).findMatch(s))

        assertEquals("Stay The Kid LAROI", queries.first())
        assertTrue(queries.none { it.contains("with Justin", ignoreCase = true) })
    }

    @Test fun `an excluded video is skipped and the next candidate wins`() = runTest {
        val s = song("Northern Lights", "Nova", 180_000)
        val dead = YouTubeSearchResult("deaddeaddea", "Northern Lights", "Nova", "Album", 180)
        val next = YouTubeSearchResult("nextnextnex", "Northern Lights", "Nova", null, 186)
        val client = mockk<InnerTubeClient>()
        coEvery { client.searchSongs(any(), any()) } returns listOf(dead, next)
        coEvery { client.searchVideos(any(), any()) } returns emptyList()

        assertEquals("deaddeaddea", TrackMatcher(client).findMatch(s)?.videoId)
        assertEquals("nextnextnex", TrackMatcher(client).findMatch(s, setOf("deaddeaddea"))?.videoId)
        assertEquals("nextnextnex", TrackMatcher(client).findMatchFanOut(s, setOf("deaddeaddea"))?.videoId)
        assertNull(TrackMatcher(client).findMatch(s, setOf("deaddeaddea", "nextnextnex")))
    }
}
