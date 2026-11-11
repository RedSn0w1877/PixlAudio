package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Pure rules for keeping the UI position in step with seeks the UI did not make itself: "previous"
 * inside a song (a restart), a seek from the notification, a watch or a car.
 *
 * Why this exists: the progress poll only samples while the player is playing, and a paused seek
 * issued from anywhere but the slider produced no UI update at all, so the mini player and the
 * lyrics bar sat on the old position until play was pressed. The controller does announce every
 * such jump as a position discontinuity; these rules decide what to do with it.
 */
object SeekPositionPolicy {

    /** Seeks (including those the player adjusted to a sync point) move the position by intent. */
    fun isSeekDiscontinuity(reason: Int): Boolean =
        reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT

    /**
     * Whether a held paused-seek override must be dropped because the player jumped somewhere else.
     * An override that agrees with the new position is the UI's own slider seek landing and is left
     * alone (it clears itself on convergence). One that disagrees is stale: keeping it would pin the
     * bar on the old slider target, or on it again when the new position is 0 (a restart).
     */
    fun shouldDropPausedOverride(overrideMs: Long?, newPositionMs: Long, toleranceMs: Long): Boolean =
        overrideMs != null && abs(overrideMs - newPositionMs) > toleranceMs
}
