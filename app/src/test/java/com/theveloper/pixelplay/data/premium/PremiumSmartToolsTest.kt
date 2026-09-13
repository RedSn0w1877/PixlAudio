package com.theveloper.pixelplay.data.premium

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumSmartToolsTest {
    private fun song(id: String, artist: String = "Artist $id", duration: Long = 180_000L) =
        Song.emptySong().copy(id = id, title = "Track $id", artist = artist, duration = duration)

    @Test
    fun `deep discovery reserves unheard songs and removes duplicate recordings`() {
        val first = song("1", "A")
        val duplicate = first.copy(id = "remote-1")
        val heard = song("2", "B")
        val unseen = (3..12).map { song(it.toString(), "Artist $it") }
        val history = mapOf(heard.id to MusicRecommendationEngine.History(plays = 20))
        val result = PremiumSmartPlaylistEngine.build(
            SmartPlaylistPreset.DISCOVER,
            listOf(first, duplicate, heard) + unseen,
            history = history,
            limit = 8,
            seed = 7L
        )
        assertEquals(8, result.songs.size)
        assertEquals(result.songs.size, result.songs.map(MusicRecommendationEngine::recordingKey).distinct().size)
        assertTrue(result.songs.count { it.id != heard.id } >= 4)
    }

    @Test
    fun `recently added is stable and short listens sort by duration`() {
        val old = song("old", duration = 300_000).copy(dateAdded = 10)
        val newest = song("new", duration = 60_000).copy(dateAdded = 30)
        val middle = song("middle", duration = 120_000).copy(dateAdded = 20)
        assertEquals(listOf("new", "middle", "old"), PremiumSmartPlaylistEngine
            .build(SmartPlaylistPreset.RECENTLY_ADDED, listOf(old, newest, middle), limit = 3).songs.map { it.id })
        assertEquals(listOf("new", "middle", "old"), PremiumSmartPlaylistEngine
            .build(SmartPlaylistPreset.SHORT_LISTEN, listOf(old, newest, middle), limit = 3).songs.map { it.id })
    }

    @Test
    fun `insights are local and report completion and discovery rates`() {
        val played = song("played", "A")
        val unseen = song("unseen", "B")
        val insights = PremiumInsightEngine.summarize(
            listOf(played, unseen),
            history = mapOf(played.id to MusicRecommendationEngine.History(plays = 2, listenedMs = 1000)),
            signals = mapOf(played.id to MusicRecommendationEngine.Signal(sessions = 2, completions = 1, listenedMs = 1000))
        )
        assertEquals(2, insights.songCount)
        assertEquals(1, insights.playedSongCount)
        assertEquals(50, insights.completionRatePercent)
        assertEquals(50, insights.discoveryRatePercent)
        assertEquals(1000L, insights.totalListeningMs)
        assertEquals("0m", insights.totalListeningLabel())
    }
}
