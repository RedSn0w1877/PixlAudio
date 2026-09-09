package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MusicRecommendationEngineTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        Song.emptySong().copy(id = id, title = title, artist = artist, artistId = -1, duration = 180_000)

    @Test fun `ending a paused session does not teach an early skip`() {
        val feedback = MusicRecommendationEngine.record(MusicRecommendationEngine.Signal(), 10_000, 180_000, false, false, now)
        assertEquals(0, feedback.earlySkips)
        assertEquals(1, feedback.sessions)
    }

    @Test fun `completed listening and early track changes provide different rewards`() {
        val completed = MusicRecommendationEngine.record(MusicRecommendationEngine.Signal(), 170_000, 180_000, true, true, now)
        val skipped = MusicRecommendationEngine.record(MusicRecommendationEngine.Signal(), 8_000, 180_000, false, true, now)
        assertEquals(1, completed.completions)
        assertEquals(0, completed.earlySkips)
        assertEquals(1, skipped.earlySkips)
        assertEquals(0, skipped.completions)
    }

    @Test fun `loading failures do not poison the taste model`() {
        val old = MusicRecommendationEngine.Signal(sessions = 3)
        assertEquals(old, MusicRecommendationEngine.record(old, 1_000, 180_000, false, true, now))
    }

    @Test fun `frequent skips reduce ranking even with high play count`() {
        val liked = song("liked", "Same artist")
        val skipped = song("skipped", "Same artist")
        val signals = mapOf(
            liked.id to MusicRecommendationEngine.Signal(sessions = 8, completions = 7),
            skipped.id to MusicRecommendationEngine.Signal(sessions = 20, earlySkips = 19)
        )
        val ranked = MusicRecommendationEngine.rank(listOf(skipped, liked), emptySet(), signals,
            mapOf(skipped.id to MusicRecommendationEngine.History(plays = 40)), now, 1)
        assertEquals(liked.id, ranked.first().song.id)
    }

    @Test fun `discovery has reserved space and unknown artist ids do not collapse artists`() {
        val songs = (1..20).map { song(it.toString()) }
        val history = songs.take(10).associate { it.id to MusicRecommendationEngine.History(plays = 10) }
        val ranked = MusicRecommendationEngine.rank(songs, songs.take(10).mapTo(hashSetOf()) { it.id }, emptyMap(), history, now, 42)
        val selected = MusicRecommendationEngine.select(ranked, 12, 0.5f)
        assertEquals(12, selected.size)
        assertTrue(selected.count { it.unheard } >= 6)
        assertEquals(12, selected.map { it.song.artist }.distinct().size)
    }

    @Test fun `duplicate recording across sources appears once and ordering is reproducible`() {
        val songs = listOf(song("local", "Artist", "Track"), song("spotify_remote", " artist ", "track"), song("other"))
        val first = MusicRecommendationEngine.select(MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 5), 30)
        val second = MusicRecommendationEngine.select(MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 5), 30)
        assertEquals(2, first.size)
        assertEquals(first, second)
        assertTrue(first.all { it.score.isFinite() && it.reason.isNotBlank() })
    }

    @Test fun `artist diversity reduces back to back same artist selections`() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b1", "B"), song("c1", "C"))
        val picks = songs.mapIndexed { i, s -> MusicRecommendationEngine.Pick(s, 1.0 - i * 0.01, "history", false) }
        val selected = MusicRecommendationEngine.select(picks, 4)
        assertNotEquals(selected[0].song.artist, selected[1].song.artist)
        assertEquals(4, selected.size)
        assertTrue(MusicRecommendationEngine.select(picks, 0).isEmpty())
    }

    @Test fun `first run learns artist affinity from existing favorites without new signals`() {
        val favorite = song("favorite", "Preferred")
        val related = song("related", "Preferred")
        val unrelated = song("unrelated", "Other")
        val songs = listOf(favorite, unrelated, related)
        val ranked = MusicRecommendationEngine.rank(songs, setOf(favorite.id),
            songs.associate { it.id to MusicRecommendationEngine.Signal() }, emptyMap(), now, 5)
        assertTrue(ranked.first { it.song.id == related.id }.score > ranked.first { it.song.id == unrelated.id }.score + 0.1)
    }

    @Test fun `late feedback preserves the newest listening timestamp`() {
        val previous = MusicRecommendationEngine.Signal(sessions = 2, lastPlayedMs = now)
        val updated = MusicRecommendationEngine.record(previous, 170_000, 180_000, false, true, now - 60_000)
        assertEquals(now, updated.lastPlayedMs)
        assertEquals(3, updated.sessions)
        assertEquals(1, updated.completions)
    }

    @Test fun `duplicate sources retain favorites and listening evidence from the removed version`() {
        val local = song("local", "Artist", "Track")
        val remote = local.copy(id = "spotify_remote")
        val feedback = MusicRecommendationEngine.Signal(sessions = 6, completions = 5, lastPlayedMs = now - 86_400_000)
        val history = MusicRecommendationEngine.History(plays = 8, lastPlayedMs = now - 86_400_000)
        val duplicatePick = MusicRecommendationEngine.rank(listOf(local, remote), setOf(remote.id),
            mapOf(remote.id to feedback), mapOf(remote.id to history), now, 5).single()
        val expected = MusicRecommendationEngine.rank(listOf(local), setOf(local.id),
            mapOf(local.id to feedback), mapOf(local.id to history), now, 5).single()
        assertEquals(local.id, duplicatePick.song.id)
        assertEquals("One of your favorites", duplicatePick.reason)
        assertFalse(duplicatePick.unheard)
        assertEquals(expected.score, duplicatePick.score, 0.0000001)
    }

    @Test fun `numeric and streaming aliases do not count a shared feedback object twice`() {
        val canonical = song("-42", "Artist", "Track").copy(spotifyId = "remote")
        val alias = canonical.copy(id = "spotify_remote")
        val feedback = MusicRecommendationEngine.Signal(sessions = 8, completions = 4, earlySkips = 3, lastPlayedMs = now)
        val history = MusicRecommendationEngine.History(plays = 8, lastPlayedMs = now)
        val expected = MusicRecommendationEngine.rank(listOf(canonical), emptySet(),
            mapOf(canonical.id to feedback), mapOf(canonical.id to history), now, 7).single()
        val actual = MusicRecommendationEngine.rank(listOf(canonical, alias), emptySet(),
            mapOf(canonical.id to feedback, alias.id to feedback),
            mapOf(canonical.id to history, alias.id to history), now, 7).single()
        assertEquals(expected.score, actual.score, 0.0000001)
    }

    @Test fun `separate recording histories combine even when their counters match`() {
        val local = song("local", "Artist", "Track")
        val remote = local.copy(id = "spotify_remote")
        val first = MusicRecommendationEngine.Signal(sessions = 3, completions = 2, earlySkips = 1, lastPlayedMs = now)
        val second = first.copy()
        val expectedSignal = first.copy(sessions = 6, completions = 4, earlySkips = 2)
        val expected = MusicRecommendationEngine.rank(listOf(local), emptySet(),
            mapOf(local.id to expectedSignal), emptyMap(), now, 9).single()
        val actual = MusicRecommendationEngine.rank(listOf(local, remote), emptySet(),
            mapOf(local.id to first, remote.id to second), emptyMap(), now, 9).single()
        assertEquals(expected.score, actual.score, 0.0000001)
    }
}
