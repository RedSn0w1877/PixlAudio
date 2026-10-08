package com.theveloper.pixelplay.data.ai.curator

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

class PlaylistPlanFillerTest {

    private val genres = listOf("Jazz", "Indie Rock", "Hip-Hop", "Classical", "Pop", "Ambient", "Metal", "Folk")

    /** 200 songs: 8 genres x 25 songs, 5 artists per genre, years 1960-2019. */
    private val library: List<Song> = (0 until 200).map { i ->
        val genre = genres[i % genres.size]
        val artist = "${genre.replace(" ", "")} Artist ${(i / genres.size) % 5}"
        Song.emptySong().copy(
            id = "song-$i",
            title = "Track $i",
            artist = artist,
            album = "$artist Album ${i % 3}",
            genre = genre,
            year = 1960 + (i % 60),
        )
    }

    private val inputs = PlaylistPlanFiller.Inputs(library = library)

    private fun Song.artistKey() = artist

    @Test
    fun `genre matching fills with that genre`() {
        val result = PlaylistPlanFiller.fill(PlaylistPlan(genres = listOf("Jazz")), inputs, target = 12)
        assertThat(result).hasSize(12)
        assertThat(result.all { it.genre == "Jazz" }).isTrue()
    }

    @Test
    fun `a named artist may fill more than the per-artist cap`() {
        val result = PlaylistPlanFiller.fill(PlaylistPlan(artists = listOf("Jazz Artist 1")), inputs, target = 5)
        assertThat(result.count { it.artist == "Jazz Artist 1" }).isEqualTo(5)
    }

    @Test
    fun `at most three songs per artist when the request names no artist`() {
        val result = PlaylistPlanFiller.fill(PlaylistPlan(genres = listOf("Pop")), inputs, target = 15)
        assertThat(result).hasSize(15)
        val perArtist = result.groupingBy { it.artistKey() }.eachCount()
        assertThat(perArtist.values.max()).isAtMost(PlaylistPlanFiller.MAX_PER_ARTIST)
        // 5 Pop artists x 3 = 15: the cap is met exactly with Pop only.
        assertThat(result.all { it.genre == "Pop" }).isTrue()
    }

    @Test
    fun `era matching prefers songs from those years`() {
        val result = PlaylistPlanFiller.fill(
            PlaylistPlan(genres = listOf("Jazz"), eraFrom = 1960, eraTo = 1979),
            inputs,
            target = 5,
        )
        assertThat(result.all { it.year in 1960..1979 && it.genre == "Jazz" }).isTrue()
    }

    @Test
    fun `low discovery prefers played songs, high discovery unplayed ones`() {
        val played = library.filter { it.genre == "Folk" }.take(6)
        val plays = played.associate { it.id to 30 }
        val withPlays = inputs.copy(playCounts = plays)

        val familiar = PlaylistPlanFiller.fill(PlaylistPlan(genres = listOf("Folk"), discovery = 1), withPlays, target = 6)
        assertThat(familiar.count { it.id in plays }).isAtLeast(5)

        val fresh = PlaylistPlanFiller.fill(PlaylistPlan(genres = listOf("Folk"), discovery = 5), withPlays, target = 6)
        assertThat(fresh.count { it.id in plays }).isEqualTo(0)
    }

    @Test
    fun `favourites get a boost`() {
        val favourite = library.first { it.genre == "Ambient" && it.id != library.first { s -> s.genre == "Ambient" }.id }
        val result = PlaylistPlanFiller.fill(
            PlaylistPlan(genres = listOf("Ambient")),
            inputs.copy(favoriteIds = setOf(favourite.id)),
            target = 3,
        )
        assertThat(result.first().id).isEqualTo(favourite.id)
    }

    @Test
    fun `target sizes are honoured, with no duplicates and only library songs`() {
        for (target in listOf(5, 24, 150)) {
            val result = PlaylistPlanFiller.fill(PlaylistPlan(genres = listOf("Metal"), moods = listOf("workout")), inputs, target)
            assertThat(result).hasSize(target)
            assertThat(result.map { it.id }.toSet()).hasSize(target)
            assertThat(library.map { it.id }).containsAtLeastElementsIn(result.map { it.id })
        }
    }

    @Test
    fun `an empty plan tops up from the seed pool first`() {
        val seed = library.filter { it.genre == "Classical" }.take(8)
        val result = PlaylistPlanFiller.fill(PlaylistPlan(), inputs.copy(seedPool = seed), target = 8)
        // The cap still applies: 5 classical artists, so the seed pool provides most of the songs.
        assertThat(result.count { it in seed }).isAtLeast(5)
        assertThat(result).hasSize(8)
    }

    @Test
    fun `the requested range becomes one target size`() {
        assertThat(PlaylistPlanFiller.targetSize(5, 15)).isEqualTo(15)
        assertThat(PlaylistPlanFiller.targetSize(10, 400)).isEqualTo(PlaylistPlanFiller.MAX_SONGS)
        assertThat(PlaylistPlanFiller.targetSize(20, 10)).isEqualTo(20)
        assertThat(PlaylistPlanFiller.targetSize(0, 0)).isEqualTo(1)
    }

    @Test
    fun `genre families let moods and energy pick plausible songs`() {
        assertThat(GenreFamilies.familyOf("indie rock")).isEqualTo("indie")
        assertThat(GenreFamilies.familyOf("hard rock")).isEqualTo("rock")
        assertThat(GenreFamilies.familyOf("dream pop")).isEqualTo("indie")
        assertThat(GenreFamilies.familyOf("hip hop")).isEqualTo("hiphop")
        assertThat(GenreFamilies.familyOf("")).isNull()
        val calm = PlaylistPlanFiller.fill(PlaylistPlan(moods = listOf("sleep"), energy = 1), inputs, target = 10)
        assertThat(calm.all { it.genre in setOf("Ambient", "Classical", "Folk") }).isTrue()
    }
}
