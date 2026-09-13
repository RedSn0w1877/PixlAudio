package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HomeRecommendationPlannerTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        Song.emptySong().copy(id = id, artist = artist, title = title, duration = 180_000)
    private fun plan(
        library: List<Song>,
        favorites: Set<String> = emptySet(),
        signals: Map<String, MusicRecommendationEngine.Signal> = emptyMap(),
        history: Map<String, MusicRecommendationEngine.History> = emptyMap(),
        discoveries: List<Song> = emptyList(),
        releases: List<Song> = emptyList()
    ) = HomeRecommendationPlanner.plan(library, favorites, signals, history, discoveries, releases, now, 42)

    @Test fun `first launch provides real local choices without a network or listening history`() {
        val songs = (1..30).map { song("$it") }
        val result = plan(songs)
        assertTrue(result.mixes.isNotEmpty())
        assertTrue(result.shelves.isNotEmpty())
        assertTrue(result.mixes.flatMap { it.songs }.all { it in songs })
        assertTrue(result.shelves.flatMap { it.songs }.all { it in songs })
        assertFalse(result.shelves.any { it.id == "discovery" || it.id == "recent_releases" })
    }

    @Test fun `a recording appears only once across shelves despite source and casing differences`() {
        val local = song("local", "Singer", "The Track").copy(isFavorite = true)
        val online = song("online", " singer ", "the track")
        val result = plan(listOf(local, song("other")), releases = listOf(online), discoveries = listOf(online))
        val keys = result.shelves.flatMap { it.songs }.map(MusicRecommendationEngine::recordingKey)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(1, keys.count { it == MusicRecommendationEngine.recordingKey(local) })
    }

    @Test fun `beyond library excludes an already saved recording with another catalog id`() {
        val local = song("local", "A", "Same")
        val duplicate = song("remote", "A", "Same")
        val outside = song("new", "A", "New")
        val result = plan(listOf(local), discoveries = listOf(duplicate, outside))
        assertEquals(listOf(outside.id), result.shelves.first { it.id == "discovery" }.songs.map { it.id })
    }

    @Test fun `favorites and unfamiliar presets are based on distinct listening behavior`() {
        val favorite = song("favorite")
        val played = song("played")
        val unheard = song("unheard")
        val result = plan(listOf(favorite, played, unheard), favorites = setOf(favorite.id),
            history = mapOf(played.id to MusicRecommendationEngine.History(plays = 5)))
        assertEquals(setOf(favorite.id, played.id), result.mixes.first { it.id == "comfort" }.songs.map { it.id }.toSet())
        assertEquals(listOf(unheard.id), result.mixes.first { it.id == "fresh_ears" }.songs.map { it.id })
    }

    @Test fun `back in rotation excludes recently played and never played songs`() {
        val old = song("old")
        val recent = song("recent")
        val unheard = song("unheard")
        val result = plan(listOf(old, recent, unheard), history = mapOf(
            old.id to MusicRecommendationEngine.History(plays = 2, lastPlayedMs = now - 8 * 86_400_000L),
            recent.id to MusicRecommendationEngine.History(plays = 2, lastPlayedMs = now - 60_000)
        ))
        assertEquals(listOf(old.id), result.shelves.first { it.id == "rediscover" }.songs.map { it.id })
    }

    @Test fun `catalog ranking follows positive listening signals and penalizes skips`() {
        val liked = song("liked", "Preferred")
        val skipped = song("skipped", "Avoided")
        val related = song("related", "Preferred")
        val unrelated = song("unrelated", "Avoided")
        val result = plan(listOf(liked, skipped), favorites = setOf(liked.id), signals = mapOf(
            liked.id to MusicRecommendationEngine.Signal(sessions = 10, completions = 9),
            skipped.id to MusicRecommendationEngine.Signal(sessions = 10, earlySkips = 9)
        ), discoveries = listOf(unrelated, related))
        assertEquals(related.id, result.shelves.first { it.id == "discovery" }.songs.first().id)
    }

    @Test fun `no invented genre mix is shown when genre metadata is missing`() {
        val songs = (1..20).map { song("$it") }
        assertFalse(plan(songs).mixes.any { it.id.startsWith("genre_") })
        val jazz = songs.map { it.copy(genre = "Jazz") }
        assertTrue(plan(jazz, favorites = setOf(jazz.first().id)).mixes.any { it.title == "Jazz mix" })
    }

    @Test fun `same day inputs are stable and shelf queues are bounded`() {
        val songs = (1..200).map { song("$it") }
        val first = plan(songs, songs.take(50).mapTo(hashSetOf()) { it.id })
        val second = plan(songs, songs.take(50).mapTo(hashSetOf()) { it.id })
        assertEquals(first, second)
        assertTrue(first.mixes.all { it.songs.size in 1..24 })
        assertTrue(first.shelves.all { it.songs.size in 1..12 })
    }

    @Test fun `empty and one song libraries do not create empty or duplicate preset cards`() {
        assertTrue(plan(emptyList()).mixes.isEmpty())
        assertTrue(plan(emptyList()).shelves.isEmpty())
        val one = plan(listOf(song("one")))
        assertEquals(1, one.mixes.size)
        assertEquals(1, one.shelves.flatMap { it.songs }.size)
    }

    @Test fun `release dates must be recent complete dates and cannot be in the future`() {
        val today = LocalDate.of(2026, 9, 8)
        assertTrue(HomeRecommendationPlanner.isRecentRelease("2026-09-08", today))
        assertTrue(HomeRecommendationPlanner.isRecentRelease(today.minusDays(180).toString(), today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease(today.minusDays(181).toString(), today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease("2026-09-09", today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease("2026", today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease("2026-08", today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease("2026-02-30", today))
        assertFalse(HomeRecommendationPlanner.isRecentRelease(null, today))
    }
}
