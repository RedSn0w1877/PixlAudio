package com.theveloper.pixelplay.data.tais.dj

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.local.TokenBudget
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

class TaizoMemoryTest {

    @Test
    fun `memory keeps the latest turns within its budget, oldest dropped first`() {
        val memory = TaizoMemory(budgetTokens = 120)
        repeat(20) { i -> memory.add("question $i about some music", "answer $i with a few words") }

        val rendered = memory.render()
        assertThat(TokenBudget.estimate(rendered)).isAtMost(120)
        assertThat(rendered).contains("question 19")
        assertThat(rendered).doesNotContain("question 0 ")
        assertThat(memory.size).isLessThan(20)
    }

    @Test
    fun `each turn is trimmed`() {
        val memory = TaizoMemory()
        memory.add("q".repeat(1_000), "a".repeat(1_000))
        assertThat(memory.render().length).isAtMost(2 * TaizoMemory.MAX_TURN_CHARS + 20)
    }

    @Test
    fun `an empty memory renders nothing`() {
        assertThat(TaizoMemory().render()).isEmpty()
    }
}

class LibraryLookupCoreTest {

    private fun song(id: Int, title: String, artist: String, album: String, genre: String?) =
        Song.emptySong().copy(id = "s$id", title = title, artist = artist, album = album, genre = genre)

    private val songs = buildList {
        var id = 0
        listOf("OK Computer", "Kid A", "In Rainbows").forEach { album ->
            repeat(4) { add(song(id++, "RH $album $it", "Radiohead", album, "Alternative")) }
        }
        repeat(6) { add(song(id++, "Jazz $it", "Miles Davis", "Kind of Blue", "Jazz")) }
        repeat(3) { add(song(id++, "Folk $it", "Bon Iver", "For Emma", "Folk")) }
    }

    private val stats = LibraryLookupCore.Stats(
        artistPlays = mapOf("Radiohead" to 210, "Miles Davis" to 40, "Bon Iver" to 12),
        genrePlays = mapOf("Alternative" to 210, "Jazz" to 40),
        totalPlays = 262,
    )

    @Test
    fun `a mentioned artist gets songs, albums and plays`() {
        val facts = LibraryLookupCore.facts("What do you think of Radiohead?", songs, stats)
        assertThat(facts).contains("Radiohead: 12 songs, 3 albums (OK Computer, Kid A, In Rainbows), 210 plays")
        assertThat(facts).contains("Library: 21 songs")
    }

    @Test
    fun `a mentioned genre gets its size and artists`() {
        val facts = LibraryLookupCore.facts("any good jazz in here", songs, stats)
        assertThat(facts).contains("Jazz: 6 songs; mostly Miles Davis")
    }

    @Test
    fun `what do I listen to most returns the top artists and genres`() {
        val facts = LibraryLookupCore.facts("what do I listen to most", songs, stats)
        assertThat(facts).contains("Most played artists: Radiohead (210 plays), Miles Davis (40 plays), Bon Iver (12 plays)")
        assertThat(facts).contains("Most played genres: Alternative (210 plays), Jazz (40 plays)")
    }

    @Test
    fun `facts stay within their budget`() {
        val many = (0 until 3_000).map { song(it, "Song $it", "Artist $it", "Album $it", "Genre ${it % 50}") }
        val facts = LibraryLookupCore.facts("tell me about my music", many, stats, budgetTokens = 100)
        assertThat(TokenBudget.estimate(facts)).isAtMost(100)
    }
}
