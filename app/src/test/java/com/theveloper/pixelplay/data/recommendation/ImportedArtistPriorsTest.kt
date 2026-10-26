package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Imported listening history reaches ranking through artist priors (see data.history). */
class ImportedArtistPriorsTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String, title: String = "Song $id") =
        Song.emptySong().copy(id = id, title = title, artist = artist, artistId = -1, duration = 180_000)

    private val songs = listOf(
        song("a", "Phoebe Bridgers"),
        song("b", "Some Band"),
        song("c", "Dua Lipa, DaBaby"),
        song("d", "Another Act"),
    )

    @Test
    fun `no priors ranks exactly as before`() {
        val without = MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 7)
        val empty = MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 7, emptyMap())
        assertEquals(without, empty)
    }

    @Test
    fun `an artist played a lot elsewhere ranks up, even unheard here`() {
        val ranked = MusicRecommendationEngine.rank(
            songs, emptySet(), emptyMap(), emptyMap(), now, 7,
            artistPriors = mapOf("phoebe bridgers" to 1.0)
        )
        assertEquals("a", ranked.first().song.id)
        assertEquals("Discover more from an artist you enjoy", ranked.first().reason)
    }

    @Test
    fun `a featured credit carries the prior and a skipped artist ranks down`() {
        val ranked = MusicRecommendationEngine.rank(
            songs, emptySet(), emptyMap(), emptyMap(), now, 7,
            artistPriors = mapOf("dababy" to 0.8, "some band" to -1.0)
        )
        assertEquals("c", ranked.first().song.id)
        assertEquals("b", ranked.last().song.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }
}
