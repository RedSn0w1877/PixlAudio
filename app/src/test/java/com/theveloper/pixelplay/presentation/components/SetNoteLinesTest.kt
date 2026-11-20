package com.theveloper.pixelplay.presentation.components

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

/**
 * The queue's set notes (docs/brain-spec.md, 9.3), read from real songs: "your side" is a song with a recorded play, and "something new" is
 * an engine track with none. A library row with no play is neither, so a First Listen queue never claims a side the listener has not played.
 */
class SetNoteLinesTest {

    private fun library(id: String, artist: String, genre: String?): Song =
        Song.emptySong().copy(id = id, title = "T$id", artist = artist, artistId = id.hashCode().toLong().coerceAtLeast(1L), albumId = 7L, genre = genre, contentUriString = "content://x/$id")

    private fun outside(id: String, artist: String): Song = Song.emptySong().copy(
        id = id, title = "T$id", artist = artist, artistId = -1L, albumId = -1L, contentUriString = EngineTrack.CONTENT_URI_PREFIX + id,
    )

    @Test
    fun `three songs of one genre and then a new one read as one note, on the first of the three`() {
        val queue = listOf(
            library("1", "Hoang Dung", "V-pop, ballad"), library("2", "Mono", "V-pop"), library("3", "Orange", "v-pop"),
            outside("4", "HIEUTHUHAI"), library("5", "Clairo", "bedroom pop"),
        )
        val notes = SetNoteLines.forQueue(queue, 0, setOf("1", "2", "3"))
        assertThat(notes.byIndex).containsExactly(0, "Three from your V-pop side, then something new from HIEUTHUHAI.")
        assertThat(notes.current).isEqualTo("Three from your V-pop side, then something new from HIEUTHUHAI.")
    }

    @Test
    fun `the note is made from what is still to play, so it moves with the current song`() {
        val queue = listOf(
            library("1", "A", "pop"), library("2", "B", "pop"), library("3", "C", "pop"), outside("4", "D"),
        )
        val heard = setOf("1", "2", "3")
        // With the first song played, two are left before the new one: the note says two, on song 2, and is the current song's only from there.
        val notes = SetNoteLines.forQueue(queue, 1, heard)
        assertThat(notes.byIndex).containsExactly(1, "Two from your pop side, then something new from D.")
        assertThat(notes.current).isEqualTo("Two from your pop side, then something new from D.")
        // The first song is not in the notes at all; no note for a queue with nothing to say or no current song.
        assertThat(SetNoteLines.forQueue(queue, -1, heard).byIndex).isEmpty()
        assertThat(SetNoteLines.forQueue(emptyList(), 0, heard).current).isNull()
        assertThat(SetNoteLines.forQueue(queue, 3, heard).current).isNull()
    }

    @Test
    fun `songs the listener never played are not their side, and a played engine track is not new`() {
        val queue = listOf(
            library("1", "A", "trap"), library("2", "B", "trap"), library("3", "C", "trap"), outside("4", "D"),
        )
        // Nothing played (a First Listen queue): no note at all. While the history is still loading there is none either.
        assertThat(SetNoteLines.forQueue(queue, 0, emptySet()).byIndex).isEmpty()
        assertThat(SetNoteLines.forQueue(queue, 0, null).byIndex).isEmpty()
        // The engine track has been played through a radio: it is not "something new".
        val played = SetNoteLines.forQueue(queue, 0, setOf("1", "2", "3", "4"))
        assertThat(played.current).isNull()
    }

    @Test
    fun `a song twice in the queue gets its note on one row only`() {
        val queue = listOf(
            library("1", "A", "pop"), library("1", "A", "pop"), library("2", "B", "pop"), outside("4", "D"),
        )
        val notes = SetNoteLines.forQueue(queue, 0, setOf("1", "2"))
        assertThat(notes.byIndex.keys).containsExactly(0)
    }
}
