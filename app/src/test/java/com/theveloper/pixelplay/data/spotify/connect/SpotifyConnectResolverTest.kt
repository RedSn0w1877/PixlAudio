package com.theveloper.pixelplay.data.spotify.connect

import com.theveloper.pixelplay.data.network.spotify.SpotifyArtistRef
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The strict match and the lookup cache (ported from iOS `SpotifyConnectResolverTests`). */
class SpotifyConnectResolverTest {
    private fun song(title: String, artist: String, seconds: Long, key: String = "f:1", spotifyId: String? = null) =
        ConnectTrack(key, title, artist, seconds * 1000, spotifyId, null)

    private fun track(id: String, name: String, artists: List<String>, seconds: Long, isLocal: Boolean? = null) =
        SpotifyTrack(id, name, seconds * 1000, artists.map { SpotifyArtistRef(null, it) }, isLocal = isLocal, type = "track")

    private val idA = "4iV5W9uYEdYUVa79Axb7Rh"
    private val idB = "1301WleyT98MSxVHPZCA6M"

    @Test fun `strict match rules`() {
        val local = song("Here Comes the Sun", "The Beatles", 185)
        assertTrue(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes The Sun - Remastered 2009", listOf("The Beatles"), 186)))
        assertTrue(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Sun", listOf("The Beatles"), 188)))
        // Duration more than 3 s away.
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Sun", listOf("The Beatles"), 189)))
        // Live / remix / cover mismatch, either way.
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Sun - Live", listOf("The Beatles"), 185)))
        assertFalse(SpotifyConnectMatcher.isStrictMatch(song("Song (Remix)", "A", 200), track(idA, "Song", listOf("A"), 200)))
        assertTrue(SpotifyConnectMatcher.isStrictMatch(song("Song (Remix)", "A", 200), track(idA, "Song - Remix", listOf("A"), 201)))
        // Another artist, another title.
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Sun", listOf("Nina Simone"), 185)))
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Rain", listOf("The Beatles"), 185)))
        // Featured artists on either side, multi-artist tags, accents.
        assertTrue(
            SpotifyConnectMatcher.isStrictMatch(
                song("Stay (feat. Justin Bieber)", "The Kid LAROI & Justin Bieber", 141),
                track(idA, "STAY (with Justin Bieber)", listOf("The Kid LAROI", "Justin Bieber"), 141)
            )
        )
        assertTrue(SpotifyConnectMatcher.isStrictMatch(song("Café", "Zoé", 210), track(idA, "Cafe", listOf("Zoe"), 211)))
        // Only real catalogue tracks.
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track("0123456789abcdef012345", "Here Comes the Sun", listOf("The Beatles"), 185)))
        assertFalse(SpotifyConnectMatcher.isStrictMatch(local, track(idA, "Here Comes the Sun", listOf("The Beatles"), 185, isLocal = true)))
        // ISRC hits only need the duration to agree.
        assertTrue(SpotifyConnectMatcher.isIsrcMatch(local, track(idA, "Something else", listOf("Someone"), 184)))
        assertFalse(SpotifyConnectMatcher.isIsrcMatch(local, track(idA, "Here Comes the Sun", listOf("The Beatles"), 240)))
        // The closest duration wins among accepted candidates.
        val best = SpotifyConnectMatcher.best(
            local,
            listOf(track(idB, "Here Comes the Sun", listOf("The Beatles"), 188), track(idA, "Here Comes the Sun", listOf("The Beatles"), 185)),
            isrc = false
        )
        assertEquals(idA, best?.id)
    }

    @Test fun `queries`() {
        assertEquals("isrc:USUM71703861", SpotifyConnectMatcher.isrcQuery("us-um7-17-03861"))
        assertNull(SpotifyConnectMatcher.isrcQuery("nope"))
        assertNull(SpotifyConnectMatcher.isrcQuery(null))
        assertEquals("Track   One A", SpotifyConnectMatcher.textQuery(song("Track: \"One\"", "A feat. B", 1)))
        assertEquals("Simon", SpotifyConnectMatcher.primaryArtist("Simon & Garfunkel"))
        assertNull(SpotifyConnectMatcher.textQuery(song(" ", "A", 1)))
    }

    private class MemoryStorage : SpotifyConnectResolutionStorage {
        var json: String? = null
        override fun load(): String? = json
        override fun save(json: String) {
            this.json = json
        }
    }

    @Test fun `resolver uses ids, then ISRC, then text, and caches misses`() = runTest {
        val queries = mutableListOf<String>()
        var now = 1_000L
        var failing = false
        val storage = MemoryStorage()
        val search: suspend (String) -> List<SpotifyTrack>? = { query ->
            queries += query
            when {
                failing -> null
                query == "isrc:GBAYE0601690" -> listOf(track(idB, "Whatever", listOf("X"), 185))
                query.startsWith("Here Comes the Sun") -> listOf(track(idA, "Here Comes the Sun", listOf("The Beatles"), 186))
                else -> emptyList()
            }
        }
        val resolver = SpotifyConnectResolver(
            storage = storage,
            search = search,
            isrc = { if (it.songId == "f:isrc") "GBAYE0601690" else null },
            nowMs = { now }
        )

        // A real Spotify id: no lookup.
        assertEquals(SpotifyConnectSlot.Uri("spotify:track:$idA"), resolver.resolve(song("x", "y", 1, key = "sp:1", spotifyId = idA)))
        assertTrue(queries.isEmpty())
        // ISRC first.
        assertEquals(SpotifyConnectSlot.Uri("spotify:track:$idB"), resolver.resolve(song("Here Comes the Sun", "The Beatles", 185, key = "f:isrc")))
        assertEquals(listOf("isrc:GBAYE0601690"), queries)
        // Then title + artist.
        assertEquals(SpotifyConnectSlot.Uri("spotify:track:$idA"), resolver.resolve(song("Here Comes the Sun", "The Beatles", 185, key = "f:2")))
        assertEquals("Here Comes the Sun The Beatles", queries.last())
        // A miss is cached and not asked again until the retry age.
        val unknown = song("Bedroom Demo 3", "Me", 100, key = "f:3")
        assertEquals(SpotifyConnectSlot.Skipped, resolver.resolve(unknown))
        val asked = queries.size
        assertEquals(SpotifyConnectSlot.Skipped, resolver.resolve(unknown))
        assertEquals(asked, queries.size)
        now += SpotifyConnectResolver.MISS_RETRY_MS
        assertEquals(SpotifyConnectSlot.Pending, resolver.cached(unknown))
        // A failed search caches nothing.
        failing = true
        val other = song("Other", "Me", 100, key = "f:4")
        assertEquals(SpotifyConnectSlot.Pending, resolver.resolve(other))
        assertEquals(SpotifyConnectSlot.Pending, resolver.cached(other))
        // New tags invalidate the cached answer.
        val retagged = song("Here Comes the Sun", "The Beatles", 185, key = "f:2")
        assertEquals(SpotifyConnectSlot.Uri("spotify:track:$idA"), resolver.cached(retagged))
        assertEquals(SpotifyConnectSlot.Pending, resolver.cached(retagged.copy(title = "Something")))

        // Persisted and reloaded.
        resolver.flush()
        val reloaded = SpotifyConnectResolver(storage, search = { emptyList() }, nowMs = { now })
        assertEquals(SpotifyConnectSlot.Uri("spotify:track:$idA"), reloaded.cached(song("Here Comes the Sun", "The Beatles", 185, key = "f:2")))
        reloaded.clear()
        assertEquals(
            SpotifyConnectSlot.Pending,
            SpotifyConnectResolver(storage, search = { emptyList() }).cached(song("Here Comes the Sun", "The Beatles", 185, key = "f:2"))
        )
    }

    @Test fun `an unreadable cache file starts empty`() = runTest {
        val storage = MemoryStorage().apply { json = "{not json" }
        val resolver = SpotifyConnectResolver(storage, search = { emptyList() })
        assertEquals(SpotifyConnectSlot.Pending, resolver.cached(song("A", "B", 100)))
    }
}
