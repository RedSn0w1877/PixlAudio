package com.theveloper.pixelplay.data.blocking

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

class BlockedArtistsTest {

    private fun song(
        id: String,
        artist: String,
        artistId: Long,
        credits: List<ArtistRef> = emptyList(),
    ) = Song(
        id = id, title = "t$id", artist = artist, artistId = artistId, artists = credits,
        album = "a", albumId = 1L, path = "", contentUriString = "", albumArtUriString = null,
        duration = 1000L, mimeType = null, bitrate = null, sampleRate = null,
    )

    @Test
    fun codecRoundTripsNamesContainingTheSeparator() {
        val artist = BlockedArtist(42L, "AC|DC")
        assertThat(BlockedArtistCodec.decode(BlockedArtistCodec.encode(artist))).isEqualTo(artist)
        assertThat(BlockedArtistCodec.decode("garbage")).isNull()
        assertThat(BlockedArtistCodec.decode("x|name")).isNull()
    }

    @Test
    fun matchesByIdFirst() {
        val index = BlockedArtistIndex(listOf(BlockedArtist(7L, "Somebody")))
        assertThat(index.isBlocked(song("1", "Other Name", 7L))).isTrue()
        assertThat(index.isBlocked(song("2", "Other Name", 8L))).isFalse()
    }

    @Test
    fun fallsBackToFoldedName() {
        val index = BlockedArtistIndex(listOf(BlockedArtist(7L, "Beyoncé")))
        assertThat(index.isBlocked(song("1", "BEYONCE", 999L))).isTrue()
    }

    @Test
    fun anyCreditedArtistBlocksTheSong() {
        val index = BlockedArtistIndex(listOf(BlockedArtist(2L, "Guest")))
        val feat = song("1", "Main", 1L, listOf(ArtistRef(1L, "Main", true), ArtistRef(2L, "Guest")))
        assertThat(index.isBlocked(feat)).isTrue()
        assertThat(index.filterAllowed(listOf(feat, song("2", "Main", 1L)))).hasSize(1)
    }

    @Test
    fun unknownIdsNeverMatchEachOther() {
        val index = BlockedArtistIndex(listOf(BlockedArtist(-1L, "Nobody")))
        assertThat(index.isBlockedCredit(-1L, null)).isFalse()
        assertThat(index.isBlockedCredit(null, "nobody")).isTrue()
    }

    @Test
    fun playerItemsMatchByDisplayNamesOrIds() {
        val index = BlockedArtistIndex(listOf(BlockedArtist(5L, "Guest Star")))
        assertThat(index.isBlockedItem(null, "Main, Guest Star")).isTrue()
        assertThat(index.isBlockedItem(longArrayOf(1L, 5L), "whatever")).isTrue()
        assertThat(index.isBlockedItem(null, "Main")).isFalse()
        assertThat(BlockedArtistIndex.EMPTY.isBlockedItem(null, "Main")).isFalse()
    }

    @Test
    fun emptyIndexBlocksNothing() {
        assertThat(BlockedArtistIndex.EMPTY.isBlocked(song("1", "x", 1L))).isFalse()
        assertThat(BlockedArtistIndex.EMPTY.isEmpty).isTrue()
    }

    @Test
    fun guardSkipsInTheTravelDirectionAndLetsAllowedSongsThrough() {
        val guard = BlockedSkipGuard()
        assertThat(guard.decide(false, false, true, true, 5)).isEqualTo(BlockedSkipGuard.Action.NONE)
        assertThat(guard.decide(true, false, true, true, 5)).isEqualTo(BlockedSkipGuard.Action.SKIP_FORWARD)
        assertThat(guard.decide(true, false, false, true, 5)).isEqualTo(BlockedSkipGuard.Action.SKIP_BACKWARD)
        assertThat(guard.decide(true, true, true, true, 5)).isEqualTo(BlockedSkipGuard.Action.NONE)
    }

    @Test
    fun guardPausesWhenNothingIsLeftOrEverythingIsBlocked() {
        val guard = BlockedSkipGuard()
        assertThat(guard.decide(true, false, true, false, 3)).isEqualTo(BlockedSkipGuard.Action.PAUSE)
        // A fully blocked queue with repeat-all would loop forever: stop after one lap.
        repeat(3) { assertThat(guard.decide(true, false, true, true, 3)).isEqualTo(BlockedSkipGuard.Action.SKIP_FORWARD) }
        assertThat(guard.decide(true, false, true, true, 3)).isEqualTo(BlockedSkipGuard.Action.PAUSE)
    }
}
