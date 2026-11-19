package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Fetch
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.ReleaseKind
import com.theveloper.pixelplay.data.youtube.ReleaseRef
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/** New releases (docs/brain-spec.md, 6.3): the diff rules, the guards, the row, and the requests it costs. */
class NewReleasesTest {

    private class MemoryFile : ReleaseFileStore {
        var file = NewReleaseFile()
        var writes = 0
        override fun read() = file
        override fun write(file: NewReleaseFile) { this.file = file; writes++ }
    }

    private val year = LocalDate.now(ZoneId.systemDefault()).year
    private val month = LocalDate.now(ZoneId.systemDefault()).monthValue

    private fun release(id: Int, title: String, y: Int = year, kind: ReleaseKind = ReleaseKind.SINGLE, playlist: String? = "OLAK5uy_$id") =
        ReleaseRef("MPREb_$id", title, y, kind, playlist)

    // ── The pure rules ───────────────────────────────────────────────────────

    @Test
    fun `the first look at an artist is a baseline and surfaces nothing`() {
        val diff = NewReleaseRules.diff(null, listOf(release(1, "Old"), release(2, "Newer")), year, month)
        assertTrue(diff.baseline)
        assertTrue(diff.surfaced.isEmpty())
        assertEquals(listOf("MPREb_1", "MPREb_2"), diff.knownIds)
    }

    @Test
    fun `a new id of this year is news, an old id is not`() {
        val known = ArtistReleaseState("UC1", "A", listOf("MPREb_1"), listOf(NewReleaseRules.titleKey("Old")), 1L)
        val diff = NewReleaseRules.diff(known, listOf(release(2, "Fresh"), release(1, "Old")), year, month)
        assertEquals(listOf("MPREb_2"), diff.surfaced.map { it.browseId })
        assertTrue("MPREb_2" in diff.knownIds)
    }

    @Test
    fun `a re-release under a new id with an older title is dropped, and so is a repeat in one check`() {
        val known = ArtistReleaseState("UC1", "A", listOf("MPREb_1"), listOf(NewReleaseRules.titleKey("Night Drive")), 1L)
        val found = listOf(
            release(2, "Night Drive (Remastered)"), // same song under a new id
            release(3, "Brand New"),
            release(4, "Brand New"), // the same title again in one answer
        )
        val diff = NewReleaseRules.diff(known, found, year, month)
        assertEquals(listOf("MPREb_3"), diff.surfaced.map { it.browseId })
        // Every id seen is remembered, so none is judged twice.
        assertTrue(listOf("MPREb_2", "MPREb_3", "MPREb_4").all { it in diff.knownIds })
    }

    @Test
    fun `the year must be the current one, or last year's during January`() {
        assertTrue(NewReleaseRules.isCurrentYear(2026, 2026, 6))
        assertFalse(NewReleaseRules.isCurrentYear(2025, 2026, 6))
        assertTrue(NewReleaseRules.isCurrentYear(2025, 2026, 1))
        assertFalse(NewReleaseRules.isCurrentYear(2024, 2026, 1))
        val known = ArtistReleaseState("UC1", "A", listOf("MPREb_1"), emptyList(), 1L)
        val diff = NewReleaseRules.diff(known, listOf(release(2, "Last decade", y = year - 3)), year, month)
        assertTrue(diff.surfaced.isEmpty())
    }

    @Test
    fun `known ids stay bounded`() {
        val many = (1..400).map { release(it, "Title $it") }
        assertEquals(NewReleaseRules.MAX_KNOWN_IDS, NewReleaseRules.diff(null, many, year, month).knownIds.size)
    }

    private fun found(channel: String, artist: String, at: Long, rids: List<Long>) = FoundRelease(
        channelId = channel, artistName = artist, browseId = "MPREb_$channel$at", title = "T", year = year, foundAtMs = at,
        rows = rids.map { StoredRadioRow(RadioRow("v$it", "S$it", listOf(RadioArtistRef(artist, channel))), it) },
    )

    @Test
    fun `the cards are newest first, three per release, two per artist, a recording once`() {
        val now = 100L * 24 * 3_600_000
        val a = found("UCa", "A", now - 1_000, listOf(1, 2, 3, 4))
        val b = found("UCb", "B", now - 2_000, listOf(5, 6, 7))
        val c = found("UCc", "C", now - 3_000, listOf(1, 8)) // rid 1 is already in A's cards
        val cards = NewReleaseRules.cards(listOf(c, b, a), now)
        assertEquals(listOf(1L, 2L, 5L, 6L, 8L), cards.map { it.stored.rid })
    }

    @Test
    fun `a release older than 28 days leaves the row`() {
        val now = 100L * 24 * 3_600_000
        val old = found("UCa", "A", now - NewReleaseRules.FRESH_MS - 1, listOf(1, 2))
        assertTrue(NewReleaseRules.cards(listOf(old), now).isEmpty())
    }

    // ── check(): the requests, and what is stored ────────────────────────────

    private fun fixture(): Triple<NewReleases, FakeRadioSource, MemoryFile> {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val clock = Clock(1_000_000_000_000L)
        return Triple(NewReleases(file, source, MemoryTrackResolver(), clock), source, file)
    }

    private class Clock(var now: Long) : BrainClock {
        override fun nowMs() = now
    }

    private fun page(albums: List<ReleaseRef>, singles: List<ReleaseRef> = emptyList()) =
        ArtistPage("A", null, emptyList(), albums, singles, emptyList())

    private fun rows(artist: String, n: Int, from: Int = 1) = (from until from + n).map { mixRow(it, artist) }

    @Test
    fun `the first check is a baseline that costs one request, the second finds the new release`() = runTest {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val clock = Clock(System.currentTimeMillis())
        val releases = NewReleases(file, source, MemoryTrackResolver(), clock)
        source.artistPages["UCa"] = page(listOf(release(1, "Old", y = year - 2)))
        assertEquals(Fetch.NETWORK, releases.check("UCa", "A"))
        assertEquals(listOf("artist:UCa"), source.calls)
        assertTrue(file.file.found.isEmpty())
        assertEquals(1, releases.requestsSince(0))

        // A day later the artist has a new single.
        clock.now += 25L * 3_600_000
        source.artistPages["UCa"] = page(listOf(release(1, "Old", y = year - 2)), listOf(release(2, "Fresh")))
        source.releaseRows["MPREb_2"] = rows("A", 4)
        assertEquals(Fetch.NETWORK, releases.check("UCa", "A"))
        assertEquals(listOf("artist:UCa", "artist:UCa", "release:MPREb_2"), source.calls)
        assertEquals(1, file.file.found.size)
        assertEquals(4, file.file.found.single().rows.size)
        // The artist page and the release's audio playlist (the card carried its id: no album-page request). The first
        // check's request is a day old and no longer counts: the count is the last 24 hours, the worker's budget.
        assertEquals(2, releases.requestsSince(0))

        // Asked again at once: nothing is requested.
        assertEquals(Fetch.CACHED, releases.check("UCa", "A"))
        assertEquals(3, source.calls.size)
    }

    @Test
    fun `a full carousel reads the whole discography`() = runTest {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val releases = NewReleases(file, source, MemoryTrackResolver(), Clock(System.currentTimeMillis()))
        val ten = (1..10).map { release(it, "Release $it", y = year - 1) }
        source.artistPages["UCa"] = page(ten)
        source.discographies["UCa"] = ten + release(11, "Hidden one", y = year - 4)
        releases.check("UCa", "A")
        assertEquals(listOf("artist:UCa", "discography:UCa"), source.calls)
        assertTrue("MPREb_11" in file.file.artists.single().knownIds)
    }

    @Test
    fun `tracks the listener already owns, music videos and fragments are not cards`() = runTest {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val clock = Clock(System.currentTimeMillis())
        val releases = NewReleases(file, source, MemoryTrackResolver(), clock)
        source.artistPages["UCa"] = page(emptyList())
        releases.check("UCa", "A")
        clock.now += 25L * 3_600_000
        source.artistPages["UCa"] = page(listOf(release(2, "Fresh", kind = ReleaseKind.ALBUM)))
        source.releaseRows["MPREb_2"] = listOf(
            mixRow(1, "A"), mixRow(2, "A", official = false), mixRow(3, "A", seconds = 20), mixRow(4, "A"), mixRow(5, "A"),
        )
        releases.check("UCa", "A") { row -> row.videoId == "vid0000001" }
        assertEquals(listOf("vid0000004", "vid0000005"), file.file.found.single().rows.map { it.row.videoId })
    }

    @Test
    fun `a release whose tracks could not be read is tried again at the next check`() = runTest {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val clock = Clock(System.currentTimeMillis())
        val releases = NewReleases(file, source, MemoryTrackResolver(), clock)
        source.artistPages["UCa"] = page(emptyList())
        releases.check("UCa", "A")
        clock.now += 25L * 3_600_000
        source.artistPages["UCa"] = page(listOf(release(2, "Fresh")))
        source.releaseRows["MPREb_2"] = null
        releases.check("UCa", "A")
        assertTrue(file.file.found.isEmpty())
        assertFalse("MPREb_2" in file.file.artists.single().knownIds)
        clock.now += 25L * 3_600_000
        source.releaseRows["MPREb_2"] = rows("A", 3)
        releases.check("UCa", "A")
        assertEquals(1, file.file.found.size)
    }

    @Test
    fun `a page that did not come back is nothing and costs the request`() = runTest {
        val (releases, source, file) = fixture()
        assertEquals(Fetch.NONE, releases.check("UCx", "X"))
        assertEquals(1, releases.requestsSince(0))
        assertTrue(file.file.artists.isEmpty())
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `artists never checked go first, then the one checked longest ago`() = runTest {
        val source = FakeRadioSource()
        val file = MemoryFile()
        val clock = Clock(System.currentTimeMillis())
        val releases = NewReleases(file, source, MemoryTrackResolver(), clock)
        source.artistPages["UC1"] = page(emptyList())
        source.artistPages["UC2"] = page(emptyList())
        releases.check("UC1", "One")
        clock.now += 1_000
        releases.check("UC2", "Two")
        val order = releases.checkOrder(listOf("Two" to null, "Three" to null, "One" to null)).map { it.first }
        assertEquals(listOf("Three", "One", "Two"), order)
        assertNotNull(releases.channelOf("two"))
        assertNull(releases.channelOf("Nobody"))
    }
}
