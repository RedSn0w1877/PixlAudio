package com.theveloper.pixelplay.data.cloudstudio.auto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The prepare-ahead ranker under Ask Taizo's taste changes: no change means the same score, boost and reduce scale it. */
class CloudAutoPrepareTasteTest {
    private val now = 1_800_000_000_000L

    private fun song(id: String, taste: Double = 1.0, blocked: Boolean = false) = AutoCandidateFacts(
        songId = id, canonicalId = id, durationMs = 180_000, favorite = true, tasteMultiplier = taste, blockedArtist = blocked,
    )

    @Test fun `a multiplier of one leaves the score exactly as it was`() {
        assertEquals(CloudAutoPrepare.score(song("a"), now), CloudAutoPrepare.score(song("a", taste = 1.0), now), 0.0)
        assertEquals(3.0, CloudAutoPrepare.score(song("a"), now), 1e-9)
    }

    @Test fun `a boost raises and a reduce lowers the score`() {
        assertEquals(6.6, CloudAutoPrepare.score(song("a", taste = 2.2), now), 1e-9)
        assertEquals(3.0 / 1.6, CloudAutoPrepare.score(song("a", taste = 1.0 / 1.6), now), 1e-9)
        val ranked = CloudAutoPrepare.rank(listOf(song("plain"), song("boosted", taste = 2.2), song("reduced", taste = 0.5)), now)
        assertEquals(listOf("boosted", "plain", "reduced"), ranked.map { it.facts.songId })
    }

    @Test fun `a blocked song is never a candidate`() {
        val ranked = CloudAutoPrepare.rank(listOf(song("ok"), song("blocked", taste = 2.2, blocked = true)), now)
        assertTrue(ranked.none { it.facts.songId == "blocked" })
    }
}
