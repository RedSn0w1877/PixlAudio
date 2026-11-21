package com.theveloper.pixelplay.data.songtap

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SongTapSenderTest {

    private class Ids(val video: String?, val isrc: String? = null) : SongTapSongIds {
        override suspend fun videoIdFor(spotifyId: String) = video
        override suspend fun isrcFor(spotifyId: String) = isrc
    }

    private fun song(spotifyId: String?, art: String? = "https://i.scdn.co/image/abc") = Song(
        id = "1", title = "Title", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L, path = "",
        contentUriString = "spotify://x", albumArtUriString = art, duration = 200_000L, mimeType = null,
        bitrate = null, sampleRate = null, spotifyId = spotifyId,
    )

    @Test
    fun `a Spotify song with a matched video sends both ids and its recording code`() = runTest {
        val sender = SongTapSender(Ids("dQw4w9WgXcQ", "GBARL9300135"))
        val p = sender.payloadFor(song("4uLU6hMCjMI75M1A2tKUQC"), 5L)!!
        assertThat(p.ytVideoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(p.spotifyId).isEqualTo("4uLU6hMCjMI75M1A2tKUQC")
        assertThat(p.isrc).isEqualTo("GBARL9300135")
        assertThat(p.artUrl).isEqualTo("https://i.scdn.co/image/abc")
        assertThat(p.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `a song saved from YouTube sends no made-up Spotify id`() = runTest {
        val synthetic = SpotifyRepository.syntheticIdForVideo("dQw4w9WgXcQ")
        val p = SongTapSender(Ids("dQw4w9WgXcQ")).payloadFor(song(synthetic), 5L)!!
        assertThat(p.spotifyId).isNull()
        assertThat(p.ytVideoId).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `a Spotify song not matched yet is sent by its Spotify id alone`() = runTest {
        val p = SongTapSender(Ids(null)).payloadFor(song("4uLU6hMCjMI75M1A2tKUQC"), 5L)!!
        assertThat(p.ytVideoId).isNull()
        assertThat(p.spotifyId).isEqualTo("4uLU6hMCjMI75M1A2tKUQC")
    }

    @Test
    fun `a song with no id at all cannot be sent`() = runTest {
        assertThat(SongTapSender(Ids("dQw4w9WgXcQ")).payloadFor(song(null), 5L)).isNull()
        assertThat(SongTapSender(Ids(null)).payloadFor(song("short"), 5L)).isNull()
    }

    @Test
    fun `art on a host that is not allowed is left out and nothing about the listener is added`() = runTest {
        val p = SongTapSender(Ids("dQw4w9WgXcQ")).payloadFor(song("4uLU6hMCjMI75M1A2tKUQC", art = "content://media/external/x"), 5L)!!
        assertThat(p.artUrl).isNull()
    }
}
