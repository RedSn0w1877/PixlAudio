package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.data.tais.SingClipWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingClipTimelineTest {
    /** A clip of 57 s to 120 s of the song. */
    private val window = SingClipWindow(57_000, 120_000)

    @Test
    fun `the clip plays at song position minus its start, clamped to the clip`() {
        assertEquals(63_000, window.lengthMs)
        assertEquals(0, window.clipPositionMs(57_000))
        assertEquals(3_500, window.clipPositionMs(60_500))
        assertEquals(0, window.clipPositionMs(10_000), "before the clip: its start")
        assertEquals(63_000, window.clipPositionMs(200_000), "after it: its end")
        assertTrue(window.covers(57_000))
        assertTrue(window.covers(119_999))
        assertFalse(window.covers(120_000))
        assertFalse(window.covers(56_999))
    }

    @Test
    fun `a switch onto the clip needs the song inside it with a few seconds to go before the way back`() {
        assertTrue(SingClipTimeline.canSwitchAt(window, 60_360))
        assertTrue(SingClipTimeline.canSwitchAt(window, 115_000), "3 s before the 118 s return point")
        assertFalse(SingClipTimeline.canSwitchAt(window, 115_001))
        assertFalse(SingClipTimeline.canSwitchAt(window, 56_000), "the song hasn't reached it")
        assertFalse(SingClipTimeline.canSwitchAt(window, 125_000), "the song is past it")
    }

    @Test
    fun `the way back starts at the planned gap after the trigger, and always ends inside the clip`() {
        assertEquals(118_000, SingClipTimeline.returnTriggerMs(window))
        assertEquals(118_000, SingClipTimeline.returnAtMs(window, plannedMs = null, fadeMs = 700))
        assertEquals(118_400, SingClipTimeline.returnAtMs(window, plannedMs = 118_400, fadeMs = 500))
        // A plan past the last safe start is pulled back so the fade ends 100 ms before the clip does.
        assertEquals(119_200, SingClipTimeline.returnAtMs(window, plannedMs = 119_900, fadeMs = 700))
        // One before the trigger waits for it.
        assertEquals(118_000, SingClipTimeline.returnAtMs(window, plannedMs = 90_000, fadeMs = 700))
    }

    @Test
    fun `the clip-end fallback decision`() {
        val returnAt = 118_060L
        assertEquals(ClipAction.STAY, SingClipTimeline.action(window, 100_000, returnAt))
        assertEquals(ClipAction.STAY, SingClipTimeline.action(window, 118_059, returnAt))
        assertEquals(ClipAction.RETURN, SingClipTimeline.action(window, 118_060, returnAt))
        assertEquals(ClipAction.RETURN, SingClipTimeline.action(window, 119_500, returnAt), "late: go now")
        assertEquals(ClipAction.LEAVE_NOW, SingClipTimeline.action(window, 120_000, returnAt), "ran out")
        assertEquals(ClipAction.LEAVE_NOW, SingClipTimeline.action(window, 30_000, returnAt), "a seek before it")
        assertEquals(ClipAction.LEAVE_NOW, SingClipTimeline.action(window, 150_000, returnAt), "a seek past it")
    }

    @Test
    fun `the watch sleeps until shortly before the way back, and looks often near it`() {
        assertEquals(SingClipTimeline.MAX_CHECK_MS, SingClipTimeline.nextCheckDelayMs(60_000, 118_000, 1f))
        assertEquals(468, SingClipTimeline.nextCheckDelayMs(117_500, 118_000, 1f))
        assertEquals(SingClipTimeline.MIN_CHECK_MS, SingClipTimeline.nextCheckDelayMs(117_990, 118_000, 1f))
        assertEquals(SingClipTimeline.MIN_CHECK_MS, SingClipTimeline.nextCheckDelayMs(119_000, 118_000, 1f))
        // At double speed the song gets there in half the time.
        assertEquals(218, SingClipTimeline.nextCheckDelayMs(117_500, 118_000, 2f))
    }

    @Test
    fun `the clip to full swap decision`() {
        // From the vocals, either one fades in at a gap.
        assertEquals(InstrumentalSwitch.CROSSFADE_FROM_ORIGINAL, SingClipTimeline.switchFor(InstrumentalAudible.NONE, wantsClip = true))
        assertEquals(InstrumentalSwitch.CROSSFADE_FROM_ORIGINAL, SingClipTimeline.switchFor(InstrumentalAudible.NONE, wantsClip = false))
        // The full instrumental over the clip: swap at once.
        assertEquals(InstrumentalSwitch.SWAP_CLIP_TO_FULL, SingClipTimeline.switchFor(InstrumentalAudible.CLIP, wantsClip = false))
        assertEquals(InstrumentalSwitch.KEEP, SingClipTimeline.switchFor(InstrumentalAudible.CLIP, wantsClip = true))
        // Never a clip over the full instrumental, nor the full one over itself.
        assertEquals(InstrumentalSwitch.KEEP, SingClipTimeline.switchFor(InstrumentalAudible.FULL, wantsClip = true))
        assertEquals(InstrumentalSwitch.KEEP, SingClipTimeline.switchFor(InstrumentalAudible.FULL, wantsClip = false))
    }
}
