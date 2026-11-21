package com.theveloper.pixelplay.data.songtap

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.songtap.SongTapPayload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SongTapReceiverTest {

    private class FakeLibrary : SongTapLibrary {
        val videos = mutableSetOf<String>()
        val spotify = mutableSetOf<String>()
        var found: String? = null
        var failImport = false
        val calls = mutableListOf<String>()

        override suspend fun hasVideo(videoId: String): Boolean { calls += "hasVideo"; return videoId in videos }
        override suspend fun hasSpotifyId(spotifyId: String): Boolean { calls += "hasSpotifyId"; return spotifyId in spotify }
        override suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?): String? {
            calls += "findVideo:$title|$artist|$durationMs"
            return found
        }
        override suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?): Boolean {
            calls += "import:$videoId|$title|$artist|$durationMs|$artUrl"
            if (failImport) error("disk full")
            return videos.add(videoId)
        }
        override suspend fun remove(videoId: String): Boolean { calls += "remove:$videoId"; return videos.remove(videoId) }
        override suspend fun songId(videoId: String) = "id-$videoId"
        override suspend fun songIdForSpotify(spotifyId: String) = "sp-$spotifyId"
    }

    private val payload = SongTapPayload(
        v = 1, ts = 1L, ytVideoId = "dQw4w9WgXcQ", spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = null,
        title = "Never Gonna Give You Up", artist = "Rick Astley", durationMs = 213_000, artUrl = "https://i.ytimg.com/vi/x/y.jpg",
    )

    @Test
    fun `a new song is imported with the beam's own text and can be undone`() = runTest {
        val lib = FakeLibrary()
        val receiver = SongTapReceiver(lib)
        val result = receiver.save(payload)
        assertThat(result).isInstanceOf(SongTapSaveResult.Saved::class.java)
        val card = (result as SongTapSaveResult.Saved).card
        assertThat(card.title).isEqualTo("Never Gonna Give You Up")
        assertThat(card.songId).isEqualTo("id-dQw4w9WgXcQ")
        assertThat(lib.calls).contains("import:dQw4w9WgXcQ|Never Gonna Give You Up|Rick Astley|213000|https://i.ytimg.com/vi/x/y.jpg")
        assertThat(receiver.undo("dQw4w9WgXcQ")).isTrue()
        assertThat(lib.videos).isEmpty()
    }

    @Test
    fun `a song already in the library is not imported again`() = runTest {
        val lib = FakeLibrary().apply { videos += "dQw4w9WgXcQ" }
        val result = SongTapReceiver(lib).save(payload)
        assertThat(result).isInstanceOf(SongTapSaveResult.AlreadyHad::class.java)
        assertThat(lib.calls.none { it.startsWith("import") }).isTrue()
    }

    @Test
    fun `the listener's own Spotify row counts as already there`() = runTest {
        val lib = FakeLibrary().apply { spotify += "4uLU6hMCjMI75M1A2tKUQC" }
        val result = SongTapReceiver(lib).save(payload)
        assertThat(result).isInstanceOf(SongTapSaveResult.AlreadyHad::class.java)
        assertThat(lib.calls.none { it.startsWith("import") }).isTrue()
    }

    @Test
    fun `a song with only a Spotify id is looked up and then saved under the video that was found`() = runTest {
        val lib = FakeLibrary().apply { found = "abcdefghijk" }
        val result = SongTapReceiver(lib).save(payload.copy(ytVideoId = null))
        assertThat(result).isInstanceOf(SongTapSaveResult.Saved::class.java)
        assertThat(lib.calls).contains("findVideo:Never Gonna Give You Up|Rick Astley|213000")
        assertThat(lib.calls.any { it.startsWith("import:abcdefghijk|") }).isTrue()
    }

    @Test
    fun `a Spotify only song the listener already has is not searched for`() = runTest {
        val lib = FakeLibrary().apply { spotify += "4uLU6hMCjMI75M1A2tKUQC" }
        val result = SongTapReceiver(lib).save(payload.copy(ytVideoId = null)) as SongTapSaveResult.AlreadyHad
        assertThat(result.card.songId).isEqualTo("sp-4uLU6hMCjMI75M1A2tKUQC")
        assertThat(lib.calls.none { it.startsWith("findVideo") }).isTrue()
    }

    @Test
    fun `nothing found means nothing saved`() = runTest {
        val lib = FakeLibrary()
        val result = SongTapReceiver(lib).save(payload.copy(ytVideoId = null))
        assertThat(result).isEqualTo(SongTapSaveResult.NotFound)
        assertThat(lib.calls.none { it.startsWith("import") }).isTrue()
    }

    @Test
    fun `a failing library is a failed result and not a crash`() = runTest {
        val lib = FakeLibrary().apply { failImport = true }
        assertThat(SongTapReceiver(lib).save(payload)).isEqualTo(SongTapSaveResult.Failed)
    }

    @Test
    fun `receiving touches the library and nothing else`() = runTest {
        // The receiver's only collaborator is the library port: no brain, no listening history and no
        // "saved while playing" flag can be reached from here, so a private session has nothing to switch off.
        val ctor = SongTapReceiver::class.java.declaredConstructors.single()
        assertThat(ctor.parameterTypes.toList()).containsExactly(SongTapLibrary::class.java)
        val lib = FakeLibrary()
        SongTapReceiver(lib).save(payload)
        assertThat(lib.calls.map { it.substringBefore(':') }.toSet()).containsExactly("hasVideo", "hasSpotifyId", "import")
    }

    @Test
    fun `an undo that cannot be done says so`() = runTest {
        val lib = FakeLibrary()
        assertThat(SongTapReceiver(lib).undo("zzzzzzzzzzz")).isFalse()
    }
}
