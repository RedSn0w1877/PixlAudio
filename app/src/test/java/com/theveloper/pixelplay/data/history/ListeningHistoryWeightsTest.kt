package com.theveloper.pixelplay.data.history

import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListeningHistoryWeightsTest {
    private val day = 86_400_000L

    @Test
    fun `source weights favour Spotify over YouTube Music`() {
        assertEquals(1.0, HistorySource.SPOTIFY_API.weight)
        assertEquals(1.0, HistorySource.SPOTIFY_EXPORT.weight)
        assertEquals(0.35, HistorySource.YTMUSIC.weight)
        assertEquals(0.35, HistorySource.TAKEOUT.weight)
        assertEquals(HistorySource.TAKEOUT, HistorySource.fromKey("takeout"))
    }

    @Test
    fun `decay halves every 180 days and never exceeds 1`() {
        val now = 1_750_000_000_000L
        assertEquals(1.0, ListeningHistoryWeights.decay(now, now), 1e-12)
        assertEquals(1.0, ListeningHistoryWeights.decay(now + day, now), 1e-12)
        assertEquals(0.5, ListeningHistoryWeights.decay(now - 180 * day, now), 1e-12)
        assertEquals(0.25, ListeningHistoryWeights.decay(now - 360 * day, now), 1e-12)
    }

    @Test
    fun `outcome reads reason_end, skipped and ms_played`() {
        assertEquals(PlayOutcome.COMPLETED, ListeningHistoryWeights.outcome(20_000, null, null, "trackdone"))
        assertEquals(PlayOutcome.COMPLETED, ListeningHistoryWeights.outcome(170_000, 200_000, true, "fwdbtn"))
        assertEquals(PlayOutcome.SKIPPED, ListeningHistoryWeights.outcome(12_000, null, true, null))
        assertEquals(PlayOutcome.SKIPPED, ListeningHistoryWeights.outcome(12_000, null, null, "fwdbtn"))
        assertEquals(PlayOutcome.SKIPPED, ListeningHistoryWeights.outcome(12_000, null, null, null))
        // Stopped by the app closing or the phone locking: not the listener moving on.
        assertEquals(PlayOutcome.PARTIAL, ListeningHistoryWeights.outcome(12_000, null, null, "endplay"))
        // A late skip is heard, not an early skip.
        assertEquals(PlayOutcome.PARTIAL, ListeningHistoryWeights.outcome(90_000, null, true, "fwdbtn"))
        assertEquals(PlayOutcome.UNKNOWN, ListeningHistoryWeights.outcome(null, 200_000, null, null))
    }

    @Test
    fun `text keys fold case, accents width and Topic channels`() {
        assertEquals("beyoncé", HistoryText.artistKey("  BEYONCÉ  "))
        assertEquals("sabrina carpenter", HistoryText.artistKey("Sabrina Carpenter - Topic"))
        assertEquals(listOf("dua lipa", "dababy"), HistoryText.splitArtists("Dua Lipa, DaBaby"))
        assertEquals(listOf("simon & garfunkel"), HistoryText.splitArtists("Simon & Garfunkel"))
        assertEquals("let it be", HistoryText.cleanTitle("Let It Be - Remastered 2009"))
        assertEquals("bad guy", HistoryText.cleanTitle("Billie Eilish - bad guy [Official Music Video]", "Billie Eilish"))
        assertEquals("stay (with me)", HistoryText.cleanTitle("Stay (With Me)"))
    }

    @Test
    fun `merging imported engagement adds plays and keeps the latest play`() {
        val merged = ImportedTasteMerge.history(
            MusicRecommendationEngine.History(plays = 3, listenedMs = 1_000, lastPlayedMs = 50),
            MusicRecommendationEngine.History(plays = 4, listenedMs = 2_000, lastPlayedMs = 70),
        )
        assertEquals(MusicRecommendationEngine.History(7, 3_000, 70), merged)
        assertEquals(MusicRecommendationEngine.History(), ImportedTasteMerge.history(null, null))
    }

    @Test
    fun `YouTube Music shelf labels become days`() {
        val today = LocalDate.of(2026, 10, 23)
        assertEquals(YouTubeHistoryDates.ShelfDay(today, true), YouTubeHistoryDates.dayFor("Today", today))
        assertEquals(YouTubeHistoryDates.ShelfDay(today.minusDays(1), true), YouTubeHistoryDates.dayFor("Yesterday", today))
        assertFalse(YouTubeHistoryDates.dayFor("This week", today).exact)
        assertEquals(LocalDate.of(2025, 3, 15), YouTubeHistoryDates.dayFor("March 2025", today).day)
        // A bare month that has not come yet this year is last year's.
        assertEquals(LocalDate.of(2025, 12, 15), YouTubeHistoryDates.dayFor("December", today).day)
        assertEquals(LocalDate.of(2026, 9, 15), YouTubeHistoryDates.dayFor("September", today).day)
        assertTrue(YouTubeHistoryDates.dayFor("Something new", today).day.isBefore(today))
    }

    @Test
    fun `insight periods reach back the right number of days`() {
        val now = 1_750_000_000_000L
        assertEquals(now - 28 * day, InsightPeriod.LAST_4_WEEKS.sinceMs(now))
        assertEquals(0L, InsightPeriod.ALL_TIME.sinceMs(now))
    }
}
