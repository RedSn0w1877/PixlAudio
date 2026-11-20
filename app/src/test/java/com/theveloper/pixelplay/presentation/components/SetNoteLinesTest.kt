package com.theveloper.pixelplay.presentation.components

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

/** The queue's set notes (docs/brain-spec.md, 9.3), read from real songs: a library row is a song the listener has, an engine-only row is new. */
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
        val notes = SetNoteLines.forQueue(queue, 0)
        assertThat(notes.bySongId).containsExactly("1", "Three from your V-pop side, then something new from HIEUTHUHAI.")
        assertThat(notes.current).isEqualTo("Three from your V-pop side, then something new from HIEUTHUHAI.")
    }

    @Test
    fun `the note is made from what is still to play, so it moves with the current song`() {
        val queue = listOf(
            library("1", "A", "pop"), library("2", "B", "pop"), library("3", "C", "pop"), outside("4", "D"),
        )
        // With the first song played, two are left before the new one: the note says two, on song 2, and is the current song's only from there.
        val notes = SetNoteLines.forQueue(queue, 1)
        assertThat(notes.bySongId).containsExactly("2", "Two from your pop side, then something new from D.")
        assertThat(notes.current).isEqualTo("Two from your pop side, then something new from D.")
        // The first song is not in the notes at all; no note for a queue with nothing to say or no current song.
        assertThat(SetNoteLines.forQueue(queue, -1).bySongId).isEmpty()
        assertThat(SetNoteLines.forQueue(emptyList(), 0).current).isNull()
        assertThat(SetNoteLines.forQueue(queue, 3).current).isNull()
    }
}
