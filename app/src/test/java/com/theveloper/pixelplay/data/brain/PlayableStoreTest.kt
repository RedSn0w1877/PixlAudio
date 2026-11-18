package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.EphemeralTrackLookup
import com.theveloper.pixelplay.data.brain.store.PlayableDao
import com.theveloper.pixelplay.data.brain.store.PlayableEntity
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.spotify.storedVideoIdFor
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `playable` path of docs/brain-spec.md, 7.2: a track with no library row still resolves to audio. */
class PlayableStoreTest {

    /** An in-memory `playable` table. */
    private class FakePlayableDao : PlayableDao {
        val rows = LinkedHashMap<String, PlayableEntity>()
        var videoIdReads = 0

        override suspend fun upsert(rows: List<PlayableEntity>) { for (row in rows) this.rows[row.synthId] = row }
        override suspend fun get(synthId: String) = rows[synthId]
        override suspend fun videoIdFor(synthId: String): String? {
            videoIdReads++
            return rows[synthId]?.videoId
        }
        override suspend fun getAll(synthIds: List<String>) = synthIds.mapNotNull { rows[it] }
        override suspend fun getBySongIds(songIds: List<Long>) = rows.values.filter { it.songId in songIds }
        override suspend fun getBySongId(songId: Long) = rows.values.firstOrNull { it.songId == songId }
        override suspend fun touch(synthIds: List<String>, nowMs: Long) {
            for (id in synthIds) rows[id]?.let { rows[id] = it.copy(lastUsedAt = nowMs) }
        }
        override suspend fun deleteUnused(olderThanMs: Long, keep: List<String>): Int {
            val doomed = rows.values.filter { it.lastUsedAt < olderThanMs && it.synthId !in keep }.map { it.synthId }
            doomed.forEach(rows::remove)
            return doomed.size
        }
        override suspend fun delete(synthId: String) { rows.remove(synthId) }
        override suspend fun count() = rows.size
    }

    private fun row(videoId: String, title: String = "Title", artist: String = "Artist") = RadioRow(
        videoId = videoId,
        title = title,
        artists = listOf(RadioArtistRef(artist, "UC$artist")),
        album = "Album",
        durationSec = 215,
        thumbnailUrl = "https://example.invalid/$videoId.jpg",
        kind = MusicVideoKind.ATV,
    )

    @Test
    fun `an engine track has the ids the importer would give it`() {
        val track = EngineTrack.from(row("dQw4w9WgXcQ"), rid = 42L)
        // First 22 hex characters of SHA-256("dQw4w9WgXcQ").
        assertEquals(22, track.synthId.length)
        assertEquals(SpotifyRepository.syntheticIdForVideo("dQw4w9WgXcQ"), track.synthId)
        assertTrue(track.synthId.all { it in '0'..'9' || it in 'a'..'f' })
        // The proxy only serves ids that look like Spotify track ids: this one must pass.
        assertTrue(CloudStreamSecurity.validateSpotifyTrackId(track.synthId))
        assertEquals(SpotifyRepository.unifiedSongId(track.synthId), track.songId)
        assertTrue(track.songId < 0, "unified-library ids are negative")
        assertEquals("spotify://${track.synthId}", track.contentUri)
        assertEquals(215_000L, track.durationMs)
    }

    @Test
    fun `the song of an engine track carries everything the player reads, with no library row`() {
        val track = EngineTrack.from(
            row("abcdefghijk").copy(artists = listOf(RadioArtistRef("Tyler, The Creator", "UC1"), RadioArtistRef("Frank Ocean", "UC2"))),
            rid = 7L,
        )
        val song = track.toSong()
        assertEquals(track.songId.toString(), song.id)
        assertEquals("spotify://${track.synthId}", song.contentUriString)
        assertEquals(track.synthId, song.spotifyId)
        assertEquals("Tyler, The Creator, Frank Ocean", song.displayArtist)
        // The credited artists stay a list: a name with a comma in it is one artist.
        assertEquals(listOf("Tyler, The Creator", "Frank Ocean"), song.artists.map { it.name })
        assertTrue(song.artists[0].isPrimary)
        assertEquals(SpotifyRepository.unifiedArtistId("Tyler, The Creator"), song.artistId)
        assertEquals(215_000L, song.duration)
        assertEquals("https://example.invalid/abcdefghijk.jpg", song.albumArtUriString)
    }

    @Test
    fun `a registered track resolves from memory without touching the table`() = runTest {
        val dao = FakePlayableDao()
        val store = PlayableStore(dao)
        val track = EngineTrack.from(row("aaaaaaaaaaa"), rid = 1L)
        store.register(listOf(track), nowMs = 1_000L)
        assertEquals("aaaaaaaaaaa", store.videoIdFor(track.synthId))
        assertEquals(0, dao.videoIdReads)
        assertEquals(1, dao.count())
        assertEquals(1_000L, dao.get(track.synthId)!!.lastUsedAt)
    }

    @Test
    fun `after a process restart the saved queue still resolves, from the table`() = runTest {
        val dao = FakePlayableDao()
        val track = EngineTrack.from(row("bbbbbbbbbbb"), rid = 2L)
        PlayableStore(dao).register(listOf(track))

        val afterRestart = PlayableStore(dao) // a new process: nothing in memory
        assertEquals("bbbbbbbbbbb", afterRestart.videoIdFor(track.synthId))
        assertEquals(1, dao.videoIdReads)
        // And it is remembered from then on.
        assertEquals("bbbbbbbbbbb", afterRestart.videoIdFor(track.synthId))
        assertEquals(1, dao.videoIdReads)
        assertNull(afterRestart.videoIdFor("0000000000000000000000"))
    }

    @Test
    fun `the proxy asks the library row first and playable only when it has nothing`() = runTest {
        val spotifyDao = mockk<SpotifyDao>()
        val ephemeral = mockk<EphemeralTrackLookup>()
        coEvery { spotifyDao.getMatchedVideoId("inLibrary0000000000000") } returns "libraryVid1"
        coEvery { spotifyDao.getMatchedVideoId("engineTrack00000000000") } returns null
        coEvery { spotifyDao.getMatchedVideoId("unknown000000000000000") } returns null
        coEvery { ephemeral.videoIdFor("engineTrack00000000000") } returns "engineVid01"
        coEvery { ephemeral.videoIdFor("unknown000000000000000") } returns null

        assertEquals("libraryVid1", storedVideoIdFor("inLibrary0000000000000", spotifyDao, ephemeral))
        coVerify(exactly = 0) { ephemeral.videoIdFor("inLibrary0000000000000") }
        assertEquals("engineVid01", storedVideoIdFor("engineTrack00000000000", spotifyDao, ephemeral))
        assertNull(storedVideoIdFor("unknown000000000000000", spotifyDao, ephemeral))
    }

    @Test
    fun `a saved track plays from its library row even while its playable row exists`() = runTest {
        val dao = FakePlayableDao()
        val store = PlayableStore(dao)
        val track = EngineTrack.from(row("ccccccccccc"), rid = 3L)
        store.register(listOf(track))
        val spotifyDao = mockk<SpotifyDao>()
        // The listener saved it and then re-matched it by hand to another video.
        coEvery { spotifyDao.getMatchedVideoId(track.synthId) } returns "manualPick1"
        assertEquals("manualPick1", storedVideoIdFor(track.synthId, spotifyDao, store))
    }

    @Test
    fun `recently played resolves engine tracks by the media id the history stored`() = runTest {
        val dao = FakePlayableDao()
        val store = PlayableStore(dao)
        val one = EngineTrack.from(row("ddddddddddd", "One", "First"), rid = 4L)
        val two = EngineTrack.from(row("eeeeeeeeeee", "Two", "Second"), rid = 5L)
        store.register(listOf(one, two))

        val songs = store.songsFor(listOf(one.songId.toString(), "-123", "not-a-number", two.songId.toString()))
        assertEquals(listOf("One", "Two"), songs.map { it.title }.sorted())
        val song = songs.first { it.title == "One" }
        assertEquals(one.songId.toString(), song.id)
        assertEquals("First", song.artist)
        assertEquals("spotify://${one.synthId}", song.contentUriString)
        assertEquals(one.synthId, song.spotifyId)
        assertNotNull(store.bySongId(two.songId.toString()))
        assertNull(store.bySongId("-123"))
        assertTrue(store.songsFor(emptyList()).isEmpty())
    }

    @Test
    fun `rows unused for sixty days are pruned, except what the queue or the history still names`() = runTest {
        val dao = FakePlayableDao()
        val store = PlayableStore(dao)
        val day = 24L * 60 * 60 * 1000
        val old = EngineTrack.from(row("fffffffffff"), rid = 6L)
        val oldButQueued = EngineTrack.from(row("ggggggggggg"), rid = 7L)
        val recent = EngineTrack.from(row("hhhhhhhhhhh"), rid = 8L)
        store.register(listOf(old, oldButQueued), nowMs = 0L)
        store.register(listOf(recent), nowMs = 50 * day)

        val removed = store.prune(keepSongIds = listOf(oldButQueued.songId.toString()), nowMs = 61 * day)
        assertEquals(1, removed)
        assertNull(dao.get(old.synthId))
        assertNotNull(dao.get(oldButQueued.synthId))
        assertNotNull(dao.get(recent.synthId))
    }
}
