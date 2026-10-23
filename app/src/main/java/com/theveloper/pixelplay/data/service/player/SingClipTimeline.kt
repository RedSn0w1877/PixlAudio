package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.data.tais.SingClipWindow
import java.io.File

/**
 * A Sing preview clip for [InstrumentalCrossfadeController]: the clip's file, where it sits in the song, and where the
 * fade back to the vocals starts before it runs out ([returnAtMs], song time; null: at [SingClipTimeline.returnTriggerMs])
 * and how long that fade is.
 */
data class SingClipSource(
    val file: File,
    val window: SingClipWindow,
    val returnAtMs: Long? = null,
    /** The longest fade ([CrossfadeRamp.DEFAULT_FADE_MS]) unless the gap planner chose another. */
    val returnFadeMs: Long = 700L,
)

/** What the instrumental player is audible on now. */
enum class InstrumentalAudible { NONE, CLIP, FULL }

/** What a request for an instrumental does, given what is audible ([SingClipTimeline.switchFor]). */
enum class InstrumentalSwitch {
    /** From the vocals: the usual fade at a gap between sung words. */
    CROSSFADE_FROM_ORIGINAL,

    /** The full instrumental arrived while the clip plays: instrumental to instrumental, a short fade at once. */
    SWAP_CLIP_TO_FULL,

    /** Already where it should be (a clip is never put over an instrumental that is already playing). */
    KEEP,
}

/** What the clip watch does at a song position ([SingClipTimeline.action]). */
enum class ClipAction {
    STAY,

    /** Time to fade back to the vocals (the clip ends soon, and the full instrumental isn't here yet). */
    RETURN,

    /** The song is outside the clip (a seek, or it ran past it): back to the vocals at once. */
    LEAVE_NOW,
}

/**
 * The timing rules of a Sing preview clip on the player side (pure, unit-tested). The clip holds the song's instrumental
 * for [SingClipWindow.startMs]..[SingClipWindow.endMs]; while it is audible the instrumental player plays the clip at
 * song position minus the clip's start, and every seek and resync goes through [SingClipWindow.clipPositionMs].
 */
object SingClipTimeline {
    /** The fade back to the vocals is planned from this long before the clip's end. */
    const val END_MARGIN_MS = 2_000L

    /** A fade back must end at least this long before the clip runs out. */
    const val END_SAFETY_MS = 100L

    /** Switching onto a clip is only worth it with at least this much of it left before the fade back. */
    const val MIN_PLAY_MS = 3_000L

    /** The fade from the clip to the full instrumental (instrumental to instrumental: short, no gap needed). */
    const val SWAP_FADE_MS = 300L

    /** The clip watch looks at the song at least this often near the return point, at most this rarely before it. */
    const val MIN_CHECK_MS = 16L
    const val MAX_CHECK_MS = 1_000L

    /** When the fade back to the vocals is planned from (the gap search starts here). */
    fun returnTriggerMs(window: SingClipWindow): Long = window.endMs - END_MARGIN_MS

    /**
     * Where the fade back starts: the planned point (a gap after the trigger), kept between the trigger and the last
     * moment a fade of [fadeMs] still ends inside the clip.
     */
    fun returnAtMs(window: SingClipWindow, plannedMs: Long?, fadeMs: Long): Long {
        val trigger = returnTriggerMs(window)
        val latest = (window.endMs - fadeMs - END_SAFETY_MS).coerceAtLeast(trigger)
        return (plannedMs ?: trigger).coerceIn(trigger, latest)
    }

    /** Whether a switch onto the clip can start at song position [startAtMs]: inside it, with enough of it left. */
    fun canSwitchAt(window: SingClipWindow, startAtMs: Long): Boolean =
        startAtMs >= window.startMs && startAtMs + MIN_PLAY_MS <= returnTriggerMs(window)

    /** The clip watch's decision at song position [songMs], with the fade back planned at [returnAtMs]. */
    fun action(window: SingClipWindow, songMs: Long, returnAtMs: Long): ClipAction = when {
        !window.covers(songMs) -> ClipAction.LEAVE_NOW
        songMs >= returnAtMs -> ClipAction.RETURN
        else -> ClipAction.STAY
    }

    /** How long the clip watch can sleep: until shortly before the return point (at [speed]), within its bounds. */
    fun nextCheckDelayMs(songMs: Long, returnAtMs: Long, speed: Float): Long {
        val remaining = (returnAtMs - songMs).coerceAtLeast(0)
        val wall = (remaining / speed.coerceAtLeast(0.1f)).toLong() - 2 * MIN_CHECK_MS
        return wall.coerceIn(MIN_CHECK_MS, MAX_CHECK_MS)
    }

    /** What a request for the full instrumental ([wantsClip] false) or a clip does while [audible] plays. */
    fun switchFor(audible: InstrumentalAudible, wantsClip: Boolean): InstrumentalSwitch = when (audible) {
        InstrumentalAudible.NONE -> InstrumentalSwitch.CROSSFADE_FROM_ORIGINAL
        InstrumentalAudible.CLIP -> if (wantsClip) InstrumentalSwitch.KEEP else InstrumentalSwitch.SWAP_CLIP_TO_FULL
        InstrumentalAudible.FULL -> InstrumentalSwitch.KEEP
    }
}
