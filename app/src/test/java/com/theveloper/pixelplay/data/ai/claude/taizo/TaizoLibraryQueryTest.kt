package com.theveloper.pixelplay.data.ai.claude.taizo

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

/** search_library sees the whole library: filters, sorts and paging, with no cap at 100. */
class TaizoLibraryQueryTest {
    private fun song(
        id: Int,
        title: String = "Track $id",
        artist: String = "Artist ${id % 7}",
        album: String = "Album ${id % 11}",
        genre: String? = if (id % 2 == 0) "Rock" else "Pop",
        year: Int = 1990 + id % 30,
        dateAdded: Long = id.toLong(),
    ) = Song.emptySong().copy(
        id = id.toString(), title = title, artist = artist, album = album, genre = genre, year = year,
        dateAdded = dateAdded, duration = 200_000,
    )

    private val library = (1..500).map { song(it) }
    private val stats = (1..500).associate { it.toString() to SongPlayStats(plays = it % 10, lastPlayedMs = it * 1_000L) }
        .filterValues { it.plays > 0 }
    private fun run(filter: LibraryFilter, liked: Set<String> = emptySet(), lib: List<Song> = library, played: Map<String, SongPlayStats> = stats) =
        TaizoLibraryQuery.run(lib, played, liked, filter)

    @Test
    fun `all five hundred songs are reachable by paging, nothing stops at a hundred`() {
        val seen = LinkedHashSet<String>()
        var offset = 0
        while (true) {
            val page = run(LibraryFilter(limit = 200, offset = offset, sort = LibrarySort.TITLE))
            assertThat(page.total).isEqualTo(500)
            page.songs.forEach { seen += it.id }
            if (!page.hasMore) break
            offset += page.songs.size
        }
        assertThat(seen).hasSize(500)
    }

    @Test
    fun `limit is clamped to 200 and an offset past the end is empty`() {
        assertThat(run(LibraryFilter(limit = 5_000)).songs).hasSize(200)
        assertThat(run(LibraryFilter(limit = 0)).songs).hasSize(1)
        val past = run(LibraryFilter(offset = 9_999))
        assertThat(past.songs).isEmpty()
        assertThat(past.hasMore).isFalse()
        assertThat(past.total).isEqualTo(500)
    }

    @Test
    fun `words must all match, ignoring case and accents, and a title match beats an artist match`() {
        val lib = listOf(
            song(1, title = "Creep", artist = "Radiohead"),
            song(2, title = "Karma Police", artist = "Radiohead"),
            song(3, title = "Radiohead Tribute", artist = "Cover Band"),
            song(4, title = "Café del Mar", artist = "Ibiza Chill"),
        )
        assertThat(run(LibraryFilter(query = "RADIOHEAD"), lib = lib, played = emptyMap()).songs.map { it.id }).containsExactly("3", "1", "2").inOrder()
        assertThat(run(LibraryFilter(query = "radiohead creep"), lib = lib).songs.map { it.id }).containsExactly("1")
        assertThat(run(LibraryFilter(query = "cafe mar"), lib = lib).songs.map { it.id }).containsExactly("4")
        assertThat(run(LibraryFilter(query = "no such thing"), lib = lib).songs).isEmpty()
    }

    @Test
    fun `artist album and genre filters narrow the library`() {
        assertThat(run(LibraryFilter(artist = "artist 3")).songs.all { it.artist == "Artist 3" }).isTrue()
        assertThat(run(LibraryFilter(artist = "artist 3", limit = 200)).total).isEqualTo(library.count { it.artist == "Artist 3" })
        assertThat(run(LibraryFilter(album = "album 4", limit = 200)).total).isEqualTo(library.count { it.album == "Album 4" })
        val rock = run(LibraryFilter(genre = "rock", limit = 200))
        assertThat(rock.total).isEqualTo(250)
        assertThat(rock.songs.all { it.genre == "Rock" }).isTrue()
    }

    @Test
    fun `year and play count ranges`() {
        val years = run(LibraryFilter(yearFrom = 2000, yearTo = 2005, limit = 200))
        assertThat(years.songs.all { it.year in 2000..2005 }).isTrue()
        assertThat(years.total).isEqualTo(library.count { it.year in 2000..2005 })
        // Songs without a year never match a year filter.
        assertThat(run(LibraryFilter(yearFrom = 1), lib = listOf(song(1, year = 0))).songs).isEmpty()

        val played = run(LibraryFilter(minPlays = 8, limit = 200))
        assertThat(played.songs.all { (stats[it.id]?.plays ?: 0) >= 8 }).isTrue()
        val few = run(LibraryFilter(maxPlays = 1, minPlays = 1, limit = 200))
        assertThat(few.songs.all { stats[it.id]?.plays == 1 }).isTrue()
    }

    @Test
    fun `never played and liked only`() {
        val never = run(LibraryFilter(neverPlayed = true, limit = 200))
        assertThat(never.total).isEqualTo(50) // every id divisible by 10 has no plays
        assertThat(never.songs.all { stats[it.id] == null }).isTrue()

        val liked = run(LibraryFilter(likedOnly = true), liked = setOf("5", "9"))
        assertThat(liked.songs.map { it.id }).containsExactly("5", "9")
        val flagged = listOf(song(1).copy(isFavorite = true), song(2))
        assertThat(run(LibraryFilter(likedOnly = true), lib = flagged).songs.map { it.id }).containsExactly("1")
    }

    @Test
    fun `sorts`() {
        val mostPlayed = run(LibraryFilter(sort = LibrarySort.MOST_PLAYED, limit = 200)).songs
        assertThat(mostPlayed.map { stats[it.id]?.plays ?: 0 }).isInOrder(compareByDescending<Int> { it })
        val recent = run(LibraryFilter(sort = LibrarySort.RECENTLY_PLAYED, limit = 3)).songs
        assertThat(recent.map { it.id }).containsExactly("499", "498", "497").inOrder() // 500 has no plays
        val added = run(LibraryFilter(sort = LibrarySort.RECENTLY_ADDED, limit = 3)).songs
        assertThat(added.map { it.id }).containsExactly("500", "499", "498").inOrder()
        val titles = run(LibraryFilter(sort = LibrarySort.TITLE, limit = 3)).songs.map { it.title }
        assertThat(titles).isInOrder()
    }

    @Test
    fun `random is shuffled but the same on every page, so paging never repeats a song`() {
        val first = TaizoLibraryQuery.run(library, stats, emptySet(), LibraryFilter(sort = LibrarySort.RANDOM, limit = 100, offset = 0), seed = 42)
        val second = TaizoLibraryQuery.run(library, stats, emptySet(), LibraryFilter(sort = LibrarySort.RANDOM, limit = 100, offset = 100), seed = 42)
        assertThat(first.songs.map { it.id }.toSet().intersect(second.songs.map { it.id }.toSet())).isEmpty()
        val again = TaizoLibraryQuery.run(library, stats, emptySet(), LibraryFilter(sort = LibrarySort.RANDOM, limit = 100, offset = 0), seed = 42)
        assertThat(again.songs).isEqualTo(first.songs)
        assertThat(first.songs.map { it.id }).isNotEqualTo(library.take(100).map { it.id })
    }

    @Test
    fun `with no query the default order is most played`() {
        val songs = run(LibraryFilter(limit = 10)).songs
        assertThat(songs.map { stats[it.id]?.plays ?: 0 }).isInOrder(compareByDescending<Int> { it })
    }
}
