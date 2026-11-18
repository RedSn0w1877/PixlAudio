package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The reason table of docs/brain-spec.md, 4.2, and the surface tags the dispatch path writes. */
class PlayReasonsTest {

    private fun reasons(
        reason: Int,
        old: Int = 4,
        new: Int = 5,
        oldSize: Int = 10,
        newSize: Int = 10,
        engine: Boolean = false,
        restoring: Boolean = false,
    ) = PlayReasons.forTransition(reason, old, new, oldSize, newSize, engine, restoring)

    private fun pair(r: PlayReasons.Reasons) = r.end to r.start

    @Test
    fun `an automatic transition is a finished track, and an engine pick starts as autoplay`() {
        assertEquals(EndReason.TRACK_DONE to StartReason.AUTO_ADVANCE, pair(reasons(PlayReasons.TRANSITION_AUTO)))
        assertEquals(EndReason.TRACK_DONE to StartReason.AUTOPLAY, pair(reasons(PlayReasons.TRANSITION_AUTO, engine = true)))
    }

    @Test
    fun `a seek to the next, the previous or any other item`() {
        assertEquals(EndReason.NEXT to StartReason.NEXT, pair(reasons(PlayReasons.TRANSITION_SEEK, old = 4, new = 5)))
        assertEquals(EndReason.PREV to StartReason.PREV, pair(reasons(PlayReasons.TRANSITION_SEEK, old = 4, new = 3)))
        assertEquals(EndReason.PICKED_OTHER to StartReason.TAP, pair(reasons(PlayReasons.TRANSITION_SEEK, old = 4, new = 8)))
        assertEquals(EndReason.PICKED_OTHER to StartReason.TAP, pair(reasons(PlayReasons.TRANSITION_SEEK, old = 4, new = 4)))
        // The old index is unknown (the first play of the process): never guessed as "next".
        assertEquals(EndReason.PICKED_OTHER to StartReason.TAP, pair(reasons(PlayReasons.TRANSITION_SEEK, old = -1, new = 0)))
    }

    @Test
    fun `absolute indices - next inside a 200-item window of a longer queue is still next`() {
        // After a crossfade the player's own index restarts inside its window; the service passes the
        // absolute index (230 to 231), and that is what the table reads.
        assertEquals(EndReason.NEXT to StartReason.NEXT, pair(reasons(PlayReasons.TRANSITION_SEEK, old = 230, new = 231, oldSize = 400, newSize = 400)))
    }

    @Test
    fun `a changed playlist - a new queue, a removed current item, a restore`() {
        assertEquals(EndReason.PICKED_OTHER to StartReason.TAP, pair(reasons(PlayReasons.TRANSITION_PLAYLIST_CHANGED, oldSize = 10, newSize = 30)))
        assertEquals(EndReason.QUEUE_EDIT to StartReason.TAP, pair(reasons(PlayReasons.TRANSITION_PLAYLIST_CHANGED, oldSize = 10, newSize = 9)))
        assertEquals(EndReason.PICKED_OTHER to StartReason.RESTORE, pair(reasons(PlayReasons.TRANSITION_PLAYLIST_CHANGED, oldSize = 0, newSize = 12, restoring = true)))
        // A one-song queue replaced by nothing-but-another is a new queue, not an edit.
        assertEquals(EndReason.PICKED_OTHER, reasons(PlayReasons.TRANSITION_PLAYLIST_CHANGED, oldSize = 1, newSize = 0).end)
    }

    @Test
    fun `repeat is a finished track that starts again by itself`() {
        assertEquals(EndReason.TRACK_DONE to StartReason.AUTO_ADVANCE, pair(reasons(PlayReasons.TRANSITION_REPEAT)))
    }

    @Test
    fun `a crossfade hand-over and an unknown reason`() {
        assertEquals(EndReason.TRACK_DONE to StartReason.AUTOPLAY, pair(PlayReasons.forHandOver(true)))
        assertEquals(EndReason.TRACK_DONE to StartReason.AUTO_ADVANCE, pair(PlayReasons.forHandOver(false)))
        assertEquals(EndReason.UNKNOWN to StartReason.UNKNOWN, pair(reasons(99)))
    }

    @Test
    fun `the reason codes are the ones media3 uses`() {
        assertEquals(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, PlayReasons.TRANSITION_REPEAT)
        assertEquals(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, PlayReasons.TRANSITION_AUTO)
        assertEquals(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, PlayReasons.TRANSITION_SEEK)
        assertEquals(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, PlayReasons.TRANSITION_PLAYLIST_CHANGED)
    }

    @Test
    fun `surface tags from the dispatch path`() {
        val playlist = BrainSurfaces.forQueue("Road trip", "playlist-42")!!
        assertEquals(Surface.nameOf(Surface.PLAYLIST), playlist.surface)
        // The log keeps a hash of the playlist id, never the id.
        assertEquals(BrainSurfaces.hashedId("playlist-42"), playlist.surfaceId)
        assertEquals(false, playlist.surfaceId!!.contains("playlist-42"))
        assertEquals(false, playlist.isEngineOwned)
        assertEquals(Surface.nameOf(Surface.LIKED), BrainSurfaces.forQueue("Liked Songs", null)!!.surface)
        assertEquals(Surface.nameOf(Surface.YOUR_MIX), BrainSurfaces.forQueue("Your Mix", null)!!.surface)
        assertEquals(Surface.nameOf(Surface.DAILY_MIX), BrainSurfaces.forQueue("Daily Mix 2", null)!!.surface)
        assertEquals(Surface.nameOf(Surface.ASSISTANT), BrainSurfaces.forQueue("Taizo", null)!!.surface)
        assertNull(BrainSurfaces.forQueue("Abbey Road", null))
        assertNull(BrainSurfaces.forQueue(null, null))
        assertEquals(Surface.nameOf(Surface.MANUAL_QUEUE), BrainSurfaces.manualQueue.surface)
    }

    @Test
    fun `a cancelled hand-over is not a finished play`() {
        // The engine publishes the player of the track the fade was leaving: the fade was cancelled.
        org.junit.jupiter.api.Assertions.assertTrue(PlayReasons.isCancelledHandOver(leavingMediaId = "old", publishedMediaId = "old"))
        // The normal end of a fade publishes the incoming track; no fade running publishes anything.
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.isCancelledHandOver(leavingMediaId = "old", publishedMediaId = "new"))
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.isCancelledHandOver(leavingMediaId = null, publishedMediaId = "old"))
        // The second or two of the incoming track ends with no reason, which weighs nothing (4.3).
        assertEquals(EndReason.UNKNOWN to StartReason.UNKNOWN, pair(PlayReasons.forCancelledHandOver()))
    }

    @Test
    fun `a queue rebuilt around the playing track is the same play going on`() {
        val changed = PlayReasons.TRANSITION_PLAYLIST_CHANGED
        // Shuffle switched off: the same track, the same position.
        org.junit.jupiter.api.Assertions.assertTrue(PlayReasons.continuesSamePlay(changed, "a", "a", 61_000L, 61_050L))
        // The same song tapped again starts from the top: a new play.
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.continuesSamePlay(changed, "a", "a", 61_000L, 0L))
        // Another song, an unknown one, or another kind of transition.
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.continuesSamePlay(changed, "a", "b", 61_000L, 61_000L))
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.continuesSamePlay(changed, null, "a", 0L, 0L))
        org.junit.jupiter.api.Assertions.assertFalse(PlayReasons.continuesSamePlay(PlayReasons.TRANSITION_SEEK, "a", "a", 61_000L, 61_000L))
    }
}
