package com.theveloper.pixelplay.presentation.components

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

/**
 * The host's notes of Endless Listening in the queue: they come from real facts only, never share a track with a set note, and come at most
 * once in five tracks. Without host facts (any other queue, or the host's notes switched off) the queue has the set notes alone.
 */
class SetNoteLinesHostTest {

    private fun library(id: String, artist: String, genre: String? = null, year: Int = 0): Song =
        Song.emptySong().copy(
            id = id, title = "T$id", artist = artist, artistId = id.hashCode().toLong().coerceAtLeast(1L), albumId = 7L,
            genre = genre, year = year, contentUriString = "content://x/$id",
        )

    private fun outside(id: String, artist: String): Song = Song.emptySong().copy(
        id = id, title = "T$id", artist = artist, artistId = -1L, albumId = -1L, contentUriString = EngineTrack.CONTENT_URI_PREFIX + id,
    )

    private fun facts(
        islands: Map<String, String> = emptyMap(),
        why: Map<String, String> = emptyMap(),
        genreFact: (String) -> String = { "" },
    ) = SetNoteLines.HostFacts(islands, why, genreFact)

    private val eight = (1..8).map { library("$it", "Artist $it") }

    @Test
    fun `without host facts the queue has no host note`() {
        // Any other queue, or the host's notes switched off: the caller passes null, so nothing is said by the host.
        assertThat(SetNoteLines.forQueue(eight, 0, emptySet(), null).byIndex).isEmpty()
        assertThat(SetNoteLines.forQueue(eight, 0, emptySet()).byIndex).isEmpty()
    }

    @Test
    fun `an island name makes a note, and the next one is at least five tracks later`() {
        val islands = eight.associate { it.id to "Soft pop" }
        val heard = eight.map { it.id }.toSet()
        val notes = SetNoteLines.forQueue(eight, 0, heard, facts(islands = islands))
        assertThat(notes.byIndex.keys).containsExactly(0, 5)
        assertThat(notes.byIndex[0]).isEqualTo("From your Soft pop side.")
        assertThat(notes.current).isEqualTo("From your Soft pop side.")
    }

    @Test
    fun `a new release by an artist you play comes first, and a track nothing is known about has no note`() {
        val queue = listOf(library("1", "Clairo"), library("2", "Mono"))
        val notes = SetNoteLines.forQueue(queue, 0, emptySet(), facts(why = mapOf("1" to "new_release")))
        assertThat(notes.byIndex).containsExactly(0, "New from Clairo, an artist you play.")
        assertThat(SetNoteLines.forQueue(queue, 0, emptySet(), facts()).byIndex).isEmpty()
    }

    @Test
    fun `a year or a short genre fact is used only when it is really there`() {
        val withYear = listOf(library("1", "A", year = 1999), library("2", "B"))
        assertThat(SetNoteLines.forQueue(withYear, 0, emptySet(), facts()).byIndex).containsExactly(0, "Released in 1999.")
        val badYear = listOf(library("1", "A", year = 12))
        assertThat(SetNoteLines.forQueue(badYear, 0, emptySet(), facts()).byIndex).isEmpty()
        val withGenre = listOf(library("1", "A", genre = "Shoegaze"))
        val fact = facts(genreFact = { if (it == "Shoegaze") "Guitar noise as a wall of sound. More text after." else "" })
        assertThat(SetNoteLines.forQueue(withGenre, 0, emptySet(), fact).byIndex).containsExactly(0, "Shoegaze: Guitar noise as a wall of sound.")
        // A description too long to say as it stands is not shortened: the note is dropped.
        val long = facts(genreFact = { "A very long description that goes on and on past what a short note can hold at all, honestly." })
        assertThat(SetNoteLines.forQueue(withGenre, 0, emptySet(), long).byIndex).isEmpty()
    }

    @Test
    fun `a track never has two notes, a set note keeps the host away from the tracks it describes`() {
        val queue = listOf(
            library("1", "A", "pop"), library("2", "B", "pop"), library("3", "C", "pop"),
            outside("4", "D"), library("5", "E"), library("6", "F"), library("7", "G"), library("8", "H"),
        )
        val heard = queue.map { it.id }.toSet() - "4"
        val islands = queue.associate { it.id to "Soft pop" }
        val result = SetNoteLines.forQueue(queue, 0, heard, facts(islands = islands))
        // The set note is on the first of its three tracks; the host speaks again only after the five tracks it covers.
        assertThat(result.byIndex[0]).isEqualTo("Three from your pop side, then something new from D.")
        assertThat(result.byIndex.keys.filter { it in 1..4 }).isEmpty()
        assertThat(result.byIndex.keys).containsExactly(0, 5)
        assertThat(result.byIndex[5]).isEqualTo("From your Soft pop side.")
    }

    @Test
    fun `an island note needs a recorded play and a new release must not have been heard`() {
        val queue = listOf(library("1", "Clairo"), library("2", "Mono"))
        val islands = mapOf("1" to "Soft pop", "2" to "Soft pop")
        assertThat(SetNoteLines.forQueue(queue, 0, emptySet(), facts(islands = islands)).byIndex).isEmpty()
        assertThat(SetNoteLines.forQueue(queue, 0, setOf("1"), facts(islands = islands)).byIndex).containsExactly(0, "From your Soft pop side.")
        // The stale reason of a song that was already heard must not claim "new".
        val why = mapOf("1" to "new_release")
        assertThat(SetNoteLines.forQueue(queue, 0, setOf("1"), facts(why = why)).byIndex).isEmpty()
    }

    @Test
    fun `moving on one track at a time keeps one note in five tracks`() {
        val islands = eight.associate { it.id to "Soft pop" }
        val heard = eight.map { it.id }.toSet()
        val f = facts(islands = islands)
        // Track 0 shows its note; going on to track 1 to 4 shows none (the card used to repeat at every track); track 5 may.
        val shown = (0..6).filter { SetNoteLines.forQueue(eight, it, heard, f).current != null }
        assertThat(shown).containsExactly(0, 5).inOrder()
    }

    @Test
    fun `a step sideways says only the genre the engine flagged`() {
        val queue = listOf(library("1", "A", "k-pop"), library("2", "B", "bossa nova"))
        val why = mapOf("2" to "adjacent_genre")
        assertThat(SetNoteLines.forQueue(queue, 1, setOf("1"), facts(why = why)).byIndex).containsExactly(1, "A step sideways: bossa nova.")
    }

    @Test
    fun `with Endless Listening's host notes off the queue has no note at all`() {
        val queue = listOf(library("1", "A"), library("2", "B"), library("3", "C"), library("4", "D"))
        val heard = setOf("1", "2", "3")
        assertThat(SetNoteLines.forQueue(queue, 0, heard, null, notesOff = true).byIndex).isEmpty()
    }
}
