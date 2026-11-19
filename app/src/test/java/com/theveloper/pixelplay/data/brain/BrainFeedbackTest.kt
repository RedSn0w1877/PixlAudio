package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAction
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteChange
import com.theveloper.pixelplay.data.recommendation.TasteOutcome
import com.theveloper.pixelplay.data.recommendation.TasteRequest
import com.theveloper.pixelplay.data.recommendation.TasteSetBy
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.presentation.viewmodel.BrainActionsStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.BrainNotice
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Not for me" and "Less from" (docs/brain-spec.md, 8.5): a mute and a reduce in the existing taste
 * changes, the flag on the play in flight set before the skip, and Undo.
 */
class BrainFeedbackTest {

    private val taste = mockk<TasteAdjustmentRepository>()
    private val tracker = mockk<ListeningStatsTracker>(relaxed = true)
    private val feedback = BrainFeedback(taste, { tracker })

    private val song = Song.emptySong().copy(
        id = "-3000000000042",
        title = "Paint The Town Red",
        artist = "Doja Cat",
        artists = listOf(ArtistRef(id = 1L, name = "Doja Cat", isPrimary = true)),
        spotifyId = "56y1jOTK0XSvJzVv9vHQBK",
    )

    /** The holder with the parts these tests do not look at as relaxed mocks. */
    private fun actionsOf() = BrainActionsStateHolder(
        feedback, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        CoroutineScope(Dispatchers.Unconfined),
    )

    private fun outcome(id: String?) =
        TasteOutcome(emptyList(), listOf(TasteChange(id, "done", changed = id != null)))

    @Test
    fun `not for me is a mute of the song, made by the listener, with no end date`() = runTest {
        val requests = slot<List<TasteRequest>>()
        coEvery { taste.apply(capture(requests), TasteSetBy.USER) } returns outcome("abc12345")

        val result = feedback.mute(song)

        assertEquals("abc12345", result.adjustmentId)
        assertEquals("Doja Cat", result.artistName)
        val request = requests.captured.single()
        assertEquals(TasteAction.MUTE, request.action)
        assertEquals(TasteTarget.SONG, request.target)
        assertEquals(song.id, request.key)
        assertEquals(song.spotifyId, request.altKey)
        assertEquals("Paint The Town Red - Doja Cat", request.label)
        assertNull(request.expiresDays)
    }

    @Test
    fun `the play in flight is flagged`() {
        feedback.flagNotForMe(song.id)
        verify { tracker.markFlag(song.id, EventFlags.NOT_FOR_ME) }
    }

    @Test
    fun `less from an artist is the existing reduce, and nothing for a name that folds to nothing`() = runTest {
        val requests = slot<List<TasteRequest>>()
        coEvery { taste.apply(capture(requests), TasteSetBy.USER) } returns outcome("r1")
        assertEquals("r1", feedback.lessFrom("Doja Cat"))
        val request = requests.captured.single()
        assertEquals(TasteAction.REDUCE, request.action)
        assertEquals(TasteTarget.ARTIST, request.target)
        assertEquals("Doja Cat", request.label)
        assertTrue(request.key.isNotEmpty())

        assertNull(feedback.lessFrom("   "))
        coVerify(exactly = 1) { taste.apply(any(), any()) }
    }

    @Test
    fun `undo removes exactly that change`() = runTest {
        coEvery { taste.remove("abc12345") } returns true
        assertTrue(feedback.undo("abc12345"))
        coVerify { taste.remove("abc12345") }
    }

    @Test
    fun `the artist of a taste change is one name`() {
        assertEquals("Doja Cat", BrainFeedback.artistOf(song))
        // A song rebuilt from a queue item has only a display line.
        val fromQueue = Song.emptySong().copy(id = "q", title = "Kill Bill", artist = "SZA, Doja Cat")
        assertEquals("SZA", BrainFeedback.artistOf(fromQueue))
        assertEquals("", BrainFeedback.artistOf(Song.emptySong().copy(id = "u", title = "T", artist = EngineTrack.UNKNOWN_ARTIST)))
        assertEquals("", BrainFeedback.artistOf(Song.emptySong().copy(id = "u", title = "T", artist = "")))
    }

    @Test
    fun `the flag is set before the skip, and the bar offers undo and less from the artist`() {
        val order = ArrayList<String>()
        every { tracker.markFlag(any(), any()) } answers {
            order.add("flag")
            Unit
        }
        coEvery { taste.apply(any(), TasteSetBy.USER) } returns outcome("abc12345")
        val actions = actionsOf()

        actions.notForMe(song, isPlaying = true) { order.add("skip") }

        assertEquals(listOf("flag", "skip"), order)
        val notice = actions.notice.value
        assertEquals(BrainNotice.Kind.NOT_FOR_ME, notice?.kind)
        assertEquals("abc12345", notice?.adjustmentId)
        assertEquals("Doja Cat", notice?.artistName)
        actions.dismissNotice()
        assertNull(actions.notice.value)
    }

    @Test
    fun `a track that is not playing is muted without a skip, and a refused mute says so`() {
        var skipped = false
        coEvery { taste.apply(any(), TasteSetBy.USER) } returns outcome(null)
        val actions = actionsOf()

        actions.notForMe(song, isPlaying = false) { skipped = true }

        assertTrue(!skipped)
        assertEquals(BrainNotice.Kind.NOT_SAVED, actions.notice.value?.kind)
        assertNull(actions.notice.value?.adjustmentId)
        actions.dismissNotice()
    }

    @Test
    fun `more like this boosts the artist for two weeks, as the listener, and it can be undone like any change`() = runTest {
        val requests = slot<List<TasteRequest>>()
        coEvery { taste.apply(capture(requests), TasteSetBy.USER) } returns outcome("boost001")

        val result = feedback.moreLike(song)

        assertEquals("boost001", result.adjustmentId)
        assertEquals("Doja Cat", result.artistName)
        val request = requests.captured.single()
        assertEquals(TasteAction.BOOST, request.action)
        assertEquals(TasteTarget.ARTIST, request.target)
        assertEquals(BrainFeedback.MORE_LIKE_DAYS, request.expiresDays)
        assertEquals("More like this", request.reason)
        // A track that names nobody has nothing to lean towards.
        val nameless = Song.emptySong().copy(id = "u", title = "T", artist = EngineTrack.UNKNOWN_ARTIST)
        assertNull(feedback.moreLike(nameless).adjustmentId)
    }

    @Test
    fun `less from an artist already reduced offers to mute them instead of stacking another change`() {
        coEvery { taste.list(any()) } returns listOf(
            com.theveloper.pixelplay.data.recommendation.TasteAdjustment(
                id = "r1", kind = com.theveloper.pixelplay.data.recommendation.TasteKind.REDUCE, target = TasteTarget.ARTIST,
                key = com.theveloper.pixelplay.data.recommendation.TasteAdjustments.nameKey("Doja Cat"), label = "Doja Cat",
            )
        )
        val actions = actionsOf()

        actions.lessFrom("Doja Cat")

        assertEquals(BrainNotice.Kind.OFFER_MUTE, actions.notice.value?.kind)
        assertEquals("Doja Cat", actions.notice.value?.artistName)
        coVerify(exactly = 0) { taste.apply(any(), any()) }
        actions.dismissNotice()
    }

    @Test
    fun `the first less from reduces, and the bar then offers mute`() {
        coEvery { taste.list(any()) } returns emptyList()
        val requests = slot<List<TasteRequest>>()
        coEvery { taste.apply(capture(requests), TasteSetBy.USER) } returns outcome("less0001")
        val actions = actionsOf()

        actions.lessFrom("Doja Cat")

        assertEquals(TasteAction.REDUCE, requests.captured.single().action)
        assertEquals(BrainNotice.Kind.LESS_FROM, actions.notice.value?.kind)
        // The bar's second step: mute them.
        coEvery { taste.apply(capture(requests), TasteSetBy.USER) } returns outcome("mute0001")
        actions.muteArtist()
        assertEquals(TasteAction.MUTE, requests.captured.single().action)
        assertEquals(TasteTarget.ARTIST, requests.captured.single().target)
        assertEquals(BrainNotice.Kind.MUTED_ARTIST, actions.notice.value?.kind)
        assertEquals("mute0001", actions.notice.value?.adjustmentId)
        actions.dismissNotice()
    }

    @Test
    fun `a swipe is only noted while the brain is in memory`() {
        val engine = mockk<BrainEngine>(relaxed = true)
        every { engine.isLoaded } returns false
        val identity = mockk<TrackResolver>(relaxed = true)
        val holder = BrainActionsStateHolder(
            feedback, mockk(relaxed = true), engine, identity, mockk(relaxed = true),
            CoroutineScope(Dispatchers.Unconfined),
        )
        holder.swiped(song)
        coVerify(exactly = 0) { identity.resolveTrack(any(), any()) }
    }
}
